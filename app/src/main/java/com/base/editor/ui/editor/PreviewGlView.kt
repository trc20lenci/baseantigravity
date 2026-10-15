package com.base.editor.ui.editor

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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
 * которым владеет этот вид, а сам вид отображает кадры с аппаратной трансформацией на GPU (MVP Matrix).
 *
 * Архитектурные свойства:
 *  • Контейнер GLSurfaceView абсолютно статичен в иерархии Compose (fillMaxSize, без динамического aspectRatio),
 *    что исключает пересоздание SurfaceControl и сброс декодеров SurfaceFlinger'ом.
 *  • Все трансформации жестов (панорамирование, pinch-to-zoom, поворот) и адаптация пропорций холста (aspect ratio)
 *    выполняются в вершинном шейдере за 0 мс на GPU при каждом вызове onDrawFrame (60/120 FPS).
 *  • Аппаратный Scissor Test гарантирует отсечение содержимого строго по границам холста без деформации геометрии.
 */
class PreviewGlView(context: Context) : GLSurfaceView(context) {
    /** Новая поверхность для плеера (после создания GL-контекста); null — поверхность потеряна. Вызывается на главном потоке. */
    var onSurface: ((Surface?) -> Unit)? = null

    private val renderer = Renderer()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var frameAspect = 9f / 16f
    @Volatile private var canvasAspect = 9f / 16f
    @Volatile private var canvasBg = 0
    @Volatile private var bufW = 720
    @Volatile private var bufH = 1280
    @Volatile private var baked: ClipTransform? = null
    @Volatile private var current: ClipTransform? = null
    @Volatile private var clearOnNextFrame = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * Размер буфера поверхности плеера = размер холста композиции.
     */
    fun setBufferSize(w: Int, h: Int) {
        if (w < 2 || h < 2) return
        bufW = w; bufH = h
        queueEvent { renderer.applyBufferSize() }
    }

    /** Реальные пропорции кадров плеера (ширина / высота холста композиции). */
    fun setFrameAspect(a: Float) {
        if (a > 0.05f && a < 20f && a != frameAspect) {
            frameAspect = a
            requestRender()
        }
    }

    /** Пропорции целевого холста (9:16, 16:9, 1:1 и т.д.): задают scissor-рамку и проекцию. */
    fun setCanvasAspect(a: Float) {
        if (a > 0.05f && a < 20f && a != canvasAspect) {
            canvasAspect = a
            requestRender()
        }
    }

    /** Цвет фона холста (ARGB). */
    fun setCanvasBg(argb: Int) {
        if (canvasBg != argb) {
            canvasBg = argb
            requestRender()
        }
    }

    /** Живое положение кадра при жесте: [baked] — запечённое в кадре плеера, [current] — под пальцами. */
    fun setLive(baked: ClipTransform?, current: ClipTransform?) {
        this.baked = baked
        this.current = current
        requestRender()
    }

