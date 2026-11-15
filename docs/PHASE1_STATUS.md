# Phase 1 status: Camera, landmarks, debug screen

## Toolchain bootstrapped on this PC (done)
Because Android Studio was not installed, the command-line SDK was installed directly:

- SDK root: `C:\Users\mohit\AppData\Local\Android\Sdk`
  - `cmdline-tools/latest` (sdkmanager)
  - `platform-tools` (**adb** present)
  - `platforms/android-35`
  - `build-tools/35.0.0`
- Licenses accepted via `sdkmanager --licenses`.
- `android/local.properties` written with `sdk.dir=C:/Users/mohit/AppData/Local/Android/Sdk` (forward slashes; backslash-escaped form broke the build with "filename, directory name, or volume label syntax is incorrect").
- Gradle wrapper added to the repo (`gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, distribution **8.11.1**).
  - Note: the wrapper's default 10 s `networkTimeout` failed on this network, so builds here used a locally unpacked Gradle 8.11.1. Bump `networkTimeout` in `gradle/wrapper/gradle-wrapper.properties` if the wrapper download stalls for you.
- Face Landmarker model downloaded to `android/app/src/main/assets/face_landmarker.task` (**3,758,596 bytes**) and confirmed inside the APK.

## Verified in this workspace (real, measured here)
| Check | Command | Result |
| --- | --- | --- |
| Kotlin/Android compile | `gradle :app:assembleDebug` | **BUILD SUCCESSFUL** |
| Debug APK produced | — | `android/app/build/outputs/apk/debug/app-debug.apk`, **54 MB** |
| Model bundled in APK | `unzip -l ...apk \| grep face_landmarker.task` | `assets/face_landmarker.task`, 3,758,596 bytes |
| JVM unit tests | `gradle :app:testDebugUnitTest` | **15 tests, 0 failures, 0 errors** |

Unit test breakdown (JVM, synthetic data — **not** a substitute for on-device measurement):
- `OneEuroFilterTest`: 4 tests (jitter reduction, saccade lag, 2D independence, saccade detector)
- `FixationDwellTest`: 5 tests (I-DT fixation/saccade rejection, dwell latch, restart, progress monotonicity)
- `RidgePolynomialModelTest`: 6 tests (dense fit, 9-point residual, LOOCV, collinear safety, JSON round-trip, linear solve)

### Bugs these tests caught (and the fixes)
1. **Dwell re-fired a click every ~800 ms while the user kept looking.** Fixed by latching after a click until the gaze leaves the release radius — otherwise holding gaze on one spot spams taps.
2. **Ridge λ grid was too coarse.** On noise-free 49-point synthetic data, λ=0.01 left a 10.3 px residual while λ=0.001 gave 0.46 px. The default grid now starts at 1e-4. (The solver itself was correct — a Python reimplementation reproduced the same 10.3493 px number.)

## Implemented in repo
- CameraX front camera; `ImageAnalysis` with `ResolutionSelector` (4:3, ~640x480, `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`) and `STRATEGY_KEEP_ONLY_LATEST`; analyzer on a single background executor.
- MediaPipe `FaceLandmarker` in `LIVE_STREAM` with blendshapes + facial transformation matrices; GPU delegate requested with automatic CPU fallback.
- Debug screen: preview, 52 blendshape bars, head pose (yaw/pitch/roll), FPS, per-stage latency (capture/landmarks/features/regression/filter + total p50/p95) at ~15 Hz UI refresh.
- **Record session** → CSV (timestamp, inference ms, image size, pose, blendshapes, landmarks) written to the phone's **Download/GazeTap** folder via MediaStore (Android 10+ needs no storage permission; Android 8–9 asks once for `WRITE_EXTERNAL_STORAGE`). The file is shareable straight from any file manager.
- Phase 2 math core, unit-tested here but **not yet wired into the UI**: `OneEuroFilter`/`OneEuroFilter2D`, `SaccadeDetector`, `IdtFixationDetector`, `DwellClicker`, `RidgePolynomialModel` (poly-2 ridge with LOOCV λ selection and JSON weights).

## Still NOT measured (needs your phone)
`adb devices` lists **no device** on this machine, so none of the performance targets could be measured. These remain open:

| Metric | Target | Measured |
| --- | --- | --- |
| FPS | ≥30 stable | **NOT MEASURED** |
| Camera-to-cursor latency | <100 ms | **NOT MEASURED** (no cursor yet) |
| Stage timings p50/p95 | visible | **NOT MEASURED** |
| Gaze error | <3° | **NOT MEASURED** |
| Jitter at fixation | <5 px RMS | **NOT MEASURED** |
| Expression false triggers | <1/min | **NOT MEASURED** |
| Expression recall | >95% | **NOT MEASURED** |
| Click success (≥10 mm) | >90% | **NOT MEASURED** |

## Install on the phone (when connected)
```
adb devices                     # expect your serial, state "device"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```
Then launch **GazeTap**, grant Camera, keep the phone on a stand, and read FPS + stage latencies off the debug screen. Tap **Record session**, then pull the CSV:
```
adb pull /sdcard/Download/GazeTap/ ./sessions/   # or just share the CSV from the phone
python analysis/summarize_session.py ./sessions/
```
