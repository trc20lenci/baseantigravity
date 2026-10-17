package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils

object ReverseMediaWorker {
    private const val TAG = "ReverseMediaWorker"

    /**
     * Создает реверс аудиофайла (WAV/AAC) и возвращает путь к новому файлу.
     */
    suspend fun reverseAudio(context: Context, audioUri: String, onProgress: (Float) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            val uri = Uri.parse(audioUri)
            if (audioUri.startsWith("content://") || audioUri.startsWith("file://")) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(audioUri)
            }
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()

            val extractor = MediaExtractor()
            if (audioUri.startsWith("content://") || audioUri.startsWith("file://")) {
                extractor.setDataSource(context, uri, null)
            } else {
                extractor.setDataSource(audioUri)
            }

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrack < 0 || inputFormat == null) {
                extractor.release()
                return@withContext null
            }

            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val pcmBytes = mutableListOf<ByteArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputBufIndex = decoder.dequeueInputBuffer(10000L)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val res = decoder.dequeueOutputBuffer(bufferInfo, 10000L)
                if (res >= 0) {
                    val outBuf = decoder.getOutputBuffer(res)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.get(chunk)
                        pcmBytes.add(chunk)
                    }
                    decoder.releaseOutputBuffer(res, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            val totalPcmSize = pcmBytes.sumOf { it.size }
            if (totalPcmSize <= 0) return@withContext null

            // Объединяем PCM и реверсируем сэмплы (16-bit stereo/mono)
            val allPcm = ByteArray(totalPcmSize)
            var offset = 0
            for (chunk in pcmBytes) {
                System.arraycopy(chunk, 0, allPcm, offset, chunk.size)
                offset += chunk.size
            }

            val sampleBytes = 2 * channelCount // 16 bit (2 bytes) * channels
            val numSamples = totalPcmSize / sampleBytes
            val reversedPcm = ByteArray(totalPcmSize)

            for (i in 0 until numSamples) {
                val srcOffset = i * sampleBytes
                val dstOffset = (numSamples - 1 - i) * sampleBytes
                System.arraycopy(allPcm, srcOffset, reversedPcm, dstOffset, sampleBytes)
            }

            // Записываем WAV-файл в cacheDir
            val outFile = File(context.cacheDir, "reversed_audio_${System.currentTimeMillis()}.wav")
            writeWavFile(outFile, reversedPcm, sampleRate, channelCount)
            Log.d(TAG, "Аудио реверс готов: ${outFile.absolutePath}, размер=${outFile.length()}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка реверса аудио", e)
            null
        }
    }

    private fun writeWavFile(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val totalAudioLen = pcm.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (16 * sampleRate * channels / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // PCM
        header[20] = 1; header[21] = 0 // linear PCM
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcm)
        }
    }

    /**
     * Создает реверс видеофайла, сохраняя его в cacheDir в формате MP4.
     */
    suspend fun reverseVideo(
        context: Context,
        videoUri: String,
        srcInMs: Long = 0L,
        srcDurMs: Long = 0L,
        onProgress: (Float) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            val uri = Uri.parse(videoUri)
            if (videoUri.startsWith("content://") || videoUri.startsWith("file://")) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(videoUri)
            }

            val origDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
            val clipDur = if (srcDurMs > 0) srcDurMs else origDur
            val endMs = srcInMs + clipDur

            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            // Размеры видео должны быть чётными для видеокодека
            val width = ((rawW / 2) * 2).coerceAtLeast(16)
            val height = ((rawH / 2) * 2).coerceAtLeast(16)

            // Сэмплируем с шагом ~33мс (30 FPS)
            val fps = 30
            val frameStepMs = 1000L / fps
            val frameTimes = mutableListOf<Long>()
            var t = endMs
            while (t >= srcInMs) {
                frameTimes.add(t)
                t -= frameStepMs
            }

            if (frameTimes.isEmpty()) {
                retriever.release()
                return@withContext null
            }

            val outFile = File(context.cacheDir, "reversed_video_${System.currentTimeMillis()}.mp4")
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)

            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // Рисуем битмапы на входную поверхность кодера
            val egldisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            android.opengl.EGL14.eglInitialize(egldisplay, version, 0, version, 1)
            val EGL_RECORDABLE_ANDROID = 0x3142
            val attribList = intArrayOf(
                android.opengl.EGL14.EGL_RED_SIZE, 8,
                android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                android.opengl.EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            android.opengl.EGL14.eglChooseConfig(egldisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            val contextAttribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE)
            val eglContext = android.opengl.EGL14.eglCreateContext(egldisplay, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            val surfaceAttribs = intArrayOf(android.opengl.EGL14.EGL_NONE)
            val eglSurface = android.opengl.EGL14.eglCreateWindowSurface(egldisplay, configs[0], inputSurface, surfaceAttribs, 0)
            android.opengl.EGL14.eglMakeCurrent(egldisplay, eglSurface, eglSurface, eglContext)

            // GLES20 шейдерная программа для отрисовки текстуры
            val vss = """
                attribute vec4 aPos;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    gl_Position = aPos;
                    vUv = aUv;
                }
            """.trimIndent()
            val fss = """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex;
                void main() {
                    gl_FragColor = texture2D(uTex, vUv);
                }
            """.trimIndent()

            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(vs, vss)
            GLES20.glCompileShader(vs)
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(fs, fss)
            GLES20.glCompileShader(fs)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)

            val aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
            val aUvLoc = GLES20.glGetAttribLocation(program, "aUv")
            val uTexLoc = GLES20.glGetUniformLocation(program, "uTex")

            val posBuf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }
            val uvBuf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
                position(0)
            }

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexLoc, 0)

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            var presentationTimeUs = 0L

            fun drainEncoder(endOfStream: Boolean) {
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10000L else 0L)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            for (i in frameTimes.indices) {
                val fTimeMs = frameTimes[i]
                onProgress(i.toFloat() / frameTimes.size.toFloat())

                val bmp = runCatching {
                    retriever.getFrameAtTime(fTimeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()

                if (bmp != null) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

                    GLES20.glEnableVertexAttribArray(aPosLoc)
                    GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
                    GLES20.glEnableVertexAttribArray(aUvLoc)
                    GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuf)

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    GLES20.glDisableVertexAttribArray(aPosLoc)
                    GLES20.glDisableVertexAttribArray(aUvLoc)

                    EGLExt.eglPresentationTimeANDROID(egldisplay, eglSurface, presentationTimeUs * 1000L)
                    EGL14.eglSwapBuffers(egldisplay, eglSurface)
                    presentationTimeUs += (1_000_000L / fps)
                    bmp.recycle()
                }

                drainEncoder(endOfStream = false)
            }

            encoder.signalEndOfInputStream()
            drainEncoder(endOfStream = true)

            // Cleanup GL
            if (texIds[0] != 0) GLES20.glDeleteTextures(1, texIds, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            android.opengl.EGL14.eglDestroySurface(egldisplay, eglSurface)
            android.opengl.EGL14.eglDestroyContext(egldisplay, eglContext)
            android.opengl.EGL14.eglTerminate(egldisplay)

            encoder.stop()
            encoder.release()
            inputSurface.release()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            retriever.release()

            Log.d(TAG, "Видео реверс готов: ${outFile.absolutePath}, размер=${outFile.length()}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка реверса видео", e)
            null
        }
    }
}
