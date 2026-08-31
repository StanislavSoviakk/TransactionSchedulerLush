package org.example.time

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpTimeProviderTest {

    // com.sun.net.httpserver always injects its own Date header, so a raw socket
    // is the only way to control exactly what comes back in the response.
    private fun serverReturning(dateHeaderLine: String?, statusLine: String = "HTTP/1.1 200 OK"): Pair<ServerSocket, String> {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            val client = socket.accept()
            BufferedReader(InputStreamReader(client.getInputStream())).readLine() // ignore the request line
            val headerLine = dateHeaderLine?.let { "Date: $it\r\n" } ?: ""
            client.getOutputStream().write(
                "$statusLine\r\n$headerLine\r\n".toByteArray()
            )
            client.getOutputStream().flush()
            client.close()
        }
        return socket to "http://localhost:${socket.localPort}/get"
    }

    @Test
    fun `reads now from the Date header of a real response, not the machine clock`() {
        val (server, url) = serverReturning("Sun, 01 Mar 2026 12:00:00 GMT")

        try {
            val provider = HttpTimeProvider(url = url)

            assertEquals(Instant.parse("2026-03-01T12:00:00Z"), provider.now())
        } finally {
            server.close()
        }
    }

    @Test
    fun `blows up rather than silently falling back when the header is missing`() {
        val (server, url) = serverReturning(dateHeaderLine = null)

        try {
            val provider = HttpTimeProvider(url = url)

            assertFailsWith<IllegalStateException> { provider.now() }
        } finally {
            server.close()
        }
    }

    @Test
    fun `blows up on a non-2xx response instead of trusting a Date header from an error page`() {
        val (server, url) = serverReturning(
            dateHeaderLine = "Sun, 01 Mar 2026 12:00:00 GMT",
            statusLine = "HTTP/1.1 503 Service Unavailable"
        )

        try {
            val provider = HttpTimeProvider(url = url)

            assertFailsWith<IllegalStateException> { provider.now() }
        } finally {
            server.close()
        }
    }

    @Test
    fun `parses a real RFC 1123 Date header string`() {
        assertEquals(
            Instant.parse("2026-01-15T10:30:00Z"),
            parseHttpDate("Thu, 15 Jan 2026 10:30:00 GMT")
        )
    }
}

