package com.base.editor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import java.io.File

data class OutroClipInfo(
    val uri: String,
    val durationMs: Long
)

object OutroHelper {
    private const val TAG = "OutroHelper"

    /**
     * Ищет видео outro в папках "for base" на устройстве.
     * Проверяет:
     * 1. /sdcard/for base
     * 2. /sdcard/Download/for base
     * 3. /sdcard/Movies/for base
     * 4. /sdcard/DCIM/for base
     * 5. Внутренняя папка приложения: filesDir/for base
     */
    fun getOutro(context: Context): OutroClipInfo? {
        val candidates = listOf(
            File(Environment.getExternalStorageDirectory(), "for base"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "for base"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "for base"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "for base"),
            File(context.filesDir, "for base"),
            context.getExternalFilesDir("for base"),
        ).filterNotNull()

        for (dir in candidates) {
            if (dir.exists() && dir.isDirectory) {
                val videoFile = dir.listFiles { f ->
                    val ext = f.extension.lowercase()
                    ext == "mp4" || ext == "mov" || ext == "mkv" || ext == "webm"
                }?.firstOrNull()

                if (videoFile != null && videoFile.length() > 0) {
                    val dur = getVideoDuration(videoFile)
                    if (dur > 0) {
                        Log.d(TAG, "Найдено outro видео: ${videoFile.absolutePath}, длительность=$dur мс")
                        return OutroClipInfo(videoFile.absolutePath, dur)
                    }
                }
            }
        }
        // Если в папках "for base" ещё нет видео (например, ссылка на диск не скачалась без OAuth),
        // автоматически извлекаем 2-секундный клип из экспортированных видео в /sdcard/for base/outro.mp4
        val fallbackDir = File(Environment.getExternalStorageDirectory(), "for base").apply { mkdirs() }
        val fallbackOutro = File(fallbackDir, "outro.mp4")
        if (!fallbackOutro.exists() || fallbackOutro.length() == 0L) {
            val sampleVideos = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "BASE"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BASE"),
                File(context.filesDir, "projects"),
            )
            for (p in sampleVideos) {
                val candidate = p.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) && f.length() > 100_000 }?.maxByOrNull { it.lastModified() }
                if (candidate != null) {
                    val ok = extractSnippet(candidate, fallbackOutro, 2_000_000L)
                    if (ok) {
                        Log.d(TAG, "Сгенерировано outro видео: ${fallbackOutro.absolutePath}")
                        break
                    }
                }
            }
        }
        if (fallbackOutro.exists() && fallbackOutro.length() > 0) {
            val dur = getVideoDuration(fallbackOutro)
            if (dur > 0) return OutroClipInfo(fallbackOutro.absolutePath, dur)
        }
        return null
    }

    private fun extractSnippet(src: File, dst: File, maxDurationUs: Long = 2_000_000L): Boolean {
        var extractor: android.media.MediaExtractor? = null
        var muxer: android.media.MediaMuxer? = null
        try {
            extractor = android.media.MediaExtractor().apply { setDataSource(src.absolutePath) }
            muxer = android.media.MediaMuxer(dst.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackCount = extractor.trackCount
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    extractor.selectTrack(i)
                }
            }
            if (trackMap.isEmpty()) return false
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var startUs = -1L
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val trackIndex = extractor.sampleTrackIndex
                val muxerTrack = trackMap[trackIndex]
                val timeUs = extractor.sampleTime
                if (startUs < 0) startUs = timeUs
                if (timeUs - startUs > maxDurationUs) {
                    extractor.advance()
                    break
                }
                if (muxerTrack != null) {
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = timeUs - startUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
            return dst.exists() && dst.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "extractSnippet failed", e)
            return false
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 2000L
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения длительности outro: ${file.absolutePath}", e)
            2000L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
