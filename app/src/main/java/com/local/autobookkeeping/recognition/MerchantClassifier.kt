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
        // 只有像「商户名」的值才拿去查规则。通知里抽出来的「支付成功」「1」「KB/S」
        // 这类残渣一旦被记住，以后每一笔同类通知都会自动归到那一类，且再也不进待核对。
        val merchant = candidate.merchant
            ?.let(::normalizeMerchant)
            ?.takeIf(::isLearnableMerchant)
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

/**
 * 这个值配不配当「商户名」被记住。
 *
 * 自动记账最难的一步是从通知或页面里认出商户，认不出来时会退化成整句里的某个片段：
 * 状态词（「支付成功」）、界面噪声（「KB/S」）、纯数字（「1」）、占位分类（「其他」）。
 * 这类值如果被用户的一次手动核实写成规则，之后同一串残渣每次都会命中规则、直接归类，
 * 而且因为命中了分类就不再进待核对——错误会静默扩散。
 *
 * 所以真正的闸门在这里：不像商户名的值，既不写入规则，也不拿去查规则。
 * 对漏网的怪值（比如全大写缩写）宁可放弃学习，也不能让它替用户做决定。
 */
internal fun isLearnableMerchant(value: String?): Boolean {
    val name = value?.trim().orEmpty()
    if (name.length !in 2..32) return false
    if (name.none(Char::isLetterOrDigit)) return false
    if (name.all(Char::isDigit)) return false
    val normalized = name.lowercase(Locale.ROOT)
    if (normalized in PLACEHOLDER_MERCHANTS) return false
    if (NON_MERCHANT_FRAGMENTS.any(normalized::contains)) return false
    return true
}

/** 分类体系里的占位名，永远不该作为商户名学习。 */
private val PLACEHOLDER_MERCHANTS = setOf(
    "其他", "其它", "待分类", "待确认", "未分类", "未知", "无",
)

/**
 * 交易状态、界面标签、支付平台名。出现在「商户名」里就说明抽取抽错了位置。
 *
 * 用「包含」而不是「等于」：通知常把状态词粘在别的字上，例如「支付成功-美团订单」。
 */
private val NON_MERCHANT_FRAGMENTS = setOf(
    "支付成功", "付款成功", "扣款成功", "交易成功", "收款成功", "退款成功",
    "转账成功", "充值成功", "提现成功", "支付失败", "付款失败", "交易失败",
    "退款失败", "已支付", "已付款", "已收款", "待支付", "处理中", "支付结果",
    "交易详情", "账单详情", "订单详情", "微信支付", "支付宝", "云闪付",
    "零钱", "余额宝", "花呗", "银行卡", "当前状态", "交易单号", "商户单号",
)

private fun isTelecomMerchant(value: String): Boolean = value in TELECOM_MERCHANTS

private val TELECOM_MERCHANTS = setOf("中国电信", "中国移动", "中国联通")
