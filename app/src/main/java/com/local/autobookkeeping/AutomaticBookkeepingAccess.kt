package com.local.autobookkeeping

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
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
        return isEnabled(expectedService) {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .map { service -> service.resolveInfo }
        }
    }

    internal fun isEnabled(
        expectedService: ComponentName,
        enabledServices: () -> List<ResolveInfo?>,
    ): Boolean = try {
        enabledServices().any { resolveInfo ->
            resolveInfo?.serviceInfo?.let { info ->
                ComponentName.createRelative(info.packageName, info.name)
            } == expectedService
        }
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
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
