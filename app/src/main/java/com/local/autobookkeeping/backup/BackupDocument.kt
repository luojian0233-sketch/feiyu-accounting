package com.local.autobookkeeping.backup

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class BackupDocument(
    val formatVersion: Int = 1,
    val exportedAtEpochMillis: Long,
    val transactions: List<BackupTransaction>,
    val categories: List<BackupCategory>,
    val accounts: List<BackupAccount>,
    val merchantRules: List<BackupMerchantRule>,
    val theme: String,
)

data class BackupData(
    val transactions: List<BackupTransaction>,
    val categories: List<BackupCategory>,
    val accounts: List<BackupAccount>,
    val merchantRules: List<BackupMerchantRule>,
    val theme: String,
)

data class BackupTransaction(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val direction: String,
    val categoryId: Long,
    val occurredAtEpochMillis: Long,
    val merchant: String,
    val note: String,
    val accountId: Long?,
    val source: String,
    val pendingConfirmation: Boolean,
    val internalTransfer: Boolean,
    val dedupeKey: String?,
    val redactedNotificationDigest: String?,
    val refundOfId: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class BackupCategory(
    val id: Long,
    val name: String,
    val displayOrder: Int,
    val hidden: Boolean,
)

data class BackupAccount(
    val id: Long,
    val name: String,
    val currency: String,
    val displayOrder: Int,
    val hidden: Boolean,
)

data class BackupMerchantRule(
    val id: Long,
    val merchantPattern: String,
    val categoryId: Long,
    val accountId: Long?,
    val priority: Int,
)

class BackupFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class BackupJsonCodec(
    private val json: Json = Json,
) {
    fun encode(document: BackupDocument, output: OutputStream) {
        val bytes = document.toJson().toString().toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
    }

    fun decode(input: InputStream): BackupDocument {
        val text = decodeStrictUtf8(input.readBytes())
        return try {
            DuplicateKeyJsonScanner(text).check()
            json.parseToJsonElement(text).jsonObject.toBackupDocument()
        } catch (failure: BackupFormatException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw BackupFormatException("备份文件不是有效 JSON", failure)
        }
    }
}

private fun decodeStrictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (failure: java.nio.charset.CharacterCodingException) {
    throw BackupFormatException("备份文件不是有效 UTF-8", failure)
}

private class DuplicateKeyJsonScanner(
    private val source: String,
) {
    private var index = 0

    fun check() {
        skipWhitespace()
        scanValue()
        skipWhitespace()
        if (index != source.length) invalidJson()
    }

    private fun scanValue() {
        skipWhitespace()
        when (source.getOrNull(index)) {
            '{' -> scanObject()
            '[' -> scanArray()
            '"' -> parseString()
            null -> invalidJson()
            else -> scanPrimitive()
        }
    }

    private fun scanObject() {
        expect('{')
        skipWhitespace()
        if (consumeIf('}')) return
        val names = hashSetOf<String>()
        while (true) {
            skipWhitespace()
            if (source.getOrNull(index) != '"') invalidJson()
            val name = parseString()
            if (!names.add(name)) throw BackupFormatException("备份对象包含重复字段")
            skipWhitespace()
            expect(':')
            scanValue()
            skipWhitespace()
            when {
                consumeIf(',') -> Unit
                consumeIf('}') -> return
                else -> invalidJson()
            }
        }
    }

    private fun scanArray() {
        expect('[')
        skipWhitespace()
        if (consumeIf(']')) return
        while (true) {
            scanValue()
            skipWhitespace()
            when {
                consumeIf(',') -> Unit
                consumeIf(']') -> return
                else -> invalidJson()
            }
        }
    }

    private fun scanPrimitive() {
        val start = index
        while (index < source.length && source[index] !in VALUE_DELIMITERS) index += 1
        if (start == index) invalidJson()
    }

    private fun parseString(): String {
        expect('"')
        val decoded = StringBuilder()
        while (index < source.length) {
            val value = source[index++]
            when {
                value == '"' -> return decoded.toString()
                value == '\\' -> decoded.append(parseEscape())
                value.code < 0x20 -> invalidJson()
                else -> decoded.append(value)
            }
        }
        invalidJson()
    }

    private fun parseEscape(): Char {
        val escaped = source.getOrNull(index++) ?: invalidJson()
        return when (escaped) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > source.length) invalidJson()
                val hex = source.substring(index, index + 4)
                if (hex.any { it.digitToIntOrNull(16) == null }) invalidJson()
                index += 4
                hex.toInt(16).toChar()
            }
            else -> invalidJson()
        }
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index) in JSON_WHITESPACE) index += 1
    }

    private fun expect(expected: Char) {
        if (!consumeIf(expected)) invalidJson()
    }

    private fun consumeIf(expected: Char): Boolean {
        if (source.getOrNull(index) != expected) return false
        index += 1
        return true
    }

    private fun invalidJson(): Nothing = throw BackupFormatException("备份文件不是有效 JSON")

    private companion object {
        val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
        val VALUE_DELIMITERS = setOf(' ', '\t', '\r', '\n', ',', ']', '}')
    }
}

