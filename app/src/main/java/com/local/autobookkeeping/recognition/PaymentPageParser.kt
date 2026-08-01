package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

data class TextBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left)
        require(bottom >= top)
    }

    internal val centerY: Int
        get() = top + (bottom - top) / 2
}

data class ObservedTextElement(
    val text: String,
    val bounds: TextBounds,
) {
    override fun toString(): String = "ObservedTextElement(text=<redacted>, bounds=$bounds)"
}

data class PageSnapshot(
    val packageName: String?,
    val elements: List<ObservedTextElement>,
    val observedAt: Instant,
) {
    override fun toString(): String =
        "PageSnapshot(packageName=$packageName, elements=<redacted:${elements.size}>, observedAt=$observedAt)"
}

sealed interface ExtractionOutcome {
    data class Single(val candidate: RecognitionCandidate) : ExtractionOutcome {
        override fun toString(): String = "ExtractionOutcome.Single(${candidate.redactedSummary()})"
    }

    data class Multiple(val options: List<RecognitionCandidate>) : ExtractionOutcome {
        override fun toString(): String =
            "ExtractionOutcome.Multiple(options=${options.map(RecognitionCandidate::redactedSummary)})"
    }

    data class Rejected(val reason: RecognitionRejectReason) : ExtractionOutcome
}

fun interface PaymentPageParser {
    fun parse(snapshot: PageSnapshot): ExtractionOutcome
}

internal data class PageParserProfile(
    val requiredPackage: String?,
    val platform: PaymentPlatform,
    val channel: CaptureChannel,
    val defaultPaymentSignatures: Set<String>,
    val carrySignatureAcrossGroups: Boolean,
    val hardRejectSignals: Set<String>,
    val baseConfidence: Int,
)

internal fun parsePaymentPage(
    snapshot: PageSnapshot,
    profile: PageParserProfile,
    zone: ZoneId = BOOKKEEPING_ZONE,
): ExtractionOutcome {
    if (profile.requiredPackage != null && snapshot.packageName != profile.requiredPackage) {
        return ExtractionOutcome.Rejected(RecognitionRejectReason.NOT_PAYMENT_PAGE)
    }

    val elements = snapshot.elements
        .asSequence()
        .filter { it.text.isNotBlank() }
        .sortedWith(compareBy<ObservedTextElement> { it.bounds.top }.thenBy { it.bounds.left })
        .toList()
    if (elements.isEmpty()) {
        return ExtractionOutcome.Rejected(RecognitionRejectReason.NOT_PAYMENT_PAGE)
    }
    if (elements.any { element ->
            profile.hardRejectSignals.any { signal -> element.text.trim().contains(signal) }
        }
    ) {
        return ExtractionOutcome.Rejected(RecognitionRejectReason.NOT_PAYMENT_PAGE)
    }

    val pageHasDefaultSignature = elements.any { it.text.trim() in profile.defaultPaymentSignatures }
    val results = groupByGeometry(elements).map { group ->
        val cardHasDefaultSignature = group.any { it.text.trim() in profile.defaultPaymentSignatures }
        parseCard(
            elements = group,
            snapshot = snapshot,
            profile = profile,
            defaultPaymentAllowed = cardHasDefaultSignature ||
                (profile.carrySignatureAcrossGroups && pageHasDefaultSignature),
            zone = zone,
        )
    }
    val candidates = results.filterIsInstance<CardParseResult.Parsed>()
    if (candidates.isEmpty()) {
        val reason = results.filterIsInstance<CardParseResult.Invalid>()
            .map(CardParseResult.Invalid::reason)
            .let { reasons ->
                when {
                    RecognitionRejectReason.CONFLICTING_DIRECTION in reasons ->
                        RecognitionRejectReason.CONFLICTING_DIRECTION
                    RecognitionRejectReason.INCOMPLETE_FIELDS in reasons ->
                        RecognitionRejectReason.INCOMPLETE_FIELDS
                    else -> RecognitionRejectReason.NOT_PAYMENT_PAGE
                }
            }
        return ExtractionOutcome.Rejected(reason)
    }

    val ordered = candidates.sortedWith(
        compareBy<CardParseResult.Parsed> { it.top }.thenBy { it.left },
    )
    return if (ordered.size == 1) {
        ExtractionOutcome.Single(ordered.single().candidate)
    } else {
        ExtractionOutcome.Multiple(ordered.map(CardParseResult.Parsed::candidate))
    }
}

