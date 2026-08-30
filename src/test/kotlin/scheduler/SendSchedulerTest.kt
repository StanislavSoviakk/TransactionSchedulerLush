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
import kotlin.test.assertFailsWith

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
        localTimestamp = localTimestamp,
        amount = java.math.BigDecimal("10.00"),
        currency = "EUR",
        paymentMethod = "CARD",
        paymentRef = "ref-1",
        fiscalSeq = null
    )

    private fun schedulerWithHolidays(vararg holidays: Holiday) =
        SendScheduler(InMemoryHolidayProvider(holidays.toList()))

    // --- Normal cases: relation between recorded local time and the 01:00-02:00 window ---

    @Test
    fun `transaction recorded before the window uses the same day's window`() {
        val scheduler = schedulerWithHolidays()
        // 00:30 is before the window that same day, no roll-over needed
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
        // 02:00 counts as already closed - window is [01:00, 02:00)
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-11T02:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // 2026-06-12T01:00 CEST
    }


    // --- Interaction between the recorded (unreliable) local time and the trusted current time ---

    @Test
    fun `already closed window relative to trusted now is never returned`() {
        val scheduler = schedulerWithHolidays()
        // recorded early, but real time says today's window already passed
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-11T10:00:00Z"))

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // next day, not 2026-06-11 01:00
    }

    @Test
    fun `trusted now exactly at window end means today's window is already closed`() {
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))
        // now is 02:00 CEST on the dot - window is [01:00, 02:00), so this is already closed
        val now = Instant.parse("2026-06-11T00:00:00Z")

        val window = scheduler.findNextSendWindow(txn, now = now)

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // rolls to tomorrow
    }

    @Test
    fun `current time inside a valid window is returned instead of skipping ahead`() {
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))
        // now is 01:30 CEST, inside today's window
        val now = Instant.parse("2026-06-10T23:30:00Z")

        val window = scheduler.findNextSendWindow(txn, now = now)

        assertEquals(Instant.parse("2026-06-10T23:00:00Z"), window.start)
        assertEquals(Instant.parse("2026-06-11T00:00:00Z"), window.end)
    }

    @Test
    fun `till clock recorded far ahead of trusted now is not corrected`() {
        // till says the future, we don't second-guess it
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

        // shop is in Berlin, holiday is Bavaria-only -> not skipped
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
        // also covers two holidays in a row across the year boundary
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
        // "now" looks like it's inside the window, but that day is a holiday
        val scheduler = schedulerWithHolidays(
            Holiday(LocalDate.parse("2026-06-11"), "DE", true, emptySet())
        )
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))
        val now = Instant.parse("2026-06-10T23:30:00Z")

        val window = scheduler.findNextSendWindow(txn, now = now)

        assertEquals(Instant.parse("2026-06-11T23:00:00Z"), window.start) // rolled to 2026-06-12
    }

    @Test
    fun `regional holiday never applies when the shop's subdivision is unknown`() {
        // no subdivision on file -> regional holidays can't match, only national ones do
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
        // Berlin jumps 02:00 -> 03:00 on this date, right at the window's end
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

    @Test
    fun `southern-hemisphere fall-back transition leaves the window unaffected`() {
        // Auckland's fall-back happens at 03:00, after the window, so this day is fine
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.NZL, LocalDateTime.parse("2026-04-04T10:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-04-04T00:00:00Z"))

        assertEquals(Instant.parse("2026-04-04T12:00:00Z"), window.start) // 01:00 NZDT (+13:00)
        assertEquals(Instant.parse("2026-04-04T13:00:00Z"), window.end)   // 02:00 NZDT (+13:00)
    }

    @Test
    fun `a day whose entire window is swallowed by a spring-forward gap is skipped like a holiday`() {
        // London's spring-forward is at 01:00 - eats the window whole, rolls to next day
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.GBR, LocalDateTime.parse("2026-03-28T10:00:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-03-28T00:00:00Z"))

        assertEquals(Instant.parse("2026-03-30T00:00:00Z"), window.start) // 2026-03-29 skipped entirely
        assertEquals(Instant.parse("2026-03-30T01:00:00Z"), window.end)
    }

    @Test
    fun `a recorded till time that falls in a DST spring-forward gap does not blow up`() {
        // real row from the fixture - recorded time sits in the gap, but we never
        // resolve it to an Instant, so it just needs to roll past this day
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.GBR, LocalDateTime.parse("2026-03-29T01:30:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-03-28T00:00:00Z"))

        assertEquals(Instant.parse("2026-03-30T00:00:00Z"), window.start) // 2026-03-30T01:00 BST (+01:00)
        assertEquals(Instant.parse("2026-03-30T01:00:00Z"), window.end)
    }

    @Test
    fun `assumption check - java time resolves an ambiguous autumn 01-00 to the earlier offset`() {
        // this is the raw library behaviour our window building relies on, pinned on its own
        // so it's obvious what changes if a future JDK ever resolved overlaps differently.
        val london = java.time.ZoneId.of("Europe/London")
        val ambiguousStart = LocalDateTime.of(2026, 10, 25, 1, 0)

        val resolved = ambiguousStart.atZone(london)

        assertEquals(java.time.ZoneOffset.ofHours(1), resolved.offset) // BST - the first 01:00, not the second
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), resolved.toInstant())
    }

    @Test
    fun `a recorded till time that falls in a DST fall-back overlap does not blow up`() {
        // real row from the fixture (GBR-0442-000006). this local time happens twice
        // that day - we don't try to guess which one, just compare plain LocalTime
        val scheduler = schedulerWithHolidays()
        val txn = transaction(Market.GBR, LocalDateTime.parse("2026-10-25T01:30:00"))

        val window = scheduler.findNextSendWindow(txn, now = Instant.parse("2026-10-24T00:00:00Z"))

        // decided policy (see test above): window opens at the first 01:00 and closes at the
        // one and only 02:00, so it's genuinely open for 2 real hours this day, not 1
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), window.start) // 2026-10-25T01:00 BST (+01:00)
        assertEquals(Instant.parse("2026-10-25T02:00:00Z"), window.end)   // 2026-10-25T02:00 GMT (+00:00)
    }

    // --- Defensive bound ---

    @Test
    fun `a holiday provider that never stops saying yes fails loudly instead of hanging`() {
        val alwaysHoliday = object : org.example.holidays.HolidayProvider {
            override fun isPublicHoliday(date: LocalDate, countryCode: String, subdivision: String?) = true
        }
        val scheduler = SendScheduler(alwaysHoliday)
        val txn = transaction(Market.DEU, LocalDateTime.parse("2026-06-10T18:00:00"))

        assertFailsWith<IllegalStateException> {
            scheduler.findNextSendWindow(txn, now = Instant.parse("2026-06-10T00:00:00Z"))
        }
    }

}
