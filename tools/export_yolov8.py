#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Ultralytics YOLOv8 权重导出成 Android 端用的 ONNX 模型，
并自动复制到 app/src/main/assets/ 。

为什么用 ONNX 而不是 TFLite：
    ultralytics 从 8.4.83 起废弃了 format="tflite"，内部强制改写为 format="litert"，
    而 LiteRT 导出链（litert_torch）对 torch / Python 版本极其敏感，
    在 CI 环境里经常直接报 `'_OpNamespace' 'aten' object has no attribute 'cholesky'`。
    ONNX 是 ultralytics 里依赖最少、最稳定的导出路径（export env = base），
    Android 侧用 onnxruntime-android（Maven 依赖，自带预编译 .so，不需要 NDK）。

用法：
    pip install -U ultralytics onnxruntime
    python tools/export_yolov8.py                     # 默认 yolov8n.pt, imgsz=640
    python tools/export_yolov8.py --model yolov8s.pt  # 换更大的模型
"""

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="yolov8n.pt", help="Ultralytics 权重，如 yolov8n.pt")
    parser.add_argument("--imgsz", type=int, default=640, help="输入尺寸，Android 端固定 640")
    parser.add_argument("--opset", type=int, default=None, help="ONNX opset，默认用 ultralytics 的推荐值")
    parser.add_argument("--no-simplify", action="store_true", help="关闭 onnxslim 图优化")
    args = parser.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.model)
    print(f"[1/3] 导出 {args.model}  format=onnx imgsz={args.imgsz}", flush=True)

    kwargs = dict(
        format="onnx",
        imgsz=args.imgsz,
        half=False,
        simplify=not args.no_simplify,
    )
    if args.opset:
        kwargs["opset"] = args.opset

    out_path = Path(model.export(**kwargs))
    print(f"      导出完成: {out_path}  ({out_path.stat().st_size:,} 字节)", flush=True)

    if out_path.suffix.lower() != ".onnx":
        print(f"[ERROR] 期望得到 .onnx，实际是 {out_path.suffix}", file=sys.stderr)
        sys.exit(1)

    print("[2/3] 复制到 assets", flush=True)
    ASSETS.mkdir(parents=True, exist_ok=True)

    # 清理旧的 tflite / onnx，避免多个模型文件一起被打进 APK
    for stale in list(ASSETS.glob("*.tflite")) + list(ASSETS.glob("*.onnx")):
        if stale.name != out_path.name:
            print(f"      删除旧模型 {stale.name}")
            stale.unlink()

    target = ASSETS / out_path.name
    shutil.copy2(out_path, target)
    print(f"      -> {target}  ({target.stat().st_size:,} 字节)")

    labels_path = ASSETS / "labels.txt"
    if not labels_path.exists():
        with labels_path.open("w", encoding="utf-8") as f:
            for name in model.names.values():
                f.write(f"{name}\n")
        print(f"      -> {labels_path}")

    names = sorted(p.name for p in ASSETS.iterdir())
    print(f"[3/3] assets/ 现在包含: {names}")
    print(f"\n提示：文件名必须与 OverlayService.DEFAULT_MODEL 一致（默认 {out_path.name}）")


if __name__ == "__main__":
    main()
