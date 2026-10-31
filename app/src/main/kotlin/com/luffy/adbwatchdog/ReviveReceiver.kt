package com.luffy.adbwatchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 定时复活链：每 15 分钟醒一次，确保看门狗服务在跑。
 *
 * 国产 ROM（MagicOS/ColorOS 等）会在息屏后杀掉前台服务，START_STICKY 不一定
 * 能及时恢复。这里用 AlarmManager 的精确闹钟做兜底：闹钟广播属于 Android 12+
 * 后台启动前台服务的豁免场景，因此可以在服务被杀后把它拉回来。
 * 拿不到精确闹钟权限时自动退化为 setAndAllowWhileIdle（不精确但能响）。
 */
class ReviveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = WatchdogService.prefs(context)
        if (!prefs.getBoolean(WatchdogService.KEY_ENABLED, false)) return

        Log.i(TAG, "Revive tick — ensuring watchdog service is running")
        prefs.edit().putLong(WatchdogService.KEY_LAST_REVIVE, System.currentTimeMillis()).apply()
        runCatching { WatchdogService.start(context) }
            .onFailure { Log.w(TAG, "Foreground service start rejected by system", it) }

        // 无论这次拉起成功与否，都把下一跳排上，持续重试。
        schedule(context, INTERVAL_MS)
    }

    companion object {
        private const val TAG = "AdbWatchdog"
        private const val REQUEST_CODE = 100
        // 10 分钟一跳：更短的停摆窗口；唤醒开销很小（一次广播 + 服务启动）
        const val INTERVAL_MS = 10 * 60 * 1000L

        /** 排下一次复活闹钟；开关未启用时直接取消。 */
        fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            if (!WatchdogService.prefs(context).getBoolean(WatchdogService.KEY_ENABLED, false)) {
                cancel(context)
                return
            }
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val pi = pendingIntent(context)
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }.onFailure { Log.w(TAG, "Failed to schedule revive alarm", it) }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { am.cancel(pendingIntent(context)) }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ReviveReceiver::class.java)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
