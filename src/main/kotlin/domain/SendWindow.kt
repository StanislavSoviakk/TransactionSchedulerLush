package org.example.domain

import java.time.Instant
import java.time.ZoneId

data class SendWindow(
    val start: Instant,
    val end: Instant,
    val zoneId: ZoneId
) {
    init {
        require(start < end) {
            "Send window start must be before its end"
        }
    }
}