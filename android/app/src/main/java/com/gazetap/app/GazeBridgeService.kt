package com.gazetap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gazetap.app.gaze.FlickDirection

/**
 * The one channel between the app process (camera, MediaPipe, calibration) and the
 * accessibility service, which lives in its own `:gestures` process.
 *
 * Why the process split: gesture injection is the only part of GazeTap that has to outlive the
 * camera. While both lived in one process, every death of that process - memory pressure from
 * MediaPipe, swiping the app away, or any crash in the frame pipeline - also destroyed the
 * accessibility binding, and HyperOS reports that as "GazeTap gestures: Not working. Tap for info."
 *
 * Why it is also a foreground service: the split only helps if `:gestures` itself stays alive. On
 * HyperOS a plain background process gets reclaimed, the service is destroyed with it, and the
 * accessibility binding is not re-created - the app then correctly reports "SERVICE LOST ... created
 * 14:40:59, bound 14:40:59 (x1)" with no unbind recorded, which is the signature of a killed
 * process. A foreground slot takes that process out of the low-memory kill list.
 *
 * Why Messenger and not a shared static: statics are per process. The previous attempt at this
 * split read `GazeAccessibilityService.instance` from the activity, which is always null in the
 * app process, and the resulting null produced the crash that got the split reverted. Every call
 * here crosses a real binder, so nothing can be silently null.
 */
class GazeBridgeService : Service() {

    private val messenger: Messenger by lazy { Messenger(IncomingHandler(Looper.getMainLooper())) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called through keepAlive(): the point is the foreground slot, not the command.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            // Android 14+ refuses a foreground service started from the background. Losing the
            // keep-alive must never crash the gesture engine itself.
            Log.e(TAG, "could not become a foreground service", t)
            Diagnostics.log(this, "A11Y-15", "gesture engine could not hold a foreground slot: $t")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gesture engine",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps eye-flick swipes and dwell taps ready in every app"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GazeTap gestures are ready")
            .setContentText("Swipes and taps work in other apps while this is running.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_CURSOR -> {
                        val d = msg.data
                        GazeAccessibilityService.instance?.setCursorNorm(
                            d.getFloat(KEY_NX),
                            d.getFloat(KEY_NY),
                            d.getFloat(KEY_PROGRESS),
                            d.getBoolean(KEY_VISIBLE),
                        )
                    }

                    MSG_SWIPE -> {
                        val dir = runCatching {
                            FlickDirection.valueOf(msg.data.getString(KEY_DIR) ?: "RIGHT")
                        }.getOrDefault(FlickDirection.RIGHT)
                        val svc = GazeAccessibilityService.instance
                        val ok = svc?.swipe(dir) ?: false
                        reply(
                            msg, MSG_SWIPE_REPLY, Bundle().apply {
                                putBoolean(KEY_OK, ok)
                                putString(KEY_DIR, dir.name)
                                putString(
                                    KEY_ERROR,
                                    when {
                                        svc == null -> "the accessibility service is not bound (toggle it OFF then ON)"
                                        else -> GazeAccessibilityService.lastError
                                            ?: "refused by the system"
                                    },
                                )
                            }
                        )
                    }

                    MSG_TAP -> {
                        val d = msg.data
                        val svc = GazeAccessibilityService.instance
                        val ok = svc?.tap(d.getFloat(KEY_X), d.getFloat(KEY_Y)) ?: false
                        reply(
                            msg, MSG_TAP_REPLY, Bundle().apply {
                                putBoolean(KEY_OK, ok)
                                putString(
                                    KEY_ERROR,
                                    if (svc == null) "the accessibility service is not bound"
                                    else GazeAccessibilityService.lastError ?: "refused by the system",
                                )
                            }
                        )
                    }

                    MSG_SELFTEST -> {
                        val svc = GazeAccessibilityService.instance
                        if (svc == null) {
                            reply(
                                msg, MSG_SELFTEST_REPLY, Bundle().apply {
                                    putString(
                                        KEY_RESULT,
                                        "the accessibility service is not bound in :gestures - " +
                                            GazeAccessibilityService.unboundHistory(this@GazeBridgeService),
                                    )
                                }
                            )
                        } else {
                            svc.selfTest { result ->
                                reply(
                                    msg, MSG_SELFTEST_REPLY, Bundle().apply {
                                        putString(KEY_RESULT, result)
                                    }
                                )
                            }
                        }
                    }

                    MSG_STATUS -> reply(
                        msg,
                        MSG_STATUS_REPLY,
                        GazeAccessibilityService.instance?.statusBundle()
                            ?: GazeAccessibilityService.unboundStatusBundle(this@GazeBridgeService),
                    )

                    else -> super.handleMessage(msg)
                }
            } catch (t: Throwable) {
                // The bridge handler is on the main thread of :gestures: an unchecked throw here
                // would take down the gesture service itself.
                Log.e(TAG, "bridge command ${msg.what} failed", t)
                Diagnostics.log(this@GazeBridgeService, "A11Y-13", "bridge command ${msg.what} threw: $t")
            }
        }
    }

    private fun reply(request: Message, what: Int, data: Bundle) {
        val target = request.replyTo ?: return
        val answer = Message.obtain(null, what)
        answer.data = data
        try {
            target.send(answer)
        } catch (t: Throwable) {
            Log.w(TAG, "reply $what could not be delivered", t)
        }
    }

    companion object {
        private const val TAG = "GazeTapBridge"
        private const val CHANNEL_ID = "gazetap_engine"
        private const val NOTIFICATION_ID = 4712

        // commands
        const val MSG_CURSOR = 1
        const val MSG_SWIPE = 2
        const val MSG_TAP = 3
        const val MSG_SELFTEST = 4
        const val MSG_STATUS = 5

        // replies (offset so a stray command can never be mistaken for one)
        const val MSG_STATUS_REPLY = 101
        const val MSG_SELFTEST_REPLY = 102
        const val MSG_SWIPE_REPLY = 103
        const val MSG_TAP_REPLY = 104

        // payload keys
        const val KEY_NX = "nx"
        const val KEY_NY = "ny"
        const val KEY_PROGRESS = "progress"
        const val KEY_VISIBLE = "visible"
        const val KEY_DIR = "dir"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_OK = "ok"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_BOUND = "bound"
        const val KEY_OVERLAY = "overlay"
        const val KEY_STATUS = "status"
        const val KEY_LAST_RESULT = "lastResult"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_REFUSED = "refused"
        const val KEY_COMPLETED = "completed"
        const val KEY_CANCELLED = "cancelled"
        const val KEY_HISTORY = "history"

        /** Puts the gesture engine - and the accessibility binding inside it - in the foreground. */
        fun keepAlive(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GazeBridgeService::class.java),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "could not keep the gesture engine alive", t)
                Diagnostics.log(context, "A11Y-15", "keepAlive failed: $t")
            }
        }
    }
}
