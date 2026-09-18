package com.david.photopriv.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object MediaCompressor {

    private const val TAG = "MediaCompressor"
    private const val MAX_DIMENSION = 1920 // Full HD: excelente resolución y tamaño reducido
    private const val JPEG_QUALITY = 82     // Balance óptimo entre fidelidad visual y peso (< 400 KB)

    data class CompressResult(
        val bytes: ByteArray,
        val origWidth: Int,
        val origHeight: Int,
        val finalWidth: Int,
        val finalHeight: Int
    )

    /**
     * Comprime y redimensiona una imagen desde un File local de la bóveda secreta.
     */
    fun compressImageFile(
        file: File,
        maxDimension: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY
    ): CompressResult? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val decodedBitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val orientedBitmap = correctOrientationFromFile(file, decodedBitmap)
            val finalBitmap = scaleToMaxDimension(orientedBitmap, maxDimension)

            val finalWidth = finalBitmap.width
            val finalHeight = finalBitmap.height

            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            if (finalBitmap != decodedBitmap && !finalBitmap.isRecycled) finalBitmap.recycle()
            if (orientedBitmap != decodedBitmap && !orientedBitmap.isRecycled) orientedBitmap.recycle()
            if (!decodedBitmap.isRecycled) decodedBitmap.recycle()

            val compressedBytes = outputStream.toByteArray()
            Log.d(TAG, "Imagen de bóveda comprimida: ${compressedBytes.size / 1024} KB (Original: ${origWidth}x${origHeight} -> Final: ${finalWidth}x${finalHeight})")
            CompressResult(compressedBytes, origWidth, origHeight, finalWidth, finalHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error comprimiendo imagen desde archivo: ${e.message}", e)
            null
        }
    }

    /**
     * Comprime y redimensiona una imagen desde su URI para reducir su peso hasta en un 90%
     * sin pérdida perceptible de calidad visual.
     */
    fun compressImage(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY
    ): CompressResult? {
        return try {
            val contentResolver = context.contentResolver

            // 1. Obtener dimensiones originales sin cargar los píxeles en memoria
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // 2. Calcular factor de submuestreo eficiente (inSampleSize)
            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // Reduce consumo de memoria a la mitad

            // 3. Decodificar el Bitmap con el tamaño reducido
            val decodedBitmap = (contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }) ?: return null

            // 4. Corregir orientación EXIF si es necesario
            val orientedBitmap = correctOrientation(context, uri, decodedBitmap)

            // 5. Escalar si aún supera las dimensiones máximas deseadas
            val finalBitmap = scaleToMaxDimension(orientedBitmap, maxDimension)
            val finalWidth = finalBitmap.width
            val finalHeight = finalBitmap.height

            // 6. Comprimir a formato JPEG de alta eficiencia
            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            if (finalBitmap != decodedBitmap && !finalBitmap.isRecycled) {
                finalBitmap.recycle()
            }
            if (orientedBitmap != decodedBitmap && !orientedBitmap.isRecycled) {
                orientedBitmap.recycle()
            }
            if (!decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }

            val compressedBytes = outputStream.toByteArray()
            Log.d(TAG, "Imagen comprimida con éxito: ${compressedBytes.size / 1024} KB (Original: ${origWidth}x${origHeight} -> Final: ${finalWidth}x${finalHeight})")
            CompressResult(compressedBytes, origWidth, origHeight, finalWidth, finalHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error comprimiendo imagen: ${e.message}", e)
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun correctOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    else -> return bitmap
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } ?: bitmap
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun correctOrientationFromFile(file: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun scaleToMaxDimension(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap

        val ratio = min(maxDim.toFloat() / width, maxDim.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
