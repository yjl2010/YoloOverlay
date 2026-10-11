# YOLO 悬浮识别（Android）

用 **YOLOv8 (ONNX Runtime)** 在 Android 上抓取整个屏幕画面，实时推理，并用**悬浮窗**把检测框和置信度画在屏幕最上层。

绘制层带 `FLAG_NOT_TOUCHABLE`，手指事件 100% 穿透到下面的 App，正常点击 / 滑动 / 打字都不受影响。

```
MediaProjection 抓屏  →  VirtualDisplay + ImageReader  →  YOLOv8 ONNX 推理  →  悬浮窗 Canvas 绘制
   （全屏镜像）            （RGBA → ARGB IntArray）        （letterbox + NMS）      （触摸穿透）
```

> ⚠️ **关于模型格式**：ultralytics 从 **8.4.83** 起废弃了 `format="tflite"`，源码里强制改写成
> `format="litert"`，而 LiteRT 导出链对 torch 版本极其敏感，CI 上常直接报
> `'_OpNamespace' 'aten' object has no attribute 'cholesky'`。
> 所以本项目改用 **ONNX**：它是 ultralytics 里依赖最少、最稳的导出路径，
> Android 侧用 `onnxruntime-android`（Maven 依赖，自带预编译 `.so`，同样不需要 NDK）。
> 详见 [app/src/main/assets/README.md](app/src/main/assets/README.md)。

---

## 一、快速开始

> 📌 **如果你手上没有能跑 Android Studio 的机器**（比如只有一台 2GB 内存的容器），
> 直接看 **[BUILD-CLOUD.md](BUILD-CLOUD.md)** —— 用 GitHub Actions 云端编译，
> 本地一个编译工具都不用装，连模型导出也是在云端做的。

### 1. 导出模型（在你的 PC 上）

```bash
pip install -U ultralytics onnxruntime
python tools/export_yolov8.py
```

脚本会自动把 `yolov8n.onnx` 复制到 `app/src/main/assets/`。

也可以手动来：

```bash
yolo export model=yolov8n.pt format=onnx imgsz=640 half=False
copy yolov8n.onnx  app\src\main\assets\
```

> ⚠️ 只支持 **float32 单输出** 的导出。加了 `nms=True` 会变成多个输出张量，需要重写
> `YoloDetector.decode()`。输入必须是 `[1,640,640,3]`，输出含一个 84 的维度。

### 2. 编译安装

**方式 A —— Android Studio（推荐）**

用 Android Studio 打开工程根目录，等 Gradle Sync 完成后直接 Run。工程没带 wrapper，AS 会自己处理。

**方式 B —— 命令行**

需要 JDK 17 + Android SDK（compileSdk 34 / build-tools 34.0.0）。工程没带 wrapper jar：

```bash
gradle wrapper --gradle-version 8.7
./gradlew installDebug
```

**方式 C —— 云端编译（无需本地环境）**

见 **[BUILD-CLOUD.md](BUILD-CLOUD.md)**。

最低支持 **Android 8.0 (API 26)**，`compileSdk 34` / `targetSdk 34`。

### 3. 使用

1. 打开 App，点 **① 授权悬浮窗并开始识别**
   - 第一次会跳到「显示在其他应用上层」设置页，手动打开后返回
   - 然后弹出系统录屏授权对话框，点「立即开始」（Android 14+ 每次启动都要确认一次，这是系统强制）
2. 屏幕上出现一个绿色 `AI` 小球，同时框开始实时画出来
3. 切到任意 App，框会继续跟着屏幕内容走

小球的三种操作：

| 操作 | 效果 |
|---|---|
| 单击 | 暂停 / 恢复绘制 |
| 拖动 | 移动位置（位置会记住） |
| 长按 | 停止识别并退出 |

通知栏里也有「暂停/继续」和「停止」两个按钮。

---

## 二、关键实现点

### 触摸穿透是怎么做到的

绘制层和控制层是**两个独立的 Window**：

```kotlin
// 绘制层：不可触摸、不可聚焦、可覆盖状态栏与刘海
WindowManager.LayoutParams(
    bounds.width(), bounds.height(),
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE      // ← 关键
        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT
)
```

- `FLAG_NOT_TOUCHABLE` 让这个 Window 完全不参与触摸分发，事件直接落到下层 App，
  也不会给下层 App 打上 `FLAG_WINDOW_IS_OBSCURED`（不会触发 tapjacking 拦截）。
- 但如果在这个 Window 上再挂一个可点区域，穿透就废了。所以控制按钮单独开一个 Window
  （`FloatingButtonView`），只有那 46dp 的圆点会吃触摸。
- `FLAG_NOT_FOCUSABLE` 保证不抢输入焦点，键盘正常弹出。

### 坐标是怎么对齐的

