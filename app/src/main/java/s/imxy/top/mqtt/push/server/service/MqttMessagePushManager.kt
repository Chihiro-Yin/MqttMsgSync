package s.imxy.top.mqtt.push.server.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.gson.Gson
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended // 🔥 核心改动：使用扩展回调
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import s.imxy.top.mqtt.push.server.MainActivity
import s.imxy.top.mqtt.push.server.R
import s.imxy.top.mqtt.push.server.model.MessageHistory
import s.imxy.top.mqtt.push.server.model.MessageHistoryManager
import s.imxy.top.mqtt.push.server.model.MqttConfigManager
import s.imxy.top.mqtt.push.server.model.NotificationInfo


class MqttMessagePushManager private constructor(
    private val appContext: Context,
    private var mqttClientRef: (() -> MqttClient?)? = null
) : MqttCallbackExtended { // 🔥 核心改动：实现 MqttCallbackExtended
    private val gson by lazy { Gson() }
    private val packageManager by lazy { appContext.packageManager }
    private val notificationManager: NotificationManager by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // 记录本地通知发送时间（即手机上应用发送通知的时间），用于与MQTT通知进行比较
    private val localNotificationTimestamps = mutableMapOf<String, Long>()
    private val NOTIFICATION_SUPPRESSION_WINDOW_MS = 1000L // 1秒内的重复通知将被抑制

    private val CHANNEL_ID = "mqtt_receive_channel"
    private val CHANNEL_NAME = "消息同步"
    private val NOTIFICATION_ID = 10086
    private val TAG = "MQTT_Push_Manager"

    init {
        createNotificationChannel()
        Log.d(TAG, "初始化完成（applicationContext）")
    }
    fun setMqttClientRef(clientRef: () -> MqttClient?) {
        this.mqttClientRef = clientRef
    }
    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        Log.i(TAG, "MQTT 连接完成。是否为重连: $reconnect")
        // 无论是首次连接还是重连，我们都尝试订阅主题
        try {
            val topic = MqttConfigManager.currentConfig.topic
            if (topic.isNotBlank()) {
                // 使用注入的 client 引用来重新订阅
                mqttClientRef?.invoke()?.subscribe(topic, 1)
                Log.i(TAG, "在 connectComplete 中订阅/重新订阅主题成功: $topic")
            }
        } catch (e: Exception) {
            Log.e(TAG, "在 connectComplete 中订阅/重新订阅失败", e)
        }
    }

    override fun connectionLost(cause: Throwable?) {
        val errorMsg = cause?.message ?: "未知原因"
        Log.w(TAG, "MQTT 连接丢失，Paho 将自动重连: $errorMsg")
        // 不需要调用任何手动回调，Paho客户端会自动重连
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        runCatching {
            if (topic.isNullOrEmpty() || message == null) return@runCatching

            val jsonStr = String(message.payload, Charsets.UTF_8)
            val notificationInfo = gson.fromJson(jsonStr, NotificationInfo::class.java)

            val currentClientId = MqttConfigManager.currentConfig.clientId
            // 仅忽略非手动发送的自身消息
            if (notificationInfo.from == currentClientId) {
                Log.w(TAG, "忽略自身消息：$jsonStr")
                return@runCatching
            }

            // 检查是否刚刚本地已发送过相同包名的通知，如果是则不发送MQTT通知
            if (notificationInfo.packageName.isNotEmpty() && notificationInfo.packageName != "ManualSend") {
                val lastLocalNotificationTime = localNotificationTimestamps[notificationInfo.packageName]
                val currentTime = System.currentTimeMillis()
                
                if (lastLocalNotificationTime != null && 
                    (currentTime - lastLocalNotificationTime) < NOTIFICATION_SUPPRESSION_WINDOW_MS) {
                    Log.d(TAG, "应用 ${notificationInfo.packageName} 本地已发送通知，跳过MQTT通知（时间差: ${currentTime - lastLocalNotificationTime}ms）")
                    return@runCatching
                }
            }

            // 记录收到的消息
            // 跳过手动发送的消息，避免重复记录
            if (notificationInfo.packageName != "ManualSend") {
                MessageHistoryManager.addMessage(
                    jsonStr, // 存储原始JSON字符串
                    MessageHistory.MessageType.RECEIVE,
                    notificationInfo.from // 使用from字段作为发送者ID
                )
            } else {
                // 对于手动发送的消息，仍然添加到历史记录，但标记为接收
                MessageHistoryManager.addMessage(
                    jsonStr, // 存储原始JSON字符串
                    MessageHistory.MessageType.RECEIVE,
                    notificationInfo.from // 使用from字段作为发送者ID
                )
            }

            if (!hasNotificationPermission()) {
                Log.w(TAG, "通知权限未开启，无法推送")
                return@runCatching
            }
            sendNotification(topic, notificationInfo)
        }.onFailure { e ->
            Log.e(TAG, "消息处理失败：${e.message}", e)
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收MQTT服务器推送的消息（强提醒）"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun sendNotification(topic: String, info: NotificationInfo) {
        val targetPackageName = info.packageName.takeIf { it.isNotEmpty() }?.trim()
        val isPackageEmpty = targetPackageName.isNullOrEmpty()

        try {
            val (appIcon, appLargeIcon, appName) = if (!isPackageEmpty) {
                try {
                    val appInfo = packageManager.getApplicationInfo(
                        targetPackageName!!,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES // 添加此标志以获取已卸载应用的信息
                    )
                    val appDrawable = appInfo.loadIcon(packageManager)
                    val bitmap = drawableToBitmap(appDrawable)
                    val name = appInfo.loadLabel(packageManager).toString()
                    Triple(IconCompat.createWithBitmap(bitmap), bitmap, name)
                } catch (e: PackageManager.NameNotFoundException) {
                    // 包名对应的App不存在，使用默认图标和名称
                    Log.w(TAG, "应用未安装：$targetPackageName", e)
                    val defaultDrawable = ContextCompat.getDrawable(appContext, R.mipmap.ic_launcher)!!
                    val bitmap = drawableToBitmap(defaultDrawable)
                    Triple(IconCompat.createWithBitmap(bitmap), bitmap, targetPackageName!!)
                }
            } else {
                val defaultDrawable = ContextCompat.getDrawable(appContext, R.mipmap.ic_launcher)!!
                val bitmap = drawableToBitmap(defaultDrawable)
                Triple(IconCompat.createWithBitmap(bitmap), bitmap, "MQTT消息")
            }

            val pendingIntent = if (!isPackageEmpty) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackageName!!)
                    launchIntent?.let {
                        PendingIntent.getActivity(
                            appContext,
                            targetPackageName.hashCode(),
                            it,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } ?: PendingIntent.getActivity(
                        appContext,
                        "default_$targetPackageName".hashCode(),
                        Intent(appContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } catch (e: Exception) {
                    // 如果无法获取启动意图，使用默认意图
                    PendingIntent.getActivity(
                        appContext,
                        "default_$targetPackageName".hashCode(),
                        Intent(appContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            } else {
                PendingIntent.getActivity(
                    appContext,
                    "default_empty_package".hashCode(),
                    Intent(appContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val contentTitle = if (!isPackageEmpty) {
                if (info.title.isNotEmpty()) "$appName - ${info.title}" else appName
            } else {
                info.title.ifEmpty { "无标题" }
            }

            val contentText = info.content.ifEmpty { "无消息内容" }

            val notificationBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(appIcon)
                .setLargeIcon(appLargeIcon)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(longArrayOf(0, 500, 200, 500))
                .setLights(android.graphics.Color.RED, 1000, 1000)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            if (ActivityCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val notificationId = if (isPackageEmpty) {
                    System.currentTimeMillis().toInt()
                } else {
                    targetPackageName!!.hashCode() + NOTIFICATION_ID
                }
                
                notificationManager.notify(notificationId, notificationBuilder.build())
                
                Log.d(TAG, "推送通知成功（应用：$appName）：topic=$topic，package=$targetPackageName")
            }

        } catch (e: Exception) {
            showTipNotification("推送失败：${e.message ?: "未知错误"}", info)
            Log.e(TAG, "发送通知失败", e)
        }
    }

    private fun showTipNotification(tip: String, info: NotificationInfo) {
        val defaultDrawable = ContextCompat.getDrawable(appContext, R.mipmap.ic_launcher)!!
        val bitmap = drawableToBitmap(defaultDrawable)
        val appIcon = IconCompat.createWithBitmap(bitmap)

        val defaultIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            "tip_notification".hashCode(),
            defaultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 处理包名不存在的情况
        val title = if (info.packageName.isNotEmpty() && info.title.isNotEmpty()) {
            try {
                val appInfo = packageManager.getApplicationInfo(
                    info.packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
                val appName = appInfo.loadLabel(packageManager).toString()
                "$appName - ${info.title}"
            } catch (e: PackageManager.NameNotFoundException) {
                // 包名对应的App不存在，直接使用原始标题
                info.title
            }
        } else {
            info.title.ifEmpty { "无标题" }
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(appIcon)
            .setLargeIcon(bitmap)
            .setContentTitle("$title（$tip）")
            .setContentText(info.content.ifEmpty { "无消息内容" })
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        if (ActivityCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
            
            // 同样，这里也不应该记录MQTT通知的发送时间
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 100,
            if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 100,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }


    companion object {
        @Volatile
        private var instance: MqttMessagePushManager? = null

        // 🔥 核心改动：简化 getInstance 的签名
        fun getInstance(
            context: Context
        ): MqttMessagePushManager {
            return instance ?: synchronized(this) {
                instance ?: MqttMessagePushManager(
                    context.applicationContext
                ).also { instance = it }
            }
        }

        fun resetInstance() {
            Log.d("TAG", "重置实例")
            instance = null
        }
        
        // 提供公共方法供本地通知监控服务调用，记录本地通知发送时间
        fun recordLocalNotificationSent(packageName: String) {
            instance?.localNotificationTimestamps?.put(packageName, System.currentTimeMillis())
        }
    }
}