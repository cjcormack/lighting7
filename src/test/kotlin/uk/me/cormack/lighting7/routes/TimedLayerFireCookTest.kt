package uk.me.cormack.lighting7.routes

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals

/**
 * A recurring timed layer re-cooks the whole cue on every fire, and `TimedFireCook` memoises that
 * cook so the repeat fires are free. The memo's one hazard is staleness, and this pins it: a Look
 * edit landing between two fires must survive the next fire rather than being reverted to the
 * cook that was taken when the layer first fired.
 *
 * The interval is [CueTriggerManager.MINIMUM_INTERVAL_MS][uk.me.cormack.lighting7.fx.CueTriggerManager]'s
 * floor, so "wait for the next fire" is a fraction of a second.
 */
class TimedLayerFireCookTest : RouteIntegrationTest() {

    private companion object {
        const val INTERVAL_MS = 100L
        /** Comfortably more than one interval, so a poll that sees no change has really settled. */
        const val SETTLE_MS = 500L
    }

    private fun seedLook(fixtureKey: String, hex: String): UUID = transaction(state.database) {
        val look = DaoLook.new {
            this.project = DaoProject.findById(projectId)!!
            this.name = "Timed Look"
        }
        DaoLookRow.new {
            this.look = look
            targetType = "fixture"; targetKey = fixtureKey
            propertyName = "colour"; value = hex; sortOrder = 0
        }
        look.uuid
    }

    private fun rewriteLookRow(lookUuid: UUID, hex: String) = transaction(state.database) {
        DaoLook.all().single { it.uuid == lookUuid }.rows.forEach { it.value = hex }
    }

    private fun cueColour(fixtureKey: String): String? =
        (state.show.fxEngine.layerResolver
            .currentCueLayerState[CueAssignmentResolver.Key.fixture(fixtureKey, "rgbColour")]
                as? CueAssignmentResolver.PropertyValue.Colour)
            ?.value?.toSerializedString()

    private suspend fun awaitColour(fixtureKey: String, expected: String, whatFor: String) {
        repeat(50) {
            if (cueColour(fixtureKey) == expected) return
            delay(50)
        }
        assertEquals(expected, cueColour(fixtureKey), whatFor)
    }

    @Test
    fun `a look edit between fires is not reverted by the next fire`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val lookUuid = seedLook("hex-1", "#ff8800")

        val (stackId, cueId) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "stack"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project
                name = "timed cue"; cueStack = stack; sortOrder = 0
                cueType = CueType.STANDARD.name
            }
            DaoCueLayer.new {
                this.cue = cue
                look = DaoLook.all().single { it.uuid == lookUuid }
                sortOrder = 0; targets = emptyList()
                intervalMs = INTERVAL_MS
            }
            // A local row so the cue registers Layer 4 at GO. `replaceCueAssignments` — which is
            // what a fire publishes through — skips a cue that has no rows registered yet, so a
            // cue holding *only* a timed layer would never light at all. Not this test's subject.
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                propertyName = "dimmer"; value = "255"; sortOrder = 0
            }
            stack.id.value to cue.id.value
        }

        try {
            state.show.cueStackManager.activateCueInStack(state, stackId, cueId)
            awaitColour("hex-1", "#ff8800", "the timed layer fired and published its Look")

            // Several more fires, all served from the memo: the published rows must not drift.
            delay(SETTLE_MS)
            assertEquals("#ff8800", cueColour("hex-1"), "repeat fires re-state the same rows")

            rewriteLookRow(lookUuid, "#0000ff")
            republishForLookEdit(state, lookUuid)
            assertEquals("#0000ff", cueColour("hex-1"), "the edit reached the live cue")

            // The regression this test exists for: the next fire re-cooks off a stamp that has
            // moved, rather than replaying the pre-edit memo over the operator's save.
            delay(SETTLE_MS)
            assertEquals("#0000ff", cueColour("hex-1"), "the next fire kept the edit")
        } finally {
            state.cueTriggerManager.deactivateTriggersForCue(cueId)
        }
    }
}
