package org.example.holidays

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate

private val json = Json { ignoreUnknownKeys = true }

/**
 * Pure HTTP client for Nager.Date - fetches one country/year and hands back
 * plain [Holiday] data. Doesn't know or care about HolidayProvider, caching,
 * or the scheduler; that's someone else's job (see [InMemoryHolidayProvider]).
 */
class NagerDateClient(
    private val baseUrl: String = "https://date.nager.at/api/v3/PublicHolidays",
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {

    fun fetch(countryCode: String, year: Int): List<Holiday> {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/$year/$countryCode")).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        check(response.statusCode() == 200) {
            "Nager.Date returned ${response.statusCode()} for $countryCode/$year"
        }

        return json.decodeFromString<List<NagerHolidayDto>>(response.body()).map { it.toHoliday() }
    }
}

// only the fields we actually use - Nager.Date's response has more (localName, fixed, types...)
@Serializable
private data class NagerHolidayDto(
    val date: String,
    val countryCode: String,
    val global: Boolean,
    val counties: List<String>? = null
) {
    fun toHoliday() = Holiday(
        date = LocalDate.parse(date),
        countryCode = countryCode,
        global = global,
        counties = counties?.toSet() ?: emptySet()
    )
}
