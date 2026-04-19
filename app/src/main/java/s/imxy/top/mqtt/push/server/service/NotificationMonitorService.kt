package s.imxy.top.mqtt.push.server.service

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import s.imxy.top.mqtt.push.server.model.MqttConfigManager
import s.imxy.top.mqtt.push.server.model.NotificationInfo
import s.imxy.top.mqtt.push.server.service.MqttClientManager
import s.imxy.top.mqtt.push.server.service.MqttMessagePushManager

class NotificationMonitorService : NotificationListenerService() {

    // 从单例获取最新配置（无需绑定服务）
    private val mqttConfig get() = MqttConfigManager.currentConfig
    private val ownPackageName by lazy { this.packageName }
    private val systemPackageRegex = "^android|^com\\.android\\.".toRegex()

    // 主线程Handler，用于延迟执行推送
    private val mainHandler = Handler(Looper.getMainLooper())

    // 暂存通知最新内容（key：通知唯一标识，value：最新通知信息）
    private val latestNotificationMap = HashMap<String, NotificationInfo>()

    // 延迟时间（500ms，可调整，平衡实时性和去重）
    private val DELAY_MS = 100L

    // 当有新通知发布时触发
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        try {
            val packageName = sbn.packageName
            if (isOwnAppNotification(packageName) || isSystemNotification(
                    packageName
                )
            ) {
                return // 直接返回，不处理该通知
            }
            val notification = sbn.notification
            val extras = notification.extras
            val title: String = extras.getCharSequence("android.title")?.toString() ?: ""
            val content: String = extras.getCharSequence("android.text")?.toString() ?: ""
            if (title.isEmpty() && content.isEmpty()) {
                return // 跳过无内容的通知
            }
            val uniqueKey = "${sbn.tag ?: ""}:${sbn.id}"
            val newNotification = NotificationInfo(
                packageName = packageName,
                title = title,
                content = content,
                time = NotificationInfo.getCurrentTime(),
                from = MqttConfigManager.currentConfig.clientId
            )
            latestNotificationMap[uniqueKey] = newNotification
            mainHandler.removeCallbacksAndMessages(uniqueKey)
            // 提交新的延迟任务（500ms后执行）
            mainHandler.postDelayed({
                // 延迟结束后，推送最新的内容
                latestNotificationMap[uniqueKey]?.let {
                    MqttClientManager.sendNotification(it, mqttConfig.topic)
                    // 记录本地通知发送时间，用于与MQTT通知进行比较
                    MqttMessagePushManager.recordLocalNotificationSent(packageName)
                }
                // 推送后移除暂存（避免内存占用）
                latestNotificationMap.remove(uniqueKey)
            }, DELAY_MS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isOwnAppNotification(packageName: String): Boolean {
        return packageName == ownPackageName
    }

    /**
     * 判断是否是Android系统的通知
     */
    private fun isSystemNotification(packageName: String): Boolean {
        // 匹配系统包名规则（可根据需要补充其他系统包名）
        return systemPackageRegex.matches(packageName)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        latestNotificationMap.clear()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}