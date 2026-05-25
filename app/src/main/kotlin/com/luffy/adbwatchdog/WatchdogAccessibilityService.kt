package com.luffy.adbwatchdog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI fallback recovery. Only used when WRITE_SECURE_SETTINGS is not granted, or
 * the secure-settings write was accepted but the AdbService did not honor it
 * (some ColorOS builds).
 *
 * Flow: launch Developer Options → find "Wireless debugging" row → click its switch.
 */
class WatchdogAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runningJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AdbWifiController.ACTION_RECOVER) {
                triggerRecovery()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound = this
        val filter = IntentFilter(AdbWifiController.ACTION_RECOVER)
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        bound = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    private fun triggerRecovery() {
        if (runningJob?.isActive == true) {
            Log.i(TAG, "Recovery already running, ignoring trigger")
            return
        }
        runningJob = scope.launch { runRecovery() }
    }

    private suspend fun runRecovery() {
        Log.i(TAG, "Starting UI recovery")

        val wakeLock = wakeScreenIfOff()
        try {
            dismissSwipeKeyguardIfNeeded()
            performGlobalAction(GLOBAL_ACTION_HOME)
            delay(500)
            runRecoveryInner()
        } finally {
            runCatching { wakeLock?.release() }
        }
    }

    private suspend fun runRecoveryInner() {

        // Direct intent to Developer Options. Falls back to general Settings if unavailable.
        val devIntent = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(devIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "No dev settings intent — opening Settings root", t)
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // Wait for the developer-options screen to materialize, with early exit when found.
        var wirelessNode: AccessibilityNodeInfo? = waitForNode(timeoutMs = 5000)

        // If still not found, the row is below the fold. Scroll the list and keep looking.
        if (wirelessNode == null) {
            Log.i(TAG, "Row not visible — scrolling to find it")
            wirelessNode = scrollAndFind(maxScrolls = 25)
        }

        val target = wirelessNode
        if (target == null) {
            Log.w(TAG, "Could not find 'Wireless debugging' row after scrolling")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // Bring it fully on-screen before tapping (in case it's only partially visible).
        target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        delay(250)

        // Two shapes of this UI in the wild:
        //   A) AOSP/Pixel: the row carries an inline Switch — click it directly.
        //   B) Realme/ColorOS (and most A12+ ROMs): the row is a navigation entry that
        //      opens a sub-screen titled "Wireless debugging" with the real toggle inside.
        val inlineSwitch = findClickableSwitchNear(target)
        if (inlineSwitch != null) {
            val ok = inlineSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Inline switch click dispatched: $ok")
        } else {
            val rowClicked = clickRow(target)
            Log.i(TAG, "Row clicked to open detail screen: $rowClicked")
            delay(1200) // navigation + page render; long enough to screenshot
            val detailSwitch = waitForAnySwitch(timeoutMs = 4000)
            if (detailSwitch != null) {
                val rect = Rect()
                detailSwitch.getBoundsInScreen(rect)
                Log.i(TAG, "Detail switch found at ${rect.flattenToString()} clickable=${detailSwitch.isClickable}")
                val ok = clickRow(detailSwitch)
                Log.i(TAG, "Detail switch click dispatched: $ok")
            } else {
                Log.w(TAG, "No Switch found on detail screen")
            }
        }

        // Some ROMs prompt "Allow wireless debugging on this network?" — accept it.
        delay(1200)
        confirmDialogIfPresent()

        delay(1000)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private suspend fun waitForAnySwitch(timeoutMs: Long, intervalMs: Long = 200): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            val sw = root?.findDescendant { n ->
                n.className?.toString()?.contains("Switch", ignoreCase = true) == true
            }
            if (sw != null) return sw
            delay(intervalMs)
        }
        return null
    }

