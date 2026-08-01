package com.local.autobookkeeping.domain

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RecognitionModelTest {
    @Test
    fun `candidate rejects missing amount and non sha256 evidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            candidate(amountMinor = 0, evidenceDigest = "raw-page-text")
        }
    }

    @Test
    fun `candidate rejects non sha256 evidence when amount is present`() {
        assertThrows(IllegalArgumentException::class.java) {
            candidate(evidenceDigest = "raw-page-text")
        }
    }

    @Test
    fun `candidate rejects confidence outside percentage range`() {
        assertThrows(IllegalArgumentException::class.java) {
            candidate(confidence = 101)
        }
    }

    @Test
    fun `receipt rejects non sha256 digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionReceipt(
                channel = CaptureChannel.NOTIFICATION,
                platform = PaymentPlatform.WECHAT,
                eventKind = TransactionEventKind.PAYMENT,
                observedAt = Instant.parse("2026-07-29T10:00:00Z"),
                digest = "notification-body",
            )
        }
    }

    @Test
    fun `manual transaction keeps backward compatible recognition defaults`() {
        val transaction = Transaction(
            amountMinor = 100,
            direction = Direction.EXPENSE,
            category = "其他",
            occurredAt = Instant.parse("2026-07-29T10:00:00Z"),
        )

        assertEquals(PaymentPlatform.UNKNOWN, transaction.platform)
        assertEquals(TransactionEventKind.UNKNOWN, transaction.eventKind)
        assertEquals(100, transaction.recognitionConfidence)
    }

    @Test
    fun `transaction rejects recognition confidence outside percentage range`() {
        assertThrows(IllegalArgumentException::class.java) {
            Transaction(
                amountMinor = 100,
                direction = Direction.EXPENSE,
                category = "其他",
                occurredAt = Instant.parse("2026-07-29T10:00:00Z"),
                recognitionConfidence = -1,
            )
        }
    }

    private fun candidate(
        amountMinor: Long = 100,
        confidence: Int = 95,
        evidenceDigest: String = "a".repeat(64),
    ) = RecognitionCandidate(
        platform = PaymentPlatform.WECHAT,
        channel = CaptureChannel.ACCESSIBILITY,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = amountMinor,
        direction = Direction.EXPENSE,
        merchant = "中国电信",
        account = "零钱",
        occurredAt = Instant.parse("2026-07-29T10:00:00Z"),
        confidence = confidence,
        evidenceDigest = evidenceDigest,
    )
}