private sealed interface CardParseResult {
    data object NotRecognized : CardParseResult
    data class Invalid(val reason: RecognitionRejectReason) : CardParseResult
    data class Parsed(
        val candidate: RecognitionCandidate,
        val top: Int,
        val left: Int,
    ) : CardParseResult
}

private data class EventSignal(
    val eventKind: TransactionEventKind,
    val direction: Direction,
)

private data class LocatedAmount(
    val amountMinor: Long,
    val element: ObservedTextElement,
    val rank: Int,
)

private sealed interface AmountSelection {
    data object Missing : AmountSelection
    data object Ambiguous : AmountSelection
    data class Selected(val amount: LocatedAmount) : AmountSelection
}

private data class LocatedTime(val instant: Instant, val rank: Int)

private sealed interface TimeSelection {
    data object Missing : TimeSelection
    data object Ambiguous : TimeSelection
    data class Selected(val instant: Instant) : TimeSelection
}

private fun parseCard(
    elements: List<ObservedTextElement>,
    snapshot: PageSnapshot,
    profile: PageParserProfile,
    defaultPaymentAllowed: Boolean,
    zone: ZoneId,
): CardParseResult {
    val explicitSignals = elements
        .flatMap { element ->
            EVENT_SIGNALS.mapNotNull { (text, signal) -> signal.takeIf { element.text.contains(text) } }
        }
        .distinct()
    if (explicitSignals.size > 1) {
        return CardParseResult.Invalid(RecognitionRejectReason.CONFLICTING_DIRECTION)
    }
    val explicitSignal = explicitSignals.singleOrNull()
    val eventSignal = explicitSignal ?: DEFAULT_PAYMENT_EVENT.takeIf { defaultPaymentAllowed }
        ?: return CardParseResult.NotRecognized

    val amount = when (val selection = selectAmount(elements)) {
        AmountSelection.Missing -> return CardParseResult.Invalid(RecognitionRejectReason.INCOMPLETE_FIELDS)
        AmountSelection.Ambiguous -> return CardParseResult.Invalid(RecognitionRejectReason.INCOMPLETE_FIELDS)
        is AmountSelection.Selected -> selection.amount
    }
    val visibleTime = when (
        val selection = selectVisibleTime(elements, amount.element, snapshot.observedAt, zone)
    ) {
        TimeSelection.Missing -> null
        TimeSelection.Ambiguous -> return CardParseResult.Invalid(RecognitionRejectReason.INCOMPLETE_FIELDS)
        is TimeSelection.Selected -> selection.instant
    }
    val merchant = extractMerchant(elements, amount.element)
    val account = extractAccount(elements)
    val occurredAt = visibleTime ?: snapshot.observedAt
    val confidence = (
        profile.baseConfidence -
            (if (explicitSignal == null) 5 else 0) -
            (if (merchant == null) 8 else 0)
        ).coerceIn(0, 100)
    val digest = RecognitionDigest.create(
        platform = profile.platform,
        eventKind = eventSignal.eventKind,
        direction = eventSignal.direction,
        amountMinor = amount.amountMinor,
        merchant = merchant,
        account = account,
        visibleAt = visibleTime,
    )
    val candidate = RecognitionCandidate(
        platform = profile.platform,
        channel = profile.channel,
        eventKind = eventSignal.eventKind,
        amountMinor = amount.amountMinor,
        direction = eventSignal.direction,
        merchant = merchant,
        account = account,
        occurredAt = occurredAt,
        confidence = confidence,
        evidenceDigest = digest,
    )
    return CardParseResult.Parsed(
        candidate = candidate,
        top = elements.minOf { it.bounds.top },
        left = elements.minOf { it.bounds.left },
    )
}

private fun groupByGeometry(elements: List<ObservedTextElement>): List<List<ObservedTextElement>> {
    if (elements.isEmpty()) return emptyList()
    val medianHeight = elements.map { max(1, it.bounds.bottom - it.bounds.top) }
        .sorted()
        .let { it[it.size / 2] }
    val semanticClusters = buildSemanticAnchorClusters(elements, medianHeight)
    if (semanticClusters.isEmpty()) return emptyList()
    return assignElementsToBoundedContainers(elements, semanticClusters, medianHeight)
}

private data class SemanticAnchor(
    val element: ObservedTextElement,
    val key: String,
) {
    val isEvent: Boolean
        get() = key.startsWith("event:")
}

