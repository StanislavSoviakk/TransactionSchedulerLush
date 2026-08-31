package org.example.holidays

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InMemoryHolidayProviderTest {

    @Test
    fun `date with no holiday entry is not a holiday`() {
        val provider = InMemoryHolidayProvider(emptyList())

        assertFalse(provider.isPublicHoliday(LocalDate.parse("2026-01-01"), "GB", null))
    }

    @Test
    fun `global holiday applies regardless of subdivision`() {
        val provider = InMemoryHolidayProvider(
            listOf(Holiday(LocalDate.parse("2026-12-25"), "GB", global = true, counties = emptySet()))
        )

        assertTrue(provider.isPublicHoliday(LocalDate.parse("2026-12-25"), "GB", null))
        assertTrue(provider.isPublicHoliday(LocalDate.parse("2026-12-25"), "GB", "GB-ENG"))
    }

    @Test
    fun `regional holiday applies only to matching subdivision`() {
        val provider = InMemoryHolidayProvider(
            listOf(
                Holiday(
                    LocalDate.parse("2026-07-12"),
                    "GB",
                    global = false,
                    counties = setOf("GB-NIR")
                )
            )
        )

        assertTrue(provider.isPublicHoliday(LocalDate.parse("2026-07-12"), "GB", "GB-NIR"))
        assertFalse(provider.isPublicHoliday(LocalDate.parse("2026-07-12"), "GB", "GB-ENG"))
    }

    @Test
    fun `regional holiday does not apply when shop subdivision is unknown`() {
        val provider = InMemoryHolidayProvider(
            listOf(
                Holiday(
                    LocalDate.parse("2026-07-12"),
                    "GB",
                    global = false,
                    counties = setOf("GB-NIR")
                )
            )
        )

        assertFalse(provider.isPublicHoliday(LocalDate.parse("2026-07-12"), "GB", null))
    }

    @Test
    fun `holiday for one country does not leak into another country on the same date`() {
        val provider = InMemoryHolidayProvider(
            listOf(Holiday(LocalDate.parse("2026-01-01"), "GB", global = true, counties = emptySet()))
        )

        assertFalse(provider.isPublicHoliday(LocalDate.parse("2026-01-01"), "FR", null))
    }

    @Test
    fun `two regional holidays on the same date are both checked`() {
        // e.g. one for Northern Ireland, one for Scotland, same day - a shop in either should match its own
        val provider = InMemoryHolidayProvider(
            listOf(
                Holiday(LocalDate.parse("2026-07-12"), "GB", global = false, counties = setOf("GB-NIR")),
                Holiday(LocalDate.parse("2026-07-12"), "GB", global = false, counties = setOf("GB-SCT"))
            )
        )

        assertTrue(provider.isPublicHoliday(LocalDate.parse("2026-07-12"), "GB", "GB-NIR"))
        assertTrue(provider.isPublicHoliday(LocalDate.parse("2026-07-12"), "GB", "GB-SCT"))
        assertFalse(provider.isPublicHoliday(LocalDate.parse("2026-07-12"), "GB", "GB-ENG"))
    }
}
