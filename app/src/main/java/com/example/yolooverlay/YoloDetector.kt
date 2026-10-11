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
 * ## 设计原则：不依赖 ORT 的元数据 API
 *
 * `OrtSession.getInputInfo()` / `getOutputInfo()` 在部分 Android 设备/版本上会返回
 * 非 TensorInfo 甚至空 map（参见 microsoft/onnxruntime#31198），因此在真机上直接
 * `info as TensorInfo` 是不安全的。本实现：
 *
 *   1. 元数据**只作为最佳努力的提示**，整段包在 try/catch 里；
 *   2. 拿不到就按 ultralytics ONNX 的默认布局（NCHW / 输出 [1,84,8400]）装配；
 *   3. **启动时做一次预热推理**：失败了就换另一种输入布局再试一次，
 *      都失败才抛异常，并把真实原因带出去；
 *   4. 输出锚点数是**从实际输出元素个数反推**的，不靠元数据。
 */
class YoloDetector(
    context: Context,
    private val labels: List<String>,
    modelAsset: String = "yolov8n.onnx",
    /** 网络输入边长；仅当模型元数据和预热都拿不到尺寸时作为兜底 */
    private val inputSizeHint: Int = 640,
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

    // 输入布局（预热阶段可能被改写）
    private var channelFirst = true
    private var netSize = inputSizeHint
    private var planeSize = inputSizeHint * inputSizeHint
    private var inputShape: LongArray = longArrayOf(1, 3, inputSizeHint.toLong(), inputSizeHint.toLong())
    private lateinit var inputFloats: FloatArray
    private lateinit var inputBuffer: FloatBuffer

    // 输出布局
    private var outChannelsFirst = true
    private var numAnchors = 0
    private var numChannels = 0
    private var outFloats = FloatArray(0)

    private val numClasses = labels.size
    private val expectChannels = 4 + labels.size

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

        inputName = session.inputNames.first()

        // ---------------------------------------------------- 1. 输入布局
        configureInputFromMetadata()

        // ---------------------------------------------------- 2. 预热
        var warm = warmUpOnce()
        if (warm == null && channelFirst) {
            // 有些导出是 NHWC。NCHW 失败就换过来再试一次。
            Log.w(TAG, "NCHW 预热失败，改用 NHWC 重试")
            configureInput(false, netSize)
            warm = warmUpOnce()
        }
        val warmed = warm
            ?: error("模型无法推理：NCHW 与 NHWC 两种输入布局都失败（model=$modelAsset）")

        // ---------------------------------------------------- 3. 输出布局
        configureOutput(warmed.first, warmed.second)

        Log.i(
            TAG,
            "模型就绪 输入=$inputName ${if (channelFirst) "NCHW" else "NHWC"} " +
                "net=$netSize 输出=${if (outChannelsFirst) "C-first" else "C-last"} " +
                "anchors=$numAnchors channels=$numChannels"
        )
    }

    // ------------------------------------------------------------------ 装配

    private fun loadModelBytes(context: Context, assetName: String): ByteArray =
        context.assets.open(assetName).use { it.readBytes() }

    /** 元数据是「能读到最好」，读不到不算错误。 */
    private fun metadataShape(name: String, isInput: Boolean): IntArray? {
        return try {
            val info = if (isInput) session.inputInfo[name] else session.outputInfo[name]
            val ti = info as? TensorInfo ?: run {
                Log.w(TAG, "元数据不是 TensorInfo: ${if (isInput) "输入" else "输出"} $name -> $info")
                return null
            }
            ti.shape.map { it.toInt() }.toIntArray()
        } catch (t: Throwable) {
            Log.w(TAG, "读取元数据异常(${if (isInput) "输入" else "输出"} $name): $t")
            null
        }
    }

    private fun configureInputFromMetadata() {
        val shape = metadataShape(inputName, true)
        if (shape != null && shape.size == 4) {
            when {
                shape[1] == 3 -> configureInput(true, shape[2].takeIf { it > 0 } ?: inputSizeHint)
                shape[3] == 3 -> configureInput(false, shape[1].takeIf { it > 0 } ?: inputSizeHint)
                else -> {
                    Log.w(TAG, "输入形状 ${shape.toList()} 不是 NCHW/NHWC，按 NCHW 处理")
                    configureInput(true, inputSizeHint)
                }
            }
        } else {
            Log.w(
                TAG,
                "拿不到输入形状，按 ultralytics ONNX 默认 NCHW " +
                    "[1,3,$inputSizeHint,$inputSizeHint] 装配"
            )
            configureInput(true, inputSizeHint)
        }
    }

    private fun configureInput(chFirst: Boolean, size: Int) {
        channelFirst = chFirst
        netSize = size
        planeSize = size * size
        inputShape = if (chFirst) {
            longArrayOf(1L, 3L, size.toLong(), size.toLong())
        } else {
            longArrayOf(1L, size.toLong(), size.toLong(), 3L)
        }
        inputFloats = FloatArray(planeSize * 3)
        inputBuffer = ByteBuffer
            .allocateDirect(planeSize * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /**
     * 跑一次全灰图。返回 (输出元素总数, 输出形状 or null)；失败返回 null。
     * 顺便把 ORT 的图优化触发掉，让第一帧真实推理更快。
     */
    private fun warmUpOnce(): Pair<Int, IntArray?>? {
        return try {
            Arrays.fill(inputFloats, PAD_VALUE)
            inputBuffer.clear()
            inputBuffer.put(inputFloats)
            inputBuffer.rewind()

            OnnxTensor.createTensor(env, inputBuffer, inputShape).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val shape = (out.info as? TensorInfo)?.shape?.map { it.toInt() }?.toIntArray()
                    val total = out.floatBuffer.capacity()
                    total to shape
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "预热失败(当前布局 ${if (channelFirst) "NCHW" else "NHWC"}, net=$netSize): $t")
            null
        }
    }

    private fun configureOutput(total: Int, shape: IntArray?) {
        if (shape != null && shape.size >= 2) {
            when {
                shape.size == 3 && shape[1] == expectChannels -> {
                    outChannelsFirst = true; numChannels = shape[1]; numAnchors = shape[2]
                }
                shape.size == 3 && shape[2] == expectChannels -> {
                    outChannelsFirst = false; numChannels = shape[2]; numAnchors = shape[1]
                }
                shape.size == 2 && shape[0] == expectChannels -> {
                    outChannelsFirst = true; numChannels = shape[0]; numAnchors = shape[1]
                }
                shape.size == 2 -> {
                    outChannelsFirst = false; numChannels = shape[1]; numAnchors = shape[0]
                }
            }
        }

        if (numAnchors <= 0) {
            if (total <= 0 || total % expectChannels != 0) {
                error("输出元素数 $total 无法按 $expectChannels 通道解析（shape=${shape?.toList()}）")
            }
            numChannels = expectChannels
            numAnchors = total / expectChannels
            outChannelsFirst = true
            Log.w(
                TAG,
                "拿不到输出形状，按元素总数反推：anchors=$numAnchors channels=$numChannels"
            )
        }

        outFloats = FloatArray(numAnchors * numChannels)
    }

    // ------------------------------------------------------------------ 推理

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
                val n = min(outFloats.size, fb.remaining())
                fb.get(outFloats, 0, n)
            }
        }

        val raw = decode(padX, padY, scale, srcWidth, srcHeight)
        val kept = nms(raw)
        kept.sortByDescending { it.score }
        return if (kept.size > maxDetections) kept.subList(0, maxDetections) else kept
    }

    /**
     * 把源帧等比缩放进 netSize×netSize 的 letterbox，其余填 114/255。
     * NCHW 写成分平面，NHWC 写成 RGB 交错。
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
