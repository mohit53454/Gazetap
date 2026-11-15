"""analyse a GazeTap session CSV for iris-tracking signal.

Recomputes the exact four gaze features used by the app (GazeFeatures.kt) from the recorded
landmarks, so we can tell whether the eyes actually moved while the session was recorded.

Usage:
    python analysis/analyze_features.py <session.csv> [--bins 10]
"""
import csv
import math
import sys
from collections import Counter

# MediaPipe Face Mesh indices (must match android .../gaze/GazeFeatures.kt)
RIGHT_OUTER, RIGHT_INNER, RIGHT_TOP, RIGHT_BOTTOM, RIGHT_IRIS = 33, 133, 159, 145, 468
LEFT_INNER, LEFT_OUTER, LEFT_TOP, LEFT_BOTTOM, LEFT_IRIS = 362, 263, 386, 374, 473
MIN_LANDMARKS = 478


def parse_landmarks(field):
    pts = []
    for chunk in field.split('|'):
        parts = chunk.split(',')
        if len(parts) != 3:
            continue
        try:
            pts.append(tuple(float(p) for p in parts))
        except ValueError:
            continue
    return pts


def eye_features(pts, outer, inner, top, bottom, iris):
    cx = (pts[outer][0] + pts[inner][0]) / 2.0
    cy = (pts[outer][1] + pts[inner][1]) / 2.0
    w = abs(pts[outer][0] - pts[inner][0])
    h = abs(pts[top][1] - pts[bottom][1])
    if w < 1e-5 or h < 1e-5:
        return None
    return ((pts[iris][0] - cx) / w, (pts[iris][1] - cy) / h)


def pct(values, p):
    if not values:
        return float('nan')
    s = sorted(values)
    idx = min(len(s) - 1, max(0, int((len(s) - 1) * p)))
    return s[idx]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    rows = []
    bad = 0
    with open(path, newline='', encoding='utf-8', errors='replace') as f:
        for row in csv.DictReader(f):
            try:
                lm_count = int(row['landmark_count'])
            except (KeyError, ValueError):
                bad += 1
                continue
            if lm_count < MIN_LANDMARKS:
                bad += 1
                continue
            pts = parse_landmarks(row['landmarks'])
            if len(pts) < MIN_LANDMARKS:
                bad += 1
                continue
            right = eye_features(pts, RIGHT_OUTER, RIGHT_INNER, RIGHT_TOP, RIGHT_BOTTOM, RIGHT_IRIS)
            left = eye_features(pts, LEFT_INNER, LEFT_OUTER, LEFT_TOP, LEFT_BOTTOM, LEFT_IRIS)
            if right is None or left is None:
                bad += 1
                continue
            rows.append({
                't': int(row['timestamp_ms']),
                'inf': int(row['inference_ms']),
                'img': (int(row['image_w']), int(row['image_h'])),
                'yaw': float(row['yaw']), 'pitch': float(row['pitch']), 'roll': float(row['roll']),
                'r': right, 'l': left,
            })

    if not rows:
        print(f'no usable rows ({bad} rejected)')
        return 1

    dur = (rows[-1]['t'] - rows[0]['t']) / 1000.0
    dt = [(rows[i]['t'] - rows[i - 1]['t']) / 1000.0 for i in range(1, len(rows))]
    inf = [r['inf'] for r in rows]

    print(f'file            : {path}')
    print(f'frames          : {len(rows)}  (rejected {bad})')
    print(f'duration        : {dur:.1f} s   -> mean fps {len(rows) / dur if dur else 0:.1f}')
    print(f'inference ms    : mean {sum(inf) / len(inf):.1f}  p50 {pct(inf, .5):.0f}  p95 {pct(inf, .95):.0f}')
    print(f'image size      : {Counter(r["img"] for r in rows).most_common(1)[0][0]}')
    print(f'frame dt s      : p50 {pct(dt, .5):.3f}  p95 {pct(dt, .95):.3f}')

    names = ['right_x', 'right_y', 'left_x', 'left_y']
    series = [
        [r['r'][0] for r in rows], [r['r'][1] for r in rows],
        [r['l'][0] for r in rows], [r['l'][1] for r in rows],
    ]
    print('\nper-feature stats (iris offset inside the eye, normalised):')
    print(f'{"feature":10} {"min":>8} {"max":>8} {"range":>7} {"std":>7}')
    overall = 0.0
    for name, vals in zip(names, series):
        lo, hi = min(vals), max(vals)
        mean = sum(vals) / len(vals)
        std = math.sqrt(sum((v - mean) ** 2 for v in vals) / len(vals))
        overall = max(overall, hi - lo)
        print(f'{name:10} {lo:8.3f} {hi:8.3f} {hi - lo:7.3f} {std:7.3f}')

    print(f'\nlargest range   : {overall:.3f}')
    if overall < 0.05:
        print('VERDICT: the iris features barely moved -> iris landmarks are NOT carrying gaze.')
    elif overall < 0.15:
        print('VERDICT: some signal, but weak for 16 calibration points looks.')
    else:
        print('VERDICT: clear iris movement -> features carry gaze; accuracy is a model/tuning problem.')

    yaws = [r['yaw'] for r in rows]
    pitches = [r['pitch'] for r in rows]
    print(f'head yaw range  : {max(yaws) - min(yaws):.1f} deg   pitch range: {max(pitches) - min(pitches):.1f} deg')
    return 0


if __name__ == '__main__':
    sys.exit(main())
