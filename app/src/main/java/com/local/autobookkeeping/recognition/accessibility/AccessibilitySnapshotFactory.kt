package com.local.autobookkeeping.recognition.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import java.time.Instant
import java.util.ArrayDeque

object AccessibilitySnapshotFactory {
    fun create(
        root: AccessibilityNodeInfo,
        packageName: String?,
        observedAt: Instant,
    ): PageSnapshot {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val elements = mutableListOf<ObservedTextElement>()
        pending.addLast(root)
        try {
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                try {
                    if (node.isVisibleToUser) {
                        node.visibleText()?.let { text ->
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            elements += ObservedTextElement(
                                text = text,
                                bounds = TextBounds(
                                    left = bounds.left,
                                    top = bounds.top,
                                    right = bounds.right,
                                    bottom = bounds.bottom,
                                ),
                            )
                        }
                    }
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let(pending::addLast)
                    }
                } finally {
                    node.releaseReference()
                }
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeFirst().releaseReference()
        }
        return PageSnapshot(
            packageName = packageName,
            elements = elements,
            observedAt = observedAt,
        )
    }

    internal fun release(root: AccessibilityNodeInfo) {
        root.releaseReference()
    }

    private fun AccessibilityNodeInfo.visibleText(): String? =
        text?.toString()?.takeIf(String::isNotBlank)
            ?: contentDescription?.toString()?.takeIf(String::isNotBlank)

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.releaseReference() {
        recycle()
    }
}
