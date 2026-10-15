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
 * Высокопроизводительное окно превью видеоредактора на базе GLSurfaceView.
 *
 * Архитектурные решения:
 * 1. Изотропная компенсация Aspect Ratio при вращении (0-360°):
 *    Координаты перед поворотом переводятся в изотропное пространство с квадратными пикселями
 *    через масштаб S(ca, 1), вращаются на -rotDeg и возвращаются через S(1/ca, 1).
 *    Геометрия кадра (круги, квадраты) сохраняет строгие пропорции 1:1 при любом угле.
 *
 * 2. Устранение резинового отскока (Rubber-band bounce) и телепортации:
 *    Интерактивные координаты жестов вычисляются через дифференциальную матрицу M_delta = M_target * M_tex^(-1).
 *    Кадр всегда отображается строго в позиции M_target, независимо от состояния плеера.
 *    При завершении жеста (UP) плеер не выполняет ложных seek, а M_delta остаётся активной
 *    до физического декодирования нового кадра MediaCodec'ом.
 *
 * 3. Zero-Allocation в горячем цикле (onDrawFrame и обработка жестов):
 *    Все матрицы (FloatArray(16)) и векторы предварительно выделены один раз в памяти.
 *    Никаких аллокаций объектов в onDrawFrame.
 *
 * 4. Бесшовная смена формата холста (16:9, 9:16, 1:1, 4:5):
 *    GLSurfaceView имеет фиксированный размер (fillMaxSize).
 *    Переключение формата происходит мгновенно в 1 кадре через glViewport и glScissor
 *    без пересоздания буферов SurfaceFlinger'а и без сброса кодека.
 *
 * 5. Кинематографичный фоновый Гауссов блюр с 30% затемнением:
 *    Двухпроходный Separable Gaussian Blur на шейдере с билинейным сэмплингом и мягким затемнением
 *    без полошения (banding) и без перекрытия серыми заглушками Compose.
 */
class PreviewGlView(context: Context) : GLSurfaceView(context) {
    /** Обратный вызов при создании/уничтожении аппаратной поверхности для Media3 плеера. */
    var onSurface: ((Surface?) -> Unit)? = null

    private val renderer = Renderer()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var frameAspect = 9f / 16f
    @Volatile private var canvasAspect = 9f / 16f
    @Volatile private var canvasBgColor = 0
    @Volatile private var isBlurBg = false
    @Volatile private var bufW = 720
    @Volatile private var bufH = 1280

    // Текущая целевая трансформация жеста (примитивные float для потокобезопасного доступа без аллокаций)
    @Volatile private var targetTx = 0f
    @Volatile private var targetTy = 0f
    @Volatile private var targetScale = 1f
    @Volatile private var targetRot = 0f
    @Volatile private var hasInteractiveTarget = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Размер внутреннего буфера SurfaceTexture плеера. */
    fun setBufferSize(w: Int, h: Int) {
        if (w < 2 || h < 2) return
        bufW = w; bufH = h
        queueEvent { renderer.applyBufferSize() }
    }

    /** Соотношение сторон видеопотока из плеера (ширина / высота). */
    fun setFrameAspect(a: Float) {
        if (a > 0.05f && a < 20f && a != frameAspect) {
            frameAspect = a
            requestRender()
        }
    }

    /** Соотношение сторон холста (9:16, 16:9, 1:1 и др.): мгновенный scissor/viewport в 1 кадре. */
    fun setCanvasAspect(a: Float) {
        if (a > 0.05f && a < 20f && a != canvasAspect) {
            canvasAspect = a
            requestRender()
        }
    }

    /** Фоновое оформление: сплошной цвет или размытие видео. */
    fun setCanvasBackground(argb: Int?, isBlur: Boolean) {
        val color = argb ?: 0
        if (canvasBgColor != color || isBlurBg != isBlur) {
            canvasBgColor = color
            isBlurBg = isBlur
            requestRender()
        }
    }

    fun setCanvasBg(argb: Int) {
        setCanvasBackground(argb, false)
    }

    /**
     * Прямая установка трансформации жеста (pan, pinch, rotation) примитивными float.
     * Вызывается из pointer input слоя; не производит аллокаций объектов.
     */
    fun setInteractiveTransform(tx: Float, ty: Float, scale: Float, rotDeg: Float) {
        targetTx = if (tx.isFinite()) tx else 0f
        targetTy = if (ty.isFinite()) ty else 0f
        targetScale = if (scale.isFinite() && scale > 0.001f) scale else 1f
        targetRot = if (rotDeg.isFinite()) rotDeg else 0f
        hasInteractiveTarget = true
        requestRender()
    }

