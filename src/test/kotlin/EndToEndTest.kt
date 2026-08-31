package org.example

import org.example.holidays.InMemoryHolidayProvider
import org.example.input.TransactionCsvParser
import org.example.scheduler.SendScheduler
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real 240-row fixture through parser -> scheduler, no network. Not checking real
 * holiday-skip behaviour here (that'd mean hardcoding six countries' calendars) - just that
 * nothing blows up on real, messy input.
 */
class EndToEndTest {

    @Test
    fun `the whole fixture schedules without errors, offline`() {
        val csvFile = File("data/transactions.csv")
        val parseResult = TransactionCsvParser.parse(csvFile.readLines())

        assertEquals(240, parseResult.transactions.size)
        assertEquals(0, parseResult.errors.size)

        val scheduler = SendScheduler(InMemoryHolidayProvider(emptyList()))
        val now = Instant.parse("2026-01-01T00:00:00Z")

        parseResult.transactions.forEach { txn ->
            val window = scheduler.findNextSendWindow(txn, now)

            assertTrue(
                !window.start.isBefore(now),
                "${txn.id}: window ${window.start} starts before trusted now $now"
            )
            assertTrue(
                window.start.isBefore(window.end),
                "${txn.id}: window start ${window.start} is not before end ${window.end}"
            )
        }
    }
}
