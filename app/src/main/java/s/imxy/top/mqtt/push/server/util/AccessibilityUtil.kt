package s.imxy.top.mqtt.push.server.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityUtil {
    /**
     * 判断无障碍服务是否已开启并运行
     */
    fun isAccessibilityServiceRunning(context: Context): Boolean {
        // 1. 目标服务：完整类名（包名+类名，替换为你的服务实际路径）
        val targetService = "s.imxy.top.mqtt.push.server.service.NotificationAccessibilityService"
        // 2. 拼接匹配标识（serviceInfo.id 格式：包名/服务类名:系统ID，核心部分是「包名/服务类名」）
        val matchStr = "${context.packageName}/$targetService"

        // 3. 获取所有开启的无障碍服务，遍历匹配（核心逻辑）
        return (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.getEnabledAccessibilityServiceList(0) // 0 = 不过滤任何服务，简化参数
            ?.any { it.id?.contains(matchStr) == true } ?: false
    }

    /**
     * 跳转到无障碍服务开启页面
     */
    fun goToAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        context.startActivity(intent)
    }
}