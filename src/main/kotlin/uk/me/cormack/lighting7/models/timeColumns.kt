package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.OffsetDateTimeColumnType
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How this schema stores time.
 *
 * Two kinds of column, and they are not interchangeable:
 *
 * * An **instant** — a point in time — is [utcInstant], stored as timezone-aware `TEXT` at UTC.
 * * A **duration** — an elapsed interval — is `exposed-java-time`'s own `duration()`, stored as
 *   a `BIGINT` of **nanoseconds**.
 *
 * ### Never name a `duration()` column `*_ms`
 *
 * `DurationColumnType` writes `inWholeNanoseconds` into the same `BIGINT` this schema used to
 * hold milliseconds, and `SQLiteDialect.supportsColumnTypeChange` is `false`, so
 * `createMissingTablesAndColumns` never inspects an existing column's type. A duration column
 * that kept its old `_ms` name would therefore read a 250 ms fade as 250 ns — a factor of a
 * million — with no error from Exposed, from SQLite, or from the compiler. Dropping the suffix
 * is what turns that silent misreading into a missing column, which fails loudly. `TimeColumnsTest`
 * asserts no `duration()` column carries the suffix; leave that test alone.
 */

/**
 * An [Instant] column, stored as timezone-aware text and always normalised to UTC.
 *
 * **Not `timestamp()`.** `InstantColumnType.notNullValueToDB` converts through
 * `toLocalDateTime(TimeZone.currentSystemDefault())`, and `SQLiteDataTypeProvider.dateTimeType()`
 * is `TEXT` — so on SQLite a `timestamp()` column holds local wall-clock text with no offset. The
 * stored value would then mean something different after a DST change or a move between machines,
 * and one hour a year would be irrecoverably ambiguous. That is not acceptable for
 * `user_sessions.expires_at` or `password_reset_tokens.expires_at`.
 * [OffsetDateTimeColumnType] writes `yyyy-MM-dd HH:mm:ss.SSSXXX` instead, and `XXX` renders `Z` at
 * zero offset. Fixed width and always UTC means the text sorts chronologically, which is what lets
 * the four SQL-level predicates and orderings on these columns keep comparing as text.
 *
 * **Resolution is milliseconds**, because that formatter's fraction field is exactly three digits.
 * Stamp from [nowUtc] rather than a bare `Instant.now()`, or an in-memory value and the one read
 * back from its column will disagree in the digits past the third.
 */
class UtcInstantColumnType : OffsetDateTimeColumnType<Instant>() {
    override fun toOffsetDateTime(value: Instant): OffsetDateTime = value.atOffset(ZoneOffset.UTC)

    override fun fromOffsetDateTime(datetime: OffsetDateTime): Instant = datetime.toInstant()

    /**
     * Accept an [Instant] unchanged before deferring to the base conversion.
     *
     * Exposed feeds a freshly written value back through `valueFromDB` (the insert's returned
     * values, and the entity cache on read-back), so the value arriving here is often the
     * `Instant` that was just assigned rather than anything the driver produced.
     * `OffsetDateTimeColumnType` only knows `OffsetDateTime`, `ZonedDateTime` and `String`, and
     * throws `Unexpected value:` on anything else — which is every write, until this override.
     */
    override fun valueFromDB(value: Any): Instant? =
        if (value is Instant) value else super.valueFromDB(value)
}

/**
 * Registers a UTC-normalised [Instant] column.
 *
 * A **fresh** [UtcInstantColumnType] per call, deliberately: `Column.nullable()` shares the
 * column-type instance with the column it derives from and mutates `columnType.nullable = true`,
 * so a single shared instance would let one `.nullable()` call make every instant column in the
 * schema nullable.
 */
fun Table.utcInstant(name: String): Column<Instant> = registerColumn(name, UtcInstantColumnType())

/**
 * The clock for anything that will be stored, truncated to the column's own resolution.
 *
 * `Instant.now()` carries microseconds on this JVM while [utcInstant] stores milliseconds, so
 * stamping from it means the value held in memory is not the value in the row. That already caused
 * one bug: a template press broadcast `…34.612360Z` on the WebSocket while the next read of the
 * column returned `…34.612Z`, and a client that patched from the frame then saw the stamp change
 * for no reason. Truncating at the source is what makes the two identical by construction.
 */
fun nowUtc(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

/**
 * Fixed width, fixed three-digit fraction, always UTC — and therefore **sortable as text**.
 *
 * Deliberately not `Instant.toString()`, which omits the fractional part altogether on an exact
 * second: a stamp at `…:34Z` would compare *after* one at `…:34.500Z`. With one instant on the
 * wire that was a caveat a client could be told about; with every instant on the wire it would be
 * a trap. Every ISO-8601 string this API emits comes from here.
 */
private val ISO_UTC_MILLIS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

/** This instant as a sortable ISO-8601 UTC string with a fixed millisecond fraction. */
fun Instant.toIsoUtc(): String = ISO_UTC_MILLIS.format(this)

/**
 * Reads a millisecond epoch that is **not** a column into an [Instant].
 *
 * The one caller group is the OAuth mirror: `StoredOAuthIdentity` is a `@Serializable` blob in the
 * credential store rather than a DB row, so it keeps its `Long` millis, and its expiry fields are
 * converted only where they are copied into `DaoOAuthIdentity`.
 */
fun Long?.asInstant(): Instant? = this?.let(Instant::ofEpochMilli)

/**
 * Reads a millisecond interval that is **not** a column into a [Duration].
 *
 * The counterpart to [asInstant], and the one place the millis→`Duration` direction lives. The
 * callers are the boundaries where a `Duration` column meets a surface that still counts in
 * milliseconds — the REST and WebSocket DTOs, and the sync JSON, both of which keep their
 * `…Ms: Long` fields by design. The other direction is just `Duration.toMillis()`, which needs
 * no helper.
 */
fun Long?.asDuration(): Duration? = this?.let(Duration::ofMillis)

/**
 * `instant + duration`, so a TTL reads as arithmetic.
 *
 * `java.time.Instant` already has `plus(TemporalAmount)`, but Kotlin does not treat it as an
 * operator. Members win overload resolution over extensions, so the body binds to the member
 * rather than recursing into this function.
 */
operator fun Instant.plus(duration: Duration): Instant = this.plus(duration)

/** `instant - duration`. See [plus]. */
operator fun Instant.minus(duration: Duration): Instant = this.minus(duration)
