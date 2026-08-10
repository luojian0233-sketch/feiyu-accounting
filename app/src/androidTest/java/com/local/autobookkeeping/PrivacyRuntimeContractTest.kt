package com.local.autobookkeeping

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.local.autobookkeeping.data.NotificationSourceSettings
import com.local.autobookkeeping.data.RoomRecognitionStore
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.notification.NotificationRouter
import com.local.autobookkeeping.notification.NotificationText
import com.local.autobookkeeping.notification.toRecognitionCandidate
import com.local.autobookkeeping.recognition.AutoSavePolicy
import com.local.autobookkeeping.recognition.GenericBillParser
import com.local.autobookkeeping.recognition.MerchantClassifier
import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import com.local.autobookkeeping.recognition.TextBounds
import com.local.autobookkeeping.recognition.accessibility.PaymentPageAccessibilityService
import com.local.autobookkeeping.recognition.capture.LocalTextRecognizer
import com.local.autobookkeeping.recognition.capture.ScreenshotRecognitionController
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PrivacyRuntimeContractTest {
    @Test
    fun finalDebugPackageRequestsOnlyExpectedPermissionsAndNoGestureCapability() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val requested = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()

        assertEquals(
            EXPECTED_PERMISSIONS +
                "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            requested,
        )

        val expectedComponent = ComponentName(context, PaymentPageAccessibilityService::class.java)
        val accessibilityManager = requireNotNull(
            context.getSystemService(AccessibilityManager::class.java),
        )
        val accessibilityInfo = accessibilityManager.installedAccessibilityServiceList.single { info ->
            info.resolveInfo.serviceInfo.let { service ->
                ComponentName(service.packageName, service.name) == expectedComponent
            }
        }

        assertEquals(EXPECTED_ACCESSIBILITY_FLAGS, accessibilityInfo.flags)
        assertEquals(EXPECTED_ACCESSIBILITY_CAPABILITIES, accessibilityInfo.capabilities)
        assertEquals(
            0,
            accessibilityInfo.capabilities and
                AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES,
        )
    }

    @Test
    fun rawNotificationOcrAndImageDataRemainMemoryOnlyAcrossRealRecognitionWrites() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "privacy-contract-${System.nanoTime()}.db"
            val databasePath = context.getDatabasePath(databaseName)
            val artifactRoots = artifactRoots(context)
            val before = snapshotFiles(artifactRoots)
            context.deleteDatabase(databaseName)
            val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addCallback(AppDatabase.categorySeedCallback())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            try {
                val store = RoomRecognitionStore(database)
                val coordinator = RecognitionCoordinator(
                    store = store,
                    autoSavePolicy = AutoSavePolicy { false },
                    classifier = MerchantClassifier(store),
                )
                val notification = NotificationText(
                    title = RAW_NOTIFICATION_TITLE,
                    body = "支付成功，支付金额 ¥12.34，商户：PrivacyCafe；$RAW_NOTIFICATION_BODY",
                    packageName = NotificationRouter.WECHAT_PACKAGE,
                    postedAt = OBSERVED_AT,
                )
                val routed = requireNotNull(NotificationRouter(EnabledNotificationSettings()).route(notification))
                assertTrue(coordinator.process(routed.toRecognitionCandidate()) is RecognitionOutcome.Saved)

                val pixelBytes = RAW_IMAGE_PIXELS.toByteArray(Charsets.UTF_8).copyOf(PIXEL_BUFFER_SIZE)
                val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(ByteBuffer.wrap(pixelBytes))
                }
                val screenshotController = ScreenshotRecognitionController(
                    textRecognizer = LocalTextRecognizer { ocrElements() },
                    genericParser = GenericBillParser,
                    coordinator = coordinator,
                )
                assertTrue(
                    screenshotController.process(
                        bitmap = bitmap,
                        foregroundPackage = null,
                        observedAt = OBSERVED_AT.plusSeconds(3_600),
                    ) is RecognitionOutcome.Saved,
                )
                assertTrue(bitmap.isRecycled)

                val rows = database.transactionDao().allTransactions()
                assertEquals(setOf("PrivacyCafe", "PrivacyShop"), rows.map { it.merchant }.toSet())
                assertTrue(rows.all { it.note.isEmpty() })
                assertTrue(rows.all { it.redactedNotificationDigest == null })
                assertTrue(rows.all { it.dedupeKey == null })
                val receipts = database.transactionDao().allRecognitionReceipts()
                assertEquals(2, receipts.size)
                assertTrue(receipts.all { it.digest.matches(LOWERCASE_SHA256) })

                val businessText = readAllBusinessText(database)
                RAW_TEXT_SENTINELS.forEach { sentinel ->
                    assertTrue(
                        "Raw sentinel leaked into a Room text column: $sentinel",
                        businessText.none { value -> value.contains(sentinel) },
                    )
                }

                database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").close()
                val databaseArtifacts = listOf(
                    databasePath,
                    File(databasePath.path + "-wal"),
                    File(databasePath.path + "-shm"),
                ).filter(File::isFile)
                val rawPatterns = RAW_TEXT_SENTINELS.map { it.toByteArray(Charsets.UTF_8) } +
                    RAW_IMAGE_PIXELS.toByteArray(Charsets.UTF_8)
                assertFilesContainNoPatterns(databaseArtifacts, rawPatterns)

                val afterFiles = filesUnder(artifactRoots)
                assertFilesContainNoPatterns(afterFiles, rawPatterns)
                val changedFiles = afterFiles.filter { file ->
                    before[file.canonicalPath] != FileFingerprint(file.length(), file.lastModified())
                }
                assertEquals(
                    emptyList<String>(),
                    changedFiles.filter(::hasImageMagic).map(File::getAbsolutePath),
                )
            } finally {
                database.close()
                context.deleteDatabase(databaseName)
            }
        }

    private fun ocrElements(): List<ObservedTextElement> = listOf(
        element("交易详情", 0),
        element("支付成功", 40),
        element("商户：PrivacyShop", 80),
        element("支付金额：¥43.21", 120),
        element("调试标记：$RAW_OCR_TEXT", 160),
    )

    private fun element(text: String, top: Int) = ObservedTextElement(
        text = text,
        bounds = TextBounds(0, top, 300, top + 30),
    )

    private fun readAllBusinessText(database: AppDatabase): List<String> {
        val sqlite = database.openHelper.writableDatabase
        val tables = buildList {
            sqlite.query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('room_master_table', 'android_metadata')",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        return tables.flatMap { table ->
            val textColumns = buildList {
                sqlite.query("PRAGMA table_info(`${table.escapeIdentifier()}`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(2).equals("TEXT", ignoreCase = true)) {
                            add(cursor.getString(1))
                        }
                    }
                }
            }
            textColumns.flatMap { column ->
                buildList {
                    sqlite.query(
                        "SELECT `${column.escapeIdentifier()}` FROM `${table.escapeIdentifier()}` " +
                            "WHERE `${column.escapeIdentifier()}` IS NOT NULL",
                    ).use { cursor ->
                        while (cursor.moveToNext()) add("$table.$column=${cursor.getString(0)}")
                    }
                }
            }
        }
    }

    private fun String.escapeIdentifier(): String = replace("`", "``")

    private fun artifactRoots(context: Context): List<File> = listOfNotNull(
        context.filesDir,
        context.cacheDir,
        context.noBackupFilesDir,
        File(context.applicationInfo.dataDir, "shared_prefs"),
        context.getExternalFilesDir(null),
        context.externalCacheDir,
    ).distinctBy(File::getAbsolutePath)

    private fun snapshotFiles(roots: List<File>): Map<String, FileFingerprint> =
        filesUnder(roots).associate { file ->
            file.canonicalPath to FileFingerprint(file.length(), file.lastModified())
        }

    private fun filesUnder(roots: List<File>): List<File> = roots
        .asSequence()
        .filter(File::exists)
        .flatMap { root -> root.walkTopDown().filter(File::isFile) }
        .distinctBy(File::getCanonicalPath)
        .toList()

    private fun assertFilesContainNoPatterns(files: List<File>, patterns: List<ByteArray>) {
        val offenders = files.filter { file -> containsAnyPattern(file, patterns) }
            .map(File::getAbsolutePath)
        assertEquals(emptyList<String>(), offenders)
    }

    private fun containsAnyPattern(file: File, patterns: List<ByteArray>): Boolean {
        val maximumPattern = patterns.maxOf(ByteArray::size)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1024)
            var carry = ByteArray(0)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return false
                val combined = ByteArray(carry.size + read)
                carry.copyInto(combined)
                buffer.copyInto(combined, destinationOffset = carry.size, endIndex = read)
                if (patterns.any { pattern -> combined.indexOf(pattern) >= 0 }) return true
                val keep = minOf(maximumPattern - 1, combined.size)
                carry = combined.copyOfRange(combined.size - keep, combined.size)
            }
        }
    }

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        if (pattern.isEmpty()) return 0
        for (start in 0..size - pattern.size) {
            if (pattern.indices.all { offset -> this[start + offset] == pattern[offset] }) return start
        }
        return -1
    }

    private fun hasImageMagic(file: File): Boolean {
        val header = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        return header.startsWith(PNG_MAGIC) ||
            header.startsWith(JPEG_MAGIC) ||
            header.startsWith(GIF87_MAGIC) ||
            header.startsWith(GIF89_MAGIC) ||
            header.startsWith(BMP_MAGIC) ||
            (header.copyOfRange(0, 4).contentEquals(RIFF_MAGIC) &&
                header.copyOfRange(8, 12).contentEquals(WEBP_MAGIC))
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private class EnabledNotificationSettings : NotificationSourceSettings {
        override val autoBookkeepingEnabled = MutableStateFlow(true)
        override val wechatEnabled = MutableStateFlow(true)
        override val alipayEnabled = MutableStateFlow(true)
        override val enabledBankPackages = MutableStateFlow<Set<String>>(emptySet())
    }

    private data class FileFingerprint(val size: Long, val modifiedAt: Long)

    private companion object {
        val EXPECTED_PERMISSIONS = setOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        )
        const val EXPECTED_ACCESSIBILITY_FLAGS =
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        const val EXPECTED_ACCESSIBILITY_CAPABILITIES =
            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT or
                AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT
        val OBSERVED_AT: Instant = Instant.parse("2026-08-10T04:00:00Z")
        const val RAW_NOTIFICATION_TITLE = "RAW_NOTIFICATION_TITLE_6f39cb12"
        const val RAW_NOTIFICATION_BODY = "RAW_NOTIFICATION_BODY_e82a5147"
        const val RAW_OCR_TEXT = "RAW_OCR_TEXT_c7a063bd"
        const val RAW_IMAGE_PIXELS = "RAW_IMAGE_PIXELS_3d8f64aa"
        val RAW_TEXT_SENTINELS = listOf(
            RAW_NOTIFICATION_TITLE,
            RAW_NOTIFICATION_BODY,
            RAW_OCR_TEXT,
        )
        const val PIXEL_BUFFER_SIZE = 4 * 4 * 4
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val JPEG_MAGIC = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val GIF87_MAGIC = "GIF87a".toByteArray(Charsets.US_ASCII)
        val GIF89_MAGIC = "GIF89a".toByteArray(Charsets.US_ASCII)
        val BMP_MAGIC = "BM".toByteArray(Charsets.US_ASCII)
        val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
        val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
    }
}
