package com.david.photopriv.data.vault

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Gestor de Bóveda Temporal Oculta ("Stealth Staging Vault").
 * 
 * Almacena de forma efímera las fotos y videos en el almacenamiento privado de la app (noBackupFilesDir).
 * - Totalmente invisible para la Galería de Android, Google Fotos, WhatsApp y exploradores de archivos.
 * - Incluye archivo .nomedia por seguridad adicional.
 * - Permite al usuario devolver sus fotos inmediatamente a la Carpeta Bloqueada en Google Fotos
 *   sin interrumpir la subida a Telegram.
 * - Una vez enviado el archivo o finalizada la sesión, se destruye de inmediato sin dejar rastro ("nunca existió").
 */
object StagingVaultManager {

    private const val TAG = "StagingVaultManager"
    private const val VAULT_DIR_NAME = ".photopriv_vault"

    fun getVaultDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, VAULT_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
            try {
                File(dir, ".nomedia").createNewFile()
            } catch (ignored: Exception) {}
        }
        return dir
    }

    /**
     * Copia de inmediato un archivo multimedia público a la bóveda secreta interna.
     * Operación ultrarrápida (milisegundos) para que el usuario pueda re-bloquear sin demora.
     */
    fun stageMedia(context: Context, mediaStoreId: Long, displayName: String, uri: Uri): File? {
        val vaultDir = getVaultDir(context)
        val cleanName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(vaultDir, "${mediaStoreId}_$cleanName")

        var attempts = 0
        while (attempts < 3) {
            attempts++
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 128 * 1024)
                    }
                }

                if (targetFile.exists() && targetFile.length() > 0L) {
                    Log.d(TAG, "Archivo aislado en bóveda secreta: ${targetFile.name} (${targetFile.length() / 1024} KB)")
                    return targetFile
                }
            } catch (e: Exception) {
                Log.w(TAG, "Intento $attempts aislando $mediaStoreId en bóveda: ${e.message}")
            }

            if (attempts < 3) {
                try { Thread.sleep(300) } catch (ignored: Exception) {}
            }
        }

        if (targetFile.exists()) targetFile.delete()
        return null
    }

    /**
     * Destruye de inmediato un archivo staged tras su subida exitosa.
     */
    fun deleteStagedFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Archivo de bóveda eliminado tras subida exitosa: $filePath (resultado: $deleted)")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando archivo de bóveda $filePath: ${e.message}", e)
            false
        }
    }

    /**
     * Limpia por completo la bóveda temporal y elimina el directorio.
     */
    fun clearVault(context: Context) {
        try {
            val vaultDir = getVaultDir(context)
            if (vaultDir.exists()) {
                vaultDir.listFiles()?.forEach { it.delete() }
                vaultDir.delete()
                Log.d(TAG, "Bóveda secreta destruida por completo sin dejar rastro.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando bóveda secreta: ${e.message}", e)
        }
    }
}
