package com.vortextrade.blackjackoverlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Entry screen. Its only job is to collect the two runtime grants the overlay needs —
 * "draw over other apps" and a MediaProjection screen-capture token — and then hand the
 * token to [OverlayService], which owns the floating window from that point on.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var projectionManager: MediaProjectionManager

    // Ask for the screen-capture token. The result is a one-shot Intent we forward to the service.
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startOverlayService(result.resultCode, data)
            statusText.text = "Overlay running. Drag the window anywhere; tap Calculate Now to analyse."
            Toast.makeText(this, "Overlay started", Toast.LENGTH_SHORT).show()
        } else {
            statusText.text = "Screen-capture permission was denied."
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Result ignored — the foreground-service notification is optional to the user. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.startButton).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            statusText.text = "Overlay stopped."
        }

        warnIfNoApiKey()
    }

    private fun onStartClicked() {
        // 1) Notifications (Android 13+) so the foreground service can post its required notice.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 2) "Draw over other apps" — this is what lets the popup float above the casino/game app.
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "Grant “Display over other apps”, then press Start again."
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // 3) Screen capture token — required to read what's on screen.
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun warnIfNoApiKey() {
        if (BuildConfig.ANTHROPIC_API_KEY.isBlank()) {
            statusText.text =
                "No API key configured. Add ANTHROPIC_API_KEY to local.properties and rebuild."
        }
    }
}
