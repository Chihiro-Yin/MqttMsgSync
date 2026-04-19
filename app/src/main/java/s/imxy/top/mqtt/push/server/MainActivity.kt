package s.imxy.top.mqtt.push.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import s.imxy.top.mqtt.push.server.model.MessageHistory
import s.imxy.top.mqtt.push.server.model.MessageHistoryManager
import s.imxy.top.mqtt.push.server.model.Model
import s.imxy.top.mqtt.push.server.model.MqttConfigManager
import s.imxy.top.mqtt.push.server.model.NotificationInfo
import s.imxy.top.mqtt.push.server.service.MqttClientManager
import s.imxy.top.mqtt.push.server.service.MqttForegroundService
import s.imxy.top.mqtt.push.server.service.MqttMessagePushManager
import s.imxy.top.mqtt.push.server.util.PermissionUtil


class MainActivity : ComponentActivity() {
    private val currentConnectStatus = mutableStateOf("未连接")
    // 只创建一个推送实例（复用applicationContext）
    lateinit var mqttMessagePushManager: MqttMessagePushManager

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "通知权限已授予")
            currentConnectStatus.value = "通知权限已授予，尝试自动连接..."
            autoConnectMqtt()
        } else {
            currentConnectStatus.value = "通知权限未授予，无法推送"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        startMqttForegroundService()  // 确保首先启动前台服务
        setProcessPriority()

        // 🔥 核心改动：简化初始化，不再需要重连回调
        mqttMessagePushManager = MqttMessagePushManager.getInstance(this)

        setContent {
            MaterialTheme {
                MainScreen(
                    activity = this,
                    connectStatus = currentConnectStatus.value,
                    onStatusChange = { newStatus ->
                        currentConnectStatus.value = newStatus
                    }
                )
            }
        }

        if (PermissionUtil.hasNotificationPermission(this)) {
            autoConnectMqtt()
        }
    }

    fun startMqttForegroundService() {
        val intent = Intent(this, MqttForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun setProcessPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            Log.d("MainActivity", "进程优先级提升至前台级别")
        } catch (e: Exception) {
            Log.e("MainActivity", "提升进程优先级失败：${e.message}")
        }
    }

    // 将应用最小化到后台
    fun moveAppToBackground() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    // 🔥 核心改动：此方法现在只用于"首次自动连接"
    private fun autoConnectMqtt() {
        Log.d("MainActivity", "执行 autoConnectMqtt 自动连接...")

        val savedConfig = MqttConfigManager.currentConfig

        if (savedConfig.serverUrl.isBlank() || savedConfig.topic.isBlank()) {
            val msg = "无有效本地配置，请手动连接"
            Log.w("MainActivity", msg)
            if (currentConnectStatus.value == "未连接") {
                currentConnectStatus.value = msg
            }
            return
        }

        if (MqttClientManager.isConnected()) {
            Log.d("MainActivity", "已连接，无需自动连接。")
            currentConnectStatus.value = "已连接" // 确保UI状态正确
            return  // 不再自动最小化到后台
        }

        Log.d("MainActivity", "使用本地配置发起连接...")
        currentConnectStatus.value = "尝试自动连接..."
        MqttClientManager.connect(
            context = this,
            config = savedConfig,
            callback = mqttMessagePushManager,
            onConnectStatus = { _, msg ->
                runOnUiThread {
                    currentConnectStatus.value = msg
                    // 不再自动最小化到后台
                }
            }
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "通知权限已授予")
                }
                else -> {
                    requestNotificationPermissionLauncher.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不要在onDestroy中断开连接，因为后台运行时可能触发此方法
        // MQTT连接由MqttClientManager和前台服务管理
        Log.d("MainActivity", "MainActivity已销毁，但保持MQTT连接在后台服务中运行")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    connectStatus: String,
    onStatusChange: (String) -> Unit
) {
    var selectedScreen by remember { mutableStateOf(Screen.HOME) }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.values().forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                Screen.HOME -> Icon(Icons.Default.Home, contentDescription = null)
                                Screen.SETTINGS -> Icon(Icons.Default.Settings, contentDescription = null)
                            }
                        },
                        label = { Text(screen.title) },
                        selected = selectedScreen == screen,
                        onClick = { selectedScreen = screen }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedScreen) {
                Screen.HOME -> HomeScreen(activity, connectStatus, onStatusChange)
                Screen.SETTINGS -> SettingsScreen(activity, connectStatus, onStatusChange)
            }
        }
    }
}

