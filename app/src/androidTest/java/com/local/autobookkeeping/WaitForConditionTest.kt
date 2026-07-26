package com.local.autobookkeeping

import androidx.compose.ui.test.ComposeTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitForConditionTest {
    @Test
    fun composeTimeoutHasTargetedAssertionDiagnosticAndOriginalCause() {
        val timeout = ComposeTimeoutException("framework timeout")
        var thrown: Throwable? = null

        try {
            withTimeoutDiagnostic("小票账本主题写入并应用", 5_000L) {
                throw timeout
            }
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertTrue(thrown is AssertionError)
        assertEquals(
            "等待超时（5000ms）：小票账本主题写入并应用",
            thrown?.message,
        )
        assertSame(timeout, thrown?.cause)
    }

    @Test
    fun nonTimeoutFailurePropagatesWithoutRewriting() {
        val expected = IllegalStateException("condition failed")
        var thrown: Throwable? = null

        try {
            withTimeoutDiagnostic("不会使用的描述", 5_000L) {
                throw expected
            }
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertSame(expected, thrown)
    }
}