private fun BackupDocument.toJson(): JsonObject = buildJsonObject {
    put("formatVersion", formatVersion)
    put("exportedAtEpochMillis", exportedAtEpochMillis)
    put("transactions", buildJsonArray { transactions.forEach { add(it.toJson()) } })
    put("categories", buildJsonArray { categories.forEach { add(it.toJson()) } })
    put("accounts", buildJsonArray { accounts.forEach { add(it.toJson()) } })
    put("merchantRules", buildJsonArray { merchantRules.forEach { add(it.toJson()) } })
    put("theme", theme)
}

private fun BackupTransaction.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("amountMinor", amountMinor)
    put("currency", currency)
    put("direction", direction)
    put("categoryId", categoryId)
    put("occurredAtEpochMillis", occurredAtEpochMillis)
    put("merchant", merchant)
    put("note", note)
    putNullable("accountId", accountId)
    put("source", source)
    put("pendingConfirmation", pendingConfirmation)
    put("internalTransfer", internalTransfer)
    putNullable("dedupeKey", dedupeKey)
    putNullable("redactedNotificationDigest", redactedNotificationDigest)
    putNullable("refundOfId", refundOfId)
    put("createdAtEpochMillis", createdAtEpochMillis)
    put("updatedAtEpochMillis", updatedAtEpochMillis)
}

private fun BackupCategory.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("displayOrder", displayOrder)
    put("hidden", hidden)
}

private fun BackupAccount.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("currency", currency)
    put("displayOrder", displayOrder)
    put("hidden", hidden)
}

private fun BackupMerchantRule.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("merchantPattern", merchantPattern)
    put("categoryId", categoryId)
    putNullable("accountId", accountId)
    put("priority", priority)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.toBackupDocument(): BackupDocument {
    requireKeys(DOCUMENT_KEYS)
    return BackupDocument(
        formatVersion = requiredInt("formatVersion"),
        exportedAtEpochMillis = requiredLong("exportedAtEpochMillis"),
        transactions = requiredArray("transactions").map { it.requiredObject().toBackupTransaction() },
        categories = requiredArray("categories").map { it.requiredObject().toBackupCategory() },
        accounts = requiredArray("accounts").map { it.requiredObject().toBackupAccount() },
        merchantRules = requiredArray("merchantRules").map { it.requiredObject().toBackupMerchantRule() },
        theme = requiredString("theme"),
    )
}

private fun JsonObject.toBackupTransaction(): BackupTransaction {
    requireKeys(TRANSACTION_KEYS)
    return BackupTransaction(
        id = requiredLong("id"),
        amountMinor = requiredLong("amountMinor"),
        currency = requiredString("currency"),
        direction = requiredString("direction"),
        categoryId = requiredLong("categoryId"),
        occurredAtEpochMillis = requiredLong("occurredAtEpochMillis"),
        merchant = requiredString("merchant"),
        note = requiredString("note"),
        accountId = nullableLong("accountId"),
        source = requiredString("source"),
        pendingConfirmation = requiredBoolean("pendingConfirmation"),
        internalTransfer = requiredBoolean("internalTransfer"),
        dedupeKey = nullableString("dedupeKey"),
        redactedNotificationDigest = nullableString("redactedNotificationDigest"),
        refundOfId = nullableLong("refundOfId"),
        createdAtEpochMillis = requiredLong("createdAtEpochMillis"),
        updatedAtEpochMillis = requiredLong("updatedAtEpochMillis"),
    )
}