private data class SemanticAnchorCluster(
    val anchors: MutableList<SemanticAnchor>,
) {
    val bounds: TextBounds
        get() = TextBounds(
            left = anchors.minOf { it.element.bounds.left },
            top = anchors.minOf { it.element.bounds.top },
            right = anchors.maxOf { it.element.bounds.right },
            bottom = anchors.maxOf { it.element.bounds.bottom },
        )

    val ownershipBounds: TextBounds
        get() {
            val eventAnchors = anchors.filter(SemanticAnchor::isEvent)
            val localAnchors = eventAnchors.ifEmpty { anchors }
            return TextBounds(
                left = localAnchors.minOf { it.element.bounds.left },
                top = localAnchors.minOf { it.element.bounds.top },
                right = localAnchors.maxOf { it.element.bounds.right },
                bottom = localAnchors.maxOf { it.element.bounds.bottom },
            )
        }
}

private fun buildSemanticAnchorClusters(
    elements: List<ObservedTextElement>,
    medianHeight: Int,
): List<SemanticAnchorCluster> {
    val anchors = elements.mapNotNull { element ->
        semanticAnchorKey(element)?.let { key -> SemanticAnchor(element, key) }
    }
    val clusters = mutableListOf<SemanticAnchorCluster>()
    anchors.forEach { anchor ->
        val cluster = clusters
            .filter { candidate -> canJoinSemanticCluster(anchor, candidate, medianHeight) }
            .minByOrNull { candidate -> squaredDistance(anchor.element.bounds, candidate.bounds) }
        if (cluster == null) {
            clusters += SemanticAnchorCluster(mutableListOf(anchor))
        } else {
            cluster.anchors += anchor
        }
    }
    return clusters.sortedWith(compareBy<SemanticAnchorCluster> { it.bounds.top }.thenBy { it.bounds.left })
}

private fun semanticAnchorKey(element: ObservedTextElement): String? {
    val text = element.text.trim()
    if (text in CARD_DETAIL_SIGNATURES) return "detail:$text"
    return EVENT_SIGNALS.keys.firstOrNull(text::contains)?.let { signal -> "event:$signal" }
}

private fun canJoinSemanticCluster(
    anchor: SemanticAnchor,
    cluster: SemanticAnchorCluster,
    medianHeight: Int,
): Boolean {
    val distinctBoundaryAnchor = cluster.anchors.any { existing ->
        !rectanglesOverlap(existing.element.bounds, anchor.element.bounds) &&
            (existing.key == anchor.key || (existing.isEvent && anchor.isEvent))
    }
    if (distinctBoundaryAnchor) return false
    return cluster.anchors.any { existing ->
        val first = existing.element.bounds
        val second = anchor.element.bounds
        horizontalOverlap(first, second) > 0 &&
            verticalGap(first, second) <= medianHeight * SEMANTIC_VERTICAL_HEIGHTS
    }
}

private fun assignElementsToBoundedContainers(
    elements: List<ObservedTextElement>,
    clusters: List<SemanticAnchorCluster>,
    medianHeight: Int,
): List<List<ObservedTextElement>> {
    // Raw elements receive card ownership before any label/value analysis can connect them.
    val assignments = clusters.associateWith { mutableListOf<ObservedTextElement>() }
    elements.forEach { element ->
        val anchoredCluster = clusters.firstOrNull { cluster ->
            cluster.anchors.any { anchor -> anchor.element === element }
        }
        val cluster = anchoredCluster ?: chooseBoundedOwningCluster(
            element.bounds,
            clusters,
            medianHeight,
        )
        if (cluster != null) assignments.getValue(cluster) += element
    }
    return clusters.mapNotNull { cluster ->
        assignments.getValue(cluster)
            .takeIf(List<ObservedTextElement>::isNotEmpty)
            ?.sortedWith(
                compareBy<ObservedTextElement> { it.bounds.top }.thenBy { it.bounds.left },
            )
    }
}

private fun chooseBoundedOwningCluster(
    bounds: TextBounds,
    clusters: List<SemanticAnchorCluster>,
    medianHeight: Int,
): SemanticAnchorCluster? {
    // Every membership check is direct against immutable anchor bounds; assigned text never expands a card.
    val verticallyLocal = clusters.filter { cluster ->
        verticalGap(bounds, cluster.ownershipBounds) <= medianHeight * CARD_VERTICAL_HEIGHTS
    }
    val sameColumn = verticallyLocal.filter { cluster ->
        horizontalOverlap(bounds, cluster.ownershipBounds) > 0
    }
    val candidates = sameColumn.ifEmpty {
        verticallyLocal.filter { cluster ->
            horizontalGap(bounds, cluster.ownershipBounds) <= medianHeight * CARD_HORIZONTAL_HEIGHTS
        }
    }
    if (candidates.isEmpty()) return null
    val centerY = bounds.top + (bounds.bottom - bounds.top) / 2
    val started = candidates.filter { cluster -> cluster.ownershipBounds.top <= centerY }
    if (started.isNotEmpty()) {
        val latestTop = started.maxOf { it.ownershipBounds.top }
        return started.filter { it.ownershipBounds.top == latestTop }
            .minBy { cluster -> squaredDistance(bounds, cluster.ownershipBounds) }
    }
    return candidates.minBy { cluster -> squaredDistance(bounds, cluster.ownershipBounds) }
}

