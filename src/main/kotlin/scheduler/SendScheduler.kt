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

        var candidateDate = maxOf(
            earliestDateFromTransaction(transaction),
            earliestDateFromNow(now, zoneId)
        )

        candidateDate = skipHolidays(
            candidateDate = candidateDate,
            countryCode = transaction.market.countryCode,
            subdivision = transaction.shopSubdivision
        )

        return buildWindow(candidateDate, zoneId)
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

    private fun skipHolidays(
        candidateDate: LocalDate,
        countryCode: String,
        subdivision: String?
    ): LocalDate {
        var date = candidateDate

        while (
            holidayProvider.isPublicHoliday(
                date = date,
                countryCode = countryCode,
                subdivision = subdivision
            )
        ) {
            date = date.plusDays(1)
        }

        return date
    }


    private fun buildWindow(
        date: LocalDate,
        zoneId: ZoneId
    ): SendWindow {
        val start = date
            .atTime(WINDOW_START)
            .atZone(zoneId)
            .toInstant()

        val end = date
            .atTime(WINDOW_END)
            .atZone(zoneId)
            .toInstant()

        check(start < end) {
            "Send window start must be before its end"
        }

        return SendWindow(
            start = start,
            end = end,
            zoneId = zoneId
        )
    }
}