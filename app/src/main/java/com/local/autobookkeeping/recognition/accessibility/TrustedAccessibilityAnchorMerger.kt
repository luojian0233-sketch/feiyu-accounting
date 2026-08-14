package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds

internal object TrustedAccessibilityAnchorMerger {
    fun merge(
        original: PageSnapshot,
        ocrElements: List<ObservedTextElement>,
    ): List<ObservedTextElement> {
        if (original.packageName != ALIPAY_PACKAGE) return ocrElements

        val trustedAnchors = original.elements.filter { element ->
            element.text.trim() in TRUSTED_DETAIL_ANCHORS
        }
        if (trustedAnchors.isEmpty()) return ocrElements

        val seen = ocrElements.mapTo(mutableSetOf()) { it.anchorKey() }
        return buildList {
            addAll(ocrElements)
            trustedAnchors.forEach { anchor ->
                if (seen.add(anchor.anchorKey())) add(anchor)
            }
        }
    }

    private fun ObservedTextElement.anchorKey() = AnchorKey(
        text = text.trim(),
        bounds = bounds,
    )

    private data class AnchorKey(
        val text: String,
        val bounds: TextBounds,
    )

    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    private val TRUSTED_DETAIL_ANCHORS = setOf("账单详情", "账单明细")
}
