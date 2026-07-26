package com.local.autobookkeeping

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun <T> withBackupOutputStreamOnIo(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    open: () -> OutputStream?,
    block: suspend (OutputStream) -> T,
): T? = withContext(ioDispatcher) {
    open()?.use { output -> block(output) }
}

internal suspend fun <T> withBackupInputStreamOnIo(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    open: () -> InputStream?,
    block: suspend (InputStream) -> T,
): T? = withContext(ioDispatcher) {
    open()?.use { input -> block(input) }
}
