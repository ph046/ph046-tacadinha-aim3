package com.tacadinha.aim

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import androidx.core.app.NotificationCompat
import kotlin.math.*

data class BallPos(val x: Float, val y: Float, val radius: Float, val isCueBall: Boolean = false)
data class PocketPos(val x: Float, val y: Float)
data class AimLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ghostX: Float, val ghostY: Float, val ballX: Float, val ballY: Float, val pocketX: Float, val pocketY: Float, val willScore: Boolean)
data class AimData(val cueBall: BallPos?, val balls: List<BallPos>, val pockets: List<PocketPos>, val aimLines: List<AimLine>)

class BallDetector(private val bmp: Bitmap, private val scaleInv: Float) {
    companion object {
        fun isTableGreen(r: Int, g: Int, b: Int) = g > 80 && g > r + 25 && g > b + 25
        fun isWhite(r: Int, g: Int, b: Int) = r > 190 && g > 190 && b > 190
        fun isBallColor(r: Int, g: Int, b: Int): Boolean {
            if (isWhite(r, g, b)) return false
            if (isTableGreen(r, g, b)) return false
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val sat = if (max > 0) (max - min).toFloat() / max else 0f
            return (sat > 0.25f && max > 60) || (max < 60 && min < 40)
        }
    }
    private val w = bmp.width; private val h = bmp.height
    fun findCueBall(): BallPos? {
        val pixels = IntArray(w * h); bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val clusters = mutableListOf<MutableList<Int>>(); val visited = BooleanArray(w * h)
        for (idx in pixels.indices) {
            if (visited[idx]) continue
            val p = pixels[idx]; val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (!isWhite(r, g, b)) continue
            val ix = idx % w; val iy = idx / w
            if (!isOnTable(pixels, ix, iy)) continue
            val cluster = mutableListOf<Int>(); val queue = ArrayDeque<Int>(); queue.add(idx)
            while (queue.isNotEmpty() && cluster.size < 600) {
                val cur = queue.removeFirst()
                if (cur < 0 || cur >= pixels.size || visited[cur]) continue
                val cp = pixels[cur]; val cr = Color.red(cp); val cg = Color.green(cp); val cb = Color.blue(cp)
                if (!isWhite(cr, cg, cb)) continue
                visited[cur] = true; cluster.add(cur)
                val cx2 = cur % w; val cy2 = cur / w
                if (cx2 + 1 < w) queue.add(cur + 1); if (cx2 - 1 >= 0) queue.add(cur - 1)
                if (cy2 + 1 < h) queue.add(cur + w); if (cy2 - 1 >= 0) queue.add(cur - w)
            }
            if (cluster.size >= 8) clusters.add(cluster)
        }
        if (clusters.isEmpty()) return null
        val best = clusters.maxByOrNull { it.size } ?: return null
        val cx = best.map { it % w }.average().toFloat(); val cy = best.map { it / w }.average().toFloat()
        val estRadius = sqrt(best.size / Math.PI.toFloat()) * scaleInv
        return BallPos(cx * scaleInv, cy * scaleInv, estRadius.coerceIn(12f, 22f), isCueBall = true)
    }
    fun findBalls(): List<BallPos> {
        val pixels = IntArray(w * h); bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val visited = BooleanArray(w * h); val result = mutableListOf<BallPos>()
        for (idx in pixels.indices) {
            if (visited[idx]) continue
            val p = pixels[idx]; val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (!isBallColor(r, g, b)) continue
            val ix = idx % w; val iy = idx / w
            if (!isOnTable(pixels, ix, iy)) continue
            val cluster = mutableListOf<Int>(); val queue = ArrayDeque<Int>(); queue.add(idx)
            val targetR = r; val targetG = g; val targetB = b
            while (queue.isNotEmpty() && cluster.size < 600) {
                val cur = queue.removeFirst()
                if (cur < 0 || cur >= pixels.size || visited[cur]) continue
                val cp = pixels[cur]; val cr = Color.red(cp); val cg = Color.green(cp); val cb = Color.blue(cp)
                if (abs(cr - targetR) > 80 || abs(cg - targetG) > 80 || abs(cb - targetB) > 80) continue
                if (!isBallColor(cr, cg, cb)) continue
                visited[cur] = true; cluster.add(cur)
                val cx2 = cur % w; val cy2 = cur / w
                if (cx2 + 1 < w) queue.add(cur + 1); if (cx2 - 1 >= 0) queue.add(cur - 1)
                if (cy2 + 1 < h) queue.add(cur + w); if (cy2 - 1 >= 0) queue.add(cur - w)
            }
            if (cluster.size >= 8) {
                val cx = cluster.map { it % w }.average().toFloat(); val cy = cluster.map { it / w }.average().toFloat()
                val estRadius = sqrt(cluster.size / Math.PI.toFloat()) * scaleInv
                result.add(BallPos(cx * scaleInv, cy * scaleInv, estRadius.coerceIn(10f, 22f)))
            }
        }
        return result
    }
    fun findPockets(screenW: Float, screenH: Float): List<PocketPos> {
        val pixels = IntArray(w * h); bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val p = pixels[y * w + x]
            if (isTableGreen(Color.red(p), Color.green(p), Color.blue(p))) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        val sx1 = minX * scaleInv; val sx2 = maxX * scaleInv
        val sy1 = minY * scaleInv; val sy2 = maxY * scaleInv; val midX = (sx1 + sx2) / 2f
        return listOf(PocketPos(sx1+20,sy1+20),PocketPos(midX,sy1+10),PocketPos(sx2-20,sy1+20),PocketPos(sx1+20,sy2-20),PocketPos(midX,sy2-10),PocketPos(sx2-20,sy2-20))
    }
    private fun isOnTable(pixels: IntArray, x: Int, y: Int): Boolean {
        var greenCount = 0; val r = 4
        for (dy in -r..r step 2) for (dx in -r..r step 2) {
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val p = pixels[ny * w + nx]
            if (isTableGreen(Color.red(p), Color.green(p), Color.blue(p))) greenCount++
        }
        return greenCount >= 3
    }
}