private fun rectanglesOverlap(first: TextBounds, second: TextBounds): Boolean =
    horizontalOverlap(first, second) > 0 && verticalOverlap(first, second) > 0

private fun verticalOverlap(first: TextBounds, second: TextBounds): Int =
    minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)

private fun horizontalGap(first: TextBounds, second: TextBounds): Int = when {
    first.right < second.left -> second.left - first.right
    second.right < first.left -> first.left - second.right
    else -> 0
}

private fun verticalGap(first: TextBounds, second: TextBounds): Int = when {
    first.bottom < second.top -> second.top - first.bottom
    second.bottom < first.top -> first.top - second.bottom
    else -> 0
}

private fun squaredDistance(first: TextBounds, second: TextBounds): Long {
    val horizontal = horizontalGap(first, second).toLong()
    val vertical = verticalGap(first, second).toLong()
    return horizontal * horizontal + vertical * vertical
}

private fun selectAmount(elements: List<ObservedTextElement>): AmountSelection {
    val primaryAnalysis = analyzePrimaryAmounts(elements)
    if (primaryAnalysis.invalid) return AmountSelection.Ambiguous
    val amounts = primaryAnalysis.splitAmounts + elements.flatMap { element ->
        findCurrencyAmounts(element.text).mapNotNull { parsed ->
            val clause = element.text.clauseContaining(parsed.range)
            val hasActualLabel = ACTUAL_AMOUNT_LABELS.any(clause::contains)
            if (!hasActualLabel && EXCLUDED_AMOUNT_CONTEXT.any(clause::contains)) {
                return@mapNotNull null
            }
            val rank = when {
                hasActualLabel -> 0
                EVENT_SIGNALS.keys.any(clause::contains) -> 1
                else -> 2
            }
            LocatedAmount(parsed.amountMinor, element, rank)
        }
    }
    val bestRank = amounts.minOfOrNull(LocatedAmount::rank) ?: return AmountSelection.Missing
    val best = amounts.filter { it.rank == bestRank }
    return if (best.size == 1) {
        AmountSelection.Selected(best.single())
    } else {
        AmountSelection.Ambiguous
    }
}

private data class PrimaryAmountAnalysis(
    val splitAmounts: List<LocatedAmount>,
    val invalid: Boolean,
)

private data class PrimaryLabelValuePair(
    val label: ObservedTextElement,
    val value: ObservedTextElement,
)

private data class PrimaryPairCandidate(
    val label: ObservedTextElement,
    val value: ObservedTextElement,
    val geometryRank: Int,
    val distance: Long,
)

private fun analyzePrimaryAmounts(elements: List<ObservedTextElement>): PrimaryAmountAnalysis {
    val splitAmounts = mutableListOf<LocatedAmount>()
    val localPairs = findPrimaryLabelValuePairs(elements)
    elements.forEach { labelElement ->
        ACTUAL_AMOUNT_LABELS.forEach { label ->
            Regex(Regex.escape(label)).findAll(labelElement.text).forEach labelMatch@ { labelMatch ->
                val clause = labelElement.text.clauseContaining(labelMatch.range)
                val inlineTokenCount = CURRENCY_TOKEN.findAll(clause).count()
                if (inlineTokenCount > 0) {
                    if (findCurrencyAmounts(clause).size != inlineTokenCount) {
                        return PrimaryAmountAnalysis(emptyList(), invalid = true)
                    }
                    return@labelMatch
                }

                val valueElement = localPairs.singleOrNull { pair -> pair.label === labelElement }?.value
                    ?: return PrimaryAmountAnalysis(emptyList(), invalid = true)
                val tokenCount = CURRENCY_TOKEN.findAll(valueElement.text).count()
                val validAmounts = findCurrencyAmounts(valueElement.text)
                if (tokenCount != 1 || validAmounts.size != 1) {
                    return PrimaryAmountAnalysis(emptyList(), invalid = true)
                }
                splitAmounts += LocatedAmount(
                    amountMinor = validAmounts.single().amountMinor,
                    element = valueElement,
                    rank = 0,
                )
            }
        }
    }
    return PrimaryAmountAnalysis(splitAmounts, invalid = false)
}

