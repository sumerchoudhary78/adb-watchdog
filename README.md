# ADB Watchdog (Android)

On-device watchdog for **wireless ADB** on Realme Narzo 50 (ColorOS). Watches
`Settings.Global.adb_wifi_enabled` through system events and a 30-second polling
fallback; when it goes to 0, tries to flip it back
to 1 via the **secure-settings** path, and falls back to an **AccessibilityService**
that drives the Settings UI if the flag write isn't honored.

## Vision

Make wireless debugging reliable for people developing and testing on their own
Android phones. ADB Watchdog keeps recovery on the device, shows what it is doing,
and lets the phone owner choose when it runs. The project stays focused on
keeping wireless ADB available with clear permissions and inspectable source code.

## Privacy: what stays on your phone

**ADB Watchdog does not upload your personal data to us.** The current app has no
account system, analytics, advertising SDK, telemetry, or cloud backend.

- **Local processing:** checking the ADB setting and recovering it happen on the
  phone. The app does not request contacts, photos, camera, microphone, or location
  permissions.
- **Local storage:** app preferences hold the enabled state, language, polling
  interval, recovery/failure counts, and heartbeat/restart timestamps. Recovery
  diagnostics are written to Android's local logs; the app does not upload them.
- **Local network discovery:** while the app screen is open, Android's mDNS
  service discovers wireless-debugging services on the Wi-Fi network. The app
  displays an endpoint only when its address belongs to this phone. This sends
  local discovery traffic; there is no developer-server upload endpoint in the
  app. The displayed address is held in memory and cleared when the screen closes.
- **Clipboard:** tapping the grant command copies that command to the clipboard.
  The app does not read your existing clipboard contents.

These statements describe this repository's current app code. You can inspect
[network discovery and clipboard handling](app/src/main/kotlin/com/luffy/adbwatchdog/MainActivity.kt),
[local preferences and recovery triggers](app/src/main/kotlin/com/luffy/adbwatchdog/WatchdogService.kt),
and the [declared permissions](app/src/main/AndroidManifest.xml).

## How it uses your phone

1. You enable the watchdog. It runs a foreground service with a status notification.
2. It watches Wi-Fi connectivity and the wireless-debugging setting, with a
   30-second polling fallback. Recovery pauses when Wi-Fi is disconnected.
3. If wireless debugging turns off, it tries to restore the setting using the
   permission you granted. If that fails, the optional Accessibility fallback
   navigates the Settings UI.
4. While enabled, alarms try to restart the service if Android kills it; it can
   also restart after a reboot or app update. Swiping the app away can schedule
   a restart, so use the watchdog switch to stop it.

### Why these permissions are present

| Permission or capability | How the current app uses it |
|---|---|
| `WRITE_SECURE_SETTINGS` | Restore `adb_wifi_enabled`; granted explicitly through ADB. This is a powerful system-setting permission. |
| Optional Accessibility service | Read active-window UI elements and perform taps/swipes to find and enable wireless debugging in Settings. |
| `INTERNET` and `ACCESS_NETWORK_STATE` | Use Android's local mDNS discovery and check Wi-Fi connectivity. |
| Foreground service and notifications | Keep the watchdog running and show its status. |
| Boot receiver and alarms | Restart the enabled watchdog after boot, updates, or service termination. |
| Wake lock and battery-optimization exemption | Support recovery while the screen is off and reduce background termination. |

**Accessibility has broad access:** Android allows this service to inspect the
active window and perform gestures. During recovery it temporarily reads visible
UI text locally in memory for the recovery attempt; that text is not saved or
uploaded. It can wake the screen, dismiss a swipe-only lockscreen, navigate
Settings, and confirm a
wireless-debugging prompt. It cannot unlock a PIN, pattern, or password. See the
[Accessibility implementation](app/src/main/kotlin/com/luffy/adbwatchdog/WatchdogAccessibilityService.kt).

### Your control

Turn **Watchdog enabled** off to stop the service and cancel its restart alarm.
This does not turn wireless debugging off: disable it separately in Android
Settings when you want to end ADB access. While the watchdog is enabled, it can
undo a manual change that turns wireless debugging off.

