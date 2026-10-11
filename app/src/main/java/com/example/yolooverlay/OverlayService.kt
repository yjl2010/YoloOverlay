package com.example.yolooverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 前台服务：持有 MediaProjection，维护两个悬浮窗
 *
 *  1) 绘制层 OverlayView   —— FLAG_NOT_TOUCHABLE，触摸完全穿透到下层 App
 *  2) 控制层 FloatingButtonView —— 可触摸的小圆点，单击暂停、拖动移位、长按停止
 */
class OverlayService : Service(), YoloPipeline.Listener {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "yolo_overlay_channel"
        private const val NOTIF_ID = 0x1001

        const val DEFAULT_MODEL = "yolov8n.onnx"

        const val ACTION_START = "com.example.yolooverlay.action.START"
        const val ACTION_STOP = "com.example.yolooverlay.action.STOP"
        const val ACTION_TOGGLE = "com.example.yolooverlay.action.TOGGLE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CONF = "extra_conf"
        const val EXTRA_CAPTURE_MAX_DIM = "extra_capture_max_dim"
        const val EXTRA_USE_NNAPI = "extra_use_nnapi"
        const val EXTRA_MODEL_ASSET = "extra_model_asset"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var buttonView: FloatingButtonView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    private var projection: MediaProjection? = null
    private var pipeline: YoloPipeline? = null

    // 当前会话参数
    private var confThreshold = 0.25f
    private var captureMaxDim = 1280
    private var useNnapi = false
    private var modelAsset = DEFAULT_MODEL

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 被系统停止")
            mainHandler.post { stopSelfSafely() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        loadPrefs()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        confThreshold = prefs.getFloat(MainActivity.KEY_CONF, 0.25f)
        captureMaxDim = prefs.getInt(MainActivity.KEY_CAPTURE_MAX_DIM, 1280)
        useNnapi = prefs.getBoolean(MainActivity.KEY_NNAPI, false)
        modelAsset = prefs.getString(MainActivity.KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (action == ACTION_TOGGLE) {
            toggleDetection()
            return START_STICKY
        }

        val conf = intent?.getFloatExtra(EXTRA_CONF, confThreshold) ?: confThreshold
        if (conf > 0f) confThreshold = conf
        val maxDim = intent?.getIntExtra(EXTRA_CAPTURE_MAX_DIM, captureMaxDim) ?: captureMaxDim
        if (maxDim > 0) captureMaxDim = maxDim
        intent?.getBooleanExtra(EXTRA_USE_NNAPI, useNnapi)?.let { useNnapi = it }
        intent?.getStringExtra(EXTRA_MODEL_ASSET)?.let { modelAsset = it }

        // 必须是可见的前台服务，之后才允许 getMediaProjection()
        startAsForeground()

        if (projection != null) {
            pipeline?.confThreshold = confThreshold
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        if (resultCode == 0 || resultData == null) {
            toast("缺少屏幕录制授权，无法启动")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!startProjection(resultCode, resultData)) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("正在初始化…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ------------------------------------------------------------ 启动 / 停止

    private fun startProjection(resultCode: Int, resultData: Intent): Boolean {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection 失败", t)
            toast("获取屏幕录制失败：${t.message}")
            return false
        }
        if (mp == null) {
            toast("获取屏幕录制失败")
            return false
        }

        // Android 14 起必须在 createVirtualDisplay 之前注册回调
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp

        addOverlayWindows()

        val p = YoloPipeline(
            context = applicationContext,
            projection = mp,
            config = YoloPipeline.Config(
                modelAsset = modelAsset,
                inputSize = 640,
                numThreads = 4,
                useNnapi = useNnapi,
                captureMaxDim = captureMaxDim
            ),
            listener = this
        )
        p.confThreshold = confThreshold
        pipeline = p
        p.start()

        isRunning = true
        updateNotification("识别运行中")
        return true
    }

    private fun stopSelfSafely() {
        isRunning = false
        pipeline?.stop()
        pipeline = null
        removeOverlayWindows()
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        stopForegroundCompat()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        isRunning = false
        pipeline?.stop()
        pipeline = null
        removeOverlayWindows()
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isRunning) return
        // 等系统把旋转做完再重建采集面，否则拿到的还是旧尺寸
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            layoutWindows()
            pipeline?.reconfigureCapture()
        }, 400)
    }

    // ------------------------------------------------------------ 悬浮窗

