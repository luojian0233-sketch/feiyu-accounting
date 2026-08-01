package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.security.MessageDigest
import java.time.Instant

object RecognitionDigest {
    fun create(
        platform: PaymentPlatform,
        eventKind: TransactionEventKind,
        direction: Direction,
        amountMinor: Long,
        merchant: String?,
        account: String?,
        visibleAt: Instant?,
    ): String {
        val normalizedFields = listOf(
            DIGEST_VERSION,
            platform.name,
            eventKind.name,
            direction.name,
            amountMinor.toString(),
            merchant.orEmpty(),
            account.orEmpty(),
            visibleAt?.epochSecond?.div(VISIBLE_TIME_BUCKET_SECONDS)?.toString().orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalizedFields.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private const val DIGEST_VERSION = "page-evidence-v1"
    private const val VISIBLE_TIME_BUCKET_SECONDS = 120L
}
