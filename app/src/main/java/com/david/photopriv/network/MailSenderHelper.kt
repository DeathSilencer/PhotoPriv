package com.david.photopriv.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.david.photopriv.data.model.TrackedPhoto
import com.david.photopriv.data.preferences.SmtpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.activation.DataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

object MailSenderHelper {

    private const val TAG = "MailSenderHelper"

    sealed class SendResult {
        object Success : SendResult()
        data class Error(val message: String, val cause: Throwable? = null) : SendResult()
    }

    suspend fun sendPhotosEmail(
        context: Context,
        config: SmtpConfig,
        photos: List<TrackedPhoto>,
        subjectTitle: String? = null
    ): SendResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext SendResult.Error("La configuración SMTP está incompleta (falta correo emisor, contraseña o destinatario).")
        }

        if (photos.isEmpty()) {
            return@withContext SendResult.Error("No hay fotos para enviar.")
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.host", config.host)
                put("mail.smtp.port", config.port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "30000")

                if (config.port == 465) {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", "465")
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.socketFactory.fallback", "false")
                } else {
                    put("mail.smtp.starttls.enable", if (config.useTls) "true" else "false")
                }
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.senderEmail, config.senderPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.senderEmail, "PhotoPriv Centinela"))
                setRecipient(Message.RecipientType.TO, InternetAddress(config.recipientEmail))

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val subject = subjectTitle ?: "[PhotoPriv Respaldo] ${photos.size} foto(s) extraída(s) - $timestamp"
                setSubject(subject, "UTF-8")
            }

            val multipart: Multipart = MimeMultipart()

            // 1. Cuerpo de texto / HTML
            val textBodyPart = MimeBodyPart().apply {
                val sb = StringBuilder()
                sb.append("<h2>🛡️ PhotoPriv - Respaldo de Emergencia</h2>")
                sb.append("<p>Se han extraído <b>${photos.size}</b> archivo(s) de la carpeta bloqueada.</p>")
                sb.append("<ul>")
                for (photo in photos) {
                    val sizeKb = photo.fileSizeBytes / 1024
                    sb.append("<li><b>${photo.displayName}</b> (Tamaño: ${sizeKb} KB)</li>")
                }
                sb.append("</ul>")
                sb.append("<p style='color: #888;'>Fecha del respaldo: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}</p>")
                setContent(sb.toString(), "text/html; charset=utf-8")
            }
            multipart.addBodyPart(textBodyPart)

            // 2. Adjuntos de fotos
            for (photo in photos) {
                try {
                    val stagedFile = photo.localStagingPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                    val bytes = if (stagedFile != null) {
                        stagedFile.readBytes()
                    } else {
                        val uri = Uri.parse(photo.uriString)
                        readUriBytes(context, uri)
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val attachmentPart = MimeBodyPart().apply {
                            val dataSource = ByteArrayDataSource(bytes, "image/jpeg", photo.displayName)
                            dataHandler = javax.activation.DataHandler(dataSource)
                            fileName = photo.displayName
                        }
                        multipart.addBodyPart(attachmentPart)
                    } else {
                        Log.w(TAG, "No se pudieron leer los bytes de ${photo.displayName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adjuntando foto ${photo.displayName}: ${e.message}", e)
                }
            }

            message.setContent(multipart)
            Transport.send(message)
            Log.d(TAG, "Correo de respaldo enviado exitosamente con ${photos.size} foto(s).")
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando correo SMTP: ${e.message}", e)
            SendResult.Error(e.message ?: "Error desconocido enviando correo", e)
        }
    }

    private fun readUriBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val byteBuffer = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    byteBuffer.write(buffer, 0, len)
                }
                byteBuffer.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo bytes del URI $uri: ${e.message}")
            null
        }
    }

    private class ByteArrayDataSource(
        private val data: ByteArray,
        private val contentType: String,
        private val name: String
    ) : DataSource {
        override fun getInputStream(): InputStream = data.inputStream()
        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException("Solo lectura")
        override fun getContentType(): String = contentType
        override fun getName(): String = name
    }
}
