package uk.me.cormack.lighting7.midi

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A stop button, through the dispatch the router and OSC share, is an operator's stop
 * (busk-stack plan D6, D12): the stack reads stopped at once and its cue fades over its release time.
 */
class SurfaceStackStopTest : RouteIntegrationTest() {

    private val manager get() = state.show.cueStackManager
    private val cueLayer get() = state.show.fxEngine.cueLayer

    private val origin = SurfaceDispatch.Origin("x-touch-compact", HistorySource.surface("x-touch-compact"))

    private fun stop(stack: UUID): ActionOutcome =
        SurfaceDispatch(DefaultSurfaceActions(state), state.activeBankState, state.encoderBankState, state.surfaceHolds)
            .dispatch(Binding(Does.Stop, Subject.Record(RecordKind.STACK, stack)), SurfaceDispatch.Input.Press, origin)

    private data class Seeded(val stackId: Int, val stackUuid: UUID, val cueId: Int, val entryId: Int)

    private fun seed(releaseMs: Long): Seeded {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "Song"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
                inRunningOrder = false
                releaseTime = Duration.ofMillis(releaseMs)
            }
            val cue = DaoCue.new { this.project = project; name = "Verse" }
            DaoCueAttributeAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                attribute = "dimmer"; value = "200"; sortOrder = 0
            }
            val entry = DaoCueStackEntry.new { this.stack = stack; this.cue = cue; ordinal = 1.0; cueNumber = "1" }
            Seeded(stack.id.value, stack.uuid, cue.id.value, entry.id.value)
        }
    }

    @Test
    fun `a stop button fades the stack's cue over its release time`() = testApplication {
        mountTestApp(state)
        val song = seed(releaseMs = 600)
        manager.activateEntry(state, song.stackId, song.entryId)

        val outcome = stop(song.stackUuid)

        assertEquals(ActionOutcome.DONE, outcome)
        assertFalse(manager.isStackActive(song.stackId), "stopped at once")
        assertTrue(manager.isReleasing(song.stackId), "and releasing")
        assertTrue(cueLayer.hasAssignments(song.cueId), "its cue still on stage")
        runBlocking { delay(300) }
        val mid = cueLayer.fadeWeightOf(song.cueId)
        assertTrue(mid in 0.15..0.85, "half way through, at $mid")

        assertEquals(ActionOutcome.Done("was not running"), stop(song.stackUuid))
        assertTrue(cueLayer.hasAssignments(song.cueId), "a second press leaves the release running")

        runBlocking { delay(600) }
        assertFalse(cueLayer.hasAssignments(song.cueId), "and gone once the release ends")
    }

    @Test
    fun `a stop button on a stack with a release time of 0 snaps`() = testApplication {
        mountTestApp(state)
        val song = seed(releaseMs = 0)
        manager.activateEntry(state, song.stackId, song.entryId)

        stop(song.stackUuid)

        assertFalse(manager.isStackActive(song.stackId))
        assertFalse(cueLayer.hasAssignments(song.cueId), "off at once")
    }
}
