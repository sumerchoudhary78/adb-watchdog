package com.luffy.adbwatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = WatchdogService.prefs(context)
        if (prefs.getBoolean(WatchdogService.KEY_ENABLED, false)) {
            WatchdogService.start(context)
            // 开机 / 应用更新后立即排上复活链，60 秒后第一次自检
            ReviveReceiver.schedule(context, 60_000L)
        }
    }
}
