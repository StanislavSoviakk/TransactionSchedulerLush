package org.example

import org.example.domain.Transaction
import org.example.holidays.HolidayProvider
import org.example.holidays.InMemoryHolidayProvider
import org.example.holidays.NagerDateClient
import org.example.input.TransactionCsvParser
import org.example.scheduler.SendScheduler
import org.example.time.HttpTimeProvider
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Single entry point: reads a transactions CSV, works out the next valid send
 * window for every row, and prints one line per transaction.
 *
 * Usage: run with no args to use data/transactions.csv, or pass a path as arg 0.
 * See NOTES.md for the reasoning behind the fallbacks below.
 */
fun main(args: Array<String>) {
    val csvPath = args.getOrElse(0) { "data/transactions.csv" }
    val csvFile = File(csvPath)

    if (!csvFile.exists()) {
        System.err.println("no such file: $csvPath")
        return
    }

    val parseResult = TransactionCsvParser.parse(csvFile.readLines())
    println("parsed ${parseResult.transactions.size} transaction(s), ${parseResult.errors.size} error(s)")
    parseResult.errors.forEach { println("  line ${it.lineNumber}: ${it.reason}") }

    if (parseResult.transactions.isEmpty()) {
        return
    }

    val now = trustedNow()
    println("trusted now: $now")

    val holidayProvider = buildHolidayProvider(parseResult.transactions, now)
    val scheduler = SendScheduler(holidayProvider)

    println()
    parseResult.transactions.forEach { txn ->
        try {
            val window = scheduler.findNextSendWindow(txn, now)
            val localStart = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").format(window.start.atZone(window.zoneId))
            println("${txn.id}  ->  $localStart ${window.zoneId} (${window.start} - ${window.end})")
        } catch (e: IllegalStateException) {
            println("${txn.id}  ->  FAILED: ${e.message}")
        }
    }
}

// falls back to the system clock if the time server is unreachable - see NOTES.md
private fun trustedNow(): Instant =
    try {
        HttpTimeProvider().now()
    } catch (e: Exception) {
        System.err.println("couldn't reach the time server (${e.javaClass.simpleName}: ${e.message}), falling back to the local clock")
        Instant.now()
    }

// fetches holidays for every year that could plausibly matter, not just "now" -
// see NOTES.md (a till's own timestamp can be in any year, wrong clock and all)
private fun buildHolidayProvider(transactions: List<Transaction>, now: Instant): HolidayProvider {
    val client = NagerDateClient()
    val countryCodes = transactions.map { it.market.countryCode }.toSet()

    val nowYear = now.atZone(ZoneOffset.UTC).year
    val recordedYears = transactions.map { it.localTimestamp.year }
    // +1 on the top end covers a candidate window rolling over New Year's Eve
    val minYear = (recordedYears + nowYear).min()
    val maxYear = (recordedYears + nowYear).max() + 1

    val holidays = countryCodes.flatMap { country ->
        (minYear..maxYear).flatMap { year ->
            try {
                client.fetch(country, year)
            } catch (e: Exception) {
                System.err.println("couldn't fetch holidays for $country/$year (${e.javaClass.simpleName}: ${e.message}), treating as none known")
                emptyList()
            }
        }
    }

    return InMemoryHolidayProvider(holidays)
}