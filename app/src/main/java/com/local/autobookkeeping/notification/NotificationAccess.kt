package com.local.autobookkeeping.notification

import android.content.Context
import android.provider.Settings

object NotificationAccess {
    fun isEnabled(context: Context): Boolean = isEnabled(
        packageName = context.packageName,
        enabledListeners = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS,
            )
        }.getOrNull(),
    )

    internal fun isEnabled(packageName: String, enabledListeners: String?): Boolean {
        if (packageName.isBlank() || enabledListeners.isNullOrBlank()) return false
        return enabledListeners
            .split(':')
            .asSequence()
            .mapNotNull(::componentPackage)
            .any { enabledPackage -> enabledPackage == packageName }
    }

    private fun componentPackage(flattened: String): String? {
        val value = flattened.trim()
        val separator = value.indexOf('/')
        if (separator <= 0 || separator == value.lastIndex || value.indexOf('/', separator + 1) >= 0) {
            return null
        }
        val componentPackage = value.substring(0, separator)
        val componentClass = value.substring(separator + 1)
        if (!PACKAGE_NAME.matches(componentPackage)) return null
        val qualifiedClass = if (componentClass.startsWith('.')) {
            componentPackage + componentClass
        } else {
            componentClass
        }
        return componentPackage.takeIf { PACKAGE_NAME.matches(qualifiedClass) }
    }

    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
}
