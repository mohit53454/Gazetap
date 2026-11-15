#!/usr/bin/env python3
"""
Summarize GazeTap session CSV exports (Phase 1 debug recordings).

Usage:
  python summarize_session.py path/to/session_*.csv
  python summarize_session.py path/to/folder_with_csvs

Reports (from recorded fields): frame count, duration, approx FPS, inference/landmark timing
stats (mean, p50, p95) when available, face-present ratio, and blendshape/pose sanity checks.
No camera data is transmitted; works on local CSVs only.
"""
from __future__ import annotations

import csv
import glob
import math
import os
import statistics
import sys
from typing import List


def percentile(sorted_vals: List[float], p: float) -> float:
    if not sorted_vals:
        return float("nan")
    idx = int((len(sorted_vals) - 1) * p)
    idx = max(0, min(idx, len(sorted_vals) - 1))
    return sorted_vals[idx]


def summarize_file(path: str) -> None:
    timestamps = []
    inference_ms = []
    face_present = 0
    total = 0
    yaw_vals, pitch_vals, roll_vals = [], [], []
    with open(path, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            total += 1
            try:
                ts = float(row.get("timestamp_ms", "nan"))
                if math.isfinite(ts):
                    timestamps.append(ts)
            except Exception:
                pass
            try:
                inf = float(row.get("inference_ms", "nan"))
                if math.isfinite(inf):
                    inference_ms.append(inf)
            except Exception:
                pass
            try:
                lm_count = int(float(row.get("landmark_count", "0")))
                if lm_count > 0:
                    face_present += 1
            except Exception:
                pass
            for key, arr in (("yaw", yaw_vals), ("pitch", pitch_vals), ("roll", roll_vals)):
                try:
                    v = float(row.get(key, "nan"))
                    if math.isfinite(v):
                        arr.append(v)
                except Exception:
                    pass

    print(f"\n=== {os.path.basename(path)} ===")
    print(f"Frames: {total}")
    if total == 0:
        print("No frames recorded.")
        return
    if len(timestamps) >= 2:
        duration_s = (max(timestamps) - min(timestamps)) / 1000.0
        fps = (len(timestamps) - 1) / duration_s if duration_s > 0 else float("nan")
        print(f"Duration (from timestamps): {duration_s:.2f} s | Approx FPS: {fps:.2f}")
    else:
        print("Duration/FPS: insufficient timestamp data")
    print(f"Face present frames: {face_present}/{total} ({(100.0*face_present/total):.1f}%)")

    if inference_ms:
        s = sorted(inference_ms)
        print(
            "Inference ms (MediaPipe reported): "
            f"mean={statistics.mean(s):.2f} p50={percentile(s,0.5):.2f} p95={percentile(s,0.95):.2f} "
            f"min={s[0]:.2f} max={s[-1]:.2f}"
        )
    else:
        print("Inference ms: n/a")

    def pose_stats(name, vals):
        if not vals:
            return f"{name}=n/a"
        s = sorted(vals)
        return f"{name} mean={statistics.mean(s):.2f} p50={percentile(s,0.5):.2f} p95={percentile(s,0.95):.2f} (deg)"

    print("Head pose (from matrix, verify on device): " + ", ".join([
        pose_stats("yaw", yaw_vals),
        pose_stats("pitch", pitch_vals),
        pose_stats("roll", roll_vals),
    ]))
    print("Note: end-to-end camera-to-cursor latency, gaze error, jitter, false triggers, recall,")
    print("and click success are NOT derived from this Phase 1 CSV; measure with Phase 2+ eval modes on device.")


def collect_csvs(arg: str) -> List[str]:
    if os.path.isdir(arg):
        return sorted(glob.glob(os.path.join(arg, "*.csv")))
    if any(ch in arg for ch in "*?[]"):
        return sorted(glob.glob(arg))
    return [arg]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    paths: List[str] = []
    for a in sys.argv[1:]:
        paths.extend(collect_csvs(a))
    if not paths:
        print("No CSV files found.")
        return 1
    for p in paths:
        if os.path.isfile(p):
            summarize_file(p)
        else:
            print(f"Skipping missing file: {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
