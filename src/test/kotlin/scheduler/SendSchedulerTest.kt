package com.lush.scheduler.scheduler

import org.example.domain.Market
import org.example.domain.Transaction
import org.example.holidays.Holiday
import org.example.holidays.InMemoryHolidayProvider
import org.example.scheduler.SendScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SendSchedulerTest {

    private fun transaction(
        market: Market,
        localTimestamp: LocalDateTime,
        shopSubdivision: String? = null
    ) = Transaction(
        id = "txn-1",
        market = market,
        shopId = "shop-1",
        shopSubdivision = shopSubdivision,
        localTimestamp = localTimestamp
    )

    private fun schedulerWithHolidays(vararg holidays: Holiday) =
        SendScheduler(InMemoryHolidayProvider(holidays.toList()))

    // --- Normal cases: relation between recorded local time and the 01:00-02:00 window ---

    @Test
    fun `transaction recorded before the window uses the same day's window`() {
        val scheduler = schedulerWithHolidays()
        // 00:30 local is before that same day's 01:00-02:00 window, so no roll-over is needed.
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-11T00:30:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-10T23:00:00Z"), window.start) // 2026-06-11T01:00 CEST
        assertEquals(Instant.parse("2026-06-11T00:00:00Z"), window.end)   // 2026-06-11T02:00 CEST
    }

    @Test
    fun `transaction recorded exactly at window start is inside that day's window`() {
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-11T01:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-10T23:00:00Z"), window.start)
    }

    @Test
    fun `transaction recorded exactly at window end rolls over to the next day`() {
        // 02:00 is treated as already closed (half-open interval [01:00, 02:00)).
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-11T02:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // 2026-06-12T01:00 CEST
    }


    // --- Interaction between the recorded (unreliable) local time and the trusted current time ---

    @Test
    fun `already closed window relative to trusted now is never returned`() {
        val scheduler = schedulerWithHolidays()
        // Recorded early, but the trusted clock says the window for that day is already over.
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-11T10:00:00Z"))

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // next day, not 2026-06-11 01:00
    }

    @Test
    fun `current time inside a valid window is returned instead of skipping ahead`() {
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))
        // now is 01:30 CEST on 2026-06-11, i.e. inside that day's send window.
        val now = Instant.parse("2026-06-10T23:30:00Z")

        val window = scheduler.findNextSendWindow(txn, now = now)

        assertEquals(Instant.parse("2026-06-10T23:00:00Z"), window.start)
        assertEquals(Instant.parse("2026-06-11T00:00:00Z"), window.end)
    }

    @Test
    fun `till clock recorded far ahead of trusted now is not corrected`() {
        // The till clock is unreliable - if it recorded a future-looking timestamp,
        // the scheduler must still honor it as a lower bound rather than "fixing" it.
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-20T10:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-20T23:00:00Z"), window.start) // 2026-06-21T01:00 CEST
    }

    // --- Public holidays ---

    @Test
    fun `national holiday is skipped`() {
        val scheduler = schedulerWithHolidays(
            Holiday(date = LocalDate.parse("2026-06-11"), countryCode = "DE", global = true, counties = emptySet())
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // 2026-06-12, not the 11th
    }

    @Test
    fun `regional holiday only skips for a matching shop subdivision`() {
        val holiday = Holiday(
            date = LocalDate.parse("2026-06-11"),
            countryCode = "DE",
            global = false,
            counties = setOf("BY")
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"), shopSubdivision = "BE")

        val window = schedulerWithHolidays(holiday)
            .findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        // Shop is in Berlin (BE), holiday only applies to Bavaria (BY) -> not skipped.
        assertEquals(Instant.parse("2026-06-10T23:00:00Z"), window.start)
    }

    @Test
    fun `regional holiday skips a matching shop subdivision`() {
        val holiday = Holiday(
            date = LocalDate.parse("2026-06-11"),
            countryCode = "DE",
            global = false,
            counties = setOf("BY")
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"), shopSubdivision = "BY")

        val window = schedulerWithHolidays(holiday)
            .findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start)
    }

    @Test
    fun `holiday run spanning the year boundary skips every day in the run`() {
        // Also proves consecutive-holiday skipping: two holidays in a row,
        // straddling Dec 31 / Jan 1, must both be skipped in one pass.
        val scheduler = schedulerWithHolidays(
            Holiday(LocalDate.parse("2026-12-31"), "DE", true, emptySet()),
            Holiday(LocalDate.parse("2027-01-01"), "DE", true, emptySet())
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-12-30T18:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-12-30T00:00:00Z"))

        assertEquals(Instant.parse("2027-01-02T00:00:00Z"), window.start) // 2027-01-02T01:00 CET (+01:00)
    }

    @Test
    fun `holiday on the day whose window is currently open is still skipped`() {
        // The scheduler must re-check the holiday calendar even when `now` already
        // falls inside the candidate window - a shortcut that returns "now's window"
        // without re-validating it would silently violate "shops don't send on holidays".
        val scheduler = schedulerWithHolidays(
            Holiday(LocalDate.parse("2026-06-11"), "DE", true, emptySet())
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))
        // now is 01:30 CEST on 2026-06-11 - nominally inside that day's window, but that day is a holiday.
        val now = Instant.parse("2026-06-10T23:30:00Z")

        val window = scheduler.findNextSendWindow(txn, now = now)

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // rolled to 2026-06-12
    }

    @Test
    fun `regional holiday never applies when the shop's subdivision is unknown`() {
        // Dirty CSV rows can have a blank shop_subdivision. A regional (non-global)
        // holiday must not be treated as "applies everywhere" in that case - only
        // national holidays protect a shop with no known subdivision.
        val holiday = Holiday(
            date = LocalDate.parse("2026-06-11"),
            countryCode = "DE",
            global = false,
            counties = setOf("BY")
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"), shopSubdivision = null)

        val window = schedulerWithHolidays(holiday)
            .findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-10T23:00:00Z"), window.start) // not skipped
    }


    // --- DST ---

    @Test
    fun `spring-forward transition produces correct absolute instants`() {
        // Europe/Berlin jumps 02:00 -> 03:00 on 2026-03-29; the window's nominal
        // 01:00-02:00 end lands exactly in the gap. java.time must resolve this,
        // not a hand-rolled offset calculation.
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-03-28T18:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-03-28T00:00:00Z"))

        assertEquals(Instant.parse("2026-03-29T00:00:00Z"), window.start) // 01:00 CET (+01:00)
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), window.end)   // resolved 03:00 CEST (+02:00)
    }

    @Test
    fun `market without DST keeps a stable offset across the year`() {
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.JPN, LocalDateTime.parse("2026-06-10T18:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-10T16:00:00Z"), window.start) // 01:00 JST, always +09:00
        assertEquals(Instant.parse("2026-06-10T17:00:00Z"), window.end)
    }
}
