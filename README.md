# ADB Watchdog (Android)

English | [简体中文](README_zh-CN.md)

On-device watchdog for **wireless ADB**. Recovery is event-driven by default
(network changes and rewrites of `adb_wifi_enabled`), with a 30-second poll as a
safety net: it flips `Settings.Global.adb_wifi_enabled` back to 1 via the
**secure-settings** path, and falls back to an **AccessibilityService** driving
the Settings UI when that write is not honored.

## AI-made changes (please read)

- **Every change in this fork**, relative to
  [sumerchoudhary78/adb-watchdog](https://github.com/sumerchoudhary78/adb-watchdog),
  **was made by an AI coding assistant** at the request of the repository owner.
- **The AI only implements explicitly requested requirements**: it does not add
  features on its own, does not refactor beyond scope, and does not rename the
  package or change existing interfaces. Each round of changes is presented as a
  file list with a short rationale and committed only after human confirmation.
- APKs are built by GitHub Actions. The code has **not been security-audited**
  and is provided as-is; it touches system settings and ADB permissions — use at
  your own discretion.

### Version history (this fork)

| Version | Highlights |
|---|---|
| v0.2.0 | Wi-Fi pause, in-app language toggle, CI auto-release |
| v0.2.1 | Fix launch crash (missing `ACCESS_NETWORK_STATE`) |
| v0.2.2 | Tap-to-copy for the adb grant command |
| v0.2.3 | Show wireless ADB `IP:port` via mDNS discovery |
| v0.2.4 | Fix launch crash (missing `INTERNET`, required by NSD) |
| v0.2.5 | Notification channel fix + runtime notification permission; revive chain; heartbeat; restart after app update |
| v0.2.6 | Heal on app open; re-assert channel importance; 30 s poll, 10 min revive; "last auto-revive" row |
| v0.2.7 / v0.2.8 | Fixed signing: keystore committed as base64 and decoded in CI |
| v0.2.9 | Event-driven recovery (network callback + settings observer) |
| v0.3.0 ~ v0.3.3 | Conditional accessibility entry; "Wireless debugging settings" button; version in the title bar |
| v0.3.4 | Replace the exposed debug signing key with private release signing and certificate verification |

## Features

- **Event-driven recovery** — reacts to network changes and to the flag being
  switched off; 30 s polling as a fallback
- **Wi-Fi pause** — no recovery attempts while no Wi-Fi is connected
- **Foreground service with a persistent notification** (channel at DEFAULT
  importance; adjust it via "Notification settings")
- **Revive chain** — a 10-minute exact alarm restarts the service if killed;
  also restarted on boot and after an app update
- **Status panel** — wireless ADB / permission / accessibility / Wi-Fi /
  endpoint / notification permission / heartbeat / last revive / counters
- **Bilingual UI** (中文 / English), persisted
- **Grant helper** — the adb command is tap-to-copy; the accessibility entry
  appears only while not granted
- **Private release signing** — release keys are stored in Actions secrets;
  normal debug builds use a separate, locally generated debug key

## Download & install

Grab `adb-watchdog-latest.apk` from the [latest release](../../releases/latest).

- Release certificate SHA-256:
  `71cabefdfb51c81a15e6f34c93390229d963242a1c1df35bafe885bc0feb8ca5`.
  APKs already using this new key can be updated in place.
- **Migration from any previous key, including the exposed fixed debug key:**
  uninstall the old app first (removes app data), install the new release,
  and grant the permission again. A matching fingerprint identifies this new
  signing key; it does not make APKs signed with the old public key trustworthy.

## One-time setup

1. Install the APK, open the app, enable **Watchdog enabled**, allow notifications
2. Grant the permission once from a PC (USB ADB or any active ADB session):

   ```sh
   adb shell pm grant com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
   ```

3. Tap **Disable battery optimization** → "Don't optimize"
4. Tap **Test recovery now** — the panel should show `Wireless ADB: ON` and the
   recovery counter should tick up

> Without ADB: the app still works, but recovery relies on the accessibility
> service — enable **ADB Watchdog (UI fallback)** in Accessibility settings
> (recovery takes a few seconds).

## Keepalive checklist (Chinese ROMs)

**MagicOS (HONOR, tested on ELP-AN00 / Android 16)**

- Settings → Apps → App launch → ADB Watchdog → Manage manually: enable
  Auto-launch / Secondary launch / Run in background
- Settings → Notifications → ADB Watchdog → allow notifications, set the
  "watchdog status" channel to Default or higher
- Lock the app card in Recents
- Optional: Developer options → Quick settings developer tiles → enable
  "Wireless debugging" for a one-tap toggle in the shade

**ColorOS (OPPO/OnePlus/realme)**

- Settings → Battery → ADB Watchdog → Allow background activity / Auto-launch
- Phone Manager → Privacy permissions → Startup manager → allow

> Measured on MagicOS: the secure-settings path works; the notification stays
> visible; recovery after Wi-Fi changes takes 1–3 seconds.

## What it does NOT do

- It does not re-pair an ADB host; the port is randomized per session — use
  `adb mdns services` on the host to find it
- It does not unlock a PIN/pattern/password lockscreen
- It does not survive a factory reset (re-grant `WRITE_SECURE_SETTINGS`)
- **After a reboot, wireless debugging must be enabled once manually** (system
  design); the watchdog takes over from there
- It cannot deep-link into MagicOS's "Wireless debugging" page (not exposed by
  the OS; only Developer options can be opened)

## Build

Requires JDK 17 and Android SDK 34. For local development (debug key):

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release signing and migration

Release APKs are signed with a new private key supplied through GitHub Actions
secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD`. CI restores the keystore only in its temporary
directory, verifies the APK certificate against
`.github/android-signing-certificate.sha256`, and removes the temporary key.
Never commit a keystore or its base64 encoding.

The previous debug signing key was publicly exposed and must never be reused.
Users of an APK signed with that key must uninstall it before installing the
new release (this removes app data), then re-grant `WRITE_SECURE_SETTINGS`.
Changing the key password or deleting the committed file does not repair the
old key. Only install APKs from this repository's trusted release page.

Pull requests build without private signing credentials. Trusted branch pushes
build signed release artifacts; only `main` publishes a GitHub release.
For an explicitly signed local release, set the four `ANDROID_*` variables
above, replacing `ANDROID_KEYSTORE_BASE64` with `ANDROID_KEYSTORE_PATH`, and run
`./gradlew :app:assembleRelease`. The file path must point outside this repository.

## File layout

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/luffy/adbwatchdog/
│   ├── AdbWifiController.kt      # read/write the secure setting + dispatch fallback
│   ├── WatchdogService.kt        # foreground service: event-driven + poll + heartbeat
│   ├── ReviveReceiver.kt         # 10-minute exact-alarm revive chain
│   ├── WatchdogAccessibilityService.kt  # optional UI fallback
│   ├── BootReceiver.kt           # restart on boot / after update
│   └── MainActivity.kt           # status panel + toggle + language + grant helper
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,themes}.xml        # English
    ├── values-zh/strings.xml              # Chinese
    └── xml/accessibility_service_config.xml
```
