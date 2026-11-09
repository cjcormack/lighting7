package uk.me.cormack.lighting7.midi

import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The service refuses a binding its function's [Spec] does not admit — the subject, the How, the
 * input the control part feeds — and places a binding that names no part by its function (D4).
 * Every write comes through the service, so these hold for MIDI Learn's commit as for the routes.
 */
class ControlSurfaceBindingSpecTest : RouteIntegrationTest() {

    private val deviceTypeKey = "x-touch-compact-standard"
    private val stack = Subject.Record(RecordKind.STACK, UUID.fromString("11111111-1111-4111-8111-111111111111"))

    private fun service() = state.controlSurfaceBindingService

    private fun create(controlId: String, binding: Binding, part: ControlPart? = null, bank: String? = null) =
        service().create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = controlId,
            bank = bank, binding = binding, part = part,
        )

    private fun refused(code: String, controlId: String, binding: Binding, part: ControlPart? = null) {
        val e = assertFailsWith<BindingRefused>("${binding.functionId} on $controlId") { create(controlId, binding, part) }
        assertEquals(code, e.code, e.message)
    }

    @Test
    fun `a subject the function does not take is refused, a fan on one fixture or a GO on a cue`() {
        refused(CODE_BINDING_WRONG_SUBJECT, "fader-1", Binding(Does.Fan(FanAxis.PAN), Subject.Fixture("hex-1")))
        refused(CODE_BINDING_WRONG_SUBJECT, "btn-1", Binding(Does.Go, Subject.Record(RecordKind.CUE, stack.uuid)))
        assertEquals(emptyList(), service().list(projectId))

        create("fader-1", Binding(Does.Fan(FanAxis.PAN), Subject.Selection))
        assertEquals(1, service().list(projectId).size)
    }

    @Test
    fun `a How the function does not take is refused`() {
        refused(CODE_BINDING_WRONG_HOW, "btn-1", Binding(Does.Go, stack, How.TOGGLE))
        refused(CODE_BINDING_WRONG_HOW, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"), How.WHILE_HELD))
        assertEquals(emptyList(), service().list(projectId))

        create("btn-1", Binding(Does.Blackout, how = How.ON))
        assertEquals(How.ON, service().list(projectId).single().binding.how)
    }

    @Test
    fun `a part whose input the function does not take is refused`() {
        refused(CODE_BINDING_WRONG_CONTROL_KIND, "enc-1", Binding(Does.Level("dimmer"), Subject.Selection), ControlPart.PUSH)
        refused(CODE_BINDING_WRONG_CONTROL_KIND, "enc-1", Binding(Does.Go, stack), ControlPart.MAIN)
        refused(CODE_BINDING_WRONG_CONTROL_KIND, "fader-1", Binding(Does.Go, stack), ControlPart.PUSH)
        assertEquals(emptyList(), service().list(projectId))
    }

    @Test
    fun `a create naming no part is placed by its function, and both parts of one encoder coexist on a layer`() {
        val push = create("enc-1", Binding(Does.Go, stack), bank = "layer-a")
        val turn = create("enc-1", Binding(Does.Level("dimmer"), Subject.Selection), bank = "layer-a")
        assertEquals(ControlPart.PUSH, push.part)
        assertEquals(ControlPart.MAIN, turn.part)

        service().invalidate(projectId)
        assertEquals(setOf(ControlPart.MAIN, ControlPart.PUSH), service().list(projectId).map { it.part }.toSet())
        fun resolve(part: ControlPart) = service().resolve(
            projectId, deviceTypeKey, "enc-1", activeBank = "layer-a", encoderBank = EncoderBankSelection("dimmer"), part = part,
        )?.id
        assertEquals(turn.id, resolve(ControlPart.MAIN))
        assertEquals(push.id, resolve(ControlPart.PUSH))
    }
}
