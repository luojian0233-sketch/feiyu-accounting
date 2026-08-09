package com.local.autobookkeeping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationSettingsLauncherTest {
    @Test
    fun `launcher uses safe fallback when notification settings cannot open`() {
        val launcher = RecordingNotificationSettingsLauncher(
            resolvableActions = setOf(GENERAL_SETTINGS_ACTION),
            openFailures = mapOf(
                NOTIFICATION_ACCESS_ACTION to SecurityException("internal policy detail"),
            ),
        )

        assertTrue(openNotificationAccessSafely(launcher))
        assertEquals(
            listOf(NOTIFICATION_ACCESS_ACTION, GENERAL_SETTINGS_ACTION),
            launcher.openAttempts,
        )
    }

    @Test
    fun `launcher reports stable failure when neither intent is safe`() {
        val launcher = RecordingNotificationSettingsLauncher(
            resolvableActions = emptySet(),
        )

        assertFalse(openNotificationAccessSafely(launcher))
        assertEquals(emptyList<String>(), launcher.openAttempts)
    }

    @Test
    fun `launcher tries fallback when notification resolver throws runtime exception`() {
        val launcher = RecordingNotificationSettingsLauncher(
            resolvableActions = setOf(GENERAL_SETTINGS_ACTION),
            canOpenFailures = mapOf(
                NOTIFICATION_ACCESS_ACTION to IllegalStateException("broken package manager"),
            ),
        )

        assertTrue(openNotificationAccessSafely(launcher))
        assertEquals(listOf(GENERAL_SETTINGS_ACTION), launcher.openAttempts)
    }

    @Test
    fun `launcher tries fallback when notification open throws runtime exception`() {
        val launcher = RecordingNotificationSettingsLauncher(
            resolvableActions = setOf(GENERAL_SETTINGS_ACTION),
            openFailures = mapOf(
                NOTIFICATION_ACCESS_ACTION to IllegalStateException("broken settings activity"),
            ),
        )

        assertTrue(openNotificationAccessSafely(launcher))
        assertEquals(
            listOf(NOTIFICATION_ACCESS_ACTION, GENERAL_SETTINGS_ACTION),
            launcher.openAttempts,
        )
    }

    @Test
    fun `launcher returns false without leaking runtime failures`() {
        val launcher = RecordingNotificationSettingsLauncher(
            canOpenFailures = mapOf(
                NOTIFICATION_ACCESS_ACTION to IllegalStateException("broken package manager"),
            ),
            openFailures = mapOf(
                GENERAL_SETTINGS_ACTION to IllegalStateException("broken settings activity"),
            ),
        )

        assertFalse(openNotificationAccessSafely(launcher))
        assertEquals(listOf(GENERAL_SETTINGS_ACTION), launcher.openAttempts)
    }

    private class RecordingNotificationSettingsLauncher(
        private val resolvableActions: Set<String> = emptySet(),
        private val canOpenFailures: Map<String, RuntimeException> = emptyMap(),
        private val openFailures: Map<String, RuntimeException> = emptyMap(),
    ) : NotificationSettingsLauncher {
        val openAttempts = mutableListOf<String>()

        override fun canOpen(action: String): Boolean {
            canOpenFailures[action]?.let { throw it }
            return action in resolvableActions || action in openFailures
        }

        override fun open(action: String) {
            openAttempts += action
            openFailures[action]?.let { throw it }
        }
    }
}
