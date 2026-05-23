package com.luffy.adbwatchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WatchdogService : LifecycleService() {

    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification(state = "starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val prefs = prefs(this)
        while (true) {
            val intervalMs = prefs.getInt(KEY_INTERVAL_S, DEFAULT_INTERVAL_S).coerceAtLeast(2) * 1000L
            val enabled = AdbWifiController.isEnabled(this)

            if (!enabled) {
                Log.i(TAG, "Wireless ADB is OFF — attempting recovery")
                updateNotification("recovering")
                val outcome = AdbWifiController.recover(this)
                if (outcome != null) {
                    Log.i(TAG, "Recovered via $outcome")
                    incrementCounter(prefs, KEY_RECOVERIES)
                } else {
                    Log.w(TAG, "Recovery failed")
                    incrementCounter(prefs, KEY_FAILURES)
                }
            }

            updateNotification(if (AdbWifiController.isEnabled(this)) "up" else "down")
            delay(intervalMs)
        }
    }

    private fun incrementCounter(prefs: SharedPreferences, key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun updateNotification(state: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(state: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADB Watchdog")
            .setContentText("Wireless ADB: $state")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "ADB Watchdog", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val TAG = "AdbWatchdog"
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIF_ID = 1
        const val PREFS = "watchdog_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL_S = "interval_s"
        const val KEY_RECOVERIES = "recoveries"
        const val KEY_FAILURES = "failures"
        const val DEFAULT_INTERVAL_S = 10

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun start(ctx: Context) {
            val intent = Intent(ctx, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchdogService::class.java))
        }
    }
}
