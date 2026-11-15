# GazeTap

Hands-free Android app: eyes move an on-screen cursor and facial expressions trigger taps, scrolls and other actions in any app. **Everything runs on-device with no network.**

## Repository structure

```
android/    Android app (Kotlin, CameraX, MediaPipe Tasks Vision FaceLandmarker)
analysis/   Python scripts for offline analysis of CSVs exported from the phone
docs/       Architecture and per-phase status
```

## Status (honest)

| Phase | Code | Verified in this workspace | Verified on device |
| --- | --- | --- | --- |
| 1. Camera, landmarks, debug screen | Implemented | **Compiles; APK built (54 MB); model bundled** | **Not yet** — no phone attached |
| 2. Gaze | Calibration, cursor, dwell click, model persistence | **39 JVM unit tests pass; app builds** | **Not yet** — needs your calibration run |
| 3. Expressions | Not started (blendshapes already recorded for tuning) | — | — |
| 4. System-wide control | Gaze flicks → real swipes; dwell → real taps via AccessibilityService | **Builds; 9 flick tests pass** | **Not yet** — needs accessibility enabled + your run |
| 5. Proof & packaging | In progress (debug APK) | APK built | — |

**No performance numbers (FPS, latency, gaze error, jitter, false triggers, recall, click success) are claimed.** `adb devices` shows no device on the build machine, so nothing could be measured on hardware.

## What was actually verified here

| Check | Result |
| --- | --- |
| `gradle :app:assembleDebug` | **BUILD SUCCESSFUL** |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` (54 MB) |
| Model inside APK | `assets/face_landmarker.task` (3,758,596 bytes) |
| `gradle :app:testDebugUnitTest` | **39 tests, 0 failures, 0 errors** |

Two real bugs were found by those tests and fixed: dwell clicking repeated every ~800 ms while the user kept looking (now latched until gaze leaves), and the ridge λ grid was too coarse (λ=0.01 left 10.3 px on synthetic data vs 0.46 px at λ=0.001).

## Environment on this PC (already set up)

- Android SDK installed at `C:\Users\mohit\AppData\Local\Android\Sdk`: `cmdline-tools/latest`, `platform-tools` (adb), `platforms/android-35`, `build-tools/35.0.0`.
- `android/local.properties` points at that SDK (forward slashes).
- Gradle wrapper 8.11.1 committed in `android/`.
- `android/app/src/main/assets/face_landmarker.task` downloaded from the official MediaPipe model URL.
- Java 21 available; Python 3.11 available.

You still need **Android Studio** for the IDE/debugger if you want it (optional — `adb` + Gradle already work from the command line), and a **physical phone** with USB debugging enabled.

## Build and install (command line)

```bash
cd android
./gradlew :app:assembleDebug        # on Windows: gradlew.bat
adb devices                          # confirm your phone shows as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the Gradle wrapper download stalls, raise `networkTimeout` in `android/gradle/wrapper/gradle-wrapper.properties` (the default 10 s was too short on this network).

### Phone setup
1. **Settings → About phone** → tap **Build number** 7 times.
2. **Settings → System → Developer options** → enable **USB debugging** (and *Stay awake*).
3. Connect via a data-capable USB cable; accept the **Allow USB debugging** prompt.
4. `adb devices` must show `device` (not `unauthorized`).
5. Launch **GazeTap** and grant **Camera**.
6. Android 13+ sideload quirk: if a later phase's Accessibility service is blocked, use **Settings → Apps → GazeTap → ⋮ → Allow restricted settings**.

## Phase 1 verification checklist (on your phone)
- [ ] Front camera preview visible.
- [ ] 52 blendshape bars respond to your face.
- [ ] Head pose yaw/pitch/roll respond correctly (validate sign conventions).
- [ ] FPS readout — record the actual value (target ≥30 stable).
- [ ] Stage latency numbers visible — record p50/p95.
- [ ] **Record session** writes a CSV into **Download/GazeTap** on the phone; share it directly, or pull it and run the analysis script.

```bash
adb pull /sdcard/Download/GazeTap/ ./sessions/
python analysis/summarize_session.py ./sessions/
```

## Privacy
No network access in the camera/ML pipeline. No camera frames are stored or transmitted. CSVs contain derived signals (landmarks, blendshapes, timestamps) and are written only when you press *Record session* — straight into the phone's **Download/GazeTap** folder so you can share the file from any file manager or messaging app.

See `docs/PHASE1_STATUS.md`, `docs/PHASE2_STATUS.md` and `docs/PERFORMANCE.md` for detailed status, and `docs/ARCHITECTURE.md` for design rationale.
