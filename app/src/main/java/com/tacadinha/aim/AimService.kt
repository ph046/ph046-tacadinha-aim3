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
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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

    val contactX: Float,
    val contactY: Float,

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
        val landscape = screenW > screenH

        return if (landscape) {
            // Calibrado para a mesa do Tacadinha do seu print
            Rect(
                (screenW * 0.248f).toInt(), // esquerda
                (screenH * 0.215f).toInt(), // topo
                (screenW * 0.755f).toInt(), // direita
                (screenH * 0.865f).toInt()  // baixo
            )
        } else {
            Rect(
                (screenW * 0.060f).toInt(),
                (screenH * 0.180f).toInt(),
                (screenW * 0.940f).toInt(),
                (screenH * 0.780f).toInt()
            )
        }
    }

     fun pockets(screenW: Float, screenH: Float): List<PocketPos> {
        val landscape = screenW > screenH

        return if (landscape) {
            listOf(
                PocketPos(screenW * 0.275f, screenH * 0.280f),
                PocketPos(screenW * 0.501f, screenH * 0.268f),
                PocketPos(screenW * 0.729f, screenH * 0.280f),

                PocketPos(screenW * 0.275f, screenH * 0.798f),
                PocketPos(screenW * 0.501f, screenH * 0.814f),
                PocketPos(screenW * 0.729f, screenH * 0.798f)
            )
        } else {
            listOf(
                PocketPos(screenW * 0.070f, screenH * 0.190f),
                PocketPos(screenW * 0.500f, screenH * 0.175f),
                PocketPos(screenW * 0.930f, screenH * 0.190f),

                PocketPos(screenW * 0.070f, screenH * 0.770f),
                PocketPos(screenW * 0.500f, screenH * 0.785f),
                PocketPos(screenW * 0.930f, screenH * 0.770f)
            )
        }
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
            return g > 65 && g > r + 14 && g > b + 14
        }

        fun isWhiteBall(r: Int, g: Int, b: Int): Boolean {
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))

            val bright = mx > 165 && mn > 130
            val balanced = abs(r - g) < 45 && abs(r - b) < 50 && abs(g - b) < 45

            return bright && balanced
        }

        fun isColoredBall(r: Int, g: Int, b: Int): Boolean {
            if (isTableGreen(r, g, b)) return false
            if (isWhiteBall(r, g, b)) return false

            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val saturation = if (mx == 0) 0f else (mx - mn).toFloat() / mx.toFloat()

            val colorful = mx > 55 && saturation > 0.20f
            val blackBall = mx < 78 && mn < 60

            return colorful || blackBall
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
                maxSize = 1000
            ) { color ->
                isWhiteBall(
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }

            if (isBallCluster(cluster)) {
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
                maxSize = 1000
            ) { color ->
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                if (!isColoredBall(r, g, b)) {
                    false
                } else {
                    abs(r - seedR) < 90 &&
                            abs(g - seedG) < 90 &&
                            abs(b - seedB) < 90
                }
            }

            if (isBallCluster(cluster)) {
                val ball = clusterToBall(cluster, false) ?: continue

                val tooCloseToCue = cueBall != null &&
                        hypot(ball.x - cueBall.x, ball.y - cueBall.y) <
                        ball.radius + cueBall.radius + 8f

                val duplicate = result.any {
                    hypot(ball.x - it.x, ball.y - it.y) < max(ball.radius, it.radius) * 1.35f
                }

                val nearPocket = isNearTablePocket(ball)

                if (!tooCloseToCue && !duplicate && !nearPocket) {
                    result.add(ball)
                }
            }
        }

        return result
            .sortedByDescending { it.radius }
            .take(16)
    }

    private fun isNearTablePocket(ball: BallPos): Boolean {
        val x = ball.x
        val y = ball.y

        val left = cropRectOriginal.left.toFloat()
        val right = cropRectOriginal.right.toFloat()
        val top = cropRectOriginal.top.toFloat()
        val bottom = cropRectOriginal.bottom.toFloat()

        val margin = 45f

        val nearLeft = abs(x - left) < margin
        val nearRight = abs(x - right) < margin
        val nearTop = abs(y - top) < margin
        val nearBottom = abs(y - bottom) < margin

        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    private fun isBallCluster(cluster: MutableList<Int>): Boolean {
        if (cluster.size !in 8..1000) return false

        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0

        for (idx in cluster) {
            val x = idx % w
            val y = idx / w

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val cw = maxX - minX + 1
        val ch = maxY - minY + 1

        if (cw < 2 || ch < 2) return false

        val ratio = cw.toFloat() / ch.toFloat().coerceAtLeast(1f)

        return ratio in 0.45f..2.2f
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

        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0

        for (idx in cluster) {
            val x = idx % w
            val y = idx / w

            sx += x.toFloat()
            sy += y.toFloat()

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val centroidX = sx / cluster.size
        val centroidY = sy / cluster.size

        val boxCenterX = (minX + maxX) / 2f
        val boxCenterY = (minY + maxY) / 2f

        val localX = boxCenterX * 0.65f + centroidX * 0.35f
        val localY = boxCenterY * 0.65f + centroidY * 0.35f

        val originalX = cropRectOriginal.left + localX * scaleInv
        val originalY = cropRectOriginal.top + localY * scaleInv

        val bw = (maxX - minX + 1) * scaleInv
        val bh = (maxY - minY + 1) * scaleInv

        val radiusByBox = ((bw + bh) / 4f).coerceIn(8f, 28f)
        val radiusByArea = (sqrt(cluster.size / Math.PI.toFloat()) * scaleInv).coerceIn(8f, 28f)

        val estimatedRadius = radiusByBox * 0.72f + radiusByArea * 0.28f

        return BallPos(originalX, originalY, estimatedRadius, isCue)
    }
}

object AutoAimCalculator {

    fun bestShot(
        cueBall: BallPos?,
        balls: List<BallPos>,
        pockets: List<PocketPos>
    ): AimLine? {
        if (cueBall == null || balls.isEmpty() || pockets.isEmpty()) return null

        var bestScore = Float.NEGATIVE_INFINITY
        var bestLine: AimLine? = null

        for (ball in balls) {
            for (pocket in pockets) {
                val line = shotLine(cueBall, ball, pocket, balls) ?: continue

                val cueToGhost = hypot(line.ghostX - cueBall.x, line.ghostY - cueBall.y)
                val ballToPocket = hypot(ball.x - pocket.x, ball.y - pocket.y)

                val cutPenalty = cutAnglePenalty(cueBall, ball, pocket)
                val blockedPenalty = if (line.clear) 0f else 5000f

                val score =
                    8000f -
                            cueToGhost * 0.85f -
                            ballToPocket * 0.65f -
                            cutPenalty -
                            blockedPenalty

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

        if (targetToPocket < 20f) return null

        val nx = tx / targetToPocket
        val ny = ty / targetToPocket

        val contactX = target.x - nx * target.radius
        val contactY = target.y - ny * target.radius

        val ghostDistance = cueBall.radius + target.radius
        val ghostX = target.x - nx * ghostDistance
        val ghostY = target.y - ny * ghostDistance

        val cueToGhost = hypot(ghostX - cueBall.x, ghostY - cueBall.y)
        if (cueToGhost < cueBall.radius * 2f) return null

        val cutPenalty = cutAnglePenalty(cueBall, target, pocket)
        if (cutPenalty > 3500f) return null

        val pathBlocked = isPathBlocked(
            x1 = cueBall.x,
            y1 = cueBall.y,
            x2 = ghostX,
            y2 = ghostY,
            balls = allBalls,
            ignore = target,
            radiusMargin = cueBall.radius * 1.05f
        )

        val pocketBlocked = isPathBlocked(
            x1 = target.x,
            y1 = target.y,
            x2 = pocket.x,
            y2 = pocket.y,
            balls = allBalls,
            ignore = target,
            radiusMargin = target.radius * 0.95f
        )

        return AimLine(
            cueX = cueBall.x,
            cueY = cueBall.y,

            ghostX = ghostX,
            ghostY = ghostY,

            contactX = contactX,
            contactY = contactY,

            targetX = target.x,
            targetY = target.y,

            pocketX = pocket.x,
            pocketY = pocket.y,

            clear = !pathBlocked && !pocketBlocked
        )
    }

    private fun cutAnglePenalty(cueBall: BallPos, target: BallPos, pocket: PocketPos): Float {
        val a1 = atan2(
            (target.y - cueBall.y).toDouble(),
            (target.x - cueBall.x).toDouble()
        )

        val a2 = atan2(
            (pocket.y - target.y).toDouble(),
            (pocket.x - target.x).toDouble()
        )

        val diff = angleDiff(a1, a2)
        val deg = Math.toDegrees(diff).toFloat()

        return when {
            deg <= 15f -> deg * 25f
            deg <= 35f -> 500f + deg * 55f
            deg <= 55f -> 1600f + deg * 85f
            else -> 5000f
        }
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var diff = abs(a - b)

        while (diff > Math.PI) {
            diff = abs(diff - Math.PI * 2.0)
        }

        return diff
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

        val t = (((px - x1) * dx + (py - y1) * dy) / lengthSq)
            .coerceIn(0f, 1f)

        val cx = x1 + t * dx
        val cy = y1 + t * dy

        return hypot(px - cx, py - cy)
    }
}

class AutoAimOverlayView(context: Context) : View(context) {
    private var data: AimData? = null

    private val cueToHitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 0, 255, 80)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(34f, 14f), 0f)
    }

    private val targetToPocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 220, 0)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val blockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 50, 50)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 255, 80)
        style = Paint.Style.FILL
    }

    private val ghostBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 0, 255, 80)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 60, 60)
        style = Paint.Style.FILL
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 220, 0)
        style = Paint.Style.FILL
    }

    fun update(newData: AimData) {
        data = newData
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val line = data?.bestLine ?: return

        val mainPaint = if (line.clear) cueToHitPaint else blockedPaint

        canvas.drawCircle(
            line.pocketX,
            line.pocketY,
            16f,
            pocketPaint
        )

        canvas.drawLine(
            line.cueX,
            line.cueY,
            line.ghostX,
            line.ghostY,
            mainPaint
        )

        canvas.drawCircle(
            line.ghostX,
            line.ghostY,
            18f,
            ghostPaint
        )

        canvas.drawCircle(
            line.ghostX,
            line.ghostY,
            18f,
            ghostBorderPaint
        )

        canvas.drawCircle(
            line.contactX,
            line.contactY,
            10f,
            contactPaint
        )

        canvas.drawLine(
            line.targetX,
            line.targetY,
            line.pocketX,
            line.pocketY,
            targetToPocketPaint
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
    private var processing = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            stopSelf()
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!processing) {
                processingHandler.post {
                    captureAndProcess()
                }
            }

            mainHandler.postDelayed(this, 320L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        processingThread = HandlerThread("TacadinhaCoachProcessing")
        processingThread.start()
        processingHandler = Handler(processingThread.looper)

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
            mainHandler
        )

        imageReader = ImageReader.newInstance(
            screenW,
            screenH,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TacadinhaCoachOverlay",
            screenW,
            screenH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )

        addOverlay()
        mainHandler.postDelayed(captureRunnable, 500L)

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

        if (now - lastProcessTime < 280L) return
        lastProcessTime = now

        val image = imageReader?.acquireLatestImage() ?: return

        processing = true

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

            processing = false
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

            if (safeRect.width() <= 10 || safeRect.height() <= 10) {
                full.recycle()
                return
            }

            val tableCrop = Bitmap.createBitmap(
                full,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height()
            )

            full.recycle()

            val scale = 0.22f

            val smallW = (tableCrop.width * scale).toInt().coerceAtLeast(1)
            val smallH = (tableCrop.height * scale).toInt().coerceAtLeast(1)

            val small = Bitmap.createScaledBitmap(
                tableCrop,
                smallW,
                smallH,
                false
            )

            tableCrop.recycle()

            val detector = LightBallDetector(
                bmp = small,
                cropRectOriginal = safeRect,
                scaleInv = 1f / scale
            )

            val cueBall = detector.findCueBall()
            val balls = detector.findColoredBalls(cueBall)

            val pockets = TableCalibration.pockets(
                screenW.toFloat(),
                screenH.toFloat()
            )

            val bestLine = AutoAimCalculator.bestShot(
                cueBall = cueBall,
                balls = balls,
                pockets = pockets
            )

            val aimData = AimData(
                cueBall = cueBall,
                balls = balls,
                pockets = pockets,
                bestLine = bestLine
            )

            mainHandler.post {
                overlayView?.update(aimData)
            }

            small.recycle()

        } catch (e: Exception) {
            e.printStackTrace()

            try {
                if (!full.isRecycled) {
                    full.recycle()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tacadinha Coach")
            .setContentText("Mostrando linha de sugestão por cima do jogo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tacadinha Coach",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(captureRunnable)

        try {
            overlayView?.let {
                windowManager.removeView(it)
            }
        } catch (_: Exception) {
        }

        overlayView = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection = null

        try {
            processingThread.quitSafely()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
