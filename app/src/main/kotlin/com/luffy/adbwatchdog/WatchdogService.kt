package com.luffy.adbwatchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WatchdogService : LifecycleService() {

    private var pollJob: Job? = null
    private var recoveryJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var settingsObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification(R.string.notif_starting))
        registerTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { pollLoop() }
        }
        // 每次服务被拉起（包括被系统杀掉后的 START_STICKY 重启、复活链拉起），
        // 都把下一跳复活闹钟续上，形成自维持链条。
        ReviveReceiver.schedule(this)
        // 重新声明通知渠道级别：Honor 等 ROM 会把渠道自动降级为 LOW，
        // 未被用户锁定的字段可通过重复 create 恢复。
        createChannel()
        return START_STICKY
    }

    /**
     * 用户从最近任务里把 app 划掉时触发。此时进程可能很快被杀，
     * 立刻排一个 5 秒后的复活闹钟，让服务自己爬回来。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs(this).getBoolean(KEY_ENABLED, false)) {
            ReviveReceiver.schedule(this, 5_000L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        recoveryJob?.cancel()
        unregisterTriggers()
        super.onDestroy()
    }

    /**
     * 事件驱动触发器：比 30 秒轮询快得多。
     * ① 网络回调：Wi-Fi 连上/能力变化 → 立刻尝试恢复；
     * ② 设置观察者：系统把 adb_wifi_enabled 改成 0 的瞬间 → 立刻恢复。
     */
    private fun registerTriggers() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (AdbWifiController.isWifiConnected(this@WatchdogService)) {
                        maybeRecover("network available")
                    }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        maybeRecover("wifi capable")
                    }
                }

                override fun onLost(network: Network) {
                    if (!AdbWifiController.isWifiConnected(this@WatchdogService)) {
                        updateNotification(R.string.notif_paused_no_wifi)
                    }
                }
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }
                .onSuccess { netCallback = cb }
                .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
        }
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // 只关心"被关掉"这种情况；我们自己写 1 也会触发，但那时 isEnabled 为 true，不会重复恢复
                if (!AdbWifiController.isEnabled(this@WatchdogService)) {
                    maybeRecover("settings changed")
                }
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(AdbWifiController.KEY_ADB_WIFI), false, obs
            )
        }.onSuccess { settingsObserver = obs }
            .onFailure { Log.w(TAG, "registerContentObserver failed", it) }
    }

    private fun unregisterTriggers() {
        netCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        settingsObserver?.let { o -> runCatching { contentResolver.unregisterContentObserver(o) } }
        settingsObserver = null
    }

    /** 合并触发：同一时间只跑一个恢复任务，内部重试 3 次（每次间隔 1.5 秒）。 */
    private fun maybeRecover(reason: String) {
        if (recoveryJob?.isActive == true) return
        recoveryJob = lifecycleScope.launch {
            Log.i(TAG, "Recovery trigger: $reason")
            val p = prefs(this@WatchdogService)
            delay(500) // 给系统一点时间（Wi-Fi 握手 / 设置写入落地）
            repeat(3) { attempt ->
                if (!p.getBoolean(KEY_ENABLED, false)) return@launch
                if (AdbWifiController.isEnabled(this@WatchdogService)) {
                    updateNotification(R.string.notif_up)
                    return@launch
                }
                if (!AdbWifiController.isWifiConnected(this@WatchdogService)) {
                    updateNotification(R.string.notif_paused_no_wifi)
                    return@launch
                }
                updateNotification(R.string.notif_recovering)
                val outcome = AdbWifiController.recover(this@WatchdogService)
                if (outcome != null && outcome != AdbWifiController.Strategy.PausedNoWifi) {
                    Log.i(TAG, "Recovered via $outcome (attempt ${attempt + 1})")
                    incrementCounter(p, KEY_RECOVERIES)
                    updateNotification(R.string.notif_up)
                    return@launch
                }
                Log.w(TAG, "Recovery attempt ${attempt + 1} failed")
                delay(1500)
            }
            incrementCounter(p, KEY_FAILURES)
            updateNotification(R.string.notif_down)
        }
    }

    private suspend fun pollLoop() {
        val prefs = prefs(this)
        while (true) {
            val intervalMs = prefs.getInt(KEY_INTERVAL_S, DEFAULT_INTERVAL_S).coerceAtLeast(2) * 1000L
            // 心跳：主界面据此判断服务是否还活着
            prefs.edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()

            if (!AdbWifiController.isEnabled(this)) {
                if (AdbWifiController.isWifiConnected(this)) {
                    // 兜底轮询：即使网络回调/设置观察者漏了事件，最多 30 秒也会补一次
                    maybeRecover("poll")
                } else {
                    Log.i(TAG, "Wireless ADB is OFF but Wi-Fi is disconnected — recovery paused")
                    updateNotification(R.string.notif_paused_no_wifi)
                }
            } else {
                updateNotification(R.string.notif_up)
            }
            delay(intervalMs)
        }
    }

    private fun incrementCounter(prefs: SharedPreferences, key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun updateNotification(stateRes: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        // 没有通知权限时 notify 可能被拒，包一层避免任何意外
        runCatching { nm.notify(NOTIF_ID, buildNotification(stateRes)) }
    }

    private fun buildNotification(stateRes: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(stateRes))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android 12+：前台服务通知立即显示，不等待系统弹窗延迟
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // 注意：这里不做"已存在就跳过"判断——重复 create 是无害的，
        // 且能把被系统/ROM 自动降级的字段（如 importance）恢复回来。
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            // DEFAULT（而非 LOW）：LOW 会被系统折叠进"静默通知"区域，
            // MagicOS 甚至会直接隐藏，这是之前"通知栏看不到状态"的主因。
            NotificationManager.IMPORTANCE_DEFAULT
        )
        ch.setSound(null, null)          // 常驻通知不响铃
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        runCatching { nm.createNotificationChannel(ch) }
    }

    companion object {
        private const val TAG = "AdbWatchdog"
        // v2：旧渠道以 LOW 重要级别创建过，改级别不会生效，因此换新 ID
        private const val CHANNEL_ID = "watchdog_v2"
        private const val NOTIF_ID = 1
        const val PREFS = "watchdog_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL_S = "interval_s"
        const val KEY_RECOVERIES = "recoveries"
        const val KEY_FAILURES = "failures"
        const val KEY_LANG = "lang"
        const val KEY_HEARTBEAT = "last_heartbeat"
        const val KEY_LAST_REVIVE = "last_revive"
        // 30 秒轮询：对 10 秒一次的高频唤醒更省电，减少被 ROM 判为"异常耗电"而打压
        const val DEFAULT_INTERVAL_S = 30

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
