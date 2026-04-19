package s.imxy.top.mqtt.push.server.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import s.imxy.top.mqtt.push.server.MainActivity
import s.imxy.top.mqtt.push.server.service.MqttForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||  // 应用更新后
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {    // 其他应用更新后
            
            // 设备启动完成后，启动前台服务（或重新启动服务）
            val serviceIntent = Intent(context, MqttForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}