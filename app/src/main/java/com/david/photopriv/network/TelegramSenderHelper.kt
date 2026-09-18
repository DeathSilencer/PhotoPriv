package com.david.photopriv.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.david.photopriv.data.model.TrackedPhoto
import com.david.photopriv.data.preferences.TelegramConfig
import com.david.photopriv.util.MediaCompressor
import com.david.photopriv.util.VideoSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TelegramSenderHelper {

    private const val TAG = "TelegramSenderHelper"
    private const val MAX_TELEGRAM_FILE_SIZE = 45 * 1024 * 1024L // 45 MB para margen de seguridad

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    sealed class SendResult {
        object Success : SendResult()
        data class Error(val message: String, val cause: Throwable? = null) : SendResult()
    }

    /**
     * Envía un archivo individual (foto o video) a Telegram.
     * - Si es video (> 45MB): segmenta en clips MP4 100% reproducibles e independientes con VideoSegmenter.
     * - Si es foto: si es .webp o .heic se convierte a JPEG de alta fidelidad con extensión .jpg para evitar
     *   que Telegram la interprete como sticker diminuto no descargable.
     * - Si [dataSaverMode] está activo y es foto: comprime a Full HD (~300KB) para envío ultrarrápido.
     */
    suspend fun sendSingleMedia(
        context: Context,
        config: TelegramConfig,
        photo: TrackedPhoto,
        dataSaverMode: Boolean = true,
        onPartProgress: ((part: Int, total: Int) -> Unit)? = null
    ): SendResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext SendResult.Error("Falta configurar el Bot Token o Chat ID de Telegram.")
        }

        val uri = Uri.parse(photo.uriString)
        val originalName = photo.displayName
        val isWebpOrHeic = originalName.endsWith(".webp", ignoreCase = true) ||
                originalName.endsWith(".heic", ignoreCase = true) ||
                originalName.endsWith(".heif", ignoreCase = true)

        // Si el archivo está resguardado en la bóveda secreta interna, leer directamente desde ahí
        val stagedFile = photo.localStagingPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        val effectiveSize = stagedFile?.length() ?: photo.fileSizeBytes

        val openMediaInputStream: () -> InputStream? = {
            if (stagedFile != null && stagedFile.exists()) {
                stagedFile.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
        }

        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val url = "https://api.telegram.org/bot${config.botToken}/sendDocument"

            // =========================================================================
            // CASO A: VIDEOS (Hasta 45MB directos o > 45MB en clips MP4 reproducibles)
            // =========================================================================
            if (photo.isVideo) {
                // Obtener archivo fuente (bóveda secreta o volcado temporal si no estaba en bóveda)
                val sourceFile = stagedFile ?: run {
                    val tempFile = File(context.cacheDir, "temp_src_vid_${photo.mediaStoreId}_${System.currentTimeMillis()}.mp4")
                    openMediaInputStream()?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 128 * 1024)
                        }
                    }
                    tempFile
                }

                try {
                    if (!sourceFile.exists() || sourceFile.length() == 0L) {
                        return@withContext SendResult.Error("No se pudo leer el archivo de video para ${photo.displayName}")
                    }

                    val videoParts = VideoSegmenter.splitIntoPlayableClips(context, sourceFile, photo.displayName)
                    if (videoParts.isEmpty()) {
                        return@withContext SendResult.Error("Fallo al generar clips reproducibles para el video ${photo.displayName}")
                    }

                    val totalParts = videoParts.size
                    for (part in videoParts) {
                        onPartProgress?.invoke(part.partIndex, totalParts)

                        val partFile = part.file
                        val partSizeKb = partFile.length() / 1024
                        val partDurationSec = part.durationMs / 1000

                        val partCaption = if (totalParts == 1) {
                            """
                            🛡️ *PhotoPriv - Video de Emergencia*
                            🎥 VIDEO: `${photo.displayName}`
                            📦 Tamaño: $partSizeKb KB
                            ⏱️ Duración: ${partDurationSec}s | Hora: $timestamp
                            """.trimIndent()
                        } else {
                            """
                            🛡️ *PhotoPriv - Video Extendido (Parte ${part.partIndex}/$totalParts)*
                            🎥 VIDEO: `${photo.displayName}`
                            📦 Clip MP4 Reproducible: `${partFile.name}` ($partSizeKb KB)
                            ⏱️ Duración Clip: ${partDurationSec}s | Hora: $timestamp
                            """.trimIndent()
                        }

                        // Enviar preferentemente como video nativo con sendVideo para que Telegram renderice
                        // el reproductor con controles, orientación nativa correcta (sin voltear) y soporte de streaming.
                        val videoUrl = "https://api.telegram.org/bot${config.botToken}/sendVideo"
                        var sendRes = executeTelegramSendVideo(
                            url = videoUrl,
                            chatId = config.chatId,
                            caption = partCaption,
                            fileName = partFile.name,
                            durationSec = partDurationSec.toInt(),
                            width = part.width,
                            height = part.height,
                            requestBody = partFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                        )

                        // Si sendVideo falla (por ejemplo por alguna incompatibilidad de streaming), respaldo con sendDocument
                        if (sendRes is SendResult.Error) {
                            Log.w(TAG, "sendVideo falló (${sendRes.message}). Reintentando con sendDocument...")
                            sendRes = executeTelegramSend(
                                url = url,
                                chatId = config.chatId,
                                caption = partCaption,
                                fileName = partFile.name,
                                requestBody = partFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                            )
                        }

                        // Si es un clip temporal generado en cache por VideoSegmenter, borrarlo tras el envío
                        if (partFile != stagedFile && partFile.exists()) {
                            partFile.delete()
                        }

                        if (sendRes is SendResult.Error) {
                            return@withContext sendRes
                        }

                        if (part.partIndex < totalParts) {
                            delay(1000) // 1s de margen entre clips para no saturar la API de Telegram
                        }
                    }

                    return@withContext SendResult.Success
                } finally {
                    if (sourceFile != stagedFile && sourceFile.exists()) {
                        sourceFile.delete()
                    }
                }
            }

            // =========================================================================
            // CASO B: FOTOS (JPEG, PNG, WEBP, HEIC, etc.)
            // =========================================================================
            // Telegram Bot API trata automáticamente los archivos .webp como stickers,
            // mostrándolos diminutos en el chat y sin botón para guardarlos en la galería.
            // Para garantizar descarga y resolución completa:
            // 1. Normalizamos la extensión a .jpg para WebP y HEIC.
            // 2. Comprimimos/convertimos a JPEG antes del envío.
            val targetFileName = if (isWebpOrHeic) {
                originalName.substringBeforeLast('.') + ".jpg"
            } else if (dataSaverMode && !originalName.endsWith(".jpg", ignoreCase = true) && !originalName.endsWith(".jpeg", ignoreCase = true)) {
                originalName.substringBeforeLast('.') + ".jpg"
            } else {
                originalName
            }

            // 1. Si Ahorro de Datos está activo O el archivo es WebP/HEIC
            if (dataSaverMode || isWebpOrHeic) {
                val maxDim = if (dataSaverMode) 1920 else 4096
                val quality = if (dataSaverMode) 82 else 95

                val compressedBytes = if (stagedFile != null && stagedFile.exists()) {
                    MediaCompressor.compressImageFile(stagedFile, maxDimension = maxDim, quality = quality)
                } else {
                    MediaCompressor.compressImage(context, uri, maxDimension = maxDim, quality = quality)
                }

                if (compressedBytes != null && compressedBytes.isNotEmpty()) {
                    val sizeKb = compressedBytes.size / 1024
                    val modeNote = if (dataSaverMode) "Ahorro de datos activo" else "Alta Resolución"
                    val captionText = """
                        🛡️ *PhotoPriv - Foto Segura*
                        📷 FOTO: `${targetFileName}`
                        📦 Tamaño: $sizeKb KB ($modeNote)
                        ⏱️ Hora: $timestamp
                    """.trimIndent()

                    val reqBody = compressedBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                    return@withContext executeTelegramSend(url, config.chatId, captionText, targetFileName, reqBody)
                }
            }

            // 2. Foto original sin compresión (JPG/PNG directo <= 45MB)
            if (effectiveSize <= MAX_TELEGRAM_FILE_SIZE) {
                val mimeType = photo.mimeType.ifBlank { "image/jpeg" }
                val requestBody = if (stagedFile != null && stagedFile.exists()) {
                    stagedFile.asRequestBody(mimeType.toMediaTypeOrNull())
                } else {
                    val inputStream: InputStream = openMediaInputStream()
                        ?: return@withContext SendResult.Error("No se pudo abrir el archivo de ${photo.displayName}")
                    object : RequestBody() {
                        override fun contentType() = mimeType.toMediaTypeOrNull()

                        override fun writeTo(sink: BufferedSink) {
                            inputStream.use { stream ->
                                val buffer = ByteArray(128 * 1024)
                                var read: Int
                                while (stream.read(buffer).also { read = it } != -1) {
                                    sink.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }

                val sizeKb = effectiveSize / 1024
                val captionText = """
                    🛡️ *PhotoPriv - Foto de Emergencia*
                    📷 FOTO: `${targetFileName}`
                    📦 Tamaño: $sizeKb KB (Original)
                    ⏱️ Hora: $timestamp
                """.trimIndent()

                return@withContext executeTelegramSend(url, config.chatId, captionText, targetFileName, requestBody)
            } else {
                return@withContext SendResult.Error("La foto ${photo.displayName} excede el límite de 45MB.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción enviando a Telegram: ${e.message}", e)
            SendResult.Error(e.message ?: "Error de conexión con Telegram", e)
        }
    }

    private fun executeTelegramSend(
        url: String,
        chatId: String,
        caption: String,
        fileName: String,
        requestBody: RequestBody
    ): SendResult {
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("parse_mode", "Markdown")
            .addFormDataPart("document", fileName, requestBody)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipartBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            return if (response.isSuccessful) {
                Log.d(TAG, "Archivo $fileName enviado a Telegram correctamente.")
                SendResult.Success
            } else {
                Log.e(TAG, "Error de API Telegram (${response.code}): $bodyString")
                SendResult.Error("Telegram API error ${response.code}: $bodyString")
            }
        }
    }

    private fun executeTelegramSendVideo(
        url: String,
        chatId: String,
        caption: String,
        fileName: String,
        durationSec: Int,
        width: Int,
        height: Int,
        requestBody: RequestBody
    ): SendResult {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("parse_mode", "Markdown")
            .addFormDataPart("supports_streaming", "true")
            .addFormDataPart("video", fileName, requestBody)

        if (durationSec > 0) {
            builder.addFormDataPart("duration", durationSec.toString())
        }
        if (width > 0 && height > 0) {
            builder.addFormDataPart("width", width.toString())
            builder.addFormDataPart("height", height.toString())
        }

        val multipartBody = builder.build()
        val request = Request.Builder()
            .url(url)
            .post(multipartBody)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d(TAG, "Video $fileName enviado a Telegram correctamente vía sendVideo.")
                    SendResult.Success
                } else {
                    Log.w(TAG, "Telegram sendVideo falló (${response.code}): $bodyString")
                    SendResult.Error("Telegram sendVideo error ${response.code}: $bodyString")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en sendVideo: ${e.message}", e)
            SendResult.Error(e.message ?: "Error de red en sendVideo", e)
        }
    }

    suspend fun sendTestMessage(botToken: String, chatId: String): SendResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.telegram.org/bot${botToken.trim()}/sendMessage"
            val formBody = okhttp3.FormBody.Builder()
                .add("chat_id", chatId.trim())
                .add("text", "🛡️ ¡Conexión con PhotoPriv exitosa! Tu bot está listo para recibir fotos y videos extraídos.")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    SendResult.Success
                } else {
                    SendResult.Error("Error ${response.code}: $body")
                }
            }
        } catch (e: Exception) {
            SendResult.Error(e.message ?: "Error de red", e)
        }
    }
}
