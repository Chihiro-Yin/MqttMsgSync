package s.imxy.top.mqtt.push.server.model

import android.content.Context

object MqttConfigManager {
    // 全局唯一配置（只建一次，后续仅更新内容）
    var currentConfig: Model.MqttConfig = Model.MqttConfig()
    // 缓存Application Context（仅传一次，避免内存泄漏）
    private lateinit var appContext: Context

    // 初始化：在Activity中调用一次即可（后续不用传Context）
    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext // 只存全局Context
        }
    }

    // （可选）需要用Context时直接拿，不用再传
    fun getContext() = appContext
}