object AimCalculator {
    fun calculate(cueBall: BallPos, balls: List<BallPos>, pockets: List<PocketPos>): List<AimLine> {
        val lines = mutableListOf<Pair<Float, AimLine>>()
        for (target in balls) for (pocket in pockets) {
            val line = calculateShot(cueBall, target, pocket, balls) ?: continue
            val dist = hypot(cueBall.x - target.x, cueBall.y - target.y)
            lines.add(Pair(if (line.willScore) 1_000_000f - dist else -dist, line))
        }
        return lines.sortedByDescending { it.first }.take(5).map { it.second }
    }
    private fun calculateShot(cueBall: BallPos, target: BallPos, pocket: PocketPos, allBalls: List<BallPos>): AimLine? {
        val toPocketX = pocket.x - target.x; val toPocketY = pocket.y - target.y
        val toPocketDist = hypot(toPocketX, toPocketY); if (toPocketDist < 5f) return null
        val nx = toPocketX / toPocketDist; val ny = toPocketY / toPocketDist
        val ballDiameter = cueBall.radius + target.radius
        val ghostX = target.x - nx * ballDiameter; val ghostY = target.y - ny * ballDiameter
        val toGhostX = ghostX - cueBall.x; val toGhostY = ghostY - cueBall.y
        val toGhostDist = hypot(toGhostX, toGhostY); if (toGhostDist < 5f) return null
        val gNx = toGhostX / toGhostDist; val gNy = toGhostY / toGhostDist
        val extDist = toGhostDist * 2.5f
        val lineEndX = cueBall.x + gNx * extDist; val lineEndY = cueBall.y + gNy * extDist
        val blocked = allBalls.any { if (it == target) false else distToSegment(it.x,it.y,cueBall.x,cueBall.y,ghostX,ghostY) < (cueBall.radius+it.radius)*0.9f }
        val targetBlocked = allBalls.any { if (it == target) false else distToSegment(it.x,it.y,target.x,target.y,pocket.x,pocket.y) < (target.radius+it.radius)*0.9f }
        return AimLine(cueBall.x,cueBall.y,lineEndX,lineEndY,ghostX,ghostY,target.x,target.y,pocket.x,pocket.y,!blocked&&!targetBlocked)
    }
    private fun distToSegment(px:Float,py:Float,x1:Float,y1:Float,x2:Float,y2:Float):Float {
        val dx=x2-x1;val dy=y2-y1;val lenSq=dx*dx+dy*dy
        if(lenSq<0.0001f) return hypot(px-x1,py-y1)
        val t=((px-x1)*dx+(py-y1)*dy)/lenSq;val c=t.coerceIn(0f,1f)
        return hypot(px-x1-c*dx,py-y1-c*dy)
    }
}

