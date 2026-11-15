package com.gazetap.app

import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.max

class MetricsTracker {
    data class Snapshot(
        val fps: Double,
        val captureMs: Double,
        val landmarksMs: Double,
        val featuresMs: Double,
        val regressionMs: Double,
        val filterMs: Double,
        val totalMs: Double,
        val totalP50Ms: Double,
        val totalP95Ms: Double,
    )

    private var lastCaptureNs: Long = 0L
    private var lastFrameTimeMs: Long = 0L
    private val frameIntervals = ArrayDeque<Double>()
    private val totalLatencies = ArrayDeque<Double>()

    @Volatile private var lastCaptureMs: Double = 0.0
    @Volatile private var lastLandmarksMs: Double = 0.0
    @Volatile private var lastFeaturesMs: Double = 0.0
    @Volatile private var lastRegressionMs: Double = 0.0
    @Volatile private var lastFilterMs: Double = 0.0
    @Volatile private var lastTotalMs: Double = 0.0

    // Every method is synchronized: the camera thread writes these queues and the UI thread reads
    // them ("Status: no face" calls snapshot()). Without the lock, that read could land in the
    // middle of a write and throw ConcurrentModificationException on the main thread - which is a
    // real crash the user sees as "GazeTap keeps stopping" the moment the face leaves the frame.
    @Synchronized
    fun onFrameCapture(captureTsNs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        if (lastFrameTimeMs > 0) {
            val dt = (nowMs - lastFrameTimeMs).toDouble()
            if (dt > 0) {
                frameIntervals.addLast(dt)
                while (frameIntervals.size > 120) frameIntervals.removeFirst()
            }
        }
        lastFrameTimeMs = nowMs
        lastCaptureNs = captureTsNs
        lastCaptureMs = 0.0 // capture stage approximated as analyzer dispatch; landmarks includes wait+inference
    }

    @Synchronized
    fun onLandmarks(endNs: Long) {
        if (lastCaptureNs > 0) {
            lastLandmarksMs = (endNs - lastCaptureNs) / 1_000_000.0
        }
        updateTotal()
    }

    @Synchronized
    fun onFeatures(durationNs: Long) { lastFeaturesMs = durationNs / 1_000_000.0; updateTotal() }

    @Synchronized
    fun onRegression(durationNs: Long) { lastRegressionMs = durationNs / 1_000_000.0; updateTotal() }

    @Synchronized
    fun onFilter(durationNs: Long) { lastFilterMs = durationNs / 1_000_000.0; updateTotal() }

    private fun updateTotal() {
        lastTotalMs = lastCaptureMs + lastLandmarksMs + lastFeaturesMs + lastRegressionMs + lastFilterMs
        if (lastTotalMs > 0) {
            totalLatencies.addLast(lastTotalMs)
            while (totalLatencies.size > 240) totalLatencies.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val fps = if (frameIntervals.isEmpty()) 0.0 else 1000.0 / frameIntervals.average()
        val sorted = totalLatencies.sorted()
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        return Snapshot(
            fps = fps,
            captureMs = lastCaptureMs,
            landmarksMs = max(0.0, lastLandmarksMs),
            featuresMs = lastFeaturesMs,
            regressionMs = lastRegressionMs,
            filterMs = lastFilterMs,
            totalMs = lastTotalMs,
            totalP50Ms = p50,
            totalP95Ms = p95,
        )
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
