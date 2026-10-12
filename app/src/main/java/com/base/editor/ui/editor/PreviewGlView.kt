package com.base.editor.ui.editor

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.base.editor.core.ClipTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Окно превью на собственной GL-поверхности. Плеер (CompositionPlayer) рисует композицию в [Surface] от SurfaceTexture,
 * которым владеет этот вид, а сам вид только показывает последний кадр. Поэтому:
 *  • поверхность плеера не зависит от жизненного цикла Compose/SurfaceView и не пересоздаётся при жестах;
 *  • масштаб, сдвиг и поворот при жесте применяются матрицей к квадрату с текстурой на каждом кадре (60 к/с) —
 *    плеер при этом вообще не трогаем (никаких перемоток и рекомпозиций);
 *  • кадр вписывается по его РЕАЛЬНЫМ пропорциям, поэтому «сжатия» при смене формата быть не может.
 */
class PreviewGlView(context: Context) : GLSurfaceView(context) {
    /** Новая поверхность для плеера (после создания GL-контекста); null — поверхность потеряна. Вызывается на главном потоке. */
    var onSurface: ((Surface?) -> Unit)? = null

    private val renderer = Renderer()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var frameAspect = 9f / 16f
    @Volatile private var baked: ClipTransform? = null
    @Volatile private var current: ClipTransform? = null
    @Volatile private var clearOnNextFrame = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Реальные пропорции кадров плеера (ширина / высота холста композиции). */
    fun setFrameAspect(a: Float) { if (a > 0.05f && a < 20f) { frameAspect = a; requestRender() } }

    /** Живое положение кадра при жесте: [baked] — то, что уже «запечено» в кадре плеера, [current] — положение под пальцами. */
    fun setLive(baked: ClipTransform?, current: ClipTransform?) { this.baked = baked; this.current = current; requestRender() }

    /** Снять живое смещение В ТОТ КАДР, когда придёт следующий кадр плеера (уже с новым положением): без скачка. */
    fun clearLiveOnNextFrame() { clearOnNextFrame = true }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var aPos = 0; private var aUv = 0; private var uTexMat = 0; private var uTex = 0
        private var texId = 0
        private var st: SurfaceTexture? = null
        private var surface: Surface? = null
        private val frameReady = AtomicBoolean(false)
        private val texMat = FloatArray(16)
        private var w = 1; private var h = 1
        private val verts = FloatBuffer.allocate(8)
        private val uvs: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
        private val posDirect: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val m = Matrix(); private val inv = Matrix(); private val pts = FloatArray(8)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VS, FS)
            aPos = GLES20.glGetAttribLocation(program, "aPos"); aUv = GLES20.glGetAttribLocation(program, "aUv")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat"); uTex = GLES20.glGetUniformLocation(program, "uTex")
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texId = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            releaseSurface()
            val s = SurfaceTexture(texId)
            s.setOnFrameAvailableListener { frameReady.set(true); requestRender() }
            st = s; surface = Surface(s)
            val out = surface
            main.post { onSurface?.invoke(out) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width.coerceAtLeast(1); h = height.coerceAtLeast(1); GLES20.glViewport(0, 0, w, h) }

        override fun onDrawFrame(gl: GL10?) {
            val s = st
            val newFrame = frameReady.getAndSet(false)
            if (s != null && newFrame) { runCatching { s.updateTexImage() } }
            // новый кадр плеера уже содержит новое положение — смещение жеста снимаем в этот же кадр отрисовки
            if (newFrame && clearOnNextFrame) { clearOnNextFrame = false; baked = null; current = null }
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (s == null) return
            s.getTransformMatrix(texMat)

            // вписывание кадра по его пропорциям
            val va = w.toFloat() / h; val fa = frameAspect
            val fx = if (fa >= va) 1f else fa / va; val fy = if (fa >= va) va / fa else 1f
            // углы в пикселях от центра (y вниз): низ-лево, низ-право, верх-лево, верх-право (порядок под UV)
            pts[0] = -fx * w / 2f; pts[1] = fy * h / 2f
            pts[2] = fx * w / 2f; pts[3] = fy * h / 2f
            pts[4] = -fx * w / 2f; pts[5] = -fy * h / 2f
            pts[6] = fx * w / 2f; pts[7] = -fy * h / 2f
            val b = baked; val c = current
            if (b != null && c != null) {
                // дельта = текущее положение ∘ обратное к «запечённому»: масштаб/поворот вокруг центра, сдвиг в долях холста
                m.reset(); m.postScale(b.scale, b.scale); m.postRotate(b.rotationDeg); m.postTranslate(b.x * w, b.y * h)
                if (m.invert(inv)) {
                    m.set(inv)
                    m.postScale(c.scale, c.scale); m.postRotate(c.rotationDeg); m.postTranslate(c.x * w, c.y * h)
                    m.mapPoints(pts)
                }
            }
            posDirect.position(0)
            for (i in 0 until 4) { posDirect.put(pts[i * 2] / (w / 2f)); posDirect.put(-pts[i * 2 + 1] / (h / 2f)) }
            posDirect.position(0)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posDirect)
            GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun releaseSurface() {
            runCatching { surface?.release() }; runCatching { st?.release() }
            surface = null; st = null
        }

        private fun link(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }

    override fun onDetachedFromWindow() {
        // поверхность плеера принадлежит GL-контексту этого вида: пока вида нет — плееру выводить некуда
        main.post { onSurface?.invoke(null) }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val VS = "attribute vec2 aPos;\nattribute vec2 aUv;\nvarying vec2 vUv;\nvoid main() { gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }\n"
        const val FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 vUv;\nuniform samplerExternalOES uTex;\nuniform mat4 uTexMat;\nvoid main() { vec2 uv = (uTexMat * vec4(vUv, 0.0, 1.0)).xy; gl_FragColor = texture2D(uTex, uv); }\n"
    }
}
