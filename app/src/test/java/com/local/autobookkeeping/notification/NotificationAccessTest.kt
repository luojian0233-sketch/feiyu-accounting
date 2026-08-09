package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.NotificationSourceSettings
import com.local.autobookkeeping.data.SettingsRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class NotificationAccessTest {
    @Test
    fun `matching listener component grants access`() {
        val setting = "com.other/.Listener:com.local.autobookkeeping/.notification.PaymentNotificationService"

        assertTrue(
            NotificationAccess.isEnabled(
                "com.local.autobookkeeping",
                "com.local.autobookkeeping.notification.PaymentNotificationService",
                setting,
            ),
        )
    }

    @Test
    fun `same package with wrong listener class does not grant access`() {
        val setting = "com.local.autobookkeeping/.notification.UnrelatedListener"

        assertFalse(
            NotificationAccess.isEnabled(
                "com.local.autobookkeeping",
                "com.local.autobookkeeping.notification.PaymentNotificationService",
                setting,
            ),
        )
    }

    @Test
    fun `fully qualified listener class grants access`() {
        val setting =
            "com.local.autobookkeeping/com.local.autobookkeeping.notification.PaymentNotificationService"

        assertTrue(
            NotificationAccess.isEnabled(
                "com.local.autobookkeeping",
                "com.local.autobookkeeping.notification.PaymentNotificationService",
                setting,
            ),
        )
    }

    @Test
    fun `malformed listener setting does not grant access`() {
        val setting = "not-a-component:com.local.autobookkeeping:/.broken"

        assertFalse(
            NotificationAccess.isEnabled(
                "com.local.autobookkeeping",
                "com.local.autobookkeeping.notification.PaymentNotificationService",
                setting,
            ),
        )
    }

    @Test
    fun `similarly prefixed package does not grant access`() {
        val setting = "com.local.autobookkeeping.evil/.Listener"

        assertFalse(
            NotificationAccess.isEnabled(
                "com.local.autobookkeeping",
                "com.local.autobookkeeping.notification.PaymentNotificationService",
                setting,
            ),
        )
    }

    @Test
    fun `disabled source is ignored before parsing`() = runTest {
        var parseCalls = 0
        val parser = PaymentParser {
            parseCalls++
            candidate(it)
        }
        val router = NotificationRouter(
            settings = FakeSettingsRepository(),
            wechatParser = parser,
            alipayParser = parser,
        )

        assertNull(router.route(notification(WECHAT_PACKAGE)))
        assertEquals(0, parseCalls)
    }

    @Test
    fun `disabled master switch ignores enabled source before parsing`() = runTest {
        var parseCalls = 0
        val parser = PaymentParser {
            parseCalls++
            candidate(it)
        }
        val router = NotificationRouter(
            settings = FakeSettingsRepository(automatic = false, wechat = true),
            wechatParser = parser,
            alipayParser = parser,
        )

        assertNull(router.route(notification(WECHAT_PACKAGE)))
        assertEquals(0, parseCalls)
    }

    @Test
    fun `wechat and alipay defaults are explicit and bank allowlist is empty`() = runTest {
        val settings = FakeSettingsRepository()

        assertFalse(SettingsRepository.DEFAULT_SOURCE_ENABLED)
        assertFalse(settings.wechatEnabled.value)
        assertFalse(settings.alipayEnabled.value)
        assertTrue(settings.enabledBankPackages.value.isEmpty())
    }

    @Test
    fun `router uses exact source package mapping`() = runTest {
        var parseCalls = 0
        val parser = PaymentParser {
            parseCalls++
            candidate(it)
        }
        val settings = FakeSettingsRepository(wechat = true)
        val router = NotificationRouter(settings, parser, parser)

        assertNull(router.route(notification("$WECHAT_PACKAGE.evil")))
        assertEquals(0, parseCalls)
        assertEquals(WECHAT_PACKAGE, router.route(notification(WECHAT_PACKAGE))?.sourcePackage)
        assertEquals(1, parseCalls)
    }

    @Test
    fun `bank parser receives only explicitly enabled package`() = runTest {
        val bankPackage = "com.bank.allowed"
        val settings = FakeSettingsRepository(banks = setOf(bankPackage))
        val router = NotificationRouter(
            settings,
            PaymentParser { null },
            PaymentParser { null },
            bankParserFactory = { allowed ->
                PaymentParser { input -> candidate(input).takeIf { input.packageName in allowed } }
            },
        )

        assertNull(router.route(notification("com.bank.unlisted")))
        assertEquals(bankPackage, router.route(notification(bankPackage))?.sourcePackage)
    }

    @Test
    fun `source gate rejects disabled package before payload extraction`() = runTest {
        val router = NotificationRouter(
            settings = FakeSettingsRepository(wechat = false),
            wechatParser = PaymentParser { error("parser must not run") },
            alipayParser = PaymentParser { null },
        )

        assertFalse(router.isSourceEnabled(WECHAT_PACKAGE))
    }

    @Test
    fun `parser failure is contained and reported without raw content`() = runTest {
        val diagnostics = InMemoryNotificationDiagnostics()
        val router = NotificationRouter(
            settings = FakeSettingsRepository(wechat = true),
            wechatParser = PaymentParser { error("raw merchant secret") },
            alipayParser = PaymentParser { null },
            diagnostics = diagnostics,
        )

        assertNull(router.route(notification(WECHAT_PACKAGE)))
        assertEquals(NotificationDiagnostic.PARSER_FAILURE, diagnostics.state.value)
        assertFalse(diagnostics.state.value.toString().contains("secret"))
    }

    @Test
    fun `router does not swallow coroutine cancellation`() = runTest {
        val router = NotificationRouter(
            settings = FakeSettingsRepository(wechat = true),
            wechatParser = PaymentParser { throw CancellationException("cancel") },
            alipayParser = PaymentParser { null },
        )

        assertThrows<CancellationException> {
            kotlinx.coroutines.runBlocking { router.route(notification(WECHAT_PACKAGE)) }
        }
    }

    @Test
    fun `master settings cancellation propagates and ordinary failure is fail closed`() = runTest {
        fun settings(master: Flow<Boolean>) = object : NotificationSourceSettings {
            override val autoBookkeepingEnabled = master
            override val wechatEnabled = MutableStateFlow(true)
            override val alipayEnabled = MutableStateFlow(true)
            override val enabledBankPackages = MutableStateFlow<Set<String>>(emptySet())
        }

        val cancelled = NotificationRouter(
            settings(flow { throw CancellationException("cancel") }),
        )
        assertThrows<CancellationException> {
            kotlinx.coroutines.runBlocking { cancelled.isSourceEnabled(WECHAT_PACKAGE) }
        }

        val diagnostics = InMemoryNotificationDiagnostics()
        val failed = NotificationRouter(
            settings = settings(flow { throw IllegalStateException("private setting") }),
            diagnostics = diagnostics,
        )
        assertFalse(failed.isSourceEnabled(WECHAT_PACKAGE))
        assertEquals(NotificationDiagnostic.SETTINGS_FAILURE, diagnostics.state.value)
    }

    private fun notification(packageName: String) = NotificationText(
        title = "private title",
        body = "private body",
        packageName = packageName,
        postedAt = Instant.parse("2026-07-23T00:00:00Z"),
    )

    private fun candidate(input: NotificationText) = buildCandidate(
        notification = input,
        amountMinor = 100,
        direction = com.local.autobookkeeping.domain.model.Direction.EXPENSE,
        confidence = 90,
        merchant = "merchant",
        eventKind = PaymentEventKind.PAYMENT,
    )

    private class FakeSettingsRepository(
        automatic: Boolean = true,
        wechat: Boolean = false,
        alipay: Boolean = false,
        banks: Set<String> = emptySet(),
    ) : NotificationSourceSettings {
        override val autoBookkeepingEnabled = MutableStateFlow(automatic)
        override val wechatEnabled = MutableStateFlow(wechat)
        override val alipayEnabled = MutableStateFlow(alipay)
        override val enabledBankPackages = MutableStateFlow(banks)
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
