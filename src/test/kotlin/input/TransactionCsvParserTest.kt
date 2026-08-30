package com.lush.scheduler.input

import org.example.domain.Market
import org.example.input.TransactionCsvParser
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionCsvParserTest {

    private val header = "txn_id,market,shop_id,shop_subdivision,local_timestamp,amount,currency,payment_method,payment_ref,fiscal_seq"

    @Test
    fun `parses a well-formed row`() {
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "DEU-1501-000018,DEU,1501,DE-BY,2026-03-27 16:19:00,43.29,EUR,CARD,58DFH2YKL6RVWYKK,88323"
            )
        )

        assertEquals(1, result.transactions.size)
        assertEquals(emptyList(), result.errors)

        val txn = result.transactions.single()
        assertEquals("DEU-1501-000018", txn.id)
        assertEquals(Market.DEU, txn.market)
        assertEquals("DE-BY", txn.shopSubdivision)
        assertEquals(java.time.LocalDateTime.parse("2026-03-27T16:19:00"), txn.localTimestamp)
        assertEquals(java.math.BigDecimal("43.29"), txn.amount)
        assertEquals("88323", txn.fiscalSeq)
    }

    @Test
    fun `blank optional fields become null rather than empty strings`() {
        // matches a real GBR row - no fiscal_seq, and CASH rows have no payment_ref
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "GBR-0203-000016,GBR,0203,GB-ENG,2026-07-03 09:18:00,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,"
            )
        )

        assertEquals(1, result.transactions.size)
        assertNull(result.transactions.single().fiscalSeq)
    }

    @Test
    fun `trailing carriage return does not leak into the last column`() {
        // the real file is CRLF - a naive split("\n") leaves a stray \r on
        // the last column, which would make a blank fiscal_seq look non-blank
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "GBR-0203-000016,GBR,0203,GB-ENG,2026-07-03 09:18:00,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,\r"
            )
        )

        assertEquals(1, result.transactions.size)
        assertNull(result.transactions.single().fiscalSeq)
    }

    @Test
    fun `unknown market is reported and the row is skipped, not the whole file`() {
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "XYZ-0001-000001,XYZ,0001,XY-ZZ,2026-06-10 10:00:00,10.00,XYZ,CARD,REF,",
                "GBR-0203-000016,GBR,0203,GB-ENG,2026-07-03 09:18:00,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,"
            )
        )

        assertEquals(1, result.transactions.size)
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors.single().lineNumber)
    }

    @Test
    fun `malformed local_timestamp is reported with the offending value`() {
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "GBR-0203-000016,GBR,0203,GB-ENG,not-a-timestamp,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,"
            )
        )

        assertEquals(0, result.transactions.size)
        assertEquals(1, result.errors.size)
        assert(result.errors.single().reason.contains("not-a-timestamp"))
    }

    @Test
    fun `calendar-invalid local_timestamp such as day 30 in February is rejected`() {
        // 2026-02-30 looks fine but doesn't exist - must be rejected, not rounded
        // down to the 28th (which is what the default parser does)
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "GBR-0203-000016,GBR,0203,GB-ENG,2026-02-30 10:00:00,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,"
            )
        )

        assertEquals(0, result.transactions.size)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `wrong column count is reported rather than silently misaligning fields`() {
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "GBR-0203-000016,GBR,0203,GB-ENG,2026-07-03 09:18:00,49.21,GBP,CARD"
            )
        )

        assertEquals(0, result.transactions.size)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `unparseable amount is reported`() {
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "GBR-0203-000016,GBR,0203,GB-ENG,2026-07-03 09:18:00,not-a-number,GBP,CARD,KEM4JE0UGVN3LB6R,"
            )
        )

        assertEquals(0, result.transactions.size)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `zero-decimal amount such as JPY is parsed without a fractional part`() {
        val result = TransactionCsvParser.parse(
            listOf(
                header,
                "JPN-0001-000001,JPN,0001,JP-13,2026-06-10 10:00:00,12000,JPY,CARD,REF,"
            )
        )

        assertEquals(java.math.BigDecimal("12000"), result.transactions.single().amount)
    }

    @Test
    fun `header missing a required column fails clearly instead of misparsing every row`() {
        val brokenHeader = "txn_id,market,shop_id,shop_subdivision,amount,currency,payment_method,payment_ref,fiscal_seq"

        assertFailsWith<IllegalArgumentException> {
            TransactionCsvParser.parse(
                listOf(brokenHeader, "GBR-0203-000016,GBR,0203,GB-ENG,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,")
            )
        }
    }

    @Test
    fun `columns in a different order than usual still parse correctly`() {
        val reordered = "market,txn_id,local_timestamp,shop_id,shop_subdivision,currency,amount,payment_method,payment_ref,fiscal_seq"
        val result = TransactionCsvParser.parse(
            listOf(
                reordered,
                "DEU,DEU-1501-000018,2026-03-27 16:19:00,1501,DE-BY,EUR,43.29,CARD,58DFH2YKL6RVWYKK,88323"
            )
        )

        val txn = result.transactions.single()
        assertEquals("DEU-1501-000018", txn.id)
        assertEquals(Market.DEU, txn.market)
        assertEquals(java.math.BigDecimal("43.29"), txn.amount)
    }

    @Test
    fun `duplicate column name in the header is rejected, not silently overwritten`() {
        val duplicated = "txn_id,market,shop_id,shop_subdivision,local_timestamp,amount,currency,payment_method,payment_ref,market"

        assertFailsWith<IllegalArgumentException> {
            TransactionCsvParser.parse(
                listOf(duplicated, "GBR-0203-000016,GBR,0203,GB-ENG,2026-07-03 09:18:00,49.21,GBP,CARD,KEM4JE0UGVN3LB6R,")
            )
        }
    }

    @Test
    fun `the real fixture parses in full with no errors`() {
        val path = java.nio.file.Path.of("data/transactions.csv")
        val lines = java.nio.file.Files.readAllLines(path)

        val result = TransactionCsvParser.parse(lines)

        assertEquals(emptyList(), result.errors)
        assertEquals(240, result.transactions.size)
    }

    @Test
    fun `fiscal_seq is kept as-is even where it is out of order relative to local_timestamp`() {
        // shop 1288 has a real case of this in the fixture: fiscal_seq 21057 is recorded
        // earlier than 21056. that's till clock drift, not a bug - we don't try to fix it.
        val path = java.nio.file.Path.of("data/transactions.csv")
        val lines = java.nio.file.Files.readAllLines(path)

        val result = TransactionCsvParser.parse(lines)
        val shop1288 = result.transactions.filter { it.shopId == "1288" }
            .associateBy { it.fiscalSeq }

        assertEquals(LocalDateTime.parse("2026-05-11T14:57:00"), shop1288.getValue("21057").localTimestamp)
        assertEquals(LocalDateTime.parse("2026-06-05T16:10:00"), shop1288.getValue("21056").localTimestamp)
        // sanity check: the higher seq really does have the earlier timestamp
        assertTrue(
            shop1288.getValue("21057").localTimestamp < shop1288.getValue("21056").localTimestamp
        )
    }
}
