package uk.me.cormack.lighting7.midi

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uk.me.cormack.lighting7.state.SubselectMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The stored binding (surface-setup plan D1, P3): every function round-trips, and every function has a spec. */
class BindingSerializationTest {
    private val stack = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val cue = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val entry = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val look = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val palette = UUID.fromString("55555555-5555-4555-8555-555555555555")
    private val pad = UUID.fromString("66666666-6666-4666-8666-666666666666")
    private val bank = UUID.fromString("77777777-7777-4777-8777-777777777777")
    private val page = UUID.fromString("88888888-8888-4888-8888-888888888888")
    private val master = UUID.fromString("99999999-9999-4999-8999-999999999999")

    /** One binding of every function, each on a subject its spec takes. */
    private val samples: List<Binding> = listOf(
        Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
        Binding(Does.Level("rgbColour", ColourAxis.SATURATION), Subject.Group("front")),
        Binding(Does.Level("pan"), Subject.Selection),
        Binding(Does.Flash("dimmer", percent = 78), Subject.Fixture("hex-1")),
        Binding(Does.Select, Subject.Group("front"), How.REPLACE),
        Binding(Does.Locate, Subject.Selection),
        Binding(Does.Fan(FanAxis.TILT), Subject.Selection),
        Binding(Does.FxSpeed, Subject.Selection),
        Binding(Does.Palette(palette), Subject.Selection),
        Binding(Does.Look, Subject.Record(RecordKind.LOOK, look), How.ON),
        Binding(Does.Cue, Subject.Record(RecordKind.CUE, cue), How.OFF),
        Binding(Does.Pad, Subject.Record(RecordKind.PAD, pad)),
        Binding(Does.PickUp, Subject.Record(RecordKind.PAD, pad)),
        Binding(Does.Place, Subject.Record(RecordKind.BANK, bank)),
        Binding(Does.Drop),
        Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)),
        Binding(Does.Back, Subject.Record(RecordKind.STACK, stack)),
        Binding(Does.Pause, Subject.Record(RecordKind.STACK, stack)),
        Binding(Does.Stop, Subject.Record(RecordKind.STACK, stack)),
        Binding(Does.GoTo, Subject.Record(RecordKind.ENTRY, entry)),
        Binding(Does.Standby, Subject.Record(RecordKind.ENTRY, entry)),
        Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, stack)),
        Binding(Does.Fire("output1"), Subject.Fixture("cannon")),
        Binding(Does.Blackout, how = How.ON),
        Binding(Does.GrandMaster()),
        Binding(Does.GrandMaster(level = 50.0)),
        Binding(Does.Tempo(80.0, 160.0), Subject.Record(RecordKind.MASTER, master)),
        Binding(Does.Tempo(bpm = 120.0)),
        Binding(Does.Tap, Subject.Record(RecordKind.MASTER, master)),
        Binding(Does.TempoScale(2.0)),
        Binding(Does.Layer("layer-b")),
        Binding(Does.EncoderPage("rgbColour", ColourAxis.HUE_FINE)),
        Binding(Does.Undo(UndoKind.PROGRAMMER)),
        Binding(Does.BuskPage(-1)),
        Binding(Does.BuskPage(), Subject.Record(RecordKind.PAGE, page)),
        Binding(Does.WindowFocus("Desk", "pads")),
        Binding(Does.WindowSheet("Desk")),
        Binding(Does.SelectionStep(SelectionMove.of(SubselectMode.ODD))),
        Binding(Does.Strip, Subject.Fixture("hex-1")),
    )

    @Test
    fun `every function has a sample here, so the round trip and spec checks below reach all of them`() {
        val sampled = samples.map { it.does::class }.toSet() + Does.Unknown::class
        val functions = Does::class.sealedSubclasses.toSet()
        assertEquals(functions, sampled, "a new function needs a sample")
    }

    @Test
    fun `every function's spec admits its sample, and takes an input unless it is never dispatched`() {
        for (binding in samples) {
            val spec = binding.does.spec
            assertTrue(binding.on.subjectKind in spec.on, "${binding.functionId} on ${binding.on}")
            binding.how?.let { assertTrue(it in spec.hows, "${binding.functionId} takes ${binding.how}") }
            if (binding.does != Does.Strip) assertTrue(spec.inputs.isNotEmpty(), "${binding.functionId} takes some input")
            spec.defaultHow?.let { assertTrue(ControlKind.BUTTON in spec.inputs, "a default How is a button's") }
        }
    }

    @Test
    fun `every binding round-trips through its payload, its function id the discriminator`() {
        for (binding in samples) {
            val payload = binding.encodePayload()
            val tree = BindingJson.parseToJsonElement(payload) as JsonObject
            assertEquals(binding.functionId, tree["does"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(binding, decodeBinding(payload, binding.functionId))
        }
    }

    @Test
    fun `defaults stay off the wire`() {
        assertEquals(
            """{"does":{"type":"level","attribute":"dimmer"},"on":{"type":"fixture","key":"hex-1"}}""",
            Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")).encodePayload(),
        )
        assertEquals("""{"does":{"type":"drop"}}""", Binding(Does.Drop).encodePayload())
        assertEquals(
            """{"does":{"type":"go"},"on":{"type":"record","kind":"STACK","uuid":"$stack"}}""",
            Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)).encodePayload(),
        )
    }

    @Test
    fun `a payload this build cannot read is kept byte for byte as unknown`() {
        val cases = mapOf(
            "a function this build never knew" to """{"does":{"type":"future"}}""",
            "a malformed record uuid" to """{"does":{"type":"go"},"on":{"type":"record","kind":"STACK","uuid":"nope"}}""",
            "a payload from before the grammar" to """{"type":"cueStackGo","stackId":3}""",
            "a value its function refuses" to """{"does":{"type":"flash","attribute":"dimmer","percent":400}}""",
            "not JSON" to "{{",
        )
        for ((why, payload) in cases) {
            val decoded = decodeBinding(payload, "stored")
            val unknown = assertIs<Does.Unknown>(decoded.does, why)
            assertEquals("stored", unknown.storedType, why)
            assertEquals("stored", decoded.functionId, why)
            assertEquals(payload, decoded.encodePayload(), why)
        }
    }

    @Test
    fun `an encoder's push is a part of its own, placed by the function's input`() {
        val encoder = EncoderDescriptor("enc-1", "Encoder 1", cc = 10, pushNote = 0)
        val noPush = EncoderDescriptor("enc-2", "Encoder 2", cc = 11)
        assertEquals(ControlKind.CONTINUOUS, encoder.inputFor(ControlPart.MAIN))
        assertEquals(ControlKind.BUTTON, encoder.inputFor(ControlPart.PUSH))
        assertEquals(null, noPush.inputFor(ControlPart.PUSH))
        assertEquals(ControlPart.PUSH, inferPart(encoder, Does.Go))
        assertEquals(ControlPart.MAIN, inferPart(encoder, Does.Level("dimmer")))
        assertEquals(ControlPart.MAIN, inferPart(encoder, Does.GrandMaster()), "a function both inputs take is the turn's")
        assertEquals(ControlPart.MAIN, inferPart(noPush, Does.Go))
        assertFalse(ControlKind.BUTTON in Does.Level("dimmer").spec.inputs, "no push writes a level full (D4)")
    }

    @Test
    fun `Learn binds the part touched when the function takes it, else the part it does take`() {
        val encoder = EncoderDescriptor("enc-1", "Encoder 1", cc = 10, pushNote = 0)
        assertEquals(ControlPart.PUSH, learntPart(encoder, ControlPart.MAIN, Does.Go), "a turn names the control; GO sits on its push")
        assertEquals(ControlPart.MAIN, learntPart(encoder, ControlPart.PUSH, Does.Level("dimmer")), "a push names it; a level sits on its turn")
        assertEquals(ControlPart.PUSH, learntPart(encoder, ControlPart.PUSH, Does.GrandMaster()))
        assertEquals(ControlPart.MAIN, learntPart(encoder, ControlPart.MAIN, Does.GrandMaster()))
    }
}
