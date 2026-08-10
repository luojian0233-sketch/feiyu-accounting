package com.local.autobookkeeping

import androidx.room.Room
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.recognition.capture.LocalTextRecognizer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApplicationGraphTest {
    @Test
    fun `application graph owns one recognition runtime`() {
        val application = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(
            application,
            AppDatabase::class.java,
        ).build()
        val recognizer = LocalTextRecognizer { emptyList() }

        try {
            val graph = ApplicationGraph(
                application = application,
                databaseProvider = { database },
                localTextRecognizerProvider = { recognizer },
            )

            assertSame(graph.recognitionStore, graph.recognitionStore)
            assertSame(graph.merchantClassifier, graph.merchantClassifier)
            assertSame(graph.recognitionCoordinator, graph.recognitionCoordinator)
            assertSame(recognizer, graph.localTextRecognizer)
            assertSame(
                graph.automaticPageRecognitionController,
                graph.automaticPageRecognitionController,
            )
            assertSame(
                graph.screenshotRecognitionController,
                graph.screenshotRecognitionController,
            )
            assertSame(graph.automaticBookkeepingAccess, graph.automaticBookkeepingAccess)
            assertSame(graph.reviewRequestCodec, graph.reviewRequestCodec)
            assertSame(graph.pendingConfirmationNotifier, graph.pendingConfirmationNotifier)
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
            "app/src/main/java/com/local/autobookkeeping/notification/PaymentNotificationService.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/accessibility/PaymentPageAccessibilityService.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotBookkeepingTileService.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotCaptureActivity.kt",
            "app/src/main/java/com/local/autobookkeeping/recognition/tile/ProjectionCaptureService.kt",
        )
    }
}
