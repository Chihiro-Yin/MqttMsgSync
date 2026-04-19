package s.imxy.top.mqtt.push.server.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

object PermissionUtil {
    fun isAutoStartPermissionEnabled(context: Context): Boolean {
        val romType = getRomType()
        return try {
            when (romType) {
                "xiaomi" -> {
                    // 小米：通过系统设置数据库判断
                    val contentResolver = context.contentResolver
                    val uri = Settings.System.getUriFor("auto_start")
                    Settings.System.getInt(contentResolver, "auto_start", 0) == 1
                }

                "huawei" -> {
                    // 华为：通过系统服务判断（需华为SDK支持，兼容方案）
                    val clazz =
                        Class.forName("com.huawei.systemmanager.startupmgr.StartupManagerHelper")
                    val method =
                        clazz.getMethod("isStartupEnable", Context::class.java, String::class.java)
                    method.invoke(null, context, context.packageName) as Boolean
                }

                "oppo" -> {
                    // OPPO：通过系统设置判断
                    val contentResolver = context.contentResolver
                    val uri = Settings.System.getUriFor("oppo_auto_start")
                    Settings.System.getInt(contentResolver, "oppo_auto_start", 0) == 1
                }

                "vivo" -> {
                    // VIVO：通过系统服务判断
                    val clazz = Class.forName("com.vivo.permissionmanager.PermissionManager")
                    val method = clazz.getMethod(
                        "checkSelfPermission",
                        Context::class.java,
                        String::class.java
                    )
                    val result =
                        method.invoke(null, context, "android.permission.AUTO_START") as Int
                    result == 0 // 0表示已授权
                }

                else -> {
                    // 其他ROM：无公开判断接口，默认返回true（或提示用户手动检查）
                    true
                }
            }
        } catch (e: Exception) {
            // 反射失败（ROM版本变更），默认返回false，引导用户手动设置
            Log.e("PermissionUtil", "判断自启动权限失败：${e.message}")
            false
        }
    }

    fun goToAutoStartSettings(context: Context) {
        val romType = getRomType()
        val intent = Intent()
        when (romType) {
            "xiaomi" -> {
                // 小米：自启动管理专属页（比权限编辑页更精准）
                intent.action = "miui.intent.action.AUTO_START_MANAGER_ACTIVITY"
                intent.setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }

            "huawei" -> {
                // 华为：自启动设置页
                intent.action = "com.huawei.intent.action.HW_AUTO_START_SETTINGS"
                intent.setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }

            "oppo" -> {
                // OPPO：自启动管理页
                intent.action = "oppo.intent.action.OPPO_AUTO_START_SETTINGS"
                intent.setClassName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                intent.putExtra("package", context.packageName)
            }

            "vivo" -> {
                // VIVO：自启动管理页
                intent.action = "vivo.intent.action.AUTO_START_SETTINGS"
                intent.setClassName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }

            else -> {
                // 其他ROM：打开应用详情页
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = android.net.Uri.parse("package:${context.packageName}")
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 跳转失败，降级到应用详情页
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        }
    }

    /**
     * 辅助方法：判断ROM类型（和MainActivity的逻辑一致，可迁移到PermissionUtil复用）
     */
    private fun getRomType(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") -> "xiaomi"
            manufacturer.contains("huawei") -> "huawei"
            manufacturer.contains("oppo") -> "oppo"
            manufacturer.contains("vivo") -> "vivo"
            else -> "other"
        }
    }

    // 检查是否有通知监听权限
    fun hasNotificationPermission(activity: Activity): Boolean {
        val pkgName = activity.packageName
        val flat = Settings.Secure.getString(
            activity.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(pkgName)
    }

    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    fun goToBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 使用备用方案：跳转到应用详情页，用户可以在那里手动管理电池优化
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        }
    }

    // 跳转到通知监听权限设置页面
    fun goToNotificationPermissionSettings(activity: Activity) {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        activity.startActivity(intent)
    }
}