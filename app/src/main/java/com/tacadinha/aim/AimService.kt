package com.tacadinha.aim

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BallPos(
    val x: Float,
    val y: Float,
    val radius: Float,
    val isCueBall: Boolean = false
)

data class PocketPos(
    val x: Float,
    val y: Float
)

data class AimLine(
    val cueX: Float,
    val cueY: Float,
    val ghostX: Float,
    val ghostY: Float,
    val targetX: Float,
    val targetY: Float,
    val pocketX: Float,
    val pocketY: Float,
    val clear: Boolean
)

data class AimData(
    val cueBall: BallPos?,
    val balls: List<BallPos>,
    val pockets: List<PocketPos>,
    val bestLine: AimLine?
)

object TableCalibration {
    fun tableRect(screenW: Int, screenH: Int): Rect {
        return Rect(
            (screenW * 0.130f).toInt(),
            (screenH * 0.105f).toInt(),
            (screenW * 0.880f).toInt(),
            (screenH * 0.790f).toInt()
        )
    }

    fun pockets(screenW: Float, screenH: Float): List<PocketPos> {
        return listOf(
            PocketPos(screenW * 0.130f, screenH * 0.122f),
            PocketPos(screenW * 0.505f, screenH * 0.095f),
            PocketPos(screenW * 0.878f, screenH * 0.122f),

            PocketPos(screenW * 0.130f, screenH * 0.781f),
            PocketPos(screenW * 0.505f, screenH * 0.787f),
            PocketPos(screenW * 0.878f, screenH * 0.781f)
        )
    }
}

class LightBallDetector(
    private val bmp: Bitmap,
    private val cropRectOriginal: Rect,
    private val scaleInv: Float
) {
    private val w = bmp.width
    private val h = bmp.height

    companion object {
        fun isTableGreen(r: Int, g: Int, b: Int): Boolean {
            return g > 70 && g > r + 18 && g > b + 18
        }

        fun isWhiteBall(r: Int, g: Int, b: Int): Boolean {
            val bright = r > 175 && g > 175 && b > 175
            val balanced = abs(r - g) < 35 && abs(r - b) < 35 && abs(g - b) < 35
            return bright && balanced
        }

        fun isColoredBall(r: Int, g: Int, b: Int): Boolean {
            if (isTableGreen(r, g, b)) return false
            if (isWhiteBall(r, g, b)) return false

            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val saturation = if (mx == 0) 0f else (mx - mn).toFloat() / mx.toFloat()

            return (mx > 45 && saturation > 0.22f) || (mx < 65 && mn < 50)
        }
    }

    fun findCueBall(): BallPos? {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(w * h)
        var bestCluster: MutableList<Int>? = null

        for (i in pixels.indices) {
            if (visited[i]) continue

            val p = pixels[i]
            if (!isWhiteBall(Color.red(p), Color.green(p), Color.blue(p))) continue

            val cluster = floodFill(
                start = i,
                pixels = pixels,
                visited = visited,
                maxSize = 900
            ) { color ->
                isWhiteBall(Color.red(color), Color.green(color), Color.blue(color))
            }

            if (cluster.size in 10..900) {
                if (bestCluster == null || cluster.size > bestCluster!!.size) {
                    bestCluster = cluster
                }
            }
        }

        return clusterToBall(bestCluster, true)
    }

    fun findColoredBalls(cueBall: BallPos?): List<BallPos> {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(w * h)
        val result = mutableListOf<BallPos>()

        for (i in pixels.indices) {
            if (visited[i]) continue

            val p = pixels[i]
            if (!isColoredBall(Color.red(p), Color.green(p), Color.blue(p))) continue

            val seedR = Color.red(p)
            val seedG = Color.green(p)
            val seedB = Color.blue(p)

            val cluster = floodFill(
                start = i,
                pixels = pixels,
                visited = visited,
                maxSize = 900
            ) { color ->
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                if (!isColoredBall(r, g, b)) {
                    false
                } else {
                    abs(r - seedR) < 85 && abs(g - seedG) < 85 && abs(b - seedB) < 85
                }
            }

            if (cluster.size in 10..900) {
                val ball = clusterToBall(cluster, false) ?: continue

                val tooCloseToCue = cueBall != null &&
                    hypot(ball.x - cueBall.x, ball.y - cueBall.y) <
                    ball.radius + cueBall.radius + 8f

                val duplicate = result.any {
                    hypot(ball.x - it.x, ball.y - it.y) < 22f
                }

                if (!tooCloseToCue && !duplicate) {
                    result.add(ball)
                }
            }
        }

        return result
    }

    private fun floodFill(
        start: Int,
        pixels: IntArray,
        visited: BooleanArray,
        maxSize: Int,
        accept: (Int) -> Boolean
    ): MutableList<Int> {
        val cluster = mutableListOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(start)

        while (queue.isNotEmpty() && cluster.size < maxSize) {
            val cur = queue.removeFirst()

            if (cur < 0 || cur >= pixels.size || visited[cur]) continue

            val color = pixels[cur]
            if (!accept(color)) continue

            visited[cur] = true
            cluster.add(cur)

            val x = cur % w
            val y = cur / w

            if (x > 0) queue.add(cur - 1)
            if (x < w - 1) queue.add(cur + 1)
            if (y > 0) queue.add(cur - w)
            if (y < h - 1) queue.add(cur + w)
        }

        return cluster
    }

    private fun clusterToBall(cluster: MutableList<Int>?, isCue: Boolean): BallPos? {
        if (cluster == null || cluster.isEmpty()) return null

        var sx = 0f
        var sy = 0f

        for (idx in cluster) {
            sx += (idx % w).toFloat()
            sy += (idx / w).toFloat()
        }

        val localX = sx / cluster.size
        val localY = sy / cluster.size

        val originalX = cropRectOriginal.left + localX * scaleInv
        val originalY = cropRectOriginal.top + localY * scaleInv

        val estimatedRadius = (sqrt(cluster.size / Math.PI.toFloat()) * scaleInv)
            .coerceIn(10f, 24f)

        return BallPos(originalX, originalY, estimatedRadius, isCue)
    }
}

