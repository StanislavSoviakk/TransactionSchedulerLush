package org.example.holidays

import java.time.LocalDate

data class Holiday(
    val date: LocalDate,
    val countryCode: String,
    val global: Boolean,
    val counties: Set<String>
)
