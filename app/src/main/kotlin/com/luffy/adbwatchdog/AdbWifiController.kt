package com.luffy.adbwatchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

object AdbWifiController {
    private const val TAG = "AdbWatchdog"
    private const val KEY = "adb_wifi_enabled"
    private const val SECURE_SETTINGS_PERM = "android.permission.WRITE_SECURE_SETTINGS"

    fun isEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, KEY, 0) == 1

    fun hasSecureSettingsPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(SECURE_SETTINGS_PERM) == PackageManager.PERMISSION_GRANTED

    /**
     * Try to enable wireless ADB. Returns the strategy that succeeded, or null if both failed.
     */
    suspend fun recover(ctx: Context): Strategy? {
        if (isEnabled(ctx)) return Strategy.AlreadyOn

        if (hasSecureSettingsPermission(ctx)) {
            val ok = runCatching {
                Settings.Global.putInt(ctx.contentResolver, KEY, 1)
            }.isSuccess

            if (ok) {
                // Give AdbService a moment to react to the flag flip.
                repeat(10) {
                    delay(200)
                    if (isEnabled(ctx)) return Strategy.SecureSettings
                }
                Log.w(TAG, "Secure settings write succeeded but state did not flip; falling back to UI.")
            } else {
                Log.w(TAG, "Secure settings write threw; falling back to UI.")
            }
        } else {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; using UI fallback.")
        }

        // Fallback: signal the accessibility service.
        ctx.sendBroadcast(Intent(ACTION_RECOVER).setPackage(ctx.packageName))

        // Wait up to ~10s for the UI fallback to take effect.
        repeat(50) {
            delay(200)
            if (isEnabled(ctx)) return Strategy.Accessibility
        }

        return null
    }

    enum class Strategy { AlreadyOn, SecureSettings, Accessibility }

    const val ACTION_RECOVER = "com.luffy.adbwatchdog.RECOVER"
}
