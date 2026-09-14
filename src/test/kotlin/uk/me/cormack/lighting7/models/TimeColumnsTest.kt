package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.datetime.DurationColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Duration
import java.time.Instant
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the three things about time storage that the compiler cannot check.
 *
 * Each of these fails *silently* if it regresses: a duration misread by a factor of a million,
 * an instant that means a different moment after the clocks change, and an ISO-8601 string that
 * sorts wrongly. See `models/timeColumns.kt`.
 */
class TimeColumnsTest {

    private lateinit var state: State

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    private fun newTemplate(name: String, block: DaoTemplate.() -> Unit): Int {
        val projectId = seedMinimalProject(state)
        return transaction(state.database) {
            DaoTemplate.new {
                this.project = DaoProject.findById(projectId)!!
                this.name = name
                block()
            }.id.value
        }
    }

    private fun <T> readRaw(sql: String, get: (java.sql.ResultSet) -> T): T? {
        var out: T? = null
        transaction(state.database) { exec(sql) { rs -> if (rs.next()) out = get(rs) } }
        return out
    }

    /**
     * `duration()` stores **nanoseconds** in the same `BIGINT` these columns used to hold
     * milliseconds. Nothing would report the difference — `SQLiteDialect` has
     * `supportsColumnTypeChange = false`, so `createMissingTablesAndColumns` never inspects an
     * existing column's type — which is why the `_ms` suffix had to go. This catches a
     * `toMillis()` that should have been `toNanos()`, or a column that gets its old name back.
     */
    @Test
    fun `duration columns store nanoseconds, not milliseconds`() {
        val id = newTemplate("fade-unit-guard") { fadeDuration = Duration.ofMillis(250) }

        assertEquals(
            250_000_000L,
            readRaw("SELECT fade_duration FROM templates WHERE id = $id") { it.getLong(1) },
            "250 ms must be stored as 250,000,000 ns",
        )
        assertEquals(
            250L,
            transaction(state.database) { DaoTemplate.findById(id)!!.fadeDuration }!!.toMillis(),
            "and must read back as 250 ms",
        )
    }

    /**
     * The schema-wide half of the rule above: a `duration()` column named `*_ms` is a column
     * whose name claims milliseconds while its contents are nanoseconds.
     */
    @Test
    fun `no duration column carries an ms suffix`() {
        val offenders = ALL_TABLES
            .flatMap { it.columns }
            .filter { it.columnType is DurationColumnType<*> && it.name.endsWith("_ms") }
            .map { "${it.table.tableName}.${it.name}" }
        assertEquals(emptyList(), offenders, "a duration column stores nanoseconds — drop the _ms suffix")
    }

    /**
     * Instants are stored as UTC text carrying an explicit `Z`.
     *
     * This is the test that fails if anyone swaps [utcInstant] for `exposed-java-time`'s plain
     * `timestamp()`, which writes local wall-clock text with **no offset** — so the same row
     * would mean a different moment on a desk in another timezone, and one hour a year would be
     * ambiguous. Forcing a non-UTC default zone is what makes the difference visible: under UTC
     * the two encodings agree and the bug hides.
     */
    @Test
    fun `instant columns store UTC text with a Z offset, whatever the machine timezone`() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            val instant = Instant.parse("2026-09-14T10:00:00.123Z")
            val id = newTemplate("instant-utc-guard") { lastPressedAt = instant }

            assertEquals(
                "2026-09-14 10:00:00.123Z",
                readRaw("SELECT last_pressed_at FROM templates WHERE id = $id") { it.getString(1) },
                "stored as UTC text, not local wall-clock",
            )
            assertEquals(
                instant,
                transaction(state.database) { DaoTemplate.findById(id)!!.lastPressedAt },
                "and the same instant must come back out",
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /**
     * Every instant this API puts on the wire is sortable as text.
     *
     * `Instant.toString()` omits the fraction entirely on an exact second, so `…:34Z` compares
     * *after* `…:34.500Z`. With one such field that was a caveat clients could be warned about;
     * across two dozen it would be a trap, so [toIsoUtc] pads to a fixed three digits.
     */
    @Test
    fun `toIsoUtc is sortable as text where Instant toString is not`() {
        val onTheSecond = Instant.parse("2026-09-14T10:00:34Z")
        val halfPast = Instant.parse("2026-09-14T10:00:34.500Z")

        assertEquals("2026-09-14T10:00:34.000Z", onTheSecond.toIsoUtc())
        assertTrue(onTheSecond.toIsoUtc() < halfPast.toIsoUtc(), "fixed-width fraction sorts correctly")
        assertTrue(onTheSecond.toString() > halfPast.toString(), "the trap this exists to avoid")
    }

    /** [nowUtc] is truncated to the column's resolution, so a stamp cannot disagree with its row. */
    @Test
    fun `nowUtc carries no sub-millisecond precision`() {
        repeat(50) {
            assertEquals(0, nowUtc().nano % 1_000_000, "nowUtc must be whole milliseconds")
        }
    }
}
