package org.example.time

import java.time.Instant

/** Always returns the same instant. For tests - no real clock, no flakiness. */
class FixedTimeProvider(private val instant: Instant) : TimeProvider {
    override fun now(): Instant = instant
}
