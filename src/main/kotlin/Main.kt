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
import kotlin.system.exitProcess

/**
 * Single entry point: reads a transactions CSV, works out the next valid send
 * window for every row, and prints one line per transaction.
 *
 * Usage: run with no args to use data/transactions.csv, or pass a path as arg 0.
 * Trusted time / holiday data are required, not best-effort - see NOTES.md.
 */
fun main(args: Array<String>) {
    val csvPath = args.getOrElse(0) { "data/transactions.csv" }
    val csvFile = File(csvPath)

    if (!csvFile.isFile) {
        System.err.println("no such file: $csvPath")
        exitProcess(1)
    }

    val parseResult = TransactionCsvParser.parse(csvFile.readLines())
    println("parsed ${parseResult.transactions.size} transaction(s), ${parseResult.errors.size} error(s)")
    parseResult.errors.forEach { println("  line ${it.lineNumber}: ${it.reason}") }

    if (parseResult.transactions.isEmpty()) {
        return
    }

    val now = try {
        trustedNow()
    } catch (e: Exception) {
        System.err.println("could not obtain a trusted current time (${e.javaClass.simpleName}: ${e.message}), aborting")
        exitProcess(1)
    }
    println("trusted now: $now")

    val holidayProvider = try {
        buildHolidayProvider(parseResult.transactions, now)
    } catch (e: Exception) {
        System.err.println("could not load public holiday data (${e.javaClass.simpleName}: ${e.message}), aborting")
        exitProcess(1)
    }
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

// no fallback here on purpose - if we can't get a trusted time, we shouldn't
// pretend the local clock is good enough. See NOTES.md.
private fun trustedNow(): Instant = HttpTimeProvider().now()

// fetches holidays for every year that could plausibly matter, not just "now" -
// see NOTES.md (a till's own timestamp can be in any year, wrong clock and all).
// no fallback to "assume no holidays" if a fetch fails - an unknown holiday
// calendar is not the same thing as an empty one.
private fun buildHolidayProvider(transactions: List<Transaction>, now: Instant): HolidayProvider {
    val client = NagerDateClient()
    val countryCodes = transactions.map { it.market.countryCode }.toSet()

    val nowYear = now.atZone(ZoneOffset.UTC).year
    val recordedYears = transactions.map { it.localTimestamp.year }
    // +1 on the top end covers a candidate window rolling over New Year's Eve
    val minYear = (recordedYears + nowYear).min()
    val maxYear = (recordedYears + nowYear).max() + 1

    val holidays = countryCodes.flatMap { country ->
        (minYear..maxYear).flatMap { year -> client.fetch(country, year) }
    }

    return InMemoryHolidayProvider(holidays)
}