package com.vrdrawing.room

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
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
    private lateinit var status: TextView
    private lateinit var exitOverlay: View

    private var session: Session? = null
    private var userRequestedInstall = true
    private var restoredImage = false
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
        renderer.offX = prefs.getFloat("ox", 0f)
        renderer.offY = prefs.getFloat("oy", 0f)
        renderer.offZ = prefs.getFloat("oz", 0f)
        renderer.scale = prefs.getFloat("scale", 1f)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val root = FrameLayout(this)
        root.addView(glView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            text = "Starting..."
        }

        val topRow = row(
            bigButton("Choose Image") { pickImage.launch(arrayOf("image/*")) },
            bigButton("Place Here") { renderer.requestPlace(); persist() },
            bigButton("Enter VR Mode") { enterVr() },
            bigButton("Reset View") { renderer.resetAll(); persist() }
        )
        val bottomRow = row(
            holdButton("Move ◀") { renderer.move(-0.01f, 0f, 0f) },
            holdButton("Move ▶") { renderer.move(0.01f, 0f, 0f) },
            holdButton("Move ▲") { renderer.move(0f, 0.01f, 0f) },
            holdButton("Move ▼") { renderer.move(0f, -0.01f, 0f) },
            holdButton("Resize −") { renderer.zoom(0.985f) },
            holdButton("Resize +") { renderer.zoom(1.015f) },
            holdButton("Distance Near") { renderer.move(0f, 0f, 0.02f) },
            holdButton("Distance Far") { renderer.move(0f, 0f, -0.02f) }
        )
        val spacer = View(this)

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow)
            addView(status)
            addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
            addView(bottomRow)
        }
        root.addView(controls, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        exitOverlay = View(this).apply {
            visibility = View.GONE
            setOnClickListener { exitVr() }
        }
        root.addView(exitOverlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) {
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun bigButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = dp(52)
        minimumHeight = dp(52)
        alpha = 0.85f
        setOnClickListener { onClick() }
    }

    private fun holdButton(label: String, action: () -> Unit): Button {
        val b = bigButton(label) {}
        val repeat = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 70)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    handler.post(repeat)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    persist()
                }
            }
            true
        }
        return b
    }

    private fun persist() {
        prefs.edit()
            .putFloat("ox", renderer.offX)
            .putFloat("oy", renderer.offY)
            .putFloat("oz", renderer.offZ)
            .putFloat("scale", renderer.scale)
            .apply()
    }

    private fun enterVr() {
        renderer.vrMode = true
        controls.visibility = View.GONE
        exitOverlay.visibility = View.VISIBLE
    }

    private fun exitVr() {
        renderer.vrMode = false
        controls.visibility = View.VISIBLE
        exitOverlay.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (renderer.vrMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_BACK -> {
                    exitVr()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    renderer.requestPlace()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setStatus(msg: String) {
        runOnUiThread { status.text = msg }
    }

    private fun loadImage(uri: Uri) {
        Thread {
            try {
                val bmp = decode(uri)
                renderer.setImage(bmp, false)
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            setStatus("The camera permission is needed to see your room.")
            return
        }
        startAr()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
                val status = ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    userRequestedInstall = false
                    return
                }
                val s = Session(this)
                val cfg = Config(s)
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
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

        if (!restoredImage) {
            restoredImage = true
            prefs.getString("uri", null)?.let {
                renderer.requestPlace()
                loadImage(Uri.parse(it))
            }
        }
        handler.removeCallbacks(statusUpdater)
        handler.post(statusUpdater)
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            status.text = when {
                !renderer.hasImage -> "Tap Choose Image"
                !renderer.tracking -> "Move the phone slowly so it can see the room..."
                else -> "Tracking OK"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onPause() {
        super.onPause()
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
