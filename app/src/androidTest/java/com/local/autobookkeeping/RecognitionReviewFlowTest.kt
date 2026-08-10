package com.local.autobookkeeping

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.ui.recognition.RecognitionReviewScreen
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RecognitionReviewFlowTest(
    private val theme: ThemeStyle,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewShowsCompactNormalizedRowsAndLocksSelectionAfterFirstClick() {
        var selections = 0
        compose.setContent {
            var processing by remember { mutableStateOf(false) }
            AutoBookkeepingTheme(theme) {
                RecognitionReviewScreen(
                    request = ReviewRequest(
                        listOf(
                            RecognitionChoice(candidate(), matchTransactionId = 73),
                            RecognitionChoice(
                                candidate().copy(
                                    merchant = null,
                                    eventKind = TransactionEventKind.TRANSFER,
                                    evidenceDigest = "cd".repeat(32),
                                ),
                            ),
                        ),
                    ),
                    processing = processing,
                    onChoiceSelected = {
                        selections += 1
                        processing = true
                    },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("review-option-0").apply {
            assertTextContains("支出 ¥123.45")
            assertTextContains("中国电信")
            assertTextContains("2026-07-29 16:30")
        }
        compose.onNodeWithTag("review-option-1").assertTextContains("转账")
        compose.onNodeWithTag("review-option-0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("review-option-0").assertIsNotEnabled()
        compose.onNodeWithTag("review-option-1").assertIsNotEnabled()
        compose.onNodeWithTag("review-processing").assertIsDisplayed()
        compose.onNodeWithTag("review-option-0").performClick()

        assertEquals(1, selections)
    }

    @Test
    fun systemBackExitsReviewWithoutTouchingBottomNavigationState() {
        var backCalls = 0
        compose.setContent {
            AutoBookkeepingTheme(theme) {
                var reviewVisible by remember { mutableStateOf(true) }
                if (reviewVisible) {
                    RecognitionReviewScreen(
                        request = ReviewRequest(listOf(RecognitionChoice(candidate()))),
                        processing = false,
                        onChoiceSelected = {},
                        onBack = {
                            backCalls += 1
                            reviewVisible = false
                        },
                    )
                }
            }
        }

        pressBack()
        compose.waitForIdle()

        compose.onNodeWithTag("review-option-0").assertDoesNotExist()
        assertEquals(1, backCalls)
    }

    private fun candidate() = RecognitionCandidate(
        platform = PaymentPlatform.WECHAT,
        channel = CaptureChannel.SCREENSHOT,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = 12_345,
        direction = Direction.EXPENSE,
        merchant = "中国电信",
        account = "招商银行",
        occurredAt = Instant.parse("2026-07-29T08:30:00Z"),
        confidence = 87,
        evidenceDigest = "ab".repeat(32),
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "theme={0}")
        fun themes(): List<Array<Any>> = ThemeStyle.entries.map { arrayOf<Any>(it) }
    }
}
