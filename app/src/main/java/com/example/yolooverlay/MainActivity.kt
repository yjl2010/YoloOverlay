package com.example.yolooverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS = "yolo_overlay_prefs"
        const val KEY_CONF = "conf_threshold"
        const val KEY_CAPTURE_MAX_DIM = "capture_max_dim"
        const val KEY_NNAPI = "use_nnapi"
        const val KEY_MODEL = "model_asset"
        const val KEY_BTN_X = "btn_x"
        const val KEY_BTN_Y = "btn_y"

        private val CAPTURE_OPTIONS = intArrayOf(640, 960, 1280, 1600)
        private val CAPTURE_LABELS = arrayOf(
            "640（最快，目标小易漏）",
            "960（快，日常推荐）",
            "1280（均衡，推荐）",
            "1600（清晰但明显更慢）"
        )
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var confLabel: TextView
    private lateinit var captureLabel: TextView
    private lateinit var confSeek: SeekBar
    private lateinit var captureSeek: SeekBar
    private lateinit var nnapiSwitch: Switch
    private lateinit var permissionHint: TextView

    private var pendingConf = 0.25f
    private var pendingCaptureIndex = 2
    private var pendingNnapi = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                startOverlayService(result.resultCode, data)
            } else {
                toast("已取消屏幕录制授权")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论是否授予都继续：拒了只是看不到通知，前台服务仍可运行
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readPrefs()
        setContentView(buildContentView())
        applyPrefsToViews()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------------ UI

    private fun buildContentView(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(title("YOLOv8 屏幕实时识别"))
        root.addView(caption(
            "原理：MediaProjection 抓屏 → YOLOv8 ONNX 推理 → 悬浮窗绘制。\n" +
                "绘制层带 FLAG_NOT_TOUCHABLE，手指事件全部穿透到下方 App，正常点击不受影响。"
        ))

        permissionHint = TextView(this).apply {
            setTextColor(Color.parseColor("#D32F2F"))
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(permissionHint)

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#1B5E20"))
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(statusText)

        startButton = Button(this).apply {
            text = "① 授权悬浮窗并开始识别"
            setOnClickListener { onStartClicked() }
        }
        root.addView(startButton, matchWidth())

        stopButton = Button(this).apply {
            text = "停止识别"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_STOP)
                )
                statusText.postDelayed({ refreshStatus() }, 400)
            }
        }
        root.addView(stopButton, matchWidth())

        root.addView(sectionTitle("置信度阈值"))
        confLabel = TextView(this).apply { textSize = 14f }
        root.addView(confLabel)
        confSeek = SeekBar(this).apply {
            max = 90
            progress = ((pendingConf * 100f).toInt() - 5).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    pendingConf = (value + 5) / 100f
                    confLabel.text = "当前：${"%.2f".format(pendingConf)}（越高越保守）"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putFloat(KEY_CONF, pendingConf).apply()
                    pushConfigToService()
                }
            })
        }
        root.addView(confSeek, matchWidth())

        root.addView(sectionTitle("抓屏分辨率（性能关键）"))
        captureLabel = TextView(this).apply { textSize = 14f }
        root.addView(captureLabel)
        captureSeek = SeekBar(this).apply {
            max = CAPTURE_OPTIONS.size - 1
            progress = pendingCaptureIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    pendingCaptureIndex = value
                    captureLabel.text = "当前：${CAPTURE_LABELS[value]}\n改动后需重启识别生效"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putInt(KEY_CAPTURE_MAX_DIM, CAPTURE_OPTIONS[pendingCaptureIndex])
                        .apply()
                }
            })
        }
        root.addView(captureSeek, matchWidth())

        root.addView(sectionTitle("加速选项"))
        nnapiSwitch = Switch(this).apply {
            text = "使用 NNAPI（部分机型更快，也可能更慢或不支持）"
            setOnCheckedChangeListener { _, checked ->
                pendingNnapi = checked
                prefs.edit().putBoolean(KEY_NNAPI, checked).apply()
            }
        }
        root.addView(nnapiSwitch, matchWidth())

        root.addView(sectionTitle("悬浮小球操作"))
        root.addView(caption("单击 = 暂停/恢复绘制\n拖动 = 移动位置\n长按 = 停止识别"))

        root.addView(sectionTitle("模型文件"))
        root.addView(caption(
            "把 yolov8n.onnx 放到 app/src/main/assets/ 再重新安装。\n" +
                "导出命令见 tools/export_yolov8.py 或 README。"
        ))

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.parseColor("#0D47A1"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#212121"))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Color.parseColor("#616161"))
        setPadding(0, 0, 0, dp(4))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ 状态

    private fun readPrefs() {
        pendingConf = prefs.getFloat(KEY_CONF, 0.25f)
        pendingNnapi = prefs.getBoolean(KEY_NNAPI, false)
        val saved = prefs.getInt(KEY_CAPTURE_MAX_DIM, 1280)
        pendingCaptureIndex = CAPTURE_OPTIONS.indexOf(saved).takeIf { it >= 0 } ?: 2
    }

    private fun applyPrefsToViews() {
        confSeek.progress = ((pendingConf * 100f).toInt() - 5).coerceIn(0, 90)
        captureSeek.progress = pendingCaptureIndex
        confLabel.text = "当前：${"%.2f".format(pendingConf)}（越高越保守）"
        captureLabel.text = "当前：${CAPTURE_LABELS[pendingCaptureIndex]}\n改动后需重启识别生效"
        nnapiSwitch.isChecked = pendingNnapi
    }

    private fun refreshStatus() {
        val running = OverlayService.isRunning
        statusText.text = if (running) "状态：运行中 ✅" else "状态：未运行"
        statusText.setTextColor(
            if (running) Color.parseColor("#1B5E20") else Color.parseColor("#616161")
        )
        startButton.isEnabled = !running
        stopButton.isEnabled = true
        permissionHint.text = if (Settings.canDrawOverlays(this)) {
            ""
        } else {
            "⚠ 还没有「显示在其他应用上层」权限，点开始后会跳去设置页，请手动打开。"
        }
    }

    // ------------------------------------------------------------------ 流程

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予「显示在其他应用上层」权限")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            toast("此设备不支持屏幕录制")
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, projectionData: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, projectionData)
            putExtra(OverlayService.EXTRA_CONF, pendingConf)
            putExtra(OverlayService.EXTRA_CAPTURE_MAX_DIM, CAPTURE_OPTIONS[pendingCaptureIndex])
            putExtra(OverlayService.EXTRA_USE_NNAPI, pendingNnapi)
            putExtra(
                OverlayService.EXTRA_MODEL_ASSET,
                prefs.getString(KEY_MODEL, OverlayService.DEFAULT_MODEL)
            )
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.postDelayed({ refreshStatus() }, 800)
    }

    private fun pushConfigToService() {
        if (!OverlayService.isRunning) return
        // 已经在跑的话，用一次带 ACTION_START 的调用刷新阈值（不会重复建 VirtualDisplay）
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_CONF, pendingConf)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
