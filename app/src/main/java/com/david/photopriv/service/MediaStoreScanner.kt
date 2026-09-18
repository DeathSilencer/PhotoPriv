package com.david.photopriv.service

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.david.photopriv.data.model.BackupStatus
import com.david.photopriv.data.model.TrackedPhoto

object MediaStoreScanner {

    private const val TAG = "MediaStoreScanner"
    const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"

    /**
     * Consulta MediaStore buscando fotos y videos añadidos a partir de [sessionStartTimeSeconds].
     * Se filtran aquellos que ya están registrados en [alreadyTrackedIds].
     */
    fun scanForExtractedPhotos(
        context: Context,
        sessionStartTimeSeconds: Long,
        sessionId: Long,
        alreadyTrackedIds: Set<Long>
    ): List<TrackedPhoto> {
        val newMedia = mutableListOf<TrackedPhoto>()
        // Margen de seguridad de 30 segundos por posibles desfases leves de reloj del sistema o apertura previa de la cámara
        val threshold = (sessionStartTimeSeconds - 30).coerceAtLeast(0L)

        // 1. Escanear Imágenes
        newMedia.addAll(
            queryMediaUri(
                context = context,
                baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                sessionStartTimeSeconds = threshold,
                sessionId = sessionId,
                alreadyTrackedIds = alreadyTrackedIds,
                isVideo = false
            )
        )

        // 2. Escanear Videos
        newMedia.addAll(
            queryMediaUri(
                context = context,
                baseUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                sessionStartTimeSeconds = threshold,
                sessionId = sessionId,
                alreadyTrackedIds = alreadyTrackedIds,
                isVideo = true
            )
        )

        return newMedia
    }

