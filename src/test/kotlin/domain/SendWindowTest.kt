package com.lush.scheduler.domain

import org.example.domain.SendWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SendWindowTest {

    @Test
    fun `creates window when start is before end`() {
        val start = Instant.parse("2026-03-28T00:00:00Z")
        val end = Instant.parse("2026-03-28T01:00:00Z")
        val zoneId = ZoneId.of("Europe/Berlin")

        val window = SendWindow(
            start = start,
            end = end,
            zoneId = zoneId
        )

        assertEquals(start, window.start)
        assertEquals(end, window.end)
        assertEquals(zoneId, window.zoneId)
    }

    @Test
    fun `rejects window when start is after end`() {
        val start = Instant.parse("2026-03-28T02:00:00Z")
        val end = Instant.parse("2026-03-28T01:00:00Z")
        val zoneId = ZoneId.of("Europe/Berlin")

        assertFailsWith<IllegalArgumentException> {
            SendWindow(
                start = start,
                end = end,
                zoneId = zoneId
            )
        }
    }

    @Test
    fun `rejects window when start equals end`() {
        val time = Instant.parse("2026-03-28T01:00:00Z")
        val zoneId = ZoneId.of("Europe/Berlin")

        assertFailsWith<IllegalArgumentException> {
            SendWindow(
                start = time,
                end = time,
                zoneId = zoneId
            )
        }
    }
}