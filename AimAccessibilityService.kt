package com.tacadinha.aim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.cos
import kotlin.math.sin

class AimAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AimAccessibilityService? = null

        private const val DRAG_LEN = 260f
        private const val GESTURE_DURATION_MS = 130L
        private const val MIN_GESTURE_INTERVAL_MS = 700L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastGestureTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisa ler eventos da tela.
    }

    override fun onInterrupt() {
        // Serviço interrompido pelo Android.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    fun aimCue(cueX: Float, cueY: Float, angleRad: Double) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            if (!cueX.isFinite() || !cueY.isFinite() || angleRad.isNaN()) {
                return
            }

            val now = System.currentTimeMillis()

            if (now - lastGestureTime < MIN_GESTURE_INTERVAL_MS) {
                return
            }

            lastGestureTime = now

            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val startX = cueX - cosA * DRAG_LEN * 0.45f
            val startY = cueY - sinA * DRAG_LEN * 0.45f

            val endX = cueX + cosA * DRAG_LEN * 1.15f
            val endY = cueY + sinA * DRAG_LEN * 1.15f

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        GESTURE_DURATION_MS
                    )
                )
                .build()

            handler.post {
                try {
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                super.onCompleted(gestureDescription)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                super.onCancelled(gestureDescription)
                            }
                        },
                        null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
