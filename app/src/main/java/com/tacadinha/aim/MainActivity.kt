package com.tacadinha.aim

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY = 1002
        const val REQUEST_NOTIFICATION = 1003
    }

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.tvStatus)

        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, AimService::class.java))
            statusText.text = "❌ Mira desativada"
            statusText.setTextColor(0xFFFF4444.toInt())
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (isAccessibilityServiceEnabled()) {
            statusText.text = "✅ Acessibilidade ativa. Pronto para iniciar."
            statusText.setTextColor(0xFF00FF88.toInt())
        } else {
            statusText.text = "⚠️ Ative a acessibilidade primeiro"
            statusText.setTextColor(0xFFFFCC00.toInt())
        }
    }

    private fun checkPermissionsAndStart() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Ative o Tacadinha Aim na acessibilidade.",
                Toast.LENGTH_LONG
            ).show()

            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "⚠️ Conceda permissão de sobreposição..."

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        statusText.text = "📸 Aguardando permissão de captura..."

        val captureIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }

        startActivityForResult(
            captureIntent,
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(
            this,
            AimAccessibilityService::class.java
        )

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = expectedComponentName.flattenToString()

        return enabledServices
            .split(":")
            .any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    checkPermissionsAndStart()
                } else {
                    Toast.makeText(
                        this,
                        "Permissão de sobreposição necessária para funcionar.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val serviceIntent = Intent(this, AimService::class.java).apply {
                        putExtra(AimService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(AimService.EXTRA_DATA, data)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    statusText.text = "✅ Mira ATIVA! Abra seu jogo."
                    statusText.setTextColor(0xFF00FF88.toInt())

                    Toast.makeText(
                        this,
                        "Mira ativada! Volte para o jogo.",
                        Toast.LENGTH_LONG
                    ).show()

                    moveTaskToBack(true)
                } else {
                    statusText.text = "⚠️ Permissão de captura negada"
                    statusText.setTextColor(0xFFFFCC00.toInt())

                    Toast.makeText(
                        this,
                        "Permissão de captura negada.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
