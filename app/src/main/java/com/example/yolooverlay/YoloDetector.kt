package com.example.yolooverlay

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv8 ONNX 推理器（ONNX Runtime for Android）。
 *
 * 输入：一帧 ARGB_8888 的 IntArray（来自 MediaProjection 的 RGBA 采集） + 宽高
 * 输出：原图坐标系下的检测框列表
 *
 * 关键设计：**输入布局在运行时探测**，不写死。
 *   PyTorch 原生是 NCHW，所以 ultralytics 的 ONNX 导出通常是 `images [1,3,640,640]`；
 *   但某些导出配置（以及 TFLite 系）会给 NHWC `[1,640,640,3]`。
 *   两种都支持，避免以后换导出参数就整个跑不起来。
 *
 * 输出同样探测：`[1, 4+nc, 8400]` 或 `[1, 8400, 4+nc]` 都能识别。
 */
class YoloDetector(
    context: Context,
    private val labels: List<String>,
    modelAsset: String = "yolov8n.onnx",
    /** 仅在模型输入维度是动态 (-1) 时作为兜底 */
    inputSizeHint: Int = 640,
    initialConf: Float = 0.25f,
    initialIou: Float = 0.45f,
    private val maxDetections: Int = 50,
    numThreads: Int = 4,
    useNnapi: Boolean = false
) {

    /** 可以在推理过程中被其它线程热更新 */
    @Volatile
    var confThreshold: Float = initialConf

    @Volatile
    var iouThreshold: Float = initialIou

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputShape: LongArray

    /** 模型输入是否为 NCHW（通道在前） */
    private val channelFirst: Boolean
    private val netSize: Int
    private val planeSize: Int
    private val inputFloats: FloatArray
    private val inputBuffer: FloatBuffer

    private var outFloats: FloatArray = FloatArray(0)

    // 输出布局缓存
    private var outChannelsFirst = true
    private var numAnchors = 0
    private var numChannels = 0

    private val numClasses = labels.size

    init {
        require(labels.isNotEmpty()) { "labels 不能为空" }

        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(numThreads)
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        } catch (t: Throwable) {
            Log.w(TAG, "设置优化级别失败，用默认值", t)
        }
        if (useNnapi) {
            try {
                options.addNnapi()
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI 不可用，回退 CPU", t)
            }
        }

        val modelBytes = loadModelBytes(context, modelAsset)
        Log.i(TAG, "模型 $modelAsset 读取完成 ${modelBytes.size} 字节")

        session = env.createSession(modelBytes, options)
        try {
            options.close()
        } catch (_: Throwable) {
        }

        // ---------------------------------------------------------- 输入布局探测
        inputName = session.inputNames.first()
        val inInfo = session.inputInfo[inputName]
        if (inInfo !is TensorInfo) error("无法读取输入张量信息 $inputName")
        val rawIn = inInfo.shape.map { it.toInt() }.toIntArray()

        var size = inputSizeHint
        var chFirst = true
        when {
            rawIn.size == 4 && rawIn[1] == 3 -> {
                // NCHW: [1,3,H,W]
                chFirst = true
                if (rawIn[2] > 0) size = rawIn[2]
            }
            rawIn.size == 4 && rawIn[3] == 3 -> {
                // NHWC: [1,H,W,3]
                chFirst = false
                if (rawIn[1] > 0) size = rawIn[1]
            }
            else -> {
                Log.w(TAG, "输入形状 ${rawIn.toList()} 不是标准四维，按 NCHW 处理")
            }
        }
        channelFirst = chFirst
        netSize = size
        planeSize = netSize * netSize
        inputShape = if (channelFirst) {
            longArrayOf(1L, 3L, netSize.toLong(), netSize.toLong())
        } else {
            longArrayOf(1L, netSize.toLong(), netSize.toLong(), 3L)
        }
        inputFloats = FloatArray(planeSize * 3)
        inputBuffer = ByteBuffer
            .allocateDirect(planeSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // ---------------------------------------------------------- 输出布局探测
        val outName = session.outputNames.first()
        val outInfo = session.outputInfo[outName]
        if (outInfo !is TensorInfo) error("无法读取输出张量信息 $outName")
        prepareOutputLayout(outInfo.shape.map { it.toInt() }.toIntArray())

        Log.i(
            TAG,
            "模型已加载 输入=$inputName ${rawIn.toList()} " +
                "(${if (channelFirst) "NCHW" else "NHWC"}, $netSize) " +
                "输出=$outName channelsFirst=$outChannelsFirst " +
                "anchors=$numAnchors channels=$numChannels"
        )
    }

    private fun loadModelBytes(context: Context, assetName: String): ByteArray =
        context.assets.open(assetName).use { it.readBytes() }

    private fun prepareOutputLayout(shape: IntArray) {
        val expect = 4 + numClasses
        when (shape.size) {
            3 -> {
                if (shape[1] == expect) {
                    outChannelsFirst = true; numChannels = shape[1]; numAnchors = shape[2]
                } else if (shape[2] == expect) {
                    outChannelsFirst = false; numChannels = shape[2]; numAnchors = shape[1]
                } else {
                    error("无法识别的输出形状 ${shape.toList()}，期望包含 $expect 个通道")
                }
            }
            2 -> {
                if (shape[0] == expect) {
                    outChannelsFirst = true; numChannels = shape[0]; numAnchors = shape[1]
                } else {
                    outChannelsFirst = false; numChannels = shape[1]; numAnchors = shape[0]
                }
            }
            else -> error("不支持的输出维度 ${shape.size}: ${shape.toList()}")
        }
        outFloats = FloatArray(numAnchors * numChannels)
    }

    /**
     * @param pixels ARGB_8888 排列的像素（alpha 会被忽略）
     */
    fun detect(pixels: IntArray, srcWidth: Int, srcHeight: Int): List<Detection> {
        if (srcWidth <= 1 || srcHeight <= 1 || pixels.size < srcWidth * srcHeight) return emptyList()

        val scale = min(netSize.toFloat() / srcWidth, netSize.toFloat() / srcHeight)
        val newW = max(1, (srcWidth * scale).roundToInt())
        val newH = max(1, (srcHeight * scale).roundToInt())
        val padX = (netSize - newW) / 2
        val padY = (netSize - newH) / 2

        letterbox(pixels, srcWidth, srcHeight, newW, newH, padX, padY)

        inputBuffer.clear()
        inputBuffer.put(inputFloats)
        inputBuffer.rewind()

        OnnxTensor.createTensor(env, inputBuffer, inputShape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                val fb = out.floatBuffer
                fb.rewind()
                fb.get(outFloats, 0, outFloats.size)
            }
        }

        val raw = decode(padX, padY, scale, srcWidth, srcHeight)
        val kept = nms(raw)
        kept.sortByDescending { it.score }
        return if (kept.size > maxDetections) kept.subList(0, maxDetections) else kept
    }

    /**
     * 把源帧等比缩放进 netSize×netSize 的 letterbox，其余填 114/255。
     * 按探测到的布局写：NCHW 写成分平面，NHWC 写成 RGB 交错。
     */
    private fun letterbox(
        pixels: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        newW: Int,
        newH: Int,
        padX: Int,
        padY: Int
    ) {
        Arrays.fill(inputFloats, PAD_VALUE)

        val xRatio = srcWidth.toFloat() / newW
        val yRatio = srcHeight.toFloat() / newH

        if (channelFirst) {
            for (dy in 0 until newH) {
                val sy = min(srcHeight - 1, (dy * yRatio).toInt())
                val srcRow = sy * srcWidth
                var dst = (dy + padY) * netSize + padX
                for (dx in 0 until newW) {
                    val sx = min(srcWidth - 1, (dx * xRatio).toInt())
                    val px = pixels[srcRow + sx]
                    inputFloats[dst] = ((px ushr 16) and 0xFF) / 255f
                    inputFloats[planeSize + dst] = ((px ushr 8) and 0xFF) / 255f
                    inputFloats[planeSize * 2 + dst] = (px and 0xFF) / 255f
                    dst++
                }
            }
        } else {
            val dstStride = netSize * 3
            for (dy in 0 until newH) {
                val sy = min(srcHeight - 1, (dy * yRatio).toInt())
                val srcRow = sy * srcWidth
                var dst = (dy + padY) * dstStride + padX * 3
                for (dx in 0 until newW) {
                    val sx = min(srcWidth - 1, (dx * xRatio).toInt())
                    val px = pixels[srcRow + sx]
                    inputFloats[dst++] = ((px ushr 16) and 0xFF) / 255f
                    inputFloats[dst++] = ((px ushr 8) and 0xFF) / 255f
                    inputFloats[dst++] = (px and 0xFF) / 255f
                }
            }
        }
    }

    private fun value(channel: Int, anchor: Int): Float =
        if (outChannelsFirst) outFloats[channel * numAnchors + anchor]
        else outFloats[anchor * numChannels + channel]

    private fun decode(
        padX: Int,
        padY: Int,
        scale: Float,
        srcWidth: Int,
        srcHeight: Int
    ): MutableList<Detection> {
        val result = ArrayList<Detection>(32)
        val invScale = 1f / scale

        for (a in 0 until numAnchors) {
            var bestId = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = value(4 + c, a)
                if (s > bestScore) {
                    bestScore = s
                    bestId = c
                }
            }
            if (bestId < 0 || bestScore < confThreshold) continue

            val cx = value(0, a)
            val cy = value(1, a)
            val bw = value(2, a)
            val bh = value(3, a)

            // 从 netSize 的 letterbox 空间回到源帧像素坐标
            val left = ((cx - bw * 0.5f) - padX) * invScale
            val top = ((cy - bh * 0.5f) - padY) * invScale
            val right = ((cx + bw * 0.5f) - padX) * invScale
            val bottom = ((cy + bh * 0.5f) - padY) * invScale

            val l = left.coerceIn(0f, srcWidth.toFloat())
            val t = top.coerceIn(0f, srcHeight.toFloat())
            val r = right.coerceIn(0f, srcWidth.toFloat())
            val b = bottom.coerceIn(0f, srcHeight.toFloat())
            if (r - l < 2f || b - t < 2f) continue

            result.add(
                Detection(
                    left = l,
                    top = t,
                    right = r,
                    bottom = b,
                    score = bestScore,
                    classId = bestId,
                    label = labels[bestId]
                )
            )
        }
        return result
    }

    /** 逐类别 NMS */
    private fun nms(input: List<Detection>): MutableList<Detection> {
        if (input.isEmpty()) return ArrayList()
        val sorted = input.sortedByDescending { it.score }
        val kept = ArrayList<Detection>(sorted.size)
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            kept.add(a)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val b = sorted[j]
                if (a.classId != b.classId) continue
                if (iou(a, b) > iouThreshold) suppressed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val w = x2 - x1
        val h = y2 - y1
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "YoloDetector"
        const val PAD_VALUE = 114f / 255f
    }
}