You choose which computers to pair through Android's wireless-debugging controls.
This app does not pair computers or provide the maintainers with remote access.
Commands and data exchanged with computers you authorize through ADB are separate
from the watchdog's local recovery work. You can disable its Accessibility service
in Android Settings and revoke its secure-settings permission with:

```sh
adb shell pm revoke com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
```

## Why two recovery paths

The primary path writes `Settings.Global.adb_wifi_enabled = 1` directly. It
requires `WRITE_SECURE_SETTINGS` which a normal app cannot request through
the runtime-permission dialog — it must be granted once via ADB:

```sh
adb shell pm grant com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
```

This persists across reboots. After that, recovery is instant and silent.

On some ColorOS builds the flag write is accepted but `AdbService` ignores it
(no observable side effect). The AccessibilityService is the fallback for that
case: it opens **Developer Options** via intent, finds the "Wireless debugging"
row, and toggles the switch. Slower (~3–5s), no permission grant needed, but
fragile against Settings UI changes.

## Build

Requires JDK 17 and Android SDK 34. From this directory:

```sh
# If you don't have the gradle wrapper yet:
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just open the directory in Android Studio and let it import.

### Download and release signing

Download `adb-watchdog-latest.apk` from the [latest release](https://github.com/sumerchoudhary78/adb-watchdog/releases/latest).
Release APKs use a private signing key stored in GitHub Actions secrets; local
debug builds use Android's generated debug key. Pull-request builds do not
receive the private signing credentials.

The old fixed debug signing key was publicly exposed and is no longer used.
If your installed APK uses that key or another older key, uninstall it first
(this removes app data), install the new release, and grant
`WRITE_SECURE_SETTINGS` again. Future releases using the new key can update
in place.

Release certificate SHA-256:
`71cabefdfb51c81a15e6f34c93390229d963242a1c1df35bafe885bc0feb8ca5`.

## One-time setup on the phone

1. Install the APK.
2. Open **ADB Watchdog**, flip "Watchdog enabled", and allow notifications.
3. If using the UI fallback, tap **Open Accessibility settings** → enable
   **ADB Watchdog (UI fallback)**. This button is shown before
   `WRITE_SECURE_SETTINGS` is granted.
4. Tap **Disable battery optimization** → choose "Don't optimize". Required —
   ColorOS will kill the foreground service otherwise.
5. From your PC, with USB ADB connected once:

   ```sh
   adb shell pm grant com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
   ```

6. Tap **Test recovery now**. The status panel should show `Wireless ADB: ON`.

## ColorOS keepalive checklist

ColorOS aggressively kills background services. Do all of these or the
watchdog will silently die:

- **Settings → Battery → ADB Watchdog → Allow background activity** (or
  whatever ColorOS calls it on your build) → enable.
- **Settings → Battery → ADB Watchdog → Auto-launch** → enable.
- **Phone Manager → Privacy permissions → Startup manager** → allow ADB
  Watchdog.
- In Recents, swipe down on the ADB Watchdog card to **lock** it. Survives
  "clear all".

If the foreground notification disappears, the OS killed the service. Re-open
the app to restart it.

## What it does NOT do

- It does not re-pair an ADB host. Pairing persists on the phone side; once
  wireless ADB is re-enabled, a host that was previously paired and is on the
  same Wi-Fi can `adb connect <phone-ip>:<port>` again. The port is
  randomized per session on Android 11+ — you may want mDNS discovery
  (`adb mdns services`) on the host side.
- It does not unlock a PIN/pattern/password lockscreen. This phone is swipe-only
  per the spec.
- It does not survive a factory reset (you'd need to re-grant
  `WRITE_SECURE_SETTINGS`).

## File layout

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/luffy/adbwatchdog/
│   ├── AdbWifiController.kt      # read/write the secure setting + dispatch fallback
│   ├── WatchdogService.kt        # foreground service with event triggers + poll loop
│   ├── ReviveReceiver.kt         # restart enabled watchdog via alarm
│   ├── WatchdogAccessibilityService.kt  # UI fallback
│   ├── BootReceiver.kt           # restart on boot / app update if enabled
│   └── MainActivity.kt           # status UI + toggle
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,themes}.xml
    └── xml/accessibility_service_config.xml
```
