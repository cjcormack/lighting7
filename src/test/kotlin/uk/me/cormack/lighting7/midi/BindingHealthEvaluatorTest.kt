package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fixture.dmx.EquinoxTwinShotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Phase 7: unit tests for [BindingHealthEvaluator]. A binding's health is its subject's, then its
 * function's parameters'; each arm maps onto the expected [AssignmentHealth], and fixture / group
 * checks delegate to [uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator] (covered by
 * that validator's own suite; here we just assert the integration).
 */
class BindingHealthEvaluatorTest {

    private val universe = Universe(0, 0)

    private fun fixturesWithHex(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            createGroup<HexFixture>("front-wash") {
                addSpread(listOf(hex1, hex2))
            }
        }
        return fixtures
    }

    private val liveMaster: UUID = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2")

    private val liveCue: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val liveStack: UUID = UUID.fromString("66666666-7777-8888-9999-000000000000")
    private val liveEntry: UUID = UUID.fromString("99999999-0000-4000-8000-000000000001")

    private val liveLook: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
    private val livePalette: UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001")
    private val livePad: UUID = UUID.fromString("cccccccc-0000-4000-8000-000000000001")
    private val livePage: UUID = UUID.fromString("dddddddd-0000-4000-8000-000000000001")
    private val liveBank: UUID = UUID.fromString("ffffffff-0000-4000-8000-000000000001")
    private val gone: UUID = UUID.fromString("eeeeeeee-0000-4000-8000-000000000001")

    private fun context(
        fixtures: Fixtures = fixturesWithHex(),
        validSpeedMasterUuids: Set<UUID> = setOf(liveMaster),
        connectedWindowNames: Set<String> = emptySet(),
    ): BindingHealthEvaluator.Context = BindingHealthEvaluator.Context(
        fixtures = fixtures,
        deviceTypes = ControlSurfaceRegistry.allTypes,
        validSpeedMasterUuids = validSpeedMasterUuids,
        validStackUuids = setOf(liveStack),
        validCueUuids = setOf(liveCue),
        selectionAttributes = BindingHealthEvaluator.selectionAttributesOf(fixtures),
        colourAttributes = BindingHealthEvaluator.colourAttributesOf(fixtures),
        validLookUuids = setOf(liveLook),
        validPaletteUuids = setOf(livePalette),
        validPadUuids = setOf(livePad),
        validBankUuids = setOf(liveBank),
        validPageUuids = setOf(livePage),
        connectedWindowNames = connectedWindowNames,
        validEntryUuids = mapOf(liveEntry to liveStack),
    )

    private fun health(binding: Binding, ctx: BindingHealthEvaluator.Context = context()) =
        BindingHealthEvaluator.evaluate(binding, ctx)

    private fun record(kind: RecordKind, uuid: UUID) = Subject.Record(kind, uuid)

    // ─── Records on buttons (midi-surface plan D6) ─────────────────────

    @Test
    fun `a record binding is healthy when its uuid resolves and dead when it does not`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Look, record(RecordKind.LOOK, liveLook))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Palette(livePalette), Subject.Selection)))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Pad, record(RecordKind.PAD, livePad))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.BuskPage(), record(RecordKind.PAGE, livePage))))

        assertIs<AssignmentHealth.MissingLook>(health(Binding(Does.Look, record(RecordKind.LOOK, gone))))
        assertIs<AssignmentHealth.MissingPalette>(health(Binding(Does.Palette(gone), Subject.Selection)))
        assertIs<AssignmentHealth.MissingPad>(health(Binding(Does.Pad, record(RecordKind.PAD, gone))))
        assertIs<AssignmentHealth.MissingPage>(health(Binding(Does.BuskPage(), record(RecordKind.PAGE, gone))))
    }

    @Test
    fun `a malformed record uuid reads as unknown rather than parsed leniently`() {
        val payloads = mapOf(
            "look" to """{"does":{"type":"look"},"on":{"type":"record","kind":"LOOK","uuid":"not-a-uuid"}}""",
            "pad" to """{"does":{"type":"pad"},"on":{"type":"record","kind":"PAD","uuid":""}}""",
            "place" to """{"does":{"type":"place"},"on":{"type":"record","kind":"BANK","uuid":"not-a-uuid"}}""",
            "palette" to """{"does":{"type":"palette","palette":"not-a-uuid"},"on":{"type":"selection"}}""",
            "tap" to """{"does":{"type":"tap"},"on":{"type":"record","kind":"MASTER","uuid":"not-a-uuid"}}""",
        )
        for ((type, payload) in payloads) {
            assertEquals(AssignmentHealth.UnknownTarget(type), health(decodeBinding(payload, type)), type)
        }
    }

    // ─── The hand (multi-screen plan §3.5) ─────────────────────────────

    @Test
    fun `the hand's two addressed functions are judged on the record they name`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.PickUp, record(RecordKind.PAD, livePad))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Place, record(RecordKind.BANK, liveBank))))

        assertIs<AssignmentHealth.MissingPad>(health(Binding(Does.PickUp, record(RecordKind.PAD, gone))))
        assertIs<AssignmentHealth.MissingBank>(health(Binding(Does.Place, record(RecordKind.BANK, gone))))

        // A pick-up's pad and a place's bank come from **different** sets: a bank uuid on a pick-up
        // is as dead as any other stranger, which is what a shared helper reading the wrong set
        // would hide.
        assertIs<AssignmentHealth.MissingPad>(health(Binding(Does.PickUp, record(RecordKind.PAD, liveBank))))
        assertIs<AssignmentHealth.MissingBank>(health(Binding(Does.Place, record(RecordKind.BANK, livePad))))
    }

    @Test
    fun `a hand drop addresses nothing and so can never be dead`() {
        // Whether the hand holds anything is a fact about this second, not about the binding — the
        // same line a level on the selection draws with an empty selection.
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Drop)))
    }

    @Test
    fun `the history functions address nothing and are always healthy`() {
        for (kind in UndoKind.entries) {
            assertEquals(AssignmentHealth.Ok, health(Binding(Does.Undo(kind))), "$kind")
        }
    }

    @Test
    fun `busk page next and prev are page-agnostic and always healthy`() {
        // A project with no pages has nowhere to step, which is not a dead binding — the same line
        // clearing the selection draws with an empty selection.
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.BuskPage(1))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.BuskPage(-1))))
    }

    @Test
    fun `a stack's FX amount and an entry are judged by their uuids`() {
        // A stack's FX amount fader is judged by its stack, as the stack buttons are.
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.FxAmount, record(RecordKind.STACK, liveStack))))
        assertEquals(
            AssignmentHealth.MissingStack(stackUuid = gone.toString()),
            health(Binding(Does.FxAmount, record(RecordKind.STACK, gone))),
        )
        // An entry gone reads as a missing cue.
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.GoTo, record(RecordKind.ENTRY, liveEntry))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Standby, record(RecordKind.ENTRY, liveEntry))))
        assertEquals(
            AssignmentHealth.MissingCue(cueUuid = gone.toString()),
            health(Binding(Does.GoTo, record(RecordKind.ENTRY, gone))),
        )
        // A cue's uuid is not an entry's.
        assertIs<AssignmentHealth.MissingCue>(health(Binding(Does.Standby, record(RecordKind.ENTRY, liveCue))))
    }

    @Test
    fun `a level on the selection is healthy for an attribute some patched fixture can take on a fader`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Level("dimmer"), Subject.Selection)))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Level("rgbColour"), Subject.Selection)))
        assertEquals(AssignmentHealth.UnknownAttribute("tilt"), health(Binding(Does.Level("tilt"), Subject.Selection)))
    }

    @Test
    fun `a select needs its group or fixture to exist and nothing else`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Select, Subject.Group("front-wash"))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Select, Subject.Fixture("hex-2"), How.REPLACE)))
        assertEquals(AssignmentHealth.MissingGroup("side-wash"), health(Binding(Does.Select, Subject.Group("side-wash"))))
        assertEquals(AssignmentHealth.MissingFixture("hex-9"), health(Binding(Does.Select, Subject.Fixture("hex-9"))))
    }

    @Test
    fun `a strip is judged by its subject alone, not by the attributes it derives`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Strip, Subject.Group("front-wash"))))
        assertEquals(AssignmentHealth.MissingGroup("side-wash"), health(Binding(Does.Strip, Subject.Group("side-wash"))))
        assertEquals(AssignmentHealth.MissingFixture("hex-9"), health(Binding(Does.Strip, Subject.Fixture("hex-9"))))
    }

    @Test
    fun `an encoder page needs an attribute some patched fixture can take`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.EncoderPage("dimmer"))))
        assertEquals(AssignmentHealth.UnknownAttribute("tilt"), health(Binding(Does.EncoderPage("tilt"))))
    }

    @Test
    fun `clearing and locating the selection are always Ok and Unknown is always dead`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.SelectionStep(SelectionMove.CLEAR))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Locate, Subject.Selection)))
        // A slot names nothing that can go missing: its job's item fills it.
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Select, Subject.Slot)))
        assertEquals(
            AssignmentHealth.UnknownTarget("fromTheFuture"),
            health(Binding(Does.Unknown("fromTheFuture", "{}"))),
        )
    }

    @Test
    fun `blackout and the grand master always resolve to Ok`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Blackout)))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Blackout, how = How.ON)))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.GrandMaster())))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.GrandMaster(level = 50.0))))
    }

    @Test
    fun `a fixture level with live fixture + valid attribute is Ok`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))))
    }

    @Test
    fun `a fixture level on a renamed fixture returns MissingFixture`() {
        val missing = assertIs<AssignmentHealth.MissingFixture>(
            health(Binding(Does.Level("dimmer"), Subject.Fixture("hex-renamed"))),
        )
        assertEquals("hex-renamed", missing.fixtureKey)
    }

    @Test
    fun `a group level on an unknown group returns MissingGroup`() {
        val missing = assertIs<AssignmentHealth.MissingGroup>(
            health(Binding(Does.Level("dimmer"), Subject.Group("vanished"))),
        )
        assertEquals("vanished", missing.groupName)
    }

    @Test
    fun `a group level on a removed attribute returns MissingAttribute`() {
        val missing = assertIs<AssignmentHealth.MissingAttribute>(
            health(Binding(Does.Level("doesNotExist"), Subject.Group("front-wash"))),
        )
        assertEquals("front-wash", missing.targetKey)
        assertEquals("doesNotExist", missing.attribute)
    }

    @Test
    fun `a level reaches elements as the write does - a cell, a group of cells, a parent whose colour is its cells'`() {
        val fixtures = Fixtures()
        fixtures.register {
            // Its colour on two warm/cold cells only.
            val blinder = addFixture(China2CellLedBlinderFixture(universe, "blinder", "Blinder", firstChannel = 201))
            // Its colour on three RGBW zones only.
            val wash = addFixture(ShehdsLed19RgbwFixture.Mode24Ch(universe, "wash", "Wash", firstChannel = 101))
            createGroup<GroupableFixture>("cells") { addSpread(listOf(blinder.elements[0], wash.elements[1])) }
        }
        val ctx = context(fixtures)
        val colour = Does.Level("rgbColour", ColourAxis.BRIGHTNESS)
        assertEquals(AssignmentHealth.Ok, health(Binding(colour, Subject.Fixture("blinder")), ctx))
        assertEquals(AssignmentHealth.Ok, health(Binding(colour, Subject.Fixture("wash")), ctx))
        assertEquals(AssignmentHealth.Ok, health(Binding(colour, Subject.Fixture("blinder.cell-2")), ctx))
        assertEquals(AssignmentHealth.Ok, health(Binding(colour, Subject.Group("cells")), ctx))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Flash("rgbColour", 50), Subject.Fixture("wash.zone-1")), ctx))

        assertEquals(AssignmentHealth.MissingFixture("blinder.cell-9"), health(Binding(colour, Subject.Fixture("blinder.cell-9")), ctx))
        assertEquals(AssignmentHealth.MissingAttribute("blinder", "zoom"), health(Binding(Does.Level("zoom"), Subject.Fixture("blinder")), ctx))
    }

    @Test
    fun `go, back, pause and stop resolve against the project's stacks`() {
        for (does in listOf(Does.Go, Does.Back, Does.Pause, Does.Stop)) {
            assertEquals(AssignmentHealth.Ok, health(Binding(does, record(RecordKind.STACK, liveStack))), does.functionId)
        }
        assertEquals(
            AssignmentHealth.MissingStack(stackUuid = gone.toString()),
            health(Binding(Does.Go, record(RecordKind.STACK, gone))),
        )
        // A cue's uuid is not a stack's.
        assertIs<AssignmentHealth.MissingStack>(health(Binding(Does.Pause, record(RecordKind.STACK, liveCue))))
    }

    @Test
    fun `a cue binding resolves against the project's cues, however it is pressed`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Cue, record(RecordKind.CUE, liveCue))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Cue, record(RecordKind.CUE, liveCue), How.ON)))
        assertEquals(
            AssignmentHealth.MissingCue(cueUuid = gone.toString()),
            health(Binding(Does.Cue, record(RecordKind.CUE, gone), How.OFF)),
        )
    }

    @Test
    fun `a layer validates against the profile of the device it is bound on`() {
        // The X-Touch Compact Standard profile declares layer-a and layer-b banks.
        val ctx = context()
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(Binding(Does.Layer("layer-a")), ctx, "x-touch-compact-standard"),
        )
        assertEquals(
            AssignmentHealth.UnknownBank("x-touch-compact-standard", "layer-z"),
            BindingHealthEvaluator.evaluate(Binding(Does.Layer("layer-z")), ctx, "x-touch-compact-standard"),
        )
        assertEquals(
            AssignmentHealth.UnknownBank("not-a-real-device", "layer-a"),
            BindingHealthEvaluator.evaluate(Binding(Does.Layer("layer-a")), ctx, "not-a-real-device"),
        )
    }

    @Test
    fun `a flash is judged by its fixture and attribute`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Flash("dimmer"), Subject.Fixture("hex-1"))))
        assertIs<AssignmentHealth.MissingFixture>(health(Binding(Does.Flash("dimmer"), Subject.Fixture("hex-gone"))))
        assertIs<AssignmentHealth.MissingAttribute>(health(Binding(Does.Flash("doesNotExist", 50), Subject.Group("front-wash"))))
    }

    @Test
    fun `a fire needs its fixture patched and carrying the trigger`() {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            addFixture(EquinoxTwinShotMkIIFixture(universe, "cannon", "Cannon", firstChannel = 100))
        }
        val ctx = context(fixtures = fixtures)
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Fire("output1"), Subject.Fixture("cannon")), ctx))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Fire("B"), Subject.Fixture("cannon")), ctx), "by label")
        assertEquals(
            AssignmentHealth.MissingAttribute("cannon", "output9"),
            health(Binding(Does.Fire("output9"), Subject.Fixture("cannon")), ctx),
        )
        assertEquals(
            AssignmentHealth.MissingAttribute("hex-1", "output1"),
            health(Binding(Does.Fire("output1"), Subject.Fixture("hex-1")), ctx),
        )
        assertEquals(
            AssignmentHealth.MissingFixture("cannon-2"),
            health(Binding(Does.Fire("output1"), Subject.Fixture("cannon-2")), ctx),
        )
    }

    @Test
    fun `a window-addressed binding is dead until a window of that name connects`() {
        val focus = Binding(Does.WindowFocus("Screen 2", "rig"))
        val sheet = Binding(Does.WindowSheet("Screen 2"))
        assertEquals(AssignmentHealth.MissingWindow("Screen 2"), health(focus))
        assertEquals(AssignmentHealth.MissingWindow("Screen 2"), health(sheet))

        val connected = context(connectedWindowNames = setOf("Screen 1", "Screen 2"))
        assertEquals(AssignmentHealth.Ok, health(focus, connected))
        assertEquals(AssignmentHealth.Ok, health(sheet, connected))
    }

    @Test
    fun `an unkeyed speed-master binding means master 1 and is always Ok`() {
        val ctx = context(validSpeedMasterUuids = emptySet())
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Tempo()), ctx))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Tap), ctx))
    }

    @Test
    fun `a speed-master binding naming a live master is Ok`() {
        val master = record(RecordKind.MASTER, liveMaster)
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Tempo(), master)))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Tempo(bpm = 120.0), master)))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.Tap, master)))
    }

    /**
     * Unlike an *effect*, which degrades to master 1 when its master vanishes, a binding
     * reports itself dead: silently retuning the global tempo instead of the master the
     * operator picked would be worse than doing nothing visible.
     */
    @Test
    fun `a speed-master binding naming a deleted master is MissingSpeedMaster`() {
        val master = record(RecordKind.MASTER, gone)
        assertEquals(AssignmentHealth.MissingSpeedMaster(gone.toString()), health(Binding(Does.Tempo(), master)))
        assertEquals(AssignmentHealth.MissingSpeedMaster(gone.toString()), health(Binding(Does.Tap, master)))
    }

    @Test
    fun `a tempo scale is judged by its master, and the selection's FX speed is always Ok`() {
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.TempoScale(2.0))))
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.TempoScale(0.5), record(RecordKind.MASTER, liveMaster))))
        assertEquals(
            AssignmentHealth.MissingSpeedMaster(gone.toString()),
            health(Binding(Does.TempoScale(2.0), record(RecordKind.MASTER, gone))),
        )
        // Selection-relative: no Local FX on the selection is this second's fact, not the binding's.
        assertEquals(AssignmentHealth.Ok, health(Binding(Does.FxSpeed, Subject.Selection)))
    }
}
