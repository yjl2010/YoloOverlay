package com.example.yolooverlay

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

object ScreenUtils {

    /**
     * 当前物理显示区域的完整尺寸（含状态栏/导航栏，已按当前旋转方向换算）。
     * 采集用的 VirtualDisplay 和悬浮窗都按这个尺寸对齐，保证坐标可以线性映射。
     */
    fun currentBounds(context: Context): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            if (wm != null) {
                val b = wm.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return Rect(b)
            }
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(m)
            if (m.widthPixels > 0 && m.heightPixels > 0) {
                return Rect(0, 0, m.widthPixels, m.heightPixels)
            }
        }
        val m = context.resources.displayMetrics
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }
}
