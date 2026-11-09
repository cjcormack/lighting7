package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.testsupport.inStack
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * D11 of `docs/plans/completed/midi-surface-plan.md`: a binding row whose payload this build cannot decode
 * loads as a dead, rebindable [Does.Unknown] keeping its bytes, and the rest of the project loads
 * with it. A record is named by uuid alone, so one naming nothing reads dead.
 * Needs a real database, which is why it is not in [ControlSurfaceBindingResolverTest].
 */
class ControlSurfaceBindingTolerantDecodeTest : RouteIntegrationTest() {

    private val deviceTypeKey = "x-touch-compact-standard"

    private fun row(controlId: String, targetType: String, payload: String): Int = transaction(state.database) {
        DaoControlSurfaceBinding.new {
            project = DaoProject.findById(projectId)!!
            this.deviceTypeKey = this@ControlSurfaceBindingTolerantDecodeTest.deviceTypeKey
            this.controlId = controlId
            this.targetType = targetType
            targetPayload = payload
            sortOrder = 0
        }.id.value
    }

    @Test
    fun `an unreadable payload loads as UnknownTarget keeping its bytes, and the other rows load with it`() {
        val good = row("btn-1", "blackout", """{"does":{"type":"blackout"}}""")
        val unreadable = mapOf(
            "fromTheFuture" to """{"does":{"type":"fromTheFuture","padUuid":"x"}}""",
            "go" to """{"does":{"type":"go"},"on":{"type":"record","kind":"STACK","uuid":"not-a-uuid"}}""",
            "cueStackGo" to """{"type":"cueStackGo","stackId":3}""",
            "flash" to """{"does":{"type":"flash"}}""",
        ).entries.mapIndexed { i, (type, payload) -> Triple(row("btn-${i + 2}", type, payload), type, payload) }
        val service = state.controlSurfaceBindingService
        service.invalidate(projectId)

        val listed = service.list(projectId).associateBy { it.id }
        assertEquals(setOf(good) + unreadable.map { it.first }, listed.keys)
        assertEquals(Binding(Does.Blackout), listed.getValue(good).binding)
        assertEquals(AssignmentHealth.Ok, listed.getValue(good).health)

        for ((id, type, payload) in unreadable) {
            val binding = listed.getValue(id).binding
            val unknown = assertIs<Does.Unknown>(binding.does, type)
            assertEquals(type, unknown.storedType)
            assertEquals(payload, unknown.rawPayload)
            assertEquals(type, binding.functionId)
            assertEquals(AssignmentHealth.UnknownTarget(type), listed.getValue(id).health)
        }

        // The row is resolvable (so the router reaches the health gate and drops it there) and
        // an unrelated edit leaves its bytes alone.
        val (future, _, futurePayload) = unreadable.first()
        assertNotNull(service.resolve(projectId, deviceTypeKey, "btn-2", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        service.update(projectId, future, sortOrder = 9)
        val stored = transaction(state.database) { DaoControlSurfaceBinding.findById(future)!!.targetPayload }
        assertEquals(futurePayload, stored)
    }

    @Test
    fun `a client cannot create or set an Unknown binding`() {
        val service = state.controlSurfaceBindingService
        assertFailsWith<IllegalArgumentException> {
            service.create(projectId, deviceTypeKey, "btn-1", null, Binding(Does.Unknown("x", "{}")))
        }
        val ok = service.create(projectId, deviceTypeKey, "btn-1", null, Binding(Does.SelectionStep(SelectionMove.CLEAR)))
        assertFailsWith<IllegalArgumentException> {
            service.update(projectId, ok.id, binding = Binding(Does.Unknown("x", "{}")))
        }
    }

    @Test
    fun `a cue or stack binding is stored by its uuid, and one naming nothing reads dead`() {
        val (cueUuid, stackUuid) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project; name = "s"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project; name = "c"
            }.inStack(stack, sortOrder = 0)
            cue.uuid to stack.uuid
        }
        val service = state.controlSurfaceBindingService
        val fire = service.create(projectId, deviceTypeKey, "btn-1", null, Binding(Does.Cue, Subject.Record(RecordKind.CUE, cueUuid)))
        assertEquals(AssignmentHealth.Ok, fire.health)
        val go = service.create(projectId, deviceTypeKey, "btn-2", null, Binding(Does.Go, Subject.Record(RecordKind.STACK, stackUuid)))
        assertEquals(AssignmentHealth.Ok, go.health)
        val nowhere = UUID.randomUUID()
        val dead = service.create(projectId, deviceTypeKey, "btn-3", null, Binding(Does.Cue, Subject.Record(RecordKind.CUE, nowhere)))
        assertEquals(AssignmentHealth.MissingCue(cueUuid = nowhere.toString()), dead.health)
        // Reloading from the DB reads the uuid back.
        service.invalidate(projectId)
        assertEquals(Subject.Record(RecordKind.CUE, cueUuid), service.get(projectId, fire.id)!!.binding.on)
        // A select on a fixture that is not patched is dead, not an error.
        val select = service.create(projectId, deviceTypeKey, "btn-4", null, Binding(Does.Select, Subject.Fixture("hex-1")))
        assertIs<AssignmentHealth.MissingFixture>(select.health)
    }
}