private fun findPrimaryLabelValuePairs(
    elements: List<ObservedTextElement>,
): List<PrimaryLabelValuePair> {
    val splitLabels = elements.filter(::hasSplitPrimaryLabel)
    val usedValues = mutableSetOf<ObservedTextElement>()
    return splitLabels.mapNotNull { label ->
        val candidates = elements.mapNotNull { value ->
            if (value === label || value in usedValues || !looksLikeRawCurrencyValue(value.text)) {
                return@mapNotNull null
            }
            val geometryRank = primaryValueGeometryRank(label, value) ?: return@mapNotNull null
            PrimaryPairCandidate(
                label = label,
                value = value,
                geometryRank = geometryRank,
                distance = squaredDistance(label.bounds, value.bounds),
            )
        }
        val bestRank = candidates.minOfOrNull(PrimaryPairCandidate::geometryRank)
            ?: return@mapNotNull null
        val ranked = candidates.filter { it.geometryRank == bestRank }
        val bestDistance = ranked.minOf(PrimaryPairCandidate::distance)
        val nearest = ranked.filter { it.distance == bestDistance }.singleOrNull()
            ?: return@mapNotNull null
        usedValues += nearest.value
        PrimaryLabelValuePair(label, nearest.value)
    }
}

private fun hasSplitPrimaryLabel(element: ObservedTextElement): Boolean =
    ACTUAL_AMOUNT_LABELS.any { label ->
        Regex(Regex.escape(label)).findAll(element.text).any { labelMatch ->
            CURRENCY_TOKEN.findAll(element.text.clauseContaining(labelMatch.range)).none()
        }
    }

private fun primaryValueGeometryRank(
    label: ObservedTextElement,
    value: ObservedTextElement,
): Int? {
    val sameRow = verticalOverlap(label.bounds, value.bounds) > 0 &&
        value.bounds.left >= label.bounds.right &&
        horizontalGap(label.bounds, value.bounds) <= MAX_PRIMARY_LABEL_HORIZONTAL_GAP
    val nextLine = verticalGap(label.bounds, value.bounds) <= MAX_PRIMARY_LABEL_VERTICAL_GAP &&
        value.bounds.top >= label.bounds.bottom &&
        horizontalOverlap(label.bounds, value.bounds) > 0
    return when {
        sameRow -> 0
        nextLine -> 1
        else -> null
    }
}

private fun looksLikeRawCurrencyValue(text: String): Boolean {
    val trimmed = text.trim()
    var tokenStart = 0
    if (trimmed.getOrNull(tokenStart) in SIGN_MARKERS) tokenStart++
    while (trimmed.getOrNull(tokenStart)?.isWhitespace() == true) tokenStart++
    val token = CURRENCY_TOKEN.matchAt(trimmed, tokenStart) ?: return false
    val valueText = trimmed.substring(token.range.last + 1).trim()
    return valueText.isNotEmpty() && valueText.all { character ->
        character.isDigit() || character.isWhitespace() || character in RAW_AMOUNT_CHARACTERS
    }
}

private data class ParsedCurrencyAmount(val amountMinor: Long, val range: IntRange)

private fun findCurrencyAmounts(text: String): List<ParsedCurrencyAmount> =
    CURRENCY_AMOUNT.findAll(text).mapNotNull { match ->
        if (hasAdjacentSignBefore(text, match.range.first)) return@mapNotNull null
        val number = match.groupValues[1]
        val numberStart = match.groups[1]?.range?.first ?: return@mapNotNull null
        if (numberStart > 0 && text[numberStart - 1] in SIGN_MARKERS) return@mapNotNull null
        val next = text.getOrNull(match.range.last + 1)
        if (next != null && (next.isDigit() || next == ',' || next == '.' || next.isLetter())) {
            return@mapNotNull null
        }
        val amountMinor = try {
            BigDecimal(number.replace(",", ""))
                .movePointRight(2)
                .longValueExact()
                .takeIf { it > 0 }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        } ?: return@mapNotNull null
        ParsedCurrencyAmount(amountMinor, match.range)
    }.toList()

