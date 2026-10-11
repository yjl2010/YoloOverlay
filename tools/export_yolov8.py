#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Ultralytics YOLOv8 权重导出成 Android 端用的 TFLite float32 模型，
并自动复制到 app/src/main/assets/ 。

用法：
    pip install -U ultralytics
    python tools/export_yolov8.py                 # 默认 yolov8n.pt, imgsz=640
    python tools/export_yolov8.py --model yolov8s.pt --imgsz 640
    python tools/export_yolov8.py --nms           # 导出自带 NMS 的模型（需要改 Kotlin 解码）

说明：
    - 只导出 float32，Android 端 YoloDetector 是按 float32 + NHWC 写的。
    - 导出产物中的 yolov8n_float32.tflite 会被复制到 app/src/main/assets/。
"""

import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="yolov8n.pt", help="Ultralytics 权重，如 yolov8n.pt")
    parser.add_argument("--imgsz", type=int, default=640, help="输入尺寸，Android 端固定 640")
    parser.add_argument("--nms", action="store_true", help="导出内置 NMS 的模型（可选）")
    parser.add_argument("--int8", action="store_true", help="导出 int8（Android 端需自行改代码）")
    args = parser.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.model)
    print(f"[1/3] 导出 {args.model}  format=tflite imgsz={args.imgsz}")

    out_path = model.export(
        format="tflite",
        imgsz=args.imgsz,
        half=False,
        int8=args.int8,
        nms=args.nms,
    )
    out_path = Path(out_path)
    print(f"      导出完成: {out_path}")

    print("[2/3] 复制到 assets")
    ASSETS.mkdir(parents=True, exist_ok=True)
    target = ASSETS / out_path.name
    shutil.copy2(out_path, target)
    print(f"      -> {target}")

    # 顺便导出标签，方便以后换数据集
    labels_path = ASSETS / "labels.txt"
    if not labels_path.exists():
        with labels_path.open("w", encoding="utf-8") as f:
            for name in model.names.values():
                f.write(f"{name}\n")
        print(f"      -> {labels_path}")

    print("[3/3] 完成。检查 app/src/main/assets 下是否有")
    print(f"      {out_path.name}")
    print("\n注意：文件名必须与 OverlayService.DEFAULT_MODEL 一致（默认 yolov8n_float32.tflite），")
    print("      如果不是，请在 App 内通过 SharedPreferences 的 model_asset 指定。")


if __name__ == "__main__":
    main()
