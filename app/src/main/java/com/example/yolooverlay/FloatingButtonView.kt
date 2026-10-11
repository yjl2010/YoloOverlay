package com.example.yolooverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * 唯一可以接收触摸的悬浮控件。
 * 它很小（默认 46dp），并且和绘制层分开成两个 Window，
 * 所以绘制层的 FLAG_NOT_TOUCHABLE 不会被破坏。
 *
 * 交互：
 *   单击   -> 暂停 / 恢复绘制
 *   拖动   -> 移动位置
 *   长按   -> 弹出菜单（停止服务）
 */
@SuppressLint("ViewConstructor")
class FloatingButtonView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val sizePx = (46 * density).toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var active = true

    fun setActive(value: Boolean) {
        active = value
        invalidate()
    }

    fun isActive() = active

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 2f * density

        bgPaint.color = if (active) 0xCC1B5E20.toInt() else 0xCC4E342E.toInt()
        canvas.drawCircle(cx, cy, radius, bgPaint)

        ringPaint.color = if (active) 0xFF69F0AE.toInt() else 0xFFFFAB40.toInt()
        ringPaint.strokeWidth = 2.5f * density
        canvas.drawCircle(cx, cy, radius - 1.5f * density, ringPaint)

        canvas.drawText("AI", cx, cy + textPaint.textSize * 0.36f, textPaint)
    }
}
