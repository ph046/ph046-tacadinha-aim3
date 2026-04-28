package com.tacadinha.aim

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY = 1002
    }

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, AimService::class.java))
            statusText.text = "❌ Mira desativada"
            statusText.setTextColor(0xFFFF4444.toInt())
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "⚠️ Conceda permissão de sobreposição..."
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        statusText.text = "📸 Aguardando permissão de captura..."
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    requestScreenCapture()
                } else {
                    Toast.makeText(this, "Permissão necessária para funcionar!", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Mira ativada! Volte para o jogo.", Toast.LENGTH_LONG).show()
                    moveTaskToBack(true)
                } else {
                    statusText.text = "⚠️ Permissão negada"
                    Toast.makeText(this, "Permissão de captura negada.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
