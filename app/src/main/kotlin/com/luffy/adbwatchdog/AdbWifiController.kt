package com.luffy.adbwatchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

object AdbWifiController {
    private const val TAG = "AdbWatchdog"
    const val KEY_ADB_WIFI = "adb_wifi_enabled"
    private const val SECURE_SETTINGS_PERM = "android.permission.WRITE_SECURE_SETTINGS"

    fun isEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, KEY_ADB_WIFI, 0) == 1

    fun hasSecureSettingsPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(SECURE_SETTINGS_PERM) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the phone currently has a Wi-Fi network as its active network.
     * Wireless debugging is bound to Wi-Fi; recovering while no Wi-Fi is
     * connected is pointless (the toggle would be forced on without a network
     * to serve), so callers should pause instead.
     *
     * Never throws: if anything goes wrong (missing permission on exotic ROMs,
     * etc.) we report "no Wi-Fi" so the watchdog pauses instead of crashing.
     */
    fun isWifiConnected(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /**
     * Try to enable wireless ADB. Returns the strategy that succeeded, or null if both failed.
     */
    suspend fun recover(ctx: Context): Strategy? {
        if (!isWifiConnected(ctx)) return Strategy.PausedNoWifi
        if (isEnabled(ctx)) return Strategy.AlreadyOn

        if (hasSecureSettingsPermission(ctx)) {
            val ok = runCatching {
                Settings.Global.putInt(ctx.contentResolver, KEY_ADB_WIFI, 1)
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

    enum class Strategy { AlreadyOn, SecureSettings, Accessibility, PausedNoWifi }

    const val ACTION_RECOVER = "com.luffy.adbwatchdog.RECOVER"
}