private fun hasAdjacentSignBefore(text: String, tokenStart: Int): Boolean {
    var index = tokenStart - 1
    while (index >= 0 && text[index].isWhitespace()) index--
    return index >= 0 && text[index] in SIGN_MARKERS
}

private fun String.clauseContaining(range: IntRange): String {
    var start = range.first
    while (start > 0 && !isClauseBoundary(start - 1)) start--
    var end = range.last
    while (end + 1 < length && !isClauseBoundary(end + 1)) end++
    return substring(start, end + 1)
}

private fun String.isClauseBoundary(index: Int): Boolean {
    val character = this[index]
    if (character !in CLAUSE_BOUNDARIES) return false
    return character != ',' || getOrNull(index - 1)?.isDigit() != true || getOrNull(index + 1)?.isDigit() != true
}

private fun extractMerchant(
    elements: List<ObservedTextElement>,
    amountElement: ObservedTextElement,
): String? {
    val explicit = elements.mapNotNull { element ->
        EXPLICIT_MERCHANT.matchEntire(element.text.trim())
            ?.groupValues
            ?.get(1)
            ?.normalizeMerchant()
    }.distinct()
    if (explicit.isNotEmpty()) return explicit.singleOrNull()

    val candidates = elements.mapNotNull { element ->
        val text = element.text.trim()
        text.normalizeMerchant()
            ?.takeIf {
                element.bounds.centerY <= amountElement.bounds.centerY &&
                    isPossibleStandaloneMerchant(text)
            }
            ?.let { merchant ->
                merchant to abs(element.bounds.centerY - amountElement.bounds.centerY)
            }
    }
    val nearestDistance = candidates.minOfOrNull { it.second } ?: return null
    return candidates.filter { it.second == nearestDistance }.map { it.first }.distinct().singleOrNull()
}

private fun String.normalizeMerchant(): String? {
    val normalized = trim().replace(WHITESPACE, " ")
    return normalized.takeIf {
        it.length in 2..64 && it.none(Char::isISOControl) && !CURRENCY_AMOUNT.containsMatchIn(it)
    }
}

private fun isPossibleStandaloneMerchant(text: String): Boolean {
    if (text.contains(':') || text.contains('：')) return false
    if (text in PAGE_META_LABELS) return false
    if (EVENT_SIGNALS.keys.any(text::contains)) return false
    if (ACTUAL_AMOUNT_LABELS.any(text::contains)) return false
    if (EXCLUDED_AMOUNT_CONTEXT.any(text::contains)) return false
    if (FULL_TIME.containsMatchIn(text) || MONTH_DAY_TIME.containsMatchIn(text) || CLOCK_TIME.containsMatchIn(text)) {
        return false
    }
    if (normalizeAccount(text) != null) return false
    if (text.startsWith("使用") && text.endsWith("支付")) return false
    return true
}

private fun extractAccount(elements: List<ObservedTextElement>): String? {
    val inlineAccounts = elements.mapNotNull { element ->
        val text = element.text.trim()
        val accountText = USE_ACCOUNT.matchEntire(text)?.groupValues?.get(1)
            ?: LABELED_ACCOUNT.matchEntire(text)?.groupValues?.get(1)
            ?: return@mapNotNull null
        normalizeAccount(accountText)
    }
    val accountLabels = elements.filter { it.text.trim() in ACCOUNT_FIELD_LABELS }
    val geometricAccounts = elements.mapNotNull { valueElement ->
        val account = normalizeAccount(valueElement.text) ?: return@mapNotNull null
        account.takeIf { accountLabels.any { label -> isAccountValueForLabel(label, valueElement) } }
    }
    return (inlineAccounts + geometricAccounts).distinct().singleOrNull()
}

private fun isAccountValueForLabel(
    label: ObservedTextElement,
    value: ObservedTextElement,
): Boolean {
    if (label === value) return false
    val verticalOverlap = minOf(label.bounds.bottom, value.bounds.bottom) -
        maxOf(label.bounds.top, value.bounds.top)
    val horizontalGap = value.bounds.left - label.bounds.right
    val sameRow = verticalOverlap > 0 && horizontalGap in 0..MAX_ACCOUNT_LABEL_HORIZONTAL_GAP
    val verticalGap = value.bounds.top - label.bounds.bottom
    val horizontalOverlap = minOf(label.bounds.right, value.bounds.right) -
        maxOf(label.bounds.left, value.bounds.left)
    val nextLine = verticalGap in 0..MAX_ACCOUNT_LABEL_VERTICAL_GAP && horizontalOverlap > 0
    return sameRow || nextLine
}

