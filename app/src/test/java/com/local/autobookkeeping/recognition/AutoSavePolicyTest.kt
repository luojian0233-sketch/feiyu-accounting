package com.local.autobookkeeping.recognition

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoSavePolicyTest {
    @Test
    fun `policy returns the stored setting value`() = runTest {
        assertTrue(AutoSavePolicy.failClosed(flowOf(true)).isEnabled())
        assertFalse(AutoSavePolicy.failClosed(flowOf(false)).isEnabled())
    }

    @Test
    fun `policy fails closed when setting flow throws IOException`() = runTest {
        val policy = AutoSavePolicy.failClosed(
            flow { throw IOException("unreadable preference file") },
        )

        assertFalse(policy.isEnabled())
    }

    @Test
    fun `policy fails closed when setting flow throws runtime exception`() = runTest {
        val policy = AutoSavePolicy.failClosed(
            flow { throw IllegalStateException("broken settings adapter") },
        )

        assertFalse(policy.isEnabled())
    }

    @Test
    fun `policy rethrows cancellation from setting flow`() = runTest {
        val cancellation = CancellationException("stop auto save decision")
        val policy = AutoSavePolicy.failClosed(
            flow { throw cancellation },
        )
        var caught: Throwable? = null

        try {
            policy.isEnabled()
        } catch (failure: Throwable) {
            caught = failure
        }

        assertSame(cancellation, caught)
    }
}
