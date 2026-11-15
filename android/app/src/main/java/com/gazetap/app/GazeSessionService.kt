package com.gazetap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps camera access alive while another app is in front.
 *
 * Why this exists: the camera was bound to MainActivity's own lifecycle, so the moment the user
 * opened anything else the camera closed, the frame pipeline stopped, and the cursor froze. There
 * was nothing left to detect a flick, so no swipe could ever be injected outside GazeTap - swipe
 * injection was fine all along, it simply had no gaze input to act on.
 *
 * This service owns no pipeline of its own. Its only job is to hold the process in a state where
 * Android still grants the camera to a backgrounded app, and to show the notice the system requires
 * while the camera is in use.
 */
class GazeSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            isRunning = true
        } catch (t: Throwable) {
            // Android 14+ refuses a camera-type foreground service started from the background.
            // Losing the keep-alive must never crash the app: log it and let the activity fall back
            // to activity-bound tracking.
            Log.e(TAG, "startForeground failed", t)
            Diagnostics.log(this, "E-CAM-6", "could not promote to a foreground service: $t", severe = true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Diagnostics.log(this, "E-CAM-5", "the camera keep-alive service is stopping")
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
                "Eye tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while GazeTap uses the camera to move the cursor"
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
            .setContentTitle("GazeTap is tracking your eyes")
            .setContentText("Eyes move the cursor, flicks swipe. Tap to open GazeTap.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "GazeTapSession"
        private const val CHANNEL_ID = "gazetap_tracking"
        private const val NOTIFICATION_ID = 4711

        /** Read by the activity to show whether background camera access is actually held. */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GazeSessionService::class.java),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "could not start tracking service", t)
                Diagnostics.log(context, "E-CAM-6", "could not start the tracking service: $t")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GazeSessionService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
