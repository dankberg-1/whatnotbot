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
        super.onDestroy()
        instance = null
        stopScanning()
        Log.d(TAG, "Accessibility service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
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

    private fun isWhatnotPackage(packageName: String): Boolean {
        return packageName == WHATNOT_PACKAGE ||
            packageName.contains("whatnot", ignoreCase = true)
    }

    private fun isWhatnotApp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val packageName = rootNode.packageName?.toString() ?: ""
        return isWhatnotPackage(packageName)
    }

    private fun scanForGiveaway() {
        if (!MainActivity.isBotActive || !isWhatnotApp()) return

        val rootNode = rootInActiveWindow ?: return

        try {
            when (currentState) {
                BotState.WATCHING,
                BotState.IDLE -> {
                    if (findAndTapGiveawayButton(rootNode)) {
                        currentState = BotState.ENTERING
                    } else if (findEnterGiveawayButton(rootNode)) {
                        currentState = BotState.ENTERING
                    } else if (isAlreadyEntered(rootNode)) {
                        currentState = BotState.ENTERED
                        Log.d(TAG, "Already entered giveaway")
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
                    // Wait before scanning for another giveaway.
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Error scanning: ${exception.message}", exception)
        } finally {
            rootNode.recycle()
        }
    }

    private fun findAndTapGiveawayButton(
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val giveawayNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "giveaway", giveawayNodes)

        for (node in giveawayNodes) {
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val description =
                node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            /*
             * Only accept a short, standalone giveaway label.
             * This prevents large parent containers or unrelated descriptions
             * containing the word "giveaway" from being tapped.
             */
            val isGiveawayBadge =
                text == "giveaway" ||
                    text.matches(
                        Regex("""giveaway\s+\d+\s*(entries|spots)?""")
                    ) ||
                    description == "giveaway"

            if (!isGiveawayBadge) continue

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            Log.d(TAG, "Found giveaway badge at $bounds")

            val clickableNode = findClickableParent(node)

            if (clickableNode != null &&
                clickableNode.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            ) {
                Log.d(TAG, "Clicked giveaway badge using accessibility action")
                return true
            }

            if (performTap(bounds.centerX(), bounds.centerY())) {
                Log.d(TAG, "Tapped giveaway badge at $bounds")
                return true
            }
        }

        return false
    }

    private fun findEnterGiveawayButton(
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val enterNodes = mutableListOf<AccessibilityNodeInfo>()

        findNodesByText(rootNode, "Enter Giveaway", enterNodes)
        findNodesByText(rootNode, "Follow and Enter", enterNodes)

        return enterNodes.isNotEmpty()
    }

    private fun tapEnterButton(
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val buttonTexts = listOf(
            "Enter Giveaway",
            "Follow and Enter"
        )

        for (buttonText in buttonTexts) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, buttonText, nodes)

            for (node in nodes) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    continue
                }

                Log.d(TAG, "Found exact enter button '$buttonText' at $bounds")

                val clickableNode = findClickableParent(node)

                if (clickableNode != null &&
                    clickableNode.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                ) {
                    Log.d(TAG, "Clicked enter button using accessibility action")
                    return true
                }

                if (performTap(bounds.centerX(), bounds.centerY())) {
                    Log.d(TAG, "Tapped enter button at $bounds")
                    return true
                }
            }
        }

        return false
    }

    private fun isAlreadyEntered(
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val enteredNodes = mutableListOf<AccessibilityNodeInfo>()

        findNodesByText(
            rootNode,
            "You're in the Giveaway",
            enteredNodes
        )
        findNodesByText(
            rootNode,
            "You’re in the Giveaway",
            enteredNodes
        )

        return enteredNodes.isNotEmpty()
    }

    private fun findNodesByText(
        node: AccessibilityNodeInfo,
        searchText: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text?.toString() ?: ""
        val contentDescription =
            node.contentDescription?.toString() ?: ""

        if (nodeText.contains(searchText, ignoreCase = true) ||
            contentDescription.contains(searchText, ignoreCase = true)
        ) {
            results.add(node)
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNodesByText(child, searchText, results)
        }
    }

    private fun findClickableParent(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node

        repeat(5) {
            val candidate = current ?: return null

            if (candidate.isClickable && candidate.isEnabled) {
                return candidate
            }

            current = candidate.parent
        }

        return null
    }

    private fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val now = System.currentTimeMillis()

        if (now - lastActionTime < TAP_DELAY_MS) {
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    100L
                )
            )
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    Log.d(TAG, "Tap completed at ($x, $y)")
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    Log.d(TAG, "Tap cancelled at ($x, $y)")
                }
            },
            null
        )

        if (dispatched) {
            lastActionTime = now
        }

        return dispatched
    }

    private fun incrementGiveawayCount() {
        MainActivity.giveawaysEntered++

        val preferences = getSharedPreferences(
            MainActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        preferences.edit()
            .putInt(
                MainActivity.KEY_GIVEAWAY_COUNT,
                MainActivity.giveawaysEntered
            )
            .apply()

        Log.d(
            TAG,
            "Giveaway entered. Total: ${MainActivity.gStatus(
            "Entered!ActivityiveawaysEntered}"
              fun getCurrentState(): BotState {
        return currentState
    }
            }