object AutoAimCalculator {
    fun bestShot(
        cueBall: BallPos?,
        balls: List<BallPos>,
        pockets: List<PocketPos>
    ): AimLine? {
        if (cueBall == null || balls.isEmpty()) return null

        var bestScore = Float.NEGATIVE_INFINITY
        var bestLine: AimLine? = null

        for (ball in balls) {
            for (pocket in pockets) {
                val line = shotLine(cueBall, ball, pocket, balls) ?: continue

                val cueToGhost = hypot(line.ghostX - cueBall.x, line.ghostY - cueBall.y)
                val ballToPocket = hypot(ball.x - pocket.x, ball.y - pocket.y)

                val anglePenalty = anglePenalty(cueBall, ball, pocket)
                val blockedPenalty = if (line.clear) 0f else 900f

                val score = 5000f - cueToGhost - ballToPocket - anglePenalty - blockedPenalty

                if (score > bestScore) {
                    bestScore = score
                    bestLine = line
                }
            }
        }

        return bestLine
    }

    private fun shotLine(
        cueBall: BallPos,
        target: BallPos,
        pocket: PocketPos,
        allBalls: List<BallPos>
    ): AimLine? {
        val tx = pocket.x - target.x
        val ty = pocket.y - target.y
        val targetToPocket = hypot(tx, ty)

        if (targetToPocket < 10f) return null

        val nx = tx / targetToPocket
        val ny = ty / targetToPocket

        val contactDistance = cueBall.radius + target.radius
        val ghostX = target.x - nx * contactDistance
        val ghostY = target.y - ny * contactDistance

        val cueToGhost = hypot(ghostX - cueBall.x, ghostY - cueBall.y)
        if (cueToGhost < 10f) return null

        val pathBlocked = isPathBlocked(
            cueBall.x,
            cueBall.y,
            ghostX,
            ghostY,
            allBalls,
            ignore = target,
            radiusMargin = cueBall.radius * 0.85f
        )

        val pocketBlocked = isPathBlocked(
            target.x,
            target.y,
            pocket.x,
            pocket.y,
            allBalls,
            ignore = target,
            radiusMargin = target.radius * 0.85f
        )

        return AimLine(
            cueX = cueBall.x,
            cueY = cueBall.y,
            ghostX = ghostX,
            ghostY = ghostY,
            targetX = target.x,
            targetY = target.y,
            pocketX = pocket.x,
            pocketY = pocket.y,
            clear = !pathBlocked && !pocketBlocked
        )
    }

    private fun anglePenalty(cueBall: BallPos, target: BallPos, pocket: PocketPos): Float {
        val ax = target.x - cueBall.x
        val ay = target.y - cueBall.y
        val bx = pocket.x - target.x
        val by = pocket.y - target.y

        val la = hypot(ax, ay)
        val lb = hypot(bx, by)

        if (la < 1f || lb < 1f) return 1000f

        val dot = ((ax / la) * (bx / lb) + (ay / la) * (by / lb)).coerceIn(-1f, 1f)

        return (1f - dot) * 900f
    }

