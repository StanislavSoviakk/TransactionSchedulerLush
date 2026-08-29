package org.example.holidays

import java.time.LocalDate

interface HolidayProvider {
    fun isPublicHoliday(
        date: LocalDate,
        countryCode: String,
        subdivision: String?
    ): Boolean
}