package org.example.time

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Gets "now" from the Date header of a real HTTP response instead of the
 * local machine clock. Doesn't care what the response body is - any endpoint
 * that returns a Date header works (https://httpbin.org/get is just an example).
 *
 * This is the "trusted" clock the brief asks for: unlike the till's own clock,
 * a server's Date header is at least someone else's problem to keep accurate.
 */
class HttpTimeProvider(
    private val url: String = "https://httpbin.org/get",
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) : TimeProvider {

    override fun now(): Instant {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())

        check(response.statusCode() in 200..299) {
            "time endpoint $url returned HTTP ${response.statusCode()}"
        }

        val dateHeader = response.headers().firstValue("Date").orElseThrow {
            IllegalStateException("HTTP response from $url had no Date header")
        }

        return parseHttpDate(dateHeader)
    }
}

// split out so a test can pin the parsing without needing a real HTTP response
internal fun parseHttpDate(dateHeader: String): Instant =
    Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(dateHeader))
