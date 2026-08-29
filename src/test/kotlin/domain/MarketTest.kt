package com.lush.scheduler.domain

import org.example.domain.Market
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketTest {

    @Test
    fun `DEU uses Berlin timezone and German country code`() {
        assertEquals(ZoneId.of("Europe/Berlin"), Market.DEU.zoneId)
        assertEquals("DE", Market.DEU.countryCode)
    }
}