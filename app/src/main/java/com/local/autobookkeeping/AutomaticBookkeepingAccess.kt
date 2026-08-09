package com.local.autobookkeeping

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.local.autobookkeeping.notification.NotificationAccess
import com.local.autobookkeeping.recognition.accessibility.PaymentPageAccessibilityService
import com.local.autobookkeeping.recognition.tile.QuickSettingsTileLauncher
import com.local.autobookkeeping.recognition.tile.TileAddResult

object AccessibilityAccess {
    fun isEnabled(context: Context): Boolean {
        val expectedService = ComponentName(
            context,
            PaymentPageAccessibilityService::class.java,
        )
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabledServices = try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { service ->
                    service.resolveInfo?.serviceInfo?.let { info ->
                        ComponentName(info.packageName, info.name)
                    }
                }
        } catch (_: SecurityException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
        return isEnabled(expectedService, enabledServices)
    }

    internal fun isEnabled(
        expectedService: ComponentName,
        enabledServices: List<ComponentName>,
    ): Boolean = enabledServices.any { component -> component == expectedService }
}

class AutomaticBookkeepingAccess(context: Context) {
    private val appContext = context.applicationContext
    private val tileLauncher by lazy { QuickSettingsTileLauncher(appContext) }

    fun isNotificationAccessEnabled(): Boolean = NotificationAccess.isEnabled(appContext)

    fun isAccessibilityAccessEnabled(): Boolean = AccessibilityAccess.isEnabled(appContext)

    fun openNotificationAccess(): Boolean =
        openNotificationAccessSafely(AndroidNotificationSettingsLauncher(appContext))

    fun openAccessibilitySettings(): Boolean =
        openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun openBatteryOptimizationSettings(): Boolean =
        openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun requestAddQuickSettingsTile(
        onResult: (TileAddResult) -> Unit = {},
    ): TileAddResult = tileLauncher.requestAdd(onResult)

    private fun openSystemSettings(action: String): Boolean = try {
        appContext.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
