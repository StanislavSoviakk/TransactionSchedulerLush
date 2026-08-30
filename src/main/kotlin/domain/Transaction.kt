package org.example.domain

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A single unsent sale from a till.
 *
 * Only [market], [shopSubdivision] and [localTimestamp] feed into scheduling.
 * The remaining fields are carried along for completeness/traceability but are
 * deliberately not consulted by [org.example.scheduler.SendScheduler].
 *
 * [localTimestamp] is the till's own wall-clock reading with no zone/offset -
 * see NOTES.md for why this is never converted straight into an [java.time.Instant].
 */
data class Transaction(
    val id: String,
    val market: Market,
    val shopId: String,
    val shopSubdivision: String?,
    val localTimestamp: LocalDateTime,
    val amount: BigDecimal,
    val currency: String,
    val paymentMethod: String,
    val paymentRef: String?,
    val fiscalSeq: String?
)