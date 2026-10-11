package com.example.yolooverlay

/**
 * 单个检测结果。
 * 坐标一律使用「采集帧」的像素坐标系（左上角为原点），
 * 由 OverlayView 在绘制时线性映射到悬浮窗尺寸。
 */
data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val classId: Int,
    val label: String
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
