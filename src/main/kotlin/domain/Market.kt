package org.example.domain

import java.time.ZoneId

enum class Market(
    val zoneId: ZoneId,
    val countryCode: String
) {
    GBR(
        zoneId = ZoneId.of("Europe/London"),
        countryCode = "GB"
    ),
    FRA(
        zoneId = ZoneId.of("Europe/Paris"),
        countryCode = "FR"
    ),
    DEU(
        zoneId = ZoneId.of("Europe/Berlin"),
        countryCode = "DE"
    ),
    USA(
        zoneId = ZoneId.of("America/New_York"),
        countryCode = "US"
    ),
    NZL(
        zoneId = ZoneId.of("Pacific/Auckland"),
        countryCode = "NZ"
    ),
    JPN(
        zoneId = ZoneId.of("Asia/Tokyo"),
        countryCode = "JP"
    )
}