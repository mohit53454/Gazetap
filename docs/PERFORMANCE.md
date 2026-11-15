# Latency budget and the speed of play

## Currently implemented (this build)

- **Flick input**: a fast gaze shift (saccade) becomes a discrete LEFT / RIGHT / UP / DOWN command
  (`gaze/FlickDetector.kt`). It runs on the **raw** gaze, not the smoothed cursor, because smoothing
  exists to reduce cursor jitter and deliberately adds lag.
- **Swipes/taps are really sent** through `GazeAccessibilityService` (`dispatchGesture`), so a flick
  reaches whatever app is in front — Subway Surfers, Chrome, YouTube.
- Detection rule: displacement ≥ 100 px from the oldest sample within 170 ms, implied speed
  ≥ 700 px/s, dominant axis wins; then latched for 250 ms and re-armed only after the eye settles.
- Dwell tap (900 ms hold) dispatches a real tap at the gaze point, for browsing.

Unit tests: 9 for the flick detector (directions, dominant axis, drift rejection, small-move
rejection, no repeat while moving, re-arm after settling, reset, NaN input). All 33 tests pass.

## Low-res fast mode (implemented in this build)

The **Low-res** button switches the analysis path at runtime:

| | Full-res mode | Low-res mode |
| --- | --- | --- |
| Analysis resolution | 640×480 | **320×240** |
| Blendshapes | on (52 outputs) | **off** |
| Facial transformation matrix | on (head pose) | **off** |
| Mirror pass | removed in both modes | removed |

Switching rebuilds the camera binding and the landmarker, because both resolution and model options
are fixed at construction. The UI prints a live comparison:

```
Compare - accurate: 14.2 fps / 61 ms | low-res: 26.8 fps / 24 ms
```

**Calibration is bound to the resolution it was made at.** The model file now stores the analysis
size it was fitted at (`gaze_model.json.meta`); if you switch modes and the running frame size no
longer matches, gaze and flicks are switched off with
`calibrated at 640×480 but running at 320×240 - recalibrate`. So: pick a mode, calibrate, play.

### How to compare on your phone
1. Install the APK, let it settle, read the `accurate:` half of the Compare line (10 s is plenty).
2. Tap **Low-res**, wait for the restart, read the `low-res:` half.
3. Whichever numbers you get, note that Low-res also removes the head-pose readout (it needs the
   transformation matrix) and the blendshape bars.

## Where the milliseconds go

| Stage | Cost today | Can it be cut? |
| --- | --- | --- |
| Camera exposure + readout @30 fps | 16–33 ms | No (hardware) |
| Bitmap copy + rotate | ~5–12 ms | Mirror pass removed in this build |
| FaceLandmarker inference @640×480 | **58–63 ms** (measured in your CSV) | Low-res mode targets ~20–25 ms |
| Feature extract + ridge predict | <1 ms | Already trivial |
| One Euro smoothing | 0 for flicks (raw path) | Already bypassed |
| `dispatchGesture` → app | 10–50 ms | No |
| One more camera frame before the app reacts | ~33 ms | No |

**Rough end-to-end now: 120–160 ms**, against the 100 ms target.

## The three changes that actually matter, in payoff order

1. **Analyse at 320×240 instead of 640×480.** Inference cost is roughly proportional to pixels, so
   this is a ~4× reduction — the difference between ~60 ms and ~20–25 ms. It costs iris precision,
   so it must be calibrated at the same resolution it is played at.
2. **Stop computing what play does not need.** Blendshapes (52 sigmoid outputs) and the facial
   transformation matrix are per-frame work. Turning both off for play saves a few ms each.
3. **Drop the mirror transform.** Gaze features are relative offsets inside each eye, so a mirrored
   frame changes nothing mathematically; removing it deletes a whole bitmap pass per frame.

Together these should land around **50–80 ms camera-to-swipe**. That is a projection, not a
measurement — the app reports FPS and per-stage latency, so it can be confirmed on your phone.

## Can Subway Surfers be played smoothly with this?

Honest answer: **playable at a casual level, not smooth in the competitive sense.**

Why it can work at all: Subway Surfers' entire input vocabulary is four discrete swipes, which is
exactly what a flick produces. Obstacles are visible roughly a second ahead, and a human reaction is
~250 ms anyway, so 50–80 ms of machine latency fits inside the normal reaction budget.

Why it will not feel smooth:

- **Occlusion.** Eyes must stay visible; the moment your hand, hair or the pillow blocks the camera,
  input stops. Nothing can fix this except a better mounting position.
- **False triggers.** Reading the screen's lane changes is a saccade too. Expect accidental swipes
  until the displacement/speed thresholds are tuned on your face — the status line prints every
  flick's speed in px/s so they can be tuned from real runs.
- **No multi-touch.** Jump-while-rolling style inputs cannot be produced.
- **Frame gaps.** At 30 fps a fast obstacle can appear mid-swipe; the game will read that as a late
  input.
- **Calibration drift.** Move your head 20° and the mapping degrades; recalibration is a 20-second job.

**Realistic expectation:** short runs, misses caused by the pipeline rather than the game. Browsing
is far easier and will feel much better — dwell taps and flick scrolls are forgiving of 100 ms.

## Tuning knobs (already exposed in the code)

| Knob | Where | Effect |
| --- | --- | --- |
| `minDisplacementPx` (100) | `FlickDetector` | raise to stop accidental swipes, lower to make flicks easier |
| `minSpeedPxPerSec` (700) | `FlickDetector` | raise to require a faster saccade |
| `cooldownMs` (250) | `FlickDetector` | raise if one flick produces two swipes |
| `dwellMs` (900) / `releaseRadiusPx` (70) | `DwellClicker` | tap speed vs accidental taps |
| `SWIPE_DURATION_MS` (120) | `GazeAccessibilityService` | shorter = snappier but may be read as a tap |
