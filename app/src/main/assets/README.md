# 把模型放在这个目录

把导出的 `yolov8n_float32.tflite` 放到 **本目录**（`app/src/main/assets/`），
路径必须正好是：

```
app/src/main/assets/yolov8n_float32.tflite
```

导出方式见项目根目录 `tools/export_yolov8.py`，或者直接：

```bash
pip install -U ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=640 half=False int8=False
```

导出的文件在 `yolov8n_saved_model/yolov8n_float32.tflite`，
复制过来后重新 Build & Run 即可。

> 注意：这里只支持 **float32** 的 TFLite 导出。
> `int8` / `float16` 的量化模型输入输出类型不同，需要改 `YoloDetector` 的预处理与解码。
