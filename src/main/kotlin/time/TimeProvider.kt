package org.example.time

import java.time.Instant

/**
 * The scheduler's only source of "now". Everything downstream depends on this
 * interface, never on Instant.now() directly - that's what makes the scheduler
 * deterministic and testable without touching a real clock.
 */
interface TimeProvider {
    fun now(): Instant
}
