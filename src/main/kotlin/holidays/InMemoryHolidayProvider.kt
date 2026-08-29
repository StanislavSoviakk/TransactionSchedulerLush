package org.example.holidays

import java.time.LocalDate

/**
 * Holds a pre-fetched holiday cache for one or more countries and answers
 * holiday queries purely in-memory. Populated once at startup (e.g. one
 * Nager.Date call per country), never fetches on a per-transaction basis.
 */
class InMemoryHolidayProvider(
    holidays: List<Holiday>
) : HolidayProvider {

    // Index by (country, date) for O(1) lookup instead of scanning the whole list per transaction.
    private val holidaysByCountryAndDate: Map<Pair<String, LocalDate>, List<Holiday>> =
        holidays.groupBy { it.countryCode to it.date }

    override fun isPublicHoliday(
        date: LocalDate,
        countryCode: String,
        subdivision: String?
    ): Boolean {
        val holidaysOnDate = holidaysByCountryAndDate[countryCode to date] ?: return false

        return holidaysOnDate.any { holiday ->
            holiday.global ||
                    (subdivision != null && subdivision in holiday.counties)
        }
    }
}