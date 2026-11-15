package com.gazetap.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single, append-only fault log so any failure on a phone without adb still produces one file the
 * user can send.
 *
 * Why codes: a sentence like "camera init failed" is ambiguous. Each failure path here gets a stable
 * code (E-CAM-2, A11Y-03, ...) that identifies the exact line of code that failed, so a single
 * screenshot or report file is enough to know where to look.
 */
object Diagnostics {

    /** Newest first in the file, but appended in order; reading the tail is enough. */
    private const val MAX_LINES = 600

    private val lines = ArrayDeque<String>()
    private var lastExportMs = 0L
    private var internalFile: File? = null

    fun init(context: Context) {
        // Called once per process now: GazeTapApp initialises it, so that a crash inside :gestures
        // is also written to the shared log file.
        if (internalFile != null) return
        val app = context.applicationContext
        internalFile = File(app.filesDir, "gazetap_diag.log")
        // Keep the previous run's lines: a crash on startup is exactly what we need to see.
        runCatching {
            internalFile?.takeIf { it.exists() }?.readLines()?.takeLast(MAX_LINES)?.forEach {
                lines.addLast(it)
            }
        }
        log(
            context, "APP-0",
            "started v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on " +
                "${Build.MANUFACTURER} ${Build.MODEL}, android ${Build.VERSION.RELEASE} " +
                "(sdk ${Build.VERSION.SDK_INT}), abi ${Build.SUPPORTED_ABIS.firstOrNull()}"
        )
    }

    /** @param code stable identifier of the failure site, e.g. "E-CAM-2". */
    fun log(context: Context?, code: String, message: String, severe: Boolean = false) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$stamp  $code  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.i(TAG, "$code $message")
        runCatching { internalFile?.appendText(line + "\n") }
        if (severe && context != null) exportThrottled(context)
    }

    /** Writes everything to Download/GazeTap and returns the path, or null if it could not. */
    fun export(context: Context, tag: String = "diag"): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val body = buildString {
            append("GazeTap fault report\n")
            append("generated: ").append(Date()).append('\n')
            append("app: v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
            append("debuggable build: ").append(BuildConfig.DEBUG).append('\n')
            // The accessibility service lives in :gestures, so nothing about it can be read from a
            // static here; the activity supplies its polled state through remoteReport.
            val remote = remoteReport?.invoke()
            if (remote != null) {
                append("\n--- accessibility service (:gestures) ---\n").append(remote).append('\n')
            } else {
                append("\n--- accessibility service (:gestures) ---\nnot reachable\n")
            }
            append("\n--- event log ---\n")
            synchronized(lines) { lines.forEach { append(it).append('\n') } }
        }
        return try {
            val dest = SessionDestination.create(context, "gazetap_${tag}_$stamp.txt", "text/plain")
            dest.outputStream.use { it.write(body.toByteArray()) }
            dest.complete(true)
            lastExportMs = android.os.SystemClock.elapsedRealtime()
            dest.displayPath
        } catch (t: Throwable) {
            Log.e(TAG, "export failed", t)
            null
        }
    }

    /** Writes at most one automatic report every 15 s, so a repeating fault cannot spam Downloads. */
    fun exportThrottled(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExportMs < 15_000L) return
        export(context, tag = "auto")
    }

    /** Set by the activity so exports include the state of the :gestures process. */
    @Volatile
    var remoteReport: (() -> String?)? = null

    fun tail(count: Int = 3): String {
        synchronized(lines) { return lines.takeLast(count).joinToString(" | ") }
    }

    private const val TAG = "GazeTapDiag"
}
