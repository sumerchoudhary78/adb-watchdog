package com.luffy.adbwatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = WatchdogService.prefs(context)
        if (prefs.getBoolean(WatchdogService.KEY_ENABLED, false)) {
            WatchdogService.start(context)
        }
    }
}
