package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot

internal object TrustedAlipayBareAmountRecovery {
    fun recover(
        original: PageSnapshot,
        ocrElements: List<ObservedTextElement>,
    ): List<ObservedTextElement> {
        if (!TrustedAccessibilityAnchorMerger.hasTrustedContext(original)) return ocrElements
        if (ocrElements.any { CURRENCY_AMOUNT.containsMatchIn(it.text) }) return ocrElements

        val candidates = ocrElements.mapIndexedNotNull { index, element ->
            index.takeIf { BARE_TWO_DECIMAL.matches(element.text.trim()) }
        }
        if (candidates.size != 1) return ocrElements

        val selectedIndex = candidates.single()
        return ocrElements.mapIndexed { index, element ->
            if (index != selectedIndex) {
                element
            } else {
                element.copy(text = "¥" + element.text.trim().replace(',', '.'))
            }
        }
    }

    private val BARE_TWO_DECIMAL = Regex("[0-9]{1,8}[.,][0-9]{2}")
    private val CURRENCY_AMOUNT = Regex(
        "(?:人民币|CNY|RMB|[¥￥])\\s*[0-9]{1,8}(?:[.,][0-9]{1,2})?",
        RegexOption.IGNORE_CASE,
    )
}
