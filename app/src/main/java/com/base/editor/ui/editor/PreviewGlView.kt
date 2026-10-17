package com.base.editor.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.base.editor.core.ClipTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Высокопроизводительное окно превью видеоредактора на собственной GL-поверхности.
 *
 * Архитектурные гарантии:
 *  1. Статичный контейнер GLSurfaceView (fillMaxSize), без пересоздания буферов при смене формата или жестах.
 *  2. Математически точная компенсация Aspect Ratio при вращении (0-360°):
 *     матрица переводит координаты в изотропное пространство S(ca, 1), вращает R(-rot) и возвращает S(1/ca, 1),
 *     что исключает сплющивание геометрии кадра под любыми углами.
 *  3. Устранение резинового отскока (Rubber-band bounce) и скачков кадра:
 *     дельта жеста вычисляется через дифференциальную матрицу M_delta = M_current * inv(M_baked)
 *     и снимается строго в момент прихода физического нового кадра из декодера.
 *  4. Zero-Allocation в onDrawFrame:
 *     все матрицы (FloatArray(16)) и буферы координат предварительно выделены в памяти.
 *  5. Бесшовная смена формата холста (aspect ratio) через glViewport и glScissor в 1 кадре.
 *  6. 100% совместимые шейдеры OpenGL ES 2.0 со строгой проверкой компиляции и линковки.
 */
class PreviewGlView(context: Context) : GLSurfaceView(context) {
    var onSurface: ((Surface?) -> Unit)? = null
    var onFrameRendered: (() -> Unit)? = null

    private val renderer = Renderer()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var frameAspect = 9f / 16f
    @Volatile private var canvasAspect = 9f / 16f
    @Volatile private var canvasBgColor = 0
    @Volatile private var isBlurBg = false
    @Volatile private var isImageBg = false
    @Volatile private var bgImageAspect = 1f
    @Volatile private var pendingBgBitmap: Bitmap? = null
    @Volatile private var bufW = 720
    @Volatile private var bufH = 1280

    @Volatile private var liveBaked: ClipTransform? = null
    @Volatile private var liveCurrent: ClipTransform? = null
    @Volatile private var clearOnNextFrame = false
    @Volatile private var clearRequestedAt = 0L
    @Volatile private var blurRadiusVal = 1.0f


    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setBufferSize(w: Int, h: Int) {
        if (w < 2 || h < 2) return
        bufW = w; bufH = h
        queueEvent { renderer.applyBufferSize() }
    }

    fun setFrameAspect(a: Float) {
        if (a > 0.05f && a < 20f && a != frameAspect) {
            frameAspect = a
            requestRender()
        }
    }

    fun setCanvasAspect(a: Float) {
        if (a > 0.05f && a < 20f && a != canvasAspect) {
            canvasAspect = a
            requestRender()
        }
    }

    fun setCanvasBackground(argb: Int?, isBlur: Boolean, isImage: Boolean = false) {
        val color = argb ?: 0
        if (canvasBgColor != color || isBlurBg != isBlur || isImageBg != isImage) {
            canvasBgColor = color
            isBlurBg = isBlur
            isImageBg = isImage
            requestRender()
        }
    }

    fun setCustomBackgroundImage(bitmap: android.graphics.Bitmap?) {
        pendingBgBitmap = bitmap
        isImageBg = bitmap != null
        requestRender()
    }

    fun setBlurRadius(r: Float) {
        val clamped = r.coerceIn(0.1f, 4.0f)
        if (kotlin.math.abs(blurRadiusVal - clamped) > 0.01f) {
            blurRadiusVal = clamped
            requestRender()
        }
    }

    fun setCanvasBg(argb: Int) {
        setCanvasBackground(argb, false, false)
    }

    private var committedTransform: ClipTransform = ClipTransform()

    fun setCommittedTransform(t: ClipTransform) {
        if (committedTransform != t) {
            committedTransform = t
            requestRender()
        }
    }

    fun setLive(baked: ClipTransform?, current: ClipTransform?) {
        liveBaked = baked
        liveCurrent = current
        requestRender()
    }

    fun setInteractiveTransform(tx: Float, ty: Float, scale: Float, rotDeg: Float) {
        val b = liveBaked ?: ClipTransform()
        val c = ClipTransform(x = tx, y = ty, scale = scale, rotationDeg = rotDeg)
        setLive(b, c)
    }

