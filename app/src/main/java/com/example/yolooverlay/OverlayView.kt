package com.example.yolooverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

/**
 * 只负责画框的悬浮层。
 *
 * 这个 View 所在的 Window 带有 FLAG_NOT_TOUCHABLE，
 * 所以它对触摸是完全透明的 —— 手指事件直接落到下面的 App 上。
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f * density, 0f, 1f * density, 0xAA000000.toInt())
    }

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(4f * density, 0f, 1f * density, 0xCC000000.toInt())
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt()
    }

    private val classColors = IntArray(256) { i ->
        val hue = (i * 47f) % 360f
        Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
    }

    private var detections: List<Detection> = emptyList()
    private var srcWidth = 1
    private var srcHeight = 1
    private var fps = 0f
    private var costMs = 0L
    private var active = true

    private val rect = RectF()
    private val hudRect = RectF()
    private val errorRect = RectF()
    private val currentRects = ArrayList<RectF>(48)

    /** 出错时把原因画在屏幕上，方便截图排查（不用连 adb） */
    private var errorText: String? = null

    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val errorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6B71C1C.toInt()
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) detections = emptyList()
        invalidate()
    }

    fun isActive() = active

    fun showError(message: String) {
        errorText = message
        postInvalidateOnAnimation()
    }

    fun clearError() {
        if (errorText != null) {
            errorText = null
            postInvalidateOnAnimation()
        }
    }

    fun update(result: YoloPipeline.DetectionResult) {
        detections = result.detections
        srcWidth = result.sourceWidth.coerceAtLeast(1)
        srcHeight = result.sourceHeight.coerceAtLeast(1)
        fps = result.fps
        costMs = result.inferenceMs
        postInvalidateOnAnimation()
    }

    fun clear() {
        detections = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        if (active && detections.isNotEmpty()) {
            val sx = width.toFloat() / srcWidth
            val sy = height.toFloat() / srcHeight

            currentRects.clear()
            currentRects.ensureCapacity(detections.size)
            for (d in detections) {
                currentRects.add(
                    RectF(d.left * sx, d.top * sy, d.right * sx, d.bottom * sy)
                )
            }

            for (i in detections.indices) {
                val d = detections[i]
                val r = currentRects[i]
                if (r.width() < 1f || r.height() < 1f) continue
                val color = classColors[d.classId and 0xFF]

                boxPaint.color = color
                canvas.drawRect(r, boxPaint)

                // 左上角小角标，视觉上更轻量
                val corner = 10f * density
                boxPaint.strokeWidth = 4f * density
                canvas.drawLine(r.left, r.top, r.left + corner, r.top, boxPaint)
                canvas.drawLine(r.left, r.top, r.left, r.top + corner, boxPaint)
                canvas.drawLine(r.right, r.bottom, r.right - corner, r.bottom, boxPaint)
                canvas.drawLine(r.right, r.bottom, r.right, r.bottom - corner, boxPaint)
                boxPaint.strokeWidth = 2.5f * density

                val text = "${CocoLabels.displayName(d.label)} ${(d.score * 100).roundToInt()}%"
                val textWidth = textPaint.measureText(text)
                val textHeight = textPaint.textSize
                val pad = 4f * density

                var labelTop = r.top - textHeight - pad * 2f
                if (labelTop < 0f) labelTop = r.top
                var labelLeft = r.left
                if (labelLeft + textWidth + pad * 2f > width) {
                    labelLeft = width - textWidth - pad * 2f
                }

                fillPaint.color = color
                canvas.drawRoundRect(
                    labelLeft,
                    labelTop,
                    labelLeft + textWidth + pad * 2f,
                    labelTop + textHeight + pad * 2f,
                    3f * density,
                    3f * density,
                    fillPaint
                )

                // 浅色底用黑字
                textPaint.color = if (isLight(color)) Color.BLACK else Color.WHITE
                canvas.drawText(text, labelLeft + pad, labelTop + textHeight + pad * 0.6f, textPaint)
            }
        }

        drawHud(canvas)
        drawError(canvas)
    }

    /** 红色错误面板：把异常链和堆栈画出来，截个图就能定位 */
    private fun drawError(canvas: Canvas) {
        val msg = errorText ?: return
        val pad = 10f * density
        val maxW = width - pad * 4f
        if (maxW <= 0f) return

        val lines = wrapText(msg, errorPaint, maxW)
        val lineH = errorPaint.textSize * 1.3f
        val maxLines = ((height * 0.55f) / lineH).toInt().coerceAtLeast(3)
        val shown = lines.take(maxLines)

        val h = pad * 2f + lineH * shown.size
        errorRect.set(pad, pad, width - pad, pad + h)
        canvas.drawRoundRect(errorRect, 8f * density, 8f * density, errorBgPaint)

        var y = errorRect.top + pad + errorPaint.textSize
        for (l in shown) {
            canvas.drawText(l, errorRect.left + pad, y, errorPaint)
            y += lineH
        }
    }

    /** 按字符宽度硬折行；消息很短，性能无所谓 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                out.add("")
                continue
            }
            val sb = StringBuilder()
            for (ch in para) {
                if (sb.isNotEmpty() && paint.measureText(sb.toString() + ch) > maxWidth) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                sb.append(ch)
            }
            out.add(sb.toString())
        }
        return out
    }

    private fun drawHud(canvas: Canvas) {
        val badge = if (active) "YOLOv8 运行中" else "YOLOv8 已暂停"
        val line1 = badge
        val line2 = if (active) {
            "${"%.1f".format(fps)} FPS · ${costMs}ms · ${detections.size} 个目标"
        } else {
            "点一下绿点恢复"
        }

        val pad = 8f * density
        val w = maxOf(hudPaint.measureText(line1), hudPaint.measureText(line2)) + pad * 2f
        val lineH = hudPaint.textSize + 3f * density
        val h = lineH * 2f + pad * 2f

        hudRect.set(8f * density, height - h - 8f * density, 8f * density + w, height - 8f * density)
        canvas.drawRoundRect(hudRect, 6f * density, 6f * density, hudBgPaint)

        hudPaint.color = if (active) 0xFF69F0AE.toInt() else 0xFFFFAB40.toInt()
        canvas.drawText(line1, hudRect.left + pad, hudRect.top + pad + hudPaint.textSize, hudPaint)
        hudPaint.color = Color.WHITE
        canvas.drawText(
            line2,
            hudRect.left + pad,
            hudRect.top + pad + lineH + hudPaint.textSize,
            hudPaint
        )
    }

    private fun isLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }

    companion object {
        fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )
    }
}
