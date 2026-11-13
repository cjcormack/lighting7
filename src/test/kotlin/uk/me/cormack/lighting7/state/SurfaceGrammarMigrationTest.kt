package uk.me.cormack.lighting7.state

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.ColourAxis
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.FanAxis
import uk.me.cormack.lighting7.midi.How
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.SelectionMove
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.UndoKind
import uk.me.cormack.lighting7.midi.decodeBinding
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Database step 15 (surface-setup plan session 1), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-surface-grammar.sql`: a database at step 14 whose
 * 61 bindings hold every old variant, written by that desk.
 */
class SurfaceGrammarMigrationTest {

    private var state: State? = null

    private val act1 = UUID.fromString("bf772e24-b4ca-4b02-8f51-421a5fa6b110")
    private val storm = UUID.fromString("48175a8e-d41e-407d-933f-a82e07a5f3ba")
    private val chorus = UUID.fromString("c104a608-7b1d-45cb-bec6-451fffd3f896")
    private val stormEntry = UUID.fromString("af4c2556-3f43-4154-abbf-7a5939a2746e")
    private val haze = UUID.fromString("df1699ca-fd4d-4876-ae05-43c0a9aac221")
    private val pink = UUID.fromString("9393cacb-02f1-4175-8669-ca86cc0d19f4")
    private val pad = UUID.fromString("ce5db03c-13c0-4261-8643-727b13f7fd5a")
    private val bank = UUID.fromString("841d4dc0-975a-40a1-a339-1df585bd1763")
    private val page = UUID.fromString("282d0c4b-90cb-4578-a168-d4b19550ffa7")
    private val chase = UUID.fromString("5c64736f-c9ba-4549-94a9-fb8a5387b33a")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
    }

    private fun loadOldDatabase() {
        val sql = javaClass.getResourceAsStream("/db/pre-surface-grammar.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    private fun <T> sql(path: String = IntegrationTestDb.path, block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use(block) }

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun indexes(): Set<String> = sql { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'control_surface_bindings' AND sql IS NOT NULL")
            .use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private data class Row(val type: String, val payload: String, val part: String, val takeover: String?)

    /** Every row by `(control, layer)`; before step 15 there is no part column, and every row is the main part. */
    private fun rows(): Map<Pair<String, String?>, Row> = sql { s ->
        val part = if (userVersion() >= SURFACE_GRAMMAR_USER_VERSION) "part" else "'MAIN'"
        s.executeQuery("SELECT control_id, bank, target_type, target_payload, $part, takeover_policy FROM control_surface_bindings").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1) to rs.getString(2), Row(rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))) }
        }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-surface-grammar-") }.toList() }
    }

    private fun Row.binding(): Binding = decodeBinding(payload, type)

    @Test
    fun `every old binding is in the grammar at its part, the unresolvable kept as they were, backed up first`() {
        loadOldDatabase()
        assertEquals(BUSK_PAD_STACK_USER_VERSION, userVersion())
        assertTrue("uq_surface_binding_slot" in indexes())
        val before = rows()

        val (_, logs) = captureLogs("SurfaceGrammarMigration") { startDesk() }
        stopDesk()

        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertEquals(setOf("uq_surface_binding_part_slot"), indexes(), "the old slot index is gone, the new one has the part")
        val after = rows()
        // The two strip rows are kept by this step and made jobs by step 17.
        assertEquals(59, after.size)
        assertEquals(2, sql { st -> st.executeQuery("SELECT COUNT(*) FROM surface_jobs").use { rs -> rs.next(); rs.getInt(1) } })

        fun at(control: String, layer: String? = null) = after.getValue(control to layer)
        val expected: Map<Pair<String, String?>, Pair<Binding, ControlPart>> = mapOf(
            ("fader-1" to "layer-a") to (Binding(Does.Level("dimmer"), Subject.Group("front")) to ControlPart.MAIN),
            ("fader-2" to null) to (Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")) to ControlPart.MAIN),
            ("fader-3" to null) to (Binding(Does.Level("rgbColour", ColourAxis.SATURATION), Subject.Selection) to ControlPart.MAIN),
            ("fader-9" to null) to (Binding(Does.GrandMaster()) to ControlPart.MAIN),
            ("enc-1" to null) to (Binding(Does.Level("pan"), Subject.Fixture("hex-1")) to ControlPart.MAIN),
            // An encoder holding a button variant: its push.
            ("enc-2" to null) to (Binding(Does.Go, Subject.Record(RecordKind.STACK, act1)) to ControlPart.PUSH),
            ("enc-3" to null) to (Binding(Does.Fan(FanAxis.PAN), Subject.Selection) to ControlPart.MAIN),
            ("enc-4" to null) to (Binding(Does.FxSpeed, Subject.Selection) to ControlPart.MAIN),
            ("enc-5" to null) to (Binding(Does.Tempo(80.0, 160.0), Subject.Record(RecordKind.MASTER, chase)) to ControlPart.MAIN),
            ("enc-6" to null) to (Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, act1)) to ControlPart.MAIN),
            // Named by int alone, resolved in its project.
            ("enc-7" to null) to (Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, act1)) to ControlPart.MAIN),
            ("enc-8" to null) to (Binding(Does.Flash("dimmer", percent = 78), Subject.Group("front")) to ControlPart.PUSH),
            ("enc-9" to null) to (Binding(Does.Select, Subject.Group("front")) to ControlPart.PUSH),
            ("btn-1" to null) to (Binding(Does.Flash("dimmer", percent = 100), Subject.Fixture("hex-1")) to ControlPart.MAIN),
            ("btn-2" to null) to (Binding(Does.Go, Subject.Record(RecordKind.STACK, act1)) to ControlPart.MAIN),
            ("btn-3" to null) to (Binding(Does.Back, Subject.Record(RecordKind.STACK, act1)) to ControlPart.MAIN),
            ("btn-5" to null) to (Binding(Does.GoTo, Subject.Record(RecordKind.ENTRY, stormEntry)) to ControlPart.MAIN),
            ("btn-6" to null) to (Binding(Does.Standby, Subject.Record(RecordKind.ENTRY, stormEntry)) to ControlPart.MAIN),
            ("btn-7" to null) to (Binding(Does.Stop, Subject.Record(RecordKind.STACK, act1)) to ControlPart.MAIN),
            ("btn-8" to null) to (Binding(Does.Cue, Subject.Record(RecordKind.CUE, chorus)) to ControlPart.MAIN),
            ("btn-9" to null) to (Binding(Does.Cue, Subject.Record(RecordKind.CUE, chorus), How.ON) to ControlPart.MAIN),
            ("btn-10" to null) to (Binding(Does.Cue, Subject.Record(RecordKind.CUE, chorus), How.OFF) to ControlPart.MAIN),
            ("btn-11" to null) to (Binding(Does.Fire("output1"), Subject.Fixture("cannon")) to ControlPart.MAIN),
            ("btn-12" to null) to (Binding(Does.Blackout) to ControlPart.MAIN),
            ("btn-13" to null) to (Binding(Does.Blackout, how = How.ON) to ControlPart.MAIN),
            ("btn-14" to null) to (Binding(Does.Blackout, how = How.OFF) to ControlPart.MAIN),
            ("btn-15" to null) to (Binding(Does.GrandMaster()) to ControlPart.MAIN),
            ("btn-16" to null) to (Binding(Does.GrandMaster(level = 50.0)) to ControlPart.MAIN),
            ("btn-17" to null) to (Binding(Does.Layer("layer-b")) to ControlPart.MAIN),
            ("btn-18" to null) to (Binding(Does.Tap) to ControlPart.MAIN),
            ("btn-19" to null) to (Binding(Does.Tempo(bpm = 120.0), Subject.Record(RecordKind.MASTER, chase)) to ControlPart.MAIN),
            ("btn-20" to null) to (Binding(Does.TempoScale(2.0)) to ControlPart.MAIN),
            ("btn-21" to null) to (Binding(Does.Select, Subject.Group("front")) to ControlPart.MAIN),
            ("btn-22" to null) to (Binding(Does.Select, Subject.Fixture("hex-1"), How.REPLACE) to ControlPart.MAIN),
            ("btn-23" to null) to (Binding(Does.SelectionStep(SelectionMove.CLEAR)) to ControlPart.MAIN),
            ("btn-24" to null) to (Binding(Does.Locate, Subject.Selection) to ControlPart.MAIN),
            ("btn-26" to null) to (Binding(Does.EncoderPage("rgbColour", ColourAxis.HUE_FINE)) to ControlPart.MAIN),
            ("btn-27" to null) to (Binding(Does.Look, Subject.Record(RecordKind.LOOK, haze)) to ControlPart.MAIN),
            ("btn-28" to null) to (Binding(Does.Look, Subject.Record(RecordKind.LOOK, haze), How.ON) to ControlPart.MAIN),
            ("btn-29" to null) to (Binding(Does.Palette(pink), Subject.Selection) to ControlPart.MAIN),
            ("btn-30" to null) to (Binding(Does.Pad, Subject.Record(RecordKind.PAD, pad)) to ControlPart.MAIN),
            ("btn-31" to null) to (Binding(Does.PickUp, Subject.Record(RecordKind.PAD, pad)) to ControlPart.MAIN),
            ("btn-32" to null) to (Binding(Does.Place, Subject.Record(RecordKind.BANK, bank)) to ControlPart.MAIN),
            ("btn-33" to null) to (Binding(Does.Drop) to ControlPart.MAIN),
            ("btn-34" to null) to (Binding(Does.Undo(UndoKind.UNDO)) to ControlPart.MAIN),
            ("btn-35" to null) to (Binding(Does.Undo(UndoKind.REDO)) to ControlPart.MAIN),
            ("btn-36" to null) to (Binding(Does.Undo(UndoKind.PROGRAMMER)) to ControlPart.MAIN),
            ("btn-37" to null) to (Binding(Does.BuskPage(1)) to ControlPart.MAIN),
            ("btn-38" to null) to (Binding(Does.BuskPage(-1)) to ControlPart.MAIN),
            ("btn-39" to null) to (Binding(Does.BuskPage(), Subject.Record(RecordKind.PAGE, page)) to ControlPart.MAIN),
            ("btn-1" to "layer-b") to (Binding(Does.WindowFocus("Desk", "pads")) to ControlPart.MAIN),
            ("btn-2" to "layer-b") to (Binding(Does.WindowSheet("Desk")) to ControlPart.MAIN),
            ("btn-3" to "layer-b") to (Binding(Does.SelectionStep(SelectionMove.NEXT)) to ControlPart.MAIN),
            ("btn-4" to "layer-b") to (Binding(Does.SelectionStep(SelectionMove.PREV)) to ControlPart.MAIN),
            ("btn-5" to "layer-b") to (Binding(Does.SelectionStep(SelectionMove.of(SubselectMode.ODD))) to ControlPart.MAIN),
            ("btn-6" to "layer-b") to (Binding(Does.Look, Subject.Record(RecordKind.LOOK, haze), How.OFF) to ControlPart.MAIN),
            ("btn-8" to "layer-b") to (Binding(Does.Cue, Subject.Record(RecordKind.CUE, storm)) to ControlPart.MAIN),
        )
        for ((slot, want) in expected) {
            val row = after.getValue(slot)
            assertEquals(want.first, row.binding(), "$slot")
            assertEquals(want.first.functionId, row.type, "$slot's type mirrors its function")
            assertEquals(want.second.name, row.part, "$slot's part")
        }
        assertEquals("PICKUP", at("enc-1").takeover, "the takeover column is untouched")

        // An int naming no stack, and a type this build never knew: kept byte for byte, read as unknown.
        for (slot in listOf("btn-4" to null, "btn-7" to "layer-b")) {
            assertEquals(before.getValue(slot), after.getValue(slot), "$slot kept")
            assertEquals("MAIN", after.getValue(slot).part)
            assertIs<Does.Unknown>(after.getValue(slot).binding().does)
        }
        assertEquals(expected.size + 2, after.size, "every row is accounted for")
        assertTrue(logs.any { "57 bindings translated, 4 kept" in it }, logs.joinToString("\n"))

        val backup = backups().single()
        sql(backup.toString()) { s ->
            s.executeQuery("SELECT COUNT(*) FROM control_surface_bindings WHERE target_payload LIKE '%\"does\"%'").use { rs ->
                rs.next(); assertEquals(0, rs.getInt(1), "the backup is the database before the step")
            }
        }
    }

    @Test
    fun `the desk reads the translated bindings, and an encoder's turn and push take a binding each`() {
        loadOldDatabase()
        val desk = startDesk()
        val service = desk.controlSurfaceBindingService
        val device = "x-touch-compact-standard"
        val push = service.resolve(1, device, "enc-2", null, desk.encoderBankState.selectionFor(device), ControlPart.PUSH)
        assertEquals(Binding(Does.Go, Subject.Record(RecordKind.STACK, act1)), push?.binding)
        assertEquals(null, service.resolve(1, device, "enc-2", null, desk.encoderBankState.selectionFor(device)), "the turn is unbound")
        service.create(1, device, "enc-2", null, Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))
        assertEquals(2, service.list(1).count { it.controlId == "enc-2" })
    }

    @Test
    fun `a database whose bindings are all in the grammar is only marked, with no backup`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        Files.delete(backups().single())
        sql { s -> s.executeUpdate("PRAGMA user_version = $BUSK_PAD_STACK_USER_VERSION") }
        val (_, logs) = captureLogs("SurfaceGrammarMigration") { startDesk() }
        stopDesk()
        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertTrue(backups().isEmpty(), "the two it cannot translate change nothing")
        assertTrue(logs.any { "0 bindings translated, 2 kept" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a second start leaves a marked database as it is`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        val translated = rows()
        startDesk()
        stopDesk()
        assertEquals(translated, rows())
        assertEquals(1, backups().size)
    }

    @Test
    fun `a fresh database is only marked`() {
        val (_, logs) = captureLogs("SurfaceGrammarMigration") { startDesk() }
        stopDesk()
        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertEquals(setOf("uq_surface_binding_part_slot"), indexes())
        assertTrue(backups().isEmpty())
        assertTrue(logs.isEmpty(), logs.joinToString("\n"))
    }
}
