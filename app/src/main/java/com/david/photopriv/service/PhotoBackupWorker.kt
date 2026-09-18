package com.david.photopriv.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.david.photopriv.PhotoPrivApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PhotoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_SESSION_ID = "key_session_id"
        private const val TAG = "PhotoBackupWorker"
        private const val WORK_NAME_MEDIA_WATCHER = "PhotoPriv_MediaWatcher"

        /**
         * Registra un vigilante a nivel de sistema operativo (WorkManager + JobScheduler)
         * que se activa de inmediato cuando MediaStore detecta una nueva imagen o video,
         * incluso si la app estaba suspendida por el Doze Mode o el sistema de Motorola.
         */
        fun scheduleMediaWatcher(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setTriggerContentUpdateDelay(1000, TimeUnit.MILLISECONDS)
                            setTriggerContentMaxDelay(3000, TimeUnit.MILLISECONDS)
                        }
                    }
                    .build()

                val request = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_MEDIA_WATCHER,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                Log.d(TAG, "Vigilante de MediaStore en segundo plano registrado exitosamente vía WorkManager.")
            } catch (e: Exception) {
                Log.e(TAG, "Error registrando vigilante MediaStore en WorkManager: ${e.message}", e)
            }
        }

        fun cancelMediaWatcher(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_MEDIA_WATCHER)
                Log.d(TAG, "Vigilante de MediaStore cancelado.")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelando vigilante MediaStore: ${e.message}", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? PhotoPrivApp ?: return@withContext Result.failure()
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        val activeSession = if (sessionId != -1L) {
            app.database.photoDao().getActiveSession()?.takeIf { it.sessionId == sessionId }
        } else {
            app.database.photoDao().getActiveSession()
        }

        if (activeSession == null) {
            Log.d(TAG, "No hay sesión activa para respaldar.")
            return@withContext Result.success()
        }

        Log.d(TAG, "WorkManager despertado por MediaStore/trigger para la sesión ${activeSession.sessionId}. Procesando fotos...")

        try {
            // 1. Escanear y aislar cualquier nueva foto tomada con la cámara u otra app
            app.repository.scanAndProcessNewPhotos(activeSession.sessionId, activeSession.startTime)

            // 2. Despachar subida inmediata de los archivos y reintentar fallidos si hay conexión
            if (com.david.photopriv.network.NetworkMonitor.isInternetAvailable(applicationContext)) {
                app.repository.retryFailedUploads(activeSession.sessionId)
            }
            app.repository.dispatchImmediateUpload(activeSession.sessionId)

            // 3. Asegurar que el Foreground Service Centinela esté corriendo
            val serviceIntent = Intent(applicationContext, ExtractionSentinelService::class.java).apply {
                action = ExtractionSentinelService.ACTION_START
                putExtra(ExtractionSentinelService.EXTRA_SESSION_ID, activeSession.sessionId)
                putExtra(ExtractionSentinelService.EXTRA_START_TIME, activeSession.startTime)
            }
            try {
                ContextCompat.startForegroundService(applicationContext, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando servicio centinela desde worker: ${e.message}")
            }

            // 4. Re-enganchar el vigilante para la siguiente foto o video
            scheduleMediaWatcher(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error durante ejecución de PhotoBackupWorker: ${e.message}", e)
        }

        Result.success()
    }
}
