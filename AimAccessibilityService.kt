package com.tacadinha.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlin.math.cos
import kotlin.math.sin

class AimAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AimAccessibilityService? = null
        const val CH = "tac_acc2"
        const val NID = 98
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }
        serviceInfo = info
        keepAlive()
    }

    private fun keepAlive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Tacadinha Ativo", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif = NotificationCompat.Builder(this, CH)
            .setContentTitle("Tacadinha Auto")
            .setContentText("Servico ativo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try { startForeground(NID, notif) } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun aimCue(cueX: Float, cueY: Float, angleRad: Double) {
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()
        val dragLen = 220f

        val startX = cueX - cosA * dragLen
        val startY = cueY - sinA * dragLen
        val endX   = cueX + cosA * dragLen
        val endY   = cueY + sinA * dragLen

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, 350L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                handler.postDelayed({
                    val confirmPath = Path().apply {
                        moveTo(startX + cosA * 20, startY + sinA * 20)
                        lineTo(endX - cosA * 20, endY - sinA * 20)
                    }
                    val confirmGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(confirmPath, 0L, 150L))
                        .build()
                    dispatchGesture(confirmGesture, null, null)
                }, 100L)
            }
            override fun onCancelled(g: GestureDescription) {}
        }, null)
    }
}
