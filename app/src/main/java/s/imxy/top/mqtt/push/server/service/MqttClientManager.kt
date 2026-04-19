package s.imxy.top.mqtt.push.server.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import s.imxy.top.mqtt.push.server.model.MessageHistory
import s.imxy.top.mqtt.push.server.model.MessageHistoryManager
import s.imxy.top.mqtt.push.server.model.Model
import s.imxy.top.mqtt.push.server.model.NotificationInfo
import s.imxy.top.mqtt.push.server.util.SslUtil
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MqttClientManager {
    private const val TAG = "MQTT_Client_Manager"
    private var mqttClient: MqttClient? = null
    private var savedConfig: Model.MqttConfig? = null
    private val isConnectingOrConnected = AtomicBoolean(false)
    private val json = Gson()
    private val lock = Any()
    
    // 添加定时重连机制
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    fun connect(
        context: Context,
        config: Model.MqttConfig,
        callback: MqttCallback, // callback 仍然是 MqttCallback 类型，因为 MqttMessagePushManager 实现了它的子接口
        onConnectStatus: (Boolean, String) -> Unit
    ) {
        if (isConnectingOrConnected.get()) {
            onConnectStatus(true, "已连接或正在连接中")
            Log.w(TAG, "已连接或正在连接，忽略本次连接请求")
            return
        }

        Thread {
            synchronized(lock) {
                if (isConnectingOrConnected.get()) {
                    onConnectStatus(true, "已连接")
                    return@Thread
                }

                savedConfig = config
                isConnectingOrConnected.set(true)
                Log.d(TAG, "开始连接...")

                try {
                    val persistence = MemoryPersistence()
                    mqttClient = MqttClient(config.serverUrl, config.clientId, persistence)

                    // 🔥 核心改动：将 client 引用注入到回调管理器中
                    if (callback is MqttMessagePushManager) {
                        callback.setMqttClientRef { mqttClient }
                    }

                    mqttClient?.setCallback(callback)
                    Log.d(TAG, "创建并设置回调: ${config.serverUrl} | ${config.clientId}")

                    val connectOptions = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 60  // 增加连接超时时间
                        keepAliveInterval = 60
                        userName = config.user
                        password = config.password.toCharArray()
                        socketFactory = SslUtil.getTrustAllSSLSocketFactory()
                        isAutomaticReconnect = true  // 启用自动重连
                    }

                    mqttClient?.connect(connectOptions)

                    // 如果代码能执行到这里，说明连接已经成功
                    Log.i(TAG, "MQTT 同步连接成功!")

                    // 🔥 注意：这里的订阅现在是可选的，因为 connectComplete 也会做
                    // 但为了首次连接更快响应，我们保留它
                    // mqttClient?.subscribe(config.topic, 1)
                    // Log.i(TAG, "首次订阅主题成功: ${config.topic}")

                    onConnectStatus(true, "连接成功")

                } catch (e: Exception) {
                    isConnectingOrConnected.set(false)
                    val errorMsg = "连接失败：${e.message ?: "未知错误"}"
                    onConnectStatus(false, errorMsg)
                    Log.e(TAG, errorMsg, e)
                    mqttClient?.close()
                    mqttClient = null
                    
                    // 启动重连机制
                    scheduleReconnect(context, config, callback, onConnectStatus)
                }
            }
        }.start()
    }

    // 添加重连机制
    private fun scheduleReconnect(
        context: Context,
        config: Model.MqttConfig,
        callback: MqttCallback,
        onConnectStatus: (Boolean, String) -> Unit
    ) {
        Log.d(TAG, "安排30秒后重连...")
        reconnectExecutor.schedule({
            Log.d(TAG, "执行重连...")
            if (!isConnectingOrConnected.get()) {
                connect(context, config, callback) { connected, msg ->
                    Log.d(TAG, "重连结果: $msg")
                    onConnectStatus(connected, "重连: $msg")
                }
            }
        }, 30, TimeUnit.SECONDS)
    }

    fun sendNotification(notification: NotificationInfo, topic: String) {
        if (!isConnectingOrConnected.get() || mqttClient?.isConnected != true) return

        try {
            val message = MqttMessage()
            message.payload = json.toJson(notification).toByteArray(Charsets.UTF_8)
            message.qos = 1

            synchronized(lock) {
                if (mqttClient?.isConnected == true) {
                    mqttClient?.publish(topic, message)
                    Log.d(TAG, "发送消息成功：topic=$topic")
                    
                    // 记录发送的消息
                    // 只有非手动发送的消息才记录，避免重复记录
                    // 因为主动发送的消息已经在UI层添加到了历史记录中
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
        }
    }

    fun disconnect(onDisconnect: () -> Unit) {
        // 取消可能存在的重连任务
        reconnectExecutor.shutdownNow()
        
        // 在后台服务模式下，不应轻易断开连接
        // 只有在明确需要断开时才执行断开操作
        
        Thread {
            synchronized(lock) {
                // 不再强制断开连接，除非是用户明确操作
                // 对于后台服务，保持连接是必要的
                Log.d(TAG, "disconnect方法被调用，但后台服务模式下保持连接")
                onDisconnect()
                return@Thread
            }
        }.start()
    }

    fun forceDisconnect(onDisconnect: () -> Unit) {
        // 取消可能存在的重连任务
        reconnectExecutor.shutdownNow()
        
        if (!isConnectingOrConnected.get()) {
            onDisconnect()
            return
        }

        Thread {
            synchronized(lock) {
                if (!isConnectingOrConnected.get()) {
                    onDisconnect()
                    return@Thread
                }
                try {
                    if (mqttClient?.isConnected == true) {
                        // 强制断开，并禁止重连
                        mqttClient?.disconnectForcibly(0,0,false)
                    }
                    Log.i(TAG, "MQTT 强制断开连接成功")
                } catch (e: Exception) {
                    Log.e(TAG, "断开连接时发生异常", e)
                } finally {
                    mqttClient?.close()
                    mqttClient = null
                    savedConfig = null
                    isConnectingOrConnected.set(false)
                    onDisconnect()
                }
            }
        }.start()
    }

    fun isConnected(): Boolean {
        return synchronized(lock) {
            isConnectingOrConnected.get() && mqttClient?.isConnected == true
        }
    }
}