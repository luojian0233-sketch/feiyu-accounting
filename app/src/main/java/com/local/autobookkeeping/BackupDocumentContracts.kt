package com.local.autobookkeeping

import androidx.activity.result.contract.ActivityResultContracts

internal const val BACKUP_MIME_TYPE = "application/json"

internal fun backupCreateDocumentContract() =
    ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)

internal fun backupOpenDocumentContract() =
    ActivityResultContracts.OpenDocument()
