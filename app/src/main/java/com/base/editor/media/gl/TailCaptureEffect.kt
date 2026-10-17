package com.base.editor.media.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Ставится на клип, ПОСЛЕ которого есть переход. Пропускает кадры без изменений
 * и копирует каждый кадр в [bridge] — к концу клипа там лежит его последний кадр.
 */
@UnstableApi
class TailCaptureEffect(
    private val bridge: TransitionBridge,
    private val durationUs: Long = 0L,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { TailCaptureProgram(bridge, durationUs, useHdr) } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class TailCaptureProgram(
    private val bridge: TransitionBridge,
    private val durationUs: Long,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private val blit = GlBlit()
    private var w = 0
    private var h = 0
    private var baseUs = Long.MIN_VALUE
    private var bridgeFbo = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth; h = inputHeight
        baseUs = Long.MIN_VALUE
        return Size(inputWidth, inputHeight)
    }

    override fun flush() {
        super.flush()
        baseUs = Long.MIN_VALUE
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            blit.draw(inputTexId)                         // кадр уходит дальше в выходной FBO Media3
            if (w > 0 && h > 0) {
                bridge.ensure(w, h)
                val prevFbo = IntArray(1)
                val prevVp = IntArray(4)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevVp, 0)
                if (bridgeFbo == 0) {
                    val f = IntArray(1)
                    GLES20.glGenFramebuffers(1, f, 0)
                    bridgeFbo = f[0]
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bridgeFbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, bridge.textureId, 0)
                GLES20.glViewport(0, 0, w, h)
                blit.draw(inputTexId)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
                GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
                bridge.hasFrame = true
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() {
        super.release()
        if (bridgeFbo != 0) {
            val f = intArrayOf(bridgeFbo)
            GLES20.glDeleteFramebuffers(1, f, 0)
            bridgeFbo = 0
        }
        runCatching { blit.release() }   // текстуру моста НЕ удаляем: она нужна входящему клипу
    }
}
