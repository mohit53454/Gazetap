package com.gazetap.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the stack trace of an uncaught exception into Download/GazeTap, so a crash on a phone
 * without adb can still be diagnosed by sharing one small text file.
 *
 * It never swallows the crash: the previous handler is always invoked, so Android's normal
 * behaviour (and the "app not responding" dialog) is preserved.
 */
object CrashReporter {

    private const val PREFS = "gazetap_prefs"
    private const val KEY_LAST_CRASH = "last_crash_path"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, throwable)
            } catch (ignored: Throwable) {
                Log.e("GazeTap", "could not persist crash", ignored)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** Returns the display path of the last crash file, if one was written. */
    fun lastCrashPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CRASH, null)

    private fun write(context: Context, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "gazetap_crash_$stamp.txt"

        val stack = StringWriter().also {
            PrintWriter(it).use { pw -> throwable.printStackTrace(pw) }
        }.toString()

        val body = buildString {
            append("GazeTap crash report\n")
            append("time: ").append(Date()).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android: ").append(Build.VERSION.RELEASE)
                .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
            append("abi: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
            append("app: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n")
            append(stack)
        }

        val dest = SessionDestination.create(context, fileName, mimeType = "text/plain")
        try {
            dest.outputStream.use { it.write(body.toByteArray()) }
            dest.complete(true)
        } catch (e: Exception) {
            dest.complete(false)
            throw e
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_CRASH, dest.displayPath).apply()
    }
}
