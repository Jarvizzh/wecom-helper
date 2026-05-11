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
    private var taskType = 0
    private var startHour = 19
    private var startMinute = 50
    private var endHour = 22
    private var endMinute = 10
    private var autoWake = false
    private var executionMode = 0 
    private var wakeInterval = 5 // 默认5分钟
    private var isWakingUp = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences

    private val configListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            "taskType" -> taskType = p.getInt("taskType", 0)
            "startHour" -> startHour = p.getInt("startHour", 19)
            "startMinute" -> startMinute = p.getInt("startMinute", 50)
            "endHour" -> endHour = p.getInt("endHour", 22)
            "endMinute" -> endMinute = p.getInt("endMinute", 10)
            "autoWake" -> autoWake = p.getBoolean("autoWake", false)
            "executionMode" -> executionMode = p.getInt("executionMode", 0)
            "wakeInterval" -> {
                wakeInterval = p.getInt("wakeInterval", 5)
                scheduleNextWake() // 间隔改变时重新排期
            }
        }
    }

    companion object {
        var isRunning = false
    }

    private val WAKE_ACTION = "com.helper.wecom.WAKE_ACTION"
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == WAKE_ACTION) {
                Log.d("WeComService", "Alarm received, waking system")
                if (isRunning) {
                    val isInWindow = if (executionMode == 1) isWithinTimeWindow() else true
                    if (isInWindow) {
                        checkAndWakeSystem()
                        triggerWorkflow()
                    }
                }
                scheduleNextWake()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("WeComService", "Service Connected")
        showNotification()
        
        val filter = android.content.IntentFilter(WAKE_ACTION)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            wakeReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        refreshConfig()
        prefs.registerOnSharedPreferenceChangeListener(configListener)
        startTicker()
    }

    private fun refreshConfig() {
        taskType = prefs.getInt("taskType", 0)
        startHour = prefs.getInt("startHour", 19)
        startMinute = prefs.getInt("startMinute", 50)
        endHour = prefs.getInt("endHour", 22)
        endMinute = prefs.getInt("endMinute", 10)
        autoWake = prefs.getBoolean("autoWake", false)
        executionMode = prefs.getInt("executionMode", 0)
        wakeInterval = prefs.getInt("wakeInterval", 5)
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
        scheduleNextWake()
    }

    private fun scheduleNextWake() {
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(WAKE_ACTION).setPackage(packageName)
        val pi = android.app.PendingIntent.getBroadcast(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // 使用动态设置的间隔
        val intervalMs = if (wakeInterval < 1) 5 * 60 * 1000L else wakeInterval * 60 * 1000L
        val triggerTime = System.currentTimeMillis() + intervalMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
            } else {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pi)
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

        // 核心修复：校验执行模式和时间窗口
        if (executionMode == 1) { // 如果是定时模式
            if (!isWithinTimeWindow()) {
                // 不在时间窗口内，跳过触发
                return
            }
        }

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

        if (taskType == 0) {
            handleGroupMessageTask(rootNode)
        } else if (taskType == 1) {
            handleDeleteOneWayFriendsTask(rootNode)
        }
        
        rootNode.recycle()
    }

    private suspend fun handleGroupMessageTask(rootNode: AccessibilityNodeInfo) {
        val pageTitle = detectPageTitle(rootNode)
        Log.d("WeComService", "Current Page (Group): $pageTitle")

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
    }

    private suspend fun handleDeleteOneWayFriendsTask(rootNode: AccessibilityNodeInfo) {
        // Step 1: Detect popups/menus first (highest priority)
        val confirmDelNode = findNodeByText(rootNode, "确认删除") ?: findNodeByText(rootNode, "确定")
        val cancelNode = findNodeByText(rootNode, "取消")
        if (confirmDelNode != null && cancelNode != null) {
            Log.d("WeComService", "Delete Step: Confirm Delete Popup")
            clickNode(confirmDelNode)
            confirmDelNode.recycle()
            cancelNode.recycle()
            // 确认删除后停顿 1.2 秒，等待列表项消失和刷新
            delay(1500)
            return
        }

        val menuDelNode = findNodeByText(rootNode, "删除此学员") ?: findNodeByText(rootNode, "删除")
        if (menuDelNode != null) {
            val rect = Rect()
            menuDelNode.getBoundsInScreen(rect)
            if (rect.width() < 800) {
                Log.d("WeComService", "Delete Step: Menu Delete Click")
                clickNode(menuDelNode)
                delay(500)
                return
            }
        }

        // Step 2: Page-based Logic
        val pageTitle = detectPageTitle(rootNode)
        Log.d("WeComService", "Delete Step: Current Page is '$pageTitle'")

        // 增强判定：即使 detectPageTitle 没认出“通讯录”，如果页面上有“新的客户”等特征，也认为是通讯录
        val isContacts = pageTitle == "通讯录" || isContactsPage(rootNode)

        if (isContacts) {
            // 优先寻找可点击的“我的学员”入口，避免误点 Header
            val myStudentsEntry = findClickableNodeByText(rootNode, "我的学员") ?: findClickableNodeByText(rootNode, "我的客户")
            if (myStudentsEntry != null) {
                Log.d("WeComService", "Delete Step: Found 'My Students' in Contacts, clicking...")
                clickNode(myStudentsEntry)
                myStudentsEntry.recycle()
                delay(500)
                return
            }
            // If not found or off-screen, try to scroll
            Log.d("WeComService", "Delete Step: 'My Students' not found or not clickable, scrolling...")
            val scrollable = findScrollableNode(rootNode)
            if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                delay(500)
            }
            return
        }

        when (pageTitle) {
            "我的学员", "单向微信学员" -> {
                val allNode = findNodeByText(rootNode, "全部微信学员")
                val oneWayNode = findNodeByText(rootNode, "单向微信学员")

                if (allNode != null && oneWayNode != null) {
                    // 菜单已打开（两个选项都可见），点击单向学员
                    Log.d("WeComService", "Delete Step: Filter menu open, selecting 'One-way'")
                    clickNode(oneWayNode)
                    allNode.recycle()
                    oneWayNode.recycle()
                    delay(500)
                    return
                }

                if (allNode != null) {
                    // 当前是“全部”，需要展开菜单
                    Log.d("WeComService", "Delete Step: Currently 'All', expanding filter...")
                    clickNode(allNode)
                    allNode.recycle()
                    delay(500)
                    return
                }

                if (oneWayNode != null) {
                    // 当前已经是“单向”，开始处理列表
                    val studentNode = findFirstStudentNode(rootNode)
                    if (studentNode != null) {
                        val rect = Rect()
                        studentNode.getBoundsInScreen(rect)
                        Log.d("WeComService", "Delete Step: Long Click Student at ${rect.centerY()}")
                        dispatchCoordinateLongClick(rect.centerX().toFloat(), rect.centerY().toFloat())
                        studentNode.recycle()
                        delay(500)
                    } else {
                        Log.d("WeComService", "Delete Step: No student detected, trying to scroll...")
                        val scrollable = findScrollableNode(rootNode)
                        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            delay(1000)
                        } else {
                            // 关键：如果没找到学生且不能滚动，先等待一下再试一次，避免加载慢导致的误判
                            delay(2000)
                            val retryNode = findFirstStudentNode(rootNode)
                            if (retryNode == null) {
                                Log.d("WeComService", "Delete Step: No students found after retry, ending task.")
                                isRunning = false
                            }
                        }
                    }
                    oneWayNode.recycle()
                    return
                }
            }
            "工作台", "消息" -> {
                Log.d("WeComService", "Delete Step: At main page, clicking Contacts tab")
                clickNodeByText(rootNode, "通讯录", minCenterY = 1700)
                delay(1000)
            }
            else -> {
                // 处理单向列表 fallback
                val oneWayNode = findNodeByText(rootNode, "单向微信学员")
                if (oneWayNode != null) {
                    // 已经在列表里（标题特征）
                    val studentNode = findFirstStudentNode(rootNode)
                    if (studentNode != null) {
                        val studentRect = Rect()
                        studentNode.getBoundsInScreen(studentRect)
                        Log.d("WeComService", "Delete Step: Long Click Student at ${studentRect.centerY()}")
                        dispatchCoordinateLongClick(studentRect.centerX().toFloat(), studentRect.centerY().toFloat())
                        studentNode.recycle()
                        delay(500)
                    } else {
                        Log.d("WeComService", "Delete Step: Scroll for more students")
                        val scrollable = findScrollableNode(rootNode)
                        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            delay(1000)
                        } else {
                            // 同理增加等待重试
                            delay(2000)
                            val retryNode = findFirstStudentNode(rootNode)
                            if (retryNode == null) {
                                Log.d("WeComService", "Delete Step: No students found, task end.")
                                isRunning = false
                            }
                        }
                    }
                    oneWayNode.recycle()
                    return
                }

                // Fallback: Try to find any navigation marker
                if (clickNodeByText(rootNode, "通讯录", minCenterY = 1700)) {
                    delay(1000)
                } else {
                    Log.d("WeComService", "Delete Step: Unknown page, performing back")
                    performBackClick(rootNode)
                }
            }
        }
    }

    private fun findFirstStudentNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. 优先使用特征文本 "@微信" 搜索（企业微信外部联系人的通用特征）
        val nodes = root.findAccessibilityNodeInfosByText("@微信")
        if (nodes != null && nodes.isNotEmpty()) {
            var student: AccessibilityNodeInfo? = null
            for (node in nodes) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 排除顶部和底部非列表区域
                if (rect.centerY() in 301..1799) {
                    // 向上寻找可点击的 RelativeLayout 列表项
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        val className = current.className?.toString() ?: ""
                        if (current.isClickable && (className.contains("RelativeLayout") || className.contains("ViewGroup"))) {
                            student = current
                            break
                        }
                        if (current == root) break
                        current = current.parent
                    }
                }
                if (student != null) break
            }
            nodes.forEach { if (it != student) it.recycle() }
            if (student != null) return student
        }

        // 2. 备选方案：遍历滚动容器（如果上述搜索失败）
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            for (i in 0 until scrollable.childCount) {
                val child = scrollable.getChild(i) ?: continue
                val rect = Rect()
                child.getBoundsInScreen(rect)
                // 排除标题、搜索框等，假设学生项高度 > 100 且是可点击的
                if (rect.centerY() in 400..1800 && rect.height() > 100 && child.isClickable) {
                    return child
                }
                child.recycle()
            }
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        // 优先匹配 RecyclerView
        if (node.isScrollable && (className.contains("RecyclerView") || className.contains("ListView"))) return node
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
            child.recycle()
        }
        
        // 兜底返回任何可滚动的
        if (node.isScrollable) return node
        return null
    }

    private fun detectPageTitle(root: AccessibilityNodeInfo): String {
        val targets = listOf("群发助手", "待发送的企业消息", "待发送", "详情", "工作台", "消息", "通讯录", "我的学员", "单向微信学员")
        for (target in targets) {
            val nodes = root.findAccessibilityNodeInfosByText(target)
            val match = nodes?.firstOrNull {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                val text = it.text?.toString() ?: ""
                // 1. 标题通常在屏幕顶部 (< 400)
                // 2. 或者在底部 Tab 且是被选中的 (selected=true)
                (rect.centerY() < 400 && text.contains(target)) || 
                (rect.centerY() > 1700 && it.isSelected && text.contains(target))
            }
            nodes?.forEach { if (it != match) it.recycle() }
            if (match != null) {
                match.recycle()
                return target
            }
        }
        return ""
    }

    private fun isContactsPage(root: AccessibilityNodeInfo): Boolean {
        // 通讯录页面的特征：有底部Tab栏，且包含“我的学员”或“新的客户”等
        val hasTab = findNodeByText(root, "工作台") != null || findNodeByText(root, "消息") != null
        if (!hasTab) return false

        val keys = listOf("添加学员", "添加客户", "我的学员", "我的客户", "企业通讯录")
        for (key in keys) {
            val node = findNodeByText(root, key)
            if (node != null) {
                node.recycle()
                return true
            }
        }
        return false
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

    private fun findClickableNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val match = nodes?.firstOrNull {
            val txt = (it.text?.toString() ?: "").contains(text) || (it.contentDescription?.toString() ?: "").contains(text)
            if (!txt) return@firstOrNull false
            
            // 检查自身或父节点是否可点击，且排除 RecyclerView 本身和 ScrollView 等容器
            var current: AccessibilityNodeInfo? = it
            var isClickable = false
            while (current != null) {
                val className = current.className?.toString() ?: ""
                if (current.isClickable && !className.contains("RecyclerView") && !className.contains("ListView")) {
                    isClickable = true
                    break
                }
                current = current.parent
            }
            isClickable
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

    private fun dispatchCoordinateLongClick(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800)) // 800ms for long press
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
                // 1. 唤醒屏幕
                val isInteractive = pm.isInteractive
                if (!isInteractive) {
                    Log.d("WeComService", "Screen is off, acquiring wake lock")
                    @Suppress("DEPRECATION")
                    wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Helper:Wake")
                    wakeLock?.acquire(5000)
                }

                delay(2000) // 等待屏幕点亮稳定

                // 2. 解锁并启动应用
                val intent = android.content.Intent(this@WeComAccessibilityService, com.helper.wecom.ui.UnlockActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)

            } catch (e: Exception) {
                Log.e("WeComService", "Wake/Unlock error", e)
            } finally {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                wakeLock = null
                isWakingUp = false
            }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(configListener)
        try {
            unregisterReceiver(wakeReceiver)
        } catch (e: Exception) {
            Log.e("WeComService", "Receiver not registered", e)
        }
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(WAKE_ACTION).setPackage(packageName)
        val pi = android.app.PendingIntent.getBroadcast(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        serviceScope.cancel()
    }
}
