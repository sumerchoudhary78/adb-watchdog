# ADB 看门狗（Android）

[English](README.md) | 简体中文

本设备端看门狗用于保住**无线调试**（wireless ADB）：默认跟随系统事件
（网络变化、`adb_wifi_enabled` 被改写）即时触发恢复，并以 30 秒轮询兜底；
优先通过**安全设置直写**把 `Settings.Global.adb_wifi_enabled` 翻回 1，
写入不被系统接受时回退到**无障碍服务**驱动设置界面去点开关。

## AI 改动说明（必读）

- 本仓库相对上游 [sumerchoudhary78/adb-watchdog](https://github.com/sumerchoudhary78/adb-watchdog)
  的**全部改动，均由 AI 编码助手按仓库所有者提出的具体需求完成**。
- **AI 仅实现人工明确提出的需求**：不自行扩展功能、不做需求之外的重构、
  不擅自更改包名与既有接口；每一轮改动都先给出文件清单与说明，经人工确认后再提交。
- APK 由 GitHub Actions 自动构建发布；代码**未经第三方安全审计**，按"尽力而为"提供，
  涉及系统设置与 ADB 权限，请自行评估风险。

### 版本历史（本 fork）

| 版本 | 主要改动 |
|---|---|
| v0.2.0 | 无 Wi-Fi 暂停、界面中英文切换、CI 自动发行 |
| v0.2.1 | 修复启动崩溃（补 `ACCESS_NETWORK_STATE`） |
| v0.2.2 | adb 授权命令可点击复制 |
| v0.2.3 | 状态面板显示无线调试 `IP:端口`（mDNS 自发现） |
| v0.2.4 | 修复启动崩溃（补 `INTERNET`，NSD 所需） |
| v0.2.5 | 通知渠道升级 + 运行时申请通知权限；复活链；服务心跳；覆盖安装后自动重启 |
| v0.2.6 | 打开界面即自愈；通知渠道级别重申；轮询改 30 秒、复活改 10 分钟；新增「上次自动复活」 |
| v0.2.7 / v0.2.8 | 固定签名：keystore 以 base64 形式随仓库提交，构建时解码 |
| v0.2.9 | 事件驱动恢复（网络回调 + 设置观察者），Wi-Fi 恢复后秒级触发 |
| v0.3.0 ~ v0.3.3 | 无障碍入口改为按需显示；新增「打开无线调试设置」按钮；标题栏显示版本号 |

## 当前功能

- **事件驱动恢复**：监听网络变化与 `adb_wifi_enabled`，秒级恢复；30 秒轮询兜底
- **无 Wi-Fi 自动暂停**：没连 Wi-Fi 时不写开关，通知栏显示「已暂停」
- **前台服务 + 常驻通知**：通知渠道为「默认」级别，可在「通知设置」里调整
- **复活链**：10 分钟精确闹钟，服务被杀后自动拉起；开机、覆盖安装后自动重启
- **状态面板**：无线调试 / 权限 / 无障碍 / Wi-Fi / 无线调试地址 / 通知权限 /
  服务心跳 / 上次自动复活 / 恢复与失败计数
- **双语文案**：中文 / English 切换，持久保存
- **授权辅助**：未授权时显示 adb 命令，点击即复制；未授权时显示无障碍入口
- **私有发行签名**：密钥保存在 GitHub Actions Secrets；本地调试使用独立的调试密钥

## 下载安装

到 [Releases 最新发行版](../../releases/latest) 下载 `adb-watchdog-latest.apk` 安装。

- 新发行证书 SHA-256：
  `71cabefdfb51c81a15e6f34c93390229d963242a1c1df35bafe885bc0feb8ca5`。
  只有已经使用此新密钥的版本才可直接覆盖安装。
- **所有旧签名版本（包括已泄露的固定调试密钥版本）**：先卸载旧版
  （会清除应用数据），再安装新版并重新授权。旧公开密钥签名的 APK 不再可信。

## 一次性设置

1. 安装 APK，打开 app，打开「看门狗开关」，允许通知
2. 电脑上执行一次授权（USB ADB 或任意可用 ADB 会话）：

   ```sh
   adb shell pm grant com.luffy.adbwatchdog android.permission.WRITE_SECURE_SETTINGS
   ```

3. 点「关闭电池优化」→ 选择"不允许优化"
4. 点「立即测试恢复」，状态面板应显示 `无线调试 : 开`，恢复计数 +1

> 没有 adb 条件时：不授权也可用，但恢复依赖无障碍——打开 app 点
> 「打开无障碍设置」启用 **ADB Watchdog (UI fallback)** 即可（恢复会慢几秒）。

## 国产 ROM 保活清单

**MagicOS（荣耀，实测 ELP-AN00 / Android 16）**

- 设置 → 应用 → 应用启动管理 → ADB 看门狗 → 手动管理：自启动 / 关联启动 / 后台活动 全开
- 设置 → 通知和状态栏 → ADB 看门狗 → 允许通知，并将「看门狗状态」渠道设为「默认」及以上
- 多任务界面下拉卡片加锁（防一键清理）
- 可选：开发者选项 → 快捷设置开发者图块 → 打开「无线调试」，下拉栏一键开关

**ColorOS（OPPO/一加/realme）**

- 设置 → 电池 → ADB Watchdog → 允许后台活动 / 自启动
- 手机管家 → 隐私权限 → 启动管理 → 允许

> 已实测（MagicOS）：安全设置直写路径有效；通知常驻；切 Wi-Fi 后 1~3 秒恢复。

## 它不做的事

- 不负责重新配对 ADB 主机；端口每次会话随机，电脑端可用 `adb mdns services` 发现
- 不能解锁 PIN/图案/密码锁屏
- 恢复出厂设置后授权失效，需重新授予 `WRITE_SECURE_SETTINGS`
- **手机重启后需手动开一次无线调试**（系统安全设计，无 root 无解），之后由看门狗接管
- 无法直达 MagicOS 的「无线调试」子页（系统没有对外暴露该页面，只能打开开发者选项）

## 构建

需要 JDK 17 与 Android SDK 34：

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 发行签名与迁移

发行 APK 使用新的私有密钥。GitHub Actions Secrets 保存
`ANDROID_KEYSTORE_BASE64`、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、
`ANDROID_KEY_PASSWORD`。CI 仅在临时目录还原密钥，核对
`.github/android-signing-certificate.sha256` 中的证书指纹，并清理临时密钥。
禁止提交密钥或其 Base64 编码。Pull Request 构建不接触签名凭据。

此前的调试私钥已公开，不能再使用；修改密码或删除仓库文件无法撤销泄露。
已安装旧签名 APK 的用户需要先卸载旧版（会清除应用数据），安装新版后重新授予
`WRITE_SECURE_SETTINGS`。仅从本仓库可信的发行页面下载安装包。

## 目录结构

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/luffy/adbwatchdog/
│   ├── AdbWifiController.kt      # 读写安全设置 + 分发兜底恢复（含 Wi-Fi 检测）
│   ├── WatchdogService.kt        # 前台服务：事件驱动恢复 + 轮询兜底 + 心跳
│   ├── ReviveReceiver.kt         # 10 分钟精确闹钟复活链
│   ├── WatchdogAccessibilityService.kt  # 界面兜底恢复（可选）
│   ├── BootReceiver.kt           # 开机 / 覆盖安装后重启
│   └── MainActivity.kt           # 状态面板 + 开关 + 语言切换 + 授权提示
└── res/
    ├── layout/activity_main.xml
    ├── values/{strings,themes}.xml        # 英文
    ├── values-zh/strings.xml              # 中文
    └── xml/accessibility_service_config.xml
```
