package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.eq
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.MidiInputEvent
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Database step 5 (terminology plan §4.4, §5), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-terminology.sql`, a database a desk on main wrote
 * before the rename: palettes, a Look and a cue with FX on every Order, a Palette Steps over two
 * palettes, a speed master with a usage and two surface bindings.
 */
class TerminologyMigrationTest {

    private var state: State? = null
    private var ownDataSource: HikariDataSource? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
        ownDataSource?.let { runCatching { it.close() } }
    }

    private fun loadOldDatabase() {
        val sql = javaClass.getResourceAsStream("/db/pre-terminology.sql")!!.readBytes().toString(Charsets.UTF_8)
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
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

    private fun sql(statement: String) =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }

    private fun <T> query(statement: String, row: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { s -> s.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }
        }

    private fun strings(statement: String): List<String?> = query(statement) { it.getString(1) }

    private fun userVersion(): Int = query("PRAGMA user_version") { it.getInt(1) }.single()

    /** Every table's and index's DDL, by name. */
    private fun schema(): Map<String, String> =
        query("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'") { it.getString(1) to it.getString(2) }
            .toMap()

    private fun columns(table: String): Set<String> = query("PRAGMA table_info($table)") { it.getString("name") }.toSet()

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-terminology-") }.toList() }
    }

    private fun dataSource(): HikariDataSource = HikariDataSource(HikariConfig().apply {
        driverClassName = "org.sqlite.JDBC"
        jdbcUrl = "jdbc:sqlite:${IntegrationTestDb.path}"
        maximumPoolSize = 1
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_SERIALIZABLE"
        validate()
    }).also { ownDataSource = it }

    /** The tables step 5 rebuilds, by the names they have now. */
    private val renamedTables = listOf(
        "palettes", "palette_rows", "palette_fx", "look_fx", "cue_ad_hoc_fx", "cue_attribute_assignments",
        "look_rows", "cue_layers", "busk_pads", "speed_masters", "fx_definitions",
    )

    /** A fresh database's DDL for [renamedTables] and their indexes: what a rebuilt one must match. */
    private fun freshSchema(): Map<String, String> {
        startDesk()
        stopDesk()
        val fresh = schema().filter { (name, sql) -> name in renamedTables || renamedTables.any { sql.contains(" ON $it ") } }
        IntegrationTestDb.reset()
        return fresh
    }

    @Test
    fun `an old database is backed up, rebuilt in the glossary's words, and its values renamed`() {
        val fresh = freshSchema()
        loadOldDatabase()
        assertEquals(4, userVersion())

        val (_, logs) = captureLogs("TerminologyMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(1, backups().size, "the database is backed up before anything moves")
        assertTrue(logs.any { "templates → palettes" in it && "look_effects → look_fx" in it }, "$logs")
        assertEquals(ATTRIBUTE_NAMES_USER_VERSION, userVersion())

        val tables = strings("SELECT name FROM sqlite_master WHERE type = 'table'").toSet()
        for (old in listOf("templates", "template_rows", "template_effects", "look_effects", "cue_ad_hoc_effects", "cue_property_assignments")) {
            assertTrue(old !in tables, "$old is gone")
        }
        val oldColumns = setOf(
            "template_id", "property_name", "property_mask", "effect_type", "effect_id", "effect_mode", "category", "usage",
            "beat_division", "distribution", "step_timing", "default_step_timing", "size", "n_shot", "spread_blocks",
            "spread_groups", "compatible_properties",
        )
        for (table in renamedTables) {
            assertEquals(emptySet(), columns(table) intersect oldColumns, "$table holds no old column")
        }
        val now = schema().filterKeys { it in fresh }
        assertEquals(fresh, now, "every rebuilt table and index is a fresh database's")

        val orders = strings("SELECT spread_order FROM look_fx UNION ALL SELECT spread_order FROM cue_ad_hoc_fx UNION ALL SELECT spread_order FROM palette_fx").toSet()
        assertEquals(setOf("NORMAL", "REVERSE", "CENTRE_OUT", "CENTRE_IN", "RANDOM", "PING_PONG", "POSITIONAL", "UNIFIED"), orders)
        val families = strings("SELECT family FROM look_fx UNION ALL SELECT family FROM cue_ad_hoc_fx UNION ALL SELECT family FROM palette_fx").toSet()
        assertTrue("dimmer" !in families && "intensity" in families, "$families")
        assertEquals(listOf("colour"), strings("SELECT family FROM speed_masters WHERE family IS NOT NULL"))
        val parameters = strings("SELECT parameters FROM look_fx UNION ALL SELECT parameters FROM cue_ad_hoc_fx")
        assertTrue(parameters.none { "tmpl:" in it!! } && parameters.count { "pal:" in it!! } == 2, "$parameters")
        assertEquals(listOf("HEAD"), strings("SELECT timing FROM look_fx WHERE fx_type = 'PaletteSteps'"), "step timing on is Per head")
        assertEquals(
            listOf(
                "pressPalette" to """{"type":"pressPalette","paletteUuid":"7dbf1758-a269-4d26-9ce0-ff0871394569"}""",
                "stackFxAmount" to """{"type":"stackFxAmount","stackId":1,"stackUuid":"59b883f6-892b-4741-898b-64e82b8bcf88"}""",
            ),
            query("SELECT target_type, target_payload FROM control_surface_bindings ORDER BY id") { it.getString(1) to it.getString(2) },
        )
        assertEquals(4, strings("SELECT id FROM palettes").size, "every row is copied")
        assertEquals(10, strings("SELECT id FROM look_fx UNION ALL SELECT id FROM cue_ad_hoc_fx").size)
    }

    @Test
    fun `the bound pad still presses its palette, through the binding`() {
        loadOldDatabase()
        val desk = startDesk()
        desk.initializeShow()
        desk.show.start()
        val red = UUID.fromString("7dbf1758-a269-4d26-9ce0-ff0871394569")
        assertNull(transaction(desk.database) { DaoPalette.find { uk.me.cormack.lighting7.models.DaoPalettes.uuid eq red }.single().lastPressedAt })

        desk.activeBankState.setBank("x-touch-compact-standard", "layer-a")
        desk.deskSelection.set(listOf(CueTargetDto("fixture", "wash-1")))
        // btn-9 on Layer A is note 24.
        desk.surfaceInputRouter.offerInputForTest("x-touch-compact-standard", MidiInputEvent.NoteOn(channel = 0, note = 24, velocity = 127u))

        assertNotNull(
            transaction(desk.database) { DaoPalette.find { uk.me.cormack.lighting7.models.DaoPalettes.uuid eq red }.single().lastPressedAt },
            "the pressPalette binding pressed Red on (the stamp is the on arm only)",
        )
    }

    @Test
    fun `a database that has not had step 4 has it, over the renamed shape`() {
        loadOldDatabase()
        sql(
            "INSERT INTO look_effects (look_id, target_type, target_key, effect_type, category, property_name, beat_division, " +
                "blend_mode, distribution, parameters, sort_order, uuid) VALUES (1, 'group', 'wash-line', 'Triangle', 'dimmer', " +
                "'dimmer', 1.0, 'OVERRIDE', 'SPLIT', '{}', 9, X'0102030405060708090a0b0c0d0e0f10')",
        )
        sql("PRAGMA user_version = $LOOK_LAYER_TARGETS_CLEARED_USER_VERSION")

        startDesk()
        stopDesk()

        assertEquals(
            listOf(listOf("Ramp", "intensity", "CENTRE_IN")),
            query("SELECT fx_type, family, spread_order FROM look_fx WHERE sort_order = 9") { listOf(it.getString(1), it.getString(2), it.getString(3)) },
        )
        assertEquals(ATTRIBUTE_NAMES_USER_VERSION, userVersion())
    }

    @Test
    fun `a fresh database is born in the glossary's words, with no backup`() {
        startDesk()
        stopDesk()
        assertEquals(ATTRIBUTE_NAMES_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
        assertTrue("palettes" in strings("SELECT name FROM sqlite_master WHERE type = 'table'"))
    }

    @Test
    fun `it runs once, so a second start renames nothing`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        val once = schema()

        val (_, logs) = captureLogs("TerminologyMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(emptyList(), logs)
        assertEquals(once, schema())
        assertEquals(1, backups().size)
    }

    @Test
    fun `a rename that fails part-way leaves the database as it was`() {
        loadOldDatabase()
        // A table already holding the name the Look FX rebuild sets its old table aside under: it
        // fails after rebuilding the palettes.
        sql("CREATE TABLE look_effects_pre_terminology (id INTEGER PRIMARY KEY)")
        val before = schema()
        val rows = strings("SELECT name FROM templates")

        val ds = dataSource()
        assertFails { TerminologyMigration.runIfNeeded(ds, Database.connect(ds), IntegrationTestDb.path) }

        assertEquals(before, schema())
        assertEquals(rows, strings("SELECT name FROM templates"))
        assertEquals(1, backups().size, "the backup was taken before anything moved")
    }

    @Test
    fun `a retired table that already holds a new name is kept aside, and the desk starts`() {
        loadOldDatabase()
        // The positional colour lists were `palettes`, dropped from the model but never from a database.
        sql("CREATE TABLE palettes (id INTEGER PRIMARY KEY, name TEXT)")
        sql("INSERT INTO palettes (id, name) VALUES (1, 'old colours')")
        val names = strings("SELECT name FROM templates ORDER BY id")

        val (_, logs) = captureLogs("TerminologyMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(listOf("old colours"), strings("SELECT name FROM palettes_retired"))
        assertEquals(names, strings("SELECT name FROM palettes ORDER BY id"))
        assertTrue(logs.any { "palettes_retired" in it }, "$logs")
        assertEquals(ATTRIBUTE_NAMES_USER_VERSION, userVersion())
    }

    @Test
    fun `a layer naming no record, older than the check that refuses one, is left out and logged`() {
        loadOldDatabase()
        val cue = strings("SELECT id FROM cues ORDER BY id LIMIT 1").single()
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA ignore_check_constraints = ON")
                it.executeUpdate(
                    "INSERT INTO cue_layers (cue_id, look_id, template_id, targets, uuid) " +
                        "VALUES ($cue, NULL, NULL, '[]', X'0f0e0d0c0b0a09080706050403020100')",
                )
            }
        }
        val layers = strings("SELECT id FROM cue_layers").size

        val (_, logs) = captureLogs("TerminologyMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(layers - 1, strings("SELECT id FROM cue_layers").size)
        assertTrue(logs.any { "1 cue_layers row(s)" in it }, "$logs")
    }

    @Test
    fun `an FX definition's palette default is renamed`() {
        loadOldDatabase()
        val project = strings("SELECT id FROM projects ORDER BY id LIMIT 1").single()
        sql(
            "INSERT INTO fx_definitions (effect_id, name, category, output_type, parameters, script, project_id, default_step_timing, uuid) " +
                "VALUES ('my-steps', 'My Steps', 'palette', 'COLOUR', " +
                "'[{\"name\":\"colours\",\"type\":\"colour-list\",\"defaultValue\":\"tmpl:7dbf1758-a269-4d26-9ce0-ff0871394569\"}]', " +
                "'', $project, 1, X'00112233445566778899aabbccddeeff')",
        )

        startDesk()
        stopDesk()

        assertEquals(
            listOf(listOf("steps", "HEAD", "pal:7dbf1758-a269-4d26-9ce0-ff0871394569")),
            query("SELECT family, default_timing, parameters FROM fx_definitions WHERE fx_id = 'my-steps'") {
                listOf(it.getString(1), it.getString(2), Regex("pal:[0-9a-f-]+").find(it.getString(3))?.value)
            },
        )
    }
}
