# Notes

## Run

```bash
./gradlew run
```

Reads `data/transactions.csv`, gets a trusted "now" from `https://httpbin.org/get`'s `Date`
header, fetches holidays for the range of years needed by the transactions and trusted now,
plus one extra year at the upper end (see "Which years to fetch" below) from Nager.Date, and
prints the next send window for all 240 transactions. Pass a different CSV
path as the first arg if you want. If the time endpoint or Nager.Date can't be reached, the
run aborts with a clear message instead of guessing — see "Known limitations".

```bash
./gradlew test
```

Runs everything else — 57 tests, no network required (Nager.Date and the HTTP clock are
stubbed with local sockets/servers in tests, never the real endpoints). `EndToEndTest` runs
the real 240-row fixture through parser → scheduler once, offline, as a wiring smoke test.

## What it does

`SendScheduler.findNextSendWindow(transaction, now)` finds the earliest 01:00–02:00 local
window a transaction can send in, given:
- the till's recorded local time (untrusted, never "corrected")
- the trusted current instant (`now`, passed in — the scheduler never touches `Instant.now()`)
- public holidays for the shop's country/subdivision

It's deterministic: same inputs → same output. That's what makes it easy to test.

**Why use the till's timestamp for the date at all, if the clock is unreliable?** The
recorded time is used as a lower bound because the scheduler must not schedule the
transaction before its recorded local time. We don't attempt to estimate or correct
the clock error. This means a wrong clock can delay a transaction, and a sufficiently wrong
clock can also move the lower bound to the wrong local date. There is no reliable way to
recover the true occurrence from this timestamp alone.

Plain Kotlin, no framework (no Spring/Micronaut/DI container): the whole thing is a handful
of small classes with constructor-passed dependencies, which is enough for something this
size and keeps startup and test time near zero.

## Timezone / DST

Zone comes only from `Market`, never guessed from `shopSubdivision`. All conversions go
through `ZonedDateTime`/`java.time` zone rules — no manual offset math.

The one real gotcha, found by actually building the window for every market/date rather than
trusting the brief: **Europe/London's spring-forward happens at 01:00 local, i.e. inside the
send window itself**, not at 02:00/03:00 like Berlin/Paris/New York. On 2026-03-29, naively
building `01:00`–`02:00` for GBR makes both ends resolve to the *same* instant (both get
pushed past the gap to 03:00 CEST... BST), which would violate `SendWindow`'s `start < end`
invariant and crash. The scheduler now detects a collapsed/inverted window and treats that
day as having no valid window, same as a holiday, rolling to the next day. Pinned with real
`Instant` assertions in `SendSchedulerTest` (not formatted strings), using an actual row from
`transactions.csv` that sits inside this exact gap.

Fall-back (clocks repeat an hour) doesn't need special handling, but the resolution isn't
accidental: on the repeated hour, `atZone()` picks the *earlier* occurrence of 01:00 by
default (checked directly against `java.time`, not assumed — see the pinned assumption test),
and we keep that default rather than overriding it. The window opens at the first 01:00 and
closes at the single, unambiguous 02:00, so it's genuinely open for 2 real hours that day
instead of 1. Also tested end-to-end against a real fixture row
(`2026-10-25 01:30:00`, ambiguous in Europe/London).

US spring-forward is a different date to the EU one (2nd Sunday of March vs. last Sunday) —
pinned separately in `SendSchedulerTest` so a fix for one market's DST dates can't quietly
assume the other's calendar.

## CSV parsing

