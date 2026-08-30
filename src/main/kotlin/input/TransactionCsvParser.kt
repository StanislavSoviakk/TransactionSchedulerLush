package org.example.input

import org.example.domain.Market
import org.example.domain.Transaction
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

// STRICT so 2026-02-30 is rejected instead of silently clamped to 02-28.
// "uuuu" not "yyyy" - "yyyy" under STRICT demands an era and fails to parse.
private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofPattern("uuuu-MM-dd HH:mm:ss")
    .withResolverStyle(ResolverStyle.STRICT)

private val REQUIRED_COLUMNS = listOf(
    "txn_id", "market", "shop_id", "shop_subdivision", "local_timestamp",
    "amount", "currency", "payment_method", "payment_ref", "fiscal_seq"
)

/** One row that couldn't be turned into a [Transaction], and why. */
data class ParseError(
    val lineNumber: Int,
    val rawLine: String,
    val reason: String
)

data class ParseResult(
    val transactions: List<Transaction>,
    val errors: List<ParseError>
)

/**
 * Parses the till export CSV:
 * txn_id,market,shop_id,shop_subdivision,local_timestamp,amount,currency,payment_method,payment_ref,fiscal_seq
 *
 * A bad row is skipped and reported as a [ParseError] rather than aborting the whole file.
 * A bad header (missing a required column) is not - it means the whole file isn't in the
 * shape we expect, so [parse] throws [IllegalArgumentException] rather than guessing.
 * Columns are looked up by name, not position, so a reordered header still works.
 * Not a full CSV parser - no quoted-field support (not needed for this feed, see NOTES.md).
 *
 * Takes [Iterable] rather than [List] so a caller reading a big file can pass a lazy
 * source (e.g. `Files.lines(path).asIterable()`) instead of loading it all upfront.
 */
object TransactionCsvParser {

    fun parse(lines: Iterable<String>): ParseResult {
        val iterator = lines.iterator()
        if (!iterator.hasNext()) {
            return ParseResult(emptyList(), emptyList())
        }

        val columnIndex = parseHeader(iterator.next())
        val transactions = mutableListOf<Transaction>()
        val errors = mutableListOf<ParseError>()

        var lineNumber = 1
        while (iterator.hasNext()) {
            lineNumber++
            val rawLine = iterator.next()

            // export is CRLF - strip a stray \r so it doesn't glue itself to the last column
            val line = rawLine.removeSuffix("\r")

            if (line.isBlank()) {
                continue // tolerate trailing blank lines; not worth reporting
            }

            parseRow(line, columnIndex)
                .onSuccess { transactions.add(it) }
                .onFailure { errors.add(ParseError(lineNumber, rawLine, it.message ?: "unknown error")) }
        }

        return ParseResult(transactions, errors)
    }

    private fun parseHeader(headerLine: String): Map<String, Int> {
        val columns = headerLine.removeSuffix("\r").split(",").map { it.trim() }

        require(columns.size == columns.toSet().size) { "CSV header contains duplicate columns" }

        val columnIndex = columns.withIndex().associate { (i, name) -> name to i }

        val missing = REQUIRED_COLUMNS.filterNot { it in columnIndex }
        require(missing.isEmpty()) { "CSV header is missing required column(s): $missing" }

        return columnIndex
    }

    private fun parseRow(line: String, columnIndex: Map<String, Int>): Result<Transaction> {
        val fields = line.split(",")

        if (fields.size != columnIndex.size) {
            return Result.failure(
                IllegalArgumentException("expected ${columnIndex.size} columns, got ${fields.size}")
            )
        }

        fun field(name: String) = fields[columnIndex.getValue(name)].trim()

        val txnId = field("txn_id")
        val marketRaw = field("market")
        val shopId = field("shop_id")
        val subdivisionRaw = field("shop_subdivision")
        val timestampRaw = field("local_timestamp")
        val amountRaw = field("amount")
        val currency = field("currency")
        val paymentMethod = field("payment_method")
        val paymentRefRaw = field("payment_ref")
        val fiscalSeqRaw = field("fiscal_seq")

        if (txnId.isBlank()) {
            return Result.failure(IllegalArgumentException("txn_id is blank"))
        }

        // exact match, case-sensitive: market codes come from till config, not free text
        val market = Market.entries.find { it.name == marketRaw }
            ?: return Result.failure(IllegalArgumentException("unknown market '$marketRaw'"))

        val localTimestamp = try {
            LocalDateTime.parse(timestampRaw, TIMESTAMP_FORMAT)
        } catch (e: DateTimeParseException) {
            return Result.failure(IllegalArgumentException("unparseable local_timestamp '$timestampRaw'"))
        }

        val amount = try {
            BigDecimal(amountRaw)
        } catch (e: NumberFormatException) {
            return Result.failure(IllegalArgumentException("unparseable amount '$amountRaw'"))
        }

        return Result.success(
            Transaction(
                id = txnId,
                market = market,
                shopId = shopId,
                shopSubdivision = subdivisionRaw.ifBlank { null },
                localTimestamp = localTimestamp,
                amount = amount,
                currency = currency,
                paymentMethod = paymentMethod,
                paymentRef = paymentRefRaw.ifBlank { null },
                fiscalSeq = fiscalSeqRaw.ifBlank { null }
            )
        )
    }
}
