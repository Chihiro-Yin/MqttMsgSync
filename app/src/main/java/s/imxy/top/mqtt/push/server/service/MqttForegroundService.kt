package s.imxy.top.mqtt.push.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import s.imxy.top.mqtt.push.server.MainActivity
import s.imxy.top.mqtt.push.server.R

class MqttForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "mqtt_keep_alive_hide"
        private const val CHANNEL_NAME = "MQTT后台保活" // 名称仅系统可见
        private const val CHANNEL_DESCRIPTION = "用于维持MQTT后台连接稳定，无打扰通知"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        // 打印日志，方便确认服务是否启动（调试用）
        Log.d("MqttForegroundService", "服务创建，开始初始化通知渠道")
        createHideNotificationChannel()
        val notification = buildHideNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d("MqttForegroundService", "前台服务启动成功，通知ID：$NOTIFICATION_ID")
        
        // 启动时尝试启动MQTT客户端
        startMqttClient()
    }

    // 优化：新增渠道描述，适配部分系统要求
    private fun createHideNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_NONE // 设置为最低重要性
                ).apply {
                    description = CHANNEL_DESCRIPTION // 必需：部分系统要求渠道有描述
                    setShowBadge(false) // 不在应用图标上显示徽章
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET // 最大程度隐藏
                    setBypassDnd(false) // 不绕过勿扰模式
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
                Log.d("MqttForegroundService", "隐藏通知渠道创建成功")
            } catch (e: Exception) {
                Log.e("MqttForegroundService", "创建通知渠道失败：${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun buildHideNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("") // 空标题
            .setContentText("") // 空内容
            .setPriority(NotificationCompat.PRIORITY_MIN) // 最低优先级
            .setOngoing(true) // 必须设为true（保活服务，用户无法删除，且不显示）
            .setSilent(true) // 静默通知，无声音震动
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // 锁屏隐藏
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 使用透明图标或最小化图标
            .build()
    }

    private fun startMqttClient() {
        // 尝试启动MQTT客户端连接
        try {
            val config = s.imxy.top.mqtt.push.server.model.MqttConfigManager.currentConfig
            if (config.serverUrl.isNotBlank() && config.topic.isNotBlank()) {
                // 使用延迟确保服务完全启动后再连接
                Thread {
                    Thread.sleep(1000) // 延迟1秒，确保服务完全启动
                    MqttClientManager.connect(
                        context = this,
                        config = config,
                        callback = MqttMessagePushManager.getInstance(this),
                        onConnectStatus = { connected, msg ->
                            Log.d("MqttForegroundService", "MQTT连接状态: $msg")
                        }
                    )
                }.start()
            }
        } catch (e: Exception) {
            Log.e("MqttForegroundService", "启动MQTT客户端失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重要：返回 START_STICKY 确保服务被杀死后会被重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MqttForegroundService", "服务被销毁")
        
        // 尝试重启服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, this::class.java))
        } else {
            startService(Intent(this, this::class.java))
        }
    }
}