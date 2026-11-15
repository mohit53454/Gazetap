# Phase 2 status: gaze cursor, calibration, dwell click

## Implemented (builds and unit-tested here)

| Piece | File | What it does |
| --- | --- | --- |
| Feature extraction | `gaze/GazeFeatures.kt` | 4 features: iris offset inside each eye socket, normalised by that eye's own width/height so leaning in/out does not change them |
| Regression | `gaze/RidgePolynomialModel.kt` | degree-2 ridge, λ chosen by leave-one-point-out CV, JSON weights |
| Smoothing | `gaze/OneEuroFilter.kt` | adaptive low-pass (steady when fixating, low lag on saccades), plus `SaccadeDetector` |
| Fixation | `gaze/FixationDwell.kt` | I-DT fixation detector + dwell click that latches until the gaze leaves |
| Calibration | `gaze/CalibrationSession.kt` | 9-point grid, 8 settle frames discarded per point, median of 20 samples |
| Persistence | `gaze/GazeModelStore.kt` | `gaze_model.json` in app-private storage |
| Overlay | `GazeOverlayView.kt` | calibration target with progress ring, cyan gaze cursor with dwell ring |
| Wiring | `MainActivity.kt` | **Calibrate** / **Gaze on** buttons, real regression+filter timings fed into the latency readout |

## How to use it on the phone

1. Install the APK, open GazeTap, grant camera.
2. Hold the phone steady (stand or propped up) roughly 40–60 cm away, face visible.
3. Tap **Calibrate**. Nine red dots appear in turn — look at each dot and keep your head still until the yellow ring completes.
4. On the last dot the app fits the model, saves it, and reports the **mean residual in px**.
5. Tap **Gaze on**. A cyan cursor follows your gaze; holding still for 900 ms fills the ring and fires a dwell click (counter + coordinates in the status line).

## Verified here

- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL**, APK 55 MB.
- `gradle :app:testDebugUnitTest` → **24 tests, 0 failures** (features: centring, eye-size normalisation, scale invariance, rejection of iris-less/degenerate meshes; calibration: 9-point completion, settle-frame rejection, residual; plus the filter/fixation/ridge suites).

## NOT measured (needs your run)

| Metric | Target | Measured |
| --- | --- | --- |
| Gaze accuracy on screen | <3° | **NOT MEASURED** — read the residual the app prints after calibration |
| Jitter at fixation | <5 px RMS | **NOT MEASURED** |
| Camera→cursor latency | <100 ms | **NOT MEASURED** — see the reg/filt columns |
| Dwell click success on ≥10 mm targets | >90% | **NOT MEASURED** |
| False dwell clicks per minute | low | **NOT MEASURED** |

The dwell click currently only counts and reports itself. **Sending an actual tap into other apps is Phase 4** (AccessibilityService) and is not implemented yet.