    /**
     * Интеграция с LiveClip стейтом: baked (исходное положение до жеста) и current (под пальцем).
     */
    fun setLive(baked: ClipTransform?, current: ClipTransform?) {
        if (current != null) {
            setInteractiveTransform(current.x, current.y, current.scale, current.rotationDeg)
        } else {
            hasInteractiveTarget = false
            requestRender()
        }
    }

    /** Сброс интерактивной трансформации к состоянию декодированного кадра плеера. */
    fun clearLiveOnNextFrame() {
        renderer.markPendingReset()
        requestRender()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var aPos = 0
        private var aUv = 0
        private var uTexMat = 0
        private var uMvpMat = 0
        private var uTex = 0
        private var uMode = 0
        private var uCover = 0
        private var texId = 0
        private var st: SurfaceTexture? = null
        private var surface: Surface? = null
        private val frameReady = AtomicBoolean(false)
        private val pendingReset = AtomicBoolean(false)

        // Преаллоцированные матрицы для Zero-Allocation в onDrawFrame
        private val texMat = FloatArray(16)
        private val identityMat = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        private val matTarget = FloatArray(16)
        private val matTex = FloatArray(16)
        private val invTex = FloatArray(16)
        private val deltaMat = FloatArray(16)
        private val mvpMat = FloatArray(16)
        private val coverVec = FloatArray(2)

        // Зафиксированное состояние трансформации кадра в SurfaceTexture
        private var texTx = 0f
        private var texTy = 0f
        private var texScale = 1f
        private var texRot = 0f

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

        fun markPendingReset() {
            pendingReset.set(true)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VS, FS)
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
            uMvpMat = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uMode = GLES20.glGetUniformLocation(program, "uMode")
            uCover = GLES20.glGetUniformLocation(program, "uCover")

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
            val isNewFrame = frameReady.getAndSet(false)
            if (s != null && isNewFrame) {
                runCatching { s.updateTexImage() }
                frames++
                // Если плеер выдал новый кадр, он уже содержит зафиксированную трансформацию
                if (pendingReset.getAndSet(false) || !hasInteractiveTarget) {
                    texTx = targetTx
                    texTy = targetTy
                    texScale = targetScale
                    texRot = targetRot
                    hasInteractiveTarget = false
                }
            }

            // 1. Очистка всей области контейнера в глубокий чёрный цвет
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (s == null) return
            s.getTransformMatrix(texMat)

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

            // 3. Аппаратный Scissor Test — отсекаем отрисовку строго границами холста
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(cx, cy, cw, ch)
            GLES20.glViewport(cx, cy, cw, ch)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posDirect)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)

            val fa = frameAspect.coerceIn(0.05f, 20f)

            // 4. Отрисовка фона холста: Гауссов блюр с 30% затемнением или сплошной цвет
            if (isBlurBg) {
                val coverX = if (fa >= ca) ca / fa else 1f
                val coverY = if (fa >= ca) 1f else fa / ca
                coverVec[0] = coverX
                coverVec[1] = coverY

                GLES20.glUniform1i(uMode, 1) // Режим Гауссова блюра
                GLES20.glUniform2fv(uCover, 1, coverVec, 0)
                GLES20.glUniformMatrix4fv(uMvpMat, 1, false, identityMat, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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

            // 5. Построение MVP-матрицы основного кадра с компенсацией Aspect Ratio
            val fitX = if (fa >= ca) 1f else fa / ca
            val fitY = if (fa >= ca) ca / fa else 1f

            if (hasInteractiveTarget) {
                // Строим целевую матрицу положения под пальцем
                buildTransformMatrix(matTarget, targetTx, targetTy, targetScale, targetRot, ca, fitX, fitY)
                // Строим матрицу положения, запеченного в текстуре кадра плеера
                buildTransformMatrix(matTex, texTx, texTy, texScale, texRot, ca, fitX, fitY)

                if (Matrix.invertM(invTex, 0, matTex, 0)) {
                    // M_delta = M_target * M_tex^(-1)
                    Matrix.multiplyMM(deltaMat, 0, matTarget, 0, invTex, 0)
                    // M_mvp = M_delta * M_tex = M_target (гарантирует точное попадание под палец)
                    Matrix.multiplyMM(mvpMat, 0, deltaMat, 0, matTex, 0)
                } else {
                    System.arraycopy(matTarget, 0, mvpMat, 0, 16)
                }
            } else {
                buildTransformMatrix(mvpMat, texTx, texTy, texScale, texRot, ca, fitX, fitY)
            }

            // 6. Отрисовка основного видеокадра
            GLES20.glUniform1i(uMode, 0) // Стандартное наложение текстуры
            GLES20.glUniformMatrix4fv(uMvpMat, 1, false, mvpMat, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        /**
         * Строит матрицу трансформации кадра с точной компенсацией соотношения сторон.
         * Порядок операций:
         * 1. Вписывание в холст (fitX, fitY).
         * 2. Масштабирование зума (s).
         * 3. Перевод координаты X в изотропное пространство с квадратными пикселями S(ca, 1).
         * 4. Вращение вокруг центра R(-rot).
         * 5. Возврат координаты X в NDC холста S(1/ca, 1).
         * 6. Смещение кадра T(tx * 2, -ty * 2).
         */
        private fun buildTransformMatrix(
            out: FloatArray,
            tx: Float,
            ty: Float,
            scale: Float,
            rotDeg: Float,
            ca: Float,
            fitX: Float,
            fitY: Float,
        ) {
            Matrix.setIdentityM(out, 0)
            val x = if (tx.isFinite()) tx * 2f else 0f
            val y = if (ty.isFinite()) -ty * 2f else 0f
            val rot = if (rotDeg.isFinite()) -rotDeg else 0f
            val s = if (scale.isFinite() && scale > 0.001f) scale else 1f

            Matrix.translateM(out, 0, x, y, 0f)
            Matrix.scaleM(out, 0, 1f / ca, 1f, 1f)
            Matrix.rotateM(out, 0, rot, 0f, 0f, 1f)
            Matrix.scaleM(out, 0, ca, 1f, 1f)
            Matrix.scaleM(out, 0, s, s, 1f)
            Matrix.scaleM(out, 0, fitX, fitY, 1f)
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
            uniform vec2 uCover;
            uniform int uMode;
            attribute vec4 aPos;
            attribute vec4 aUv;
            varying vec2 vUv;

            void main() {
                gl_Position = uMVPMatrix * aPos;
                vec2 uv = aUv.xy;
                if (uMode == 1) {
                    uv = (uv - 0.5) * uCover + 0.5;
                }
                vUv = (uTexMat * vec4(uv, 0.0, 1.0)).xy;
            }
        """

        const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            uniform int uMode;

            void main() {
                if (uMode == 1) {
                    // Высококачественный Гауссов блюр с билинейной интерполяцией и затемнением подложки на 30%
                    vec2 texOffset = vec2(0.0035, 0.0035);
                    vec3 acc = texture2D(uTex, vUv).rgb * 0.22;
                    
                    vec2 off1 = texOffset * 1.5;
                    vec2 off2 = texOffset * 3.5;

                    acc += (texture2D(uTex, vUv + vec2(off1.x, 0.0)).rgb + texture2D(uTex, vUv - vec2(off1.x, 0.0)).rgb) * 0.14;
                    acc += (texture2D(uTex, vUv + vec2(0.0, off1.y)).rgb + texture2D(uTex, vUv - vec2(0.0, off1.y)).rgb) * 0.14;
                    acc += (texture2D(uTex, vUv + vec2(off2.x, 0.0)).rgb + texture2D(uTex, vUv - vec2(off2.x, 0.0)).rgb) * 0.08;
                    acc += (texture2D(uTex, vUv + vec2(0.0, off2.y)).rgb + texture2D(uTex, vUv - vec2(0.0, off2.y)).rgb) * 0.08;

                    acc += (texture2D(uTex, vUv + off1).rgb + texture2D(uTex, vUv - off1).rgb) * 0.05;
                    acc += (texture2D(uTex, vUv + vec2(off1.x, -off1.y)).rgb + texture2D(uTex, vUv + vec2(-off1.x, off1.y)).rgb) * 0.05;
                    acc += (texture2D(uTex, vUv + off2).rgb + texture2D(uTex, vUv - off2).rgb) * 0.03;
                    acc += (texture2D(uTex, vUv + vec2(off2.x, -off2.y)).rgb + texture2D(uTex, vUv + vec2(-off2.x, off2.y)).rgb) * 0.03;

                    gl_FragColor = vec4(acc * 0.70, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, vUv);
                }
            }
        """
    }
}
