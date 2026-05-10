package com.helper.wecom.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
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
    private val THROTTLE_MS = 300L // 降低节流，确保不错过数据加载事件
    private var tickerJob: Job? = null
    private var workflowJob: Job? = null

    // Cached configuration
    private var startHour = 19
    private var startMinute = 50
    private var endHour = 22
    private var endMinute = 10
    private var autoWake = false
    private var executionMode = 0 
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
    }

    companion object {
        var isRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("WeComService", "Service Connected")
        showNotification()
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        refreshConfig()
        prefs.registerOnSharedPreferenceChangeListener(configListener)
        startTicker()
    }

    private fun refreshConfig() {
        startHour = prefs.getInt("startHour", 19)
        startMinute = prefs.getInt("startMinute", 50)
        endHour = prefs.getInt("endHour", 22)
        endMinute = prefs.getInt("endMinute", 10)
        autoWake = prefs.getBoolean("autoWake", false)
        executionMode = prefs.getInt("executionMode", 0)
    }

    private fun showNotification() {
        val channelId = "wecom_helper_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "企业微信助手", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("助手运行中")
            .setContentText("正在监控企业微信任务...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                if (isRunning) {
                    val isInWindow = if (executionMode == 1) isWithinTimeWindow() else true
                    if (isInWindow) {
                        checkAndWakeSystem()
                        // 即使没有事件触发，每 5 秒强制检查一遍 UI 状态，防止脚本卡死
                        triggerWorkflow()
                    }
                }
                delay(5000) 
            }
        }
    }

    private fun checkAndWakeSystem() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) {
            if (autoWake) wakeScreenAndUnlock(pm, km)
        } else {
            val root = rootInActiveWindow
            if (root?.packageName != "com.tencent.wework") launchWeCom()
            root?.recycle()
        }
    }

    private fun launchWeCom() {
        packageManager.getLaunchIntentForPackage("com.tencent.wework")?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        val type = event?.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            triggerWorkflow()
        }
    }

    private fun triggerWorkflow() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < THROTTLE_MS) return
        if (workflowJob?.isActive == true) return

        lastProcessTime = currentTime
        workflowJob = serviceScope.launch {
            try {
                handleWorkflow()
            } catch (e: Exception) {
                Log.e("WeComService", "Workflow error", e)
            }
        }
    }

    private suspend fun handleWorkflow() {
        val rootNode = rootInActiveWindow ?: return
        if (rootNode.packageName != "com.tencent.wework") {
            rootNode.recycle()
            return
        }

        val pageTitle = detectPageTitle(rootNode)
        Log.d("WeComService", "Current Page: $pageTitle")

        when (pageTitle) {
            "消息" -> {
                clickNodeByText(rootNode, "工作台", minCenterY = 1700)
            }
            "工作台" -> {
                clickNodeByText(rootNode, "群发助手", maxCenterY = 1700)
            }
            "群发助手" -> {
                if (!processGroupAssistantPage(rootNode)) {
                    // 关键修复：如果在群发助手页没找到任务，延迟1秒再试一次，应对数据加载延迟
                    delay(1000)
                    rootInActiveWindow?.let { 
                        processGroupAssistantPage(it)
                        it.recycle()
                    }
                }
            }
            "待发送", "待发送的企业消息" -> {
                Log.d("WeComService", "Step 4: At Send Page")
                delay(800) // 等待页面渲染稳定
                val root = rootInActiveWindow ?: rootNode
                if (!clickSendButton(root, "发送")) {
                    if (!clickSendButton(root, "去发送")) {
                        Log.d("WeComService", "Send button not found, searching by class...")
                        // 备选方案：寻找可点击的 Button
                        findAndClickGenericButton(root)
                    }
                }
                if (root != rootNode) root.recycle()
            }
            "详情" -> {
                performBackClick(rootNode)
            }
            else -> {
                // 如果在未知页面，尝试点击底部的“工作台”切回主线
                if (!clickNodeByText(rootNode, "工作台", minCenterY = 1700)) {
                    // 如果没看到工作台，尝试点一下“返回”
                    performBackClick(rootNode)
                }
            }
        }
        rootNode.recycle()
    }

    private fun detectPageTitle(root: AccessibilityNodeInfo): String {
        val targets = listOf("群发助手", "待发送的企业消息", "待发送", "详情", "工作台", "消息")
        for (target in targets) {
            val nodes = root.findAccessibilityNodeInfosByText(target)
            val match = nodes?.firstOrNull {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                val text = it.text?.toString() ?: ""
                // 标题通常在屏幕顶部 300 像素内
                rect.centerY() < 300 && text.contains(target)
            }
            nodes?.forEach { if (it != match) it.recycle() }
            if (match != null) {
                match.recycle()
                return target
            }
        }
        return ""
    }

    private fun processGroupAssistantPage(root: AccessibilityNodeInfo): Boolean {
        val keywords = listOf("条企业消息待发送", "待发送", "企业消息")
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            val taskNode = nodes?.firstOrNull {
                val txt = (it.text ?: "").toString() + (it.contentDescription ?: "").toString()
                (txt.contains("条") && txt.contains("待发送")) || txt.contains("待发送")
            }
            if (taskNode != null) {
                Log.d("WeComService", "Step 3: Found Task -> ${taskNode.text}")
                val success = clickNode(taskNode)
                nodes.forEach { it.recycle() }
                return success
            }
            nodes?.forEach { it.recycle() }
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var target: AccessibilityNodeInfo = node
        // 向上寻找可点击的容器
        while (current != null) {
            if (current.isClickable) {
                target = current
                break
            }
            current = current.parent
        }

        val rect = Rect()
        target.getBoundsInScreen(rect)
        Log.d("WeComService", "Clicking ${target.className} at ${rect.centerX()}, ${rect.centerY()}")

        var ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            ok = dispatchCoordinateClick(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        if (target != node) target.recycle()
        return ok
    }

    private fun clickNodeByText(root: AccessibilityNodeInfo, text: String, minCenterY: Int = 0, maxCenterY: Int = 9999): Boolean {
        val node = findNodeByText(root, text) ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cy = rect.centerY()
        if (cy in minCenterY..maxCenterY) {
            val ok = clickNode(node)
            node.recycle()
            return ok
        }
        node.recycle()
        return false
    }

    private fun clickSendButton(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val button = nodes?.firstOrNull {
            val rect = Rect()
            it.getBoundsInScreen(rect)
            val nodeText = it.text?.toString() ?: ""
            // 排除标题（通常在顶部），且文字匹配（或包含）
            rect.centerY() > 300 && nodeText.contains(text)
        }
        val result = if (button != null) {
            Log.d("WeComService", "Clicking '$text' button at center (${button.className})")
            clickNode(button)
        } else false
        nodes?.forEach { it.recycle() }
        return result
    }

    private fun findAndClickGenericButton(root: AccessibilityNodeInfo): Boolean {
        // 递归寻找屏幕下半部分且可点击的节点，名字包含“发送”的
        return searchAndClick(root)
    }

    private fun searchAndClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = (node.text ?: "").toString() + (node.contentDescription ?: "").toString()
        if (rect.centerY() > 300 && (text.contains("发送") || node.isClickable) && text.length < 10) {
            if (text.contains("发送")) {
                return clickNode(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClick(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val match = nodes?.firstOrNull {
            (it.text?.toString() ?: "").contains(text) || (it.contentDescription?.toString() ?: "").contains(text)
        }
        nodes?.forEach { if (it != match) it.recycle() }
        return match
    }

    private fun dispatchCoordinateClick(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performBackClick(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText("返回")
        val back = nodes?.firstOrNull {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.left < 200 && r.top < 300
        }
        val ok = if (back != null) clickNode(back) else false
        nodes?.forEach { it.recycle() }
        return ok
    }

    private fun isWithinTimeWindow(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val cur = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = startHour * 60 + startMinute
        val e = endHour * 60 + endMinute
        return if (s <= e) cur in s..e else cur >= s || cur <= e
    }

    private fun wakeScreenAndUnlock(pm: PowerManager, km: KeyguardManager) {
        if (isWakingUp) return
        isWakingUp = true
        serviceScope.launch {
            try {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Helper:Wake")
                wl.acquire(3000)
                if (km.isKeyguardLocked) {
                    val path = Path().apply {
                        val dm = resources.displayMetrics
                        moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.8f)
                        lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.2f)
                    }
                    dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 500)).build(), null, null)
                }
                delay(2000)
                launchWeCom()
            } finally {
                isWakingUp = false
            }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(configListener)
        serviceScope.cancel()
    }
}
