package s.imxy.top.mqtt.push.server.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import s.imxy.top.mqtt.push.server.model.NotificationInfo
import s.imxy.top.mqtt.push.server.model.MqttConfigManager
import s.imxy.top.mqtt.push.server.service.MqttClientManager
import s.imxy.top.mqtt.push.server.service.MqttMessagePushManager

class NotificationAccessibilityService : AccessibilityService() {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var keepAliveHandler: Handler? = null
    private var keepAliveRunnable: Runnable? = null

    // 必须实现：处理无障碍事件（如通知显示）
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val notificationText = getNotificationText(event) ?: ""
            val title = getNotificationTitle(event) ?: ""

            if (title.isEmpty() && notificationText.isEmpty()) return
            if (notificationText.contains("已隐藏敏感通知内容")) return

            val notificationInfo = NotificationInfo(
                packageName = packageName,
                title = title,
                content = notificationText,
                time = sdf.format(Date()),
                from = MqttConfigManager.currentConfig.clientId
            )

            val mqttTopic = MqttConfigManager.currentConfig.topic ?: return
            MqttClientManager.sendNotification(notificationInfo, mqttTopic)
            
            // 记录本地通知发送时间，用于与MQTT通知进行比较
            MqttMessagePushManager.recordLocalNotificationSent(packageName)
        }
    }

    // 必须实现：服务被中断时的回调
    override fun onInterrupt() {
        Log.d("AccessibilityService", "无障碍服务被中断，清理资源")
        stopKeepAliveTask() // 停止保活心跳（若无则忽略）
    }

    // 服务连接时初始化（可选）
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AccessibilityService", "无障碍服务已启动")
        startKeepAliveTask() // 启动保活心跳（若无则忽略）
    }

    // 服务销毁时清理资源（可选）
    override fun onDestroy() {
        super.onDestroy()
//        Log.d("AccessibilityService", "无障碍服务已销毁")
//        stopKeepAliveTask() // 停止保活心跳（若无则忽略）
    }

    // 粘性重启支持（可选）
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    // 辅助方法：提取通知标题（自定义）
    private fun getNotificationTitle(event: AccessibilityEvent): String? {
        return event.text.firstOrNull { it.isNotEmpty() }?.toString()
    }

    // 辅助方法：提取通知内容（自定义）
    private fun getNotificationText(event: AccessibilityEvent): String? {
        val nodeInfo = event.source ?: return null
        val textList = mutableListOf<String>()
//        traverseNode(nodeInfo, textList)
        nodeInfo.recycle()
        return textList.filter { it.isNotEmpty() }.joinToString(" ")
    }
//
//    // 辅助方法：递归遍历节点（自定义）
//    private fun traverseNode(nodeInfo: AccessibilityNodeInfo, textList: mutableListOf<String>) {
//        if (nodeInfo.text != null && nodeInfo.text.isNotEmpty()) {
//            textList.add(nodeInfo.text.toString())
//        }
//        for (i in 0 until nodeInfo.childCount) {
//            val childNode = nodeInfo.getChild(i)
//            childNode?.let { traverseNode(it, textList) }
//            childNode?.recycle()
//        }
//    }

    // 保活心跳任务（自定义，若无则忽略）
    private fun startKeepAliveTask() {
        keepAliveHandler = Handler(Looper.getMainLooper())
        keepAliveRunnable = Runnable {
            Log.d("AccessibilityService", "保活心跳：服务正常运行")
            keepAliveRunnable?.let { runnable ->
                keepAliveHandler?.postDelayed(runnable, 30 * 60 * 1000)
            }
        }
        keepAliveHandler?.postDelayed(keepAliveRunnable!!, 30 * 60 * 1000)
    }


    // 停止保活心跳（自定义，若无则忽略）
    private fun stopKeepAliveTask() {
        keepAliveRunnable?.let { keepAliveHandler?.removeCallbacks(it) }
        keepAliveHandler = null
        keepAliveRunnable = null
    }
}