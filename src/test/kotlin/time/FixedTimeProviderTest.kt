package org.example.time

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FixedTimeProviderTest {

    @Test
    fun `always returns the same instant it was built with`() {
        val instant = Instant.parse("2026-01-01T00:00:00Z")
        val provider = FixedTimeProvider(instant)

        assertEquals(instant, provider.now())
        assertEquals(instant, provider.now()) // still the same, doesn't drift like a real clock
    }
}
