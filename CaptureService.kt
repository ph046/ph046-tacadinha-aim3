package com.tacadinha.auto

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    companion object {
        const val CH = "tac_auto"
        const val NID = 55
        const val INVERT_AIM = false
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var sw = 0; private var sh = 0; private var dpi = 0
    private var autoRepeat = false
    private var isProcessing = false
    private var playerBallType = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { releaseCapture(); stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        processingThread = HandlerThread("TacadinhaProcessing")
        processingThread.start()
        processingHandler = Handler(processingThread.looper)
        refreshMetrics()
        createChannel()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra("data", Intent::class.java) ?: return START_NOT_STICKY
        else intent.getParcelableExtra("data") ?: return START_NOT_STICKY

        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, notif)

        releaseCapture()
        refreshMetrics()

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        projection?.registerCallback(projectionCallback, mainHandler)
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)
        vDisplay = projection?.createVirtualDisplay("TacAuto", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, mainHandler)

        if (overlayView == null) addOverlay()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun refreshMetrics() {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        sw = m.widthPixels; sh = m.heightPixels; dpi = m.densityDpi
    }

    private fun addOverlay() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 10, 10, 10))
            setPadding(14, 14, 14, 14)
        }

        val title = TextView(this).apply {
            text = "TACADINHA AUTO"
            setTextColor(Color.argb(255, 0, 255, 120))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
        }

        val ballLabel = TextView(this).apply {
            text = "Minhas bolas:"
            setTextColor(Color.LTGRAY)
            textSize = 11f
        }

        val ballGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbAuto   = RadioButton(this).apply { text="Auto";      setTextColor(Color.WHITE); textSize=10f; isChecked=true; id=100 }
        val rbSolid  = RadioButton(this).apply { text="Lisas";     setTextColor(Color.WHITE); textSize=10f; id=101 }
        val rbStripe = RadioButton(this).apply { text="Listradas"; setTextColor(Color.WHITE); textSize=10f; id=102 }
        ballGroup.addView(rbAuto); ballGroup.addView(rbSolid); ballGroup.addView(rbStripe)
        ballGroup.setOnCheckedChangeListener { _, id ->
            playerBallType = when (id) { 101 -> 1; 102 -> 2; else -> 0 }
        }

        val btnShot = Button(this).apply {
            text = "TACAR"
            setTextColor(Color.BLACK); textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 220, 80))
        }

        val toggleAuto = ToggleButton(this).apply {
            textOff = "AUTO OFF"; textOn = "AUTO ON"
            isChecked = false; setTextColor(Color.WHITE); textSize = 11f
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(200, 50, 50, 50))
        }

        val tvStatus = TextView(this).apply {
            text = "Pronto"
            setTextColor(Color.GRAY); textSize = 10f
            gravity = android.view.Gravity.CENTER
        }

        container.addView(title,      LinearLayout.LayoutParams(-1,-2).also{it.bottomMargin=6})
        container.addView(ballLabel,  LinearLayout.LayoutParams(-1,-2))
        container.addView(ballGroup,  LinearLayout.LayoutParams(-1,-2).also{it.bottomMargin=8})
        container.addView(btnShot,    LinearLayout.LayoutParams(-1,-2).also{it.bottomMargin=6})
        container.addView(toggleAuto, LinearLayout.LayoutParams(-1,-2).also{it.bottomMargin=4})
        container.addView(tvStatus,   LinearLayout.LayoutParams(-1,-2))

        val lp = WindowManager.LayoutParams(
            300, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 160 }

        var dX=0f; var dY=0f; var sX=0f; var sY=0f
        container.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sX=e.rawX; sY=e.rawY; dX=lp.x.toFloat(); dY=lp.y.toFloat(); true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x=(dX-(e.rawX-sX)).toInt(); lp.y=(dY+(e.rawY-sY)).toInt()
                    try { wm.updateViewLayout(v,lp) } catch(_:Exception){}; true
                }
                else -> false
            }
        }

        btnShot.setOnClickListener {
            tvStatus.text = "Calculando..."
            tvStatus.setTextColor(Color.YELLOW)
            triggerShot { msg, ok ->
                mainHandler.post {
                    tvStatus.text = msg
                    tvStatus.setTextColor(if (ok) Color.GREEN else Color.RED)
                }
            }
        }

        toggleAuto.setOnCheckedChangeListener { _, checked ->
            autoRepeat = checked
            if (checked) { tvStatus.text="AUTO ligado"; tvStatus.setTextColor(Color.GREEN); scheduleAuto(tvStatus) }
            else { tvStatus.text="AUTO desligado"; tvStatus.setTextColor(Color.GRAY) }
        }

        try { wm.addView(container, lp); overlayView = container } catch (e: Exception) { e.printStackTrace() }
    }

    private fun scheduleAuto(tvStatus: TextView) {
        if (!autoRepeat) return
        mainHandler.postDelayed({
            triggerShot { msg, ok ->
                mainHandler.post { tvStatus.text=msg; tvStatus.setTextColor(if(ok) Color.GREEN else Color.RED) }
            }
            scheduleAuto(tvStatus)
        }, 3200L)
    }

    private fun triggerShot(onResult: (String, Boolean) -> Unit) {
        if (isProcessing) { onResult("Processando...", false); return }
        val svc = AimAccessibilityService.instance
        if (svc == null) { onResult("Acessibilidade OFF", false); return }
        val image = reader?.acquireLatestImage()
        if (image == null) { onResult("Sem imagem", false); return }

        isProcessing = true
        try {
            val plane = image.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            val bmp = Bitmap.createBitmap(bw, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val final = if (bw != image.width)
                Bitmap.createBitmap(bmp,0,0,image.width,image.height).also{bmp.recycle()} else bmp

            processingHandler.post {
                try {
                    val (cue, allBalls, pockets) = Detector.analyze(final, final.width, final.height)
                    if (!final.isRecycled) final.recycle()

                    if (cue == null) { isProcessing=false; onResult("Bola branca nao encontrada",false); return@post }
                    if (allBalls.isEmpty()) { isProcessing=false; onResult("Nenhuma bola detectada",false); return@post }

                    val shot = ShotCalculator.bestShot(cue, allBalls, pockets)
                    if (shot == null || !shot.willScore) {
                        isProcessing=false; onResult("Sem tiro disponivel",false); return@post
                    }

                    val finalAngle = if (INVERT_AIM) shot.angleRad + Math.PI else shot.angleRad

                    mainHandler.postDelayed({
                        svc.aimCue(cue.x, cue.y, finalAngle)
                        isProcessing = false
                        onResult("Mirando! ${(shot.confidence*100).toInt()}%", true)
                    }, 80L)

                } catch (e: Exception) {
                    e.printStackTrace()
                    try { if (!final.isRecycled) final.recycle() } catch(_:Exception){}
                    isProcessing = false
                    onResult("Erro no calculo", false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isProcessing = false
            onResult("Erro na captura", false)
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun releaseCapture() {
        try { vDisplay?.release() } catch(_:Exception){}; vDisplay=null
        try { reader?.close() } catch(_:Exception){}; reader=null
    }

    override fun onDestroy() {
        autoRepeat=false; mainHandler.removeCallbacksAndMessages(null)
        try { overlayView?.let { wm.removeView(it) } } catch(_:Exception){}; overlayView=null
        releaseCapture()
        try { projection?.unregisterCallback(projectionCallback) } catch(_:Exception){}
        try { projection?.stop() } catch(_:Exception){}; projection=null
        try { processingThread.quitSafely() } catch(_:Exception){}
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH,"Tacadinha Auto",NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotif() = NotificationCompat.Builder(this,CH)
        .setContentTitle("Tacadinha Auto").setContentText("Toque no botao verde para mirar")
        .setSmallIcon(android.R.drawable.ic_menu_compass).setPriority(NotificationCompat.PRIORITY_LOW).build()
}

object ToastHelper {
    fun show(context: android.content.Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