class AimOverlayView(context: Context) : View(context) {
    private var data: AimData? = null
    private val greenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(230,0,255,80);strokeWidth=5f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(30f,12f),0f) }
    private val redLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(180,255,60,60);strokeWidth=3f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(20f,15f),0f) }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(90,0,255,80);style=Paint.Style.FILL }
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(200,0,255,80);strokeWidth=3f;style=Paint.Style.STROKE }
    private val cueLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(200,255,255,255);strokeWidth=4f;style=Paint.Style.STROKE }
    private val targetLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(200,255,220,0);strokeWidth=4f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(25f,10f),0f) }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(120,255,160,0);style=Paint.Style.FILL }
    fun updateData(d: AimData) { data=d; postInvalidate() }
    override fun onDraw(canvas: Canvas) {
        val d = data ?: return
        for (p in d.pockets) canvas.drawCircle(p.x,p.y,18f,pocketPaint)
        val (scoring,notScoring) = d.aimLines.partition { it.willScore }
        for (l in notScoring) canvas.drawLine(l.x1,l.y1,l.x2,l.y2,redLine)
        for (l in scoring) {
            canvas.drawLine(l.x1,l.y1,l.x2,l.y2,greenLine)
            val r = d.cueBall?.radius ?: 15f
            canvas.drawCircle(l.ghostX,l.ghostY,r,ghostFill)
            canvas.drawCircle(l.ghostX,l.ghostY,r,ghostStroke)
            canvas.drawLine(l.ballX,l.ballY,l.pocketX,l.pocketY,targetLine)
        }
        d.cueBall?.let { canvas.drawCircle(it.x,it.y,it.radius+6f,cueLine) }
    }
}

class AimService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "tacadinha_aim_ch"
        const val NOTIF_ID = 42
    }
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: AimOverlayView? = null
    private lateinit var windowManager: WindowManager
    private var screenW = 0; private var screenH = 0; private var densityDpi = 0
    private val handler = Handler(Looper.getMainLooper())
    private val captureRunnable = object : Runnable {
        override fun run() { captureAndProcess(); handler.postDelayed(this, 250L) }
    }
    override fun onCreate() {
        super.onCreate(); windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)
        screenW=metrics.widthPixels; screenH=metrics.heightPixels; densityDpi=metrics.densityDpi
        createNotificationChannel()
    }
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA) ?: return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification())
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay("TacadinhaAim",screenW,screenH,densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,imageReader!!.surface,null,null)
        addOverlay(); handler.postDelayed(captureRunnable, 500L); return START_STICKY
    }
    private fun addOverlay() {
        overlayView = AimOverlayView(this)
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,layoutFlag,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        windowManager.addView(overlayView, params)
    }
    private fun captureAndProcess() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
            val bitmapW = rowStride / pixelStride
            val bmp = Bitmap.createBitmap(bitmapW, screenH, Bitmap.Config.ARGB_8888); bmp.copyPixelsFromBuffer(plane.buffer)
            val finalBmp = if (bitmapW != screenW) Bitmap.createBitmap(bmp,0,0,screenW,screenH).also { bmp.recycle() } else bmp
            processFrame(finalBmp)
        } catch (e: Exception) { e.printStackTrace() } finally { image.close() }
    }
    private fun processFrame(original: Bitmap) {
        val scale = 0.25f; val sw = (original.width*scale).toInt().coerceAtLeast(1); val sh = (original.height*scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(original, sw, sh, false); original.recycle()
        val scaleInv = 1f/scale; val detector = BallDetector(small, scaleInv)
        val cueBall = detector.findCueBall(); val balls = detector.findBalls(); val pockets = detector.findPockets(screenW.toFloat(), screenH.toFloat()); small.recycle()
        val aimLines = if (cueBall != null && balls.isNotEmpty()) AimCalculator.calculate(cueBall, balls, pockets) else emptyList()
        overlayView?.updateData(AimData(cueBall, balls, pockets, aimLines))
    }
    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        virtualDisplay?.release(); mediaProjection?.stop(); imageReader?.close(); super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID,"Tacadinha Aim",NotificationManager.IMPORTANCE_LOW).apply { description="Serviço de mira ativo" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("🎱 Tacadinha Aim").setContentText("Mira ativa").setSmallIcon(android.R.drawable.ic_menu_compass).setPriority(NotificationCompat.PRIORITY_LOW).build()
}
