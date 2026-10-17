package uk.me.cormack.lighting7.fx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.routes.deleteCueStackRows
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cue stack's effect size (effects plan session 5, D10, P3): `w = size × envelope × stackSize`,
 * the stack being the one whose hold is on top of the cue (§11, question 21), 100 % under a loose
 * hold; kept by a reload, dropped by a project switch.
 *
 * The rig is hex-1 and hex-2, and an effect-only Look with one deferred Pulse on dimmer, which a cue
 * layers onto a fixture.
 */
class StackEffectSizeTest : RouteIntegrationTest() {

    private val manager get() = state.show.cueStackManager
    private val engine get() = state.show.fxEngine

    private suspend fun HttpClient.pulseLook(): LookDetails {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 21)
        return post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Pulse",
                    effects = listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR",
                        ),
                    ),
                ),
            )
        }.body()
    }

    private fun cue(name: String, look: LookDetails, fixture: String, fadeMs: Long? = null): Int = transaction(state.database) {
        val cue = DaoCue.new {
            project = DaoProject.findById(projectId)!!
            this.name = name
            fadeDuration = fadeMs?.let { Duration.ofMillis(it) }
        }
        DaoCueLayer.new {
            this.cue = cue
            this.look = DaoLook[look.id]
            targets = listOf(CueTargetDto("fixture", fixture))
            sortOrder = 0
        }
        cue.id.value
    }

    private fun stack(name: String, sort: Int, vararg cues: Int): Pair<Int, List<Int>> = transaction(state.database) {
        val stack = DaoCueStack.new {
            project = DaoProject.findById(projectId)!!
            this.name = name; loop = false
            type = CueStackType.STACK.name; sortOrder = sort
        }
        stack.id.value to cues.mapIndexed { i, cueId ->
            DaoCueStackEntry.new {
                this.stack = stack; this.cue = DaoCue[cueId]; ordinal = i + 1.0; cueNumber = "${i + 1}"
            }.id.value
        }
    }

    private fun one(cueId: Int): FxInstance = engine.getActiveEffects().single { it.cueId == cueId }

    private fun weight(effect: FxInstance): Double = engine.weightOf(effect, effect.dynamics)

    private fun assertNear(expected: Double, actual: Double, message: String) =
        assertTrue(kotlin.math.abs(expected - actual) < 1e-6, "$message: expected $expected, was $actual")

    @Test
    fun `50 percent halves each of the stack's effects' weight and no other stack's`() = testApplication {
        mountTestApp(state)
        val look = jsonClient().pulseLook()
        val q1 = cue("Q1", look, "hex-1")
        val q2 = cue("Q2", look, "hex-2")
        val (s, sEntries) = stack("S", 0, q1)
        val (t, tEntries) = stack("T", 1, q2)
        manager.activateEntry(state, s, sEntries[0])
        manager.activateEntry(state, t, tEntries[0])

        assertEquals(0.5, manager.setEffectSize(s, 0.5))

        assertNear(0.5, weight(one(q1)), "S's effect is halved")
        assertNear(1.0, weight(one(q2)), "T's is not")
        assertEquals(1.0, manager.effectSizes.of(t))

        assertEquals(0.0, manager.setEffectSize(s, -2.0), "clamped to nothing")
        assertNear(0.0, weight(one(q1)), "and weighs nothing")
        manager.setEffectSize(s, 1.0)
        assertTrue(manager.effectSizes.snapshot().isEmpty(), "full is no entry at all")
    }

    @Test
    fun `a cue two stacks hold is weighed by its top hold's stack, and a press holds it at full`() = testApplication {
        mountTestApp(state)
        val look = jsonClient().pulseLook()
        val q1 = cue("Q1", look, "hex-1")
        val (s, sEntries) = stack("S", 0, q1)
        val (t, tEntries) = stack("T", 1, q1)
        manager.setEffectSize(s, 0.25)
        manager.setEffectSize(t, 0.5)

        manager.activateEntry(state, s, sEntries[0])
        assertNear(0.25, weight(one(q1)), "S alone holds it")

        manager.activateEntry(state, t, tEntries[0])
        assertEquals(s, one(q1).cueStackId, "the instance keeps its first hold's tag")
        assertNear(0.5, weight(one(q1)), "T's hold is on top (a later stack's band), so T weighs it")

        manager.holds.takeLooseCue(state, q1)
        assertNear(1.0, weight(one(q1)), "a press is on top of every stack: full")

        manager.holds.dropLoose(state, q1)
        assertNear(0.5, weight(one(q1)), "let go, T weighs it again")
    }

    @Test
    fun `a leaving effect shrinks from its stack's size`() = testApplication {
        mountTestApp(state)
        val look = jsonClient().pulseLook()
        engine.sweepsLeaving = false
        val q1 = cue("Q1", look, "hex-1")
        val q2 = cue("Q2", look, "hex-2", fadeMs = 3000)
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        manager.setEffectSize(s, 0.5)
        val outgoing = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(outgoing.leaving)
        assertEquals(s, weighingStackOf(outgoing), "it is still its stack's")
        assertEquals(0.5, manager.effectSizes.weightOf(outgoing), "so the stack's size still weighs it as it shrinks")
    }

    @Test
    fun `a reload keeps a stack's size, a project switch drops it, and a stack delete forgets it`() = testApplication {
        mountTestApp(state)
        val look = jsonClient().pulseLook()
        val q1 = cue("Q1", look, "hex-1")
        val (s, entries) = stack("S", 0, q1)
        manager.activateEntry(state, s, entries[0])
        manager.setEffectSize(s, 0.4)

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }
        assertEquals(0.4, manager.effectSizes.of(s), "the reload carried it")
        assertNear(0.4, weight(one(q1)), "and the cue it brought back arrives weighed")

        val other = seedMinimalProject(state, projectName = "Other")
        runBlocking {
            state.projectManager.switchProject(other)
            state.projectManager.switchProject(projectId)
        }
        assertEquals(1.0, manager.effectSizes.of(s), "a switch starts every stack at full")

        manager.setEffectSize(s, 0.3)
        transaction(state.database) {
            deleteCueStackRows(state, DaoProject.findById(projectId)!!, DaoCueStack[s])
        }
        assertTrue(s !in manager.effectSizes.snapshot(), "a deleted stack's size goes with it")
    }
}
