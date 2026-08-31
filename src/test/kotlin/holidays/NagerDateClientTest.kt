package org.example.holidays

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class NagerDateClientTest {

    // com.sun.net.httpserver is fine here - we only care about the response body,
    // not the headers like in the HttpTimeProvider tests
    private fun stubServer(body: String): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/PublicHolidays") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://localhost:${server.address.port}/PublicHolidays"
    }

    @Test
    fun `fetches and maps a national holiday`() {
        val (server, url) = stubServer(
            """[{"date":"2026-04-03","countryCode":"GB","global":true,"counties":null}]"""
        )

        try {
            val holidays = NagerDateClient(baseUrl = url).fetch("GB", 2026)

            assertEquals(
                listOf(Holiday(LocalDate.parse("2026-04-03"), "GB", global = true, counties = emptySet())),
                holidays
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fetches and maps a regional holiday's counties`() {
        val (server, url) = stubServer(
            """[{"date":"2026-01-02","countryCode":"GB","global":false,"counties":["GB-SCT"]}]"""
        )

        try {
            val holidays = NagerDateClient(baseUrl = url).fetch("GB", 2026)

            assertEquals(
                listOf(Holiday(LocalDate.parse("2026-01-02"), "GB", global = false, counties = setOf("GB-SCT"))),
                holidays
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unknown fields in the response don't break parsing`() {
        // real Nager.Date rows also carry localName, name, fixed, launchYear, types - we ignore all of that
        val (server, url) = stubServer(
            """[{"date":"2026-12-25","localName":"Christmas Day","name":"Christmas Day","countryCode":"GB","fixed":false,"global":true,"counties":null,"launchYear":null,"types":["Public"]}]"""
        )

        try {
            val holidays = NagerDateClient(baseUrl = url).fetch("GB", 2026)

            assertEquals(1, holidays.size)
            assertEquals(LocalDate.parse("2026-12-25"), holidays[0].date)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a non-200 response fails loudly instead of returning an empty holiday list`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/PublicHolidays") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()

        try {
            val url = "http://localhost:${server.address.port}/PublicHolidays"

            kotlin.test.assertFailsWith<IllegalStateException> {
                NagerDateClient(baseUrl = url).fetch("GB", 2026)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `malformed JSON fails loudly instead of returning garbage`() {
        val (server, url) = stubServer("""this is not json""")

        try {
            kotlin.test.assertFails {
                NagerDateClient(baseUrl = url).fetch("GB", 2026)
            }
        } finally {
            server.stop(0)
        }
    }
}