    private fun addOverlayWindows() {
        if (overlayView == null) {
            val view = OverlayView(this)
            val params = buildOverlayParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            try {
                windowManager.addView(view, params)
                overlayView = view
                overlayParams = params
            } catch (t: Throwable) {
                Log.e(TAG, "添加绘制层失败", t)
                toast("悬浮窗不可用：${t.message}")
                return
            }
        }

        if (buttonView == null) {
            val view = FloatingButtonView(this)
            val size = (46 * resources.displayMetrics.density).roundToInt()
            val bounds = ScreenUtils.currentBounds(this)
            val params = buildOverlayParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            params.width = size
            params.height = size
            params.x = p.getInt(
                MainActivity.KEY_BTN_X,
                (bounds.width() - size - 24 * resources.displayMetrics.density).roundToInt()
            ).coerceIn(0, (bounds.width() - size).coerceAtLeast(0))
            params.y = p.getInt(
                MainActivity.KEY_BTN_Y,
                (bounds.height() * 0.35f).roundToInt()
            ).coerceIn(0, (bounds.height() - size).coerceAtLeast(0))
            params.alpha = 0.85f
            attachButtonTouch(view, params)
            try {
                windowManager.addView(view, params)
                buttonView = view
                buttonParams = params
            } catch (t: Throwable) {
                Log.e(TAG, "添加控制按钮失败", t)
            }
        }

        layoutWindows()
    }

    private fun buildOverlayParams(flags: Int): WindowManager.LayoutParams {
        val bounds = ScreenUtils.currentBounds(this)
        return WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 旋转后重新对齐绘制层尺寸，保证「采集帧 -> 屏幕」的线性映射成立。 */
    private fun layoutWindows() {
        val bounds = ScreenUtils.currentBounds(this)
        val v = overlayView
        val p = overlayParams
        if (v != null && p != null) {
            p.width = bounds.width()
            p.height = bounds.height()
            p.x = 0
            p.y = 0
            try {
                windowManager.updateViewLayout(v, p)
            } catch (_: Throwable) {
            }
        }
        val bv = buttonView
        val bp = buttonParams
        if (bv != null && bp != null) {
            bp.x = bp.x.coerceIn(0, (bounds.width() - bp.width).coerceAtLeast(0))
            bp.y = bp.y.coerceIn(0, (bounds.height() - bp.height).coerceAtLeast(0))
            try {
                windowManager.updateViewLayout(bv, bp)
            } catch (_: Throwable) {
            }
        }
    }

    private fun removeOverlayWindows() {
        overlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Throwable) {
            }
        }
        buttonView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Throwable) {
            }
        }
        overlayView = null
        overlayParams = null
        buttonView = null
        buttonParams = null
    }

    // ------------------------------------------------------------ 控制按钮

    private fun attachButtonTouch(view: FloatingButtonView, params: WindowManager.LayoutParams) {
        val slop = 8 * resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressFired = false

        val longPress = Runnable {
            longPressFired = true
            toast("已停止悬浮识别")
            stopSelfSafely()
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressFired = false
                    mainHandler.postDelayed(longPress, 650)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        val bounds = ScreenUtils.currentBounds(this)
                        params.x = (startX + dx).roundToInt()
                            .coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).roundToInt()
                            .coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!dragging && !longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleDetection()
                    } else if (dragging) {
                        // 拖动结束后保存位置
                        buttonParams?.let { p ->
                            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                                .edit()
                                .putInt(MainActivity.KEY_BTN_X, p.x)
                                .putInt(MainActivity.KEY_BTN_Y, p.y)
                                .apply()
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleDetection() {
        val v = overlayView ?: return
        val next = !v.isActive()
        v.setActive(next)
        buttonView?.setActive(next)
        if (!next) v.clear()
        updateNotification(if (next) "识别运行中" else "绘制已暂停")
    }

    // ------------------------------------------------------------ 结果回调

    override fun onResult(result: YoloPipeline.DetectionResult) {
        overlayView?.update(result)
    }

    override fun onError(error: Throwable) {
        val detail = describeError(error)
        Log.e(TAG, "推理错误:\n$detail", error)
        // 画在屏幕上，用户截个图就能定位（不需要连 adb）
        overlayView?.showError(detail)
        toast(detail.lineSequence().first().take(120))
    }

    /** 把异常链 + 堆栈拼成一段可以直接看懂的诊断文本 */
    private fun describeError(t: Throwable): String {
        val sb = StringBuilder()
        sb.append("⚠ 推理失败\n")
        if (t is java.io.FileNotFoundException) {
            sb.append("找不到模型文件 assets/").append(modelAsset).append('\n')
        }
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 4) {
            if (depth > 0) sb.append("\n↑ 由 ")
            sb.append(e.javaClass.name).append(": ").append(e.message ?: "(无消息)")
            e = e.cause
            depth++
        }
        val frames = t.stackTrace.take(8)
        if (frames.isNotEmpty()) {
            sb.append("\n\n堆栈:")
            for (f in frames) sb.append("\n  ").append(f.toString())
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "悬浮识别",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "YOLOv8 悬浮窗实时识别"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("YOLOv8 悬浮识别")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "暂停/继续", toggle)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        val manager = NotificationManagerCompat.from(this)
        try {
            if (manager.areNotificationsEnabled()) {
                manager.notify(NOTIF_ID, buildNotification(text))
            }
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }
}
