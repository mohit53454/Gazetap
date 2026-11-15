package com.gazetap.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log

class GazeTapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Initialised here rather than in the activity so that both processes log into the same
        // file: a crash or refusal inside :gestures used to leave nothing behind at all.
        Diagnostics.init(this)

        // Both processes run this class, and a crash loop shows up here as many "start #N" lines
        // seconds apart - the only evidence available when the crash was native and produced no
        // Java stack trace.
        try {
            val process = applicationInfo.processName ?: "?"
            val prefs = getSharedPreferences("gazetap_starts_$process", Context.MODE_PRIVATE)
            val starts = prefs.getInt("count", 0) + 1
            prefs.edit().putInt("count", starts).apply()
            Diagnostics.log(this, "APP-1", "process $process start #$starts, pid ${Process.myPid()}")
        } catch (t: Throwable) {
            Log.w("GazeTap", "startup bookkeeping failed", t)
        }
    }
}