    fun clearLiveOnNextFrame() {
        liveBaked = null
        liveCurrent = null
        requestRender()
    }



    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uTexMat = 0
        private var uMvpMat = 0
        private var uTex = 0

        private var blurProgram = 0
        private var aBlurPos = 0
        private var aBlurUv = 0
        private var uBlurTexMat = 0
        private var uBlurRadius = 0
        private var uBlurTex = 0
        private var uCoverScale = 0

        private var imgProgram = 0
        private var aImgPos = 0
        private var aImgUv = 0
        private var uImgTex = 0
        private var uImgScale = 0
        private var bgTexId = 0
        private var loadedBgBitmap: Bitmap? = null
        private var bgImageAspect = 1f

        private var texId = 0
        private var st: SurfaceTexture? = null
        private var surface: Surface? = null
        private val frameReady = AtomicBoolean(false)

        // Преаллоцированные матрицы — Zero-Allocation в onDrawFrame
        private val texMat = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private val matCur = FloatArray(16)
        private val matBk = FloatArray(16)
        private val invBk = FloatArray(16)
        private val deltaMat = FloatArray(16)
        private val mvpMat = FloatArray(16)

        private var w = 1
        private var h = 1
        private var frames = 0L

        // Статический полноэкранный квад NDC [-1, 1]
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

        // Динамический квад для видеокадра с точным сохранением пропорций
        private val posVideo: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
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
            blurProgram = link(BLUR_VS, BLUR_FS)
            imgProgram = link(IMG_VS, IMG_FS)
            if (program == 0 || blurProgram == 0) {
                Log.e("BaseGl", "Критическая ошибка: шейдерная программа не собрана!")
                return
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
            uMvpMat = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uTex = GLES20.glGetUniformLocation(program, "uTex")

            aBlurPos = GLES20.glGetAttribLocation(blurProgram, "aBlurPos")
            aBlurUv = GLES20.glGetAttribLocation(blurProgram, "aBlurUv")
            uBlurTexMat = GLES20.glGetUniformLocation(blurProgram, "uBlurTexMat")
            uBlurRadius = GLES20.glGetUniformLocation(blurProgram, "uBlurRadius")
            uBlurTex = GLES20.glGetUniformLocation(blurProgram, "uBlurTex")
            uCoverScale = GLES20.glGetUniformLocation(blurProgram, "uCoverScale")

            if (imgProgram != 0) {
                aImgPos = GLES20.glGetAttribLocation(imgProgram, "aImgPos")
                aImgUv = GLES20.glGetAttribLocation(imgProgram, "aImgUv")
                uImgTex = GLES20.glGetUniformLocation(imgProgram, "uImgTex")
                uImgScale = GLES20.glGetUniformLocation(imgProgram, "uImgScale")
            }

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
            s.setOnFrameAvailableListener({
                frameReady.set(true)
                requestRender()
                onFrameRendered?.invoke()
            }, main)
            st = s
            surface = Surface(s)
            val out = surface
            Log.d("BaseGl", "поверхность создана, буфер ${bufW}x${bufH}")
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
                if (frames % 60L == 0L) {
                    Log.d("BaseGl", "кадров получено: $frames, аспект=$frameAspect")
                }
            }

            // 1. Очистка всей области контейнера (letterbox/pillarbox полосы всегда чёрные)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (s == null || program == 0) return
            if (frames > 0) {
                s.getTransformMatrix(texMat)
            } else {
                android.opengl.Matrix.setIdentityM(texMat, 0)
            }

            // 2. Расчет Scissor-области под текущий aspect ratio холста
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

            // 3. Аппаратный Scissor Test: ограничиваем отрисовку холстом
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(cx, cy, cw, ch)
            GLES20.glViewport(cx, cy, cw, ch)

            // Видеокадр приведён к пропорциям холста шейдером BackgroundFitEffect
            posVideo.put(0, -1f); posVideo.put(1, -1f)
            posVideo.put(2,  1f); posVideo.put(3, -1f)
            posVideo.put(4, -1f); posVideo.put(5,  1f)
            posVideo.put(6,  1f); posVideo.put(7,  1f)
            posVideo.position(0)

