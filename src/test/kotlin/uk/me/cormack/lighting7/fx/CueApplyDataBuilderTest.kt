package uk.me.cormack.lighting7.fx

import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.TriggerType
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertIs
import uk.me.cormack.lighting7.routes.applyCue

/**
 * `CueApplyData` has exactly one builder.
 *
 * It had three. `CueStackManager.activateCueInStack` and `AiTools.applyCue` each hand-rolled a
 * near-identical field-by-field construction, and each in turn shipped missing a field the other
 * had: `layers` was added to [buildCueApplyData] alone, leaving every Look layer inert on the
 * **stack GO path** — the primary firing path — while the standalone apply-cue route worked.
 * Caught in review, not by a test; hence this one. See `FU-CUE-APPLYDATA-ONE-BUILDER`.
 *
 * Two guards, because the two failure modes are different: [the field test][
 * `the one builder carries every field a firing path reads`] catches a field the builder itself
 * forgets, and the GO-vs-apply test catches the paths diverging again whatever the cause.
 */
class CueApplyDataBuilderTest : RouteIntegrationTest() {

    /** A bound Look holding one colour row for [fixtureKey]. */
    private fun seedLook(name: String, fixtureKey: String, hex: String): DaoLook =
        transaction(state.database) {
            val look = DaoLook.new {
                this.project = DaoProject.findById(projectId)!!
                this.name = name
            }
            DaoLookRow.new {
                this.look = look
                targetType = "fixture"; targetKey = fixtureKey
                propertyName = "colour"; value = hex; sortOrder = 0
            }
            look
        }

    private fun cueColour(fixtureKey: String): CueAssignmentResolver.PropertyValue.Colour {
        val value = state.show.fxEngine.layerResolver
            .currentCueLayerState[CueAssignmentResolver.Key.fixture(fixtureKey, "rgbColour")]
        return assertIs(value)
    }

    /**
     * Every field set to a **non-default** value, so a field the builder stops populating shows up
     * as a failure rather than as a default that happens to match. Same rule
     * `RichProjectFixture` follows for the sync round-trip.
     */
    @Test
    fun `the one builder carries every field a firing path reads`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val look = seedLook("Warm Amber", "hex-1", "#ff8800")

        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "stack"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val script = DaoScript.new {
                this.project = project
                name = "trigger-script"; script = "// no-op"
                scriptType = ScriptType.FX_APPLICATION
            }
            val cue = DaoCue.new {
                this.project = project
                name = "rich cue"; cueStack = stack; sortOrder = 7
                cueType = CueType.STANDARD.name
                autoAdvance = true
                autoAdvanceDelayMs = 2_500L
                fadeDurationMs = 1_250L
                fadeCurve = "SINE_IN_OUT"
                stomp = true
            }
            DaoCueLayer.new {
                this.cue = cue
                this.look = look
                sortOrder = 3; targets = emptyList()
                delayMs = 400L
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                propertyName = "dimmer"; value = "128"; sortOrder = 0
            }
            DaoCueAdHocEffect.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                effectType = "SineWave"; category = "dimmer"; propertyName = "dimmer"
                beatDivision = 1.0; blendMode = "OVERRIDE"; distribution = "LINEAR"
                parameters = emptyMap()
                delayMs = 750L
            }
            DaoCueTrigger.new {
                this.cue = cue
                triggerType = TriggerType.ACTIVATION
                this.script = script
                intervalMs = 5_000L
                sortOrder = 0
            }
            cue.id.value
        }

        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }

        assertEquals("rich cue", applyData.cueName)
        assertEquals(7, applyData.sortOrder)
        assertEquals(true, applyData.stomp)
        // The four that only `CueStackManager` reads. Leaving them at their defaults here is
        // exactly what forced the second builder into existence.
        assertEquals(true, applyData.autoAdvance)
        assertEquals(2_500L, applyData.autoAdvanceDelayMs)
        assertEquals(1_250L, applyData.fadeDurationMs)
        assertEquals("SINE_IN_OUT", applyData.fadeCurve)

        assertEquals(1, applyData.layers.size, "layers — the field the GO path used to drop")
        assertEquals(look.id.value, applyData.layers.single().source.id)
        assertEquals(400L, applyData.layers.single().delayMs)
        assertEquals(1, applyData.propertyAssignments.size, "rows — the field the AI path dropped")
        assertEquals(1, applyData.triggers.size, "triggers — the other field the AI path dropped")
        assertEquals(5_000L, applyData.triggers.single().intervalMs)
        assertEquals(1, applyData.adHocEffects.size)
        assertEquals(750L, applyData.adHocEffects.single().delayMs, "ad-hoc timing, dropped too")
    }

    /**
     * The behavioural half: a cue whose only colour comes from a Look layer must land the same way
     * whichever path fires it. This is the bug the follow-up was filed for — the GO path composed
     * it with an empty layer stack and put nothing on stage.
     */
    @Test
    fun `a stack GO and a standalone apply publish the same cue layer rows`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val look = seedLook("Warm Amber", "hex-1", "#ff8800")

        val (stackId, cueId) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "stack"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project
                name = "layered cue"; cueStack = stack; sortOrder = 0
                cueType = CueType.STANDARD.name
            }
            DaoCueLayer.new {
                this.cue = cue
                this.look = look
                sortOrder = 0; targets = emptyList()
            }
            stack.id.value to cue.id.value
        }

        state.show.cueStackManager.activateCueInStack(state, stackId, cueId)
        val viaGo = cueColour("hex-1").value.toSerializedString()
        assertEquals("#ff8800", viaGo, "the GO path composed the cue's Look layer")

        state.show.fxEngine.removeCueAssignments(cueId)
        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        applyCue(state, applyData, replaceAll = false)

        assertEquals(viaGo, cueColour("hex-1").value.toSerializedString())
    }
}