    /** Снять живое смещение В ТОТ КАДР, когда придёт следующий кадр плеера. */
    fun clearLiveOnNextFrame() {
        clearOnNextFrame = true
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uTexMat = 0
        private var uMvpMat = 0
        private var uTex = 0
        private var texId = 0
        private var st: SurfaceTexture? = null
        private var surface: Surface? = null
        private val frameReady = AtomicBoolean(false)

        private val texMat = FloatArray(16)
        private val mvpMat = FloatArray(16)
        private val fitMat = FloatArray(16)
        private val matCur = FloatArray(16)
        private val matBk = FloatArray(16)
        private val invBk = FloatArray(16)
        private val deltaMat = FloatArray(16)

        private var w = 1
        private var h = 1
        private var frames = 0L

        // Статичный полноэкранный квад (Triangle Strip: Bottom-Left, Bottom-Right, Top-Left, Top-Right)
        private val posDirect: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    -1f, -1f,
                     1f, -1f,
                    -1f,  1f,
                     1f,  1f
                ))
                position(0)
            }

        private val uvs: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    0f, 0f,
                    1f, 0f,
                    0f, 1f,
                    1f, 1f
                ))
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VS, FS)
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
            uMvpMat = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uTex = GLES20.glGetUniformLocation(program, "uTex")

            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texId = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            releaseSurface()
            val s = SurfaceTexture(texId)
            s.setDefaultBufferSize(bufW, bufH)
            s.setOnFrameAvailableListener {
                frameReady.set(true)
                requestRender()
            }
            st = s
            surface = Surface(s)
            val out = surface
            android.util.Log.d("BaseGl", "поверхность создана, буфер ${bufW}x${bufH}")
            main.post { onSurface?.invoke(out) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w = width.coerceAtLeast(1)
            h = height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            val s = st
            val newFrame = frameReady.getAndSet(false)
            if (s != null && newFrame) {
                runCatching { s.updateTexImage() }
                frames++
                if (frames % 120L == 0L) {
                    android.util.Log.d("BaseGl", "кадров получено: $frames, аспект=$frameAspect, живое=${current != null}")
                }
            }

            if (newFrame && clearOnNextFrame) {
                clearOnNextFrame = false
                baked = null
                current = null
            }

            // 1. Очистка всей области вида (letterbox/pillarbox полосы всегда чёрные)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (s == null) return
            s.getTransformMatrix(texMat)

            // 2. Расчёт геометрии холста внутри GLSurfaceView
            val va = w.toFloat() / h
            val ca = canvasAspect.coerceIn(0.05f, 20f)
            val cw: Int
            val ch: Int
            val cx: Int
            val cy: Int
            if (ca >= va) {
                cw = w
                ch = (w / ca).toInt().coerceAtLeast(1)
                cx = 0
                cy = (h - ch) / 2
            } else {
                ch = h
                cw = (h * ca).toInt().coerceAtLeast(1)
                cx = (w - cw) / 2
                cy = 0
            }

            // 3. Аппаратный Scissor Test: отсекаем отрисовку строго в пределах холста
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(cx, cy, cw, ch)
            GLES20.glViewport(cx, cy, cw, ch)

            val bg = canvasBg
            if (bg != 0) {
                val r = ((bg shr 16) and 0xFF) / 255f
                val g = ((bg shr 8) and 0xFF) / 255f
                val b = (bg and 0xFF) / 255f
                val a = ((bg shr 24) and 0xFF) / 255f
                GLES20.glClearColor(r, g, b, a)
            } else {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 4. Построение MVP-матрицы для вершинного шейдера
            // Вписывание соотношений сторон видео (frameAspect) в холст (canvasAspect)
            val fa = frameAspect.coerceIn(0.05f, 20f)
            val fitX = if (fa >= ca) 1f else fa / ca
            val fitY = if (fa >= ca) ca / fa else 1f
            Matrix.setIdentityM(fitMat, 0)
            Matrix.scaleM(fitMat, 0, fitX, fitY, 1f)

            // Жесты (смещение pan, масштаб pinch, поворот rotation)
            val b = baked
            val c = current
            if (b != null && c != null) {
                buildTransformMatrix(matCur, c)
                buildTransformMatrix(matBk, b)
                if (Matrix.invertM(invBk, 0, matBk, 0)) {
                    Matrix.multiplyMM(deltaMat, 0, matCur, 0, invBk, 0)
                } else {
                    Matrix.setIdentityM(deltaMat, 0)
                }
                Matrix.multiplyMM(mvpMat, 0, deltaMat, 0, fitMat, 0)
            } else {
                System.arraycopy(fitMat, 0, mvpMat, 0, 16)
            }

            // 5. Отрисовка видеокадра через шейдер
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
            GLES20.glUniformMatrix4fv(uMvpMat, 1, false, mvpMat, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posDirect)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        private fun buildTransformMatrix(out: FloatArray, t: ClipTransform) {
            Matrix.setIdentityM(out, 0)
            // Доли холста -> координаты в NDC [-1, 1]; ось Y в GL направлена вверх
            val tx = if (t.x.isFinite()) t.x * 2f else 0f
            val ty = if (t.y.isFinite()) -t.y * 2f else 0f
            val rot = if (t.rotationDeg.isFinite()) -t.rotationDeg else 0f
            val s = if (t.scale.isFinite() && t.scale > 0.001f) t.scale else 1f

            Matrix.translateM(out, 0, tx, ty, 0f)
            Matrix.rotateM(out, 0, rot, 0f, 0f, 1f)
            Matrix.scaleM(out, 0, s, s, 1f)
        }

        fun applyBufferSize() {
            runCatching { st?.setDefaultBufferSize(bufW, bufH) }
        }

        private fun releaseSurface() {
            runCatching { surface?.release() }
            runCatching { st?.release() }
            surface = null
            st = null
        }

        private fun link(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }

    override fun onDetachedFromWindow() {
        android.util.Log.d("BaseGl", "вид отсоединён от окна")
        main.post { onSurface?.invoke(null) }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val VS = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMat;
            attribute vec4 aPos;
            attribute vec4 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMVPMatrix * aPos;
                vUv = (uTexMat * aUv).xy;
            }
        """

        const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """
    }
}