    private fun queryMediaUri(
        context: Context,
        baseUri: Uri,
        sessionStartTimeSeconds: Long,
        sessionId: Long,
        alreadyTrackedIds: Set<Long>,
        isVideo: Boolean
    ): List<TrackedPhoto> {
        val results = mutableListOf<TrackedPhoto>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.IS_PENDING)
            }
        }.toTypedArray()

        // Filtro robusto: captura archivos creados, modificados o tomados en la sesión activa
        // (esencial para fotos tomadas con la cámara que guardan DATE_TAKEN o DATE_MODIFIED)
        val thresholdSec = sessionStartTimeSeconds
        val thresholdMs = sessionStartTimeSeconds * 1000L
        val selection = "(${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns.DATE_MODIFIED} >= ? OR ${MediaStore.MediaColumns.DATE_TAKEN} >= ?)"
        val selectionArgs = arrayOf(thresholdSec.toString(), thresholdSec.toString(), thresholdMs.toString())
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                baseUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                parseCursorIntoResults(
                    cursor = cursor,
                    results = results,
                    baseUri = baseUri,
                    sessionId = sessionId,
                    alreadyTrackedIds = alreadyTrackedIds,
                    isVideo = isVideo,
                    sessionStartTimeSeconds = sessionStartTimeSeconds,
                    context = context
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando MediaStore con filtro ($baseUri): ${e.message}", e)
        }

        // Respaldo de seguridad: si no se detectaron archivos mediante la consulta filtrada
        // (por diferencias de sintaxis SQL en MediaProvider de Android 14 o desfase de reloj),
        // consultar los últimos 40 archivos de la galería y filtrar directamente en Kotlin.
        if (results.isEmpty()) {
            try {
                context.contentResolver.query(
                    baseUri,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    parseCursorIntoResults(
                        cursor = cursor,
                        results = results,
                        baseUri = baseUri,
                        sessionId = sessionId,
                        alreadyTrackedIds = alreadyTrackedIds,
                        isVideo = isVideo,
                        sessionStartTimeSeconds = sessionStartTimeSeconds,
                        context = context,
                        filterByTimestamp = true,
                        maxItemsToInspect = 40
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en consulta de respaldo MediaStore ($baseUri): ${e.message}", e)
            }
        }

        return results
    }

    private fun parseCursorIntoResults(
        cursor: android.database.Cursor,
        results: MutableList<TrackedPhoto>,
        baseUri: Uri,
        sessionId: Long,
        alreadyTrackedIds: Set<Long>,
        isVideo: Boolean,
        sessionStartTimeSeconds: Long,
        context: Context,
        filterByTimestamp: Boolean = false,
        maxItemsToInspect: Int = Int.MAX_VALUE
    ) {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateAddedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val dateTakenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val dateModifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val isPendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
        } else -1

        var inspected = 0
        val thresholdSec = (sessionStartTimeSeconds - 60).coerceAtLeast(0L)
        val thresholdMs = thresholdSec * 1000L

        while (cursor.moveToNext() && inspected < maxItemsToInspect) {
            inspected++
            val id = cursor.getLong(idCol)
            if (alreadyTrackedIds.contains(id) || results.any { it.mediaStoreId == id }) {
                continue
            }

            val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else sessionStartTimeSeconds
            val dateModified = if (dateModifiedCol >= 0) cursor.getLong(dateModifiedCol) else 0L
            val rawDateTaken = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L

            if (filterByTimestamp) {
                val isRecent = dateAdded >= thresholdSec ||
                               dateModified >= thresholdSec ||
                               rawDateTaken >= thresholdMs
                if (!isRecent) continue
            }

            // Si el archivo todavía se está escribiendo (IS_PENDING = 1), esperar al siguiente ciclo
            if (isPendingCol >= 0 && cursor.getInt(isPendingCol) == 1) {
                Log.d(TAG, "Archivo $id aún en escritura (IS_PENDING = 1). Se procesará al finalizar.")
                continue
            }

            val prefix = if (isVideo) "Video_" else "Foto_"
            val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "$prefix$id" else "$prefix$id"
            var size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
            val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg" else if (isVideo) "video/mp4" else "image/jpeg"
            val contentUri = ContentUris.withAppendedId(baseUri, id)

            // Si el tamaño reportado en la BD es 0 o negativo, consultar el tamaño real en disco
            if (size <= 0L) {
                size = try {
                    context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                        val stat = pfd.statSize
                        if (stat > 0L) {
                            stat
                        } else {
                            java.io.FileInputStream(pfd.fileDescriptor).channel.size()
                        }
                    } ?: 0L
                } catch (e: Exception) { 0L }

                if (size <= 0L) {
                    size = try {
                        context.contentResolver.openInputStream(contentUri)?.use { stream ->
                            val buffer = ByteArray(8192)
                            val read = stream.read(buffer)
                            if (read > 0) read.toLong() else 0L
                        } ?: 0L
                    } catch (e: Exception) { 0L }
                }

                if (size <= 0L) {
                    Log.d(TAG, "Archivo $id ($name) aún sin bytes en disco. Se procesará en el siguiente ciclo.")
                    continue
                }
            }

            // Para videos: verificar si está listo para no tomar archivos a medio escribir
            if (isVideo) {
                val isReady = isVideoFullyWritten(context, contentUri)
                if (!isReady) {
                    Log.d(TAG, "Video $id ($name) aún en escritura/descifrado. Se esperará al siguiente ciclo.")
                    continue
                }
            }

            val dateTaken = if (rawDateTaken > 0L) rawDateTaken else dateAdded * 1000L

            results.add(
                TrackedPhoto(
                    mediaStoreId = id,
                    sessionId = sessionId,
                    uriString = contentUri.toString(),
                    displayName = name,
                    dateAdded = dateAdded,
                    dateTaken = dateTaken,
                    fileSizeBytes = size,
                    isVideo = isVideo,
                    mimeType = mimeType,
                    backupStatus = BackupStatus.PENDING,
                    isReProtected = false
                )
            )
        }
    }

    /**
     * Obtiene el tamaño real en bytes de un archivo multimedia desde su URI.
     */
    fun getActualFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Verifica si un video ha terminado completamente de escribirse y descifrarse en el almacenamiento público.
     * Si Google Fotos aún está escribiendo fotogramas, la cabecera MP4 (moov atom) no será válida o la duración será 0.
     */
    fun isVideoFullyWritten(context: Context, uri: Uri): Boolean {
        val size = getActualFileSize(context, uri)
        if (size <= 0L) return false

        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val hasVideo = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            retriever.release()

            val durationMs = durationStr?.toLongOrNull() ?: 0L
            durationMs > 0L && hasVideo == "yes"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifica si una foto o video sigue existiendo en el almacenamiento público.
     * Si fue movido a la Carpeta Bloqueada o eliminado de la galería, ya no existirá.
     */
    fun checkPhotoStillExistsInPublicStorage(context: Context, photo: TrackedPhoto): Boolean {
        val uri = Uri.parse(photo.uriString)
        try {
            // 1. Probar consulta en ContentResolver
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return true
                }
            }

            // 2. Probar abrir InputStream para confirmar acceso
            context.contentResolver.openInputStream(uri)?.use {
                return true
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    /**
     * Abre el archivo específicamente en Google Fotos a pantalla completa.
     * Si Google Fotos no está instalada, abre en la galería predeterminada.
     */
    fun openPhotoInGooglePhotos(context: Context, photo: TrackedPhoto): Boolean {
        val uri = Uri.parse(photo.uriString)
        val mimeType = photo.mimeType.ifBlank {
            if (photo.isVideo) "video/*" else "image/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            intent.setPackage(GOOGLE_PHOTOS_PACKAGE)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            try {
                intent.setPackage(null)
                context.startActivity(intent)
                true
            } catch (ex: Exception) {
                Log.e(TAG, "Error abriendo visualizador genérico: ${ex.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo en Google Fotos: ${e.message}", e)
            false
        }
    }
}
