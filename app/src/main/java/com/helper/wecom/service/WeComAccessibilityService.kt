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
    private var workflowJob: Job? = null

    // Cached configuration
    private var startHour = 19
    private var startMinute = 50
    private var endHour = 22
    private var endMinute = 10
    private var autoWake = false
    private var executionMode = 0 // 0: Immediate, 1: Scheduled
    private var isWakingUp = false
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
        if (workflowJob?.isActive == true) return
        
        lastProcessTime = currentTime

        // We don't pass the rootNode here to avoid staleness in the coroutine
        workflowJob = serviceScope.launch {
            try {
                handleWorkflow()
            } catch (e: Exception) {
                Log.e("WeComService", "Error in workflow", e)
            }
        }
    }

    private fun wakeScreenAndUnlock(powerManager: PowerManager, keyguardManager: KeyguardManager) {
        val needsWake = !powerManager.isInteractive
        val needsUnlock = keyguardManager.isKeyguardLocked

        // If everything is already fine or we are already in progress, skip
        if (!(needsWake || needsUnlock) || isWakingUp) return

        isWakingUp = true
        serviceScope.launch {
            try {
                if (needsWake) {
                    // Use FULL_WAKE_LOCK (deprecated but more effective for waking on old MIUI)
                    @Suppress("DEPRECATION")
                    val wakeLock = powerManager.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or
                                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                PowerManager.ON_AFTER_RELEASE,
                        "WeComHelper:WakeLock"
                    )
                    wakeLock.acquire(5000) // Keep screen on for 5 seconds
                    Log.d("WeComService", "Screen waked up via Full WakeLock")
                    delay(1000) // Wait for screen to fully turn on
                }

                if (keyguardManager.isKeyguardLocked) {
                    // Try legacy KeyguardLock (deprecated but often works on MIUI 12)
                    @Suppress("DEPRECATION")
                    val keyguardLock = keyguardManager.newKeyguardLock("WeComHelper:KeyguardLock")
                    keyguardLock.disableKeyguard()

                    // Perform a swipe up gesture to bypass "Swipe to unlock"
                    Log.d("WeComService", "Attempting swipe to unlock")
                    unlockBySwipe()
                    delay(1000)
                }
            } catch (e: Exception) {
                Log.e("WeComService", "Wake/Unlock failed", e)
            } finally {
                isWakingUp = false
            }
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
        val rootNode = rootInActiveWindow ?: return
        
        try {
            val currentPackage = rootNode.packageName?.toString()
            if (currentPackage != "com.tencent.wework") {
                Log.d("WeComService", "Not in WeCom, skip. Package: $currentPackage")
                return
            }

            // 1. Identify current screen state (Robust text-based detection)
            var pageTitle = ""
            val targets = listOf("群发助手", "企业群发助手", "消息", "工作台", "收件人")
            for (target in targets) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(target)
                if (!nodes.isNullOrEmpty()) {
                    val titleNode = nodes.firstOrNull { 
                        val rect = android.graphics.Rect()
                        it.getBoundsInScreen(rect)
                        // Title is strictly at the very top and usually smaller/centered
                        rect.centerY() < 200 && rect.height() > 30
                    }
                    if (titleNode != null) {
                        pageTitle = target
                        nodes.forEach { it.recycle() }
                        break
                    }
                }
                nodes?.forEach { it.recycle() }
            }
            
            Log.d("WeComService", "Detected Page: $pageTitle")

            // 2. Priority Actions based on screen
            if (pageTitle == "群发助手" || pageTitle == "企业群发助手") {
                handleMassAssistantPage(rootNode)
                return
            }

            // NAVIGATION: If in Message List, look for Mass Assistant chat (High Priority)
            if (pageTitle == "消息") {
                if (clickMassAssistantInMessageList(rootNode)) return
            }

            if (pageTitle == "收件人" || tryClickByText(rootNode, "发送", isBottomUp = true)) {
                // If we see a "Send" button and it's likely a sub-page of mass sending
                return
            }

            if (tryClickByText(rootNode, "确定")) {
                return
            }

            // 3. Navigation Actions (Priority: Workbench)
            // If in Workbench, look for Mass Assistant entry
            if (pageTitle == "工作台") {
                if (tryClickByText(rootNode, "群发助手")) return
            }

            // Fallback: If lost or in a group chat, go to "Messages" tab first
            val messagesTab = findNodeByText(rootNode, "消息")
            if (messagesTab != null) {
                try {
                    // Avoid clicking the title, look for the bottom tab (Y > 1700)
                    val rect = android.graphics.Rect()
                    messagesTab.getBoundsInScreen(rect)
                    if (rect.centerY() > 1700) {
                        if (clickNode(messagesTab)) return
                    }
                } finally {
                    messagesTab.recycle()
                }
            }

            // Secondary Fallback: go to Workbench
            val workbench = findNodeByText(rootNode, "工作台")
            if (workbench != null) {
                try {
                    if (clickNode(workbench)) return
                } finally {
                    workbench.recycle()
                }
            }

            // Ultimate Fallback: Try to go back if we are in a sub-page (e.g. a chat)
            // Look for a back button in the top-left corner
            val queue = mutableListOf(rootNode)
            while (queue.isNotEmpty()) {
                val node = queue.removeAt(0)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                
                // Typical back button: top-left, small, clickable
                if (rect.left < 150 && rect.top < 250 && rect.width() < 200 && (node.isClickable || node.parent?.isClickable == true)) {
                    Log.d("WeComService", "Lost in sub-page, attempting to go back")
                    if (clickNode(node)) return
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
                if (node != rootNode) node.recycle()
            }
        } finally {
            rootNode.recycle()
        }
    }

    private suspend fun handleMassAssistantPage(rootNode: AccessibilityNodeInfo) {
        val sendButton = findSendButton(rootNode)
        if (sendButton != null) {
            try {
                Log.d("WeComService", "Found Send Button in Mass Assistant")
                if (clickNode(sendButton)) {
                    delay(2000)
                    return
                }
            } finally {
                sendButton.recycle()
            }
        }

        // Ultimate fallback for the blue button region (usually bottom right-ish)
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.85f
        val y = metrics.heightPixels * 0.9f
        Log.d("WeComService", "Mass Assistant: Triggering coordinate fallback tap at ($x, $y)")
        if (dispatchCoordinateClick(x, y)) {
            delay(2000)
        }
    }

    private fun clickMassAssistantInMessageList(rootNode: AccessibilityNodeInfo): Boolean {
        // Search specifically within the message list area
        val nodes = rootNode.findAccessibilityNodeInfosByText("群发助手")
        if (nodes.isNullOrEmpty()) return false
        
        Log.d("WeComService", "Searching for '群发助手' in list: found ${nodes.size} candidates")
        
        val screenHeight = resources.displayMetrics.heightPixels
        val rect = android.graphics.Rect()
        
        // Find the one that is likely a Chat List Item
        val candidateTextNode = nodes.firstOrNull { node ->
            node.getBoundsInScreen(rect)
            val nodeText = node.text?.toString() ?: ""
            // Exact match and must be below the title bar area (> 200)
            val isExact = nodeText == "群发助手"
            val isBelowTitle = rect.centerY() > 200
            val isNotBottomTab = rect.centerY() < (screenHeight * 0.9)
            
            isExact && isBelowTitle && isNotBottomTab
        }
        
        if (candidateTextNode != null) {
            candidateTextNode.getBoundsInScreen(rect)
            Log.d("WeComService", "Found Mass Assistant item at ${rect.toShortString()}")
            
            // CRITICAL: Find the actual clickable row (go up until we find a clickable parent)
            var parent = candidateTextNode.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) {
                    val pRect = android.graphics.Rect()
                    parent.getBoundsInScreen(pRect)
                    Log.d("WeComService", "Clicking the full row: ${pRect.toShortString()}")
                    val result = clickNode(parent)
                    nodes.forEach { it.recycle() }
                    return result
                }
                val nextParent = parent.parent
                if (parent != rootNode) parent.recycle()
                parent = nextParent
                depth++
            }
            
            // Fallback to direct click
            val result = clickNode(candidateTextNode)
            nodes.forEach { it.recycle() }
            return result
        }

        nodes.forEach { it.recycle() }
        return false
    }

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenHeight = resources.displayMetrics.heightPixels
        val targets = listOf("发送", "确定", "下一步", "知道了")
        
        // 1. Search for text-based buttons in the bottom half of the screen
        for (target in targets) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(target)
            if (!nodes.isNullOrEmpty()) {
                val button = nodes.lastOrNull { node ->
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    // Must be in bottom 40% of screen and likely a real button (not a long paragraph)
                    rect.centerY() > (screenHeight * 0.6) && (node.text?.length ?: 0) < 15
                }
                if (button != null) {
                    val rect = android.graphics.Rect()
                    button.getBoundsInScreen(rect)
                    Log.d("WeComService", "Found '$target' button by text at ${rect.toShortString()}")
                    nodes.forEach { if (it != button) it.recycle() }
                    return button
                }
                nodes.forEach { it.recycle() }
            }
        }

        // 2. ID-Agnostic Structural Search: 
        val queue = mutableListOf(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            
            val text = getAllText(node).replace("\\s+".toRegex(), "")
            if (text.contains("发送") && !text.contains("已发送")) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                
                // Real buttons are usually compact. If the text is too long, it's a message, not a button.
                val isShortText = text.length < 10
                
                // If it's a clickable container in the bottom half
                if (isShortText && rect.centerY() > (screenHeight * 0.6) && (node.isClickable || node.parent?.isClickable == true)) {
                    if (rect.width() > 300 && rect.height() > 50) {
                        Log.d("WeComService", "Found candidate button by structure at ${rect.toShortString()} with text: $text")
                        return node 
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (node != rootNode) node.recycle()
        }

        return null
    }

    private fun getAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sb.append(node.text ?: "").append(" ").append(node.contentDescription ?: "")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                sb.append(" ").append(getAllText(child))
                child.recycle()
            }
        }
        return sb.toString()
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

        val cleanTarget = text.replace("\\s+".toRegex(), "")

        // 1. Filter out "已发送" and other non-button text if we are looking for "发送"
        val filteredNodes = if (cleanTarget == "发送") {
            nodes.filter { 
                val nodeText = it.text?.toString()?.replace("\\s+".toRegex(), "") ?: ""
                val nodeDesc = it.contentDescription?.toString()?.replace("\\s+".toRegex(), "") ?: ""
                val isSent = nodeText.contains("已发送") || nodeDesc.contains("已发送")
                val isLongText = nodeText.length > 10 // Real button text is short
                !isSent && !isLongText
            }
        } else {
            nodes
        }

        if (filteredNodes.isEmpty()) {
            nodes.forEach { it.recycle() }
            return null
        }

        // 2. Try to find exact matches first
        val exactMatches = filteredNodes.filter { 
            val nodeText = it.text?.toString()?.replace("\\s+".toRegex(), "") ?: ""
            val nodeDesc = it.contentDescription?.toString()?.replace("\\s+".toRegex(), "") ?: ""
            nodeText == cleanTarget || nodeDesc == cleanTarget 
        }

        val result = if (exactMatches.isNotEmpty()) {
            if (isBottomUp) exactMatches.last() else exactMatches.first()
        } else {
            // 3. Fallback to partial matches among filtered nodes
            // For "发送", we allow nodes that START with "发送" or contain it if it's short
            val relaxedMatches = filteredNodes.filter {
                val nodeText = it.text?.toString()?.replace("\\s+".toRegex(), "") ?: ""
                val nodeDesc = it.contentDescription?.toString()?.replace("\\s+".toRegex(), "") ?: ""
                nodeText.contains(cleanTarget) || nodeDesc.contains(cleanTarget)
            }
            if (relaxedMatches.isNotEmpty()) {
                if (isBottomUp) relaxedMatches.last() else relaxedMatches.first()
            } else {
                if (isBottomUp) filteredNodes.last() else filteredNodes.first()
            }
        }

        // Recycle all nodes except the result
        nodes.forEach { if (it != result) it.recycle() }

        return result
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var depth = 0
        val parentsToRecycle = mutableListOf<AccessibilityNodeInfo>()

        var current: AccessibilityNodeInfo = node
        // Check if the node itself or its parents are clickable
        while (!current.isClickable && depth < 10) {
            val parent = current.parent ?: break
            parentsToRecycle.add(parent)
            current = parent
            depth++
        }

        val result = if (current.isClickable) {
            // First try accessibility click
            val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("WeComService", "Accessibility click [${node.text ?: node.contentDescription}]: $success")
            
            // For large containers that might not respond to accessibility clicks,
            // we ALWAYS perform a coordinate click at the bottom-center as fallback.
            val rect = android.graphics.Rect()
            current.getBoundsInScreen(rect)
            if (rect.width() > 300 && rect.height() > 100) {
                 clickAtBottomRegionOf(current)
            } else {
                 clickAtCenterOf(current)
            }
            success
        } else {
            Log.w("WeComService", "Node not clickable. Trying coordinate click as fallback.")
            clickAtCenterOf(node)
        }

        parentsToRecycle.forEach { if (it != node) it.recycle() }
        return result
    }

    private fun clickAtBottomRegionOf(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        // From XML, the blue button is at the very bottom of the [44,1397][1036,1865] container.
        // Let's click 10% from the bottom and center-X.
        val x = rect.centerX().toFloat()
        val y = rect.bottom - (rect.height() * 0.1f)

        return dispatchCoordinateClick(x, y)
    }

    private fun clickAtCenterOf(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()

        return dispatchCoordinateClick(x, y)
    }

    private fun dispatchCoordinateClick(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("WeComService", "Coordinate click completed at ($x, $y)")
            }
        }, null)
        Log.d("WeComService", "Dispatching coordinate click at ($x, $y): $result")
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
