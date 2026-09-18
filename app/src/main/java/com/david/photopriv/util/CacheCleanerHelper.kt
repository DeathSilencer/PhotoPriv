package com.david.photopriv.util

import android.content.Context
import android.util.Log
import com.david.photopriv.data.dao.PhotoDao
import com.david.photopriv.data.vault.StagingVaultManager
import java.io.File

/**
 * Gestor Autónomo de Limpieza de Caché y Datos Residuales.
 * 
 * - Purga clips temporales de video (VideoSegmenter) y volcados efímeros en cacheDir.
 * - Monitorea el tamaño total del caché y elimina archivos antiguos si supera el umbral seguro.
 * - Detecta y destruye archivos huérfanos en la bóveda secreta (.photopriv_vault) que ya no
 *   estén vinculados a ninguna foto activa en la base de datos.
 * - Preserva siempre el archivo .nomedia de la bóveda para mantener la invisibilidad.
 */
object CacheCleanerHelper {

    private const val TAG = "CacheCleaner"
    private const val MAX_CACHE_AGE_MS = 30 * 60 * 1000L // 30 minutos
    private const val MAX_TOTAL_CACHE_BYTES = 35 * 1024 * 1024L // 35 MB umbral máximo

    data class CacheStats(
        val cacheSizeBytes: Long,
        val vaultSizeBytes: Long,
        val cleanedFilesCount: Int
    )

    /**
     * Limpieza integral del sistema: caché de la app + archivos huérfanos de bóveda.
     */
    fun cleanAll(context: Context, photoDao: PhotoDao? = null): CacheStats {
        var cleanedCount = 0
        try {
            cleanedCount += cleanCacheDirectory(context.cacheDir)
            context.externalCacheDir?.let {
                cleanedCount += cleanCacheDirectory(it)
            }
            if (photoDao != null) {
                cleanedCount += cleanOrphanVaultFiles(context, photoDao)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en auto-limpieza integral: ${e.message}", e)
        }

        val cacheSize = getDirectorySize(context.cacheDir) + (context.externalCacheDir?.let { getDirectorySize(it) } ?: 0L)
        val vaultSize = getDirectorySize(StagingVaultManager.getVaultDir(context))

        Log.d(TAG, "Auto-limpieza completada. Archivos eliminados: $cleanedCount. Caché actual: ${cacheSize / 1024} KB. Bóveda: ${vaultSize / 1024} KB.")
        return CacheStats(cacheSize, vaultSize, cleanedCount)
    }

    /**
     * Limpia un directorio de caché eliminando archivos temporales o antiguos.
     */
    fun cleanCacheDirectory(dir: File?): Int {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0

        var deletedCount = 0
        try {
            val now = System.currentTimeMillis()
            val files = dir.listFiles() ?: return 0
            var totalSize = 0L

            for (file in files) {
                if (file.isDirectory) {
                    deletedCount += cleanSubdirectory(file, now)
                } else {
                    val length = file.length()
                    totalSize += length
                    val age = now - file.lastModified()
                    val isTempWorkFile = file.name.startsWith("temp_") ||
                            file.name.contains("_Parte") ||
                            file.name.endsWith(".tmp")

                    // Borrar archivos de video/trabajo temporal si tienen más de 5 minutos,
                    // o cualquier archivo de caché con más de 30 minutos
                    if ((isTempWorkFile && age > 5 * 60 * 1000L) || age > MAX_CACHE_AGE_MS) {
                        val sizeKb = length / 1024
                        if (file.delete()) {
                            deletedCount++
                            totalSize -= length
                            Log.d(TAG, "Archivo de caché purgado: ${file.name} ($sizeKb KB)")
                        }
                    }
                }
            }

            // Si el caché acumulado aún supera el límite máximo (35 MB), purgar archivos
            // ordenados por antigüedad hasta reducirlo a menos de la mitad
            if (totalSize > MAX_TOTAL_CACHE_BYTES) {
                val remaining = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified()} ?: emptyList()
                for (f in remaining) {
                    if (totalSize <= MAX_TOTAL_CACHE_BYTES / 2) break
                    val len = f.length()
                    if (f.delete()) {
                        deletedCount++
                        totalSize -= len
                        Log.d(TAG, "Cuota de caché excedida. Purgado por antigüedad: ${f.name} (${len / 1024} KB)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error limpiando directorio ${dir.absolutePath}: ${e.message}")
        }
        return deletedCount
    }

    private fun cleanSubdirectory(subDir: File, now: Long): Int {
        var count = 0
        val subFiles = subDir.listFiles() ?: return 0
        for (f in subFiles) {
            if (f.isFile && (now - f.lastModified()) > MAX_CACHE_AGE_MS) {
                if (f.delete()) count++
            }
        }
        return count
    }

    /**
     * Purga de la bóveda secreta cualquier archivo huérfano que no esté
     * registrado en la base de datos como localStagingPath activo.
     */
    fun cleanOrphanVaultFiles(context: Context, photoDao: PhotoDao): Int {
        var purgedCount = 0
        try {
            val vaultDir = StagingVaultManager.getVaultDir(context)
            if (!vaultDir.exists()) return 0

            val activeStagedPaths = photoDao.getAllStagedPaths().toSet()
            val vaultFiles = vaultDir.listFiles() ?: return 0

            for (file in vaultFiles) {
                if (file.name == ".nomedia") continue
                if (!activeStagedPaths.contains(file.absolutePath)) {
                    val sizeKb = file.length() / 1024
                    if (file.delete()) {
                        purgedCount++
                        Log.d(TAG, "Archivo huérfano de bóveda eliminado: ${file.name} ($sizeKb KB)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando huérfanos de bóveda: ${e.message}")
        }
        return purgedCount
    }

    fun getDirectorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        try {
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) getDirectorySize(file) else file.length()
            }
        } catch (ignored: Exception) {}
        return size
    }
}
