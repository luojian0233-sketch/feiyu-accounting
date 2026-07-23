package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.DuplicateDedupeKeyException
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction

class CandidateProcessor(
    private val repository: LedgerRepository,
) {
    suspend fun process(candidate: PaymentCandidate): Long? {
        if (repository.findByDedupeKey(candidate.dedupeKey) != null) return null

        // Direction is required by the Task 2 domain model. EXPENSE is only a storage
        // placeholder for direction-unknown rows, which always remain pending and are
        // excluded from statistics until a user confirms their direction.
        val pendingConfirmation = requiresConfirmation(candidate)
        val transaction = Transaction(
            amountMinor = candidate.amountMinor,
            direction = candidate.direction ?: Direction.EXPENSE,
            category = if (pendingConfirmation) PENDING_CATEGORY else DEFAULT_CATEGORY,
            occurredAt = candidate.occurredAt,
            merchant = candidate.merchant.orEmpty(),
            source = Source.NOTIFICATION,
            pendingConfirmation = pendingConfirmation,
            dedupeKey = candidate.dedupeKey,
        )
        return try {
            repository.save(transaction)
        } catch (_: DuplicateDedupeKeyException) {
            null
        }
    }

    companion object {
        const val DIRECT_SAVE_CONFIDENCE = 80
        fun requiresConfirmation(candidate: PaymentCandidate): Boolean =
            candidate.direction == null ||
                candidate.merchant == null ||
                candidate.eventKind == PaymentEventKind.TRANSFER ||
                candidate.confidence < DIRECT_SAVE_CONFIDENCE

        private const val PENDING_CATEGORY = "待确认"
        private const val DEFAULT_CATEGORY = "其他"
    }
}
