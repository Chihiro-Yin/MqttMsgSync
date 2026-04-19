package s.imxy.top.mqtt.push.server.model

data class MessageHistory(
    val id: String = System.currentTimeMillis().toString(),
    val content: String,
    val timestamp: String,
    val type: MessageType, // SEND 或 RECEIVE
    val senderId: String = if (type == MessageType.SEND) "me" else "unknown" // 发送者ID，"me"表示自己发送，其他表示接收
) {
    enum class MessageType {
        SEND, RECEIVE
    }
}