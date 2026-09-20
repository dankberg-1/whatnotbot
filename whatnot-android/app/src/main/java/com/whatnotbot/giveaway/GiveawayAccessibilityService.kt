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

        // Timing constants
        private const val SCAN_INTERVAL_MS = 1000L
        private const val TAP_DELAY_MS = 800L
        private const val COOLDOWN_MS = 3000L

        // Singleton instance for overlay to communicate
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
            if (MainActivity.isBotActive) {
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
        Log.d(TAG, "Accessibility Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopScanning()
        Log.d(TAG, "Accessibility Service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")
        startScanning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!MainActivity.isBotActive) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (currentState == BotState.WATCHING) {
                    handler.removeCallbacks(scanRunnable)
                    handler.postDelayed(scanRunnable, 250)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    fun startScanning() {
        if (!isScanning) {
            isScanning = true
            currentState = BotState.WATCHING
            handler.post(scanRunnable)
            Log.d(TAG, "Started scanning for giveaways")
        }
    }

    fun stopScanning() {
        isScanning = false
        currentState = BotState.IDLE
        handler.removeCallbacks(scanRunnable)
        Log.d(TAG, "Stopped scanning")
    }

    private fun isWhatnotApp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val packageName = rootNode.packageName?.toString() ?: ""
        val isWhatnot = packageName == WHATNOT_PACKAGE || packageName.contains("whatnot", ignoreCase = true)
        Log.d(TAG, "Current app package: $packageName")
        return isWhatnot
    }

    private fun scanForGiveaway() {
        if (!MainActivity.isBotActive) return

        val rootNode = rootInActiveWindow ?: return

        try {
            when (currentState) {
                BotState.WATCHING, BotState.IDLE -> {
                    if (findAndTapGiveawayButton(rootNode)) {
                        currentState = BotState.ENTERING
                    } else if (findEnterGiveawayButton(rootNode)) {
                        currentState = BotState.ENTERING
                    } else if (isAlreadyEntered(rootNode)) {
                        currentState = BotState.ENTERED
                        Log.d(TAG, "Already in giveaway, watching for next one")
                    } else {
                        logVisibleNodes(rootNode, 0)
                    }
                }

                BotState.ENTERING -> {
                    if (tapEnterButton(rootNode)) {
                        currentState = BotState.COOLDOWN
                        incrementGiveawayCount()
                        handler.postDelayed({
                            currentState = BotState.WATCHING
                        }, COOLDOWN_MS)
                    }
                }

                BotState.ENTERED -> {
                    if (!isAlreadyEntered(rootNode)) {
                        currentState = BotState.WATCHING
                    }
                }

                BotState.COOLDOWN -> {
                    // wait for cooldown
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    private fun findAndTapGiveawayButton(rootNode: AccessibilityNodeInfo): Boolean {
        val giveawayNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "giveaway", giveawayNodes)

        for (node in giveawayNodes) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            Log.d(TAG, "Candidate giveaway node: $bounds text=${node.text} desc=${node.contentDescription}")

            if (performTap(bounds.centerX(), bounds.centerY())) {
                Log.d(TAG, "Tapped giveaway node at $bounds")
                return true
            }
        }

        return false
    }

    private fun findEnterGiveawayButton(rootNode: AccessibilityNodeInfo): Boolean {
        val enterNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "enter giveaway", enterNodes)
        findNodesByText(rootNode, "follow and enter", enterNodes)
        findNodesByText(rootNode, "enter", enterNodes)
        findNodesByText(rootNode, "join giveaway", enterNodes)
        findNodesByText(rootNode, "join", enterNodes)

        return enterNodes.isNotEmpty()
    }

    private fun tapEnterButton(rootNode: AccessibilityNodeInfo): Boolean {
        val buttonTexts = listOf(
            "Enter Giveaway",
            "Follow and Enter",
            "Enter",
            "Join Giveaway",
            "Join"
        )

        for (buttonText in buttonTexts) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, buttonText, nodes)

            for (node in nodes) {
                if (node.isClickable || node.isEnabled || node.isFocusable) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    if (bounds.width() <= 0 || bounds.height() <= 0) continue

                    Log.d(TAG, "Found enter button: $buttonText at $bounds")

                    if (performTap(bounds.centerX(), bounds.centerY())) {
                        Log.d(TAG, "Tapped enter button $buttonText")
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun isAlreadyEntered(rootNode: AccessibilityNodeInfo): Boolean {
        val enteredNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "you're in the giveaway", enteredNodes)
        findNodesByText(rootNode, "you’re in the giveaway", enteredNodes)
        findNodesByText(rootNode, "you're in", enteredNodes)
        findNodesByText(rootNode, "you’re in", enteredNodes)

        return enteredNodes.isNotEmpty()
    }

    private fun findNodesByText(
        node: AccessibilityNodeInfo,
        text: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        if (nodeText.contains(text, ignoreCase = true) ||
            contentDesc.contains(text, ignoreCase = true)
        ) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByText(child, text, results)
        }
    }

    private fun logVisibleNodes(node: AccessibilityNodeInfo, depth: Int) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.isNotEmpty() || desc.isNotEmpty()) {
            Log.d(TAG, "Node depth=$depth text='$text' desc='$desc'")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logVisibleNodes(child, depth + 1)
        }
    }

    private fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastActionTime < TAP_DELAY_MS) {
            return false
        }

        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)

        if (result) {
            lastActionTime = now
        }

        return result
    }

    private fun incrementGiveawayCount() {
        MainActivity.giveawaysEntered++
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(MainActivity.KEY_GIVEAWAY_COUNT, MainActivity.giveawaysEntered).apply()
        Log.d(TAG, "Giveaway entered! Total: ${MainActivity.giveawaysEntered}")

        OverlayService.instance?.updateStatus("Entered! Total: ${MainActivity.giveawaysEntered}")
    }

    fun getCurrentState(): BotState = currentState
}
