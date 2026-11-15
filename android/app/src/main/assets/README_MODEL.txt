REQUIRED MODEL (on-device, no network at runtime)
=================================================

Place the MediaPipe Face Landmarker task model in this folder with the exact name:

  face_landmarker.task

Download (official MediaPipe model bundle, with blendshapes/facial transformation support):
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task

Steps:
1. Download the .task file on your PC.
2. Copy it to: android/app/src/main/assets/face_landmarker.task
3. Rebuild/reinstall the app.

The model file is not committed in this workspace by default; you must add it locally before running.
No camera data is stored or sent anywhere by the app. This model runs fully on-device.
