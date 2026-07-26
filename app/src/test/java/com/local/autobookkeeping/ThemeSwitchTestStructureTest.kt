package com.local.autobookkeeping

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeSwitchTestStructureTest {
    @Test
    fun `theme recreation test launches production activity with real isolated storage`() {
        val themeSource = appSource(
            "src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTest.kt",
        )
        val runnerSource = appSource(
            "src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTestRunner.kt",
        )
        val applicationSource = appSource(
            "src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTestApplication.kt",
        )

        assertTrue(themeSource.contains("ActivityScenario.launch(MainActivity::class.java)"))
        assertFalse(themeSource.contains("ThemeSwitchTestActivity"))
        assertFalse(themeSource.contains("ThemeLedgerRepository"))
        assertFalse(themeSource.contains("MutableStateFlow"))
        assertFalse(themeSource.contains("application.cleanUpTestStorage()"))
        assertTrue(runnerSource.contains("ThemeSwitchTestApplication"))
        assertTrue(runnerSource.contains("cleanUpTestStorage()"))
        assertTrue(applicationSource.contains("Room.databaseBuilder"))
        assertTrue(applicationSource.contains("PreferenceDataStoreFactory.create"))
    }

    private fun appSource(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
        )
        return candidates.firstOrNull(File::isFile)?.readText().orEmpty()
    }
}
