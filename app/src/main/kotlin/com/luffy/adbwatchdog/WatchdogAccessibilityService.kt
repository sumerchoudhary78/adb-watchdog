package com.luffy.adbwatchdog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
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

        // Make sure we're not on lockscreen; on a swipe-only phone HOME dismisses keyguard.
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(400)

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
        delay(150)

        // Click the toggle. Prefer the Switch sibling if present; else click the row.
        val clickable = findClickableSwitchNear(target) ?: target
        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Toggle click dispatched: $ok")

        // Some ROMs prompt "Allow wireless debugging on this network?" — accept it.
        delay(700)
        confirmDialogIfPresent()

        delay(800)
        performGlobalAction(GLOBAL_ACTION_HOME)
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

    private fun findClickableSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Walk up to a list-row ancestor, then find a Switch descendant.
        var ancestor: AccessibilityNodeInfo? = node
        repeat(4) {
            ancestor = ancestor?.parent ?: return@repeat
            val switch = ancestor?.findDescendant { n ->
                n.className?.toString()?.contains("Switch", ignoreCase = true) == true
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
