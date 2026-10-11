package com.example.yolooverlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 屏幕采集 + 推理流水线。
 *
 * 线程模型：
 *   captureHandlerThread ── ImageReader 回调，RGBA -> ARGB IntArray，投递到「只保留最新一帧」的队列
 *   yolo-infer           ── 阻塞取帧 -> YOLOv8 推理 -> 回调到主线程
 *
 * 队列容量为 1 且深度可丢帧，保证推理永远处理最新画面，不会积压延迟。
 */
class YoloPipeline(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: Config,
    private val listener: Listener
) {

    data class Config(
        val modelAsset: String,
        val inputSize: Int,
        val numThreads: Int,
        val useNnapi: Boolean,
        /** 采集时最长边的上限，越小越快，推荐 960 ~ 1280 */
        val captureMaxDim: Int
    )

    interface Listener {
        fun onResult(result: DetectionResult)
        fun onError(error: Throwable)
    }

    data class DetectionResult(
        val detections: List<Detection>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val inferenceMs: Long,
        val fps: Float
    )

    private class Frame(val pixels: IntArray, val width: Int, val height: Int)

    private val running = AtomicBoolean(false)
    private val captureThread = HandlerThread("yolo-capture")
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var scratch = ByteArray(0)

    private val frameQueue = ArrayBlockingQueue<Frame>(1)
    private val bufferPool = ConcurrentLinkedQueue<IntArray>()
    private var worker: Thread? = null

    @Volatile
    var confThreshold: Float = 0.25f

    fun start() {
        if (!running.compareAndSet(false, true)) return
        captureThread.start()
        val handler = Handler(captureThread.looper)
        captureHandler = handler
        handler.post { createCapture() }
        worker = Thread(::workerLoop, "yolo-infer").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val handler = captureHandler
        if (handler != null) {
            handler.post {
                releaseCapture()
                captureThread.quitSafely()
            }
        } else {
            captureThread.quitSafely()
        }
        captureHandler = null
        worker?.interrupt()
        worker = null
        frameQueue.clear()
        bufferPool.clear()
    }

    /** 旋转 / 分辨率变化后调用，重建采集面。 */
    fun reconfigureCapture() {
        val handler = captureHandler ?: return
        handler.post {
            if (!running.get()) return@post
            val size = computeCaptureSize(ScreenUtils.currentBounds(context))
            if (size.width == captureWidth && size.height == captureHeight) return@post

            val old = imageReader
            val reader = newReader(size)
            val vd = virtualDisplay
            if (vd == null) {
                releaseCapture()
                createCapture()
                old?.setOnImageAvailableListener(null, null)
                old?.close()
                return@post
            }
            vd.resize(size.width, size.height, context.resources.displayMetrics.densityDpi)
            vd.setSurface(reader.surface)
            imageReader = reader
            captureWidth = size.width
            captureHeight = size.height
            old?.setOnImageAvailableListener(null, null)
            old?.close()
            frameQueue.clear()
            bufferPool.clear()
            Log.i(TAG, "采集面重建为 ${size.width}x${size.height}")
        }
    }

    // ------------------------------------------------------------------ 采集

    private fun createCapture() {
        if (!running.get()) return
        val size = computeCaptureSize(ScreenUtils.currentBounds(context))
        val reader = newReader(size)
        val density = context.resources.displayMetrics.densityDpi
        virtualDisplay = projection.createVirtualDisplay(
            "yolo-capture",
            size.width,
            size.height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
        imageReader = reader
        captureWidth = size.width
        captureHeight = size.height
        Log.i(TAG, "采集已启动 ${size.width}x${size.height} density=$density")
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        onImageAvailable(reader)
    }

    private fun newReader(size: Size): ImageReader {
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener(imageListener, captureHandler)
        return reader
    }

    private fun releaseCapture() {
        imageReader?.setOnImageAvailableListener(null, null)
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        imageReader = null
    }

    private fun computeCaptureSize(bounds: Rect): Size {
        var w = max(2, bounds.width())
        var h = max(2, bounds.height())
        val longest = max(w, h)
        val cap = max(320, config.captureMaxDim)
        if (longest > cap) {
            val s = cap.toFloat() / longest
            w = (w * s).roundToInt()
            h = (h * s).roundToInt()
        }
        // 保证偶数，避免部分解码器对齐问题
        return Size(max(2, w and 1.inv()), max(2, h and 1.inv()))
    }

    private fun onImageAvailable(reader: ImageReader) {
        val raw: Image? = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null
        }
        val image = raw ?: return
        try {
            copyImage(image)
        } catch (t: Throwable) {
            Log.w(TAG, "拷贝帧失败", t)
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun copyImage(image: Image) {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return

        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        buffer.rewind()

        val need = width * height
        var pixels = bufferPool.poll()
        if (pixels == null || pixels.size != need) pixels = IntArray(need)

        if (pixelStride == 4 && rowStride == width * 4 && buffer.remaining() >= need * 4) {
            // 快路径：内存里是 R,G,B,A，按小端读成 int 就是 A<<24|B<<16|G<<8|R
            val ints = buffer.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
            for (i in 0 until need) {
                val v = ints.get(i)
                pixels[i] = (v and 0xFF00FF00.toInt()) or
                    ((v and 0xFF) shl 16) or
                    ((v ushr 16) and 0xFF)
            }
        } else {
            val needed = rowStride * height
            if (scratch.size < needed) scratch = ByteArray(needed)
            buffer.get(scratch, 0, minOf(needed, buffer.remaining()))
            var src = 0
            var dst = 0
            for (y in 0 until height) {
                var p = src
                for (x in 0 until width) {
                    val r = scratch[p].toInt() and 0xFF
                    val g = scratch[p + 1].toInt() and 0xFF
                    val b = scratch[p + 2].toInt() and 0xFF
                    pixels[dst++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    p += pixelStride
                }
                src += rowStride
            }
        }

        publish(Frame(pixels, width, height))
    }

    /** 只保留最新一帧：队列满了就丢掉旧帧并把它的缓冲区还回池子。 */
    private fun publish(frame: Frame) {
        while (!frameQueue.offer(frame)) {
            val old = frameQueue.poll() ?: break
            bufferPool.offer(old.pixels)
        }
    }

    // ------------------------------------------------------------------ 推理

    private fun workerLoop() {
        val detector = try {
            YoloDetector(
                context = context,
                labels = CocoLabels.NAMES,
                modelAsset = config.modelAsset,
                inputSizeHint = config.inputSize,
                initialConf = confThreshold,
                numThreads = config.numThreads,
                useNnapi = config.useNnapi
            )
        } catch (t: Throwable) {
            Log.e(TAG, "模型加载失败", t)
            mainHandler.post { listener.onError(t) }
            return
        }

        var emaFps = 0f
        while (running.get()) {
            val frame: Frame = try {
                frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }

            detector.confThreshold = confThreshold
            val t0 = SystemClock.elapsedRealtime()
            val detections = try {
                detector.detect(frame.pixels, frame.width, frame.height)
            } catch (t: Throwable) {
                Log.e(TAG, "推理失败", t)
                mainHandler.post { listener.onError(t) }
                emptyList()
            }
            val cost = SystemClock.elapsedRealtime() - t0

            bufferPool.offer(frame.pixels)

            val instant = if (cost > 0) 1000f / cost else 0f
            emaFps = if (emaFps <= 0f) instant else emaFps * 0.75f + instant * 0.25f

            val result = DetectionResult(
                detections = detections,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                inferenceMs = cost,
                fps = emaFps
            )
            mainHandler.post {
                if (running.get()) listener.onResult(result)
            }
        }

        detector.close()
    }

    private companion object {
        const val TAG = "YoloPipeline"
    }
}
