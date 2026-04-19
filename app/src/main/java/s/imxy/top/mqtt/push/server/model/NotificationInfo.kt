package s.imxy.top.mqtt.push.server.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotificationInfo(
    val packageName: String, // 应用包名（如com.tencent.mm=微信）
    val title: String,       // 通知标题
    val content: String,     // 通知内容
    val time: String,         // 通知时间
    val from: String         //来自设备
) {
    companion object {
        // 时间格式化工具
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        fun getCurrentTime() = dateFormat.format(Date())
    }
}