package uk.me.cormack.lighting7.midi

import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.state.SubselectMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one translate from a binding stored before the grammar (P4): each old variant's function,
 * subject and How, the part an encoder's button variant moves to, ints resolved where the desk can,
 * and the reverse the old screens read.
 */
class SurfaceGrammarTest {
    private val stack = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val cue = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val entry = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val look = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val palette = UUID.fromString("55555555-5555-4555-8555-555555555555")
    private val pad = UUID.fromString("66666666-6666-4666-8666-666666666666")
    private val bank = UUID.fromString("77777777-7777-4777-8777-777777777777")
    private val page = UUID.fromString("88888888-8888-4888-8888-888888888888")
    private val master = UUID.fromString("99999999-9999-4999-8999-999999999999")

    private val refs = object : SurfaceGrammar.Ints, SurfaceGrammar.Ids {
        override fun stackUuid(id: Int) = if (id == 7) stack else null
        override fun cueUuid(id: Int) = if (id == 9) cue else null
        override fun stackId(uuid: UUID) = if (uuid == stack) 7 else null
        override fun cueId(uuid: UUID) = if (uuid == cue) 9 else null
        override fun entryStack(entry: UUID) = if (entry == this@SurfaceGrammarTest.entry) stack else null
    }

    private fun old(json: String) = BindingJson.parseToJsonElement(json) as JsonObject

    private fun translate(json: String): Binding? = SurfaceGrammar.binding(old(json), refs)

