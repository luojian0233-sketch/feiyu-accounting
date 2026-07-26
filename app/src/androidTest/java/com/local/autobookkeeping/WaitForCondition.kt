package com.local.autobookkeeping

import androidx.compose.ui.test.ComposeTimeoutException

internal fun withTimeoutDiagnostic(
    description: String,
    timeoutMillis: Long,
    wait: () -> Unit,
) {
    try {
        wait()
    } catch (failure: ComposeTimeoutException) {
        throw AssertionError(
            "等待超时（${timeoutMillis}ms）：$description",
            failure,
        )
    }
}
