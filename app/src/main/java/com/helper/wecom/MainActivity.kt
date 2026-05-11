package com.helper.wecom

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helper.wecom.service.WeComAccessibilityService
import com.helper.wecom.ui.theme.WeComHelperTheme

class MainActivity : ComponentActivity() {

    private var isServiceEnabledState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        setContent {
            var isRunning by remember { mutableStateOf<Boolean>(WeComAccessibilityService.isRunning) }
            var showSheet by remember { mutableStateOf(false) }

            // Configuration states
            var taskType by remember { mutableStateOf(prefs.getInt("taskType", 0)) } // 0: 群发任务, 1: 清理单向好友
            var executionMode by remember { mutableStateOf(prefs.getInt("executionMode", 0)) } // 0: Immediate, 1: Scheduled
            var startHour by remember { mutableStateOf(prefs.getInt("startHour", 19)) }
            var startMinute by remember { mutableStateOf(prefs.getInt("startMinute", 0)) }
            var endHour by remember { mutableStateOf(prefs.getInt("endHour", 21)) }
            var endMinute by remember { mutableStateOf(prefs.getInt("endMinute", 30)) }
            var autoWake by remember { mutableStateOf(prefs.getBoolean("autoWake", false)) }

            WeComHelperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isServiceEnabled = isServiceEnabledState,
                        isRunning = isRunning,
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onStopClick = {
                            WeComAccessibilityService.isRunning = false
                            isRunning = false
                        },
                        onGroupMessageClick = {
                            if (!isServiceEnabledState) {
                                Toast.makeText(this@MainActivity, "请先开启辅助功能权限", Toast.LENGTH_SHORT).show()
                            } else {
                                taskType = 0
                                prefs.edit().putInt("taskType", 0).apply()
                                showSheet = true
                            }
                        },
                        onCleanFriendsClick = {
                            if (!isServiceEnabledState) {
                                Toast.makeText(this@MainActivity, "请先开启辅助功能权限", Toast.LENGTH_SHORT).show()
                            } else {
                                taskType = 1
                                prefs.edit().putInt("taskType", 1).apply()
                                showSheet = true
                            }
                        }
                    )

                    if (showSheet) {
                        ConfigBottomSheet(
                            executionMode = executionMode,
                            startHour = startHour,
                            startMinute = startMinute,
                            endHour = endHour,
                            endMinute = endMinute,
                            autoWake = autoWake,
                            onConfigChange = { mode, h1, m1, h2, m2, wake ->
                                executionMode = mode
                                startHour = h1
                                startMinute = m1
                                endHour = h2
                                endMinute = m2
                                autoWake = wake
                                prefs.edit().apply {
                                    putInt("executionMode", mode)
                                    putInt("startHour", h1)
                                    putInt("startMinute", m1)
                                    putInt("endHour", h2)
                                    putInt("endMinute", m2)
                                    putBoolean("autoWake", wake)
                                    apply()
                                }
                            },
                            onConfirm = {
                                showSheet = false
                                WeComAccessibilityService.isRunning = true
                                isRunning = true
                                launchWeCom()
                            },
                            onDismiss = { showSheet = false }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isServiceEnabledState = isAccessibilityServiceEnabled(this)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedService = "${context.packageName}/${WeComAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledServices == null) return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun launchWeCom() {
        val intent = packageManager.getLaunchIntentForPackage("com.tencent.wework")
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "未找到企业微信", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MainScreen(
    isServiceEnabled: Boolean,
    isRunning: Boolean,
    onOpenSettings: () -> Unit,
    onStopClick: () -> Unit,
    onGroupMessageClick: () -> Unit,
    onCleanFriendsClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Icon Placeholder / Decorative Element
            Surface(
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "企业微信助手",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRunning) "自动化运行中..." else "准备就绪，点击下方按钮开始",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Accessibility Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceEnabled) 
                        Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isServiceEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isServiceEnabled) "辅助功能权限：已开启" else "辅助功能权限：点击开启",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isServiceEnabled) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                }
            }

            Spacer(modifier = Modifier.height(64.dp))

            if (isRunning) {
                // Stop Button
                Button(
                    onClick = onStopClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "停止自动化任务",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            } else {
                // Two separate action buttons
                Button(
                    onClick = onGroupMessageClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "开始群发任务",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onCleanFriendsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = "开始清理单向好友",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheet(
    executionMode: Int,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    autoWake: Boolean,
    onConfigChange: (Int, Int, Int, Int, Int, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "任务设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Execution Mode Selection
            Text(
                text = "选择执行模式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeTab(
                    text = "立即执行",
                    selected = executionMode == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfigChange(0, startHour, startMinute, endHour, endMinute, autoWake) }
                )
                ModeTab(
                    text = "定时执行",
                    selected = executionMode == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfigChange(1, startHour, startMinute, endHour, endMinute, autoWake) }
                )
            }

            if (executionMode == 1) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "执行时间段",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TimeBox(hour = startHour, minute = startMinute, label = "开始时间") { h, m ->
                            onConfigChange(1, h, m, endHour, endMinute, autoWake)
                        }
                        Text("至", modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        TimeBox(hour = endHour, minute = endMinute, label = "结束时间") { h, m ->
                            onConfigChange(1, startHour, startMinute, h, m, autoWake)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Auto-Wake Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "自动唤醒屏幕", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(text = "到时间自动点亮屏幕并执行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoWake,
                    onCheckedChange = { onConfigChange(executionMode, startHour, startMinute, endHour, endMinute, it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(text = "立即开启自动化", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun ModeTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TimeBox(hour: Int, minute: Int, label: String, onTimeChange: (Int, Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimeDigit(value = hour) { onTimeChange(it, minute) }
            Text(":", modifier = Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.Bold)
            TimeDigit(value = minute) { onTimeChange(hour, it) }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TimeDigit(value: Int, onValueChange: (Int) -> Unit) {
    // Local state to hold the string being edited
    var text by remember { mutableStateOf(String.format("%02d", value)) }
    
    // Sync local text with external value ONLY if the external value actually changes
    // and is different from what we currently have parsed.
    LaunchedEffect(value) {
        val parsed = text.toIntOrNull() ?: -1
        if (parsed != value) {
            text = String.format("%02d", value)
        }
    }
    
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 2 && input.all { it.isDigit() }) {
                text = input
                // Update parent state: if empty, treat as 0; otherwise parse
                val newVal = if (input.isEmpty()) 0 else input.toInt()
                if (newVal in 0..59) {
                    onValueChange(newVal)
                }
            }
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.width(32.dp),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
        )
    )
}

// Remove the redundant BasicTextField wrapper since we are using it directly now
