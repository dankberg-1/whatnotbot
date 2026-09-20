package com.whatnotbot.giveaway

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GiveawayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GiveawayBot"
        private const val WHATNOT_PACKAGE = "com.whatnot.whatnot"

        private const val SCAN_INTERVAL_MS = 1000L
        private const val TAP_DELAY_MS = 800L
        private const val COOLDOWN_MS = 3000L

        var instance: GiveawayAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var lastActionTime = 0L
    private var currentState = BotState.IDLE

    enum class BotState {
        IDLE,
        WATCHING,
        ENTERING,
        ENTERED,
        COOLDOWN
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (MainActivity.isBotActive && isWhatnotApp()) {
                scanForGiveaway()
            }

            if (isScanning) {
                handler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility service created")
    }

    override fun onDestroy() {
        stopScanning()
        instance = null
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startScanning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !MainActivity.isBotActive) return

        val packageName = event.packageName?.toString() ?: return
        if (!isWhatnotPackage(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (currentState == BotState.WATCHING) {
                    handler.removeCallbacks(scanRunnable)
                    handler.postDelayed(scanRunnable, 250L)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    fun startScanning() {
        if (isScanning) return
        isScanning = true
        currentState = BotState.WATCHING
        handler.post(scanRunnable)
        Log.d(TAG, "Started scanning for giveaways")
    }

    fun stopScanning() {
        isScanning = false
        currentState = BotState.IDLE
        handler.removeCallbacks(scanRunnable)
        Log.d(TAG, "Stopped scanning")
    }

    private fun isWhatnotPackage(packageName: String): Boolean {
        return packageName == WHATNOT_PACKAGE ||
            packageName.contains("whatnot", ignoreCase = true)
    }

    private fun isWhatnotApp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return isWhatnotPackage(root.packageName?.toString() ?: "")
    }

    private fun scanForGiveaway() {
        if (!MainActivity.isBotActive || !isWhatnotApp()) return

        val root = rootInActiveWindow ?: return

        try {
            when (currentState) {
                BotState.WATCHING, BotState.IDLE -> {
                    if (findAndTapGiveawayButton(root)) {
                        currentState = BotState.ENTERING
                    } else if (hasEnterButton(root)) {
                        currentState = BotState.ENTERING
                    } else if (isAlreadyEntered(root)) {
                        currentState = BotState.ENTERED
                    }
                }

                BotState.ENTERING -> {
                    if (tapEnterButton(root)) {
                        currentState = BotState.COOLDOWN
                        handler.postDelayed({ verifyEntryAndContinue() }, 1200L)
                    }
                }

                BotState.ENTERED -> {
                    if (!isAlreadyEntered(root)) {
                        currentState = BotState.WATCHING
                    }
                }

                BotState.COOLDOWN -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning: ${e.message}", e)
        } finally {
            root.recycle()
        }
    }

    private fun verifyEntryAndContinue() {
        if (!MainActivity.isBotActive) return

        val root = rootInActiveWindow
        if (root != null) {
            try {
                if (isAlreadyEntered(root)) {
                    incrementGiveawayCount()
                    currentState = BotState.ENTERED
                    return
                }
            } finally {
                root.recycle()
            }
        }

        currentState = BotState.ENTERING
        handler.removeCallbacks(scanRunnable)
        if (isScanning) handler.post(scanRunnable)
    }

    private fun findAndTapGiveawayButton(root: AccessibilityNodeInfo): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(root, "giveaway", nodes)

        for (node in nodes) {
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            val isBadge =
                text == "giveaway" ||
                    text.matches(Regex("""giveaway\s+\d+\s*(entries|spots)?""")) ||
                    desc == "giveaway"

            if (!isBadge || !node.isVisibleToUser) continue

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.isEmpty) continue

            val clickable = findClickableParent(node)

            if (clickable != null &&
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                Log.d(TAG, "Clicked giveaway badge")
                return true
            }

            if (performTap(bounds.centerX(), bounds.centerY())) {
                Log.d(TAG, "Tapped giveaway badge at $bounds")
                return true
            }
        }

        return false
    }

    private fun hasEnterButton(root: AccessibilityNodeInfo): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(root, "Enter Giveaway", nodes)
        findNodesByText(root, "Follow and Enter", nodes)
        return nodes.any { it.isVisibleToUser }
    }

    private fun tapEnterButton(root: AccessibilityNodeInfo): Boolean {
        for (label in listOf("Enter Giveaway", "Follow and Enter")) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(root, label, nodes)

            for (node in nodes) {
                if (!node.isVisibleToUser || !node.isEnabled) continue

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) continue

                Log.d(TAG, "Found enter button '$label' at $bounds")

                if (node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    Log.d(TAG, "Clicked $label directly")
                    return true
                }

                val parent = findClickableParent(node)
                if (parent != null &&
                    parent.isVisibleToUser &&
                    parent.isEnabled &&
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    Log.d(TAG, "Clicked $label parent")
                    return true
                }

                if (performTap(bounds.centerX(), bounds.centerY())) {
                    Log.d(TAG, "Tapped $label at $bounds")
                    return true
                }
            }
        }
        return false
    }

    private fun isAlreadyEntered(root: AccessibilityNodeInfo): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(root, "You're in the Giveaway", nodes)
        findNodesByText(root, "You’re in the Giveaway", nodes)
        return nodes.isNotEmpty()
    }

    private fun findNodesByText(
        node: AccessibilityNodeInfo,
        search: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains(search, true) || desc.contains(search, true)) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByText(child, search, results)
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var level = 0

        while (current != null && level < 4) {
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                return current
            }
            current = current.parent
            level++
        }

        return null
    }

    private fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val now = System.currentTimeMillis()
        if (now - lastActionTime < TAP_DELAY_MS) return false

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Tap completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Tap cancelled at ($x, $y)")
                }
            },
            null
        )

        if (dispatched) lastActionTime = now
        return dispatched
    }

    private fun incrementGiveawayCount() {
        MainActivity.giveawaysEntered++

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(MainActivity.KEY_GIVEAWAY_COUNT, MainActivity.giveawaysEntered).apply()

        Log.d(TAG, "Giveaway entered. Total: ${MainActivity.giveawaysEntered}")
        OverlayService.instance?.updateStatus("Entered! Total: ${MainActivity.giveawaysEntered}")
    }

    fun getCurrentState(): BotState = currentState
}
