package com.luffy.adbwatchdog

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.luffy.adbwatchdog.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = WatchdogService.prefs(this)

        binding.enableSwitch.isChecked = prefs.getBoolean(WatchdogService.KEY_ENABLED, false)
        binding.enableSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(WatchdogService.KEY_ENABLED, on).apply()
            if (on) WatchdogService.start(this) else WatchdogService.stop(this)
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

        binding.btnTest.setOnClickListener {
            lifecycleScope.launch {
                val s = AdbWifiController.recover(this@MainActivity)
                refreshStatus(extra = "manual recover -> $s")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        statusJob = lifecycleScope.launch {
            while (true) {
                refreshStatus()
                delay(1000)
            }
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        super.onStop()
    }

    private fun refreshStatus(extra: String? = null) {
        val prefs = WatchdogService.prefs(this)
        val adbOn = AdbWifiController.isEnabled(this)
        val granted = AdbWifiController.hasSecureSettingsPermission(this)
        val a11y = WatchdogAccessibilityService.isBound()
        val recoveries = prefs.getInt(WatchdogService.KEY_RECOVERIES, 0)
        val failures = prefs.getInt(WatchdogService.KEY_FAILURES, 0)

        binding.status.text = buildString {
            append("Wireless ADB        : ").append(if (adbOn) "ON" else "OFF").append('\n')
            append("WRITE_SECURE_SETTINGS: ").append(if (granted) "granted" else "NOT granted").append('\n')
            append("AccessibilityService : ").append(if (a11y) "bound" else "not bound").append('\n')
            append("Recoveries / failures: ").append(recoveries).append(" / ").append(failures)
            if (extra != null) append('\n').append(extra)
        }

        binding.grantHint.text = if (granted) {
            "Secure-settings path is active — recovery should be instant."
        } else {
            "Run on your PC once (USB ADB or any active ADB session):\n" +
                "  adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n" +
                "After that, the watchdog can re-enable wireless ADB without UI navigation."
        }
    }
}
