package com.david.photopriv.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

object VideoSegmenter {

    private const val TAG = "VideoSegmenter"
    // Telegram Bot API max file size is 50 MB. Usamos 45 MB como límite seguro para margen de cabeceras HTTP.
    const val MAX_PART_BYTES = 45 * 1024 * 1024L

    data class VideoMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int
    )

    data class VideoPart(
        val file: File,
        val partIndex: Int,
        val totalParts: Int,
        val durationMs: Long,
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0
    )

    /**
     * Divide un video grande en partes reproducibles e independientes (.mp4).
     * Cada parte es un archivo MP4 100% válido con su propio encabezado y tabla de muestras (moov atom).
     * Se puede reproducir directamente en Telegram, Google Fotos, WhatsApp o VLC sin corruptela.
     *
     * Utiliza MediaExtractor + MediaMuxer para realizar remuxing sin decodificar fotogramas,
     * lo cual se ejecuta en menos de 2 segundos sin pérdida de calidad.
     */
    fun splitIntoPlayableClips(
        context: Context,
        sourceFile: File,
        baseName: String
    ): List<VideoPart> {
        val totalBytes = sourceFile.length()
        val sourceMeta = getVideoMetadata(sourceFile.absolutePath)

        if (totalBytes <= MAX_PART_BYTES) {
            return listOf(
                VideoPart(
                    file = sourceFile,
                    partIndex = 1,
                    totalParts = 1,
                    durationMs = sourceMeta.durationMs,
                    width = sourceMeta.width,
                    height = sourceMeta.height,
                    rotation = sourceMeta.rotation
                )
            )
        }

        val totalParts = ceil(totalBytes.toDouble() / MAX_PART_BYTES).toInt().coerceAtLeast(2)
        val totalDurationMs = sourceMeta.durationMs
        val totalDurationUs = if (totalDurationMs > 0) totalDurationMs * 1000L else 60_000_000L
        val partDurationUs = totalDurationUs / totalParts

        Log.d(TAG, "Dividiendo ${sourceFile.name} ($totalBytes bytes, ${totalDurationMs / 1000}s, rot: ${sourceMeta.rotation}°) en $totalParts clips MP4 reproducibles...")

        val resultParts = mutableListOf<VideoPart>()
        val cleanBase = baseName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9._-]"), "_")

        for (partIndex in 1..totalParts) {
            val startUs = (partIndex - 1) * partDurationUs
            val endUs = if (partIndex == totalParts) Long.MAX_VALUE else partIndex * partDurationUs
            val outputFile = File(context.cacheDir, "${cleanBase}_Parte${partIndex}_de_${totalParts}.mp4")

            val success = extractClip(sourceFile, outputFile, startUs, endUs, sourceMeta.rotation)
            if (success && outputFile.exists() && outputFile.length() > 0L) {
                val clipMeta = getVideoMetadata(outputFile.absolutePath)
                resultParts.add(
                    VideoPart(
                        file = outputFile,
                        partIndex = partIndex,
                        totalParts = totalParts,
                        durationMs = clipMeta.durationMs,
                        width = clipMeta.width,
                        height = clipMeta.height,
                        rotation = clipMeta.rotation
                    )
                )
                Log.d(TAG, "Clip $partIndex/$totalParts creado: ${outputFile.name} (${outputFile.length() / 1024} KB, rot: ${clipMeta.rotation}°)")
            } else {
                Log.e(TAG, "Fallo al crear clip $partIndex/$totalParts para ${sourceFile.name}")
                resultParts.forEach { it.file.delete() }
                return emptyList()
            }
        }

        return resultParts
    }

    private fun extractClip(
        sourceFile: File,
        outputFile: File,
        startUs: Long,
        endUs: Long,
        rotation: Int
    ): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            extractor = MediaExtractor().apply {
                setDataSource(sourceFile.absolutePath)
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Preservar la orientación física del video para que no se reproduzca volteado / girado
            if (rotation in listOf(90, 180, 270)) {
                muxer.setOrientationHint(rotation)
                Log.d(TAG, "Orientación configurada a $rotation° en muxer para ${outputFile.name}")
            }

            val trackCount = extractor.trackCount
            val trackMap = mutableMapOf<Int, Int>()
            var videoTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                    }
                }
            }

            if (trackMap.isEmpty()) {
                Log.e(TAG, "No se encontraron pistas de video o audio en ${sourceFile.name}")
                return false
            }

            muxer.start()

            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            val firstPtsMap = mutableMapOf<Int, Long>()
            val lastPtsMap = mutableMapOf<Int, Long>()
            var samplesWritten = 0

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleTime = extractor.sampleTime

                if (endUs != Long.MAX_VALUE && sampleTime >= endUs) {
                    val flags = extractor.sampleFlags
                    val isSyncBoundary = if (videoTrackIndex >= 0) {
                        trackIndex == videoTrackIndex && (flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    } else {
                        true
                    }
                    if (isSyncBoundary) {
                        break
                    }
                }

                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val firstPts = firstPtsMap.getOrPut(trackIndex) { sampleTime }
                val rawAdjustedPts = (sampleTime - firstPts).coerceAtLeast(0L)
                val lastPts = lastPtsMap[trackIndex] ?: -1L
                val adjustedPts = if (rawAdjustedPts > lastPts) rawAdjustedPts else lastPts + 1000L
                lastPtsMap[trackIndex] = adjustedPts

                bufferInfo.presentationTimeUs = adjustedPts
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0

                val muxerTrack = trackMap[trackIndex]
                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    samplesWritten++
                }

                extractor.advance()
            }

            if (samplesWritten > 0) {
                muxer.stop()
                true
            } else {
                Log.w(TAG, "No se escribieron muestras en el clip ${outputFile.name}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extrayendo clip (${sourceFile.name}): ${e.message}", e)
            outputFile.delete()
            false
        } finally {
            try {
                extractor?.release()
            } catch (ignored: Exception) {}
            try {
                muxer?.release()
            } catch (ignored: Exception) {}
        }
    }

    fun getVideoDurationMs(path: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getVideoMetadata(path: String): VideoMetadata {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            retriever.release()

            val duration = durationStr?.toLongOrNull() ?: 0L
            val rawWidth = widthStr?.toIntOrNull() ?: 0
            val rawHeight = heightStr?.toIntOrNull() ?: 0
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val (width, height) = if (rotation == 90 || rotation == 270) {
                Pair(rawHeight, rawWidth)
            } else {
                Pair(rawWidth, rawHeight)
            }

            VideoMetadata(duration, width, height, rotation)
        } catch (e: Exception) {
            VideoMetadata(0L, 0, 0, 0)
        }
    }
}
