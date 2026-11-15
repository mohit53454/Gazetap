package com.gazetap.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.max
import android.util.Size
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.gazetap.app.databinding.ActivityMainBinding
import com.gazetap.app.gaze.CalibrationSession
import com.gazetap.app.gaze.DwellClicker
import com.gazetap.app.gaze.FaceMotionSwipeDetector
import com.gazetap.app.gaze.FlickDetector
import com.gazetap.app.gaze.GazeFeatures
import com.gazetap.app.gaze.GazeCursorSmoother
import com.gazetap.app.gaze.GazeModelStore
import com.gazetap.app.gaze.HeadSwipeDetector
import com.gazetap.app.gaze.RidgePolynomialModel
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {

    private companion object {
        /** How often the frame watchdog checks that the camera is still producing frames. */
        const val WATCHDOG_INTERVAL_MS = 2_000L

        /** Older than this and tracking is considered dead. */
        const val STALE_FRAME_MS = 3_000L

        /** Never restart the pipeline more often than this, so a broken camera cannot spin. */
        const val RESTART_THROTTLE_MS = 8_000L

        // Nose, bridge, forehead, chin/jaw, cheeks, eye corners, mouth - represent "total face" motion.
        private val FACE_CENTER_LANDMARKS = intArrayOf(
            1, 2, 4, 5, 6, 8, 9, 10, 151, 152, 168, 197, 195,
            33, 133, 160, 159, 158, 157, 173, 144, 145, 153,
            362, 263, 387, 386, 385, 384, 398, 373, 374, 380,
            61, 291, 0, 17, 84, 181, 314, 405, 321, 375,
            234, 454, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152,
            361, 288, 397, 365, 379, 378, 400, 377
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null

    private val metrics = MetricsTracker()
    private val recorder = SessionRecorder()
    private val blendshapeBars = mutableListOf<Pair<TextView, ProgressBar>>()

    // --- Phase 2: gaze ---
    private lateinit var gazeStore: GazeModelStore
    @Volatile private var gazeModel: RidgePolynomialModel? = null
    @Volatile private var calibration: CalibrationSession? = null
    @Volatile private var gazeEnabled = false
    // A quiet zone, not heavy averaging: below 18 px of movement the pointer does not move at all
    // (that is what kills the darting), above it the motion is essentially 1:1 with the eye, so it
    // still keeps up. The dot is additionally interpolated at display rate in GazeOverlayView.
    private val gazeFilter = GazeCursorSmoother(quietRadiusPx = 18.0, minCutoff = 1.2, beta = 0.8)
    // Holding still for 1.1 s is a deliberate click; 650 ms fired while the eye was still arriving.
    private val dwell = DwellClicker(dwellMs = 1100.0, releaseRadiusPx = 55.0)
    private val flickDetector = FlickDetector()
    // Primary swipe: whole face moves away from a neutral center (joystick-like), then returns to center.
    // v0.28.11-style logic + adaptive smoothing + early intent commit + hysteresis re-arm, tuned for fast response and chaining.
    private val faceMotionSwipeDetector = FaceMotionSwipeDetector(
        triggerPx = 115.0,
        earlyTriggerPx = 72.0,
        veryEarlyTriggerPx = 52.0,
        fastOutwardSpeedPxPerSec = 360.0,
        veryFastOutwardSpeedPxPerSec = 620.0,
        returnPx = 85.0,
        rearmPx = 42.0,
        settleSpeedPxPerSec = 210.0,
        settleMs = 50.0,
        cooldownMs = 100.0,
        minAxisDominance = 1.3,
        minEarlyAxisDominance = 1.45,
        lostCenterMs = 320.0,
    )
    // Head turns (full-face pose) as an additional gesture (tuned for faster, responsive swipes).
    private val headSwipeDetector = HeadSwipeDetector(
        yawThresholdDeg = 14.0,
        pitchThresholdDeg = 12.0,
        settleWindowMs = 220.0,
        settleMaxDeltaDeg = 5.0,
        maxTurnMs = 450.0,
        holdMs = 60.0,
        cooldownMs = 350.0,
    )

    /** Last three raw predictions, for a per-axis median that drops single-frame spikes. */
    private val rawWindow = ArrayDeque<DoubleArray>(4)
    @Volatile private var flicksEnabled = false
    private var flickCount = 0

    // --- performance modes ---
    @Volatile private var fastMode = false
    @Volatile private var lastFrameW = 0
    @Volatile private var lastFrameH = 0
    @Volatile private var storedModelInfo: GazeModelStore.Stored? = null
    @Volatile private var mismatchNotified = false
    // Written from the MediaPipe callback thread, read from the UI thread.
    private val modeFps = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val modeInferenceMs = java.util.concurrent.ConcurrentHashMap<String, Double>()

    // Live eye-feature diagnostics: proves whether iris movement is being captured at all.
    private val featMin = DoubleArray(GazeFeatures.COUNT) { Double.MAX_VALUE }
    private val featMax = DoubleArray(GazeFeatures.COUNT) { -Double.MAX_VALUE }
    @Volatile private var lastFeatures: DoubleArray? = null
    @Volatile private var lastLookFeatures: DoubleArray? = null
    private val lookMin = DoubleArray(GazeFeatures.COUNT) { Double.MAX_VALUE }
    private val lookMax = DoubleArray(GazeFeatures.COUNT) { -Double.MAX_VALUE }
    private val lmsX = FloatArray(GazeFeatures.MIN_LANDMARKS)
    private val lmsY = FloatArray(GazeFeatures.MIN_LANDMARKS)

    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    @Volatile private var dwellProgress = 0f
    @Volatile private var hasCursor = false
    @Volatile private var clickCount = 0
    // Sub-pixel jitter gate: a fixation must not make the cursor crawl.
    private var lastDrawnX = Float.NaN
    private var lastDrawnY = Float.NaN
    private val cursorDeadbandPx = 1.0f
    @Volatile private var swipeSentCount = 0
    @Volatile private var swipeRefusedCount = 0
    @Volatile private var lastSwipeDebugLogMs = 0L

    private val isRecording = AtomicBoolean(false)
    private var lastUiUpdateMs = 0L
    private var lastAccessibilityState = ""
    @Volatile private var lastPipelineErrorMs = 0L

    /** What the cursor draw path last asked for, so a hidden cursor is not re-sent every frame. */
    @Volatile private var lastSentVisible = false

    /** Camera use cases, kept so they can be re-bound with or without a preview surface. */
    @Volatile private var analysisUseCase: ImageAnalysis? = null
    @Volatile private var previewUseCase: Preview? = null

    /** Whether this activity's window is on screen. Drives headless vs. preview binding. */
    @Volatile private var activityVisible = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRestartMs = 0L

    // --- gesture bridge (:gestures process) ---
    // The accessibility service never shares a process with the camera. MediaPipe makes this
    // process large, the user swipes it away, and any crash in the frame pipeline used to destroy
    // the accessibility binding with it - which HyperOS reports as "GazeTap gestures: Not working".
    /** Set while the camera is being torn down, so a late frame is dropped instead of processed. */
    @Volatile private var cameraClosed = false

    /** When a frame last reached the pipeline. The heartbeat that proves tracking is really alive. */
    @Volatile private var lastFrameAtMs = 0L

    private var batteryAsked = false

    /** Camera lifecycle owner that outlives "another app is in front". */
    private val trackingOwner = TrackingOwner()

    /** The user asked for swipes but the gesture process had not answered yet. */
    @Volatile private var flickWanted = false

    @Volatile private var bridge: Messenger? = null
    @Volatile private var bridgeStatus: Bundle? = null
    @Volatile private var bridgeConnected = false
    @Volatile private var bridgeStatusRequestedAt = 0L
    private val replyMessenger: Messenger by lazy { Messenger(ReplyHandler(Looper.getMainLooper())) }

    private inner class ReplyHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            try {
                onBridgeReply(msg)
            } catch (t: Throwable) {
                Diagnostics.log(this@MainActivity, "A11Y-14", "reply handling threw: $t")
            }
        }
    }

    private val bridgeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge = binder?.let { Messenger(it) }
            bridgeConnected = bridge != null
            bridgeStatusRequestedAt = 0L
            requestStatus(force = true)
            Diagnostics.log(this@MainActivity, "A11Y-10", "connected to the :gestures process")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
            bridgeConnected = false
            bridgeStatus = null
            Diagnostics.log(
                this@MainActivity, "A11Y-11",
                "the :gestures process died; the system is asked to rebind it", severe = true,
            )
        }
    }

    /** True only when the accessibility service is bound in :gestures and can inject gestures. */
    private fun accessibilityUsable(): Boolean =
        bridgeStatus?.getBoolean(GazeBridgeService.KEY_BOUND) == true

    /**
     * True only when the floating cursor window is actually attached over other apps.
     *
     * Distinct from [accessibilityUsable] on purpose: the service can be bound while its overlay was
     * refused by the window manager, and in that case hiding this activity's own cursor left the user
     * with no cursor anywhere at all - "the cursor disappeared outside the app".
     */
    private fun remoteCursorOn(): Boolean =
        bridgeStatus?.getBoolean(GazeBridgeService.KEY_OVERLAY) == true

    private fun sendToService(what: Int, data: Bundle? = null, wantReply: Boolean = false): Boolean {
        val messenger = bridge ?: return false
        val msg = Message.obtain(null, what)
        if (data != null) msg.data = data
        if (wantReply) msg.replyTo = replyMessenger
        return try {
            messenger.send(msg)
            true
        } catch (t: Throwable) {
            // A dead binder means that process is gone: drop the handle instead of retrying it.
            bridge = null
            bridgeConnected = false
            Diagnostics.log(this, "A11Y-12", "command $what to :gestures failed: $t")
            false
        }
    }

    private fun sendCursor(nx: Float, ny: Float, progress: Float, visible: Boolean) {
        sendToService(
            GazeBridgeService.MSG_CURSOR,
            Bundle().apply {
                putFloat(GazeBridgeService.KEY_NX, nx)
                putFloat(GazeBridgeService.KEY_NY, ny)
                putFloat(GazeBridgeService.KEY_PROGRESS, progress)
                putBoolean(GazeBridgeService.KEY_VISIBLE, visible)
            },
        )
    }

    /** ~1 Hz is enough for a status line, and cheap enough to run forever. */
    private fun requestStatus(force: Boolean = false) {
        if (bridge == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - bridgeStatusRequestedAt < 1000L) return
        bridgeStatusRequestedAt = now
        sendToService(GazeBridgeService.MSG_STATUS, wantReply = true)
    }

    private fun onBridgeReply(msg: Message) {
        val data = msg.data ?: return
        when (msg.what) {
            GazeBridgeService.MSG_STATUS_REPLY -> {
                bridgeStatus = Bundle(data)
                safeText { binding.svcText.text = accessibilityHealthLine() }
                updateFlickButton()
                // The bind is asynchronous. A swipe request made a second too early used to stay
                // off for the whole session; the first status reply now completes it.
                if (flickWanted && !flicksEnabled && gazeModel != null && accessibilityUsable()) {
                    flickWanted = false
                    flicksEnabled = true
                    flickDetector.reset()
                    if (!gazeEnabled) {
                        gazeEnabled = true
                        gazeFilter.reset()
                        rawWindow.clear()
                        dwell.reset()
                        updateGazeButton()
                    }
                    refreshTrackingService()
                    updateFlickButton()
                    safeText { binding.statusText.text = getString(R.string.flicks_enabled) }
                    Diagnostics.log(this, "A11Y-08", "swipes enabled once the gesture service answered")
                }
            }

            GazeBridgeService.MSG_SELFTEST_REPLY ->
                finishSelfTest(data.getString(GazeBridgeService.KEY_RESULT) ?: "no result")

            GazeBridgeService.MSG_SWIPE_REPLY -> {
                val direction = data.getString(GazeBridgeService.KEY_DIR) ?: "?"
                if (data.getBoolean(GazeBridgeService.KEY_OK)) {
                    swipeSentCount++
                } else {
                    swipeRefusedCount++
                    val reason = data.getString(GazeBridgeService.KEY_ERROR) ?: "no reason given"
                    Diagnostics.log(this, "A11Y-05", "swipe $direction failed: $reason", severe = true)
                    safeText { binding.statusText.text = "Swipe $direction refused: $reason" }
                }
            }

            GazeBridgeService.MSG_TAP_REPLY -> if (!data.getBoolean(GazeBridgeService.KEY_OK)) {
                Diagnostics.log(
                    this, "A11Y-05",
                    "dwell tap failed: " + (data.getString(GazeBridgeService.KEY_ERROR) ?: "no reason given"),
                    severe = true,
                )
            }
        }
    }

    private fun safeText(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        try {
            block()
        } catch (t: Throwable) {
            Diagnostics.log(this, "A11Y-14", "ui update threw: $t")
        }
    }
    private var pendingRecordStart = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            binding.statusText.text = getString(R.string.camera_permission_required)
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    /** Android 8-9 only: needed to write the CSV into the public Download folder. */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingRecordStart) {
            pendingRecordStart = false
            startRecording()
        } else {
            pendingRecordStart = false
            binding.statusText.text = getString(R.string.storage_permission_required)
            Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A flick must be a deliberate, straight, one-directional movement: about a quarter of the
        // screen width, fast, within ~27 degrees of an axis. Anything less is hand/head wobble or
        // the eye merely looking around, and a wrong swipe is worse than a missed one. A flick may only
        // start after the gaze has been continuously still, and the new position must be held.
        flickDetector.minDisplacementPx =
            (resources.displayMetrics.widthPixels * 0.26).coerceAtLeast(300.0)
        flickDetector.minSpeedPxPerSec = 1600.0
        flickDetector.cooldownMs = 800.0
        flickDetector.rearmSpeedPxPerSec = 350.0
        flickDetector.minAxisDominance = 2.0
        flickDetector.minStraightness = 0.82
        flickDetector.minMonotonicity = 0.92
        flickDetector.settleWindowMs = 500.0
        flickDetector.confirmTimeoutMs = 300.0
        flickDetector.confirmFraction = 0.80
        flickDetector.snapBackFraction = 0.35
        flickDetector.maxWindowMs = 220.0

        Diagnostics.init(this)
        // Two processes: this one owns camera + MediaPipe, the accessibility service lives in
        // :gestures. Statics do not cross a process boundary, so every call goes through the
        // bound bridge below.
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Binding :gestures is also the recovery path for "Not working": the accessibility service
        // is rebound into a process that exists, so opening GazeTap can revive it without the user
        // cycling the accessibility toggle.
        try {
            bindService(
                Intent(this, GazeBridgeService::class.java),
                bridgeConnection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (t: Throwable) {
            Diagnostics.log(this, "A11Y-12", "could not bind the gesture bridge: $t")
        }
        Diagnostics.remoteReport = { formatBridgeStatus() }
        setupBlendshapePlaceholders(52)
        binding.helpText.text = getString(R.string.help_line) +
            "  |  v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"

        // If the previous run crashed, point at the report file instead of silently restarting.
        CrashReporter.lastCrashPath(this)?.let { path ->
            binding.statusText.text = getString(R.string.previous_crash, path)
        }

        gazeStore = GazeModelStore(File(filesDir, GazeModelStore.FILE_NAME))
        val stored = gazeStore.load()
        gazeModel = stored?.model
        storedModelInfo = stored
        if (stored == null && gazeStore.hasSavedModel()) {
            // Say why, instead of silently pretending the user never calibrated.
            Diagnostics.log(
                this, "E-CAL-4",
                "saved calibration not usable: ${gazeStore.lastLoadError ?: "unknown reason"}",
                severe = true,
            )
        }
        updateGazeButton()

        binding.recordButton.setOnClickListener {
            if (isRecording.get()) stopRecording() else requestRecording()
        }
        binding.calibrateButton.setOnClickListener { startCalibration() }
        binding.gazeButton.setOnClickListener { toggleGaze() }
        binding.flickButton.setOnClickListener { toggleFlicks() }
        binding.fastButton.setOnClickListener { toggleFastMode() }
        binding.testSwipeButton.setOnClickListener { runAccessibilitySelfTest() }
        updateFlickButton()
        updateFastButton()

        if (isAccessibilityEnabledInSettings() && !accessibilityUsable()) {
            binding.statusText.text = getString(R.string.accessibility_enabled_not_connected)
            binding.svcText.text = accessibilityHealthLine()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------------------------------------------------------- gaze control

    private fun updateGazeButton() {
        val ready = gazeModel != null
        binding.gazeButton.isEnabled = ready
        binding.gazeButton.text = when {
            !ready -> getString(R.string.gaze_needs_calibration)
            gazeEnabled -> getString(R.string.gaze_off)
            else -> getString(R.string.gaze_on)
        }
    }

    private fun toggleGaze() {
        if (gazeModel == null) {
            binding.statusText.text = getString(R.string.gaze_needs_calibration)
            return
        }
        gazeEnabled = !gazeEnabled
        gazeFilter.reset()
        rawWindow.clear()
        dwell.reset()
        flickDetector.reset()
        headSwipeDetector.reset()
        faceMotionSwipeDetector.reset()
        if (!gazeEnabled) {
            flicksEnabled = false
            flickWanted = false
            updateFlickButton()
        }
        refreshTrackingService()
        hasCursor = false
        hideCursor()
        updateGazeButton()
        binding.statusText.text =
            getString(if (gazeEnabled) R.string.gaze_enabled else R.string.gaze_disabled)
    }

    /** Clears the drawn cursor and dwell ring so no stale dot is left on screen. */
    private fun hideCursor() {
        dwellProgress = 0f
        cursorX = 0f
        cursorY = 0f
        binding.gazeOverlay.showCursor = false
        binding.gazeOverlay.dwellProgress = 0f
        binding.gazeOverlay.cursorX = 0f
        binding.gazeOverlay.cursorY = 0f
        binding.gazeOverlay.invalidate()
    }

    private fun resetFeatureStats() {
        for (d in featMin.indices) {
            featMin[d] = Double.MAX_VALUE
            featMax[d] = -Double.MAX_VALUE
            lookMin[d] = Double.MAX_VALUE
            lookMax[d] = -Double.MAX_VALUE
        }
    }

    /** Reads the system setting, because the service may be enabled but not yet bound. */
    private fun isAccessibilityEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = "${packageName}/${GazeAccessibilityService::class.java.name}"
        val short = "${packageName}/.GazeAccessibilityService"
        return enabled.contains(full) || enabled.contains(short)
    }

    /**
     * Proves whether the accessibility service can actually inject gestures, and writes a
     * diagnostic report either way: "This service is malfunctioning" in Settings carries no reason.
     */
    /**
     * One button that tests gesture injection and always produces a report file, whatever the
     * outcome - so a broken install still yields something sendable.
     */
    private fun runAccessibilitySelfTest() {
        val listed = isAccessibilityEnabledInSettings()
        if (sendToService(GazeBridgeService.MSG_SELFTEST, wantReply = true)) {
            binding.statusText.text = "Sending a test tap + swipe and writing a report..."
            return
        }
        Diagnostics.log(
            this,
            if (listed) "A11Y-01" else "A11Y-00",
            "report requested with no gesture process: listed=$listed, ${serviceHistory()}",
            severe = listed,
        )
        val path = Diagnostics.export(this, tag = "report")
        binding.statusText.text = if (path != null) {
            "[A11Y-01] the gesture process is not running - report saved to $path"
        } else {
            "[A11Y-01] the gesture process is not running - report could not be written"
        }
    }

    private fun finishSelfTest(result: String) {
        Diagnostics.log(this, "A11Y-T", "self test: $result")
        val path = Diagnostics.export(this, tag = "report")
        binding.statusText.text = if (path != null) {
            "Accessibility: $result - report saved to $path"
        } else {
            "Accessibility: $result - report could not be written"
        }
    }

    private fun updateFastButton() {
        binding.fastButton.text =
            getString(if (fastMode) R.string.low_res_on else R.string.low_res_off)
    }

    /**
     * Switches analysis resolution and per-frame outputs. Camera and landmarker are rebuilt
     * because both the resolution and the model options are fixed at construction time.
     * The existing calibration is deliberately NOT reused: it belongs to the other resolution.
     */
    private fun toggleFastMode() {
        fastMode = !fastMode
        gazeEnabled = false
        flicksEnabled = false
        mismatchNotified = false
        hasCursor = false
        hideCursor()
        gazeFilter.reset()
        rawWindow.clear()
        dwell.reset()
        flickDetector.reset()
        headSwipeDetector.reset()
        faceMotionSwipeDetector.reset()
        val previous = faceLandmarkerHelper
        faceLandmarkerHelper = null
        // Closed ON the camera thread, which is the only thread that feeds the model, so no frame
        // can still be inside it. Closing from the UI thread mid-frame was a native SIGSEGV, which
        // leaves no Java stack trace and so looked like an unexplainable "keeps stopping".
        runCatching { cameraExecutor.execute { previous?.clearFaceLandmarker() } }
        updateFastButton()
        updateGazeButton()
        updateFlickButton()
        startCamera()
        binding.statusText.text = getString(
            if (fastMode) R.string.low_res_enabled else R.string.low_res_disabled
        )
    }

    /** Runs only while tracking is in use: it is what keeps camera access outside GazeTap. */
    private fun refreshTrackingService() {
        if (gazeEnabled || flicksEnabled) {
            GazeSessionService.start(this)
            askForBatteryExemptionOnce()
        } else {
            GazeSessionService.stop(this)
        }
    }

    /**
     * How old the newest camera frame is. Anything above ~1 s while tracking is meant to be on means
     * the camera was taken away from us - which is exactly what a frozen cursor outside GazeTap is.
     */
    private fun frameAgeText(): String {
        val last = lastFrameAtMs
        if (last == 0L) return "none"
        return "%.1fs ago".format((SystemClock.elapsedRealtime() - last) / 1000.0)
    }

    /**
     * HyperOS suspends the camera and foreground services of apps it has not exempted from battery
     * optimisation. That is the difference between "cursor keeps working while Subway Surfers is in
     * front" and "cursor freezes the moment GazeTap leaves the screen".
     */
    private fun askForBatteryExemptionOnce() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            if (batteryAsked) return
            batteryAsked = true
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
        } catch (t: Throwable) {
            Diagnostics.log(this, "E-SYS-1", "battery exemption prompt unavailable: $t")
        }
    }

    /**
     * Rebuilds camera + model after the pipeline lost its frames (backgrounded too long, camera
     * taken by another app, or the preview surface was recreated). Without this the app came back to
     * a black preview and no cursor until the whole process was killed.
     */
    private fun restartPipeline(reason: String) {
        Diagnostics.log(this, "E-CAM-7", "rebinding the camera: $reason", severe = true)
        val previous = faceLandmarkerHelper
        faceLandmarkerHelper = null
        if (previous != null) {
            runCatching { cameraExecutor.execute { previous.clearFaceLandmarker() } }
        }
        lastFrameAtMs = 0L
        cameraClosed = false
        startCamera()
    }

    private fun updateFlickButton() {
        binding.flickButton.text =
            getString(if (flicksEnabled) R.string.flicks_on else R.string.flicks_off)
    }

    private fun toggleFlicks() {
        if (!flicksEnabled) {
            flicksEnabled = true
            flickWanted = true
            flickDetector.reset()
            headSwipeDetector.reset()
            faceMotionSwipeDetector.reset()
            // Face/head swipe detection works without gaze calibration; auto-enable gaze cursor only if a model exists.
            if (!gazeEnabled && gazeModel != null) {
                gazeEnabled = true
                gazeFilter.reset()
                rawWindow.clear()
                dwell.reset()
                updateGazeButton()
            }
            refreshTrackingService()
            updateFlickButton()
            val a11ySettingOn = isAccessibilityEnabledInSettings()
            val a11yUsable = accessibilityUsable()
            binding.statusText.text = when {
                !a11ySettingOn ->
                    "Swipes detection ON (counts/direction). Enable Accessibility \"GazeTap gestures\" to send swipes to other apps."
                !a11yUsable -> {
                    requestStatus(force = true)
                    mainHandler.postDelayed({ if (flickWanted) requestStatus(force = true) }, 700L)
                    "Swipes detection ON (counts/direction). Gesture service not connected yet — detection works, but system swipes won't be sent until service reconnects."
                }
                else -> getString(R.string.flicks_enabled)
            }
            return
        }
        flicksEnabled = false
        flickWanted = false
        flickDetector.reset()
        headSwipeDetector.reset()
        faceMotionSwipeDetector.reset()
        refreshTrackingService()
        updateFlickButton()
        binding.statusText.text = getString(R.string.flicks_disabled)
    }

    private fun startCalibration() {
        val w = binding.gazeOverlay.width
        val h = binding.gazeOverlay.height
        if (w <= 0 || h <= 0) {
            binding.statusText.text = getString(R.string.calibration_needs_camera)
            return
        }
        gazeEnabled = false
        hasCursor = false
        hideCursor()
        updateGazeButton()

        resetFeatureStats()
        val session = CalibrationSession(
            w.toDouble(), h.toDouble(),
            quadraticDims = GazeFeatures.COMBINED_QUADRATIC_DIMS,
        )
        calibration = session
        gazeFilter.reset()
        rawWindow.clear()
        dwell.reset()

        val target = session.currentTarget
        binding.gazeOverlay.showCalibrationTarget = true
        binding.gazeOverlay.targetX = (target?.first ?: 0.0).toFloat()
        binding.gazeOverlay.targetY = (target?.second ?: 0.0).toFloat()
        binding.gazeOverlay.targetProgress = 0f
        binding.gazeOverlay.invalidate()
        binding.statusText.text = getString(
            R.string.calibration_started, 1, session.totalPoints
        )
    }

    private fun finishCalibration(session: CalibrationSession) {
        try {
            val model = session.fit()
            val residual = session.residualPx(model)
            val spread = session.featureSpread()
            val screenWidthPx = resources.displayMetrics.widthPixels.toDouble()
            val residualLimit = max(160.0, screenWidthPx * 0.18)
            // If the iris features barely varied, the fit is meaningless: refuse to save it.
            if (spread < 0.03 || residual > residualLimit) {
                Diagnostics.log(
                    this, "E-CAL-1",
                    "fit rejected: spread=%.4f residual=%.1fpx limit=%.0fpx points=%d iris=%.3f look=%.3f"
                        .format(
                            spread, residual, residualLimit, session.fitPointCount(),
                            session.spreadOf(RidgePolynomialModel.SOURCE_IRIS),
                            session.spreadOf(RidgePolynomialModel.SOURCE_EYE_LOOK),
                        ),
                    severe = true,
                )
                calibration = null
                gazeModel = null
                runOnUiThread {
                    binding.gazeOverlay.showCalibrationTarget = false
                    hideCursor()
                    updateGazeButton()
                    binding.statusText.text = getString(
                        R.string.calibration_rejected,
                        spread, residual, residualLimit, session.totalPoints
                    )
                }
                return
            }
            gazeStore.save(model, lastFrameW, lastFrameH)
            // One less thing to get wrong: with a model and a bound service, swipes work now.
            val canSwipe = accessibilityUsable()
            if (canSwipe) {
                flicksEnabled = true
                flickDetector.reset()
                // Swipes are on now, so the camera keep-alive has to start here too: this path used to
                // skip it, leaving calmer sessions without background camera access.
                refreshTrackingService()
                Diagnostics.log(this, "A11Y-08", "swipes auto-enabled after calibration")
            } else {
                // Not "never", just "not yet": keep the request so the status reply completes it.
                flickWanted = true
                Diagnostics.log(
                    this, "A11Y-09",
                    "calibrated, swipes pending a bound gesture service: ${serviceHistory()}"
                )
            }
            storedModelInfo = GazeModelStore.Stored(model, lastFrameW, lastFrameH)
            mismatchNotified = false
            gazeModel = model
            calibration = null
            gazeFilter.reset()
            rawWindow.clear()
            dwell.reset()
            runOnUiThread {
                binding.gazeOverlay.showCalibrationTarget = false
                binding.gazeOverlay.invalidate()
                updateGazeButton()
                updateFlickButton()
                binding.statusText.text =
                    getString(R.string.calibration_saved, residual) +
                        "  · features=" + model.featureSource +
                        if (flicksEnabled) "  · swipes on" else "  · swipes off (no accessibility)"
            }
        } catch (e: Exception) {
            Diagnostics.log(this, "E-CAL-3", "fit threw: $e", severe = true)
            calibration = null
            runOnUiThread {
                binding.gazeOverlay.showCalibrationTarget = false
                binding.gazeOverlay.invalidate()
                binding.statusText.text = "[E-CAL-3] " + getString(
                    R.string.calibration_failed, e.message ?: "unknown error"
                )
            }
        }
    }

    /** Blendshape-based alternative features; needs full-res mode (blendshapes are off in low-res). */
    private fun eyeLookFeatures(result: FaceLandmarkerResult): DoubleArray? {
        val bs = result.faceBlendshapes()
        if (!bs.isPresent || bs.get().isEmpty()) return null
        val scores = HashMap<String, Float>(16)
        for (c in bs.get()[0]) scores[c.categoryName()] = c.score()
        return GazeFeatures.fromBlendshapes(scores)
    }

    private fun extractFeatures(result: FaceLandmarkerResult): DoubleArray? {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val lms = faces[0]
        if (lms.size < GazeFeatures.MIN_LANDMARKS) return null
        for (i in 0 until GazeFeatures.MIN_LANDMARKS) {
            lmsX[i] = lms[i].x()
            lmsY[i] = lms[i].y()
        }
        return GazeFeatures.extract(lmsX, lmsY)
    }

    /**
     * Average position of many stable face landmarks (nose, jaw/chin, cheeks, forehead, eyes, mouth)
     * mapped to screen-equivalent pixels, so whole-face movement can be measured as swipe distance.
     */
    private fun faceCenterScreenPx(result: FaceLandmarkerResult): DoubleArray? {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return null
        val lms = faces[0]
        if (lms.size < 468) return null
        // nose, bridge, forehead, chin/jaw, cheeks, eye corners, mouth corners - "total face"
        val idx = FACE_CENTER_LANDMARKS
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (i in idx) {
            if (i >= lms.size) continue
            val p = lms[i]
            sx += p.x().toDouble()
            sy += p.y().toDouble()
            n++
        }
        if (n == 0) return null
        val nx = sx / n
        val ny = sy / n
        val dm = resources.displayMetrics
        // Front camera preview is mirrored in UX; mirror x so "move head left" maps naturally.
        val px = (1.0 - nx) * dm.widthPixels
        val py = ny * dm.heightPixels
        return doubleArrayOf(px, py)
    }

    // ---------------------------------------------------------------- setup

    private fun setupBlendshapePlaceholders(count: Int) {
        binding.blendshapeContainer.removeAllViews()
        blendshapeBars.clear()
        repeat(count) { i ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                textSize = 10f
                text = "blendshape_$i"
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                max = 1000
                progress = 0
            }
            val value = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textSize = 10f
                text = "0.00"
            }
            row.addView(label)
            row.addView(bar)
            row.addView(value)
            binding.blendshapeContainer.addView(row)
            blendshapeBars.add(label to bar)
            bar.tag = value
        }
    }

    private fun startCamera() {
        cameraClosed = false
        binding.statusText.text = "Status: starting camera..."
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
          try {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            // 4:3 at ~640x480: enough detail for iris landmarks while keeping inference fast.
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        if (fastMode) Size(320, 240) else Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // Pinned on purpose. Without this, rotating the display (opening a landscape game)
                // changes rotationDegrees, so every frame arrives rotated 90 degrees and the whole
                // gaze feature space flips with it: the calibrated model then points sideways and
                // flick directions stop matching what the eye actually does.
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        // After onDestroy the executor is shut down and the model is closed: a frame
                        // that still arrives must be dropped, never processed.
                        if (cameraClosed) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val captureTs = SystemClock.elapsedRealtimeNanos()
                        metrics.onFrameCapture(captureTs)
                        try {
                            faceLandmarkerHelper?.detectLiveStream(imageProxy, isFrontCamera = true)
                        } catch (t: Throwable) {
                            // Throwable, not Exception: an OutOfMemoryError here would escape the
                            // camera thread and take the whole process down with it.
                            Diagnostics.log(this@MainActivity, "E-CAM-3", "analyzer: $t", severe = true)
                            runOnUiThread {
                                binding.statusText.text = "[E-CAM-3] analyzer: $t"
                            }
                            runCatching { imageProxy.close() }
                        }
                    }
                }

            try {
                analysisUseCase = imageAnalyzer
                previewUseCase = preview
                bindUseCases(cameraProvider, withPreview = activityVisible)
                initLandmarker()
            } catch (e: Exception) {
                Diagnostics.log(this, "E-CAM-2", "camera bind: $e", severe = true)
                binding.statusText.text = "[E-CAM-2] camera bind failed: ${e.message}"
            }
          } catch (t: Throwable) {
            // A throw here would happen on the UI thread and take the whole app down.
            Diagnostics.log(this, "E-CAM-1", "camera init: $t", severe = true)
            binding.statusText.text = "[E-CAM-1] camera init failed: ${t.message}"
          }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Binds camera use cases, with the preview only when this window can actually show it.
     *
     * The preview is bound to a surface that belongs to this Activity. Without a preview, analysis
     * runs headless - which is the only state that works while another app is in front.
     */
    private fun bindUseCases(provider: ProcessCameraProvider, withPreview: Boolean) {
        provider.unbindAll()
        val analysis = analysisUseCase ?: return
        trackingOwner.resume()
        val preview = previewUseCase
        if (withPreview && preview != null) {
            provider.bindToLifecycle(
                trackingOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis,
            )
        } else {
            provider.bindToLifecycle(trackingOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
    }

    private fun initLandmarker() {
        try {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = applicationContext,
                runningMode = RunningMode.LIVE_STREAM,
                currentDelegate = FaceLandmarkerHelper.DELEGATE_GPU,
                outputBlendshapes = !fastMode,
                outputTransformationMatrix = !fastMode,
                faceLandmarkerHelperListener = this
            )
            binding.statusText.text = "Status: running (GPU requested, CPU fallback if unavailable)"
        } catch (gpuError: Exception) {
            Diagnostics.log(this, "E-MOD-1", "GPU delegate failed: $gpuError")
            try {
                faceLandmarkerHelper = FaceLandmarkerHelper(
                    context = applicationContext,
                    runningMode = RunningMode.LIVE_STREAM,
                    currentDelegate = FaceLandmarkerHelper.DELEGATE_CPU,
                    outputBlendshapes = !fastMode,
                    outputTransformationMatrix = !fastMode,
                    faceLandmarkerHelperListener = this
                )
                binding.statusText.text = "Status: running (CPU delegate)"
            } catch (cpuError: Exception) {
                Diagnostics.log(this, "E-MOD-2", "CPU delegate failed: $cpuError", severe = true)
                binding.statusText.text =
                    "[E-MOD-2] model/init failed: ${cpuError.message}. ${getString(R.string.model_missing)}"
            }
        }
    }

    // ---------------------------------------------------------------- recording (Download/GazeTap)

    @Suppress("DEPRECATION")
    private fun requestRecording() {
        val needsLegacyStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

        if (needsLegacyStorage) {
            pendingRecordStart = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "gazetap_session_$stamp.csv"
        try {
            val destination = SessionDestination.create(this, fileName)
            recorder.start(destination)
            isRecording.set(true)
            binding.recordButton.text = getString(R.string.stop_recording)
            binding.statusText.text = getString(R.string.recording_to, destination.displayPath)
        } catch (e: Exception) {
            isRecording.set(false)
            binding.recordButton.text = getString(R.string.record_session)
            binding.statusText.text = getString(R.string.recording_failed, e.message ?: "unknown error")
        }
    }

    private fun stopRecording() {
        val path = recorder.stop(completed = true)
        isRecording.set(false)
        binding.recordButton.text = getString(R.string.record_session)
        binding.statusText.text =
            if (path != null) getString(R.string.recording_saved, path)
            else getString(R.string.recording_stopped)
    }

    // ---------------------------------------------------------------- frame callback

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        try {
            handleResults(resultBundle)
        } catch (t: Throwable) {
            onPipelineFailure(t)
        }
    }

    /**
     * A throw in the frame pipeline used to kill the whole process - and with it the accessibility
     * binding. Now the exception is logged with its stack trace and tracking simply continues, so
     * one bad frame can never cost the user their swipes again.
     */
    private fun onPipelineFailure(t: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPipelineErrorMs < 10_000L) return
        lastPipelineErrorMs = now
        Diagnostics.log(
            this, "E-PIPE-1",
            "frame pipeline threw: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString()}",
            severe = true,
        )
        runOnUiThread {
            binding.statusText.text =
                "[E-PIPE-1] " + (t.message ?: t.javaClass.simpleName) + " - logged, tracking continues"
        }
    }

    private fun handleResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        lastFrameAtMs = SystemClock.elapsedRealtime()
        metrics.onLandmarks(SystemClock.elapsedRealtimeNanos())

        lastFrameW = resultBundle.inputImageWidth
        lastFrameH = resultBundle.inputImageHeight
        val modeKey = if (fastMode) "fast" else "accurate"
        modeInferenceMs[modeKey] = modeInferenceMs[modeKey]
            ?.let { it * 0.9 + resultBundle.inferenceTime * 0.1 }
            ?: resultBundle.inferenceTime.toDouble()

        val result = resultBundle.result
        val featStart = SystemClock.elapsedRealtimeNanos()
        val pose = HeadPoseEstimator.estimate(result)
        val features = extractFeatures(result)
        if (features != null) {
            lastFeatures = features
            for (d in 0 until minOf(features.size, featMin.size)) {
                if (features[d] < featMin[d]) featMin[d] = features[d]
                if (features[d] > featMax[d]) featMax[d] = features[d]
            }
        }
        val look = eyeLookFeatures(result)
        // One 6-dim vector: averaged iris, averaged eyeLook, head yaw, head pitch.
        // Head pose is deliberately zeroed. As dims 4-5 of the model it made the cursor follow the
        // HEAD: turning your head moved it while moving only your eyes barely did - the opposite of
        // what a gaze cursor is for. Keeping the dims constant instead of dropping them preserves the
        // model format, the basis size and every stored value; ridge gives a constant column no
        // weight, and a constant cannot move a prediction.
        val combined = GazeFeatures.combine(features, look, 0.0, 0.0)
        if (look != null) {
            lastLookFeatures = look
            for (d in 0 until minOf(look.size, lookMin.size)) {
                if (look[d] < lookMin[d]) lookMin[d] = look[d]
                if (look[d] > lookMax[d]) lookMax[d] = look[d]
            }
        }
        metrics.onFeatures(SystemClock.elapsedRealtimeNanos() - featStart)

        val tsMs = SystemClock.elapsedRealtime().toDouble()
        var frameSwipeHandled = false
        // Face/head-motion swipes must work whenever a face is seen, independent of gaze calibration/model.
        if (flicksEnabled) {
            val face = faceCenterScreenPx(result)
            if (face != null) {
                val faceDir = faceMotionSwipeDetector.update(face[0], face[1], tsMs)
                val nowDbg = SystemClock.elapsedRealtime()
                if (nowDbg - lastSwipeDebugLogMs > 1500L) {
                    lastSwipeDebugLogMs = nowDbg
                    Diagnostics.log(
                        this, "SWIPE-DBG",
                        "face=(%.0f,%.0f) dx=%.0f dy=%.0f dist=%.0f spd=%.0f armed=%s near=%s settled=%s trig=%.0f ret=%.0f".format(
                            face[0], face[1],
                            faceMotionSwipeDetector.debugDx, faceMotionSwipeDetector.debugDy,
                            faceMotionSwipeDetector.debugDist, faceMotionSwipeDetector.debugSpeed,
                            faceMotionSwipeDetector.debugArmed, faceMotionSwipeDetector.debugNearCenter,
                            faceMotionSwipeDetector.debugSettled,
                            faceMotionSwipeDetector.triggerPx, faceMotionSwipeDetector.returnPx
                        )
                    )
                }
                if (faceDir != null) {
                    flickCount++
                    frameSwipeHandled = true
                    val direction = faceDir.name
                    val sent = sendToService(
                        GazeBridgeService.MSG_SWIPE,
                        Bundle().apply { putString(GazeBridgeService.KEY_DIR, direction) },
                        wantReply = true,
                    )
                    if (!sent) swipeRefusedCount++
                    Diagnostics.log(
                        this, "A11Y-07",
                        "face-swipe $direction face=(%.0f,%.0f) dx=%.0f dy=%.0f dist=%.0f queued=$sent".format(
                            face[0], face[1],
                            faceMotionSwipeDetector.debugDx, faceMotionSwipeDetector.debugDy,
                            faceMotionSwipeDetector.debugDist
                        ),
                    )
                    runOnUiThread {
                        binding.statusText.text = if (sent) {
                            "Face swipe $direction sent · total $flickCount (sent ${swipeSentCount}, refused $swipeRefusedCount)"
                        } else {
                            "Face swipe $direction detected, but gesture service not running · total $flickCount (refused $swipeRefusedCount)"
                        }
                    }
                }
                // When a face is tracked, rely on whole-face motion detector only (avoids extra/random triggers).
            } else {
                val nowDbg = SystemClock.elapsedRealtime()
                if (nowDbg - lastSwipeDebugLogMs > 3000L) {
                    lastSwipeDebugLogMs = nowDbg
                    Diagnostics.log(this, "SWIPE-DBG", "no face landmarks for face-center swipe")
                }
                faceMotionSwipeDetector.reset()
                // Fallback to head pose only when face landmarks are unavailable.
                val headDir = headSwipeDetector.update(
                    pose.yaw.toDouble(), pose.pitch.toDouble(), tsMs
                )
                if (headDir != null) {
                    flickCount++
                    frameSwipeHandled = true
                    val direction = headDir.name
                    val sent = sendToService(
                        GazeBridgeService.MSG_SWIPE,
                        Bundle().apply { putString(GazeBridgeService.KEY_DIR, direction) },
                        wantReply = true,
                    )
                    if (!sent) swipeRefusedCount++
                    Diagnostics.log(
                        this, "A11Y-07",
                        "head-swipe $direction yaw=%.1f pitch=%.1f queued=$sent".format(
                            pose.yaw.toDouble(), pose.pitch.toDouble()
                        ),
                    )
                    runOnUiThread {
                        binding.statusText.text = if (sent) {
                            "Head swipe $direction sent · total $flickCount (sent ${swipeSentCount}, refused $swipeRefusedCount)"
                        } else {
                            "Head swipe $direction detected, but gesture service not running · total $flickCount (refused $swipeRefusedCount)"
                        }
                    }
                }
            }
        } else {
            val nowDbg = SystemClock.elapsedRealtime()
            if (nowDbg - lastSwipeDebugLogMs > 5000L) {
                lastSwipeDebugLogMs = nowDbg
                Diagnostics.log(this, "SWIPE-DBG", "flicksEnabled=false (swipes off)")
            }
            faceMotionSwipeDetector.reset()
            headSwipeDetector.reset()
        }

        // A model fitted at one analysis resolution is not valid at another: landmarks shift.
        val info = storedModelInfo
        val modeMatches = info == null ||
            gazeStore.matchesResolution(info, lastFrameW, lastFrameH)
        if (gazeModel != null && !modeMatches && (gazeEnabled || flicksEnabled) && !mismatchNotified) {
            mismatchNotified = true
            gazeEnabled = false
            flicksEnabled = false
            hasCursor = false
            runOnUiThread {
                updateGazeButton()
                updateFlickButton()
                hideCursor()
                binding.statusText.text = getString(
                    R.string.recalibrate_for_mode,
                    info?.imageWidth ?: 0, info?.imageHeight ?: 0, lastFrameW, lastFrameH
                )
            }
        }

        val regStart = SystemClock.elapsedRealtimeNanos()
        val model = gazeModel
        val session = calibration
        if (combined != null && session != null) {
            session.addSample(combined)
            metrics.onRegression(SystemClock.elapsedRealtimeNanos() - regStart)
            metrics.onFilter(0)
            when {
                session.isAborted -> abortCalibration(session)
                session.isComplete -> finishCalibration(session)
                else -> postCalibrationProgress(session)
            }
        } else {
            // A model remembers which vector it was fitted on; feeding it the other one would
            // produce numbers with no meaning at all.
            val input = when {
                model == null -> null
                model.featureCount == GazeFeatures.COMBINED_COUNT -> combined
                model.usesEyeLook -> look
                else -> features
            }
            if (input != null && model != null && gazeEnabled && modeMatches) {
            val raw = model.predict(input)
            if (!raw[0].isFinite() || !raw[1].isFinite()) {
                // A model that produced NaN would otherwise walk the cursor off-screen and poison the
                // filters; skip the frame instead.
                Diagnostics.log(this, "E-PIPE-2", "model produced a non-finite position")
                return
            }
            metrics.onRegression(SystemClock.elapsedRealtimeNanos() - regStart)

            val filtStart = SystemClock.elapsedRealtimeNanos()
            val tsMs = SystemClock.elapsedRealtime().toDouble()
            // One noisy frame must not teleport the cursor: the median of the last three
            // predictions removes single-frame spikes while leaving a genuine path intact.
            val steady = medianOf3(raw[0], raw[1])
            // Always smooth, never snap back to the raw prediction. Dropping the filter on every
            // "saccade" is what made the dot jump between random points; the filter already raises
            // its cutoff with speed, so a real saccade still keeps up.
            val smoothed = gazeFilter.filter(steady[0], steady[1], tsMs / 1000.0)
            val dwellState = dwell.update(smoothed[0], smoothed[1], tsMs)
            // Flicks are matched on the STEADIED cursor, not the raw prediction: the dead zone keeps
            // the value frozen while the eye is still (so the detector can arm) and passes real
            // movement through 1:1. A one-frame model spike never reaches the detector this way.
            val flick = flickDetector.update(smoothed[0], smoothed[1], tsMs)
            metrics.onFilter(SystemClock.elapsedRealtimeNanos() - filtStart)

            var swipeHandled = frameSwipeHandled
            if (flick != null && flicksEnabled && !swipeHandled) {
                flickCount++
                val direction = flick.direction.name
                val speed = flick.speedPxPerSec
                val sent = sendToService(
                    GazeBridgeService.MSG_SWIPE,
                    Bundle().apply { putString(GazeBridgeService.KEY_DIR, direction) },
                    wantReply = true,
                )
                swipeHandled = true
                if (!sent) {
                    swipeRefusedCount++
                    Diagnostics.log(
                        this, "A11Y-06",
                        "flick $direction with no reachable :gestures process",
                        severe = true,
                    )
                }
                Diagnostics.log(
                    this, "A11Y-07",
                    "flick $direction speed=%.0fpx/s d=(%.0f,%.0f) queued=$sent".format(
                        speed, flick.dx, flick.dy
                    ),
                )
                runOnUiThread {
                    binding.statusText.text = if (sent) {
                        "Gaze swipe $direction sent (${speed.toInt()}px/s) · total $flickCount (sent ${swipeSentCount}, refused $swipeRefusedCount)"
                    } else {
                        "Gaze swipe $direction detected, but gesture service not running · total $flickCount (refused $swipeRefusedCount)"
                    }
                }
            }

            cursorX = smoothed[0].toFloat()
            cursorY = smoothed[1].toFloat()
            dwellProgress = dwellState.progress.toFloat()
            hasCursor = true
            if (dwellState.clicked) {
                clickCount++
                val dm = resources.displayMetrics
                val fx = smoothed[0].toFloat() / binding.gazeOverlay.width.coerceAtLeast(1)
                val fy = smoothed[1].toFloat() / binding.gazeOverlay.height.coerceAtLeast(1)
                val px = fx * dm.widthPixels
                val py = fy * dm.heightPixels
                // A dwell click is a tap, not a swipe: it must not require swipes to be enabled.
                if (accessibilityUsable()) {
                    sendToService(
                        GazeBridgeService.MSG_TAP,
                        Bundle().apply {
                            putFloat(GazeBridgeService.KEY_X, px)
                            putFloat(GazeBridgeService.KEY_Y, py)
                        },
                        wantReply = true,
                    )
                }
                runOnUiThread {
                    binding.statusText.text = getString(
                        R.string.dwell_click, clickCount, cursorX, cursorY
                    )
                }
            }
            } else {
                metrics.onRegression(SystemClock.elapsedRealtimeNanos() - regStart)
                metrics.onFilter(0)
                hasCursor = false
            }
        }

        if (isRecording.get()) {
            recorder.append(
                result,
                pose,
                resultBundle.inferenceTime,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth
            )
        }

        // The cursor is drawn on every frame: the 15 Hz text refresh below made it step visibly,
        // which is most of what "not smooth like a mouse cursor" looks like.
        postCursorToOverlay()

        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateMs < 66) return // ~15 Hz for the text; the pipeline itself is untouched
        lastUiUpdateMs = now

        runOnUiThread {
            updateBlendshapesUi(result)
            requestStatus()
            binding.svcText.text = accessibilityHealthLine() +
                "  · tracking " + (if (GazeSessionService.isRunning) "fg" else "OFF") +
                "  · frames " + frameAgeText() +
                "  · flicks $flickCount (swipes sent $swipeSentCount, refused $swipeRefusedCount)"
            binding.poseText.text =
                "Head pose yaw/pitch/roll (deg): %.1f / %.1f / %.1f".format(pose.yaw, pose.pitch, pose.roll)
            val s = metrics.snapshot()
            modeFps[modeKey] = s.fps
            binding.fpsText.text =
                "FPS: %.1f  (frame %d×%d)".format(s.fps, lastFrameW, lastFrameH)
            val f = lastFeatures
            val lk = lastLookFeatures
            binding.lookText.text = if (lk == null) {
                getString(R.string.look_debug_none)
            } else {
                String.format(
                    java.util.Locale.US,
                    "look R(%+.2f,%+.2f) L(%+.2f,%+.2f)  range(%.2f,%.2f)",
                    lk[0], lk[1], lk[2], lk[3],
                    lookMax[0] - lookMin[0], lookMax[1] - lookMin[1]
                ) + if (kotlin.math.abs(lk[1]) > 0.6 || kotlin.math.abs(lk[3]) > 0.6) {
                    "  SATURATED - hold the phone at eye level"
                } else {
                    ""
                }
            }
            binding.eyeText.text = if (f == null) {
                getString(R.string.eye_debug_none)
            } else {
                java.util.Locale.US.let { loc ->
                    String.format(
                        loc, "iris L(%+.2f,%+.2f) R(%+.2f,%+.2f)  range(%.2f,%.2f)",
                        f[0], f[1], f[2], f[3],
                        featMax[0] - featMin[0], featMax[1] - featMin[1]
                    )
                }
            }
            binding.modeText.text = getString(
                R.string.mode_stats,
                modeFps["accurate"] ?: 0.0, modeInferenceMs["accurate"] ?: 0.0,
                modeFps["fast"] ?: 0.0, modeInferenceMs["fast"] ?: 0.0
            )
            binding.latencyText.text =
                "Latency ms (cap/lm/feat/reg/filt/total): %.1f / %.1f / %.1f / %.2f / %.2f / %.1f  (p50/p95 total: %.1f / %.1f)".format(
                    s.captureMs, s.landmarksMs, s.featuresMs, s.regressionMs, s.filterMs, s.totalMs,
                    s.totalP50Ms, s.totalP95Ms
                )
            if (!isRecording.get() && calibration == null && !gazeEnabled) {
                binding.statusText.text =
                    "Status: face=${if (result.faceLandmarks().isNotEmpty()) "yes" else "no"} | inference=${resultBundle.inferenceTime}ms"
            }
        }
    }

    /**
     * Distinguishes the four states the settings screen collapses into "enabled":
     * off, enabled and bound, enabled but never bound by the system (the crashed case HyperOS
     * reports as "Not working. Tap for info."), and bound but with the overlay blocked.
     */
    private fun accessibilityHealthLine(): String {
        val listed = isAccessibilityEnabledInSettings()
        val st = bridgeStatus
        val bound = st?.getBoolean(GazeBridgeService.KEY_BOUND) == true
        val state = "$listed/$bound/" + (st != null)
        if (state != lastAccessibilityState) {
            lastAccessibilityState = state
            Diagnostics.log(
                this,
                if (bound) "A11Y-02" else if (listed) "A11Y-01" else "A11Y-00",
                when {
                    bound -> "accessibility bound in :gestures"
                    st == null -> "the :gestures process has not answered yet"
                    listed -> "settings say enabled but the system has not bound the service: " +
                        (st.getString(GazeBridgeService.KEY_HISTORY) ?: "-")
                    else -> "accessibility is off in settings"
                },
                severe = listed && !bound,
            )
        }
        return when {
            !listed -> "accessibility: off - enable GazeTap gestures in Settings"
            st == null -> "accessibility: waiting for the gesture process..."
            bound -> "accessibility: " + (st.getString(GazeBridgeService.KEY_STATUS) ?: "bound") +
                " / ok " + st.getInt(GazeBridgeService.KEY_COMPLETED) +
                " / cancelled " + st.getInt(GazeBridgeService.KEY_CANCELLED) +
                " / " + (st.getString(GazeBridgeService.KEY_LAST_RESULT) ?: "-")
            else -> "accessibility: SERVICE LOST (toggle GazeTap gestures off and on in Settings) - " +
                serviceHistory()
        }
    }

    /**
     * History now comes from the :gestures process, which is the only writer. Reading the same
     * SharedPreferences file from this process is what made an earlier build claim the service
     * "was never even created" for a service that had bound several times.
     */
    private fun serviceHistory(): String =
        bridgeStatus?.getString(GazeBridgeService.KEY_HISTORY)
            ?: "the gesture process has not answered yet"

    /**
     * Pushes the cursor to the floating accessibility window, and to this activity's own overlay
     * only as a fallback when that window could not be created.
     *
     * The send must happen whenever the service is bound, not only when its window already exists:
     * the service creates the window on the first cursor message it receives, so waiting for
     * "overlay on" before sending deadlocked it and left the cursor visible only inside GazeTap.
     */
    private fun postCursorToOverlay() {
        val x = cursorX
        val y = cursorY
        val progress = dwellProgress
        val visible = hasCursor
        val moved = lastDrawnX.isNaN() ||
            kotlin.math.abs(x - lastDrawnX) > cursorDeadbandPx ||
            kotlin.math.abs(y - lastDrawnY) > cursorDeadbandPx
        // A hidden cursor that is already hidden needs nothing: without this second clause the app
        // sent a "hide the cursor" binder message on every single frame, forever.
        val needsRedraw = (visible && (moved || progress > 0f)) || (!visible && lastSentVisible)
        if (!needsRedraw) return
        lastDrawnX = x
        lastDrawnY = y
        lastSentVisible = visible
        runOnUiThread {
            val ovW = binding.gazeOverlay.width.coerceAtLeast(1).toFloat()
            val ovH = binding.gazeOverlay.height.coerceAtLeast(1).toFloat()
            val remoteOn = remoteCursorOn()
            if (visible && accessibilityUsable()) sendCursor(x / ovW, y / ovH, progress, true)
            if (visible && !remoteOn) {
                // Fallback: the floating window does not exist, so keep a cursor on this screen
                // rather than none at all.
                binding.gazeOverlay.dwellProgress = progress
                binding.gazeOverlay.setCursorTarget(x, y)
                binding.gazeOverlay.showCursor = true
                // The dwell ring fills while the dot is motionless; without this repaint the ring
                // would freeze until the next movement.
                binding.gazeOverlay.invalidate()
            } else if (binding.gazeOverlay.showCursor) {
                binding.gazeOverlay.showCursor = false
                binding.gazeOverlay.invalidate()
            }
            if (!visible) sendCursor(0f, 0f, 0f, false)
        }
    }

    /** Median of the last three predictions per axis, so one bad frame cannot move the cursor. */
    private fun medianOf3(x: Double, y: Double): DoubleArray {
        rawWindow.addLast(doubleArrayOf(x, y))
        while (rawWindow.size > 3) rawWindow.removeFirst()
        val xs = DoubleArray(rawWindow.size) { rawWindow[it][0] }
        val ys = DoubleArray(rawWindow.size) { rawWindow[it][1] }
        xs.sort()
        ys.sort()
        fun median(a: DoubleArray): Double = if (a.size % 2 == 1) {
            a[a.size / 2]
        } else {
            (a[a.size / 2 - 1] + a[a.size / 2]) / 2.0
        }
        return doubleArrayOf(median(xs), median(ys))
    }

    /** Stops a calibration run whose points showed no eye movement at all. */
    private fun abortCalibration(session: CalibrationSession) {
        Diagnostics.log(
            this, "E-CAL-2",
            "calibration aborted after ${session.consecutiveRejections} non-moving points " +
                "(iris spread ${session.spreadOf(RidgePolynomialModel.SOURCE_IRIS)}, " +
                "look spread ${session.spreadOf(RidgePolynomialModel.SOURCE_EYE_LOOK)})"
        )
        calibration = null
        hideCursor()
        runOnUiThread {
            binding.gazeOverlay.showCalibrationTarget = false
            binding.gazeOverlay.invalidate()
            binding.statusText.text = getString(
                R.string.calibration_aborted, session.consecutiveRejections
            )
        }
    }

    private fun postCalibrationProgress(session: CalibrationSession) {
        val target = session.currentTarget ?: return
        val progress = session.currentPointProgress.toFloat()
        val pointNumber = session.currentPointIndex + 1
        runOnUiThread {
            binding.gazeOverlay.showCalibrationTarget = true
            binding.gazeOverlay.targetX = target.first.toFloat()
            binding.gazeOverlay.targetY = target.second.toFloat()
            binding.gazeOverlay.targetProgress = progress
            binding.gazeOverlay.invalidate()
            binding.statusText.text = if (!session.lastPointAccepted) {
                getString(R.string.calibration_no_movement)
            } else {
                getString(R.string.calibration_started, pointNumber, session.totalPoints)
            }
        }
    }

    private fun updateBlendshapesUi(result: FaceLandmarkerResult) {
        val blendshapes = result.faceBlendshapes()
        if (blendshapes.isPresent && blendshapes.get().isNotEmpty()) {
            val categories = blendshapes.get()[0]
            for (i in categories.indices) {
                if (i >= blendshapeBars.size) break
                val cat = categories[i]
                val label = blendshapeBars[i].first
                val bar = blendshapeBars[i].second
                val valueView = bar.tag as? TextView
                label.text = cat.categoryName()
                val score = cat.score()
                bar.progress = (score * 1000).roundToInt().coerceIn(0, 1000)
                valueView?.text = "%.2f".format(score)
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread { binding.statusText.text = "Status: error ($errorCode): $error" }
    }

    override fun onEmpty() {
        // A frame did arrive; it just had no face in it. Without this the watchdog below would
        // believe the camera died every time the user looked away.
        lastFrameAtMs = SystemClock.elapsedRealtime()
        metrics.onLandmarks(SystemClock.elapsedRealtimeNanos())
        // The face left the frame. Drop the cursor instead of leaving a ghost parked on screen, and
        // forget the flick/fixation history so the first frame after reacquiring the face cannot fire
        // a swipe from a stale displacement.
        if (hasCursor) {
            hasCursor = false
            dwellProgress = 0f
            dwell.reset()
            flickDetector.reset()
            gazeFilter.reset()
            rawWindow.clear()
        }
        runOnUiThread {
            if (!isRecording.get() && calibration == null) {
                binding.fpsText.text = "FPS: %.1f".format(metrics.snapshot().fps)
                binding.statusText.text = "Status: no face"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        // Back on screen: re-attach the preview to the running session.
        runCatching {
            val future = ProcessCameraProvider.getInstance(this)
            if (future.isDone) bindUseCases(future.get(), withPreview = true)
        }.onFailure { Diagnostics.log(this, "E-CAM-9", "preview rebind failed: $it") }
    }

    override fun onStop() {
        // Going away (Home, or a game in front). The preview surface dies with this window and takes
        // the whole camera session with it on some devices, so rebind analysis-only: that is what
        // keeps frames - and therefore flicks and the cursor - alive behind another app.
        activityVisible = false
        super.onStop()
        if (gazeEnabled || flicksEnabled) {
            runCatching {
                val future = ProcessCameraProvider.getInstance(this)
                if (future.isDone) bindUseCases(future.get(), withPreview = false)
            }.onFailure { Diagnostics.log(this, "E-CAM-8", "headless rebind failed: $it") }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Settings: ask immediately instead of waiting for the next poll.
        requestStatus(force = true)
        // The accessibility service shares the :gestures process, so keeping that process in the
        // foreground is what stops HyperOS from killing it - and the binding with it.
        if (isAccessibilityEnabledInSettings()) GazeBridgeService.keepAlive(this)

        // Coming back from the launcher or another app: if the camera stopped producing frames while
        // we were away, rebinding it here is what turns the black preview back into a live cursor.
        if (gazeEnabled || flicksEnabled) {
            val age = if (lastFrameAtMs == 0L) Long.MAX_VALUE
            else SystemClock.elapsedRealtime() - lastFrameAtMs
            if (age > 2_000L) {
                restartPipeline("no frame for ${age / 1000.0}s on resume")
                GazeSessionService.start(this)
            }
        }
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.postDelayed(frameWatchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(frameWatchdog)
    }

    /**
     * Re-binds the camera whenever frames stop arriving while tracking is supposed to be running.
     *
     * This is the difference between "it froze and stayed frozen" and "it recovers by itself": the
     * camera can be taken away at any moment (another app grabbing it, the system reclaiming it, a
     * lost surface), and none of those report an error the app can hook.
     */
    private val frameWatchdog = object : Runnable {
        override fun run() {
            try {
                if (gazeEnabled || flicksEnabled) {
                    val now = SystemClock.elapsedRealtime()
                    val last = lastFrameAtMs
                    val staleFor = if (last == 0L) Long.MAX_VALUE else now - last
                    if (staleFor > STALE_FRAME_MS && now - lastRestartMs > RESTART_THROTTLE_MS) {
                        lastRestartMs = now
                        GazeSessionService.start(this@MainActivity)
                        restartPipeline("watchdog: no frame for ${staleFor / 1000.0}s")
                    }
                }
            } finally {
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording.get()) stopRecording()
        try {
            unbindService(bridgeConnection)
        } catch (_: Throwable) {
        }
        bridge = null
        bridgeConnected = false
        Diagnostics.remoteReport = null
        GazeSessionService.stop(this)

        // Order matters, and getting it wrong is what produced the repeated "GazeTap keeps
        // stopping" after leaving the app: CameraX kept submitting frames to an executor that had
        // already been shut down (a rejected task thrown inside CameraX's own thread, uncaught),
        // and the MediaPipe model was closed while a frame was still inside it (native crash).
        // Frames first, then the model on the camera thread, then the thread itself.
        cameraClosed = true
        try {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        } catch (t: Throwable) {
            Diagnostics.log(this, "E-CAM-4", "unbindAll during destroy failed: $t")
        }
        val helper = faceLandmarkerHelper
        faceLandmarkerHelper = null
        runCatching {
            cameraExecutor.execute { helper?.clearFaceLandmarker() }
            cameraExecutor.shutdown()
        }
        trackingOwner.destroy()
        mainHandler.removeCallbacks(frameWatchdog)
    }

    /** Snapshot of the gesture process, appended to every exported report. */
    private fun formatBridgeStatus(): String {
        val st = bridgeStatus ?: return "the gesture process has not answered a status request yet"
        return buildString {
            append("bound: ").append(st.getBoolean(GazeBridgeService.KEY_BOUND)).append('\n')
            append("overlay: ").append(st.getBoolean(GazeBridgeService.KEY_OVERLAY)).append('\n')
            append("status: ").append(st.getString(GazeBridgeService.KEY_STATUS)).append('\n')
            append("last result: ").append(st.getString(GazeBridgeService.KEY_LAST_RESULT)).append('\n')
            append("last error: ").append(st.getString(GazeBridgeService.KEY_ERROR)).append('\n')
            append("accepted/refused: ").append(st.getInt(GazeBridgeService.KEY_ACCEPTED))
                .append(" / ").append(st.getInt(GazeBridgeService.KEY_REFUSED)).append('\n')
            append("completed/cancelled: ").append(st.getInt(GazeBridgeService.KEY_COMPLETED))
                .append(" / ").append(st.getInt(GazeBridgeService.KEY_CANCELLED)).append('\n')
            append("history: ").append(st.getString(GazeBridgeService.KEY_HISTORY)).append('\n')
        }
    }
}
