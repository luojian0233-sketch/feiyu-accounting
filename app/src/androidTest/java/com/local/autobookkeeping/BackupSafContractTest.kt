package com.local.autobookkeeping

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSafContractTest {
    @Test
    fun createDocumentUsesJsonMimeTypeAndOpenableSafAction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = backupCreateDocumentContract()
            .createIntent(context, "自动记账备份.json")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("自动记账备份.json", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun openDocumentUsesJsonMimeTypeAndOpenableSafAction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = backupOpenDocumentContract()
            .createIntent(context, arrayOf("application/json"))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
    }
}
