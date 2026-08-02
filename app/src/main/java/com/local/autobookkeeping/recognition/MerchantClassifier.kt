package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.util.Locale

data class Classification(
    val category: String?,
    val account: String?,
)

class MerchantClassifier(
    private val store: RecognitionStore,
) {
    suspend fun classify(candidate: RecognitionCandidate): Classification {
        val merchant = candidate.merchant?.let(::normalizeMerchant)
        val user = merchant?.let { store.findMerchantRule(it) }
        if (user != null && store.isCategoryVisible(user.category)) {
            return Classification(user.category, user.account ?: candidate.account)
        }

        val category = when (candidate.eventKind) {
            TransactionEventKind.REFUND -> "退款"
            TransactionEventKind.TRANSFER -> "转账"
            TransactionEventKind.RED_PACKET -> "红包"
            else -> merchant?.takeIf(::isTelecomMerchant)?.let { "通讯" }
        }
        return Classification(
            category = category?.takeIf { store.isCategoryVisible(it) },
            account = candidate.account,
        )
    }
}

internal fun normalizeMerchant(value: String): String = value.trim().lowercase(Locale.ROOT)

private fun isTelecomMerchant(value: String): Boolean = value in TELECOM_MERCHANTS

private val TELECOM_MERCHANTS = setOf("中国电信", "中国移动", "中国联通")
