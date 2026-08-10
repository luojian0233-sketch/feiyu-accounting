package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewRequestCodecTest {
    private val codec = ReviewRequestCodec()

    @Test
    fun `review codec round trips normalized fields only`() {
        val request = ReviewRequest(listOf(choice()))

        val encoded = codec.encode(request)

        assertEquals(request, codec.decode(encoded))
        assertFalse(encoded.contains("pageRawText"))
        assertFalse(encoded.contains("notificationBody"))
        assertFalse(encoded.contains("raw", ignoreCase = true))
    }

    @Test
    fun `decode rejects unknown enum names without throwing`() {
        val encoded = codec.encode(ReviewRequest(listOf(choice())))

        val decoded = codec.decode(encoded.replace("\"WECHAT\"", "\"PAYPAL\""))

        assertNull(decoded)
    }

    @Test
    fun `decode rejects malformed and nonpositive amounts without throwing`() {
        val encoded = codec.encode(ReviewRequest(listOf(choice())))

        assertNull(codec.decode(encoded.replace("12345", "\"12.34\"")))
        assertNull(codec.decode(encoded.replace("12345", "0")))
    }

    @Test
    fun `decode rejects invalid confidence digest and match id`() {
        val encoded = codec.encode(ReviewRequest(listOf(choice())))

        assertNull(codec.decode(encoded.replace("\"confidence\":87", "\"confidence\":101")))
        assertNull(codec.decode(encoded.replace(DIGEST, DIGEST.uppercase())))
        assertNull(codec.decode(encoded.replace("\"matchTransactionId\":73", "\"matchTransactionId\":0")))
    }

    @Test
    fun `decode rejects unknown fields so raw recognition text cannot be smuggled`() {
        val encoded = codec.encode(ReviewRequest(listOf(choice())))
        val withRawText = encoded.dropLast(1) + ",\"pageRawText\":\"secret\"}"

        assertNull(codec.decode(withRawText))
    }

    @Test
    fun `codec enforces option count and bounded intent payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReviewRequest(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReviewRequest(List(6) { choice(matchTransactionId = it.toLong() + 1) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(
                ReviewRequest(
                    listOf(
                        choice(
                            candidate = candidate(merchant = "商".repeat(257)),
                        ),
                    ),
                ),
            )
        }
        assertNull(codec.decode(" ".repeat(32 * 1024 + 1)))
    }

    @Test
    fun `decode bounds merchant and account before constructing choices`() {
        val encoded = codec.encode(ReviewRequest(listOf(choice())))
        val longMerchant = encoded.replace("中国电信", "商".repeat(257))
        val longAccount = encoded.replace("招商银行", "账".repeat(129))

        assertNull(codec.decode(longMerchant))
        assertNull(codec.decode(longAccount))
    }

    @Test
    fun `encoded utf8 payload stays within android intent budget`() {
        val encoded = codec.encode(
            ReviewRequest(
                List(5) { index ->
                    choice(
                        candidate = candidate(
                            merchant = "商".repeat(256),
                            account = "账".repeat(128),
                            digest = index.toString(16).repeat(64),
                        ),
                        matchTransactionId = index.toLong() + 1,
                    )
                },
            ),
        )

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        assertEquals(5, codec.decode(encoded)?.options?.size)
    }

    private fun choice(
        candidate: RecognitionCandidate = candidate(),
        matchTransactionId: Long? = 73,
    ) = RecognitionChoice(candidate, matchTransactionId)

    private fun candidate(
        merchant: String? = "中国电信",
        account: String? = "招商银行",
        digest: String = DIGEST,
    ) = RecognitionCandidate(
        platform = PaymentPlatform.WECHAT,
        channel = CaptureChannel.SCREENSHOT,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = 12_345,
        direction = Direction.EXPENSE,
        merchant = merchant,
        account = account,
        occurredAt = Instant.parse("2026-07-29T08:30:00Z"),
        confidence = 87,
        evidenceDigest = digest,
    )

    private companion object {
        val DIGEST = "ab".repeat(32)
    }
}
