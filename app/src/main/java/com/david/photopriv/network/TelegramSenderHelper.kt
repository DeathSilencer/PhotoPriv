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
        deviceName: String = "",
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
            val captureTimeStr = formatCaptureTime(photo.dateTaken)?.let { "\n🗓️ Captura: $it" } ?: ""
            val url = "https://api.telegram.org/bot${config.botToken}/sendDocument"

            val resolvedDevice = deviceName.trim().ifBlank {
                val m = android.os.Build.MODEL
                val man = android.os.Build.MANUFACTURER.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
                if (m.startsWith(man, ignoreCase = true)) m else "$man $m"
            }
            val deviceHeader = "📱 Teléfono: *$resolvedDevice*"

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

                    val totalOriginalVideoBytes = sourceFile.length()
                    val totalOriginalSizeStr = formatSize(totalOriginalVideoBytes)

                    val videoParts = VideoSegmenter.splitIntoPlayableClips(context, sourceFile, photo.displayName)
                    if (videoParts.isEmpty()) {
                        return@withContext SendResult.Error("Fallo al generar clips reproducibles para el video ${photo.displayName}")
                    }

                    val totalParts = videoParts.size
                    val totalDurationSec = videoParts.sumOf { it.durationMs } / 1000

                    for (part in videoParts) {
                        onPartProgress?.invoke(part.partIndex, totalParts)

                        val partFile = part.file
                        val partSizeStr = formatSize(partFile.length())
                        val partDurationSec = part.durationMs / 1000

                        val partCaption = if (totalParts == 1) {
                            """
                            🛡️ *PhotoPriv - Video Seguro*
                            $deviceHeader
                            🎥 Video: `${photo.displayName}`
                            📦 Tamaño: $partSizeStr
                            ⏱️ Duración: ${partDurationSec}s
                            📐 Resolución: ${part.width}×${part.height}$captureTimeStr
                            ⏱️ Envío: $timestamp
                            """.trimIndent()
                        } else {
                            """
                            🛡️ *PhotoPriv - Video Extendido (Parte ${part.partIndex}/$totalParts)*
                            $deviceHeader
                            🎥 Video: `${photo.displayName}`
                            📊 Tamaño Total Original: $totalOriginalSizeStr
                            📦 Clip MP4 Reproducible: `${partFile.name}` ($partSizeStr)
                            ⏱️ Duración Clip: ${partDurationSec}s (Total: ${totalDurationSec}s)
                            📐 Resolución: ${part.width}×${part.height}$captureTimeStr
                            ⏱️ Envío: $timestamp
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
            val targetFileName = if (isWebpOrHeic) {
                originalName.substringBeforeLast('.') + ".jpg"
            } else if (dataSaverMode && !originalName.endsWith(".jpg", ignoreCase = true) && !originalName.endsWith(".jpeg", ignoreCase = true)) {
                originalName.substringBeforeLast('.') + ".jpg"
            } else {
                originalName
            }

            val originalSizeBytes = photo.fileSizeBytes.takeIf { it > 0 } ?: (stagedFile?.length() ?: 0L)
            val originalSizeStr = formatSize(originalSizeBytes)

            // 1. Si Ahorro de Datos está activo O el archivo es WebP/HEIC
            if (dataSaverMode || isWebpOrHeic) {
                val maxDim = if (dataSaverMode) 1920 else 4096
                val quality = if (dataSaverMode) 82 else 95

                val compressResult = if (stagedFile != null && stagedFile.exists()) {
                    MediaCompressor.compressImageFile(stagedFile, maxDimension = maxDim, quality = quality)
                } else {
                    MediaCompressor.compressImage(context, uri, maxDimension = maxDim, quality = quality)
                }

                if (compressResult != null && compressResult.bytes.isNotEmpty()) {
                    val compSizeStr = formatSize(compressResult.bytes.size.toLong())
                    val reductionPct = if (originalSizeBytes > 0 && originalSizeBytes > compressResult.bytes.size) {
                        ((originalSizeBytes - compressResult.bytes.size).toDouble() / originalSizeBytes * 100).toInt()
                    } else 0
                    val reductionNotice = if (reductionPct > 0) " (-$reductionPct% optimizado)" else ""

                    val captionText = """
                        🛡️ *PhotoPriv - Foto Segura*
                        $deviceHeader
                        📷 Foto: `${targetFileName}`
                        📊 Tamaño Original: $originalSizeStr
                        📦 Tamaño Comprimido: $compSizeStr$reductionNotice
                        📐 Resolución: ${compressResult.origWidth}×${compressResult.origHeight} ➔ ${compressResult.finalWidth}×${compressResult.finalHeight}$captureTimeStr
                        ⏱️ Envío: $timestamp
                    """.trimIndent()

                    val reqBody = compressResult.bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
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

                val captionText = """
                    🛡️ *PhotoPriv - Foto Segura*
                    $deviceHeader
                    📷 Foto: `${targetFileName}`
                    📦 Tamaño Original: $originalSizeStr (Sin compresión)$captureTimeStr
                    ⏱️ Envío: $timestamp
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

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB (%d KB)", mb, bytes / 1024)
        } else {
            "${bytes / 1024} KB"
        }
    }

    private fun formatCaptureTime(epochMillis: Long): String? {
        if (epochMillis <= 0) return null
        val millis = if (epochMillis < 10000000000L) epochMillis * 1000 else epochMillis
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
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