采集用的 `VirtualDisplay` 是屏幕的等比缩小版，绘制层覆盖整块屏幕，
所以两边**宽高比一致**，直接线性映射即可：

```kotlin
val sx = view.width  / result.sourceWidth
val sy = view.height / result.sourceHeight
canvas.drawRect(d.left * sx, d.top * sy, d.right * sx, d.bottom * sy, paint)
```

旋转屏幕时，`Service.onConfigurationChanged` 里延迟 400ms 重建 `ImageReader` / `VirtualDisplay` 尺寸，
并同步调整悬浮窗大小，映射关系继续成立。

### 性能设计（很重要）

屏幕抓取 + 推理如果写不好会又慢又烫。这里做了这些事：

| 手段 | 说明 |
|---|---|
| 降分辨率采集 | `captureMaxDim` 默认 1280，抓 4K 再缩到 640 纯属浪费 |
| 双线程流水线 | 采集线程只负责拷贝像素，推理线程只跑模型 |
| **只保留最新帧** | 队列容量 1，满了就丢旧帧。永远不会积压出几百毫秒的延迟 |
| 零分配缓冲池 | `IntArray` 帧缓冲在采集/推理线程之间循环复用，稳态下不产生 GC |
| 快速通道 | RGBA→ARGB 用 `asIntBuffer` 位运算，避免逐通道取字节 |
| 手动 letterbox | 直接写进 `ByteBuffer`，不经过 Bitmap，省一次内存拷贝 |

实测参考（`yolov8n`，640 输入，4 线程 CPU）：中端机约 **8~15 FPS**，旗舰机 15~25 FPS。

调优建议：
- 卡 → 把「抓屏分辨率」调到 960 或 640
- 还是卡 → 打开 NNAPI 试试（部分芯片明显快，部分反而更慢，逐个机型试）
- 想更准 → 换 `yolov8s.pt`，但帧率会掉

---

## 三、工程结构

```
app/src/main/java/com/example/yolooverlay/
├── MainActivity.kt          授权流程 + 参数设置界面
├── OverlayService.kt        前台服务：持有 MediaProjection，管理两个悬浮窗
├── YoloPipeline.kt          采集 + 推理流水线（双线程、丢帧、缓冲池）
├── YoloDetector.kt          ONNX Runtime 推理：letterbox / 解码 / 逐类 NMS
├── OverlayView.kt           穿透绘制层：画框、标签、置信度、FPS HUD
├── FloatingButtonView.kt    可触摸的小球控件
├── Detection.kt             检测结果数据类
├── CocoLabels.kt            COCO 80 类（含中文名）
└── ScreenUtils.kt           全屏物理尺寸获取

app/src/main/assets/         ← 把 yolov8n.onnx 放这里
tools/export_yolov8.py       模型导出脚本
```

---

## 四、已知限制

1. **Android 14+ 每次启动都要重新授权录屏**，系统限制，无法绕过。
2. **`FLAG_SECURE` 的内容抓不到**（银行 App、Netflix、部分密码框），会显示成黑块，这是系统安全机制。
3. 抓屏时系统会常驻一个「正在共享屏幕」的提示，无法隐藏。
4. 只支持 **float32 单输出** 的 YOLOv8 ONNX 模型；如果用了 `nms=True` 导出（内置 NMS），
   输出张量数量会变，需要改 `YoloDetector.decode()`。
5. **自己画的框也会被抓进画面**（MediaProjection 抓的是合成后的屏幕，包含悬浮层），
   理论上存在反馈回路。实际影响很小（框很细，输入又被缩到 640），
   但如果发现某个物体反复被“自激发”，把置信度阈值调高即可。
6. 推理默认跑 CPU（ORT 自带 XNNPACK）。想再快可以打开界面里的 NNAPI 开关
   （`YoloDetector` 里调 `SessionOptions.addNnapi()`），部分机型明显更快，部分反而更慢。
7. 悬浮窗在小球位置会吃掉触摸（这是刻意的，否则没法控制）。不想留就把球拖到边角。
8. 编译用 `compileSdk = 34` + AGP 8.5.2 + JDK 17 + Gradle 8.7。
   想上 API 35 记得把 AGP 一起升到 8.7 以上。

---

## 五、想改成摄像头识别？

现在的数据源是 `YoloPipeline` 里的 `ImageReader`。换成 CameraX 只需要：

1. `ImageAnalysis.Analyzer` 里拿到 `ImageProxy`；
2. 用同样的方式转成 `IntArray`（注意 `ImageProxy` 的 `rowStride`）；
3. 调用 `YoloDetector.detect(pixels, width, height)`；
4. 坐标映射改成「分析帧 → 预览 View」，而不是「采集帧 → 整屏」。

`YoloDetector` 和 `OverlayView` 完全不用改。
