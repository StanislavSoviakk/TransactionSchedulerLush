package org.example.domain

import java.time.ZoneId
import kotlin.time.Instant

data class SendWindow(
    val start: Instant,
    val end: Instant,
    val zoneId: ZoneId
)