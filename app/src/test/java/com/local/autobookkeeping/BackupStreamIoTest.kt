package com.local.autobookkeeping

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class BackupStreamIoTest {
    @Test
    fun `export operation and close both execute on IO dispatcher`() = runTest {
        val callerThread = Thread.currentThread().name
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "backup-stream-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val operationThread = AtomicReference<String>()
        val closeThread = AtomicReference<String>()
        val stream = object : ByteArrayOutputStream() {
            override fun close() {
                closeThread.set(Thread.currentThread().name)
                super.close()
            }
        }
        try {
            val result = withBackupOutputStreamOnIo(
                ioDispatcher = dispatcher,
                open = { stream },
            ) { output ->
                operationThread.set(Thread.currentThread().name)
                output.write(7)
                "exported"
            }

            assertEquals("exported", result)
            assertEquals("backup-stream-io", operationThread.get())
            assertEquals("backup-stream-io", closeThread.get())
            assertNotEquals(callerThread, closeThread.get())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `restore operation and close both execute on IO dispatcher`() = runTest {
        val callerThread = Thread.currentThread().name
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "backup-stream-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val operationThread = AtomicReference<String>()
        val closeThread = AtomicReference<String>()
        val stream = object : ByteArrayInputStream(byteArrayOf(7)) {
            override fun close() {
                closeThread.set(Thread.currentThread().name)
                super.close()
            }
        }
        try {
            val result = withBackupInputStreamOnIo(
                ioDispatcher = dispatcher,
                open = { stream },
            ) { input ->
                operationThread.set(Thread.currentThread().name)
                input.read()
            }

            assertEquals(7, result)
            assertEquals("backup-stream-io", operationThread.get())
            assertEquals("backup-stream-io", closeThread.get())
            assertNotEquals(callerThread, closeThread.get())
        } finally {
            dispatcher.close()
        }
    }
}