@Composable
fun HomeScreen(
    activity: MainActivity,
    connectStatus: String,
    onStatusChange: (String) -> Unit
) {
    val messages by s.imxy.top.mqtt.push.server.model.MessageHistoryManager.messages.collectAsState()
    val lazyListState = rememberLazyListState()
    
    // 当消息更新时，自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            lazyListState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 消息历史记录
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages.withIndex().toList()) { (index, message) ->
                val prevMessage = if (index > 0) messages[index - 1] else null
                val nextMessage = if (index < messages.size - 1) messages[index + 1] else null
                
                MessageItemWithGrouping(
                    message = message,
                    isGroupStart = isGroupStart(message, prevMessage),
                    isGroupEnd = isGroupEnd(message, nextMessage)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 发送消息区域 - 简洁版
        SendMessageBox()

    }
}

// 检查是否是消息组的开始
fun isGroupStart(current: MessageHistory, previous: MessageHistory?): Boolean {
    return previous == null || 
           current.type != previous.type || 
           current.senderId != previous.senderId
}

// 检查是否是消息组的结束
fun isGroupEnd(current: MessageHistory, next: MessageHistory?): Boolean {
    return next == null || 
           current.type != next.type || 
           current.senderId != next.senderId
}

@Composable
fun MessageItemWithGrouping(
    message: s.imxy.top.mqtt.push.server.model.MessageHistory,
    isGroupStart: Boolean,
    isGroupEnd: Boolean
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val gson = Gson()
    
    // 提取设备名称和消息内容
    val deviceName = remember(message) {
        if (message.type == MessageHistory.MessageType.SEND) {
            "我"
        } else {
            // 尝试从消息内容中解析JSON
            try {
                val notificationInfo = gson.fromJson(message.content, NotificationInfo::class.java)
                notificationInfo.from
            } catch (e: Exception) {
                // 如果解析失败，返回默认值
                "未知设备"
            }
        }
    }
    
    val actualContent = remember(message) {
        if (message.type == MessageHistory.MessageType.RECEIVE) {
            // 尝试从JSON消息中解析内容
            try {
                val notificationInfo = gson.fromJson(message.content, NotificationInfo::class.java)
                notificationInfo.content
            } catch (e: Exception) {
                // 如果解析失败，返回原始内容
                message.content
            }
        } else {
            // 对于发送的消息，直接返回内容
            message.content
        }
    }
    
    // 确定气泡形状
    val bubbleShape = when {
        isGroupStart && isGroupEnd -> RoundedCornerShape(14.dp) // 单个消息，全部圆角
        isGroupStart && !isGroupEnd -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 8.dp, bottomEnd = 8.dp) // 组开始，底部稍平
        !isGroupStart && isGroupEnd -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 14.dp, bottomEnd = 14.dp) // 组结束，顶部稍平
        else -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 8.dp) // 组中间，左右平直
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        horizontalArrangement = if (message.type == MessageHistory.MessageType.SEND) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Column(
            horizontalAlignment = if (message.type == MessageHistory.MessageType.SEND) {
                Alignment.End
            } else {
                Alignment.Start
            }
        ) {
            // 显示设备名称（如果需要显示）
            if (isGroupStart && message.type == MessageHistory.MessageType.RECEIVE) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (message.type == MessageHistory.MessageType.SEND) {
                        Color.White.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            
            Card(
                modifier = Modifier
                    .wrapContentWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { /* 按下处理，暂无 */ },
                            onLongPress = {
                                // 复制消息内容到剪贴板（不包含设备名）
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(actualContent))
                                
                                // 显示Toast提示
                                Toast.makeText(context, "消息已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            },
                            onTap = { /* 单击处理，暂无 */ }
                        )
                    },
                colors = if (message.type == MessageHistory.MessageType.SEND) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                } else {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = bubbleShape
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = actualContent,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal)
                    )
                    
                    // 只在组的末尾显示时间戳
                    if (isGroupEnd) {
                        Text(
                            text = message.timestamp,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = if (message.type == MessageHistory.MessageType.SEND) {
                                Color.White.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SendMessageBox() {
    var sendMessage by remember { mutableStateOf("") }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextField(
            value = sendMessage,
            onValueChange = { sendMessage = it },
            placeholder = { Text("输入消息...") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )
        
        FloatingActionButton(
            onClick = {
                if (sendMessage.isNotBlank()) {
                    // 立即添加到本地消息历史记录（标记为发送），只存储用户输入的消息内容
                    s.imxy.top.mqtt.push.server.model.MessageHistoryManager.addMessage(
                        sendMessage, // 直接存储用户输入的消息内容
                        s.imxy.top.mqtt.push.server.model.MessageHistory.MessageType.SEND,
                        "me" // 标识为自己发送
                    )
                    
                    // 发送消息到MQTT服务器
                    val notificationInfo = s.imxy.top.mqtt.push.server.model.NotificationInfo(
                        packageName = "ManualSend", // 手动发送的消息
                        title = "手动发送",
                        content = sendMessage,
                        time = s.imxy.top.mqtt.push.server.model.NotificationInfo.getCurrentTime(),
                        from = MqttConfigManager.currentConfig.clientId
                    )
                    
                    // 发送消息到MQTT服务器
                    val topic = MqttConfigManager.currentConfig.topic
                    if (topic.isNotBlank()) {
                        MqttClientManager.sendNotification(notificationInfo, topic)
                    }
                    
                    // 清空输入框
                    sendMessage = ""
                }
            },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
        }
    }
}

@Composable
fun SettingsScreen(
    activity: MainActivity,
    connectStatus: String,
    onStatusChange: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf(MqttConfigManager.currentConfig.serverUrl) }
    var topic by remember { mutableStateOf(MqttConfigManager.currentConfig.topic) }
    var user by remember { mutableStateOf(MqttConfigManager.currentConfig.user) }
    var password by remember { mutableStateOf(MqttConfigManager.currentConfig.password) }
    var hasNotificationPerm by remember {
        mutableStateOf(PermissionUtil.hasNotificationPermission(activity))
    }
    var isBatteryOptimizationDisabled by remember {
        mutableStateOf(PermissionUtil.isBatteryOptimizationDisabled(activity))
    }
    var isAutoStartEnabled by remember {
        mutableStateOf(PermissionUtil.isAutoStartPermissionEnabled(activity))
    }

    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasNotificationPerm = PermissionUtil.hasNotificationPermission(activity)
            isBatteryOptimizationDisabled = PermissionUtil.isBatteryOptimizationDisabled(activity)
            isAutoStartEnabled = PermissionUtil.isAutoStartPermissionEnabled(activity)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "通知权限")
            Switch(
                checked = hasNotificationPerm,
                onCheckedChange = {
                    PermissionUtil.goToNotificationPermissionSettings(activity)
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "关闭省电限制")
            Switch(
                checked = isBatteryOptimizationDisabled,
                onCheckedChange = { PermissionUtil.goToBatteryOptimizationSettings(activity) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "自启动权限")
            Switch(
                checked = isAutoStartEnabled,
                onCheckedChange = { PermissionUtil.goToAutoStartSettings(activity) }
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("MQTT服务器地址") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("推送主题") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("账号") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = {
                val newConfig = Model.MqttConfig(
                    serverUrl = serverUrl,
                    topic = topic,
                    user = user,
                    password = password,
                    clientId = "Android_${Build.MODEL ?: "UnknownDevice"}".replace(
                        Regex("[^a-zA-Z0-9_-]"),
                        "_"
                    )
                )
                MqttConfigManager.currentConfig = newConfig

                // 如果已连接，重新连接以应用新配置
                if (MqttClientManager.isConnected()) {
                    MqttClientManager.forceDisconnect {
                        activity.runOnUiThread {
                            onStatusChange("配置已保存，正在重新连接...")
                            
                            MqttClientManager.connect(
                                context = activity,
                                config = newConfig,
                                callback = activity.getMqttMessagePushManager(),
                                onConnectStatus = { _, msg ->
                                    activity.runOnUiThread { onStatusChange(msg) }
                                }
                            )
                        }
                    }
                } else {
                    onStatusChange("配置已保存")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存配置")
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 添加隐藏后台并保活的按钮
        Button(
            onClick = {
                // 确保前台服务正在运行
                activity.startMqttForegroundService()
                
                // 如果尚未连接MQTT，尝试连接
                if (!MqttClientManager.isConnected()) {
                    val config = MqttConfigManager.currentConfig
                    if (config.serverUrl.isNotBlank() && config.topic.isNotBlank()) {
                        MqttClientManager.connect(
                            context = activity,
                            config = config,
                            callback = activity.getMqttMessagePushManager(),
                            onConnectStatus = { _, msg ->
                                activity.runOnUiThread { onStatusChange(msg) }
                            }
                        )
                    }
                }
                
                // 将应用移动到后台
                activity.moveAppToBackground()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("隐藏后台并保活")
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "当前状态：$connectStatus",
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                connectStatus.contains("成功") || connectStatus.contains("已连接") -> MaterialTheme.colorScheme.primary
                connectStatus.contains("失败") || connectStatus.contains("断开") -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onBackground
            }
        )
    }
}

fun MainActivity.getMqttMessagePushManager(): MqttMessagePushManager {
    return this.mqttMessagePushManager
}