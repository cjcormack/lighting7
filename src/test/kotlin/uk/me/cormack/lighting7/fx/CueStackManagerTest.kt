package uk.me.cormack.lighting7.fx

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.WindowedSlider
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stack's deliberate stop and its Release at the end (busk-stack plan §3 "Release", D3, D6): it
 * reads stopped at once, and each cue it had on stage fades from where it stood to 0 over the release
 * time, or stays at full where another hold keeps it — never at part weight.
 *
 * One stack, `S`: s1 → C1 (hex-1's dimmer, no fade), s2 → C2 (hex-2's dimmer, its own fade). Weights are
 * read off the cue layer directly, so a timing assertion is about the fade and not about the rig.
 */
class CueStackManagerTest : RouteIntegrationTest() {

    private data class Rig(val stack: Int, val c1: Int, val c2: Int, val s1: Int, val s2: Int)

    private val manager get() = state.show.cueStackManager
    private val cueLayer get() = state.show.fxEngine.cueLayer

    private fun seed(
        releaseMs: Long = 600,
        c2FadeMs: Long? = null,
        releaseAtEnd: Boolean = false,
        followMs: Long? = null,
    ): Rig {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 21)
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "S"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
                releaseTime = Duration.ofMillis(releaseMs)
                this.releaseAtEnd = releaseAtEnd
            }
            fun cue(name: String, fixture: String, fadeMs: Long?) = DaoCue.new {
                this.project = project
                this.name = name
                fadeDuration = fadeMs?.let { Duration.ofMillis(it) }
            }.also { cue ->
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = fixture
                    attribute = "dimmer"; value = "200"; sortOrder = 0
                }
            }
            fun entry(cue: DaoCue, ordinal: Double) = DaoCueStackEntry.new {
                this.stack = stack; this.cue = cue; this.ordinal = ordinal; cueNumber = ordinal.toInt().toString()
                if (followMs != null) {
                    autoAdvance = true
                    autoAdvanceDelay = Duration.ofMillis(followMs)
                }
            }.id.value
            val c1 = cue("C1", "hex-1", null)
            val c2 = cue("C2", "hex-2", c2FadeMs)
            Rig(stack.id.value, c1.id.value, c2.id.value, entry(c1, 1.0), entry(c2, 2.0))
        }
    }

    private fun onStage(cueId: Int) = cueLayer.hasAssignments(cueId)

    private fun weight(cueId: Int) = cueLayer.fadeWeightOf(cueId)

    private fun pause(ms: Long) = runBlocking { delay(ms) }

    @Test
    fun `a stop reads stopped at once and fades its cue out over the release time`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 600)
        manager.activateEntry(state, rig.stack, rig.s1)

        manager.stopStack(state, rig.stack)

        assertFalse(manager.isStackActive(rig.stack), "stopped at once")
        assertNull(manager.runState.runStateFor(state, rig.stack).activeCueId)
        assertFalse(manager.holds.isHeld(rig.c1), "its hold is gone")
        assertTrue(onStage(rig.c1), "but its light is still fading")
        pause(300)
        assertTrue(weight(rig.c1) in 0.15..0.85, "half way through, at ${weight(rig.c1)}")
        pause(600)
        assertFalse(onStage(rig.c1), "and gone once the release ends")
    }

    @Test
    fun `a stop 40 percent into a fade-in falls from where it stood, never from full`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 800, c2FadeMs = 1_000)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.advanceStack(state, rig.stack, CueStackManager.AdvanceDirection.FORWARD)
        pause(400)
        val incoming = weight(rig.c2)
        val outgoing = weight(rig.c1)
        assertTrue(incoming in 0.2..0.7, "C2 part way in, at $incoming")

        manager.stopStack(state, rig.stack)

        assertTrue(weight(rig.c2) <= incoming + 0.05, "C2 starts its release where it stood, not at 1 (${weight(rig.c2)})")
        assertTrue(weight(rig.c1) <= outgoing + 0.05, "and C1, still going out, keeps going out (${weight(rig.c1)})")
        pause(200)
        assertTrue(weight(rig.c2) < incoming, "both are falling")
        pause(900)
        assertFalse(onStage(rig.c2))
        assertFalse(onStage(rig.c1))
    }

    @Test
    fun `a stop while a press holds the cue leaves it at full`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 400)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.holds.takeLooseCue(state, rig.c1)

        manager.stopStack(state, rig.stack)

        assertEquals(1.0, weight(rig.c1))
        pause(600)
        assertTrue(onStage(rig.c1), "the press keeps it")
        assertEquals(1.0, weight(rig.c1))
    }

    @Test
    fun `a stop mid-crossfade pins a pressed incoming cue at full while the outgoing one still fades to 0`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 600, c2FadeMs = 1_000)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.advanceStack(state, rig.stack, CueStackManager.AdvanceDirection.FORWARD)
        pause(300)
        manager.holds.takeLooseCue(state, rig.c2)
        val outgoing = weight(rig.c1)

        manager.stopStack(state, rig.stack)

        assertEquals(1.0, weight(rig.c2), "the press holds C2, so it is not left part-faded")
        assertTrue(weight(rig.c1) <= outgoing + 0.05)
        pause(900)
        assertFalse(onStage(rig.c1), "C1 went out")
        assertEquals(1.0, weight(rig.c2))
    }

    @Test
    fun `a stop mid-crossfade takes the outgoing cue's leaving effects down over the release time too`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 500, c2FadeMs = 10_000)
        manager.activateEntry(state, rig.stack, rig.s1)
        val fxId = state.show.fxEngine.addFx(
            FxInstance(
                effect = WindowedSlider(60u),
                target = SliderTarget("hex-1", "dimmer"),
                timing = FxTiming(cycle = CycleLength.ONE_BEAT),
                blendMode = BlendMode.OVERRIDE,
            ).also { it.cueId = rig.c1; it.cueStackId = rig.stack },
        )
        manager.advanceStack(state, rig.stack, CueStackManager.AdvanceDirection.FORWARD)
        val fx = state.show.fxEngine.getActiveFx().single { it.id == fxId }
        assertTrue(fx.leaving)
        assertEquals(10_000, fx.envelope.durationMs, "going out on the GO's fade")

        manager.stopStack(state, rig.stack)

        assertTrue(fx.leaving)
        assertEquals(500, fx.envelope.durationMs, "and now on the release, with its rows")
    }

    @Test
    fun `arming a cue on a stack released at its end puts it back on deck`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 0, releaseAtEnd = true)
        manager.activateEntry(state, rig.stack, rig.s2)
        assertTrue(manager.go(state, rig.stack)!!.released)
        assertTrue(manager.runState.isReleasedAtEnd(rig.stack))

        manager.runState.setStandby(state, rig.stack, rig.s2)

        assertFalse(manager.runState.runStateFor(state, rig.stack).releasedAtEnd)
    }

    @Test
    fun `a reload carries a stack released at its end`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 0, releaseAtEnd = true)
        manager.activateEntry(state, rig.stack, rig.s2)
        assertTrue(manager.go(state, rig.stack)!!.released)

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }

        assertTrue(manager.runState.isReleasedAtEnd(rig.stack), "still nothing on deck in the reloaded show")
        assertTrue(rig.stack in manager.runState.stacksWithRunState())
    }

    @Test
    fun `a stop on a stack already releasing changes nothing`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 800)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.stopStack(state, rig.stack)
        pause(300)
        val before = weight(rig.c1)

        assertEquals(0, manager.stopStack(state, rig.stack))

        assertTrue(onStage(rig.c1), "the release runs on")
        assertTrue(weight(rig.c1) <= before + 0.02, "from where it was, not restarted")
        pause(800)
        assertFalse(onStage(rig.c1))
    }

    @Test
    fun `a stop removes the stack's own cue-less effects`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.stack, rig.s1)
        val fxId = state.show.fxEngine.addFx(
            FxInstance(
                effect = WindowedSlider(60u),
                target = SliderTarget("hex-1", "dimmer"),
                timing = FxTiming(cycle = CycleLength.ONE_BEAT),
                blendMode = BlendMode.OVERRIDE,
            ).also { it.cueStackId = rig.stack },
        )

        manager.stopStack(state, rig.stack)

        assertTrue(state.show.fxEngine.getActiveFx().none { it.id == fxId })
    }

    @Test
    fun `a GO past the last cue releases a Release stack, answering it released`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 500, releaseAtEnd = true)
        manager.activateEntry(state, rig.stack, rig.s1)
        assertEquals(rig.s2, manager.go(state, rig.stack)!!.entryId)

        val released = manager.go(state, rig.stack)!!

        assertTrue(released.released)
        assertEquals(rig.s2, released.entryId, "where it stood")
        assertFalse(manager.isStackActive(rig.stack))
        val run = manager.runState.runStateFor(state, rig.stack)
        assertTrue(run.releasedAtEnd)
        assertEquals(rig.c1, run.nextCueId, "a start would fire its first cue")
        assertTrue(rig.stack in manager.runState.stacksWithRunState(), "so a window connecting later is told")
        assertTrue(onStage(rig.c2), "fading, not snapped")
        pause(800)
        assertFalse(onStage(rig.c2))

        manager.go(state, rig.stack)
        assertFalse(manager.runState.runStateFor(state, rig.stack).releasedAtEnd, "until its next start")
    }

    @Test
    fun `a Stay stack's GO past the last cue stays on it`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseAtEnd = false)
        manager.activateEntry(state, rig.stack, rig.s2)
        val result = manager.go(state, rig.stack)!!
        assertFalse(result.released)
        assertEquals(rig.s2, manager.getActiveEntryId(rig.stack))
    }

    @Test
    fun `a follow off the last cue releases a Release stack`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 300, releaseAtEnd = true, followMs = 100)
        manager.activateEntry(state, rig.stack, rig.s1)
        pause(450)
        assertFalse(manager.isStackActive(rig.stack), "s1 followed into s2, which followed into the release")
        assertTrue(manager.runState.runStateFor(state, rig.stack).releasedAtEnd)
        pause(500)
        assertFalse(onStage(rig.c2))
    }

    @Test
    fun `a restart mid-release snaps its cue on`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 600)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.stopStack(state, rig.stack)
        pause(250)

        manager.go(state, rig.stack)

        assertEquals(1.0, weight(rig.c1), "a start snaps on")
        pause(600)
        assertEquals(1.0, weight(rig.c1), "and the cancelled release does not take it down")
        assertTrue(onStage(rig.c1))
    }

    @Test
    fun `a release time of 0 snaps`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 0)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.stopStack(state, rig.stack)
        assertFalse(onStage(rig.c1))
    }

    @Test
    fun `deactivateStack still snaps, a release under way included`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 2_000)
        manager.activateEntry(state, rig.stack, rig.s1)
        manager.deactivateStack(rig.stack, state)
        assertFalse(onStage(rig.c1), "a delete's, an undo's and the show's own stop are at once")

        manager.activateEntry(state, rig.stack, rig.s1)
        manager.stopStack(state, rig.stack)
        assertTrue(onStage(rig.c1))
        manager.deactivateStack(rig.stack, state)
        assertFalse(onStage(rig.c1), "and cut a release short")
    }

    @Test
    fun `GO on a stopped stack with no cue entries still throws`() = testApplication {
        mountTestApp(state)
        val empty = transaction(state.database) {
            DaoCueStack.new {
                project = DaoProject.findById(projectId)!!
                name = "Empty"; loop = false; type = CueStackType.STACK.name; sortOrder = 1
            }.id.value
        }
        assertFailsWith<IllegalArgumentException> { manager.go(state, empty) }
    }
}
