package com.vrdrawing.room

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(private val activity: MainActivity) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    @Volatile var vrMode: Boolean = false
        set(value) {
            field = value
            viewportChanged = true
        }

    // Image settings (offsets are in the image's own frame, in meters)
    @Volatile var offX = 0f
    @Volatile var offY = 0f
    @Volatile var offZ = 0f
    @Volatile var scale = 1f
    @Volatile var tracking = false
    @Volatile var hasImage = false

    @Volatile private var viewportChanged = true
    @Volatile private var placeRequested = false
    private val pending = AtomicReference<Bitmap?>(null)
    private var lastBitmap: Bitmap? = null

    private var width = 1
    private var height = 1
    private var boundSession: Session? = null
    private var anchor: Anchor? = null

    private var bgTex = 0
    private var bgProgram = 0
    private var imgProgram = 0
    private var imgTex = 0
    private var imgAspect = 1f

    private val quadArr = floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)
    private val uvArr = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
    private val quadBuf = floatBuf(quadArr)
    private val uvBuf = floatBuf(uvArr)
    private val imgPosBuf = floatBuf(floatArrayOf(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f))
    private val imgUvBuf = floatBuf(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewEye = FloatArray(16)
    private val tmp = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    // ---- Called from the UI thread ----
    fun setImage(bmp: Bitmap, place: Boolean) {
        pending.set(bmp)
        if (place) placeRequested = true
    }

    fun requestPlace() {
        offX = 0f
        offY = 0f
        offZ = 0f
        placeRequested = true
    }

    fun resetAll() {
        scale = 1f
        requestPlace()
    }

    fun move(dx: Float, dy: Float, dz: Float) {
        offX += dx
        offY += dy
        offZ = (offZ + dz).coerceIn(-10f, 3f)
    }

    fun zoom(factor: Float) {
        scale = (scale * factor).coerceIn(0.1f, 10f)
    }

    // ---- GL thread ----
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        bgTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        bgProgram = makeProgram(BG_VS, BG_FS)
        imgProgram = makeProgram(IMG_VS, IMG_FS)
        imgTex = 0
        boundSession = null
        lastBitmap?.let { pending.set(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        if (boundSession !== s) {
            s.setCameraTextureName(bgTex)
            boundSession = s
            viewportChanged = true
        }
        if (viewportChanged) {
            val w = if (vrMode) width / 2 else width
            s.setDisplayGeometry(activity.displayRotation(), w, height)
            viewportChanged = false
        }

        val frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        val camera = frame.camera
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadArr,
                Coordinates2d.TEXTURE_NORMALIZED, uvArr
            )
            uvBuf.position(0)
            uvBuf.put(uvArr)
            uvBuf.position(0)
        }

        val isTracking = camera.trackingState == TrackingState.TRACKING
        tracking = isTracking

        val bmp = pending.getAndSet(null)
        if (bmp != null) uploadImage(bmp)

        if (placeRequested && isTracking && imgTex != 0) {
            anchor?.detach()
            val pose = camera.displayOrientedPose.compose(Pose.makeTranslation(0f, 0f, -DEFAULT_DISTANCE))
            anchor = s.createAnchor(pose)
            placeRequested = false
        }

        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        camera.getViewMatrix(view, 0)

        val a = anchor
        val showImage = isTracking && imgTex != 0 && a != null && a.trackingState == TrackingState.TRACKING

        if (vrMode) {
            val half = width / 2
            GLES20.glViewport(0, 0, half, height)
            drawBackground()
            if (showImage) drawImage(a!!, IPD / 2f)
            GLES20.glViewport(half, 0, half, height)
            drawBackground()
            if (showImage) drawImage(a!!, -IPD / 2f)
        } else {
            GLES20.glViewport(0, 0, width, height)
            drawBackground()
            if (showImage) drawImage(a!!, 0f)
        }
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProgram, "uTex"), 0)
        val pos = GLES20.glGetAttribLocation(bgProgram, "aPos")
        val uv = GLES20.glGetAttribLocation(bgProgram, "aUv")
        quadBuf.position(0)
        uvBuf.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private fun drawImage(a: Anchor, eyeShift: Float) {
        a.pose.toMatrix(model, 0)
        Matrix.translateM(model, 0, offX, offY, offZ)
        val h = BASE_HEIGHT * scale
        Matrix.scaleM(model, 0, h * imgAspect, h, 1f)

        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, eyeShift, 0f, 0f)
        Matrix.multiplyMM(viewEye, 0, tmp, 0, view, 0)
        Matrix.multiplyMM(mv, 0, viewEye, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(imgProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(imgProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(imgProgram, "uTex"), 0)
        val pos = GLES20.glGetAttribLocation(imgProgram, "aPos")
        val uv = GLES20.glGetAttribLocation(imgProgram, "aUv")
        imgPosBuf.position(0)
        imgUvBuf.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, imgPosBuf)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, imgUvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun uploadImage(bmp: Bitmap) {
        if (imgTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            imgTex = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        imgAspect = bmp.width.toFloat() / bmp.height.toFloat()
        lastBitmap = bmp
        hasImage = true
    }

    private fun makeProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        return sh
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }

    companion object {
        const val BASE_HEIGHT = 0.5f
        const val DEFAULT_DISTANCE = 1.5f
        const val IPD = 0.064f

        const val BG_VS = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { gl_Position = aPos; vUv = aUv; }
        """
        const val BG_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
        const val IMG_VS = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { gl_Position = uMvp * aPos; vUv = aUv; }
        """
        const val IMG_FS = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
    }
}
