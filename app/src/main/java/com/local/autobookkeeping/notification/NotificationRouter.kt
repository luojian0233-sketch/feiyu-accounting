package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.NotificationSourceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

enum class NotificationDiagnostic {
    NONE,
    SETTINGS_FAILURE,
    PARSER_FAILURE,
    PROCESSOR_FAILURE,
}

interface NotificationDiagnostics {
    val state: StateFlow<NotificationDiagnostic>
    fun record(diagnostic: NotificationDiagnostic)
}

class InMemoryNotificationDiagnostics : NotificationDiagnostics {
    private val mutableState = MutableStateFlow(NotificationDiagnostic.NONE)
    override val state: StateFlow<NotificationDiagnostic> = mutableState

    override fun record(diagnostic: NotificationDiagnostic) {
        mutableState.value = diagnostic
    }
}

class NotificationRouter(
    private val settings: NotificationSourceSettings,
    private val wechatParser: PaymentParser = WechatParser,
    private val alipayParser: PaymentParser = AlipayParser,
    private val diagnostics: NotificationDiagnostics = InMemoryNotificationDiagnostics(),
    private val bankParserFactory: (Set<String>) -> PaymentParser = ::BankNotificationParser,
) {
    suspend fun route(notification: NotificationText): PaymentCandidate? {
        if (!isSourceEnabled(notification.packageName)) return null
        val parser = parserForEnabledSource(notification.packageName)

        return try {
            parser.parse(notification)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            diagnostics.record(NotificationDiagnostic.PARSER_FAILURE)
            null
        }
    }

    suspend fun isSourceEnabled(packageName: String): Boolean = try {
        if (!settings.autoBookkeepingEnabled.first()) {
            false
        } else {
            when (packageName) {
                WECHAT_PACKAGE -> settings.wechatEnabled.first()
                ALIPAY_PACKAGE -> settings.alipayEnabled.first()
                else -> packageName in settings.enabledBankPackages.first()
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        diagnostics.record(NotificationDiagnostic.SETTINGS_FAILURE)
        false
    }

    private fun parserForEnabledSource(packageName: String): PaymentParser = when (packageName) {
        WECHAT_PACKAGE -> wechatParser
        ALIPAY_PACKAGE -> alipayParser
        else -> bankParserFactory(setOf(packageName))
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    }
}
