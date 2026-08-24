package com.geminiweb2api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/**
 * Foreground service that hosts the gemini-web2api Python HTTP server.
 *
 * The server itself runs on a Python daemon thread inside this process; the
 * service keeps the process alive (notification + partial wake lock) so other
 * apps on the device can call the API while the screen is off.
 */
class MainService : Service() {

    companion object {
        const val ACTION_START = "com.geminiweb2api.action.START"
        const val ACTION_STOP = "com.geminiweb2api.action.STOP"
        private const val CHANNEL_ID = "gemini_web2api_server"
        private const val NOTIFICATION_ID = 1001

        @Volatile var running = false
            private set
        @Volatile var baseUrl: String = ""
            private set
        @Volatile var lastError: String? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager().createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))
        if (!running) {
            startServerThread()
        }
        // Sticky so Android restarts the server after the process is killed.
        return START_STICKY
    }

    private fun startServerThread() {
        Thread {
            val status: JSONObject = try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                JSONObject(
                    Python.getInstance()
                        .getModule("server_runner")
                        .callAttr("start_server", filesDir.absolutePath)
                        .toString()
                )
            } catch (t: Throwable) {
                running = false
                lastError = t.message ?: t.toString()
                updateNotification(getString(R.string.notif_failed, shorten(lastError)))
                return@Thread
            }

            if (status.optBoolean("running")) {
                running = true
                baseUrl = status.optString("base_url")
                lastError = status.optString("error").takeIf { it.isNotEmpty() }
                acquireWakeLock()
                updateNotification(getString(R.string.notif_running, baseUrl))
            } else {
                running = false
                lastError = status.optString("error").ifEmpty { getString(R.string.error_unknown) }
                updateNotification(getString(R.string.notif_failed, shorten(lastError)))
            }
        }.start()
    }

    private fun shutdownServer() {
        running = false
        baseUrl = ""
        releaseWakeLock()
        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("server_runner").callAttr("stop_server")
            }
        } catch (_: Throwable) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geminiweb2api:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        shutdownServer()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MainService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_stop),
                stopIntent
            )
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun shorten(message: String?): String {
        val m = message ?: return ""
        return if (m.length > 300) m.take(300) + "…" else m
    }
}