private fun normalizeAccount(value: String): String? {
    val compact = value.trim().replace(WHITESPACE, " ")
    EXACT_ACCOUNTS[compact]?.let { return it }
    val card = MASKED_BANK_CARD.matchEntire(compact) ?: return null
    val label = card.groupValues[1]
    val digits = card.groupValues.drop(2).firstOrNull(String::isNotEmpty) ?: return null
    return "$label($digits)"
}

private fun selectVisibleTime(
    elements: List<ObservedTextElement>,
    amountElement: ObservedTextElement,
    observedAt: Instant,
    zone: ZoneId,
): TimeSelection {
    val observedDate = observedAt.atZone(zone).toLocalDate()
    val candidates = elements.flatMap { element ->
        buildList {
            FULL_TIME.findAll(element.text).forEach { match ->
                if (isTimeOwnedByCard(match, element, elements, amountElement)) {
                    parseFullTime(match, zone)?.let { add(LocatedTime(it, 0)) }
                }
            }
            MONTH_DAY_TIME.findAll(element.text).forEach { match ->
                if (isTimeOwnedByCard(match, element, elements, amountElement)) {
                    parseMonthDayTime(match, observedAt, zone)?.let { add(LocatedTime(it, 1)) }
                }
            }
            CLOCK_TIME.findAll(element.text).forEach { match ->
                if (isTimeOwnedByCard(match, element, elements, amountElement)) {
                    parseClockTime(match, observedDate, zone)?.let { add(LocatedTime(it, 2)) }
                }
            }
        }
    }
    val bestRank = candidates.minOfOrNull(LocatedTime::rank) ?: return TimeSelection.Missing
    val best = candidates.filter { it.rank == bestRank }.map(LocatedTime::instant).distinct()
    return if (best.size == 1) TimeSelection.Selected(best.single()) else TimeSelection.Ambiguous
}

private fun isTimeOwnedByCard(
    match: MatchResult,
    timeElement: ObservedTextElement,
    cardElements: List<ObservedTextElement>,
    amountElement: ObservedTextElement,
): Boolean {
    val clause = timeElement.text.clauseContaining(match.range)
    if (TIME_FIELD_LABELS.any(clause::contains)) return true
    val followsTransactionAnchor = cardElements.any { anchor ->
        isTransactionRegionAnchor(anchor) &&
            timeElement.bounds.top >= anchor.bounds.top &&
            horizontalOverlap(timeElement.bounds, anchor.bounds) > 0
    }
    val followsAmount = timeElement.bounds.top >= amountElement.bounds.top &&
        horizontalOverlap(timeElement.bounds, amountElement.bounds) > 0
    return followsTransactionAnchor || followsAmount
}

private fun isTransactionRegionAnchor(element: ObservedTextElement): Boolean {
    val text = element.text.trim()
    return text in TRANSACTION_DETAIL_SIGNATURES || EVENT_SIGNALS.keys.any(text::contains)
}

private fun horizontalOverlap(first: TextBounds, second: TextBounds): Int =
    minOf(first.right, second.right) - maxOf(first.left, second.left)

private fun parseFullTime(match: MatchResult, zone: ZoneId): Instant? = try {
    LocalDateTime.of(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
        match.groupValues[4].toInt(),
        match.groupValues[5].toInt(),
    ).atZone(zone).toInstant()
} catch (_: DateTimeException) {
    null
}

private fun parseMonthDayTime(
    match: MatchResult,
    observedAt: Instant,
    zone: ZoneId,
): Instant? {
    val observedYear = observedAt.atZone(zone).year
    return (observedYear - 1..observedYear + 1).mapNotNull { year ->
        try {
            LocalDateTime.of(
                year,
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt(),
            ).atZone(zone).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }.minByOrNull { candidate -> abs(Duration.between(observedAt, candidate).seconds) }
}

private fun parseClockTime(match: MatchResult, date: LocalDate, zone: ZoneId): Instant? = try {
    date.atTime(LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()))
        .atZone(zone)
        .toInstant()
} catch (_: DateTimeException) {
    null
}

private fun RecognitionCandidate.redactedSummary(): String =
    "RecognitionCandidate(" +
        "platform=$platform, channel=$channel, eventKind=$eventKind, amountMinor=$amountMinor, " +
        "direction=$direction, merchant=<redacted>, account=<redacted>, occurredAt=$occurredAt, " +
        "confidence=$confidence, evidenceDigest=$evidenceDigest)"

