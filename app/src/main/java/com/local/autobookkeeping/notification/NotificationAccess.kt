package com.local.autobookkeeping.notification

import android.content.Context
import android.provider.Settings

object NotificationAccess {
    fun isEnabled(context: Context): Boolean = isEnabled(
        packageName = context.packageName,
        serviceClassName = PaymentNotificationService::class.java.name,
        enabledListeners = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS,
            )
        }.getOrNull(),
    )

    internal fun isEnabled(
        packageName: String,
        serviceClassName: String,
        enabledListeners: String?,
    ): Boolean {
        if (packageName.isBlank() || serviceClassName.isBlank() || enabledListeners.isNullOrBlank()) {
            return false
        }
        return enabledListeners
            .split(':')
            .asSequence()
            .mapNotNull(::componentIdentity)
            .any { component ->
                component.packageName == packageName && component.className == serviceClassName
            }
    }

    private fun componentIdentity(flattened: String): ComponentIdentity? {
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
        if (!CLASS_NAME.matches(qualifiedClass)) return null
        return ComponentIdentity(componentPackage, qualifiedClass)
    }

    private data class ComponentIdentity(val packageName: String, val className: String)

    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+")
}
