package com.david.photopriv.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.david.photopriv.PhotoPrivApp
import com.david.photopriv.data.dao.PhotoDao
import com.david.photopriv.data.model.BackupStatus
import com.david.photopriv.data.model.ExtractionSession
import com.david.photopriv.data.model.TrackedPhoto
import com.david.photopriv.data.vault.StagingVaultManager
import com.david.photopriv.network.MailSenderHelper
import com.david.photopriv.network.NetworkMonitor
import com.david.photopriv.network.TelegramSenderHelper
import com.david.photopriv.service.ExtractionSentinelService
import com.david.photopriv.service.MediaStoreScanner
import com.david.photopriv.service.PhotoBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PhotoRepository(
    private val photoDao: PhotoDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "PhotoRepository"
    }

    private val scanMutex = Mutex()
    private val uploadMutex = Mutex()
    private val inFlightUploads = ConcurrentHashMap.newKeySet<Long>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeSessionFlow: Flow<ExtractionSession?> = photoDao.getActiveSessionFlow()
    val latestSessionFlow: Flow<ExtractionSession?> = photoDao.getLatestSessionFlow()

    fun getPhotosForSession(sessionId: Long): Flow<List<TrackedPhoto>> {
        return photoDao.getPhotosForSession(sessionId)
    }

    suspend fun getActiveSession(): ExtractionSession? = withContext(Dispatchers.IO) {
        photoDao.getActiveSession()
    }

    suspend fun startExtractionSession(): Long = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000
        photoDao.closeAllActiveSessions(nowSeconds)

        val newSession = ExtractionSession(
            startTime = nowSeconds,
            isActive = true
        )
        val sessionId = photoDao.insertSession(newSession)

        // Limpiar cualquier estado inconsistente previo
        photoDao.resetIncompleteUploads(sessionId)

        // Iniciar servicio foreground centinela
        val serviceIntent = Intent(context, ExtractionSentinelService::class.java).apply {
            action = ExtractionSentinelService.ACTION_START
            putExtra(ExtractionSentinelService.EXTRA_SESSION_ID, sessionId)
            putExtra(ExtractionSentinelService.EXTRA_START_TIME, nowSeconds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        Log.d(TAG, "Sesión de extracción $sessionId iniciada a las $nowSeconds")
        sessionId
    }

    suspend fun stopExtractionSession() = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000
        photoDao.closeAllActiveSessions(nowSeconds)

        // Detener servicio centinela
        val serviceIntent = Intent(context, ExtractionSentinelService::class.java).apply {
            action = ExtractionSentinelService.ACTION_STOP
        }
        context.startService(serviceIntent)

        // Purgar bóveda secreta si no quedan archivos pendientes
        val active = photoDao.getActiveSession()
        if (active == null) {
            val pendingCount = photoDao.getPendingBackupPhotos(nowSeconds).size
            if (pendingCount == 0) {
                StagingVaultManager.clearVault(context)
            }
        }

        Log.d(TAG, "Sesión de extracción detenida.")
    }

    /**
     * Escanea el almacenamiento público buscando fotos recién añadidas desde el inicio de la sesión.
     * Protegido contra llamadas simultáneas mediante Mutex.
     * Inmediatamente aísla los archivos en la bóveda secreta para que el usuario pueda re-bloquearlos
     * en Google Fotos sin esperar a que termine la subida.
     */
    suspend fun scanAndProcessNewPhotos(sessionId: Long, sessionStartTime: Long): List<TrackedPhoto> =
        withContext(Dispatchers.IO) {
            if (!scanMutex.tryLock()) {
                return@withContext emptyList()
            }
            try {
                val existingIds = photoDao.getTrackedMediaIdsForSession(sessionId).toSet()
                val newPhotos = MediaStoreScanner.scanForExtractedPhotos(
                    context,
                    sessionStartTime,
                    sessionId,
                    existingIds
                )

                if (newPhotos.isNotEmpty()) {
                    Log.d(TAG, "Detectadas ${newPhotos.size} nuevas fotos/videos. Resguardando de inmediato en bóveda secreta...")

                    // Aislamiento inmediato en almacenamiento interno privado (invisible para galería)
                    val stagedPhotos = newPhotos.map { photo ->
                        val uri = Uri.parse(photo.uriString)
                        val stagedFile = StagingVaultManager.stageMedia(context, photo.mediaStoreId, photo.displayName, uri)
                        if (stagedFile != null) {
                            photo.copy(
                                localStagingPath = stagedFile.absolutePath,
                                fileSizeBytes = stagedFile.length().takeIf { it > 0 } ?: photo.fileSizeBytes
                            )
                        } else {
                            photo
                        }
                    }

                    photoDao.insertPhotos(stagedPhotos)

                    val app = context.applicationContext as? PhotoPrivApp
                    val telegram = app?.settingsManager?.getTelegramConfig()
                    val smtp = app?.settingsManager?.getSmtpConfig()
                    val canAutoSend = (telegram?.isConfigured == true && telegram.autoSendImmediately && telegram.enabled) ||
                                      (smtp?.isConfigured == true && smtp.autoSendImmediately)
                    if (canAutoSend) {
                        dispatchImmediateUpload(sessionId)
                    }
                }
                newPhotos
            } finally {
                scanMutex.unlock()
            }
        }

    /**
     * Despacha la cola de subida con control estricto de concurrencia y prevención de duplicados:
     * 1. Solo UN bucle de procesamiento puede correr a la vez (Mutex).
     * 2. Si ya hay uno activo, no se crean corrutinas duplicadas; el bucle activo procesará
     *    los nuevos elementos de forma secuencial al terminar el archivo en curso.
     * 3. Registra en memoria (inFlightUploads) cada archivo para que ningún hilo o reintento lo tome dos veces.
     */
    fun dispatchImmediateUpload(sessionId: Long) {
        repositoryScope.launch {
            if (!uploadMutex.tryLock()) {
                Log.d(TAG, "Procesador de subida ya en marcha. Los nuevos archivos serán procesados en orden.")
                return@launch
            }
            try {
                processUploadQueue(sessionId)
            } finally {
                uploadMutex.unlock()
            }
        }
    }

    private suspend fun processUploadQueue(sessionId: Long) {
        val app = context.applicationContext as? PhotoPrivApp ?: return
        val telegramConfig = app.settingsManager.getTelegramConfig()
        val smtpConfig = app.settingsManager.getSmtpConfig()
        val appPrefs = app.settingsManager.getAppPreferences()

        while (true) {
            if (!NetworkMonitor.isInternetAvailable(context)) {
                Log.w(TAG, "Sin conexión a internet disponible. Pausando subidas.")
                break
            }

            // Consultar únicamente archivos que están en estado PENDING (fotos primero, videos después)
            val pendingList = photoDao.getPendingBackupPhotos(sessionId)
            if (pendingList.isEmpty()) {
                Log.d(TAG, "No hay más archivos pendientes en la cola de subida. Destruyendo bóveda secreta...")
                StagingVaultManager.clearVault(context)
                break
            }

            for (media in pendingList) {
                // 1. Doble chequeo en BD: si ya fue subido exitosamente, ignorar
                val freshPhoto = photoDao.getPhotoById(media.mediaStoreId) ?: continue
                if (freshPhoto.backupStatus == BackupStatus.BACKED_UP) {
                    continue
                }

                // 2. Verificación en memoria: si ya está en vuelo, ignorar
                if (!inFlightUploads.add(media.mediaStoreId)) {
                    continue
                }

                try {
                    var targetMedia = freshPhoto
                    val uri = Uri.parse(targetMedia.uriString)

                    // Si por alguna razón no estaba en bóveda, intentar resguardarlo ahora
                    val hasStaged = targetMedia.localStagingPath?.let { File(it).exists() } == true
                    if (!hasStaged) {
                        val staged = StagingVaultManager.stageMedia(context, targetMedia.mediaStoreId, targetMedia.displayName, uri)
                        if (staged != null) {
                            photoDao.updateLocalStagingPath(targetMedia.mediaStoreId, staged.absolutePath)
                            targetMedia = targetMedia.copy(
                                localStagingPath = staged.absolutePath,
                                fileSizeBytes = staged.length().takeIf { it > 0 } ?: targetMedia.fileSizeBytes
                            )
                        }
                    }

                    // Si es un video, comprobar si el archivo público aún existe y creció en tamaño
                    // (por si Google Fotos seguía descifrando/escribiendo cuando se detectó inicialmente)
                    if (targetMedia.isVideo) {
                        val publicSize = MediaStoreScanner.getActualFileSize(context, uri)
                        val currentStagedSize = targetMedia.localStagingPath?.let { File(it).length() } ?: 0L
                        if (publicSize > currentStagedSize && MediaStoreScanner.isVideoFullyWritten(context, uri)) {
                            Log.d(TAG, "El video ${targetMedia.displayName} creció en disco ($publicSize bytes vs $currentStagedSize en bóveda). Re-aislando completo...")
                            StagingVaultManager.deleteStagedFile(targetMedia.localStagingPath)
                            val newStaged = StagingVaultManager.stageMedia(context, targetMedia.mediaStoreId, targetMedia.displayName, uri)
                            if (newStaged != null) {
                                photoDao.updateLocalStagingPath(targetMedia.mediaStoreId, newStaged.absolutePath)
                                photoDao.updateFileSize(targetMedia.mediaStoreId, newStaged.length())
                                targetMedia = targetMedia.copy(
                                    localStagingPath = newStaged.absolutePath,
                                    fileSizeBytes = newStaged.length()
                                )
                            }
                        } else if (publicSize > 0L && publicSize != targetMedia.fileSizeBytes) {
                            photoDao.updateFileSize(targetMedia.mediaStoreId, publicSize)
                            targetMedia = targetMedia.copy(fileSizeBytes = publicSize)
                        }
                    }

                    photoDao.updateBackupStatus(
                        targetMedia.mediaStoreId,
                        BackupStatus.BACKING_UP,
                        error = if (targetMedia.isVideo) "Enviando video..." else null
                    )

                    // 3. Envío prioritario vía Telegram
                    if (telegramConfig.isConfigured && telegramConfig.enabled) {
                        // Pausa de 350ms para respetar límites de tasa (HTTP 429) de Telegram
                        delay(350)

                        var attempts = 0
                        var result: TelegramSenderHelper.SendResult? = null
                        val maxAttempts = 3

                        while (attempts < maxAttempts) {
                            attempts++
                            result = TelegramSenderHelper.sendSingleMedia(
                                context = context,
                                config = telegramConfig,
                                photo = targetMedia,
                                dataSaverMode = if (targetMedia.isVideo) false else appPrefs.dataSaverMode,
                                deviceName = appPrefs.deviceName,
                                onPartProgress = { part, total ->
                                    photoDao.updateBackupStatus(
                                        targetMedia.mediaStoreId,
                                        BackupStatus.BACKING_UP,
                                        error = "Enviando clip $part de $total..."
                                    )
                                }
                            )

                            if (result is TelegramSenderHelper.SendResult.Success) {
                                break
                            }

                            if (attempts < maxAttempts) {
                                val backoffMs = attempts * 2000L
                                Log.w(TAG, "Intento $attempts/$maxAttempts falló para ${targetMedia.displayName}. Reintentando en ${backoffMs}ms...")
                                photoDao.updateBackupStatus(
                                    targetMedia.mediaStoreId,
                                    BackupStatus.BACKING_UP,
                                    error = "Reintentando auto (${attempts + 1}/$maxAttempts)..."
                                )
                                delay(backoffMs)
                            }
                        }

                        val now = System.currentTimeMillis()
                        when (result) {
                            is TelegramSenderHelper.SendResult.Success -> {
                                Log.d(TAG, "Subida exitosa a Telegram: ${targetMedia.displayName}. Destruyendo copia en bóveda...")
                                StagingVaultManager.deleteStagedFile(targetMedia.localStagingPath)
                                photoDao.updateLocalStagingPath(targetMedia.mediaStoreId, null)
                                photoDao.updateBackupStatus(targetMedia.mediaStoreId, BackupStatus.BACKED_UP, timestamp = now)
                            }
                            is TelegramSenderHelper.SendResult.Error -> {
                                Log.e(TAG, "Error persistente subiendo ${targetMedia.displayName} tras $attempts intentos: ${result.message}")
                                photoDao.updateBackupStatus(targetMedia.mediaStoreId, BackupStatus.FAILED, error = result.message)
                            }
                            null -> {
                                photoDao.updateBackupStatus(targetMedia.mediaStoreId, BackupStatus.FAILED, error = "Fallo desconocido")
                            }
                        }
                    } else if (smtpConfig.isConfigured) {
                        // Envío por correo SMTP
                        val result = MailSenderHelper.sendPhotosEmail(context, smtpConfig, listOf(targetMedia))
                        val now = System.currentTimeMillis()
                        when (result) {
                            is MailSenderHelper.SendResult.Success -> {
                                StagingVaultManager.deleteStagedFile(targetMedia.localStagingPath)
                                photoDao.updateLocalStagingPath(targetMedia.mediaStoreId, null)
                                photoDao.updateBackupStatus(targetMedia.mediaStoreId, BackupStatus.BACKED_UP, timestamp = now)
                            }
                            is MailSenderHelper.SendResult.Error -> {
                                photoDao.updateBackupStatus(targetMedia.mediaStoreId, BackupStatus.FAILED, error = result.message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Excepción inesperada subiendo ${media.displayName}: ${e.message}", e)
                    photoDao.updateBackupStatus(media.mediaStoreId, BackupStatus.FAILED, error = e.message)
                } finally {
                    inFlightUploads.remove(media.mediaStoreId)
                }
            }
        }
    }

    /**
     * Reintenta el envío de un único archivo específico sin reenviar los que ya están en BACKED_UP.
     */
    suspend fun retrySinglePhoto(mediaStoreId: Long) = withContext(Dispatchers.IO) {
        val photo = photoDao.getPhotoById(mediaStoreId) ?: return@withContext
        inFlightUploads.remove(mediaStoreId)
        photoDao.updateBackupStatus(mediaStoreId, BackupStatus.PENDING, error = null)
        Log.d(TAG, "Reintentando archivo individual: ${photo.displayName}")
        dispatchImmediateUpload(photo.sessionId)
    }

    /**
     * Elimina / descarta un archivo de la cola de subida y del panel de control
     * para desatorar el sistema si se queda pegado o si el usuario no desea enviarlo.
     */
    suspend fun dismissPhoto(mediaStoreId: Long) = withContext(Dispatchers.IO) {
        val photo = photoDao.getPhotoById(mediaStoreId)
        photo?.localStagingPath?.let { path ->
            StagingVaultManager.deleteStagedFile(path)
        }
        inFlightUploads.remove(mediaStoreId)
        photoDao.deletePhoto(mediaStoreId)
        Log.d(TAG, "Archivo $mediaStoreId descartado y eliminado de la cola por el usuario.")

        // Despachar inmediatamente el siguiente archivo en cola para que no se detenga el flujo
        val active = photoDao.getActiveSession()
        if (active != null) {
            dispatchImmediateUpload(active.sessionId)
        }
    }

    /**
     * Descarta todos los archivos fallidos de la sesión.
     */
    suspend fun dismissFailedPhotos(sessionId: Long) = withContext(Dispatchers.IO) {
        val failedPhotos = photoDao.getPendingOrFailedPhotos(sessionId)
        for (p in failedPhotos) {
            if (p.backupStatus == BackupStatus.FAILED) {
                p.localStagingPath?.let { StagingVaultManager.deleteStagedFile(it) }
                inFlightUploads.remove(p.mediaStoreId)
            }
        }
        photoDao.deleteFailedPhotos(sessionId)
        Log.d(TAG, "Todos los archivos fallidos de la sesión $sessionId han sido descartados.")
    }

    /**
     * Reintenta el envío únicamente de los archivos que fallaron, sin reenviar los que ya están en BACKED_UP.
     */
    suspend fun retryFailedUploads(sessionId: Long) = withContext(Dispatchers.IO) {
        val failedPhotos = photoDao.getPendingOrFailedPhotos(sessionId)
        var resetCount = 0
        for (photo in failedPhotos) {
            if (photo.backupStatus == BackupStatus.FAILED) {
                photoDao.updateBackupStatus(photo.mediaStoreId, BackupStatus.PENDING, error = null)
                resetCount++
            }
        }
        Log.d(TAG, "Reintentando $resetCount archivo(s) fallido(s).")
        dispatchImmediateUpload(sessionId)
    }

    /**
     * Verifica si las fotos expuestas ya no están en el almacenamiento público
     * (lo que significa que han regresado a la Carpeta Bloqueada o se han borrado).
     */
    suspend fun checkProtectionStatus(sessionId: Long): Int = withContext(Dispatchers.IO) {
        val photos = photoDao.getPhotosForSessionSync(sessionId)
        var newlyProtectedCount = 0

        for (photo in photos) {
            if (!photo.isReProtected) {
                val stillExists = MediaStoreScanner.checkPhotoStillExistsInPublicStorage(context, photo)
                if (!stillExists) {
                    photoDao.markAsProtected(photo.mediaStoreId)
                    newlyProtectedCount++
                    Log.d(TAG, "Foto ${photo.displayName} confirmada como re-protegida.")
                }
            }
        }
        newlyProtectedCount
    }

    fun triggerBackupWorker(sessionId: Long) {
        val workData = Data.Builder()
            .putLong(PhotoBackupWorker.KEY_SESSION_ID, sessionId)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupWork = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
            .setInputData(workData)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "PhotoBackupWorker_$sessionId",
            ExistingWorkPolicy.KEEP,
            backupWork
        )
    }
}