private val EVENT_SIGNALS = linkedMapOf(
    "支付成功" to EventSignal(TransactionEventKind.PAYMENT, Direction.EXPENSE),
    "已支付" to EventSignal(TransactionEventKind.PAYMENT, Direction.EXPENSE),
    "收款到账" to EventSignal(TransactionEventKind.RECEIPT, Direction.INCOME),
    "退款到账" to EventSignal(TransactionEventKind.REFUND, Direction.INCOME),
    "退款成功" to EventSignal(TransactionEventKind.REFUND, Direction.INCOME),
    "转入" to EventSignal(TransactionEventKind.TRANSFER, Direction.INCOME),
    "转出" to EventSignal(TransactionEventKind.TRANSFER, Direction.EXPENSE),
    "收到红包" to EventSignal(TransactionEventKind.RED_PACKET, Direction.INCOME),
    "发出红包" to EventSignal(TransactionEventKind.RED_PACKET, Direction.EXPENSE),
)
private val DEFAULT_PAYMENT_EVENT = EventSignal(TransactionEventKind.PAYMENT, Direction.EXPENSE)
private val CURRENCY_AMOUNT = Regex(
    "(?:人民币|CNY|RMB|[¥￥])\\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?)",
    RegexOption.IGNORE_CASE,
)
private val CURRENCY_TOKEN = Regex("(?:人民币|CNY|RMB|[¥￥])", RegexOption.IGNORE_CASE)
private val ACTUAL_AMOUNT_LABELS = setOf("实付", "交易金额", "支付金额", "收款金额", "退款金额", "入账金额")
private val EXCLUDED_AMOUNT_CONTEXT = setOf("余额", "总资产", "优惠", "最高减", "原价", "折扣", "优惠券")
private val SIGN_MARKERS = setOf('+', '-', '−', '－', '﹣', '负')
private val RAW_AMOUNT_CHARACTERS = SIGN_MARKERS + setOf(',', '.')
private val CLAUSE_BOUNDARIES = setOf('，', ',', '。', '；', ';', '\n')
private val EXPLICIT_MERCHANT = Regex("^(?:商户|收款方)\\s*[：:]\\s*(.{2,64})$")
private val USE_ACCOUNT = Regex("^使用\\s*(.+?)\\s*支付$")
private val LABELED_ACCOUNT = Regex("^(?:付款方式|支付方式)\\s*[：:]?\\s*(.+)$")
private val MASKED_BANK_CARD = Regex(
    "^([\\p{L}]{2,12}银行(?:储蓄卡|信用卡)?|银行卡|储蓄卡|信用卡)\\s*" +
        "(?:[（(]\\s*(?:尾号\\s*)?(?:[*•·]{0,8}\\s*)?(\\d{4})\\s*[）)]|" +
        "(?:尾号\\s*|[*•·]{2,}\\s*)(\\d{4}))$",
)
private val EXACT_ACCOUNTS = mapOf(
    "零钱" to "零钱",
    "支付宝余额" to "支付宝余额",
    "花呗" to "花呗",
)
private val ACCOUNT_FIELD_LABELS = setOf("付款方式", "支付方式")
private val PAGE_META_LABELS = setOf(
    "微信支付",
    "支付宝",
    "账单详情",
    "账单明细",
    "交易详情",
    "帮助与服务",
)
private val TRANSACTION_DETAIL_SIGNATURES = setOf("账单详情", "账单明细", "交易详情")
private val CARD_DETAIL_SIGNATURES = setOf("微信支付", "支付宝") + TRANSACTION_DETAIL_SIGNATURES
private val TIME_FIELD_LABELS = setOf("交易时间", "支付时间", "转账时间", "收款时间", "退款时间", "创建时间")
private val FULL_TIME = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?!\\d)")
private val MONTH_DAY_TIME = Regex("(?<!\\d)(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?!\\d)")
private val CLOCK_TIME = Regex("(?<![\\d:])(\\d{2}):(\\d{2})(?![\\d:])")
private val WHITESPACE = Regex("\\s+")
private const val MAX_ACCOUNT_LABEL_HORIZONTAL_GAP = 240
private const val MAX_ACCOUNT_LABEL_VERTICAL_GAP = 56
private const val MAX_PRIMARY_LABEL_HORIZONTAL_GAP = 240
private const val MAX_PRIMARY_LABEL_VERTICAL_GAP = 80
private const val SEMANTIC_VERTICAL_HEIGHTS = 6
private const val CARD_VERTICAL_HEIGHTS = 12
private const val CARD_HORIZONTAL_HEIGHTS = 12
