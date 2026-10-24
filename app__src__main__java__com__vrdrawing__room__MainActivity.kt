package com.vrdrawing.room

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.media.ExifInterface
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var controls: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var bubble: Button
    private lateinit var status: TextView
    private lateinit var exitOverlay: View

    private var session: Session? = null
    private var userRequestedInstall = true
    private var askedForImage = false
    private var hintUntil = 0L
    private var downTime = 0L
    private var multiTouch = false
    private var tapCount = 0
    private var lastTapTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("vr_drawing", Context.MODE_PRIVATE) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            prefs.edit().putString("uri", uri.toString()).apply()
            renderer.requestPlace()
            loadImage(uri)
        } else if (!renderer.hasImage) {
            prefs.getString("uri", null)?.let {
                renderer.requestPlace()
                loadImage(Uri.parse(it))
            }
        }
    }

    fun displayRotation(): Int {
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.rotation
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        renderer = ArRenderer(this)
        renderer.scale = prefs.getFloat("scale", 1f)
        renderer.alpha = prefs.getFloat("alpha", 1f)
        renderer.stableMode = prefs.getBoolean("stable", false)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // The dot in the middle of the screen is the pointer.
        // Touch the screen = press whatever the dot is on. Two fingers = resize.
        // 4 quick taps hide the buttons and the dot. 3 quick taps bring them back.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoom(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                persist()
            }
        })
        glView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    multiTouch = false
                    renderer.setPress(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    renderer.setPress(false)
                }
                MotionEvent.ACTION_UP -> {
                    renderer.setPress(false)
                    persist()
                    if (!multiTouch && System.currentTimeMillis() - downTime < 350) registerTap()
                }
                MotionEvent.ACTION_CANCEL -> renderer.setPress(false)
            }
            true
        }

        val root = FrameLayout(this)
        root.addView(glView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x99000000.toInt())
            }
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            text = "Starting..."
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10)
                }
        )

        // Icon commands (opened from the bubble)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0x88000000.toInt())
            }
            addView(iconRow(
                cmd("🖼", "Choose Image", false) { pickImage.launch(arrayOf("image/*")) },
                cmd("📍", "Place Here", false) { renderer.requestPlace() },
                cmd("🥽", "Enter VR Mode", false) { enterVr() },
                cmd("↺", "Reset View", false) { renderer.resetAll() }
            ))
            addView(iconRow(
                cmd("➕", "Resize +", true) { renderer.zoom(1.015f) },
                cmd("➖", "Resize −", true) { renderer.zoom(0.985f) },
                cmd("🔆", "More visible", true) { renderer.fade(0.02f) },
                cmd("🔅", "More transparent", true) { renderer.fade(-0.02f) }
            ))
            addView(iconRow(
                cmd("⬅", "Move left", true) { renderer.move(-0.01f, 0f, 0f) },
                cmd("➡", "Move right", true) { renderer.move(0.01f, 0f, 0f) },
                cmd("⬆", "Move up", true) { renderer.move(0f, 0.01f, 0f) },
                cmd("⬇", "Move down", true) { renderer.move(0f, -0.01f, 0f) }
            ))
            addView(iconRow(
                cmd("🔍", "Image Distance: nearer", true) { renderer.move(0f, 0f, 0.02f) },
                cmd("🔭", "Image Distance: farther", true) { renderer.move(0f, 0f, -0.02f) },
                cmd("🧭", "Stable mode", false) { toggleStable() }
            ))
        }

        bubble = circleButton("⚙", 56).apply {
            setOnClickListener {
                val open = panel.visibility == View.VISIBLE
                panel.visibility = if (open) View.GONE else View.VISIBLE
                text = if (open) "⚙" else "✕"
            }
        }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(panel)
            addView(bubble)
        }
        root.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    leftMargin = dp(12)
                    bottomMargin = dp(12)
                }
        )

        exitOverlay = View(this).apply {
            visibility = View.GONE
            setOnClickListener { exitVr() }
        }
        root.addView(exitOverlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun iconRow(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b)
    }

    private fun circleButton(symbol: String, sizeDp: Int): Button = Button(this).apply {
        text = symbol
        textSize = 20f
        setTextColor(Color.WHITE)
        setAllCaps(false)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC202428.toInt())
            setStroke(dp(1), 0x66FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun showHint(msg: String) {
        status.text = msg
        hintUntil = System.currentTimeMillis() + 1500
    }

    /** An icon command. If repeat is true it keeps acting while the finger is held down. */
    private fun cmd(symbol: String, name: String, repeat: Boolean, action: () -> Unit): Button {
        val b = circleButton(symbol, 52)
        val rep = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 70)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    showHint(name)
                    if (repeat) handler.post(rep) else action()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(rep)
                    persist()
                }
            }
            true
        }
        return b
    }

    private fun persist() {
        prefs.edit().putFloat("scale", renderer.scale).putFloat("alpha", renderer.alpha).apply()
    }

    private fun enterVr() {
        renderer.vrMode = true
        controls.visibility = View.GONE
        status.visibility = View.GONE
        exitOverlay.visibility = View.VISIBLE
    }

    private fun exitVr() {
        renderer.vrMode = false
        controls.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        exitOverlay.visibility = View.GONE
    }

    private fun toggleStable() {
        val on = !renderer.stableMode
        renderer.setStable(on)
        prefs.edit().putBoolean("stable", on).apply()
        showHint(if (on) "Stable mode ON: the camera is only a background" else "Camera tracking mode ON")
    }

    private fun registerTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime > 700) tapCount = 0
        lastTapTime = now
        tapCount++
        if (!renderer.uiLocked && tapCount >= 4) {
            renderer.uiLocked = true
            tapCount = 0
            showHint("Buttons hidden. Tap 3 times quickly to bring them back.")
        } else if (renderer.uiLocked && tapCount >= 3) {
            renderer.uiLocked = false
            tapCount = 0
            showHint("Buttons are back.")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (renderer.vrMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_BACK -> {
                    exitVr()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event == null || event.repeatCount == 0) renderer.setPress(true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (renderer.vrMode && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            renderer.setPress(false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun setStatus(msg: String) {
        runOnUiThread { status.text = msg }
    }

    private fun loadImage(uri: Uri) {
        Thread {
            try {
                val bmp = decode(uri)
                renderer.setImage(bmp)
            } catch (e: Exception) {
                setStatus("Could not load the image: " + e.message)
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("unreadable image")
        val orientation = try {
            contentResolver.openInputStream(uri).use {
                ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            }
        } catch (e: Exception) {
            1
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    override fun onResume() {
        super.onResume()
        renderer.startSensors(getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            setStatus("The camera permission is needed to see your room.")
            return
        }
        startAr()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAr()
        } else {
            setStatus("Camera permission was denied. The app cannot work without it.")
        }
    }

    private fun startAr() {
        if (session == null) {
            try {
                val installStatus = ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
                if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    userRequestedInstall = false
                    return
                }
                val s = Session(this)
                val cfg = Config(s)
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
                cfg.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                cfg.depthMode = Config.DepthMode.DISABLED
                s.configure(cfg)
                session = s
            } catch (e: Exception) {
                setStatus("AR could not start: " + e.javaClass.simpleName + " " + (e.message ?: ""))
                return
            }
        }
        try {
            session!!.resume()
        } catch (e: Exception) {
            setStatus("Camera could not start: " + e.javaClass.simpleName)
            session = null
            return
        }
        renderer.session = session
        glView.onResume()

        if (!askedForImage) {
            askedForImage = true
            pickImage.launch(arrayOf("image/*"))
        }
        handler.removeCallbacks(statusUpdater)
        handler.post(statusUpdater)
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > hintUntil) {
                status.text = when {
                    !renderer.hasImage -> "Open the menu ⚙ and choose an image"
                    renderer.stableMode && !renderer.gyroAvailable -> "This phone has no rotation sensor. Stable mode cannot work."
                    !renderer.tracking && renderer.stableMode -> "Waiting for the rotation sensor..."
                    !renderer.tracking -> "Tracking lost. Step back and move the phone slowly."
                    !renderer.placed -> "Look where you want the image, then tap 📍"
                    renderer.uiLocked -> "Buttons hidden. Tap 3 times quickly to show them."
                    else -> "Aim the dot at a button, then touch and hold the screen"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onPause() {
        super.onPause()
        renderer.stopSensors(getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        handler.removeCallbacks(statusUpdater)
        glView.onPause()
        renderer.session = null
        session?.pause()
        persist()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