private fun JsonObject.toBackupCategory(): BackupCategory {
    requireKeys(CATEGORY_KEYS)
    return BackupCategory(
        id = requiredLong("id"),
        name = requiredString("name"),
        displayOrder = requiredInt("displayOrder"),
        hidden = requiredBoolean("hidden"),
    )
}

private fun JsonObject.toBackupAccount(): BackupAccount {
    requireKeys(ACCOUNT_KEYS)
    return BackupAccount(
        id = requiredLong("id"),
        name = requiredString("name"),
        currency = requiredString("currency"),
        displayOrder = requiredInt("displayOrder"),
        hidden = requiredBoolean("hidden"),
    )
}

private fun JsonObject.toBackupMerchantRule(): BackupMerchantRule {
    requireKeys(MERCHANT_RULE_KEYS)
    return BackupMerchantRule(
        id = requiredLong("id"),
        merchantPattern = requiredString("merchantPattern"),
        categoryId = requiredLong("categoryId"),
        accountId = nullableLong("accountId"),
        priority = requiredInt("priority"),
    )
}

private fun JsonObject.requireKeys(expected: Set<String>) {
    if (keys != expected) throw BackupFormatException("备份字段缺失或包含未知字段")
}

private fun JsonObject.requiredArray(key: String): JsonArray =
    this[key]?.let { element ->
        runCatching { element.jsonArray }.getOrNull()
    } ?: throw BackupFormatException("$key 必须是数组")

private fun JsonElement.requiredObject(): JsonObject =
    runCatching { jsonObject }.getOrNull()
        ?: throw BackupFormatException("数组元素必须是对象")

private fun JsonObject.requiredString(key: String): String =
    (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?: throw BackupFormatException("$key 必须是字符串")

private fun JsonObject.nullableString(key: String): String? {
    val value = this[key] ?: throw BackupFormatException("缺少字段 $key")
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?: throw BackupFormatException("$key 必须是字符串或 null")
}

private fun JsonObject.requiredLong(key: String): Long =
    (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull
        ?: throw BackupFormatException("$key 必须是整数")

private fun JsonObject.nullableLong(key: String): Long? {
    val value = this[key] ?: throw BackupFormatException("缺少字段 $key")
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull
        ?: throw BackupFormatException("$key 必须是整数或 null")
}

private fun JsonObject.requiredInt(key: String): Int =
    (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.intOrNull
        ?: throw BackupFormatException("$key 必须是整数")

private fun JsonObject.requiredBoolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull
        ?: throw BackupFormatException("$key 必须是布尔值")

private val DOCUMENT_KEYS = setOf(
    "formatVersion",
    "exportedAtEpochMillis",
    "transactions",
    "categories",
    "accounts",
    "merchantRules",
    "theme",
)

private val TRANSACTION_KEYS = setOf(
    "id",
    "amountMinor",
    "currency",
    "direction",
    "categoryId",
    "occurredAtEpochMillis",
    "merchant",
    "note",
    "accountId",
    "source",
    "pendingConfirmation",
    "internalTransfer",
    "dedupeKey",
    "redactedNotificationDigest",
    "refundOfId",
    "createdAtEpochMillis",
    "updatedAtEpochMillis",
)

private val CATEGORY_KEYS = setOf("id", "name", "displayOrder", "hidden")
private val ACCOUNT_KEYS = setOf("id", "name", "currency", "displayOrder", "hidden")
private val MERCHANT_RULE_KEYS = setOf("id", "merchantPattern", "categoryId", "accountId", "priority")
