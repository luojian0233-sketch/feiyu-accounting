package com.local.autobookkeeping.ui.recognition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import java.time.format.DateTimeFormatter

@Composable
fun RecognitionReviewScreen(
    request: ReviewRequest,
    processing: Boolean,
    onChoiceSelected: (RecognitionChoice) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, enabled = !processing) {
                Text("返回", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = "选择待核对记录",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = "请选择与本次识别对应的一笔",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (processing) {
            Row(
                modifier = Modifier.testTag("review-processing"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("正在处理", style = MaterialTheme.typography.labelSmall)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = request.options,
                key = { index, choice ->
                    choice.matchTransactionId?.let { "transaction-$it" }
                        ?: "candidate-${choice.candidate.evidenceDigest}-$index"
                },
            ) { index, choice ->
                ReviewOption(
                    choice = choice,
                    enabled = !processing,
                    onClick = { onChoiceSelected(choice) },
                    modifier = Modifier.testTag("review-option-$index"),
                )
            }
        }
    }
}

@Composable
private fun ReviewOption(
    choice: RecognitionChoice,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidate = choice.candidate
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = candidate.directionAndAmount(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = candidate.merchant?.trim()?.takeIf(String::isNotBlank)
                        ?: candidate.eventKind.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                candidate.account?.trim()?.takeIf(String::isNotBlank)?.let { account ->
                    Text(
                        text = account,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = candidate.occurredAt
                        .atZone(BOOKKEEPING_ZONE)
                        .format(REVIEW_TIME_FORMAT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun RecognitionCandidate.directionAndAmount(): String {
    val directionLabel = when (direction) {
        Direction.EXPENSE -> "支出"
        Direction.INCOME -> "收入"
        null -> "方向待定"
    }
    return "$directionLabel ¥${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
}

private fun TransactionEventKind.displayName(): String = when (this) {
    TransactionEventKind.PAYMENT -> "付款"
    TransactionEventKind.RECEIPT -> "收款"
    TransactionEventKind.REFUND -> "退款"
    TransactionEventKind.TRANSFER -> "转账"
    TransactionEventKind.RED_PACKET -> "红包"
    TransactionEventKind.UNKNOWN -> "未知交易"
}

private val REVIEW_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
