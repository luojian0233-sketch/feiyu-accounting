package com.local.autobookkeeping

import android.graphics.Bitmap
import androidx.room.Room
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionController
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.LocalTextRecognizer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApplicationGraphTest {
    @Test
    fun `graph controllers share provider recognizer coordinator store and repository behavior`() = runTest {
        val application = RuntimeEnvironment.getApplication()
        var databaseProviderCalls = 0
        val database = Room.inMemoryDatabaseBuilder(
            application,
            AppDatabase::class.java,
        ).build()
        var recognizerProviderCalls = 0
        var recognizeCalls = 0
        val recognizer = LocalTextRecognizer {
            recognizeCalls += 1
            if (recognizeCalls == 1) {
                recognizedPage("微信支付", "GraphAuto", "¥1.00")
            } else {
                recognizedPage("交易详情", "GraphScreenshot", "¥2.00")
            }
        }

        try {
            val graph = ApplicationGraph(
                application = application,
                databaseProvider = {
                    databaseProviderCalls += 1
                    database
                },
                localTextRecognizerProvider = {
                    recognizerProviderCalls += 1
                    recognizer
                },
            )

            graph.settingsRepository.setAutoBookkeepingEnabled(true)
            graph.settingsRepository.setWechatEnabled(true)
            graph.settingsRepository.setAutoSaveHighConfidence(false)
            val automaticBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            graph.accessibilityCaptureGateway.attach {
                CaptureResult.Success(
                    bitmap = automaticBitmap,
                    foregroundPackage = AutoPageRecognitionController.WECHAT_PACKAGE,
                )
            }
            graph.automaticPageRecognitionController.onWindowChanged(
                PageSnapshot(
                    packageName = AutoPageRecognitionController.WECHAT_PACKAGE,
                    elements = listOf(
                        element("微信支付", 0),
                        element("账单详情", 40),
                    ),
                    observedAt = NOW,
                ),
            )
            val screenshotBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val screenshotOutcome = graph.screenshotRecognitionController.process(
                bitmap = screenshotBitmap,
                foregroundPackage = null,
                observedAt = NOW.plusSeconds(3_600),
            )

            assertTrue(screenshotOutcome is RecognitionOutcome.Saved)
            assertEquals(2, graph.ledgerRepository.observeTransactions().first { it.size == 2 }.size)
            assertEquals(2, database.transactionDao().allRecognitionReceipts().size)
            assertEquals(1, databaseProviderCalls)
            assertEquals(1, recognizerProviderCalls)
            assertEquals(2, recognizeCalls)
            assertTrue(automaticBitmap.isRecycled)
            assertTrue(screenshotBitmap.isRecycled)
        } finally {
            database.close()
        }
    }

    @Test
    fun `android entry points do not construct graph owned dependencies`() {
        val forbidden = listOf(
            "RoomRecognitionStore(",
            "MerchantClassifier(",
            "RecognitionCoordinator(",
            "MlKitTextRecognizer(",
            "AccessibilityCaptureGateway(",
            "AutoPageRecognitionController(",
            "ScreenshotRecognitionController(",
            "AutomaticBookkeepingAccess(",
            "ReviewRequestCodec(",
            "PendingConfirmationNotifier(",
        )
        val offenders = ENTRY_POINTS.flatMap { relative ->
            val path = projectRoot().resolve(relative)
            val source = Files.readString(path)
            forbidden.filter(source::contains).map { constructor ->
                "$relative -> $constructor"
            }
        }

        org.junit.Assert.assertEquals(emptyList<String>(), offenders)
    }

    private fun projectRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("app/src/main/AndroidManifest.xml")) }
    }

    private companion object {
        val ENTRY_POINTS = listOf(
            "app/src/main/java/com/local/autobookkeeping/MainActivity.kt",
            "app/src/main/java/com/local/autobookkeeping/RecognitionReviewLaunchActivity.kt",
            "app/src/main/java/com/local/autobookkeeping/notification/PaymentNotificationService.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/accessibility/PaymentPageAccessibilityService.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotBookkeepingTileService.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotCaptureActivity.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/tile/ProjectionCaptureService.kt",
        )

        val NOW: Instant = Instant.parse("2026-08-10T04:00:00Z")

        fun recognizedPage(signature: String, merchant: String, amount: String) = listOf(
            element(signature, 0),
            element("支付成功", 40),
            element("商户：$merchant", 80),
            element("支付金额：$amount", 120),
        )

        fun element(text: String, top: Int) = ObservedTextElement(
            text = text,
            bounds = TextBounds(0, top, 300, top + 30),
        )
    }
}
