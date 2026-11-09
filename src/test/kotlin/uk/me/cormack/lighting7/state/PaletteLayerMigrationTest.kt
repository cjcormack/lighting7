package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Database step 10 (palette-references plan D17), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-palette-references.sql`: a database at step 9 whose
 * cues each hold one palette layer, plain or not.
 */
class PaletteLayerMigrationTest {

    private var state: State? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
    }

    private fun loadOldDatabase() {
        val sql = javaClass.getResourceAsStream("/db/pre-palette-references.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun setUserVersion(version: Int) = sql { it.execute("PRAGMA user_version = $version") }

    private fun <T> sql(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection -> connection.createStatement().use(block) }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-palette-references-") }.toList() }
    }

    private data class Row(val target: TargetRef, val attribute: String, val value: String)

    @Test
    fun `every plain palette layer becomes reference rows and every other stays, named, backed up first`() {
        loadOldDatabase()
        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion())

        val (desk, logs) = captureLogs("PaletteLayerMigration") { startDesk() }

        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertEquals(1, backups().size, "the database is backed up before anything moves")
        val log = logs.joinToString("\n")
        for ((cue, reason) in mapOf(
            "Half amount" to "its amount is 50 %",
            "Additive" to "it blends ADDITIVE",
            "Stomp" to "it stomps the layers below",
            "Timed" to "it is timed",
            "Disabled" to "it is disabled",
            "Foreign mask" to "it is masked to POSITION",
            "Under a Look" to "Look 'Warm' above it asserts colour on the heads it reaches",
        )) {
            assertTrue(logs.any { "cue '$cue' in 'Old layers' keeps palette 'Red' as a layer: $reason" in it }, log)
        }
        assertTrue(logs.none { "'FX palette'" in it || "'Chase'" in it }, "an FX palette's layer is not a candidate:\n$log")
        for (cue in listOf("Plain", "Under an intensity Look", "Own row", "Per-fixture")) {
            assertTrue(logs.any { "cue '$cue' in 'Old layers':" in it && "became" in it }, log)
        }
        assertTrue(logs.any { "4 cue(s) now reference their palettes" in it }, log)

        transaction(desk.database) {
            val palettes = DaoPalette.all().associate { it.name to "pal:${it.uuid}" }
            val cues = DaoCue.all().associateBy { it.name }
            fun layers(name: String) = cues.getValue(name).layers.map { it.palette?.name ?: it.look?.name }.toSet()
            fun rows(name: String) = cues.getValue(name).attributeAssignments.sortedBy { it.sortOrder }.map { Row(it.target, it.attribute, it.value) }
            fun referenceRows(name: String) = rows(name).filter { it.value.startsWith("pal:") }

            val front = TargetRef.Group("front-wash")
            val hex1 = TargetRef.Fixture("hex-1")
            assertEquals(emptySet(), layers("Plain"))
            assertEquals(
                listOf(Row(front, "dimmer", "255"), Row(front, "rgbColour", palettes.getValue("Red"))),
                rows("Plain"), "its rows land after the cue's own",
            )
            assertEquals(setOf("Bright"), layers("Under an intensity Look"))
            assertEquals(listOf(Row(hex1, "rgbColour", palettes.getValue("Red"))), referenceRows("Under an intensity Look"))
            assertEquals(emptySet(), layers("Own row"))
            assertEquals(
                listOf(Row(hex1, "white", "100"), Row(hex1, "rgbColour", palettes.getValue("Red white"))),
                rows("Own row"), "the own white row already won, so only colour becomes a reference",
            )
            assertEquals(emptySet(), layers("Per-fixture"))
            assertEquals(
                listOf(
                    Row(TargetRef.Fixture("spot-1"), "position", palettes.getValue("DSR Focus")),
                    Row(TargetRef.Fixture("spot-2"), "position", palettes.getValue("DSR Focus")),
                ),
                rows("Per-fixture"),
            )
            val paletteUuids = DaoPalette.all().associate { "pal:${it.uuid}" to it.uuid }
            for (cue in cues.values) {
                for (row in cue.attributeAssignments) assertEquals(paletteUuids[row.value], row.paletteUuid, "the indexed column follows the value")
            }

            for (name in listOf("Half amount", "Additive", "Stomp", "Timed", "Disabled", "Foreign mask")) {
                assertEquals(setOf("Red"), layers(name), name)
                assertEquals(emptyList(), rows(name), name)
            }
            assertEquals(setOf("Red", "Warm"), layers("Under a Look"))
            assertEquals(emptyList(), rows("Under a Look"))
            assertEquals(setOf("Chase"), layers("FX palette"))
            assertEquals(emptyList(), rows("FX palette"))
        }
    }

    @Test
    fun `it never runs twice`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        val before = backups()

        val (_, logs) = captureLogs("PaletteLayerMigration") { startDesk() }

        assertEquals(before, backups())
        assertTrue(logs.none { "keeps palette" in it || "became" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a database whose palette layers all stay is only marked`() {
        loadOldDatabase()
        sql { it.execute("UPDATE cue_layers SET enabled = 0 WHERE palette_id IS NOT NULL") }

        val (_, logs) = captureLogs("PaletteLayerMigration") { startDesk() }

        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
        assertTrue(logs.count { "it is disabled" in it } == 11, logs.joinToString("\n"))
    }

    @Test
    fun `a fresh database is born at step 10`() {
        startDesk()
        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
        stopDesk()
        setUserVersion(MOVE_IN_DARK_ROW_FLAG_USER_VERSION)
        startDesk()
        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion(), "a database holding no palette layer is only marked")
        assertEquals(emptyList(), backups())
    }
}