    private fun isPathBlocked(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        balls: List<BallPos>,
        ignore: BallPos,
        radiusMargin: Float
    ): Boolean {
        for (b in balls) {
            if (b == ignore) continue

            val d = distanceToSegment(b.x, b.y, x1, y1, x2, y2)

            if (d < b.radius + radiusMargin) {
                return true
            }
        }

        return false
    }

    private fun distanceToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy

        if (lengthSq <= 0.0001f) return hypot(px - x1, py - y1)

        val t = (((px - x1) * dx + (py - y1) * dy) / lengthSq).coerceIn(0f, 1f)
        val cx = x1 + t * dx
        val cy = y1 + t * dy

        return hypot(px - cx, py - cy)
    }
}

class AutoAimOverlayView(context: Context) : View(context) {
    private var data: AimData? = null

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 220, 0)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 0, 255, 70)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(28f, 12f), 0f)
    }

    private val blockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 50, 50)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private val pocketLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 220, 0)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 255, 70)
        style = Paint.Style.FILL
    }

    private val ghostBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 70)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 170, 0)
        style = Paint.Style.FILL
    }

    fun update(newData: AimData) {
        data = newData
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = data ?: return

        for (pocket in d.pockets) {
            canvas.drawCircle(pocket.x, pocket.y, 13f, pocketPaint)
        }

        for (ball in d.balls) {
            canvas.drawCircle(ball.x, ball.y, ball.radius + 5f, ballPaint)
        }

        d.cueBall?.let {
            canvas.drawCircle(it.x, it.y, it.radius + 7f, cuePaint)
        }

        val line = d.bestLine ?: return
        val linePaint = if (line.clear) aimPaint else blockedPaint

        canvas.drawLine(
            line.cueX,
            line.cueY,
            line.ghostX,
            line.ghostY,
            linePaint
        )

        canvas.drawCircle(line.ghostX, line.ghostY, 17f, ghostPaint)
        canvas.drawCircle(line.ghostX, line.ghostY, 17f, ghostBorderPaint)

        canvas.drawLine(
            line.targetX,
            line.targetY,
            line.pocketX,
            line.pocketY,
            pocketLinePaint
        )
    }
}

class AimService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "tacadinha_aim_ch"
        const val NOTIF_ID = 42
    }

    private lateinit var windowManager: WindowManager

    private var overlayView: AutoAimOverlayView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenW = 0
    private var screenH = 0
    private var densityDpi = 0

    private var lastProcessTime = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            stopSelf()
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureAndProcess()
            handler.postDelayed(this, 350L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        densityDpi = metrics.densityDpi

        createNotificationChannel()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceIntent = intent ?: return START_NOT_STICKY

        val resultCode = serviceIntent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        if (resultCode == Activity.RESULT_CANCELED) {
            stopSelf()
            return START_NOT_STICKY
        }

        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceIntent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            serviceIntent.getParcelableExtra(EXTRA_DATA)
        }

        val data = dataIntent ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(
            projectionCallback,
            handler
        )

        imageReader = ImageReader.newInstance(
            screenW,
            screenH,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TacadinhaAutoAimLight",
            screenW,
            screenH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        addOverlay()
        handler.postDelayed(captureRunnable, 500L)

        return START_STICKY
    }

    private fun addOverlay() {
        if (overlayView != null) return

        overlayView = AutoAimOverlayView(this)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)
    }

    private fun captureAndProcess() {
        val now = SystemClock.uptimeMillis()

        if (now - lastProcessTime < 300L) return
        lastProcessTime = now

        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            if (pixelStride <= 0 || rowStride <= 0) return

            val bitmapW = rowStride / pixelStride
            val imageW = image.width
            val imageH = image.height

            val raw = Bitmap.createBitmap(
                bitmapW,
                imageH,
                Bitmap.Config.ARGB_8888
            )

            raw.copyPixelsFromBuffer(plane.buffer)

            val full = if (bitmapW != imageW) {
                Bitmap.createBitmap(raw, 0, 0, imageW, imageH).also {
                    raw.recycle()
                }
            } else {
                raw
            }

            screenW = full.width
            screenH = full.height

            processFrame(full)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun processFrame(full: Bitmap) {
        try {
            val tableRect = TableCalibration.tableRect(screenW, screenH)

            val safeRect = Rect(
                tableRect.left.coerceAtLeast(0),
                tableRect.top.coerceAtLeast(0),
                tableRect.right.coerceAtMost(full.width),
                tableRect.bottom.coerceAtMost(full.height)
            )

 
