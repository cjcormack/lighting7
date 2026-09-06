package uk.me.cormack.lighting7.midi

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * D11 of `docs/plans/midi-surface-plan.md`: a binding row whose payload this build cannot decode
 * loads as a dead, rebindable [BindingTarget.Unknown] and the rest of the project loads with it.
 * Also the v11 uuid fill: a cue binding created by int is stored with the cue's uuid beside it.
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
    fun `an unknown discriminator loads as UnknownTarget and the other rows load with it`() {
        val good = row("btn-1", "blackout", """{"type":"blackout"}""")
        val future = row("btn-2", "fromTheFuture", """{"type":"fromTheFuture","padUuid":"x"}""")
        val malformed = row("btn-3", "flash", """{"type":"flash","target":{"type":"blackout"}}""")
        val service = state.controlSurfaceBindingService
        service.invalidate(projectId)

        val listed = service.list(projectId).associateBy { it.id }
        assertEquals(setOf(good, future, malformed), listed.keys)
        assertEquals(AssignmentHealth.Ok, listed.getValue(good).health)

        val unknown = assertIs<BindingTarget.Unknown>(listed.getValue(future).target)
        assertEquals("fromTheFuture", unknown.targetType)
        assertEquals("""{"type":"fromTheFuture","padUuid":"x"}""", unknown.rawPayload)
        assertEquals(AssignmentHealth.UnknownTarget("fromTheFuture"), listed.getValue(future).health)
        assertEquals("fromTheFuture", listed.getValue(future).target.discriminator())
        assertEquals(AssignmentHealth.UnknownTarget("flash"), listed.getValue(malformed).health)

        // The row is resolvable (so the router reaches the health gate and drops it there) and
        // an unrelated edit leaves its bytes alone.
        assertNotNull(service.resolve(projectId, deviceTypeKey, "btn-2", activeBank = null, encoderBankProperty = "dimmer"))
        service.update(projectId, future, sortOrder = 9)
        val stored = transaction(state.database) { DaoControlSurfaceBinding.findById(future)!!.targetPayload }
        assertEquals("""{"type":"fromTheFuture","padUuid":"x"}""", stored)
    }

    @Test
    fun `a client cannot create or set an Unknown target`() {
        val service = state.controlSurfaceBindingService
        assertFailsWith<IllegalArgumentException> {
            service.create(projectId, deviceTypeKey, "btn-1", null, BindingTarget.Unknown("x", "{}"))
        }
        val ok = service.create(projectId, deviceTypeKey, "btn-1", null, BindingTarget.ClearSelection)
        assertFailsWith<IllegalArgumentException> {
            service.update(projectId, ok.id, target = BindingTarget.Unknown("x", "{}"))
        }
    }

    @Test
    fun `a cue or stack binding created by int is stored with the uuid beside it`() {
        val (cueId, cueUuid, stackId, stackUuid) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project; name = "s"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project; name = "c"; cueStack = stack; sortOrder = 0; cueType = CueType.STANDARD.name
            }
            listOf(cue.id.value.toString(), cue.uuid.toString(), stack.id.value.toString(), stack.uuid.toString())
        }
        val service = state.controlSurfaceBindingService
        val fire = service.create(projectId, deviceTypeKey, "btn-1", null, BindingTarget.FireCue(cueId.toInt()))
        assertEquals(BindingTarget.FireCue(cueId.toInt(), cueUuid), fire.target)
        assertEquals(AssignmentHealth.Ok, fire.health)
        val go = service.create(projectId, deviceTypeKey, "btn-2", null, BindingTarget.CueStackGo(stackId.toInt()))
        assertEquals(BindingTarget.CueStackGo(stackId.toInt(), stackUuid), go.target)
        // An int that resolves to nothing is stored as sent and reads dead, as before.
        val dead = service.create(projectId, deviceTypeKey, "btn-3", null, BindingTarget.FireCue(999_999))
        assertEquals(BindingTarget.FireCue(999_999), dead.target)
        assertIs<AssignmentHealth.MissingCue>(dead.health)
        // Reloading from the DB reads the uuid back.
        service.invalidate(projectId)
        assertEquals(cueUuid, (service.get(projectId, fire.id)!!.target as BindingTarget.FireCue).cueUuid)
        // A select target on a fixture that is not patched is dead, not an error.
        val select = service.create(projectId, deviceTypeKey, "btn-4", null, BindingTarget.SelectTarget(CueTargetDto("fixture", "hex-1")))
        assertIs<AssignmentHealth.MissingFixture>(select.health)
    }
}
