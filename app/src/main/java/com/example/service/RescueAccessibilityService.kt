package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.HapticAndSoundFeedback
import com.example.core.RescueLogger
import com.example.model.DeviceProfile
import com.example.model.DeviceProfileResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RescueAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: RescueAccessibilityService? = null

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _isScanningForDialog = MutableStateFlow(false)
        val isScanningForDialog: StateFlow<Boolean> = _isScanningForDialog.asStateFlow()

        private val _dialogConfirmed = MutableStateFlow(false)
        val dialogConfirmed: StateFlow<Boolean> = _dialogConfirmed.asStateFlow()

        fun getInstance(): RescueAccessibilityService? = instance

        fun startDialogScan(durationMs: Long = 60_000L) {
            instance?.startScan(durationMs)
        }

        fun isConnected(): Boolean = _isServiceConnected.value
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanStartTime = 0L
    private var scanTimeoutMs = 60_000L
    private var isScanning = false
    private var lastAttemptLogTime = 0L

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!isScanning) return

            val elapsed = System.currentTimeMillis() - scanStartTime
            if (elapsed > scanTimeoutMs) {
                stopScan(timedOut = true)
                return
            }

            val handled = scanAndClickAdbDialog()
            if (handled) {
                stopScan(timedOut = false)
            } else {
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        RescueLogger.s("AccessibilityService connected! Zero-touch auto-allow ready.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan(timedOut = false)
        if (instance == this) {
            instance = null
        }
        _isServiceConnected.value = false
        RescueLogger.w("AccessibilityService destroyed.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val pkg = event.packageName?.toString() ?: ""
            val profile = DeviceProfileResolver.resolve()

            if (profile.targetPackages.any { pkg.contains(it, ignoreCase = true) } || isScanning) {
                // If this is a candidate window, inspect immediately
                scanAndClickAdbDialog()
            }
        }
    }

    override fun onInterrupt() {
        RescueLogger.w("AccessibilityService interrupted.")
    }

    fun startScan(durationMs: Long = 60_000L) {
        handler.removeCallbacks(scanRunnable)
        scanStartTime = System.currentTimeMillis()
        scanTimeoutMs = durationMs
        isScanning = true
        _isScanningForDialog.value = true
        _dialogConfirmed.value = false
        RescueLogger.i("Starting 60s auto-allow dialog scanner (retry every 500ms)...")
        handler.post(scanRunnable)
    }

    fun stopScan(timedOut: Boolean) {
        if (!isScanning) return
        isScanning = false
        _isScanningForDialog.value = false
        handler.removeCallbacks(scanRunnable)

        if (timedOut) {
            RescueLogger.w("Timeout: 'Allow USB debugging?' dialog was not detected within 60 seconds.")
            HapticAndSoundFeedback.playTimeoutFeedback(this)
        }
    }

    /**
     * Inspects active window hierarchy and attempts 3-layer click:
     * 1. View IDs: android:id/alwaysUse, android:id/button1
     * 2. Multi-language text matching
     * 3. dispatchGesture tap on button bounds
     * Returns true if dialog was found and confirmed.
     */
    @Synchronized
    private fun scanAndClickAdbDialog(): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            // Check all interactive windows if rootInActiveWindow is null
            val windows = windows
            for (window in windows) {
                val node = window.root
                if (node != null) {
                    val res = inspectNodeTree(node)
                    if (res) return true
                }
            }
            return false
        }

        return inspectNodeTree(rootNode)
    }

    private fun inspectNodeTree(rootNode: AccessibilityNodeInfo): Boolean {
        val profile = DeviceProfileResolver.resolve()
        val pkg = rootNode.packageName?.toString() ?: ""

        // Check if package matches profile
        val isTargetPkg = profile.targetPackages.any { pkg.contains(it, ignoreCase = true) }
        val now = System.currentTimeMillis()
        if (now - lastAttemptLogTime > 5000L) {
            lastAttemptLogTime = now
            RescueLogger.i("Scanning window: pkg='$pkg', targetMatch=$isTargetPkg, profile='${profile.name}'")
        }

        var checkboxNode: AccessibilityNodeInfo? = null
        var allowButtonNode: AccessibilityNodeInfo? = null

        // --- LAYER 1: Search by View IDs ---
        for (id in profile.checkboxViewIds) {
            val list = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) {
                checkboxNode = list.first()
                break
            }
        }

        for (id in profile.allowButtonViewIds) {
            val list = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) {
                allowButtonNode = list.first()
                break
            }
        }

        // --- LAYER 2: Multi-Language Text Matching (if not found by ID) ---
        if (checkboxNode == null) {
            checkboxNode = findNodeMatchingTexts(rootNode, profile.checkboxTexts)
        }

        if (allowButtonNode == null) {
            allowButtonNode = findNodeMatchingTexts(rootNode, profile.allowButtonTexts)
        }

        // Check if we found either the allow button or both
        if (allowButtonNode == null && checkboxNode == null) {
            return false
        }

        RescueLogger.s("USB Authorization candidate detected! (Package: $pkg)")

        // Step A: Tick "Always allow from this computer" checkbox first
        if (checkboxNode != null) {
            val isChecked = checkboxNode.isChecked
            RescueLogger.i("Checkbox found. Current isChecked = $isChecked")
            if (!isChecked) {
                var clicked = checkboxNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    // Layer 3 fallback: dispatch gesture tap
                    clicked = tapNodeBounds(checkboxNode)
                }
                RescueLogger.i("Ticked 'Always allow from this computer': success=$clicked")
            } else {
                RescueLogger.i("'Always allow from this computer' is already checked.")
            }
        } else {
            RescueLogger.w("Checkbox node not identified; proceeding to tap Allow button.")
        }

        // Step B: Tap "Allow" / "OK" button
        if (allowButtonNode != null) {
            var clicked = allowButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                // Layer 3 fallback: dispatch gesture tap
                RescueLogger.i("Standard click on Allow button failed. Attempting Layer 3 dispatchGesture tap...")
                clicked = tapNodeBounds(allowButtonNode)
            }

            if (clicked) {
                RescueLogger.s("Successfully clicked 'Allow' on USB Debugging authorization dialog!")
                _dialogConfirmed.value = true
                HapticAndSoundFeedback.playDialogConfirmedFeedback(this)
                return true
            } else {
                RescueLogger.e("Failed to click Allow button despite finding node.")
            }
        }

        return false
    }

    private fun findNodeMatchingTexts(root: AccessibilityNodeInfo, textList: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeText = node.text?.toString()?.lowercase() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""

            for (target in textList) {
                val lowerTarget = target.lowercase()
                if (nodeText.contains(lowerTarget) || nodeDesc.contains(lowerTarget)) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Layer 3: Dispatches a synthetic touch tap gesture directly at the center of the node bounds.
     */
    private fun tapNodeBounds(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            return false
        }

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        RescueLogger.i("Dispatching gesture tap at coordinates ($centerX, $centerY)...")

        val path = Path().apply {
            moveTo(centerX, centerY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                RescueLogger.i("Gesture tap dispatched successfully at ($centerX, $centerY).")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                RescueLogger.w("Gesture tap cancelled at ($centerX, $centerY).")
            }
        }, null)
    }
}
