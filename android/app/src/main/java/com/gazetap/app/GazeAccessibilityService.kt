package com.gazetap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gazetap.app.gaze.FlickDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Sends gaze commands into whatever app is in front (swipes, taps) and hosts the floating cursor.
 *
 * Two jobs, because both need the same binding:
 *  1. `dispatchGesture` is the only sanctioned way for a third-party app to inject touch into other
 *     apps.
 *  2. A window of type TYPE_ACCESSIBILITY_OVERLAY needs no permission and floats above every app,
 *     so the cursor can be visible outside GazeTap too.
 *
 * This class runs in the dedicated `:gestures` process (see the manifest) and is driven through
 * [GazeBridgeService]. It deliberately shares nothing with the camera pipeline: that process is
 * where MediaPipe allocates hundreds of megabytes, gets swiped away, and occasionally crashes, and
 * every one of those events used to kill gesture injection with it.
 *
 * Every failure is captured in [status]/[lastError] and can be written to
 * Download/GazeTap/accessibility_diag_*.txt, because "This service is malfunctioning" on the
 * settings screen gives no reason at all.
 */
class GazeAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlayView: GazeOverlayView? = null
    private var overlayError: String? = null

    override fun onCreate() {
        super.onCreate()
        record("created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Nothing is created here on purpose. On HyperOS, touching WindowManager during the bind
        // is a good way to be marked as a crashed service ("Not working. Tap for info."), and a
        // crashed service never rebinds until the user cycles the toggle.
        status = "bound"
        record("bound")
        log("connected (overlay deferred)")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        detachOverlay()
        if (instance === this) instance = null
        status = "unbound"
        record("unbound")
        log("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachOverlay()
        if (instance === this) instance = null
        status = "destroyed"
        record("destroyed")
        log("destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------ floating cursor

    /** Adds the floating cursor window, once, outside the service bind path. */
    private fun ensureOverlay() {
        try {
            attachOverlay()
            status = "bound (cursor overlay on)"
            log("overlay attached")
        } catch (t: Throwable) {
            overlayError = "${t.javaClass.simpleName}: ${t.message}"
            status = "bound, overlay blocked"
            log("overlay failed: $overlayError")
            Log.e(TAG, "cursor overlay failed", t)
        }
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = GazeOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(view, lp)
        overlayView = view
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }

    /**
     * Positions the floating cursor from normalised coordinates (0..1). Normalised because the
     * activity overlay used for calibration is not the same size as the screen: pixels would put
     * the cursor in the wrong place, fractions do not.
     */
    fun setCursorNorm(fx: Float, fy: Float, progress: Float, visible: Boolean) {
        if (visible && overlayView == null && overlayError == null) ensureOverlay()
        main.post(Runnable {
            val view = overlayView
            if (view != null && view.width > 0 && view.height > 0) {
                view.showCursor = visible
                if (visible) {
                    // Target, not position: the view eases towards it at display rate so the dot
                    // travels in a continuous line between the ~15 fps camera frames.
                    view.setCursorTarget(
                        fx.coerceIn(0f, 1f) * view.width,
                        fy.coerceIn(0f, 1f) * view.height,
                    )
                    view.dwellProgress = progress
                } else {
                    view.dwellProgress = 0f
                }
                view.invalidate()
            }
        })
    }

    // ------------------------------------------------------------------ gestures

    /** One flick = one swipe of ~30% of the short screen edge, taking [SWIPE_DURATION_MS]. */
    fun swipe(direction: FlickDirection, onDone: ((Boolean) -> Unit)? = null): Boolean {
        val (w, h) = screenSizePx()
        if (w <= 0f || h <= 0f) {
            lastError = "the display size is not available yet"
            return false
        }
        val reach = min(w, h) * SWIPE_REACH_FRACTION
        val (dx, dy) = when (direction) {
            FlickDirection.LEFT -> -reach to 0f
            FlickDirection.RIGHT -> reach to 0f
            FlickDirection.UP -> 0f to -reach
            FlickDirection.DOWN -> 0f to reach
        }
        // Centred on the display, then clamped so the whole stroke stays on screen. A stroke that
        // starts outside the display is swallowed with no error at all - which is exactly what a
        // landscape game sees when the metrics cached by this background process are still portrait.
        val maxX = (w - EDGE_MARGIN).coerceAtLeast(EDGE_MARGIN)
        val maxY = (h - EDGE_MARGIN).coerceAtLeast(EDGE_MARGIN)
        val sx = (w / 2f - dx / 2f).coerceIn(EDGE_MARGIN, maxX)
        val sy = (h / 2f - dy / 2f).coerceIn(EDGE_MARGIN, maxY)
        val ex = (sx + dx).coerceIn(EDGE_MARGIN, maxX)
        val ey = (sy + dy).coerceIn(EDGE_MARGIN, maxY)

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        // Logged with the size it used, so a report shows whether the geometry was plausible.
        log(
            "swipe ${direction.name} on ${w.toInt()}x${h.toInt()} " +
                "(%.0f,%.0f)->(%.0f,%.0f)".format(Locale.US, sx, sy, ex, ey)
        )
        return dispatch("swipe ${direction.name}", path, 0L, SWIPE_DURATION_MS, onDone)
    }

    /**
     * Display size in the CURRENT rotation.
     *
     * `resources.displayMetrics` is the configuration this background process last received, which is
     * not necessarily the rotation the app in front is using. Reading the window bounds makes the
     * swipe geometry correct in portrait, landscape and multi-window alike.
     */
    private fun screenSizePx(): Pair<Float, Float> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WindowManager::class.java)
                val bounds = wm?.maximumWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width().toFloat() to bounds.height().toFloat()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the display bounds", t)
        }
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    /** Dwell click: a real tap at screen coordinates. */
    fun tap(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch("tap %.0f,%.0f".format(Locale.US, x, y), path, 0L, TAP_DURATION_MS, onDone)
    }

    private fun dispatch(
        label: String,
        path: Path,
        startMs: Long,
        durationMs: Long,
        onDone: ((Boolean) -> Unit)?,
    ): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed++
                        lastResult = "$label -> completed"
                        onDone?.invoke(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cancelled++
                        lastResult = "$label -> cancelled (another gesture or a system gesture won)"
                        onDone?.invoke(false)
                    }
                },
                null,
            )
        } catch (t: Throwable) {
            // A throw here means the service is bound but gesture injection is refused, which is
            // exactly the case the settings screen reports as "not working".
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            log("dispatchGesture threw for $label: $lastError")
            onDone?.invoke(false)
            return false
        }
        if (accepted) {
            acceptedCount++
            // A message from an earlier failure must not be reported as this gesture's reason.
            lastError = null
        } else {
            refused++
        }
        lastResult = if (accepted) "$label -> accepted" else "$label -> refused by the system"
        if (!accepted) {
            lastError = "dispatchGesture returned false for $label"
            log("dispatchGesture returned false for $label")
        }
        return accepted
    }

    /**
     * Exercises exactly the two gesture types the gaze pipeline uses, in order: a dwell tap, then a
     * flick swipe. The swipe half is what proves the swipe path independently of calibration.
     */
    fun selfTest(callback: (String) -> Unit) {
        val dm = resources.displayMetrics
        val t0 = SystemClock.elapsedRealtime()
        val accepted = tap(dm.widthPixels / 2f, dm.heightPixels * 0.55f) { tapOk ->
            val tapMs = SystemClock.elapsedRealtime() - t0
            if (!tapOk) {
                main.post { callback("tap cancelled (${lastError ?: "no reason given"})") }
                return@tap
            }
            val swipeAccepted = swipe(FlickDirection.RIGHT) { swipeOk ->
                val total = SystemClock.elapsedRealtime() - t0
                main.post {
                    callback(
                        "tap OK in ${tapMs}ms, swipe " +
                            (if (swipeOk) "OK" else "cancelled: " + (lastError ?: "?")) +
                            " (${total}ms total)"
                    )
                }
            }
            if (!swipeAccepted) {
                main.post {
                    callback(
                        "tap OK, swipe REFUSED by the system (" +
                            (lastError ?: "no reason given") + ")"
                    )
                }
            }
        }
        if (!accepted) {
            main.post { callback("tap refused by the system (${lastError ?: "no reason given"})") }
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /** Everything the app needs to describe this service's state, as one bundle over the bridge. */
    fun statusBundle(): Bundle = Bundle().apply {
        putBoolean(GazeBridgeService.KEY_BOUND, true)
        putBoolean(GazeBridgeService.KEY_OVERLAY, overlayView != null)
        putString(GazeBridgeService.KEY_STATUS, status)
        putString(GazeBridgeService.KEY_LAST_RESULT, lastResult ?: "-")
        putString(GazeBridgeService.KEY_ERROR, lastError ?: (overlayError ?: "-"))
        putInt(GazeBridgeService.KEY_ACCEPTED, acceptedCount)
        putInt(GazeBridgeService.KEY_REFUSED, refused)
        putInt(GazeBridgeService.KEY_COMPLETED, completed)
        putInt(GazeBridgeService.KEY_CANCELLED, cancelled)
        putString(GazeBridgeService.KEY_HISTORY, historyText(getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
    }

    /**
     * Writes the timestamp of a lifecycle event into shared preferences. Because this runs in the
     * `:gestures` process, only that process reads it back: another process's cached copy of the
     * same file is how an earlier version managed to report "the service was never created" for a
     * service that had bound three times.
     */
    private fun record(event: String) {
        try {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            prefs.edit()
                .putString("a11y_$event", stamp)
                .putInt("a11y_bind_count", prefs.getInt("a11y_bind_count", 0) + if (event == "bound") 1 else 0)
                .commit()
        } catch (t: Throwable) {
            Log.w(TAG, "could not record $event", t)
        }
    }

    fun diagnosticsReport(): String = buildString {
        append("GazeTap accessibility diagnostics\n")
        append("time: ").append(Date()).append('\n')
        append("app: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
        append(android.os.Build.MODEL).append(", android ").append(android.os.Build.VERSION.RELEASE)
        append(" (sdk ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        append("process: ").append(applicationInfo.processName ?: "?").append("\n\n")
        append("bound: ").append(instance != null).append('\n')
        append("status: ").append(status).append('\n')
        append("cursor overlay: ").append(overlayView != null).append('\n')
        if (overlayError != null) append("overlay error: ").append(overlayError).append('\n')
        append("gestures accepted/refused: ").append(acceptedCount).append(" / ")
        append(refused).append('\n')
        append("gestures completed/cancelled: ").append(completed).append(" / ").append(cancelled).append('\n')
        append("last result: ").append(lastResult ?: "-").append('\n')
        append("last error: ").append(lastError ?: "-").append('\n')
        val svcInfo = try {
            serviceInfo?.let {
                "eventTypes=${it.eventTypes} flags=${it.flags}"
            } ?: "serviceInfo null"
        } catch (t: Throwable) {
            "serviceInfo threw: ${t.message}"
        }
        append("serviceInfo: ").append(svcInfo).append("\n\n")
        append("log:\n")
        synchronized(logLines) { logLines.forEach { append("  ").append(it).append('\n') } }
    }

    /** Writes the report into Download/GazeTap and returns its display path. */
    fun writeDiagnostics(context: Context): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return try {
            val dest = SessionDestination.create(context, "gazetap_accessibility_$stamp.txt", "text/plain")
            dest.outputStream.use { it.write(diagnosticsReport().toByteArray()) }
            dest.complete(true)
            dest.displayPath
        } catch (t: Throwable) {
            Log.e(TAG, "could not write diagnostics", t)
            null
        }
    }

    private fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(logLines) {
            logLines.add("$stamp $line")
            while (logLines.size > 60) logLines.removeAt(0)
        }
        Log.d(TAG, line)
    }

    companion object {
        private const val TAG = "GazeTapA11y"
        const val PREFS = "gazetap_prefs"
        private const val SWIPE_DURATION_MS = 120L
        private const val TAP_DURATION_MS = 40L

        /** 30% of the short edge: ~3700 px/s on a 1080-wide screen, well inside a game's flick.
         *  Small enough to stay on screen in landscape. */
        private const val SWIPE_REACH_FRACTION = 0.30f

        /** Paths never touch the very edge of the display, where system gestures live. */
        private const val EDGE_MARGIN = 24f

        private val logLines = ArrayList<String>()
        @Volatile private var acceptedCount = 0
        @Volatile private var refused = 0
        @Volatile private var completed = 0
        @Volatile private var cancelled = 0
        @Volatile var lastResult: String? = null
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var status: String = "not bound"
            private set

        /** Only meaningful inside `:gestures`; the app process must use the bridge instead. */
        @Volatile
        var instance: GazeAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** Did the system ever bind us, and when did it drop us? Read in `:gestures` only. */
        fun historyText(prefs: android.content.SharedPreferences): String {
            val created = prefs.getString("a11y_created", null)
            val boundAt = prefs.getString("a11y_bound", null)
            val lostAt = prefs.getString("a11y_destroyed", null)
                ?: prefs.getString("a11y_unbound", null)
            val binds = prefs.getInt("a11y_bind_count", 0)
            if (created == null && boundAt == null) {
                return "not created since install - turn the toggle OFF, leave Settings, swipe GazeTap " +
                    "away, then turn it ON again"
            }
            return buildString {
                append("created ").append(created ?: "-")
                append(", bound ").append(boundAt ?: "never")
                append(" (x").append(binds).append(')')
                if (lostAt != null) append(", lost ").append(lostAt)
            }
        }

        fun unboundHistory(context: Context): String = try {
            historyText(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        } catch (t: Throwable) {
            "history unreadable: ${t.message}"
        }

        /** Status bundle for the case where the accessibility binding does not exist at all. */
        fun unboundStatusBundle(context: Context): Bundle = Bundle().apply {
            putBoolean(GazeBridgeService.KEY_BOUND, false)
            putBoolean(GazeBridgeService.KEY_OVERLAY, false)
            putString(GazeBridgeService.KEY_STATUS, status)
            putString(GazeBridgeService.KEY_LAST_RESULT, lastResult ?: "-")
            putString(GazeBridgeService.KEY_ERROR, lastError ?: "-")
            putInt(GazeBridgeService.KEY_ACCEPTED, acceptedCount)
            putInt(GazeBridgeService.KEY_REFUSED, refused)
            putInt(GazeBridgeService.KEY_COMPLETED, completed)
            putInt(GazeBridgeService.KEY_CANCELLED, cancelled)
            putString(GazeBridgeService.KEY_HISTORY, unboundHistory(context))
        }
    }
}
