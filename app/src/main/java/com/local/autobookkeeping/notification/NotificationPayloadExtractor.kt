package com.local.autobookkeeping.notification

import android.app.Notification

data class NotificationPayload(
    val title: String,
    val body: String,
)

object NotificationPayloadExtractor {
    fun extract(
        flags: Int,
        title: CharSequence?,
        bigText: CharSequence?,
        text: CharSequence?,
    ): NotificationPayload? {
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val body = bigText.safeText().ifBlank { text.safeText() }
        return NotificationPayload(title = title.safeText(), body = body)
    }

    private fun CharSequence?.safeText(): String = this?.toString().orEmpty()
}
