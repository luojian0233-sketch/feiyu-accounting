# Payment Page Time Anchor Separation Design

**Date:** 2026-08-03
**Status:** Approved in conversation; pending written-spec review
**Scope:** Resume blocked Task 4 only. Do not implement Task 5 or later work in this change.

## Context

`PaymentPageParser` already separates nearby transaction cards and keeps label/value pairing inside bounded card containers. The remaining blocking defect is narrower: a shared page-level heading such as `账单详情` can still authorize an unlabeled full or month-day timestamp as if it belonged to the first transaction card.

A page with this order reproduces the defect:

1. shared page heading (`账单详情`),
2. unlabeled `2026-07-20 08:00` or `07-20 08:00`,
3. left and right transaction event anchors (`转出` and `转入`),
4. each card's own amount.

The left candidate incorrectly receives the page timestamp while the right candidate falls back to `observedAt`. The bad timestamp also changes the evidence time bucket and therefore the deduplication digest.

## Root Cause

The parser currently conflates two semantic roles:

- **Page/card grouping anchors:** platform titles and detail headings such as `微信支付`, `支付宝`, `账单详情`, `账单明细`, and `交易详情`.
- **Transaction-local authorization anchors:** event signals such as `支付成功`, `转出`, `转入`, `收款到账`, and refund/red-packet signals.

Grouping headings are useful for assigning text to a bounded card container, but they do not prove that a nearby unlabeled timestamp belongs to a transaction. `isTransactionRegionAnchor()` presently accepts both groups, allowing a page heading to authorize the timestamp.

## Considered Approaches

### A. Separate anchor roles at the time-authorization boundary — selected

Keep existing card-container grouping unchanged. For timestamp ownership, treat only transaction event signals as transaction-local anchors. Page/platform/detail headings never authorize an unlabeled timestamp.

This is the smallest change that fixes the source of the defect. It preserves the card geometry work and makes the semantic rule explicit.

### B. Introduce a typed `CardRegion` model

Carry page, card, event, field-label, and value roles through every parser stage. This would be more general, but it would reopen card partitioning, merchant extraction, account extraction, and amount pairing after those paths have already passed their regression suites.

### C. Add another distance or ordering threshold

Reject timestamps above or beyond a fixed distance from an event or amount. This is brittle across screen density, accessibility bounds, and OCR line spacing, and it does not address the semantic role confusion.

## Selected Design

### Anchor roles

The parser will preserve two distinct predicates:

- `isCardGroupingAnchor(element)`: existing platform/detail/event signatures used by container construction.
- `isTransactionTimeAnchor(element)`: event signals only. It must not accept page/platform/detail headings.

The existing container builder may continue using `CARD_DETAIL_SIGNATURES` and event signals. Only time authorization changes.

### Timestamp ownership

For each timestamp match within an already selected card container:

1. A clause with an exact recognized time-field label (`交易时间`, `支付时间`, `转账时间`, `收款时间`, `退款时间`, or `创建时间`) is accepted.
2. An unlabeled timestamp is accepted when it follows a transaction event anchor in the same card and horizontally overlaps that event anchor.
3. An unlabeled timestamp is accepted when it follows the selected amount in the same card and horizontally overlaps the amount.
4. Otherwise it is rejected as page/UI metadata and the candidate falls back to `snapshot.observedAt`.

These rules apply consistently to full timestamps, month-day timestamps, and bare clocks. Existing ambiguity handling remains unchanged: multiple distinct highest-ranked owned timestamps reject the card as ambiguous.

### Data and privacy behavior

No domain model, database schema, backup format, or digest format changes. The candidate continues storing only the normalized `occurredAt`. Raw page text, timestamp source text, page headings, and card elements must not enter logs, exceptions, receipts, or digest input.

### Compatibility

The following behaviors must remain unchanged:

- visible full, month-day, and labeled local-clock transaction times parse in `BOOKKEEPING_ZONE`;
- page/status-bar clocks and timestamps from another card fall back to `observedAt`;
- card partitioning, amount selection, event/direction mapping, merchant extraction, account extraction, and generic screenshot fallback remain untouched;
- WeChat and Alipay exact package restrictions remain untouched.

## Error Handling

An unowned timestamp is not an extraction error. It is ignored and the parser uses `observedAt`. A timestamp remains an ambiguity only when more than one distinct timestamp is legitimately owned by the same selected card at the same priority rank.

## Test Design

TDD must add the failing combination before production changes:

1. Shared `账单详情`, then an unlabeled full timestamp, then side-by-side `转出`/`转入` cards. Both candidates must use `observedAt`.
2. The same layout with an unlabeled month-day timestamp. Both candidates must use `observedAt`.
3. Preserve the existing three valid transaction-time cases:
   - full timestamp inside a transaction card,
   - month-day timestamp inside a transaction card,
   - labeled local clock inside a transaction card.
4. Preserve existing status-bar, remote-card, card-partition, amount, account, merchant, event, and privacy regressions.

Verification must include:

- targeted `PaymentPageParserTest`;
- recognition and notification parser regressions;
- complete `testDebugUnitTest` with one Gradle worker from the `R:\` path;
- a source scan confirming no raw page text logging or exception payloads were introduced;
- an independent scoped review of the fix commit.

## Implementation Boundary

Expected production change: the time-anchor predicate and its call site in `PaymentPageParser.kt`. Expected test change: `PaymentPageParserTest.kt`. Refactoring card ownership or changing thresholds is outside this repair unless the new failing test proves the selected design cannot be implemented without it.
