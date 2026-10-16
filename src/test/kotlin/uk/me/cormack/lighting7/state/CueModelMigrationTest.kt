package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cue model plan's startup migration (D5, session 1) over a database in the **old** shape:
 * `src/test/resources/db/pre-cue-model.sql`, the pre-change DDL with `RichProjectFixture`'s rows
 * and a hand-written marker, tie, gap and dangling anchor. The migration runs the way a desk meets
 * it — inside `State`'s `initDatabase`, before the schema is reconciled — and everything is read
 * back through raw SQL, so the assertions do not lean on the model the migration was built from.
 */
class CueModelMigrationTest {

    private var state: State? = null
    private val sources = mutableListOf<HikariDataSource>()

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        loadPreChangeDatabase(IntegrationTestDb.path)
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
        sources.forEach { runCatching { it.close() } }
    }

    // ─── Fixture ─────────────────────────────────────────────────────────────

    private fun loadPreChangeDatabase(path: String) {
        val sql = javaClass.getResourceAsStream("/db/pre-cue-model.sql")!!.readBytes().toString(Charsets.UTF_8)
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                sql.split(";\n")
                    .map { chunk -> chunk.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement.execute(it) }
            }
            connection.commit()
        }
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun stopDesk() {
        state?.shutdown()
        state = null
    }

    /** Every row [sql] answers, a 16-byte blob read as the uuid it is. */
    private fun rows(sql: String, path: String = IntegrationTestDb.path): List<Map<String, Any?>> =
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).associate { i ->
                                val value = rs.getObject(i)
                                meta.getColumnLabel(i) to (if (value is ByteArray && value.size == 16) value.asUuid() else value)
                            })
                        }
                    }
                }
            }
        }

    private fun ByteArray.asUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }

    private fun columns(table: String, path: String = IntegrationTestDb.path): Set<String> =
        rows("PRAGMA table_info($table)", path).map { it["name"] as String }.toSet()

    private fun indexes(path: String = IntegrationTestDb.path): Set<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'index'", path).map { it["name"] as String }.toSet()

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files ->
            files.filter { it.name.startsWith("${db.name}.pre-cue-model-") }.toList()
        }
    }

    /** Every child row's uuid, by its cue, across the tables a cue owns (R2.2's keys). */
    private fun childrenByCue(): Map<Int, Set<UUID>> =
        CHILD_TABLES.flatMap { table -> rows("SELECT cue_id, uuid FROM $table") }
            .groupBy({ (it["cue_id"] as Number).toInt() }, { it["uuid"] as UUID })
            .mapValues { (_, uuids) -> uuids.toSet() }

    private fun cueUuids(): Map<Int, UUID> =
        rows("SELECT id, uuid FROM cues").associate { (it["id"] as Number).toInt() to it["uuid"] as UUID }

    private data class Entry(
        val stackId: Int,
        val cueId: Int?,
        val kind: String,
        val label: String?,
        val ordinal: Double,
        val cueNumber: String?,
        val cueNumberAuto: Boolean,
        val autoAdvance: Boolean,
        val autoAdvanceDelayNanos: Long?,
    )

    private fun entries(): List<Entry> =
        rows("SELECT * FROM cue_stack_entries ORDER BY stack_id, ordinal, id").map {
            Entry(
                stackId = (it["stack_id"] as Number).toInt(),
                cueId = (it["cue_id"] as Number?)?.toInt(),
                kind = it["kind"] as String,
                label = it["label"] as String?,
                ordinal = (it["ordinal"] as Number).toDouble(),
                cueNumber = it["cue_number"] as String?,
                cueNumberAuto = (it["cue_number_auto"] as Number).toInt() != 0,
                autoAdvance = (it["auto_advance"] as Number).toInt() != 0,
                autoAdvanceDelayNanos = (it["auto_advance_delay"] as Number?)?.toLong(),
            )
        }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `the fixture is the old shape`() {
        assertTrue("cue_stack_id" in columns("cues"))
        assertTrue("cue_id" in columns("prompt_book_anchors"))
        assertTrue("uq_cue_number_per_stack" in indexes())
        assertFalse("cue_stack_entries" in rows("SELECT name FROM sqlite_master WHERE type = 'table'").map { it["name"] })
    }

    @Test
    fun `every cue keeps its id, uuid and children, and a marker goes with its rows`() {
        val cuesBefore = cueUuids()
        val childrenBefore = childrenByCue()
        val padsBefore = rows("SELECT id, cue_id FROM busk_pads WHERE cue_id IS NOT NULL")
        assertEquals(setOf(1, 2, 3, 4, 5, 6), cuesBefore.keys)
        assertTrue(childrenBefore.getValue(1).isNotEmpty(), "the fixture's cue 1 has children to keep")
        assertTrue(childrenBefore.getValue(6).isNotEmpty(), "and the hand-written marker has a row to lose")

        startDesk()
        stopDesk()

        val markers = setOf(3, 6)
        assertEquals(cuesBefore - markers, cueUuids(), "every standard cue keeps its id and uuid; the markers are gone")
        assertEquals(childrenBefore - markers, childrenByCue(), "every child row keeps its cue and its uuid")
        assertEquals(
            padsBefore.filterNot { (it["cue_id"] as Number).toInt() in markers },
            rows("SELECT id, cue_id FROM busk_pads WHERE cue_id IS NOT NULL"),
            "a pad on a marker goes; a pad on a cue stays",
        )
        assertFalse("cue_stack_id" in columns("cues"))
        assertTrue(listOf("sort_order", "cue_number", "cue_number_auto", "auto_advance", "auto_advance_delay", "cue_type").none { it in columns("cues") })
    }

    @Test
    fun `every entry matches its old row, renormalised per stack by sort order then id`() {
        val old = rows("SELECT * FROM cues").associateBy { (it["id"] as Number).toInt() }
        startDesk()
        stopDesk()

        fun cueEntry(id: Int, ordinal: Double): Entry {
            val row = old.getValue(id)
            return Entry(
                stackId = (row["cue_stack_id"] as Number).toInt(),
                cueId = id,
                kind = "CUE",
                label = null,
                ordinal = ordinal,
                cueNumber = row["cue_number"] as String?,
                cueNumberAuto = (row["cue_number_auto"] as Number).toInt() != 0,
                autoAdvance = (row["auto_advance"] as Number).toInt() != 0,
                autoAdvanceDelayNanos = (row["auto_advance_delay"] as Number?)?.toLong(),
            )
        }

        fun markerEntry(stackId: Int, label: String, ordinal: Double) =
            Entry(stackId, null, "MARKER", label, ordinal, null, false, false, null)

        assertEquals(
            listOf(
                // Stack 1 by (sort_order, id): open (0, #1), build (1, #2), tie (1, #4), Interval (5, #6), gap (9, #5).
                // The migration renormalised them `1.0, 2.0, …`; the same start's next step
                // (`CueEntryUuidMigration.kt`, sync v32) makes each its 0-based position, what a v31
                // archive's sortOrder said, so every desk writes the same entry file.
                cueEntry(1, 0.0),
                cueEntry(2, 1.0),
                cueEntry(4, 2.0),
                markerEntry(1, "Interval", 3.0),
                cueEntry(5, 4.0),
                // Stack 2's only row was the fixture's marker.
                markerEntry(2, "finale", 0.0),
            ),
            entries(),
        )
        // The fixture's cue 1 carries a follow and an explicit number: both moved, not defaulted.
        assertEquals(2_500_000_000L, entries().first().autoAdvanceDelayNanos)
        assertEquals("1.5", entries().first().cueNumber)
    }

    @Test
    fun `a marker entry keeps the marker cue's uuid, so the archive writes the file it always did`() {
        val markerUuids = rows("SELECT name, uuid FROM cues WHERE cue_type = 'MARKER'").associate { it["name"] to it["uuid"] }
        startDesk()
        stopDesk()
        assertEquals(
            markerUuids,
            rows("SELECT label, uuid FROM cue_stack_entries WHERE kind = 'MARKER'").associate { it["label"] to it["uuid"] },
        )
        // The migration minted each cue entry a uuid of its own; the next step of the same start
        // (`CueEntryUuidMigration.kt`, sync formatVersion 32) names it after its cue, as every desk
        // and every pre-v32 import does, so two desks agree on the place.
        val cueEntries = rows(
            "SELECT e.uuid AS entry, c.uuid AS cue FROM cue_stack_entries e JOIN cues c ON c.id = e.cue_id WHERE e.kind = 'CUE'",
        )
        assertTrue(cueEntries.isNotEmpty())
        assertTrue(cueEntries.all { it["entry"] == it["cue"] }, "each cue's one entry carries its cue's uuid: $cueEntries")
    }

    /**
     * Two entries a desk played tied — an older archive's equal sortOrders, imported after cue model
     * session 1 — become positions in the order the desk played them, by id, never by uuid, so the
     * step moves nothing on stage (agreed 2026-10-16).
     */
    @Test
    fun `entries the desk played tied become positions in the order it played them`() {
        startDesk()
        stopDesk()
        // The marker of stack 1 and that stack's first cue entry, on one ordinal; the marker — the
        // later id, and a marker's uuid is never renamed — gets the lowest uuid there is, so an order
        // by uuid would put it first. Then the one-shot step runs again.
        val marker = rows("SELECT id, stack_id FROM cue_stack_entries WHERE kind = 'MARKER' AND label = 'Interval'").single()
        val markerId = (marker["id"] as Number).toInt()
        val stackId = (marker["stack_id"] as Number).toInt()
        val cueId = (rows("SELECT id FROM cue_stack_entries WHERE kind = 'CUE' AND stack_id = $stackId ORDER BY id LIMIT 1").single()["id"] as Number).toInt()
        assertTrue(cueId < markerId, "test sanity: the cue entry is the earlier id")
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE cue_stack_entries SET ordinal = 2.5 WHERE id IN ($cueId, $markerId)")
                statement.executeUpdate("UPDATE cue_stack_entries SET uuid = zeroblob(16) WHERE id = $markerId")
                statement.executeUpdate("PRAGMA user_version = 0")
            }
        }
        startDesk()
        stopDesk()
        fun ordinal(id: Int) = (rows("SELECT ordinal FROM cue_stack_entries WHERE id = $id").single()["ordinal"] as Number).toDouble()
        assertTrue(ordinal(cueId) < ordinal(markerId), "the tie keeps the order the desk played: ${ordinal(cueId)}, ${ordinal(markerId)}")
    }

    @Test
    fun `cue entries are named after their cues once, and never again`() {
        startDesk()
        stopDesk()
        assertEquals(CUE_ENTRIES_KEYED_USER_VERSION, (rows("PRAGMA user_version").single().values.single() as Number).toInt())

        // An entry renamed afterwards — as a later session's entry of a shared cue would be — stays as
        // it is on every start from now on.
        val renamed = UUID.randomUUID()
        val bytes = ByteBuffer.allocate(16).putLong(renamed.mostSignificantBits).putLong(renamed.leastSignificantBits).array()
        val entryId = (rows("SELECT id FROM cue_stack_entries WHERE kind = 'CUE' ORDER BY id LIMIT 1").single()["id"] as Number).toInt()
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.prepareStatement("UPDATE cue_stack_entries SET uuid = ? WHERE id = ?").use { statement ->
                statement.setBytes(1, bytes)
                statement.setInt(2, entryId)
                statement.executeUpdate()
            }
        }
        startDesk()
        stopDesk()
        assertEquals(renamed, rows("SELECT uuid FROM cue_stack_entries WHERE id = $entryId").single()["uuid"])
    }

    @Test
    fun `anchors re-point to their cue's entry, and one on a missing cue is dropped`() {
        val anchorsBefore = rows("SELECT id, uuid, prompt_book_id, region, label, cue_id FROM prompt_book_anchors ORDER BY id")
        assertEquals(listOf(1, 2, 3), anchorsBefore.map { (it["id"] as Number).toInt() })

        startDesk()
        stopDesk()

        assertFalse("cue_id" in columns("prompt_book_anchors"))
        val after = rows(
            "SELECT a.id, a.uuid, a.prompt_book_id, a.region, a.label, e.cue_id, e.label AS entry_label " +
                "FROM prompt_book_anchors a JOIN cue_stack_entries e ON e.id = a.entry_id ORDER BY a.id",
        )
        assertEquals(listOf(1, 2), after.map { (it["id"] as Number).toInt() }, "anchor 3 named cue 99, which never existed")
        for (anchor in after) {
            val before = anchorsBefore.single { it["id"] == anchor["id"] }
            listOf("uuid", "prompt_book_id", "region", "label").forEach { assertEquals(before[it], anchor[it], it) }
        }
        assertEquals(1, (after[0]["cue_id"] as Number).toInt(), "anchor 1 sits on cue 1's entry")
        assertNull(after[1]["cue_id"])
        assertEquals("Interval", after[1]["entry_label"], "anchor 2 sits on the marker entry the Interval became")
    }

    @Test
    fun `the old index goes, the entry index arrives, and the backup holds the old shape`() {
        startDesk()
        stopDesk()

        assertFalse("uq_cue_number_per_stack" in indexes())
        assertTrue("uq_entry_number_per_stack" in indexes())
        assertTrue(rows("SELECT name FROM sqlite_master WHERE name LIKE '%pre_cue_model%'").isEmpty(), "no table left aside")

        val backup = backups().single()
        assertTrue("cue_stack_id" in columns("cues", backup.toString()), "the backup is the database before the migration")
        assertEquals(6, rows("SELECT id FROM cues", backup.toString()).size)
    }

    @Test
    fun `the rebuilt tables are exactly the model's, as a fresh desk makes them`() {
        startDesk()
        stopDesk()
        val migrated = tableDdl(IntegrationTestDb.path)

        IntegrationTestDb.reset()
        startDesk()
        stopDesk()
        val fresh = tableDdl(IntegrationTestDb.path)

        for (table in listOf("cues", "prompt_book_anchors", "cue_stack_entries")) {
            assertEquals(fresh[table], migrated[table], table)
        }
    }

    private fun tableDdl(path: String): Map<String, String> =
        rows("SELECT name, sql FROM sqlite_master WHERE type = 'table'", path)
            .associate { it["name"] as String to (it["sql"] as String).replace(Regex("\\s+"), " ").trim() }

    @Test
    fun `a second start is a no-op`() {
        startDesk()
        stopDesk()
        val entriesAfterFirst = rows("SELECT * FROM cue_stack_entries ORDER BY id")
        val anchorsAfterFirst = rows("SELECT * FROM prompt_book_anchors ORDER BY id")

        assertNull(CueModelMigration.runIfNeeded(dataSource(), database(), IntegrationTestDb.path), "nothing left to do")

        startDesk()
        stopDesk()
        assertEquals(entriesAfterFirst, rows("SELECT * FROM cue_stack_entries ORDER BY id"))
        assertEquals(anchorsAfterFirst, rows("SELECT * FROM prompt_book_anchors ORDER BY id"))
        assertEquals(1, backups().size, "and takes no second backup")
    }

    @Test
    fun `a migration that fails part-way leaves the database as it was`() {
        // A table already holding the name `cues` is renamed aside to: the rename fails after the
        // entries are written, the markers deleted and the anchors rebuilt, so all of it must roll back.
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE cues_pre_cue_model (id INTEGER PRIMARY KEY)") }
        }
        val tablesBefore = tableDdl(IntegrationTestDb.path)
        val indexesBefore = indexes()
        val dataBefore = DATA_TABLES.associateWith { rows("SELECT * FROM $it ORDER BY id") }

        assertFails { CueModelMigration.runIfNeeded(dataSource(), database(), IntegrationTestDb.path) }

        assertEquals(tablesBefore, tableDdl(IntegrationTestDb.path))
        assertEquals(indexesBefore, indexes())
        for (table in DATA_TABLES) assertEquals(dataBefore[table], rows("SELECT * FROM $table ORDER BY id"), table)
        assertEquals(1, backups().size, "the backup was taken before anything moved")
    }

    @Test
    fun `a stray entries table beside an unconverted cues table is refused, not guessed at`() {
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE cue_stack_entries (id INTEGER PRIMARY KEY)") }
        }
        assertFailsWith<IllegalStateException> {
            CueModelMigration.runIfNeeded(dataSource(), database(), IntegrationTestDb.path)
        }
        assertTrue("cue_stack_id" in columns("cues"))
    }

    private var ownDataSource: HikariDataSource? = null

    private fun dataSource(): HikariDataSource = ownDataSource ?: HikariDataSource(HikariConfig().apply {
        driverClassName = "org.sqlite.JDBC"
        jdbcUrl = "jdbc:sqlite:${IntegrationTestDb.path}"
        maximumPoolSize = 1
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_SERIALIZABLE"
        validate()
    }).also { ownDataSource = it; sources += it }

    private fun database(): Database = Database.connect(dataSource())

    private companion object {
        val CHILD_TABLES = listOf(
            "cue_layers", "cue_ad_hoc_effects", "cue_property_assignments", "cue_triggers", "cue_scenery", "cue_events",
        )
        val DATA_TABLES = listOf("cues", "prompt_book_anchors", "busk_pads", "cue_slots") + CHILD_TABLES
    }

}
