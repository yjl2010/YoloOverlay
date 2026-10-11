# 把模型放在这个目录

把导出的 `yolov8n.onnx` 放到 **本目录**（`app/src/main/assets/`），
路径必须正好是：

```
app/src/main/assets/yolov8n.onnx
```

导出方式见项目根目录 `tools/export_yolov8.py`，或者直接：

```bash
pip install -U ultralytics onnxruntime
yolo export model=yolov8n.pt format=onnx imgsz=640 half=False
```

导出的文件在当前目录，复制过来后重新 Build & Run 即可。

## 为什么不用 TFLite

ultralytics 从 **8.4.83** 开始废弃了 `format="tflite"`，源码里直接写：

```python
if fmt in {"tflite", "tfjs"}:
    LOGGER.warning(f"format='{fmt}' is deprecated as of 8.4.83 ...")
    fmt = self.args.format = "litert"
```

而 LiteRT 导出链（`litert_torch`）对 torch / Python 版本极其敏感，
在 CI 环境里经常直接报：

```
ERROR ❌ LiteRT: export failure: '_OpNamespace' 'aten' object has no attribute 'cholesky'
```

ONNX 是 ultralytics 里依赖最少、最稳定的导出路径（export env = `base`，
不需要 TensorFlow、不需要 onnx2tf），Android 侧用 `onnxruntime-android`
（Maven 依赖，自带全部 ABI 的预编译 `.so`，同样不需要 NDK）。

## 模型要求

- 输入张量名任意（代码运行时读取 `session.inputNames`）
- 输入形状必须是 `[1, 640, 640, 3]`，类型 `float32`，RGB，归一化到 0..1
- 输出必须是**单个**张量，含一个维度 = 84（4 坐标 + 80 类）
  - `[1, 84, 8400]` 或 `[1, 8400, 84]` 都能自动识别
- **不要**加 `nms=True` 导出，否则输出会变成多个张量，解码逻辑要重写

构建日志里的 `Verify ONNX model shapes` 步骤会把这些形状打出来，照着核对就行。
