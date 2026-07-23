package com.local.autobookkeeping.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface NotificationSourceSettings {
    val wechatEnabled: Flow<Boolean>
    val alipayEnabled: Flow<Boolean>
    val enabledBankPackages: Flow<Set<String>>
}

private val Context.notificationSettingsDataStore by preferencesDataStore(
    name = "notification_source_settings",
)

class SettingsRepository(
    context: Context,
) : NotificationSourceSettings {
    private val dataStore = context.applicationContext.notificationSettingsDataStore

    override val wechatEnabled: Flow<Boolean> = booleanSetting(WECHAT_ENABLED)
    override val alipayEnabled: Flow<Boolean> = booleanSetting(ALIPAY_ENABLED)
    override val enabledBankPackages: Flow<Set<String>> = dataStore.data
        .map { preferences ->
            preferences[ENABLED_BANK_PACKAGES]
                .orEmpty()
                .let(::sanitizeBankPackages)
        }
        .distinctUntilChanged()

    suspend fun setWechatEnabled(enabled: Boolean) = setBoolean(WECHAT_ENABLED, enabled)

    suspend fun setAlipayEnabled(enabled: Boolean) = setBoolean(ALIPAY_ENABLED, enabled)

    suspend fun setEnabledBankPackages(packages: Set<String>) {
        val sanitized = sanitizeBankPackages(packages)
        dataStore.edit { preferences -> preferences[ENABLED_BANK_PACKAGES] = sanitized }
    }

    private fun booleanSetting(key: Preferences.Key<Boolean>): Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[key] ?: DEFAULT_SOURCE_ENABLED }
        .distinctUntilChanged()

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, enabled: Boolean) {
        dataStore.edit { preferences -> preferences[key] = enabled }
    }

    companion object {
        internal const val DEFAULT_SOURCE_ENABLED = false
        val WECHAT_ENABLED = booleanPreferencesKey("wechat_enabled")
        val ALIPAY_ENABLED = booleanPreferencesKey("alipay_enabled")
        val ENABLED_BANK_PACKAGES = stringSetPreferencesKey("enabled_bank_packages")
    }
}

internal fun sanitizeBankPackages(packages: Set<String>): Set<String> = packages
    .asSequence()
    .map(String::trim)
    .filter(BANK_PACKAGE_NAME::matches)
    .toCollection(linkedSetOf())
    .toSet()

private val BANK_PACKAGE_NAME =
    Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