`TransactionCsvParser` is a plain comma-splitter (no quoted-field support — see "Left out
on purpose"). Bad rows are skipped and collected as `ParseError`s with a line number and
reason; one bad row does not sink the file.

Columns are looked up by header name, not fixed position — a reordered header still parses
correctly, and a header missing a required column fails the whole file immediately with a
clear error instead of quietly misreading every row (e.g. treating `shop_id` as `market`).
A malformed header isn't a "bad row," it means the file isn't in our schema at all.
An extra, unrecognised column in the header is fine and simply ignored — the export
gaining a field later shouldn't break scheduling. A duplicate column name is not fine and
is rejected the same way as a missing one, rather than silently keeping the last one.

Checked, not assumed: all 240 rows in `data/transactions.csv` parse cleanly — 0 errors. The
corpus's "dirtiness" turned out to be realistic-but-valid quirks (see below), not malformed
rows. The synthetic malformed-input tests (bad column count, bad date, unknown market, bad
amount) exist for robustness against a real feed, not because this fixture needed them.

Two findings from actually running it against `transactions.csv`, not just the brief:
- The file is CRLF. A naive `split("\n")` leaves a stray `\r` on the last column
  (`fiscal_seq`), which silently defeats an "is this blank?" check. Stripped explicitly.
- `LocalDateTime.parse` with a default `yyyy-MM-dd HH:mm:ss` formatter **silently rewrites**
  `2026-02-30` to `2026-02-28` (SMART resolver) instead of rejecting it — and separately,
  turning on `STRICT` with the `yyyy` pattern letter *also* fails to parse a perfectly valid
  date (it demands an explicit era). The fix is `STRICT` + `uuuu` (proleptic year, not
  year-of-era). Both failure modes are exactly the "library does something confidently wrong
  by default" trap the brief warns about, and both are pinned by tests.

`amount` is parsed as `BigDecimal`, not `Double` — it's money, and JPY genuinely has zero
decimal places in this feed (confirmed against the real data) while EUR/GBP/USD/NZD have two.

`shop_subdivision`/`payment_ref`/`fiscal_seq` are blank-to-`null`, not validated against a
reference list (no full ISO-3166-2 table was provided, and it isn't needed for scheduling —
a garbage subdivision just fails to match any holiday's `counties`, which is a safe default).

## Holidays

One `InMemoryHolidayProvider` holds holidays for all countries, keyed by (country, date).
A holiday applies if it's global, or if the shop's subdivision is in its `counties`.

Two decisions worth knowing:
- unknown/blank subdivision → regional holidays never match, only national ones do.
- holiday check always runs, even if `now` is already inside the candidate window —
  "current window is valid, return it" doesn't skip the holiday check.

Where the holiday data comes from is kept out of the scheduler entirely:

```
NagerDateClient --(HTTP, once per country/year)--> List<Holiday>
                                                          |
                                                          v
                                              InMemoryHolidayProvider
                                                          |
                                                          v
                                                    SendScheduler
```

`NagerDateClient` only fetches and parses JSON — it doesn't implement `HolidayProvider` and
knows nothing about caching or scheduling. `Main` fetches once per (country, year) up front
and hands the flat `List<Holiday>` to `InMemoryHolidayProvider`, which is what `SendScheduler`
actually depends on. Keeping the HTTP client dumb like this is what makes the scheduler
itself stay deterministic and offline-testable.

### Which years to fetch

`Main` decides up front which (country, year) pairs to fetch — the scheduler must not do
HTTP mid-calculation. "Just the current year" isn't enough: the till's clock is untrusted,
so a recorded `local_timestamp` could land in any year. The range fetched is:

`min/max(every recorded transaction year, the trusted "now" year)`, plus one extra year on
top for a candidate date rolling over New Year's Eve.

Not a full guarantee — `SendScheduler`'s own safety cap (`MAX_LOOKAHEAD_DAYS`, 10 years) is
wider than this fetch range. A date outside the fetched range currently reads as "not a
holiday". This is a known fail-open limitation, not a claim that the date is actually
holiday-free.

## Known issue: fiscal_seq ordering

Not hypothetical — it's already in the fixture. Shop 1288 (FR-69):

```
FRA-1288-000010  2026-03-22 15:37:00  fiscal_seq 21055
FRA-1288-000042  2026-05-11 14:57:00  fiscal_seq 21057   <- higher seq
FRA-1288-000023  2026-06-05 16:10:00  fiscal_seq 21056   <- lower seq, later local_timestamp
```

The fiscal sequence indicates `...023` precedes `...042`, while the till timestamps put it
later. That's clock drift, exactly as described in the brief, just large enough here to swap
two rows.

For the markets where `fiscal_seq` is present, the brief says it must be reported as an
unbroken, strictly increasing sequence. The scheduler calculates each transaction's window
independently and never uses `fiscal_seq` — payment/fiscal fields deliberately don't feed
scheduling logic. So if a dispatcher sends transactions in window order, it can send `21057`
before `21056` for this exact shop, breaking that ordering requirement.

**Left alone on purpose**: fixing this means either trusting the till clock enough to reorder
by it (we can't — that's the one thing we're told not to do) or reordering by `fiscal_seq`
instead of window date (a dispatcher-level concern, one layer above this scheduler, and only
relevant to the 2 of 6 markets that have `fiscal_seq` at all). Pinned as-is in
`TransactionCsvParserTest` — the parser doesn't touch or reorder `fiscal_seq`, so at least it
doesn't hide the problem. See "Questions" below.

## Known limitations

- `Main` requires a trusted time source and holiday data to run at all — no fallback to
  the system clock if the time endpoint is unreachable, and no "assume no holidays" if
  Nager.Date fails for a country/year. Both abort the run with a clear message instead.
  Earlier versions of this had silent fallbacks; dropped them because "trusted now" that
  silently degrades to the untrusted local clock isn't trusted at all, and "we don't know
  if this is a holiday" is not the same fact as "it isn't one" — treating the two the same
  could send on a real public holiday without anyone noticing.
- Even when the time endpoint *is* reachable, the `Date` header is only second-precision
  and reflects when the server wrote the response, not when we read it — network RTT adds
  a small, unaccounted-for skew. Fine for a 1-hour window; wouldn't be for anything
  sub-second.
- The holiday-skip loop has a hard cap (10 years of lookahead) that throws instead of
  hanging if a `HolidayProvider` ever says "holiday" forever. Real holiday runs are a
  handful of days; this only exists so a broken provider fails loudly, not silently.
- Till clock is never corrected. If it's wrong (fast/slow), the scheduler still honors it
  as a lower bound, which can genuinely delay sending further than necessary. No way to
  detect this from a single timestamp, so we don't try.
- No holiday data for a date/market outside the fetched (country, year) range is treated as
  "not a holiday" — different from the point above: that's a fetch *failure*, this is a
  gap in what got fetched (see "Which years to fetch"). Same fail-open trade-off either way.
- `shop_subdivision` isn't validated against a real ISO-3166-2 list, so a typo'd subdivision
  silently behaves like "no subdivision" for holiday matching rather than being flagged.

## Left out on purpose

- Quoted/escaped CSV fields. `split(",")` breaks if any field (e.g. a free-text `payment_ref`
  or shop name) ever contains a literal comma — checked the real fixture, none do. A real CSV
  library would fix this properly; not worth the dependency for a 10-column, comma-only feed.
- HTTP retry/error handling for the Nager.Date and time-server calls — doesn't affect
  scheduling correctness, and neither client is used anywhere but `Main`'s startup wiring.

## On a real Android till

`TimeProvider` would sync from a trusted server periodically instead of reading an HTTP
`Date` header on demand (till is offline a lot). Holidays would ship bundled/cached, not
fetched live. Actual dispatch would go through `WorkManager` or similar — `SendScheduler`
itself has no Android dependency and would plug in unchanged.

## Data and surrounding services

In a real system, this component should receive:

- a trusted current time from a service with a defined accuracy/availability guarantee,
  rather than depending on a public HTTP endpoint;
- holiday data from a reliable, versioned source, preferably cached locally so scheduling
  does not depend on network availability at the moment of calculation;
- market/shop configuration including the shop's IANA timezone and ISO subdivision.

The scheduler should not fetch data or own retries/caching. Those concerns belong at the
boundary around it. This keeps the scheduling logic deterministic and makes failures explicit.

The supplied configuration has one timezone per market, so the implementation follows that
model. If a market can span multiple timezones in production, the timezone should become
part of the shop configuration rather than being derived from the market.

## Questions

- **Fiscal ordering:** if `fiscal_seq` is authoritative for reporting order, which component
  is responsible for holding or reordering transactions when unreliable local timestamps put
  them in the wrong order?
- **Timezone configuration:** is one IANA timezone per market a guaranteed invariant, or
  should timezone eventually be configured per shop? The latter would avoid making the
  scheduler depend on a market-level assumption that may not hold as the estate grows.
- **Holiday data:** what is the production source of truth, and is a holiday dataset/version
  expected to stay consistent for the whole scheduling run? A changing dataset mid-run could
  otherwise give two transactions different answers.
- **Trusted time:** is the requirement about accuracy within a known bound, or specifically
  about using a server-derived timestamp? That affects whether a periodically synchronised
  device clock could be used instead of an HTTP request at scheduling time.