    /** Every old variant, as `main` wrote it, and the binding it becomes. */
    private val table: List<Pair<String, Binding>> = listOf(
        """{"type":"fixtureAttribute","fixtureKey":"hex-1","attribute":"dimmer"}""" to Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
        """{"type":"groupAttribute","groupName":"front","attribute":"rgbColour","colourAxis":"saturation"}""" to
            Binding(Does.Level("rgbColour", ColourAxis.SATURATION), Subject.Group("front")),
        """{"type":"selectionAttribute","attribute":"pan"}""" to Binding(Does.Level("pan"), Subject.Selection),
        """{"type":"cueStackGo","stackId":7}""" to Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)),
        """{"type":"cueStackBack","stackId":3,"stackUuid":"$stack"}""" to Binding(Does.Back, Subject.Record(RecordKind.STACK, stack)),
        """{"type":"cueStackPause","stackId":7,"stackUuid":"$stack"}""" to Binding(Does.Pause, Subject.Record(RecordKind.STACK, stack)),
        """{"type":"cueStackStop","stackUuid":"$stack"}""" to Binding(Does.Stop, Subject.Record(RecordKind.STACK, stack)),
        """{"type":"cueStackGoTo","stackUuid":"$stack","entryUuid":"$entry"}""" to Binding(Does.GoTo, Subject.Record(RecordKind.ENTRY, entry)),
        """{"type":"cueStackStandby","stackUuid":"$stack","entryUuid":"$entry"}""" to Binding(Does.Standby, Subject.Record(RecordKind.ENTRY, entry)),
        """{"type":"stackFxAmount","stackId":7}""" to Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, stack)),
        """{"type":"fireCue","cueId":9}""" to Binding(Does.Cue, Subject.Record(RecordKind.CUE, cue)),
        """{"type":"looseCueSet","cueUuid":"$cue","on":false}""" to Binding(Does.Cue, Subject.Record(RecordKind.CUE, cue), How.OFF),
        """{"type":"fireTrigger","fixtureKey":"cannon","trigger":"output1"}""" to Binding(Does.Fire("output1"), Subject.Fixture("cannon")),
        """{"type":"flash","target":{"type":"groupAttribute","groupName":"front","attribute":"dimmer"},"max":200}""" to
            Binding(Does.Flash("dimmer", percent = 78), Subject.Group("front")),
        """{"type":"flash","target":{"type":"fixtureAttribute","fixtureKey":"hex-1","attribute":"dimmer"}}""" to
            Binding(Does.Flash("dimmer", percent = 100), Subject.Fixture("hex-1")),
        """{"type":"blackout"}""" to Binding(Does.Blackout),
        """{"type":"blackoutSet","on":true}""" to Binding(Does.Blackout, how = How.ON),
        """{"type":"grandMasterToggle"}""" to Binding(Does.GrandMaster()),
        """{"type":"grandMasterFader"}""" to Binding(Does.GrandMaster()),
        """{"type":"grandMasterLevel","level":50.0}""" to Binding(Does.GrandMaster(level = 50.0)),
        """{"type":"setBank","deviceTypeKey":"x-touch-compact-standard","bank":"layer-b"}""" to Binding(Does.Layer("layer-b")),
        """{"type":"speedMasterBpm","masterUuid":"$master","minBpm":80.0,"maxBpm":160.0}""" to
            Binding(Does.Tempo(80.0, 160.0), Subject.Record(RecordKind.MASTER, master)),
        """{"type":"speedMasterBpm"}""" to Binding(Does.Tempo()),
        """{"type":"speedMasterSetBpm","masterUuid":"$master","bpm":120.0}""" to Binding(Does.Tempo(bpm = 120.0), Subject.Record(RecordKind.MASTER, master)),
        """{"type":"speedMasterTap"}""" to Binding(Does.Tap),
        """{"type":"speedMasterScale","factor":0.5}""" to Binding(Does.TempoScale(0.5)),
        """{"type":"fxSpeed"}""" to Binding(Does.FxSpeed, Subject.Selection),
        """{"type":"selectionFan","axis":"ACROSS"}""" to Binding(Does.Fan(FanAxis.ACROSS), Subject.Selection),
        """{"type":"selectTarget","target":{"type":"group","key":"front"}}""" to Binding(Does.Select, Subject.Group("front")),
        """{"type":"selectTarget","target":{"type":"fixture","key":"hex-1"},"mode":"replace"}""" to
            Binding(Does.Select, Subject.Fixture("hex-1"), How.REPLACE),
        """{"type":"clearSelection"}""" to Binding(Does.SelectionStep(SelectionMove.CLEAR)),
        """{"type":"locateSelection"}""" to Binding(Does.Locate, Subject.Selection),
        """{"type":"strip","target":{"type":"group","key":"front"}}""" to Binding(Does.Strip, Subject.Group("front")),
        """{"type":"encoderBankSet","attribute":"rgbColour","colourAxis":"hueFine"}""" to Binding(Does.EncoderPage("rgbColour", ColourAxis.HUE_FINE)),
        """{"type":"applyLook","lookUuid":"$look"}""" to Binding(Does.Look, Subject.Record(RecordKind.LOOK, look)),
        """{"type":"lookSet","lookUuid":"$look","on":true}""" to Binding(Does.Look, Subject.Record(RecordKind.LOOK, look), How.ON),
        """{"type":"pressPalette","paletteUuid":"$palette"}""" to Binding(Does.Palette(palette), Subject.Selection),
        """{"type":"pressPad","padUuid":"$pad"}""" to Binding(Does.Pad, Subject.Record(RecordKind.PAD, pad)),
        """{"type":"pickUpPad","padUuid":"$pad"}""" to Binding(Does.PickUp, Subject.Record(RecordKind.PAD, pad)),
        """{"type":"handPlaceInBank","bankUuid":"$bank"}""" to Binding(Does.Place, Subject.Record(RecordKind.BANK, bank)),
        """{"type":"handDrop"}""" to Binding(Does.Drop),
        """{"type":"undo"}""" to Binding(Does.Undo(UndoKind.UNDO)),
        """{"type":"redo"}""" to Binding(Does.Undo(UndoKind.REDO)),
        """{"type":"programmerUndo"}""" to Binding(Does.Undo(UndoKind.PROGRAMMER)),
        """{"type":"buskPageNext"}""" to Binding(Does.BuskPage(1)),
        """{"type":"buskPagePrev"}""" to Binding(Does.BuskPage(-1)),
        """{"type":"buskPageSet","pageUuid":"$page"}""" to Binding(Does.BuskPage(), Subject.Record(RecordKind.PAGE, page)),
        """{"type":"buskFocusSet","windowName":"Desk","focus":"rig"}""" to Binding(Does.WindowFocus("Desk", "rig")),
        """{"type":"buskSheetToggle","windowName":"Desk"}""" to Binding(Does.WindowSheet("Desk")),
        """{"type":"selectionNext"}""" to Binding(Does.SelectionStep(SelectionMove.NEXT)),
        """{"type":"selectionPrev"}""" to Binding(Does.SelectionStep(SelectionMove.PREV)),
        """{"type":"selectionCells","mode":"FIRST_HALF"}""" to Binding(Does.SelectionStep(SelectionMove.of(SubselectMode.FIRST_HALF))),
    )

    @Test
    fun `every old variant becomes its function, subject and How`() {
        for ((json, expected) in table) assertEquals(expected, translate(json), json)
    }

    @Test
    fun `explicit nulls the old screens send read as absent`() {
        assertEquals(
            Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
            translate("""{"type":"fixtureAttribute","fixtureKey":"hex-1","attribute":"dimmer","colourAxis":null}"""),
        )
        assertEquals(Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)), translate("""{"type":"cueStackGo","stackId":7,"stackUuid":null}"""))
    }

    @Test
    fun `what cannot be translated answers nothing, and the row keeps its bytes`() {
        val cases = listOf(
            """{"type":"cueStackPause","stackId":9999}""",
            """{"type":"fireCue","cueId":12}""",
            """{"type":"applyLook","lookUuid":"not-a-uuid"}""",
            """{"type":"futureThing","dial":3}""",
            """{"type":"unknown","targetType":"x","rawPayload":"{}"}""",
            """{"type":"flash","target":{"type":"selectionAttribute","attribute":"dimmer"}}""",
        )
        for (json in cases) assertNull(translate(json), json)
        assertNull(SurfaceGrammar.row("x-touch-compact-standard", "btn-4", """{"type":"cueStackPause","stackId":9999}""", refs))
    }

    @Test
    fun `an archive's ints are another desk's, so they resolve nothing`() {
        assertNull(SurfaceGrammar.binding(old("""{"type":"cueStackGo","stackId":7}"""), SurfaceGrammar.NoInts))
    }

    @Test
    fun `a row is placed by its old variant's input, an encoder's button variant moving to its push`() {
        val device = "x-touch-compact-standard"
        val go = SurfaceGrammar.row(device, "enc-2", """{"type":"cueStackGo","stackId":7}""", refs)!!
        assertEquals(ControlPart.PUSH, go.part)
        assertEquals("go", go.targetType)
        assertEquals(Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)), decodeBinding(go.payload, go.targetType))
        // The grand master toggle was a button there: the push, though the function takes a turn too.
        assertEquals(ControlPart.PUSH, SurfaceGrammar.row(device, "enc-3", """{"type":"grandMasterToggle"}""", refs)!!.part)
        assertEquals(ControlPart.MAIN, SurfaceGrammar.row(device, "enc-1", """{"type":"fixtureAttribute","fixtureKey":"h","attribute":"pan"}""", refs)!!.part)
        assertEquals(ControlPart.MAIN, SurfaceGrammar.row(device, "btn-3", """{"type":"cueStackGo","stackId":7}""", refs)!!.part)
        assertEquals(ControlPart.MAIN, SurfaceGrammar.row(device, "strip-1", """{"type":"strip","target":{"type":"group","key":"g"}}""", refs)!!.part)
    }

    @Test
    fun `a row already in the grammar is left alone`() {
        val payload = Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)).encodePayload()
        assertNull(SurfaceGrammar.row("x-touch-compact-standard", "btn-1", payload, refs))
    }

    @Test
    fun `the old screens read every binding back in the old union's words, and it translates back the same`() {
        for ((_, binding) in table) {
            // The two spellings of the grand master's no-level binding differ only by the part's input.
            for (button in listOf(false, true)) {
                val legacy = SurfaceGrammar.legacyTarget(binding, "x-touch-compact-standard", button, refs)
                assertEquals(binding, SurfaceGrammar.binding(legacy, refs), legacy.toString())
            }
        }
        assertEquals(
            "grandMasterToggle",
            SurfaceGrammar.legacyType(SurfaceGrammar.legacyTarget(Binding(Does.GrandMaster()), "d", true, refs)),
        )
        assertEquals(
            "grandMasterFader",
            SurfaceGrammar.legacyType(SurfaceGrammar.legacyTarget(Binding(Does.GrandMaster()), "d", false, refs)),
        )
        val unknown = SurfaceGrammar.legacyTarget(Binding(Does.Unknown("futureThing", "{}")), "d", true, refs)
        assertEquals("unknown", SurfaceGrammar.legacyType(unknown))
    }
}
