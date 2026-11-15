# GazeTap Architecture

> Living document. Updated as phases are implemented and measured. Every module and design choice below must be explainable (interview-ready).

## Goals and constraints
- **Hands-free control:** eyes move cursor; facial expressions trigger actions system-wide.
- **On-device only:** no network, no camera data stored/sent. Local CSV export is explicit/user-initiated for offline tuning.
- **Real phone target:** no laptop webcam assumptions; Android-first.
- **Performance targets (to be measured, not assumed):** camera-to-cursor < 100 ms, ≥30 fps mid-range, gaze < 3°, fixation jitter < 5 px RMS, expression false triggers < 1/min, expression recall > 95% after per-user calibration, click success > 90% on ≥10 mm targets.

## Module overview (planned)

### 1. Camera (`camera/`)
- **CameraX** with front camera, ~640x480 for latency/throughput balance.
- **ImageAnalysis** with `STRATEGY_KEEP_ONLY_LATEST` to drop backpressure frames and minimize latency.
- Analysis runs off the main thread; only UI state posted to main thread.
- **Why:** CameraX handles lifecycle/device quirks; keep-only-latest bounds queueing latency vs `STRATEGY_BLOCK_PRODUCER`.

### 2. Landmarks & inference (`ml/`)
- **MediaPipe Tasks Vision FaceLandmarker**, `LIVE_STREAM` mode.
- Outputs: face landmarks (including iris 468/473), 52 blendshapes, facial transformation matrix (for head pose).
- **GPU delegate with CPU fallback.** 
- **Why:** single on-device model provides landmarks + expressions + pose; LIVE_STREAM gives timestamped async results suited for real-time cursor.

### 3. Feature extraction (`gaze/features/`)
- Iris centers (468, 473) normalized inside eye boxes: corners 33/133 & 362/263, eyelids 159/145 & 386/374.
- Head yaw/pitch/roll from transformation matrix; inter-eye distance proxy for distance.
- Include `eyeLook*` blendshapes.
- Discard blink frames.
- **Why:** normalized gaze-in-eye features reduce head/scale sensitivity; combining with head pose enables compensation.

### 4. Gaze regression & calibration (`gaze/`)
- **9-point grid** (optional 13-point refinement) calibration: wait 500 ms, collect ~30 frames/point, outlier removal via median + MAD.
- **Ridge regression** on degree-2 polynomial features → screen (x,y); regularization by leave-one-point-out cross-validation.
- Weights stored as **JSON** (plain Kotlin regression, no heavy ML runtime dependency).
- Validation error reported in px, degrees, cm.
- Implicit recalibration nudges regression toward clicked known target.
- **Why:** simple, interpretable, fast on-device; LOOCV picks regularization without a separate val set.

### 5. Filtering & dynamics (`gaze/filter/`)
- **One Euro filter** (tunable `min_cutoff`, `beta`) for adaptive smoothing.
- **Saccade detection** to reduce lag on fast jumps while stabilizing fixations.
- **I-DT fixation detection** and dwell-click with shrinking ring (default 800 ms).
- **Why:** One Euro balances jitter vs latency; I-DT separates fixations from saccades for dwell.

### 6. Expression engine (`expression/`)
- Per-user calibration: neutral, each expression 3 s, thresholds ~60% between neutral and peak.
- **Hysteresis** (on > off), **minimum hold** (150–300 ms), **cooldown** (500 ms).
- Ignore natural blinks < 400 ms; prefer winks over blinks.
- Default map: smile=tap, brow raise=scroll up, jaw open=scroll down, cheek puff=long press/drag, left wink=back, right wink=home, tongue out=pause/resume. Remappable.
- Optional on-device **logistic regression** over 52 blendshapes (~20 samples/gesture) vs threshold method.
- **Why:** hysteresis/hold/cooldown suppress chatter; calibration adapts to user anatomy and expression range.

### 7. System control (`service/`, `accessibility/`)
- **ForegroundService** (`foregroundServiceType=camera`) hosts camera+engines; ~15 fps idle/no-face; pause when screen off.
- **AccessibilityService** (`canPerformGestures`, `canRetrieveWindowContent`): cursor via non-touchable `TYPE_ACCESSIBILITY_OVERLAY`; gestures via `dispatchGesture`; global actions via `performGlobalAction`.
- Snap-to-target via accessibility node tree (nearest clickable within radius), fallback to raw coords.
- Grid/zoom for small targets; big-key keyboard overlay with word prediction.
- **Why:** Accessibility APIs are the sanctioned path for system-wide gestures without root; overlay must not consume touches.

### 8. Instrumentation & offline analysis (`analysis/`, debug UI)
- Debug screen shows preview, 52 blendshape bars, head pose, FPS, per-stage latency (capture/landmarks/features/regression/filter).
- Session recording exports landmarks, blendshapes, timestamps to CSV.
- Evaluation mode logs px/degree error, jitter RMS, latency p50/p95; `/analysis` scripts summarize.
- **Why:** enables tuning/verification without a webcam and with honest measured reporting.

## Data & privacy
- No network access for camera/ML pipeline. No camera frames stored. Exported CSVs contain derived signals (landmarks/blendshapes/timestamps) saved locally at user request.

## Known limitations (to be validated)
- Requires phone stand; ~2–4° practical accuracy ceiling; some apps block/limit accessibility trees; glasses/glare may degrade tracking.

## Measurement log
*(To be filled with real device measurements per phase: device model, FPS, latency p50/p95, gaze error px/°/cm, jitter RMS, false triggers/min, recall, click success.)*