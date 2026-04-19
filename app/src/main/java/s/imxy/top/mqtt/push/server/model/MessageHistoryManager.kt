package s.imxy.top.mqtt.push.server.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessageHistoryManager {
    private val _messages = MutableStateFlow<List<MessageHistory>>(emptyList())
    val messages: StateFlow<List<MessageHistory>> = _messages

    fun addMessage(content: String, type: MessageHistory.MessageType, senderId: String = if (type == MessageHistory.MessageType.SEND) "me" else "unknown") {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = formatter.format(Date())
        
        val newMessage = MessageHistory(
            content = content,
            timestamp = timestamp,
            type = type,
            senderId = senderId
        )
        
        _messages.value = (_messages.value + newMessage).takeLast(100) // 只保留最近100条消息
    }

    fun clearHistory() {
        _messages.value = emptyList()
    }
}