    private suspend fun waitForNode(timeoutMs: Long, intervalMs: Long = 200): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findWirelessDebuggingRow()?.let { return it }
            delay(intervalMs)
        }
        return null
    }

    private suspend fun scrollAndFind(maxScrolls: Int): AccessibilityNodeInfo? {
        // dispatchGesture-based swipe instead of ACTION_SCROLL_FORWARD: on ColorOS the
        // scrollable node refuses the programmatic action even when the list clearly
        // has more rows below the fold. A synthetic swipe goes through the regular
        // touch pipeline and works regardless of which node owns the scroll behaviour.
        var lastSnapshot: List<String> = emptyList()
        repeat(maxScrolls) { i ->
            val snapshot = collectVisibleText()
            if (snapshot == lastSnapshot && lastSnapshot.isNotEmpty()) {
                Log.i(TAG, "Screen content did not change after swipe — assuming end of list")
                return null
            }
            lastSnapshot = snapshot

            val ok = swipeUp()
            if (!ok) {
                Log.w(TAG, "Gesture dispatch failed or was cancelled at iteration $i")
                return null
            }
            delay(450) // let the list settle and new rows render
            findWirelessDebuggingRow()?.let { return it }
        }
        Log.w(TAG, "Exhausted $maxScrolls swipes without finding the row")
        return null
    }

    private suspend fun swipeUp(): Boolean {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.75f
        val endY = dm.heightPixels * 0.30f
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        val done = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.complete(true) }
            override fun onCancelled(g: GestureDescription?) { done.complete(false) }
        }, null)
        if (!dispatched) done.complete(false)
        return done.await()
    }

    private fun collectVisibleText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    private fun findWirelessDebuggingRow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return LABELS.flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty() }.firstOrNull()
    }

    private suspend fun clickRow(target: AccessibilityNodeInfo): Boolean {
        findClickableSwitchNear(target)?.let { switch ->
            if (switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        var ancestor: AccessibilityNodeInfo? = target
        repeat(6) {
            val cur = ancestor ?: return@repeat
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            ancestor = cur.parent
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.width() == 0 || rect.height() == 0) {
            Log.w(TAG, "Row bounds empty — cannot tap")
            return false
        }
        Log.i(TAG, "Falling back to gesture tap at (${rect.centerX()}, ${rect.centerY()})")
        return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun wakeScreenIfOff(): PowerManager.WakeLock? {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            Log.i(TAG, "Screen already on")
            return null
        }
        @Suppress("DEPRECATION") // FULL_WAKE_LOCK is deprecated but is the only flag that actually turns the screen on from a service.
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "AdbWatchdog:recovery"
        )
        wl.acquire(20_000L)
        Log.i(TAG, "Wake lock acquired — screen woken")
        return wl
    }

    private suspend fun dismissSwipeKeyguardIfNeeded() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) return
        if (km.isKeyguardSecure) {
            // PIN/pattern/password — we cannot unlock without the user. Recovery will likely fail.
            Log.w(TAG, "Secure keyguard active — toggle UI is inaccessible until user unlocks")
            return
        }
        Log.i(TAG, "Swipe keyguard active — dispatching swipe-up to dismiss")
        delay(500) // give the screen time to actually render after wake
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.90f
        val endY = dm.heightPixels * 0.10f
        val path = Path().apply { moveTo(centerX, startY); lineTo(centerX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        val done = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.complete(true) }
            override fun onCancelled(g: GestureDescription?) { done.complete(false) }
        }, null)
        if (!dispatched) done.complete(false)
        done.await()
        delay(700)
        if (km.isKeyguardLocked) {
            Log.w(TAG, "Keyguard still locked after swipe — Settings will be behind it")
        } else {
            Log.i(TAG, "Keyguard dismissed")
        }
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val done = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.complete(true) }
            override fun onCancelled(g: GestureDescription?) { done.complete(false) }
        }, null)
        if (!dispatched) done.complete(false)
        return done.await()
    }

    private fun findClickableSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Walk up to a list-row ancestor, then find a clickable Switch descendant.
        var ancestor: AccessibilityNodeInfo? = node
        repeat(4) {
            ancestor = ancestor?.parent ?: return@repeat
            val switch = ancestor?.findDescendant { n ->
                n.className?.toString()?.contains("Switch", ignoreCase = true) == true && n.isClickable
            }
            if (switch != null) return switch
        }
        return null
    }

    private fun confirmDialogIfPresent() {
        val root = rootInActiveWindow ?: return
        val allow = listOf("Allow", "ALLOW", "OK", "Ok").flatMap {
            root.findAccessibilityNodeInfosByText(it).orEmpty()
        }.firstOrNull { it.isClickable || it.parent?.isClickable == true }
        allow?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun AccessibilityNodeInfo.findDescendant(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            child.findDescendant(predicate)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "AdbWatchdog"
        private val LABELS = listOf("Wireless debugging", "Wireless Debugging", "ADB over Wi-Fi")

        @Volatile
        var bound: WatchdogAccessibilityService? = null
            private set

        fun isBound(): Boolean = bound != null
    }
}
