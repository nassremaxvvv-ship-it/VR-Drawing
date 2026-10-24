package com.vrdrawing.room

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

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
    @Volatile var alpha = 1f
    @Volatile var tracking = false
    @Volatile var hasImage = false
    @Volatile var placed = false

    // Stable mode: the camera is only a background. Head rotation comes from the gyroscope.
    @Volatile var stableMode = false
    @Volatile var gyroAvailable = true
    @Volatile private var gyroReady = false
    private val rotLock = Any()
    private val rotRaw = FloatArray(9)
    private val rotA = FloatArray(9)
    private val rotB = FloatArray(9)
    private val rotTmp = FloatArray(9)
    private var stablePlaced = false
    private val stableAnchor = FloatArray(16)
    private val anchorM = FloatArray(16)
    private val camC = FloatArray(3)
    private val camX = floatArrayOf(1f, 0f, 0f)
    private val camY = floatArrayOf(0f, 1f, 0f)
    private val camZ = floatArrayOf(0f, 0f, 1f)

    // The pointer dot and the buttons around the image can be hidden (locked)
    @Volatile var uiLocked = false
    @Volatile private var pressDown = false

    @Volatile private var viewportChanged = true
    @Volatile private var placePending = false
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
    private var whiteTex = 0
    private var reticleTex = 0
    private val iconTex = IntArray(5)

    // Pointer interaction state (GL thread)
    private var hovered = H_NONE
    private var active = H_NONE
    private var prevPress = false
    private var gazeOk = false
    private var gazeX = 0f
    private var gazeY = 0f
    private var gazeFy = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var grabAlpha = 1f
    private var grabOffZ = 0f
    private var grabFy = 0f
    private val grabCam = FloatArray(3)

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
    private val base = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    // ---- Called from the UI thread ----
    fun setImage(bmp: Bitmap) {
        pending.set(bmp)
    }

    /** Hang the image in the air, straight ahead of where the user is looking. */
    fun requestPlace() {
        offX = 0f
        offY = 0f
        offZ = 0f
        placePending = true
    }

    fun resetAll() {
        scale = 1f
        alpha = 1f
        requestPlace()
    }

    fun setPress(down: Boolean) {
        pressDown = down
    }

    fun setStable(on: Boolean) {
        stableMode = on
        requestPlace()
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            try {
                SensorManager.getRotationMatrixFromVector(rotTmp, event.values)
                synchronized(rotLock) {
                    System.arraycopy(rotTmp, 0, rotRaw, 0, 9)
                }
                gyroReady = true
            } catch (e: Exception) {
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startSensors(sm: SensorManager) {
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            gyroAvailable = false
        } else {
            gyroAvailable = true
            sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopSensors(sm: SensorManager) {
        sm.unregisterListener(sensorListener)
        gyroReady = false
    }

    fun fade(delta: Float) {
        alpha = (alpha + delta).coerceIn(0.1f, 1f)
    }

    fun move(dx: Float, dy: Float, dz: Float) {
        offX += dx
        offY += dy
        offZ = (offZ + dz).coerceIn(-3f, 1f)
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

        val white = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        white.eraseColor(Color.WHITE)
        whiteTex = makeTexture(white)
        reticleTex = makeTexture(makeReticle())
        for (i in 1..4) iconTex[i] = makeTexture(makeIcon(i))

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

        val viewW = if (vrMode) width / 2 else width
        try {
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        } catch (e: Exception) {
            Matrix.perspectiveM(proj, 0, 60f, viewW.toFloat() / height.toFloat(), 0.05f, 100f)
        }

        if (stableMode) {
            tracking = updateGyroCamera()
        } else {
            tracking = camera.trackingState == TrackingState.TRACKING
            val dp = camera.displayOrientedPose
            System.arraycopy(dp.translation, 0, camC, 0, 3)
            System.arraycopy(dp.xAxis, 0, camX, 0, 3)
            System.arraycopy(dp.yAxis, 0, camY, 0, 3)
            System.arraycopy(dp.zAxis, 0, camZ, 0, 3)
            camera.getViewMatrix(view, 0)
        }

        val bmp = pending.getAndSet(null)
        if (bmp != null) uploadImage(bmp)

        if (placePending && imgTex != 0 && tracking) {
            val ok = if (stableMode) placeStable() else placeInFront(s, camera)
            if (ok) placePending = false
        }

        val a = anchor
        val showImage = tracking && imgTex != 0 &&
            (if (stableMode) stablePlaced else (a != null && a.trackingState == TrackingState.TRACKING))

        if (showImage) {
            loadAnchorMatrix(a)
            updateBase()
            updateInteraction()
            updateBase()
        } else {
            hovered = H_NONE
            active = H_NONE
        }

        if (vrMode) {
            val half = width / 2
            GLES20.glViewport(0, 0, half, height)
            drawScene(showImage, IPD / 2f, half)
            GLES20.glViewport(half, 0, half, height)
            drawScene(showImage, -IPD / 2f, half)
        } else {
            GLES20.glViewport(0, 0, width, height)
            drawScene(showImage, 0f, width)
        }
    }

    /** Camera orientation from the gyroscope. The camera stays at the center of the room. */
    private fun updateGyroCamera(): Boolean {
        if (!gyroReady) return false
        synchronized(rotLock) {
            System.arraycopy(rotRaw, 0, rotA, 0, 9)
        }
        val axisX: Int
        val axisY: Int
        when (activity.displayRotation()) {
            Surface.ROTATION_90 -> {
                axisX = SensorManager.AXIS_Y
                axisY = SensorManager.AXIS_MINUS_X
            }
            Surface.ROTATION_180 -> {
                axisX = SensorManager.AXIS_MINUS_X
                axisY = SensorManager.AXIS_MINUS_Y
            }
            Surface.ROTATION_270 -> {
                axisX = SensorManager.AXIS_MINUS_Y
                axisY = SensorManager.AXIS_X
            }
            else -> {
                axisX = SensorManager.AXIS_X
                axisY = SensorManager.AXIS_Y
            }
        }
        if (!SensorManager.remapCoordinateSystem(rotA, axisX, axisY, rotB)) return false

        // Screen axes, converted from the sensor world (East, North, Up) to our world (X, Y up, Z)
        camX[0] = rotB[0]
        camX[1] = rotB[6]
        camX[2] = -rotB[3]
        camY[0] = rotB[1]
        camY[1] = rotB[7]
        camY[2] = -rotB[4]
        camZ[0] = rotB[2]
        camZ[1] = rotB[8]
        camZ[2] = -rotB[5]
        camC[0] = 0f
        camC[1] = 0f
        camC[2] = 0f

        view[0] = camX[0]
        view[1] = camY[0]
        view[2] = camZ[0]
        view[3] = 0f
        view[4] = camX[1]
        view[5] = camY[1]
        view[6] = camZ[1]
        view[7] = 0f
        view[8] = camX[2]
        view[9] = camY[2]
        view[10] = camZ[2]
        view[11] = 0f
        view[12] = 0f
        view[13] = 0f
        view[14] = 0f
        view[15] = 1f
        return true
    }

    private fun placeStable(): Boolean {
        val fx0 = -camZ[0]
        val fz0 = -camZ[2]
        val hl = sqrt(fx0 * fx0 + fz0 * fz0)
        val z: FloatArray
        val pos: FloatArray
        if (hl < 1e-3f) {
            z = floatArrayOf(0f, 0f, 1f)
            pos = floatArrayOf(0f, 0f, -DEFAULT_DISTANCE)
        } else {
            val fx = fx0 / hl
            val fz = fz0 / hl
            z = floatArrayOf(-fx, 0f, -fz)
            pos = floatArrayOf(fx * DEFAULT_DISTANCE, 0f, fz * DEFAULT_DISTANCE)
        }
        Matrix.setIdentityM(stableAnchor, 0)
        // columns: x axis, y axis, z axis, position
        stableAnchor[0] = z[2]
        stableAnchor[1] = 0f
        stableAnchor[2] = -z[0]
        stableAnchor[4] = 0f
        stableAnchor[5] = 1f
        stableAnchor[6] = 0f
        stableAnchor[8] = z[0]
        stableAnchor[9] = z[1]
        stableAnchor[10] = z[2]
        stableAnchor[12] = pos[0]
        stableAnchor[13] = pos[1]
        stableAnchor[14] = pos[2]
        stablePlaced = true
        placed = true
        return true
    }

    private fun loadAnchorMatrix(a: Anchor?) {
        if (stableMode) {
            System.arraycopy(stableAnchor, 0, anchorM, 0, 16)
        } else if (a != null) {
            a.pose.toMatrix(anchorM, 0)
        }
    }

    private fun updateBase() {
        System.arraycopy(anchorM, 0, base, 0, 16)
        Matrix.translateM(base, 0, offX, offY, offZ)
    }

    // ---------- Pointer (the dot in the middle of the screen) ----------

    private fun handleX(id: Int, w: Float): Float = when (id) {
        H_RESIZE -> -w / 2f - 0.03f
        H_DISTANCE -> w / 2f + 0.03f
        H_FADE -> w / 2f + 0.03f
        else -> 0f
    }

    private fun handleY(id: Int, h: Float): Float = when (id) {
        H_RESIZE -> -h / 2f - 0.03f
        H_MOVE -> -h / 2f - 0.11f
        H_DISTANCE -> -h / 2f - 0.03f
        else -> h / 2f + 0.03f
    }

    private fun handleSize(id: Int): Float = if (id == H_MOVE) 0.15f else 0.11f

    /** Finds where the center-of-screen ray crosses the plane of the image. */
    private fun computeGaze(): Boolean {
        val fx = -camZ[0]
        val fy = -camZ[1]
        val fz = -camZ[2]
        gazeFy = fy
        val nx = base[8]
        val ny = base[9]
        val nz = base[10]
        val denom = fx * nx + fy * ny + fz * nz
        if (abs(denom) < 1e-4f) return false
        val ox = base[12]
        val oy = base[13]
        val oz = base[14]
        val t = ((ox - camC[0]) * nx + (oy - camC[1]) * ny + (oz - camC[2]) * nz) / denom
        if (t <= 0f) return false
        val ix = camC[0] + fx * t - ox
        val iy = camC[1] + fy * t - oy
        val iz = camC[2] + fz * t - oz
        gazeX = ix * base[0] + iy * base[1] + iz * base[2]
        gazeY = ix * base[4] + iy * base[5] + iz * base[6]
        return true
    }

    private fun updateInteraction() {
        gazeOk = computeGaze()
        if (uiLocked) {
            hovered = H_NONE
            active = H_NONE
            prevPress = pressDown
            return
        }
        val h = BASE_HEIGHT * scale
        val w = h * imgAspect

        hovered = H_NONE
        if (gazeOk) {
            var best = Float.MAX_VALUE
            for (id in 1..4) {
                val d = hypot(gazeX - handleX(id, w), gazeY - handleY(id, h))
                val r = handleSize(id) * 0.65f
                if (d < r && d < best) {
                    best = d
                    hovered = id
                }
            }
        }

        val down = pressDown
        if (down && !prevPress && hovered != H_NONE && gazeOk) {
            active = hovered
            grabX = gazeX
            grabY = gazeY
            grabAlpha = alpha
            grabOffZ = offZ
            grabFy = gazeFy
            if (active == H_MOVE) {
                // Remember where the image is relative to the phone
                val rx = base[12] - camC[0]
                val ry = base[13] - camC[1]
                val rz = base[14] - camC[2]
                grabCam[0] = rx * camX[0] + ry * camX[1] + rz * camX[2]
                grabCam[1] = rx * camY[0] + ry * camY[1] + rz * camY[2]
                grabCam[2] = rx * camZ[0] + ry * camZ[1] + rz * camZ[2]
            }
        }
        if (!down) active = H_NONE
        prevPress = down

        if (active != H_NONE) {
            when (active) {
                H_MOVE -> {
                    // Hold the image like a physical object: it keeps its distance and
                    // direction relative to the phone and follows every movement.
                    val tx = camC[0] + camX[0] * grabCam[0] + camY[0] * grabCam[1] + camZ[0] * grabCam[2]
                    val ty = camC[1] + camX[1] * grabCam[0] + camY[1] * grabCam[1] + camZ[1] * grabCam[2]
                    val tz = camC[2] + camX[2] * grabCam[0] + camY[2] * grabCam[1] + camZ[2] * grabCam[2]
                    val dx = tx - anchorM[12]
                    val dy = ty - anchorM[13]
                    val dz = tz - anchorM[14]
                    offX = (dx * anchorM[0] + dy * anchorM[1] + dz * anchorM[2]).coerceIn(-8f, 8f)
                    offY = (dx * anchorM[4] + dy * anchorM[5] + dz * anchorM[6]).coerceIn(-8f, 8f)
                    offZ = (dx * anchorM[8] + dy * anchorM[9] + dz * anchorM[10]).coerceIn(-8f, 8f)
                }
                H_RESIZE -> if (gazeOk) {
                    val dNow = hypot(gazeX, gazeY)
                    val dGrab = hypot(grabX, grabY)
                    if (dGrab > 0.03f) zoom((dNow / dGrab).coerceIn(0.92f, 1.08f))
                    grabX = gazeX
                    grabY = gazeY
                }
                H_DISTANCE -> {
                    offZ = (grabOffZ - (gazeFy - grabFy) * 3f).coerceIn(-3f, 1f)
                }
                H_FADE -> if (gazeOk) {
                    alpha = (grabAlpha + (gazeX - grabX) * 2f).coerceIn(0.1f, 1f)
                }
            }
            hovered = active
        }
    }

    // ---------- Placement ----------

    /**
     * Anchors the image in free space, about 1.5 m ahead at eye level,
     * upright and facing the user. No wall or surface detection is used.
     */
    private fun placeInFront(s: Session, camera: Camera): Boolean {
        try {
            val dp = camera.displayOrientedPose
            val c = dp.translation
            val zAxis = dp.zAxis
            var fx = -zAxis[0]
            val fy = -zAxis[1]
            var fz = -zAxis[2]
            val hl = sqrt(fx * fx + fz * fz)
            val pos: FloatArray
            val z: FloatArray
            if (hl < 1e-3f) {
                pos = floatArrayOf(c[0] + fx * DEFAULT_DISTANCE, c[1] + fy * DEFAULT_DISTANCE, c[2] + fz * DEFAULT_DISTANCE)
                z = floatArrayOf(0f, 0f, 1f)
            } else {
                fx /= hl
                fz /= hl
                pos = floatArrayOf(c[0] + fx * DEFAULT_DISTANCE, c[1], c[2] + fz * DEFAULT_DISTANCE)
                z = floatArrayOf(-fx, 0f, -fz)
            }
            val yAxis = floatArrayOf(0f, 1f, 0f)
            val xAxis = floatArrayOf(z[2], 0f, -z[0])
            val q = quatFromAxes(xAxis, yAxis, z)
            val newAnchor = s.createAnchor(Pose(pos, q))
            anchor?.detach()
            anchor = newAnchor
            placed = true
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun quatFromAxes(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m00 = x[0]
        val m10 = x[1]
        val m20 = x[2]
        val m01 = y[0]
        val m11 = y[1]
        val m21 = y[2]
        val m02 = z[0]
        val m12 = z[1]
        val m22 = z[2]
        val tr = m00 + m11 + m22
        val q = FloatArray(4)
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            q[3] = 0.25f * s
            q[0] = (m21 - m12) / s
            q[1] = (m02 - m20) / s
            q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s
            q[0] = 0.25f * s
            q[1] = (m01 + m10) / s
            q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s
            q[0] = (m01 + m10) / s
            q[1] = 0.25f * s
            q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s
            q[0] = (m02 + m20) / s
            q[1] = (m12 + m21) / s
            q[2] = 0.25f * s
        }
        return q
    }

    // ---------- Drawing ----------

    private fun drawScene(showImage: Boolean, eyeShift: Float, viewW: Int) {
        drawBackground()
        if (showImage) {
            val h = BASE_HEIGHT * scale
            val w = h * imgAspect
            drawPiece(imgTex, 0f, 0f, w, h, eyeShift, 1f, 1f, 1f, alpha)
            if (!uiLocked) {
                val t = 0.004f
                drawPiece(whiteTex, 0f, h / 2f, w + t, t, eyeShift, 1f, 1f, 1f, 0.55f)
                drawPiece(whiteTex, 0f, -h / 2f, w + t, t, eyeShift, 1f, 1f, 1f, 0.55f)
                drawPiece(whiteTex, -w / 2f, 0f, t, h + t, eyeShift, 1f, 1f, 1f, 0.55f)
                drawPiece(whiteTex, w / 2f, 0f, t, h + t, eyeShift, 1f, 1f, 1f, 0.55f)
                for (id in 1..4) {
                    val k = if (hovered == id) 1.25f else 1f
                    val sz = handleSize(id) * k
                    val a = if (hovered == id) 1f else 0.85f
                    drawPiece(iconTex[id], handleX(id, w), handleY(id, h), sz, sz, eyeShift, 1f, 1f, 1f, a)
                }
            }
        }
        if (!uiLocked) drawReticle(viewW)
    }

    private fun drawPiece(
        tex: Int, tx: Float, ty: Float, sx: Float, sy: Float, eyeShift: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        System.arraycopy(base, 0, model, 0, 16)
        Matrix.translateM(model, 0, tx, ty, 0f)
        Matrix.scaleM(model, 0, sx, sy, 1f)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, eyeShift, 0f, 0f)
        Matrix.multiplyMM(viewEye, 0, tmp, 0, view, 0)
        Matrix.multiplyMM(mv, 0, viewEye, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
        drawTextured(tex, mvp, r, g, b, a)
    }

    private fun drawReticle(viewW: Int) {
        val size = if (hovered != H_NONE) 0.09f else 0.07f
        Matrix.setIdentityM(tmp, 0)
        Matrix.scaleM(tmp, 0, size * height / viewW, size, 1f)
        if (hovered != H_NONE) {
            drawTextured(reticleTex, tmp, 0.4f, 1f, 0.5f, 1f)
        } else {
            drawTextured(reticleTex, tmp, 1f, 1f, 1f, 1f)
        }
    }

    private fun drawTextured(tex: Int, m: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(imgProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(imgProgram, "uMvp"), 1, false, m, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(imgProgram, "uColor"), r, g, b, a)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
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

    // ---------- Textures ----------

    private fun pot(n: Int): Int {
        val lo = Integer.highestOneBit(n.coerceAtLeast(1))
        val p = if (n - lo > lo / 2) lo * 2 else lo
        return p.coerceIn(256, 2048)
    }

    private fun makeTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    private fun uploadImage(bmp: Bitmap) {
        // Power-of-two size + mipmaps removes the shimmering when the phone moves.
        val pw = pot(bmp.width)
        val ph = pot(bmp.height)
        val scaled = if (pw == bmp.width && ph == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, pw, ph, true)
        if (imgTex != 0) GLES20.glDeleteTextures(1, intArrayOf(imgTex), 0)
        imgTex = makeTexture(scaled)
        imgAspect = bmp.width.toFloat() / bmp.height.toFloat()
        lastBitmap = bmp
        hasImage = true
    }

    private fun makeReticle(): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA000000.toInt()
            style = Paint.Style.FILL
        }
        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        c.drawCircle(32f, 32f, 20f, dark)
        c.drawCircle(32f, 32f, 13f, light)
        return bmp
    }

    private fun arrowHead(c: Canvas, p: Paint, tipX: Float, tipY: Float, dx: Float, dy: Float) {
        val bx = tipX - dx * 22f
        val by = tipY - dy * 22f
        val px = -dy * 13f
        val py = dx * 13f
        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(bx + px, by + py)
        path.lineTo(bx - px, by - py)
        path.close()
        c.drawPath(path, p)
    }

    private fun makeIcon(kind: Int): Bitmap {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE61C2126.toInt()
            style = Paint.Style.FILL
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        c.drawCircle(64f, 64f, 60f, fill)
        c.drawCircle(64f, 64f, 60f, ring)
        when (kind) {
            H_MOVE -> {
                c.drawLine(64f, 30f, 64f, 98f, line)
                c.drawLine(30f, 64f, 98f, 64f, line)
                arrowHead(c, solid, 64f, 18f, 0f, -1f)
                arrowHead(c, solid, 64f, 110f, 0f, 1f)
                arrowHead(c, solid, 18f, 64f, -1f, 0f)
                arrowHead(c, solid, 110f, 64f, 1f, 0f)
            }
            H_RESIZE -> {
                c.drawLine(40f, 88f, 88f, 40f, line)
                arrowHead(c, solid, 28f, 100f, -0.7071f, 0.7071f)
                arrowHead(c, solid, 100f, 28f, 0.7071f, -0.7071f)
            }
            H_DISTANCE -> {
                c.drawRect(32f, 32f, 96f, 96f, line)
                c.drawRect(52f, 52f, 76f, 76f, line)
            }
            H_FADE -> {
                c.drawCircle(64f, 64f, 30f, line)
                val half = Path()
                half.addArc(RectF(34f, 34f, 94f, 94f), 90f, 180f)
                half.close()
                c.drawPath(half, solid)
            }
        }
        return bmp
    }

    // ---------- GL helpers ----------

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

        const val H_NONE = 0
        const val H_RESIZE = 1
        const val H_MOVE = 2
        const val H_DISTANCE = 3
        const val H_FADE = 4

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
            uniform vec4 uColor;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb * uColor.rgb, c.a * uColor.a);
            }
        """
    }
}
