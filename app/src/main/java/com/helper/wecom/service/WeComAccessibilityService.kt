package com.helper.wecom.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class WeComAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var lastProcessTime = 0L
    private val THROTTLE_MS = 500L
    private var tickerJob: Job? = null

    // Cached configuration
    private var startHour = 19
    private var startMinute = 50
    private var endHour = 22
    private var endMinute = 10
    private var autoWake = false
    private var executionMode = 0 // 0: Immediate, 1: Scheduled
    private lateinit var prefs: SharedPreferences

    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            "startHour" -> startHour = p.getInt("startHour", 19)
            "startMinute" -> startMinute = p.getInt("startMinute", 50)
            "endHour" -> endHour = p.getInt("endHour", 22)
            "endMinute" -> endMinute = p.getInt("endMinute", 10)
            "autoWake" -> autoWake = p.getBoolean("autoWake", false)
            "executionMode" -> executionMode = p.getInt("executionMode", 0)
        }
        Log.d("WeComService", "Config updated: $startHour:$startMinute - $endHour:$endMinute, autoWake=$autoWake, mode=$executionMode")
    }

    companion object {
        var isRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("WeComService", "Service Connected")

        // Initialize and cache configuration
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        startHour = prefs.getInt("startHour", 19)
        startMinute = prefs.getInt("startMinute", 50)
        endHour = prefs.getInt("endHour", 22)
        endMinute = prefs.getInt("endMinute", 10)
        autoWake = prefs.getBoolean("autoWake", false)
        executionMode = prefs.getInt("executionMode", 0)

        prefs.registerOnSharedPreferenceChangeListener(configListener)

        // Start active ticker (check every 2 minutes)
        startTicker()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                if (isRunning) {
                    // Only check time window in Scheduled mode (1)
                    val isInWindow = if (executionMode == 1) {
                        isWithinTimeWindow(startHour, startMinute, endHour, endMinute)
                    } else {
                        true // Immediate mode is always "in window"
                    }

                    if (isInWindow && autoWake) {
                        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (!powerManager.isInteractive) {
                            Log.d("WeComService", "Ticker: Waking up screen and launching WeCom")
                            wakeScreenAndUnlock()
                            delay(2000)
                            launchWeCom()
                        }
                    }
                }
                delay(120_000) // 2 minutes
            }
        }
    }

    private fun launchWeCom() {
        val intent = packageManager.getLaunchIntentForPackage("com.tencent.wework")
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            startActivity(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        
        // Check time window using cached values, only if mode is Scheduled (1)
        if (executionMode == 1) {
            if (isWithinTimeWindow(startHour, startMinute, endHour, endMinute)) {
                if (autoWake) {
                    wakeScreenAndUnlock()
                }
            } else {
                // Throttled logging for time window to avoid spamming
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProcessTime > 5000) {
                    Log.d("WeComService", "Outside of allowed time window ($startHour:$startMinute - $endHour:$endMinute). Skipping...")
                }
                return
            }
        } else {
            // Immediate mode: Always wake if needed
            if (autoWake) {
                wakeScreenAndUnlock()
            }
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < THROTTLE_MS) return
        lastProcessTime = currentTime

        // We don't pass the rootNode here to avoid staleness in the coroutine
        serviceScope.launch {
            try {
                handleWorkflow()
            } catch (e: Exception) {
                Log.e("WeComService", "Error in workflow", e)
            }
        }
    }

    private fun wakeScreenAndUnlock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WeComHelper:WakeLock"
            )
            wakeLock.acquire(3000) // Wake screen for 3 seconds
            Log.d("WeComService", "Screen waked up")
        }
    }

    /**
     * Supports midnight crossing (e.g., 23:00 to 01:00)
     */
    private fun isWithinTimeWindow(startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val current = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val start = startH * 60 + startM
        val end = endH * 60 + endM

        return if (start <= end) {
            current in start..end
        } else {
            // Crosses midnight
            current >= start || current <= end
        }
    }

    private suspend fun handleWorkflow() {
        // Fetch fresh root node inside the coroutine to ensure it's not stale
        val rootNode = rootInActiveWindow ?: return
        
        try {
            Log.d("WeComService", "Checking UI state...")

            // Priority 1: Handle Mass Send specific buttons
            if (tryClickByText(rootNode, "发送", isBottomUp = true) || tryClickByText(rootNode, "确定")) {
                return
            }

            // Priority 2: Try to find and click "群发助手" (Mass Assistant)
            val massAssistant = findNodeByText(rootNode, "群发助手")
            if (massAssistant != null) {
                try {
                    Log.d("WeComService", "Found Mass Assistant")
                    if (clickNode(massAssistant)) {
                        delay(1000)
                        return
                    }
                } finally {
                    massAssistant.recycle()
                }
            }

            // Priority 3: If not in Mass Assistant, try to go to Workbench
            val workbench = findNodeByText(rootNode, "工作台")
            if (workbench != null) {
                try {
                    Log.d("WeComService", "Clicking Workbench")
                    if (clickNode(workbench)) {
                        delay(1000)
                        return
                    }
                } finally {
                    workbench.recycle()
                }
            }
        } finally {
            // Always recycle the root node retrieved from rootInActiveWindow
            rootNode.recycle()
        }
    }

    private fun tryClickByText(rootNode: AccessibilityNodeInfo, text: String, isBottomUp: Boolean = false): Boolean {
        val node = findNodeByText(rootNode, text, isBottomUp)
        if (node != null) {
            try {
                Log.d("WeComService", "Found button: $text (bottomUp=$isBottomUp)")
                return clickNode(node)
            } finally {
                node.recycle()
            }
        }
        return false
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String, isBottomUp: Boolean = false): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return null
        
        val result = if (isBottomUp) {
            nodes.findLast { it.text?.toString() == text } ?: nodes.last()
        } else {
            nodes.find { it.text?.toString() == text } ?: nodes[0]
        }

        // Recycle all other nodes in the list that we are NOT returning
        nodes.forEach { if (it != result) it.recycle() }
        
        return result
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var depth = 0
        // List to keep track of parents so we can recycle them if they are not the node itself
        val parentsToRecycle = mutableListOf<AccessibilityNodeInfo>()
        
        var current: AccessibilityNodeInfo = node
        while (!current.isClickable && depth < 5) {
            val parent = current.parent ?: break
            parentsToRecycle.add(parent)
            current = parent
            depth++
        }

        val result = if (current.isClickable) {
            val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("WeComService", "Click result for ${node.text}: $success")
            success
        } else {
            Log.w("WeComService", "Node ${node.text} or its parents are not clickable")
            false
        }

        // Recycle all parents except possibly the target if it was the original node (which is recycled by the caller)
        parentsToRecycle.forEach { if (it != node) it.recycle() }

        return result
    }

    override fun onInterrupt() {
        Log.d("WeComService", "Service Interrupted")
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(configListener)
        }
        serviceScope.cancel()
    }
}