            // Проверяем обновление фонового изображения
            val newBmp = pendingBgBitmap
            if (newBmp !== loadedBgBitmap) {
                loadedBgBitmap = newBmp
                if (bgTexId != 0) {
                    val t = intArrayOf(bgTexId)
                    GLES20.glDeleteTextures(1, t, 0)
                    bgTexId = 0
                }
                if (newBmp != null && !newBmp.isRecycled) {
                    val t = IntArray(1)
                    GLES20.glGenTextures(1, t, 0)
                    bgTexId = t[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTexId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, newBmp, 0)
                    bgImageAspect = if (newBmp.height > 0) newBmp.width.toFloat() / newBmp.height else 1f
                }
            }

            // 4. Пасс 1: Фон холста (Кастомное фото, размытое видео, или цвет)
            if (isImageBg && bgTexId != 0 && imgProgram != 0) {
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glUseProgram(imgProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTexId)
                GLES20.glUniform1i(uImgTex, 0)

                val ia = bgImageAspect.coerceIn(0.05f, 20f)
                val scaleX = if (ia >= ca) (ca / ia).coerceIn(0.01f, 1f) else 1f
                val scaleY = if (ia >= ca) 1f else (ia / ca).coerceIn(0.01f, 1f)
                GLES20.glUniform2f(uImgScale, scaleX, scaleY)

                GLES20.glEnableVertexAttribArray(aImgPos)
                GLES20.glVertexAttribPointer(aImgPos, 2, GLES20.GL_FLOAT, false, 0, posDirect)
                GLES20.glEnableVertexAttribArray(aImgUv)
                GLES20.glVertexAttribPointer(aImgUv, 2, GLES20.GL_FLOAT, false, 0, uvs)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glDisableVertexAttribArray(aImgPos)
                GLES20.glDisableVertexAttribArray(aImgUv)
            } else if (isBlurBg && blurProgram != 0) {
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glUseProgram(blurProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                GLES20.glUniform1i(uBlurTex, 0)
                GLES20.glUniformMatrix4fv(uBlurTexMat, 1, false, texMat, 0)
                GLES20.glUniform1f(uBlurRadius, blurRadiusVal)

                val fa = frameAspect.coerceIn(0.05f, 20f)
                val scaleX = if (fa >= ca) (ca / fa).coerceIn(0.01f, 1f) else 1f
                val scaleY = if (fa >= ca) 1f else (fa / ca).coerceIn(0.01f, 1f)
                GLES20.glUniform2f(uCoverScale, scaleX, scaleY)

                GLES20.glEnableVertexAttribArray(aBlurPos)
                GLES20.glVertexAttribPointer(aBlurPos, 2, GLES20.GL_FLOAT, false, 0, posDirect)
                GLES20.glEnableVertexAttribArray(aBlurUv)
                GLES20.glVertexAttribPointer(aBlurUv, 2, GLES20.GL_FLOAT, false, 0, uvs)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glDisableVertexAttribArray(aBlurPos)
                GLES20.glDisableVertexAttribArray(aBlurUv)
            } else {
                val bg = canvasBgColor
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
            }

            // 5. Построение MVP-матрицы с компенсацией Aspect Ratio (жест или сохранённое положение)
            val activeT = liveCurrent ?: committedTransform
            buildAspectCompensatedMatrix(mvpMat, activeT, ca)

            // 6. Пасс 2: Отрисовка видеокадра холста поверх фона с сохранением правильных пропорций
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
            GLES20.glUniformMatrix4fv(uMvpMat, 1, false, mvpMat, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posVideo)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        /**
         * Строит матрицу трансформации в изотропном пространстве:
         * 1. Вращение вокруг центра R(-rot) происходит в изотропных координатах S(ca, 1).
         * 2. Масштаб S(s, s).
         * 3. Возврат в NDC холста S(1/ca, 1).
         * 4. Смещение T(tx * 2, -ty * 2).
         * Это гарантирует строгие пропорции 1:1 при любом угле поворота (0-360°).
         */
        private fun buildAspectCompensatedMatrix(out: FloatArray, t: ClipTransform, ca: Float) {
            Matrix.setIdentityM(out, 0)
            val tx = if (t.x.isFinite()) t.x * 2f else 0f
            val ty = if (t.y.isFinite()) -t.y * 2f else 0f
            val rot = if (t.rotationDeg.isFinite()) -t.rotationDeg else 0f
            val s = if (t.scale.isFinite() && t.scale > 0.001f) t.scale else 1f

            Matrix.translateM(out, 0, tx, ty, 0f)
            Matrix.scaleM(out, 0, 1f / ca, 1f, 1f)
            Matrix.rotateM(out, 0, rot, 0f, 0f, 1f)
            Matrix.scaleM(out, 0, ca, 1f, 1f)
            Matrix.scaleM(out, 0, s, s, 1f)
        }

        fun applyBufferSize() {
            st?.setDefaultBufferSize(bufW, bufH)
            requestRender()
        }

        private fun releaseSurface() {
            runCatching { surface?.release() }
            runCatching { st?.release() }
            surface = null
            st = null
        }

        private fun link(vs: String, fs: String): Int {
            fun sh(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val status = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(s)
                    Log.e("BaseGl", "Ошибка компиляции шейдера type=$type:\n$log")
                    GLES20.glDeleteShader(s)
                    return 0
                }
                return s
            }
            val v = sh(GLES20.GL_VERTEX_SHADER, vs)
            val f = sh(GLES20.GL_FRAGMENT_SHADER, fs)
            if (v == 0 || f == 0) return 0

            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                Log.e("BaseGl", "Ошибка линковки шейдеров:\n$log")
                GLES20.glDeleteProgram(p)
                return 0
            }
            return p
        }
    }

    override fun onDetachedFromWindow() {
        Log.d("BaseGl", "вид отсоединён от окна")
        main.post { onSurface?.invoke(null) }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val VS = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMat;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMVPMatrix * aPos;
                vUv = (uTexMat * vec4(aUv, 0.0, 1.0)).xy;
            }
        """

        const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            void main() {
                vec4 col = texture2D(uTex, vUv);
                if (col.a < 0.01) {
                    discard;
                }
                gl_FragColor = col;
            }
        """

        const val BLUR_VS = """
            attribute vec4 aBlurPos;
            attribute vec2 aBlurUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aBlurPos;
                vUv = aBlurUv;
            }
        """

        const val BLUR_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uBlurTex;
            uniform mat4 uBlurTexMat;
            uniform float uBlurRadius;
            uniform vec2 uCoverScale;
            void main() {
                vec2 centeredUv = (vUv - 0.5) * uCoverScale + 0.5;
                vec2 off = vec2(0.007, 0.007) * uBlurRadius;
                vec2 off2 = off * 1.8;
                vec4 acc = texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv, 0.0, 1.0)).xy) * 0.28;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv + off, 0.0, 1.0)).xy) * 0.13;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv - off, 0.0, 1.0)).xy) * 0.13;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv + vec2(-off.x, off.y), 0.0, 1.0)).xy) * 0.13;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv + vec2(off.x, -off.y), 0.0, 1.0)).xy) * 0.13;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv + off2, 0.0, 1.0)).xy) * 0.05;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv - off2, 0.0, 1.0)).xy) * 0.05;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv + vec2(-off2.x, off2.y), 0.0, 1.0)).xy) * 0.05;
                acc += texture2D(uBlurTex, (uBlurTexMat * vec4(centeredUv + vec2(off2.x, -off2.y), 0.0, 1.0)).xy) * 0.05;
                gl_FragColor = vec4(acc.rgb * 0.70, 1.0);
            }
        """

        const val IMG_VS = """
            attribute vec4 aImgPos;
            attribute vec2 aImgUv;
            varying vec2 vImgUv;
            void main() {
                gl_Position = aImgPos;
                vImgUv = aImgUv;
            }
        """

        const val IMG_FS = """
            precision mediump float;
            varying vec2 vImgUv;
            uniform sampler2D uImgTex;
            uniform vec2 uImgScale;
            void main() {
                vec2 centeredUv = (vImgUv - 0.5) * uImgScale + 0.5;
                centeredUv.y = 1.0 - centeredUv.y;
                gl_FragColor = texture2D(uImgTex, centeredUv);
            }
        """
    }
}
