package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ReviewRequest(val options: List<RecognitionChoice>) {
    init {
        require(options.size in 1..MAX_REVIEW_OPTIONS) {
            "Review requests must contain between one and five options"
        }
    }
}

class ReviewRequestCodec(
    private val json: Json = Json,
) {
    fun encode(request: ReviewRequest): String {
        request.options.forEach(::validateChoice)
        val encoded = buildJsonObject {
            put(
                OPTIONS,
                buildJsonArray {
                    request.options.forEach { choice -> add(choice.toJson()) }
                },
            )
        }.toString()
        require(encoded.utf8SizeWithinLimit()) { "Review request payload is too large" }
        return encoded
    }

    fun decode(encoded: String): ReviewRequest? {
        if (!encoded.utf8SizeWithinLimit()) return null
        return try {
            val root = json.parseToJsonElement(encoded) as? JsonObject ?: return null
            if (!root.hasOnly(ROOT_KEYS)) return null
            val rows = root[OPTIONS] as? JsonArray ?: return null
            if (rows.size !in 1..MAX_REVIEW_OPTIONS) return null
            ReviewRequest(rows.map { row -> row.toChoiceOrNull() ?: return null })
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun validateChoice(choice: RecognitionChoice) {
        val candidate = choice.candidate
        require(candidate.merchant.lengthAtMost(MAX_MERCHANT_LENGTH))
        require(candidate.account.lengthAtMost(MAX_ACCOUNT_LENGTH))
        require(choice.matchTransactionId == null || choice.matchTransactionId > 0)
    }

    private fun RecognitionChoice.toJson() = buildJsonObject {
        put(CANDIDATE, candidate.toJson())
        put(
            MATCH_TRANSACTION_ID,
            matchTransactionId?.let(::JsonPrimitive) ?: JsonNull,
        )
    }

    private fun RecognitionCandidate.toJson() = buildJsonObject {
        put(PLATFORM, platform.name)
        put(CHANNEL, channel.name)
        put(EVENT_KIND, eventKind.name)
        put(AMOUNT_MINOR, amountMinor)
        put(DIRECTION, direction?.let { JsonPrimitive(it.name) } ?: JsonNull)
        put(MERCHANT, merchant?.let(::JsonPrimitive) ?: JsonNull)
        put(ACCOUNT, account?.let(::JsonPrimitive) ?: JsonNull)
        put(OCCURRED_AT_EPOCH_MILLIS, occurredAt.toEpochMilli())
        put(CONFIDENCE, confidence)
        put(EVIDENCE_DIGEST, evidenceDigest)
    }

    private fun kotlinx.serialization.json.JsonElement.toChoiceOrNull(): RecognitionChoice? {
        val row = this as? JsonObject ?: return null
        if (!row.hasOnly(CHOICE_KEYS)) return null
        val candidate = row[CANDIDATE].toCandidateOrNull() ?: return null
        val matchId = row[MATCH_TRANSACTION_ID].nullableLongOrInvalid()
        if (matchId === INVALID_VALUE) return null
        val typedMatchId = matchId as Long?
        if (typedMatchId != null && typedMatchId <= 0) return null
        return RecognitionChoice(candidate, typedMatchId)
    }

    private fun kotlinx.serialization.json.JsonElement?.toCandidateOrNull(): RecognitionCandidate? {
        val row = this as? JsonObject ?: return null
        if (!row.hasOnly(CANDIDATE_KEYS)) return null
        val platform = row[PLATFORM].enumOrNull<PaymentPlatform>() ?: return null
        val channel = row[CHANNEL].enumOrNull<CaptureChannel>() ?: return null
        val eventKind = row[EVENT_KIND].enumOrNull<TransactionEventKind>() ?: return null
        val amountMinor = row[AMOUNT_MINOR].unquotedLongOrNull()?.takeIf { it > 0 } ?: return null
        val directionValue = row[DIRECTION]
        val direction = when (directionValue) {
            JsonNull -> null
            else -> directionValue.enumOrNull<Direction>() ?: return null
        }
        val merchantValue = row[MERCHANT].nullableStringOrInvalid()
        if (merchantValue === INVALID_VALUE) return null
        val merchant = merchantValue as String?
        if (!merchant.lengthAtMost(MAX_MERCHANT_LENGTH)) return null
        val accountValue = row[ACCOUNT].nullableStringOrInvalid()
        if (accountValue === INVALID_VALUE) return null
        val account = accountValue as String?
        if (!account.lengthAtMost(MAX_ACCOUNT_LENGTH)) return null
        val occurredAtEpochMillis = row[OCCURRED_AT_EPOCH_MILLIS].unquotedLongOrNull() ?: return null
        val confidence = row[CONFIDENCE]
            .unquotedLongOrNull()
            ?.takeIf { it in 0..100 }
            ?.toInt()
            ?: return null
        val digest = row[EVIDENCE_DIGEST].quotedStringOrNull() ?: return null
        if (!digest.matches(LOWERCASE_SHA256)) return null
        return RecognitionCandidate(
            platform = platform,
            channel = channel,
            eventKind = eventKind,
            amountMinor = amountMinor,
            direction = direction,
            merchant = merchant,
            account = account,
            occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis),
            confidence = confidence,
            evidenceDigest = digest,
        )
    }

    private inline fun <reified T : Enum<T>> kotlinx.serialization.json.JsonElement?.enumOrNull(): T? {
        val name = quotedStringOrNull() ?: return null
        return enumValues<T>().singleOrNull { it.name == name }
    }

    private fun kotlinx.serialization.json.JsonElement?.quotedStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.unquotedLongOrNull(): Long? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun kotlinx.serialization.json.JsonElement?.nullableLongOrInvalid(): Any? = when (this) {
        JsonNull -> null
        else -> unquotedLongOrNull() ?: INVALID_VALUE
    }

    private fun kotlinx.serialization.json.JsonElement?.nullableStringOrInvalid(): Any? = when (this) {
        JsonNull -> null
        else -> quotedStringOrNull() ?: INVALID_VALUE
    }

    private fun JsonObject.hasOnly(expected: Set<String>): Boolean = keys == expected

    private fun String.utf8SizeWithinLimit(): Boolean =
        length <= MAX_REVIEW_PAYLOAD_BYTES &&
            toByteArray(Charsets.UTF_8).size <= MAX_REVIEW_PAYLOAD_BYTES

    private fun String?.lengthAtMost(maximum: Int): Boolean = this == null || length <= maximum

    private companion object {
        const val OPTIONS = "options"
        const val CANDIDATE = "candidate"
        const val MATCH_TRANSACTION_ID = "matchTransactionId"
        const val PLATFORM = "platform"
        const val CHANNEL = "channel"
        const val EVENT_KIND = "eventKind"
        const val AMOUNT_MINOR = "amountMinor"
        const val DIRECTION = "direction"
        const val MERCHANT = "merchant"
        const val ACCOUNT = "account"
        const val OCCURRED_AT_EPOCH_MILLIS = "occurredAtEpochMillis"
        const val CONFIDENCE = "confidence"
        const val EVIDENCE_DIGEST = "evidenceDigest"
        val ROOT_KEYS = setOf(OPTIONS)
        val CHOICE_KEYS = setOf(CANDIDATE, MATCH_TRANSACTION_ID)
        val CANDIDATE_KEYS = setOf(
            PLATFORM,
            CHANNEL,
            EVENT_KIND,
            AMOUNT_MINOR,
            DIRECTION,
            MERCHANT,
            ACCOUNT,
            OCCURRED_AT_EPOCH_MILLIS,
            CONFIDENCE,
            EVIDENCE_DIGEST,
        )
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
        val INVALID_VALUE = Any()
    }
}

internal const val MAX_REVIEW_OPTIONS = 5
internal const val MAX_REVIEW_PAYLOAD_BYTES = 32 * 1024
private const val MAX_MERCHANT_LENGTH = 256
private const val MAX_ACCOUNT_LENGTH = 128
