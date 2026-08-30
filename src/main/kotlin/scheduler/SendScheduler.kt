package org.example.scheduler

import org.example.domain.SendWindow
import org.example.domain.Transaction
import org.example.holidays.HolidayProvider
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

private val WINDOW_START = LocalTime.of(1, 0)
private val WINDOW_END = LocalTime.of(2, 0)

// safety net - real holiday runs are a few days, not years. if we ever hit this,
// something upstream (holiday data) is broken, so fail loudly instead of hanging
private const val MAX_LOOKAHEAD_DAYS = 3650L

/**
 * Finds the earliest valid overnight window for an unsent transaction.
 *
 * The scheduler is deliberately deterministic:
 * - the current time is supplied by the caller;
 * - timezone rules are delegated to java.time / IANA TZDB;
 * - holiday data is supplied through HolidayProvider;
 * - no network or clock access happens here.
 */
class SendScheduler(
    private val holidayProvider: HolidayProvider
) {

    fun findNextSendWindow(
        transaction: Transaction,
        now: Instant
    ): SendWindow {
        val zoneId = transaction.market.zoneId
        val countryCode = transaction.market.countryCode
        val subdivision = transaction.shopSubdivision

        var candidateDate = maxOf(
            earliestDateFromTransaction(transaction),
            earliestDateFromNow(now, zoneId)
        )
        val giveUpAfter = candidateDate.plusDays(MAX_LOOKAHEAD_DAYS)

        while (true) {
            if (candidateDate.isAfter(giveUpAfter)) {
                throw IllegalStateException(
                    "no valid send window found for ${transaction.id} within $MAX_LOOKAHEAD_DAYS days " +
                            "of $candidateDate - likely a bad HolidayProvider"
                )
            }

            if (holidayProvider.isPublicHoliday(candidateDate, countryCode, subdivision)) {
                candidateDate = candidateDate.plusDays(1)
                continue
            }

            val window = tryBuildWindow(candidateDate, zoneId)
            if (window != null) {
                return window
            }

            // window didn't exist as a real local time today (see tryBuildWindow) - skip it
            candidateDate = candidateDate.plusDays(1)
        }
    }

    private fun earliestDateFromTransaction(
        transaction: Transaction
    ): LocalDate {
        val timestamp = transaction.localTimestamp

        return earliestWindowDate(
            date = timestamp.toLocalDate(),
            time = timestamp.toLocalTime()
        )
    }

    private fun earliestWindowDate(
        date: LocalDate,
        time: LocalTime
    ): LocalDate =
        if (time < WINDOW_END) {
            date
        } else {
            date.plusDays(1)
        }

    private fun earliestDateFromNow(
        now: Instant,
        zoneId: ZoneId
    ): LocalDate {
        val nowLocal = now.atZone(zoneId)

        return earliestWindowDate(
            date = nowLocal.toLocalDate(),
            time = nowLocal.toLocalTime()
        )
    }

    private fun tryBuildWindow(
        date: LocalDate,
        zoneId: ZoneId
    ): SendWindow? {
        // on a fall-back day, 01:00 happens twice. atZone() picks the earlier
        // occurrence by default, and we keep that: the window opens the moment the
        // clock first reads 01:00 and closes at the single, unambiguous 02:00, so it
        // stays open for 2 real hours that day instead of 1. Decided, not incidental -
        // see the pinned assumption test in SendSchedulerTest.
        val start = date
            .atTime(WINDOW_START)
            .atZone(zoneId)
            .toInstant()

        val end = date
            .atTime(WINDOW_END)
            .atZone(zoneId)
            .toInstant()

        // this happens when a spring-forward jump lands right on the window
        // (e.g. London 01:00 -> 02:00) and swallows it whole - not a bug, just skip the day
        if (start >= end) {
            return null
        }

        return SendWindow(
            start = start,
            end = end,
            zoneId = zoneId
        )
    }
}