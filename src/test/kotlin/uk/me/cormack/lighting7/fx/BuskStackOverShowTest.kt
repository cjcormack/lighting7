package uk.me.cormack.lighting7.fx

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackOverShow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Over the show* on a live show (busk-stack plan D5, D7, D8): stacks ranked by latest GO in two bands,
 * and a busk stack on Replaces taking the keys its cue sets from the show and handing them back.
 *
 * Two show stacks, `ACT1` and `ACT2`, and a busk stack `CARA`. hex-1's colour is LTP and its dimmer
 * HTP: Act 1's Q14 lights it red at 204, Act 2's Q1 green, Carabossy's Q1 blue at 0.
 */
class BuskStackOverShowTest : RouteIntegrationTest() {

    private data class Rig(
        val act1: Int, val act2: Int, val cara: Int,
        val q14: Int, val q15: Int, val a2q1: Int, val c1: Int, val c2: Int,
        val act1q14: Int, val act1q15: Int, val act2q1: Int, val caraQ1: Int, val caraQ2: Int,
    )

    private val manager get() = state.show.cueStackManager
    private val engine get() = state.show.fxEngine

    private fun seed(replaces: Boolean = true, releaseMs: Long = 600, q15FadeMs: Long? = null, caraFollowMs: Long? = null): Rig {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            fun stack(name: String, sort: Int, inOrder: Boolean = true) = DaoCueStack.new {
                this.project = project
                this.name = name; loop = false; type = CueStackType.STACK.name; sortOrder = sort
                inRunningOrder = inOrder
                releaseTime = Duration.ofMillis(releaseMs)
                if (!inOrder) {
                    releaseAtEnd = true
                    overShow = if (replaces) CueStackOverShow.REPLACES else CueStackOverShow.ADDS
                }
            }
            fun cue(name: String, colour: String, level: Int?, fadeMs: Long? = null) = DaoCue.new {
                this.project = project
                this.name = name
                fadeDuration = fadeMs?.let { Duration.ofMillis(it) }
            }.also { cue ->
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = "hex-1"; attribute = "rgbColour"; value = colour; sortOrder = 0
                }
                if (level != null) DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = "hex-1"; attribute = "dimmer"; value = "$level"; sortOrder = 1
                }
            }
            fun entry(stack: DaoCueStack, cue: DaoCue, ordinal: Double, followMs: Long? = null) = DaoCueStackEntry.new {
                this.stack = stack; this.cue = cue; this.ordinal = ordinal; cueNumber = ordinal.toInt().toString()
                if (followMs != null) {
                    autoAdvance = true
                    autoAdvanceDelay = Duration.ofMillis(followMs)
                }
            }.id.value
            val act1 = stack("ACT1", 0)
            val act2 = stack("ACT2", 1)
            val cara = stack("CARA", 2, inOrder = false)
            val q14 = cue("Q14", "#ff0000", 204)
            val q15 = cue("Q15", "#ffff00", 204, q15FadeMs)
            val a2q1 = cue("A2 Q1", "#00ff00", 204)
            val c1 = cue("Cara Q1", "#0000ff", 0)
            val c2 = cue("Cara Q2", "#0000ff", 0)
            Rig(
                act1.id.value, act2.id.value, cara.id.value,
                q14.id.value, q15.id.value, a2q1.id.value, c1.id.value, c2.id.value,
                entry(act1, q14, 1.0), entry(act1, q15, 2.0), entry(act2, a2q1, 1.0),
                entry(cara, c1, 1.0, caraFollowMs), entry(cara, c2, 2.0),
            )
        }
    }

    private fun value(attribute: String) = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("hex-1", attribute)]

    private fun colour(): String? = (value("rgbColour") as? CueAssignmentResolver.AttributeValue.Colour)?.value?.toSerializedString()?.take(7)

    private fun dimmer(): Int? = (value("dimmer") as? CueAssignmentResolver.AttributeValue.Slider)?.value?.toInt()

    private fun pause(ms: Long) = runBlocking { delay(ms) }

    private fun place(stackId: Int) = manager.holds.ranks.placeOf(stackId)

    // ─── Latest GO wins (D5) ─────────────────────────────────────────────────────────────────

    @Test
    fun `a later operator GO on an older stack overtakes, and a follow does not`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.activateEntry(state, rig.act2, rig.act2q1)
        assertEquals("#00ff00", colour(), "Act 2, GO'd last, wins")

        manager.advanceStack(state, rig.act1, CueStackManager.AdvanceDirection.FORWARD, operator = false)
        assertEquals("#00ff00", colour(), "a follow on Act 1 keeps it under Act 2")
        assertEquals(1, place(rig.act1)?.rank)

        manager.goToEntry(state, rig.act1, rig.act1q14)
        assertEquals("#ff0000", colour(), "an operator GO on Act 1 puts it on top")
        assertEquals(StackRanks.Place(StackRanks.Band.SHOW, 2), place(rig.act1))
        assertEquals(StackRanks.Place(StackRanks.Band.SHOW, 1), place(rig.act2), "and Act 2 closes up beneath")
    }

    @Test
    fun `a busk stack sits above a show GO made after it, and a loose press above both`() = testApplication {
        mountTestApp(state)
        val rig = seed(replaces = false)
        manager.activateEntry(state, rig.cara, rig.caraQ1)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        assertEquals("#0000ff", colour(), "Carabossy's band is above the show's")

        manager.holds.takeLooseCue(state, rig.a2q1)
        assertEquals("#00ff00", colour(), "a loose press is above every stack")
    }

    @Test
    fun `a stopping stack keeps its rank through its release, and the ranks close up when it ends`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 500)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.activateEntry(state, rig.act2, rig.act2q1)

        manager.stopStack(state, rig.act1)
        assertEquals(1, place(rig.act1)?.rank, "releasing, it keeps rank 1")
        assertEquals(2, place(rig.act2)?.rank)
        val fading = engine.cueLayer.publishedPriorityOf(rig.q14)!!
        assertEquals(rig.act1, manager.holds.ranks.stackAt(fading), "its fading rows still name it")

        pause(800)
        assertNull(place(rig.act1), "gone once the release ends")
        assertEquals(1, place(rig.act2)?.rank, "and Act 2 has closed up")
        assertEquals(stackPriority(place(rig.act2), 0), manager.holds.priorityOf(rig.a2q1), "its hold restamped with it")
    }

    @Test
    fun `a GO on another stack mid-crossfade keeps the fading cue below the stack now on top`() = testApplication {
        mountTestApp(state)
        val rig = seed(q15FadeMs = 1_000)
        manager.activateEntry(state, rig.act2, rig.act2q1)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.advanceStack(state, rig.act1, CueStackManager.AdvanceDirection.FORWARD)
        assertEquals(2, place(rig.act1)?.rank)

        manager.goToEntry(state, rig.act2, rig.act2q1)

        assertEquals(1, place(rig.act1)?.rank, "Act 1 under Act 2 now")
        val fadingQ14 = engine.cueLayer.publishedPriorityOf(rig.q14)!!
        val incomingQ15 = engine.cueLayer.publishedPriorityOf(rig.q15)!!
        assertEquals(rig.act1, manager.holds.ranks.stackAt(fadingQ14), "the outgoing Q14 moved down with its stack")
        assertTrue(fadingQ14 < incomingQ15, "still below the cue its own fade brings in")
        assertTrue(incomingQ15 < manager.holds.priorityOf(rig.a2q1)!!, "and both below Act 2")
    }

    @Test
    fun `a reload keeps the order the stacks were last GO'd in`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.act2, rig.act2q1)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        assertEquals("#ff0000", colour())

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }

        assertEquals(2, place(rig.act1)?.rank)
        assertEquals(1, place(rig.act2)?.rank)
        assertEquals("#ff0000", colour(), "Act 1 still on top, whatever order the reload GO'd them in")
    }

    // ─── Replaces (D7, D8) ───────────────────────────────────────────────────────────────────

    @Test
    fun `Replaces takes the wash to 0 and its release hands it back with no jump`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 600)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        assertEquals(204, dimmer())

        manager.activateEntry(state, rig.cara, rig.caraQ1)
        assertEquals(0, dimmer(), "Carabossy's 0 wins outright, intensity included")
        assertEquals("#0000ff", colour())

        manager.stopStack(state, rig.cara)
        pause(300)
        val mid = dimmer()!!
        assertTrue(mid in 30..175, "fading back to the show's value, at $mid")
        pause(600)
        assertEquals(204, dimmer(), "and there once the release ends")
        assertEquals("#ff0000", colour())
    }

    @Test
    fun `Adds leaves the show's intensity highest-takes-precedence`() = testApplication {
        mountTestApp(state)
        val rig = seed(replaces = false)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.activateEntry(state, rig.cara, rig.caraQ1)
        assertEquals(204, dimmer(), "a song's 0 never darkens the wash")
        assertEquals("#0000ff", colour(), "while its colour wins by latest GO")
    }

    @Test
    fun `a follow between two replacing cues that both set 0 stays at 0`() = testApplication {
        mountTestApp(state)
        val rig = seed(caraFollowMs = 50)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.activateEntry(state, rig.cara, rig.caraQ1)
        pause(250)
        assertEquals(rig.caraQ2, manager.getActiveEntryId(rig.cara))
        assertEquals(0, dimmer())
    }

    @Test
    fun `a GO on the show during Carabossy lands under her`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.activateEntry(state, rig.cara, rig.caraQ1)

        manager.goToEntry(state, rig.act1, rig.act1q15)

        assertEquals(0, dimmer(), "still Carabossy's")
        assertEquals("#0000ff", colour(), "on every key she sets")
    }

    @Test
    fun `an FX under a replacing stack is held back by its weight, phase kept, and the programmer's runs`() = testApplication {
        mountTestApp(state)
        val rig = seed(releaseMs = 600)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        fun slider(priority: Int) = FxInstance(
            effect = WindowedSlider(60u),
            target = SliderTarget("hex-1", "dimmer"),
            timing = FxTiming(cycle = CycleLength.ONE_BEAT),
            blendMode = BlendMode.OVERRIDE,
        ).also { it.priority = priority }
        val manual = slider(0)
        val programmer = slider(FxEngine.PROGRAMMER_FX_PRIORITY_BASE)
        engine.addFx(manual)
        engine.addFx(programmer)
        val started = manual.startedAtMs

        manager.activateEntry(state, rig.cara, rig.caraQ1)
        assertEquals(0.0, engine.underReplacing(1.0, manual, "hex-1"), "a manual FX is held back fully")
        assertEquals(1.0, engine.underReplacing(1.0, programmer, "hex-1"), "the programmer's is not")
        assertEquals(
            KeyStackLayer.HeldBackReason.REPLACED,
            FxSuppression.heldBackReason(emptyMap(), false, "hex-1", "dimmer", manual, { _, _, _ -> false },
                engine.layerResolver.replacedWeight(engine.layerResolver.current, "hex-1", "dimmer", manual.priority, manual.cueId)),
        )

        manager.stopStack(state, rig.cara)
        pause(300)
        val handing = engine.underReplacing(1.0, manual, "hex-1")
        assertTrue(handing in 0.1..0.9, "handing back as Carabossy fades, at $handing")
        pause(600)
        assertEquals(1.0, engine.underReplacing(1.0, manual, "hex-1"), "back in full once she is gone")
        assertTrue(engine.getActiveFx().any { it.id == manual.id }, "held back, never removed")
        assertEquals(started, manual.startedAtMs, "its phase kept")
    }

    @Test
    fun `the Next GO preview of a replacing stack lands as its GO does`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.act1, rig.act1q14)
        val controller = state.show.fixtures.controllers.first()
        val dimmerChannel = (1..20).single { controller.getValue(it).toInt() == 204 }

        val preview = uk.me.cormack.lighting7.routes.previewCueLook(state, rig.cara, rig.caraQ1)!!

        assertEquals(0, preview.channels.single { it.channel == dimmerChannel }.value.toInt(), "0, not the show's 204")
    }

    @Test
    fun `an Over the show change on a live stack lands at its next GO`() = testApplication {
        mountTestApp(state)
        val rig = seed(replaces = false)
        manager.activateEntry(state, rig.act1, rig.act1q14)
        manager.activateEntry(state, rig.cara, rig.caraQ1)
        assertEquals(204, dimmer())
        transaction(state.database) { DaoCueStack[rig.cara].overShow = CueStackOverShow.REPLACES }
        assertEquals(204, dimmer(), "nothing moves until its next GO")

        manager.go(state, rig.cara)

        assertEquals(0, dimmer(), "Cara Q2 replaces")
    }
}
