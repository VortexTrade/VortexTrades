package com.vortextrade.blackjackoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service that owns:
 *  - the draggable floating overlay (a WindowManager view),
 *  - the MediaProjection screen-capture pipeline, and
 *  - the "Calculate Now" flow that turns one screenshot into one Claude request.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var capture: ScreenCaptureManager? = null
    private val advisor = BlackjackAdvisor()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be foreground (with the mediaProjection type) BEFORE acquiring the projection on API 29+.
        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_CANCELED_VALUE) ?: RESULT_CANCELED_VALUE
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode == RESULT_CANCELED_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        setupProjection(resultCode, resultData)
        showOverlay()
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: run {
            stopSelf(); return
        }
        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        val (w, h, dpi) = screenMetrics()
        capture = ScreenCaptureManager(projection, w, h, dpi).apply { start() }
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManagerLazy().currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), resources.configuration.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManagerLazy().defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun windowManagerLazy(): WindowManager {
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        return windowManager
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManagerLazy()

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_view, null)
        overlayView = view

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
        }

        val adviceText = view.findViewById<TextView>(R.id.adviceText)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val calculateButton = view.findViewById<TextView>(R.id.calculateButton)

        view.findViewById<TextView>(R.id.closeButton).setOnClickListener { stopSelf() }

        calculateButton.setOnClickListener {
            onCalculate(adviceText, progress, calculateButton)
        }

        makeDraggable(view)
        windowManager.addView(view, layoutParams)
    }

    private fun onCalculate(
        adviceText: TextView,
        progress: ProgressBar,
        calculateButton: TextView
    ) {
        val cap = capture ?: return
        progress.visibility = View.VISIBLE
        calculateButton.isEnabled = false
        adviceText.text = "Reading the table…"

        scope.launch {
            // Hide the overlay for one frame so it never appears in the screenshot we analyse.
            overlayView?.visibility = View.INVISIBLE
            val bitmap = withContext(Dispatchers.Default) {
                Thread.sleep(60) // let the compositor drop the overlay before capturing
                cap.captureLatest()
            }
            overlayView?.visibility = View.VISIBLE

            val result = if (bitmap == null) {
                "Couldn't grab a frame — try again."
            } else {
                advisor.analyze(bitmap).also { bitmap.recycle() }
            }

            adviceText.text = result
            progress.visibility = View.GONE
            calculateButton.isEnabled = true
        }
    }

    /** Lets the user drag the popup anywhere on screen. */
    private fun makeDraggable(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        // Return true so MOVE/UP are delivered here. This listener only fires
                        // for gestures that start on non-interactive areas — the button and close
                        // control consume their own touches before this listener is consulted.
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                            dragging = true
                        }
                        if (dragging) {
                            layoutParams.x = initialX + dx.toInt()
                            layoutParams.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(view, layoutParams)
                            return true
                        }
                        return false
                    }
                }
                return false
            }
        })
    }

    private fun startAsForeground() {
        val channelId = "blackjack_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        capture?.release()
        capture = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_CANCELED_VALUE = 0
        private const val TOUCH_SLOP = 12
    }
}
