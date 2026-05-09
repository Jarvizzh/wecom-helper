package com.helper.wecom.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
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

        showNotification()

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

    private fun showNotification() {
        val channelId = "wecom_helper_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "企业微信助手服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("企业微信助手已开启")
            .setContentText("正在后台运行任务检查...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
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
                        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        
                        val isScreenOff = !powerManager.isInteractive
                        val isLocked = keyguardManager.isKeyguardLocked

                        if (isScreenOff || isLocked) {
                            Log.d("WeComService", "Ticker: Waking up screen / unlocking and launching WeCom")
                            wakeScreenAndUnlock(powerManager, keyguardManager)
                            delay(3000) // Slightly longer delay for system readiness
                            launchWeCom()
                        } else {
                            // Screen is on and unlocked. Ensure WeCom is launched.
                            val currentPackage = rootInActiveWindow?.packageName?.toString()
                            if (currentPackage != "com.tencent.wework") {
                                Log.d("WeComService", "Ticker: Launching WeCom (already awake)")
                                launchWeCom()
                            }
                        }
                    }
                }
                delay(120_000) // 2 minutes
            }
        }
    }

    private fun launchWeCom() {
        val intent = packageManager.getLaunchIntentForPackage("com.tencent.wework")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                startActivity(intent)
                Log.d("WeComService", "Launch WeCom intent sent successfully")
            } catch (e: Exception) {
                Log.e("WeComService", "Failed to launch WeCom", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        
        // Check time window using cached values, only if mode is Scheduled (1)
        if (executionMode == 1) {
            if (isWithinTimeWindow(startHour, startMinute, endHour, endMinute)) {
                if (autoWake) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    wakeScreenAndUnlock(powerManager, keyguardManager)
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
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                wakeScreenAndUnlock(powerManager, keyguardManager)
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

    private fun wakeScreenAndUnlock(powerManager: PowerManager, keyguardManager: KeyguardManager) {
        if (!powerManager.isInteractive) {
            // Use FULL_WAKE_LOCK (deprecated but more effective for waking on old MIUI)
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "WeComHelper:WakeLock"
            )
            wakeLock.acquire(10000) // Keep screen on for 10 seconds
            Log.d("WeComService", "Screen waked up via Full WakeLock")
        }

        // Try legacy KeyguardLock (deprecated but often works on MIUI 12)
        @Suppress("DEPRECATION")
        val keyguardLock = keyguardManager.newKeyguardLock("WeComHelper:KeyguardLock")
        keyguardLock.disableKeyguard()

        // Perform a swipe up gesture to bypass "Swipe to unlock"
        serviceScope.launch {
            delay(1000) // Wait for screen to fully turn on
            unlockBySwipe()
        }
    }

    private fun unlockBySwipe() {
        val displayMetrics = resources.displayMetrics
        val height = displayMetrics.heightPixels
        val width = displayMetrics.widthPixels

        val path = Path()
        // Swipe from bottom-middle to top-middle
        path.moveTo(width / 2f, height * 0.8f)
        path.lineTo(width / 2f, height * 0.2f)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 500))
        
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("WeComService", "Unlock swipe gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("WeComService", "Unlock swipe gesture cancelled")
            }
        }, null)
        Log.d("WeComService", "Dispatching unlock swipe: $result")
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
            val sendButton = findSendButton(rootNode)
            if (sendButton != null) {
                try {
                    Log.d("WeComService", "Found Send Button")
                    if (clickNode(sendButton)) {
                        delay(1000)
                        return
                    }
                } finally {
                    sendButton.recycle()
                }
            }

            if (tryClickByText(rootNode, "确定")) {
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

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Search by "发" to cover all variants ("发送", "发 送", etc.)
        val nodes = rootNode.findAccessibilityNodeInfosByText("发")
        if (nodes.isNullOrEmpty()) return null

        // Regex to strip all possible spacing characters including full-width, non-breaking spaces
        val spaceRegex = "[\\s\\u00A0\\u2007\\u202F\\u3000]+".toRegex()

        // 1. Prioritize exact match of "发送" after removing all kinds of spaces.
        val exactMatches = nodes.filter { node ->
            val text = node.text?.toString()?.replace(spaceRegex, "") ?: ""
            val desc = node.contentDescription?.toString()?.replace(spaceRegex, "") ?: ""
            text == "发送" || desc == "发送"
        }

        val result = if (exactMatches.isNotEmpty()) {
            // We want the most recent/bottom one
            exactMatches.last() 
        } else {
            // 2. Fallback: look for a node that contains "发送" but NOT "已发送"
            // This handles cases where the text is bundled in the entire task box description
            val partialMatches = nodes.filter { node ->
                val text = node.text?.toString()?.replace(spaceRegex, "") ?: ""
                val desc = node.contentDescription?.toString()?.replace(spaceRegex, "") ?: ""
                (text.contains("发送") && !text.contains("已发送")) || 
                (desc.contains("发送") && !desc.contains("已发送"))
            }
            if (partialMatches.isNotEmpty()) {
                partialMatches.last()
            } else {
                null
            }
        }

        // Recycle unused nodes
        nodes.forEach { if (it != result) it.recycle() }

        return result
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

        val cleanText = text.replace("\\s+".toRegex(), "")

        // 1. Try to find exact matches first (checking both text and contentDescription, ignoring spaces)
        val exactMatches = nodes.filter { 
            it.text?.toString()?.replace("\\s+".toRegex(), "") == cleanText || 
            it.contentDescription?.toString()?.replace("\\s+".toRegex(), "") == cleanText 
        }

        val result = if (exactMatches.isNotEmpty()) {
            if (isBottomUp) exactMatches.last() else exactMatches.first()
        } else {
            // 2. Fallback to partial matches
            if (isBottomUp) nodes.last() else nodes.first()
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
        while (!current.isClickable && depth < 10) { // Increased depth to 10
            val parent = current.parent ?: break
            parentsToRecycle.add(parent)
            current = parent
            depth++
        }

        val result = if (current.isClickable) {
            val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("WeComService", "Click result for text [${node.text ?: node.contentDescription}]: $success. Clicked node class: ${current.className}")
            success
        } else {
            Log.w("WeComService", "Node text [${node.text ?: node.contentDescription}] or its parents are not clickable. Max depth reached: $depth")
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
