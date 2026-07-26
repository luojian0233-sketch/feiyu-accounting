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
            failingActions = setOf(NOTIFICATION_ACCESS_ACTION),
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

    private class RecordingNotificationSettingsLauncher(
        private val resolvableActions: Set<String>,
        private val failingActions: Set<String> = emptySet(),
    ) : NotificationSettingsLauncher {
        val openAttempts = mutableListOf<String>()

        override fun canOpen(action: String): Boolean = action in resolvableActions ||
            action in failingActions

        override fun open(action: String) {
            openAttempts += action
            if (action in failingActions) throw SecurityException("internal policy detail")
        }
    }
}
