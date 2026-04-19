package s.imxy.top.mqtt.push.server.model

import android.os.Build

class Model {
    data class MqttConfig(
        val serverUrl: String = "ssl://你的mqtt服务器地址", // MQTT服务器地址（tcp协议）
        val topic: String = "android/notification", // 推送主题
        val clientId: String = "Android_${Build.MODEL ?: "UnknownDevice"}".replace(Regex("[^a-zA-Z0-9_-]"), "_"),
        val user: String = "你的mqtt用户名",
        val password: String = "你的mqtt密码"
    )
}