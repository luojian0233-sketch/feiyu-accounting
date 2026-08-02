package com.local.autobookkeeping.recognition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RecognitionDiagnostic {
    NONE,
    SAVED,
    NEEDS_CONFIRMATION,
    MULTIPLE_CANDIDATES,
    INCOMPLETE_FIELDS,
    PERMISSION_MISSING,
    CAPTURE_FAILED,
    OCR_FAILED,
}

interface RecognitionDiagnostics {
    val state: StateFlow<RecognitionDiagnostic>
    fun record(value: RecognitionDiagnostic)
}

class InMemoryRecognitionDiagnostics : RecognitionDiagnostics {
    private val mutableState = MutableStateFlow(RecognitionDiagnostic.NONE)
    override val state: StateFlow<RecognitionDiagnostic> = mutableState

    override fun record(value: RecognitionDiagnostic) {
        mutableState.value = value
    }
}
