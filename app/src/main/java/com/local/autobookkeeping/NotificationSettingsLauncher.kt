package com.local.autobookkeeping

import android.content.Context
import android.content.Intent

internal const val NOTIFICATION_ACCESS_ACTION =
    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
internal const val GENERAL_SETTINGS_ACTION = "android.settings.SETTINGS"

internal interface NotificationSettingsLauncher {
    fun canOpen(action: String): Boolean
    fun open(action: String)
}

internal class AndroidNotificationSettingsLauncher(
    private val context: Context,
) : NotificationSettingsLauncher {
    override fun canOpen(action: String): Boolean =
        Intent(action).resolveActivity(context.packageManager) != null

    override fun open(action: String) {
        context.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal fun openNotificationAccessSafely(
    launcher: NotificationSettingsLauncher,
): Boolean {
    for (action in listOf(NOTIFICATION_ACCESS_ACTION, GENERAL_SETTINGS_ACTION)) {
        val resolvable = try {
            launcher.canOpen(action)
        } catch (_: RuntimeException) {
            false
        }
        if (!resolvable) continue
        try {
            launcher.open(action)
            return true
        } catch (_: RuntimeException) {
            // Try the general settings fallback.
        }
    }
    return false
}
