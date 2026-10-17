package com.base.editor.media.gl

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.base.editor.core.TransitionSpec

/**
 * Шейдерный переход ВО входящий клип. Первые [durationUs] его времени кадр = shader(from, to, progress):
 *  • from — последний кадр уходящего клипа из [bridge];
 *  • to   — живой кадр входящего клипа;
 *  • progress — 0.0 … 1.0; ratio — ширина/высота кадра.
 * После окна перехода кадры идут без изменений. Если шейдер не собрался или моста нет —
 * эффект тихо работает как проходной (экспорт/просмотр не падают), причина уходит в [onFallback].
 */
@UnstableApi
class TransitionEffect(
    private val spec: TransitionSpec,
    private val bridge: TransitionBridge,
    private val durationUs: Long,
    private val onFallback: (String) -> Unit = {},
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { TransitionProgram(spec, bridge, durationUs, useHdr, onFallback) } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class TransitionProgram(
    private val spec: TransitionSpec,
    private val bridge: TransitionBridge,
    private val durationUs: Long,
    useHdr: Boolean,
    onFallback: (String) -> Unit,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private val blit = GlBlit()
    private val program: GlProgram? = try {
        GlProgram(VERTEX_SHADER, spec.fragmentSource)
    } catch (e: GlUtil.GlException) {
        Log.e(TAG, "шейдер ${spec.id} не собрался — работаем как проходной", e)
        onFallback("Переход «${spec.label}» не поддерживается этим устройством")
        null
    }
    private val vbo = createVbo(GlUtil.getNormalizedCoordinateBounds())
    private val locProgress = program?.getUniformLocation("progress") ?: -1
    private val locRatio = program?.getUniformLocation("ratio") ?: -1
    private val locFrom = program?.getUniformLocation("from") ?: -1
    private val locTo = program?.getUniformLocation("to") ?: -1

    private var w = 0
    private var h = 0
    private var baseUs = Long.MIN_VALUE      // время первого кадра клипа (на случай ненулевого старта)

    override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

    override fun flush() { super.flush(); baseUs = Long.MIN_VALUE }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
            val elapsed = presentationTimeUs - baseUs
            val active = program != null && elapsed < durationUs && bridge.hasFrame && bridge.width == w && bridge.height == h
            if (!active) { blit.draw(inputTexId); return }

            val p = program!!
            val progress = (elapsed.toFloat() / durationUs).coerceIn(0f, 1f)
            p.use()
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glVertexAttribPointer(0, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bridge.textureId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            // uniform'ы, вырезанные компилятором (шейдер их не использует), пропускаем
            if (locFrom >= 0) GLES20.glUniform1i(locFrom, 0)
            if (locTo >= 0) GLES20.glUniform1i(locTo, 1)
            if (locProgress >= 0) GLES20.glUniform1f(locProgress, progress)
            if (locRatio >= 0) GLES20.glUniform1f(locRatio, w.toFloat() / h)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        runCatching {
            blit.release()
            program?.delete()
            deleteVbo(vbo)
            bridge.release()
        }   // переход — последний потребитель моста
    }

    private companion object {
        const val TAG = "BaseTransition"

        fun createVbo(data: FloatArray): Int {
            val b = GlUtil.createBuffer(data)
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, b, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            return ids[0]
        }

        fun deleteVbo(id: Int) {
            if (id != 0) {
                val ids = intArrayOf(id)
                GLES20.glDeleteBuffers(1, ids, 0)
            }
        }
    }
}
