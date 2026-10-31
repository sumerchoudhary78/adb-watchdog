package com.luffy.adbwatchdog

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.luffy.adbwatchdog.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var statusJob: Job? = null

    // Wireless debugging endpoint discovered via mDNS (_adb-tls-connect._tcp).
    @Volatile private var adbEndpoint: String? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var resolving = false
    @Volatile private var nsdBroken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore the language chosen via the toggle button (persists across launches).
        val savedLang = WatchdogService.prefs(this).getString(WatchdogService.KEY_LANG, null)
        if (savedLang == "zh") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        } else if (savedLang == "en") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 标题栏显示版本号
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        supportActionBar?.title =
            if (versionName.isBlank()) getString(R.string.app_name)
            else "${getString(R.string.app_name)} v$versionName"

        val prefs = WatchdogService.prefs(this)

        binding.enableSwitch.isChecked = prefs.getBoolean(WatchdogService.KEY_ENABLED, false)
        binding.enableSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(WatchdogService.KEY_ENABLED, on).apply()
            if (on) {
                WatchdogService.start(this)
                ReviveReceiver.schedule(this)
            } else {
                WatchdogService.stop(this)
                ReviveReceiver.cancel(this)
            }
        }

        binding.btnWirelessDebugging.setOnClickListener {
            // 能直达子页的 ROM（AOSP/Pixel）用 show_fragment；MagicOS 等会落在开发者选项顶部，
            // 此时用 Toast 指引用户下滑查找——这是 ROM 限制，没有更深的入口可用。
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(
                    ":settings:show_fragment",
                    "com.android.settings.development.WirelessDebuggingFragment"
                )
            val ok = runCatching { startActivity(intent) }.isSuccess
            if (!ok) {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            Toast.makeText(this, R.string.wd_hint, Toast.LENGTH_LONG).show()
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnBattery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        binding.btnNotifications.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        binding.btnTest.setOnClickListener {
            lifecycleScope.launch {
                val s = AdbWifiController.recover(this@MainActivity)
                refreshStatus(extra = getString(R.string.manual_recover_fmt, s?.toString() ?: "null"))
            }
        }

        binding.btnLanguage.setOnClickListener { toggleLanguage() }

        // Tap the command box to copy the adb grant command.
        binding.grantCommand.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("adb", binding.grantCommand.text))
            Toast.makeText(this, R.string.copy_toast_ok, Toast.LENGTH_SHORT).show()
        }

        // Android 13+：常驻通知需要运行时授权，否则通知栏里看不到守护状态
        if (Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) refreshStatus()
    }

    /**
     * Toggle the app language between Chinese and English and persist the choice.
     * setApplicationLocales recreates the activity automatically.
     */
    private fun toggleLanguage() {
        val prefs = WatchdogService.prefs(this)
        val current = AppCompatDelegate.getApplicationLocales()
        val currentIsZh = if (!current.isEmpty) {
            current[0]?.language == "zh"
        } else {
            resources.configuration.locales[0].language == "zh"
        }
        val target = if (currentIsZh) "en" else "zh"
        prefs.edit().putString(WatchdogService.KEY_LANG, target).apply()
        AppCompatDelegate.setApplicationLocales(
            if (target == "zh") LocaleListCompat.forLanguageTags("zh-CN")
            else LocaleListCompat.forLanguageTags("en")
        )
    }

    override fun onStart() {
        super.onStart()
        // 打开界面即自愈：若服务已被系统杀掉（进程可能还在），立刻重启并续排复活链
        val prefs = WatchdogService.prefs(this)
        if (prefs.getBoolean(WatchdogService.KEY_ENABLED, false)) {
            WatchdogService.start(this)
            ReviveReceiver.schedule(this)
        }
        statusJob = lifecycleScope.launch {
            while (true) {
                refreshStatus()
                delay(1000)
            }
        }
        startNsdDiscovery()
    }

    override fun onStop() {
        statusJob?.cancel()
        stopNsdDiscovery()
        super.onStop()
    }

    /**
     * Discover this device's own wireless-debugging endpoint (IP:port) via the
     * system mDNS daemon. The service type is _adb-tls-connect._tcp; results
     * whose resolved host is not one of this device's own IPv4 addresses are
     * ignored (other adb devices on the LAN advertise the same type).
     * Everything is wrapped defensively: any failure just leaves the row
     * showing "-", never a crash.
     */
    private fun startNsdDiscovery() {
        if (discoveryListener != null || nsdBroken) return
        // getSystemService(NsdManager) itself can throw SecurityException on
        // ROMs that gate NSD behind INTERNET — never let that crash the app.
        val nm = try { getSystemService(NsdManager::class.java) } catch (t: Throwable) { null }
        if (nm == null) { nsdBroken = true; return }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null || resolving) return
                resolving = true
                nm.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        resolving = false
                    }

                    override fun onServiceResolved(info: NsdServiceInfo?) {
                        resolving = false
                        if (info == null) return
                        val host = info.hostAddresses.firstOrNull()?.hostAddress
                        val port = info.port
                        if (host.isNullOrBlank() || port <= 0) return
                        if (host !in localAddresses()) return
                        adbEndpoint = "$host:$port"
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                adbEndpoint = null
                resolving = false
            }

            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                discoveryListener = null
                nsdBroken = true
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                discoveryListener = null
            }
        }
        discoveryListener = listener
        try {
            nm.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            discoveryListener = null
            nsdBroken = true
        }
    }

    private fun stopNsdDiscovery() {
        val l = discoveryListener ?: return
        runCatching { getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) }
        discoveryListener = null
        adbEndpoint = null
        resolving = false
    }

    /** This device's own IPv4 addresses, used to reject foreign adb services. */
    private fun localAddresses(): Set<String> = runCatching {
        val out = mutableSetOf<String>()
        val en = NetworkInterface.getNetworkInterfaces() ?: return@runCatching out
        for (nif in en) {
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { out.add(it) }
                }
            }
        }
        out
    }.getOrDefault(emptySet())

    private fun refreshStatus(extra: String? = null) {
        val prefs = WatchdogService.prefs(this)
        val adbOn = AdbWifiController.isEnabled(this)
        val granted = AdbWifiController.hasSecureSettingsPermission(this)
        val a11y = WatchdogAccessibilityService.isBound()
        val wifi = AdbWifiController.isWifiConnected(this)
        val recoveries = prefs.getInt(WatchdogService.KEY_RECOVERIES, 0)
        val failures = prefs.getInt(WatchdogService.KEY_FAILURES, 0)
        val ep = adbEndpoint
        val notifOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val hb = prefs.getLong(WatchdogService.KEY_HEARTBEAT, 0L)
        val hbSec = if (hb <= 0L) -1 else ((System.currentTimeMillis() - hb) / 1000).toInt()
        val lastRevive = prefs.getLong(WatchdogService.KEY_LAST_REVIVE, 0L)

        binding.status.text = buildString {
            append(getString(R.string.status_wireless)).append(" : ")
                .append(getString(if (adbOn) R.string.val_on else R.string.val_off)).append('\n')
            append(getString(R.string.status_perm)).append(" : ")
                .append(getString(if (granted) R.string.val_granted else R.string.val_not_granted)).append('\n')
            // 无障碍状态常显（仅作信息展示；对应按钮只在未授权时出现）
            append(getString(R.string.status_a11y)).append(" : ")
                .append(getString(if (a11y) R.string.val_bound else R.string.val_not_bound)).append('\n')
            append(getString(R.string.status_wifi)).append(" : ")
                .append(getString(if (wifi) R.string.val_connected else R.string.val_disconnected)).append('\n')
            append(getString(R.string.status_endpoint)).append(" : ").append(
                when {
                    !wifi || !adbOn || nsdBroken -> "-"
                    ep != null -> ep
                    else -> getString(R.string.val_searching)
                }
            ).append('\n')
            append(getString(R.string.status_notif)).append(" : ")
                .append(getString(if (notifOn) R.string.val_allowed else R.string.val_denied)).append('\n')
            append(getString(R.string.status_heartbeat)).append(" : ").append(
                when {
                    hbSec < 0 -> getString(R.string.val_never)
                    hbSec <= 90 -> getString(R.string.hb_seconds_fmt, hbSec)
                    else -> getString(R.string.hb_stale_fmt, hbSec)
                }
            ).append('\n')
            append(getString(R.string.status_revive)).append(" : ").append(
                if (lastRevive <= 0L) getString(R.string.val_never)
                else getString(R.string.minutes_fmt, ((System.currentTimeMillis() - lastRevive) / 60000).toInt())
            ).append('\n')
            append(getString(R.string.status_counters)).append(" : ")
                .append(recoveries).append(" / ").append(failures)
            if (extra != null) append('\n').append(extra)
        }

        binding.grantHint.text = if (granted) {
            getString(R.string.hint_granted)
        } else {
            getString(R.string.hint_not_granted)
        }
        binding.grantCommand.isVisible = !granted
        if (!granted) {
            binding.grantCommand.text = getString(R.string.grant_cmd_fmt, packageName)
        }
        // 未授权时显示无障碍入口（无 adb 场景的唯一恢复路径）；已授权则隐藏
        binding.btnAccessibility.isVisible = !granted
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1001
    }
}
