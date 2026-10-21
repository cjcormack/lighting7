package uk.me.cormack.lighting7.fx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerClearTargetInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.routes.ToggleLookRequest
import uk.me.cormack.lighting7.routes.clearProgrammerCompletely
import uk.me.cormack.lighting7.testsupport.EffectTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fading by size (effects plan session 4, §3.5, D1, D13): an effect's envelope follows its cue's holds
 * over the GO's fade, a continuing effect runs on (P4), a cue brought back on mid-fade keeps its
 * instances, and the programmer's press, release, Clear and Blind move the envelope over the
 * programmer fade.
 *
 * Nothing here waits out a fade: an envelope is a ramp on the clock ([EffectEnvelope]), so each test
 * reads the ramp a gesture set and asks it the level at a chosen instant, and sweeps the engine's
 * retirement at a chosen instant ([FxEngine.retireFinishedLeaving]).
 *
 * The rig: hex-1 and hex-2, and two effect-only Looks with a deferred effect each — *Pulse* and
 * *Sine*, both on dimmer — which a cue layers onto a target.
 */
class EffectFadeTest : RouteIntegrationTest() {

    private val manager get() = state.show.cueStackManager
    private val holds get() = manager.holds
    private val engine get() = state.show.fxEngine

    private data class Looks(val pulse: LookDetails, val sine: LookDetails)

    /** A Look with one deferred dimmer effect, and a dimmer row of its own on [rowOn] when named. */
    private suspend fun HttpClient.look(name: String, effectType: String, rowOn: String? = null, nShot: Int? = null): LookDetails =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = rowOn?.let { listOf(LookRowDto("fixture", it, "dimmer", "120")) }.orEmpty(),
                    effects = listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = effectType, category = "dimmer", propertyName = "dimmer",
                            beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR",
                            nShot = nShot,
                        ),
                    ),
                ),
            )
        }.body()

    /**
     * The rig and the two Looks. Also stops the engine's own 50 Hz sweep of leaving instances, so a
     * test retires them only at the instants it names.
     */
    private suspend fun HttpClient.seedLooks(): Looks {
        state.show.fxEngine.sweepsLeaving = false
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 21)
        return Looks(look("Pulse", "Pulse"), look("Sine", "SineWave"))
    }

    /** A cue layering each of [layers] (a Look and the fixture it lands on), with [fadeMs]. */
    private fun cue(name: String, fadeMs: Long?, vararg layers: Pair<LookDetails, String>): Int = transaction(state.database) {
        val cue = DaoCue.new {
            project = DaoProject.findById(projectId)!!
            this.name = name
            fadeDuration = fadeMs?.let { Duration.ofMillis(it) }
        }
        layers.forEachIndexed { i, (look, fixture) ->
            DaoCueLayer.new {
                this.cue = cue
                this.look = DaoLook[look.id]
                targets = listOf(CueTargetDto("fixture", fixture))
                sortOrder = i
            }
        }
        cue.id.value
    }

    /** A stack of [cues] in order; answers the stack and its entries. */
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

    private fun effectsOf(cueId: Int) = engine.getActiveEffects().filter { it.cueId == cueId }

    private fun one(cueId: Int): FxInstance = effectsOf(cueId).single()

    private fun bandEffects() = engine.getActiveEffects().filter { FxEngine.isProgrammerFxPriority(it.priority) }

    /** [instance]'s envelope at [ms] after its ramp began. */
    private fun levelAfter(instance: FxInstance, ms: Long): Double = instance.envelope.let { it.at(it.startMs + ms) }

    /** Held at full, with no fade running and not leaving. */
    private fun assertFull(instance: FxInstance, message: String = "at full") {
        val envelope = instance.envelope
        assertTrue(envelope.isConstant && envelope.to == 1.0 && !envelope.leaving, "$message: $envelope")
    }

    private fun assertNear(expected: Double, actual: Double, message: String) =
        assertTrue(kotlin.math.abs(expected - actual) < 1e-6, "$message: expected $expected, was $actual")

    // ─── A cue's GO ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a GO over 3 s shrinks the outgoing effect and grows the incoming one, both running throughout`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.sine to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)

        manager.activateEntry(state, s, entries[0])
        val outgoing = one(q1)
        assertFull(outgoing, "a first GO snaps")

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        val incoming = one(q2)
        assertSame(outgoing, one(q1), "the outgoing instance is still in the engine")
        assertTrue(outgoing.leaving)
        assertEquals(3000, outgoing.envelope.durationMs)
        assertNear(1.0, levelAfter(outgoing, 0), "outgoing starts at full")
        assertNear(0.5, levelAfter(outgoing, 1500), "outgoing halfway")
        assertNear(0.0, levelAfter(outgoing, 3000), "outgoing ends at nothing")
        assertFalse(incoming.leaving)
        assertNear(0.0, levelAfter(incoming, 0), "incoming starts at nothing")
        assertNear(0.5, levelAfter(incoming, 1500), "incoming halfway")
        assertNear(1.0, levelAfter(incoming, 3000), "incoming ends at full")
        assertTrue(outgoing.isRunning && incoming.isRunning, "both run throughout")

        val start = outgoing.envelope.startMs
        assertEquals(0, engine.retireFinishedLeaving(start + 2999), "nothing goes before the fade ends")
        assertEquals(1, engine.retireFinishedLeaving(start + 3000), "the outgoing goes at 0")
        assertTrue(effectsOf(q1).isEmpty())
        assertSame(incoming, one(q2))
    }

    @Test
    fun `a continuing Look effect keeps its instance, id and phase`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.pulse to "hex-1", looks.sine to "hex-2")
        val (s, entries) = stack("S", 0, q1, q2)

        manager.activateEntry(state, s, entries[0])
        val pulse = one(q1)
        val (id, started) = pulse.id to pulse.startedAtMs

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(effectsOf(q1).isEmpty(), "Q1 has nothing left to fade")
        val q2Effects = effectsOf(q2)
        assertEquals(2, q2Effects.size)
        val continuing = q2Effects.single { it.id == id }
        assertSame(pulse, continuing, "the same instance runs on")
        assertEquals(started, continuing.startedAtMs)
        assertFull(continuing, "and is not faded")
        assertFalse(continuing.leaving)
        assertEquals(cueDerivedPriority(s, 1), continuing.priority, "at the incoming cue's priority")
        val arriving = q2Effects.single { it.id != id }
        assertNear(0.0, levelAfter(arriving, 0), "the Sine arrives from nothing")
        assertTrue(continuing.composeOrder < arriving.composeOrder, "composing in Q2's layer order")
    }

    @Test
    fun `a cue composes in its own layer order however it was reached`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        // The Sine is Q2's first layer, the Pulse it continues from Q1 its second, both on hex-1.
        val q2 = cue("Q2", 3000, looks.sine to "hex-1", looks.pulse to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val pulse = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        val sine = effectsOf(q2).single { it !== pulse }
        assertSame(pulse, effectsOf(q2).single { it === pulse }, "the Pulse continues")
        assertTrue(sine.composeOrder < pulse.composeOrder, "and composes over the Sine, as a cold GO into Q2 would")
    }

    @Test
    fun `a paused effect or an N-shot is not continued`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val looks = client.seedLooks()
        val shot = client.look("Shot", "Pulse", nShot = 3)
        val q1 = cue("Q1", null, looks.pulse to "hex-1", shot to "hex-2")
        val q2 = cue("Q2", 2000, looks.pulse to "hex-1", shot to "hex-2")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val (paused, oneShot) = effectsOf(q1).partition { it.target.targetKey == "hex-1" }.let { it.first.single() to it.second.single() }
        engine.pauseEffect(paused.id)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(paused.leaving && oneShot.leaving, "both leave with Q1")
        val arrived = effectsOf(q2)
        assertEquals(2, arrived.size)
        assertTrue(arrived.none { it === paused || it === oneShot }, "Q2 starts its own")
        assertTrue(arrived.all { it.isRunning })
    }

    @Test
    fun `a 0 s cue snaps its effects`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", null, looks.sine to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)

        manager.activateEntry(state, s, entries[0])
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(effectsOf(q1).isEmpty(), "the outgoing effect is removed at once")
        assertFull(one(q2), "the incoming one is at full")
    }

    @Test
    fun `GO BACK fades as GO does`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", 1000, looks.pulse to "hex-1")
        val q2 = cue("Q2", null, looks.sine to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[1])

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.BACKWARD)

        val leaving = one(q2)
        assertTrue(leaving.leaving, "Q2 shrinks over Q1's fade")
        assertEquals(1000, leaving.envelope.durationMs)
        val arriving = one(q1)
        assertNear(0.0, levelAfter(arriving, 0), "Q1 grows")
        assertEquals(1000, arriving.envelope.durationMs)
    }

    @Test
    fun `an effect-only cue fades, and the run state says so`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 2000, looks.sine to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertFalse(engine.cueLayer.hasAssignments(q2), "no rows on either side")
        assertNotNull(manager.runState.runStateFor(state, s).fadeElapsedMs, "a fade is running")
        assertTrue(one(q1).leaving)
        assertNear(0.0, levelAfter(one(q2), 0), "Q2's effect grows")
    }

    @Test
    fun `a GO onto a cue another stack holds moves no envelope`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 2000, looks.sine to "hex-2")
        val (s, sEntries) = stack("S", 0, q1, q2)
        val (t, tEntries) = stack("T", 1, q2)
        manager.activateEntry(state, t, tEntries[0])
        val held = one(q2)
        manager.activateEntry(state, s, sEntries[0])

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertSame(held, one(q2))
        assertFull(held, "already on stage: nothing moves")
        assertTrue(one(q1).leaving, "the outgoing side still fades")
    }

    @Test
    fun `a stack leaving a cue a press holds leaves its effects at full`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 2000, looks.sine to "hex-2")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        holds.takeLooseCue(state, q1)
        val pressed = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertSame(pressed, one(q1))
        assertFalse(pressed.leaving)
        assertFull(pressed)
    }

    @Test
    fun `a loose press snaps on and off`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q = cue("Q", 2000, looks.pulse to "hex-1")

        holds.takeLooseCue(state, q)
        assertFull(one(q), "a loose hold has no fade")

        holds.dropLoose(state, q)
        assertTrue(effectsOf(q).isEmpty(), "and lets go at once")
    }

    @Test
    fun `a cue brought back on mid-fade keeps its instances and returns to full`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.sine to "hex-2")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val pulse = one(q1)
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        assertTrue(pulse.leaving)

        // A press brings Q1 back while its fade-out runs: a snap arrival.
        holds.takeLooseCue(state, q1)

        assertSame(pulse, one(q1), "kept, not respawned")
        assertFalse(pulse.leaving)
        assertFull(pulse, "back to full at once")
        assertEquals(0, engine.retireFinishedLeaving(System.currentTimeMillis() + 10_000), "and never retired")
    }

    @Test
    fun `a cue taken back by another stack's fade grows from where it stood`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q0 = cue("Q0", null, looks.sine to "hex-2")
        val q1 = cue("Q1", 2000, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.sine to "hex-2")
        val (s, sEntries) = stack("S", 0, q1, q2)
        val (t, tEntries) = stack("T", 1, q0, q1)
        manager.activateEntry(state, s, sEntries[0])
        val pulse = one(q1)
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        val fadingOut = pulse.envelope
        manager.activateEntry(state, t, tEntries[0])

        manager.advanceStack(state, t, CueStackManager.AdvanceDirection.FORWARD)

        assertSame(pulse, one(q1))
        assertFalse(pulse.leaving)
        assertEquals(2000, pulse.envelope.durationMs, "over T's GO into Q1")
        assertNear(1.0, pulse.envelope.to, "back to full")
        val reached = fadingOut.at(pulse.envelope.startMs)
        assertTrue(kotlin.math.abs(pulse.envelope.from - reached) < 0.02, "from where its fade-out had reached ($reached), ${pulse.envelope.from}")
    }

    @Test
    fun `a GO BACK on the same stack mid-fade keeps the returning cue's instances`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", 1000, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.sine to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val pulse = one(q1)
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        assertTrue(pulse.leaving)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.BACKWARD)

        assertSame(pulse, one(q1), "kept, not respawned")
        assertFalse(pulse.leaving)
        assertEquals(1000, pulse.envelope.durationMs, "growing back over Q1's fade")
        assertTrue(pulse.envelope.from > 0.5, "from where it stood, ${pulse.envelope.from}")
    }

    @Test
    fun `a cue taken back gets back the effects the next cue took over, as fresh instances`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1", looks.sine to "hex-2")
        val q2 = cue("Q2", 3000, looks.pulse to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val (pulse, sine) = effectsOf(q1).partition { it.target.targetKey == "hex-1" }.let { it.first.single() to it.second.single() }
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        assertSame(pulse, one(q2), "Q2 took the Pulse over")

        holds.takeLooseCue(state, q1)

        val back = effectsOf(q1)
        assertEquals(2, back.size, "Q1 runs both its effects again")
        assertTrue(back.any { it === sine }, "its own Sine kept")
        assertTrue(back.single { it.target.targetKey == "hex-1" } !== pulse, "and a Pulse of its own")
        assertSame(pulse, one(q2), "while Q2 keeps the one it runs")
    }

    @Test
    fun `adoptions go back when the outgoing cue turns out to be held`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", null, looks.pulse to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val pulse = one(q1)
        val data = transaction(state.database) { buildCueGoData(DaoCue[q2], DaoCueStackEntry[entries[1]]) }

        // A GO's two halves with a press landing between them.
        val acquired = holds.acquire(
            state, data, CueHold.Stack(s, entries[1]), cueDerivedPriority(data), CueHolds.Arrival.SNAP,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), fireEvents = false, stackTag = s, adoptFrom = q1,
        )
        assertSame(pulse, one(q2))
        holds.takeLooseCue(state, q1)
        assertFalse(holds.release(state, q1, CueHold.Stack(s, entries[0]), CueHolds.Departure.SNAP).last)

        holds.giveBack(state, acquired.adopted, respawnFor = q2)

        assertSame(pulse, one(q1), "Q1, still on, has its Pulse back")
        assertEquals(holds.priorityOf(q1), pulse.priority)
        assertTrue(one(q2) !== pulse, "and Q2 runs one of its own")
    }

    // ─── Effect runs (stack-timeline plan D7, D8) ────────────────────────────────────────────

    /** An immediate ad-hoc dimmer effect on [fixture] in [cueId], in run [run]; answers its row's uuid. */
    private fun adHoc(
        cueId: Int,
        fixture: String,
        run: UUID?,
        division: Double = 1.0,
        seconds: Boolean = false,
        nShot: Int? = null,
        sort: Int = 0,
        order: String = "LINEAR",
        wings: Int = 1,
    ): UUID = transaction(state.database) {
        DaoCueAdHocEffect.new {
            cue = DaoCue[cueId]
            targetType = "fixture"; targetKey = fixture
            effectType = "Pulse"; category = "dimmer"; propertyName = "dimmer"
            beatDivision = division; blendMode = "OVERRIDE"; distribution = order
            parameters = emptyMap()
            this.wings = wings
            this.seconds = seconds
            this.nShot = nShot
            sortOrder = sort
            runUuid = run
        }.uuid
    }

    /** A stack of [cues] with its first entry live. */
    private fun runStack(vararg cues: Int): Pair<Int, List<Int>> {
        val (s, entries) = stack("S", 0, *cues)
        manager.activateEntry(state, s, entries[0])
        return s to entries
    }

    @Test
    fun `an effect run is handed on with its id and phase`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run) }
        val q2 = cue("Q2", 3000)
        val row2 = adHoc(q2, "hex-1", run)
        val (s, _) = runStack(q1, q2)
        val pulse = one(q1)
        val (id, started) = pulse.id to pulse.startedAtMs

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(effectsOf(q1).isEmpty(), "Q1 has nothing left to fade")
        assertSame(pulse, one(q2), "the same instance runs on")
        assertEquals(id, pulse.id)
        assertEquals(started, pulse.startedAtMs)
        assertFull(pulse, "and is not faded")
        assertEquals(row2, pulse.layerEffectUuid, "stamped with Q2's row")
        assertEquals(run, pulse.runUuid)
        assertEquals(cueDerivedPriority(s, 1), pulse.priority)
    }

    @Test
    fun `a new speed inside a run is an edit in place at the GO`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run) }
        val q2 = cue("Q2", 3000).also { adHoc(it, "hex-1", run, division = 0.5) }
        val (s, _) = runStack(q1, q2)
        val pulse = one(q1)
        val (id, started) = pulse.id to pulse.startedAtMs

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(effectsOf(q1).isEmpty())
        val running = one(q2)
        assertEquals(id, running.id, "the same effect runs on")
        assertEquals(started, running.startedAtMs, "phase kept")
        assertEquals(0.5, running.timing.beatDivision, "at Q2's speed from the GO")
        assertFull(running, "with no fade of its own")
    }

    @Test
    fun `a run moving onto Seconds restarts its phase`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run) }
        val q2 = cue("Q2", 3000).also { adHoc(it, "hex-1", run, division = 2.0, seconds = true) }
        val (s, _) = runStack(q1, q2)
        val id = one(q1).id

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        val running = one(q2)
        assertEquals(id, running.id, "edited in place")
        assertTrue(running.onSeconds)
        assertEquals(TimingSource.WALL_CLOCK, running.timingSource, "on the wall-clock loop")
        assertTrue(running.accumulatedScaledMs < 1000.0, "near the top of its 2 s cycle: ${running.accumulatedScaledMs}")
    }

    @Test
    fun `different run keys fade one effect out and the other in`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", UUID.randomUUID()) }
        val q2 = cue("Q2", 3000).also { adHoc(it, "hex-1", UUID.randomUUID()) }
        val (s, _) = runStack(q1, q2)
        val outgoing = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(outgoing.leaving, "Q1's shrinks over the fade")
        val incoming = one(q2)
        assertTrue(incoming !== outgoing)
        assertNear(0.0, levelAfter(incoming, 0), "Q2's grows from nothing")
    }

    @Test
    fun `an effect with no run key restarts at every GO, as before`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", null) }
        val q2 = cue("Q2", 3000).also { adHoc(it, "hex-1", null) }
        val (s, _) = runStack(q1, q2)
        val outgoing = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(outgoing.leaving)
        val incoming = one(q2)
        assertTrue(incoming !== outgoing && incoming.id != outgoing.id)
        assertNear(0.0, levelAfter(incoming, 0), "grows from nothing")
    }

    @Test
    fun `a paused effect or an N-shot is not handed on`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val (run, shotRun) = UUID.randomUUID() to UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run); adHoc(it, "hex-2", shotRun, nShot = 3, sort = 1) }
        val q2 = cue("Q2", 2000).also { adHoc(it, "hex-1", run); adHoc(it, "hex-2", shotRun, nShot = 3, sort = 1) }
        val (s, _) = runStack(q1, q2)
        val (paused, oneShot) = effectsOf(q1).partition { it.target.targetKey == "hex-1" }.let { it.first.single() to it.second.single() }
        engine.pauseEffect(paused.id)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(paused.leaving && oneShot.leaving, "both leave with Q1")
        val arrived = effectsOf(q2)
        assertEquals(2, arrived.size)
        assertTrue(arrived.none { it.id == paused.id || it.id == oneShot.id }, "Q2 starts its own")
    }

    @Test
    fun `an N-shot row in a run starts afresh rather than taking the run over`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run) }
        val q2 = cue("Q2", 2000).also { adHoc(it, "hex-1", run, nShot = 3) }
        val (s, _) = runStack(q1, q2)
        val forever = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(forever.leaving, "Q1's leaves over the fade")
        assertEquals(null, forever.dynamics.nShot, "untouched")
        val accent = one(q2)
        assertTrue(accent.id != forever.id)
        assertEquals(3, accent.dynamics.nShot)
        assertNear(0.0, levelAfter(accent, 0), "the accent grows from nothing")
    }

    @Test
    fun `GO BACK hands a run back with its own settings`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", 1000)
        val row1 = adHoc(q1, "hex-1", run)
        val q2 = cue("Q2", 1000).also { adHoc(it, "hex-1", run, division = 0.5) }
        val (s, _) = runStack(q1, q2)
        val (id, started) = one(q1).let { it.id to it.startedAtMs }
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        assertEquals(id, one(q2).id)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.BACKWARD)

        assertTrue(effectsOf(q2).isEmpty())
        val back = one(q1)
        assertEquals(id, back.id, "the same effect runs on")
        assertEquals(started, back.startedAtMs)
        assertEquals(1.0, back.timing.beatDivision, "at Q1's speed again")
        assertEquals(row1, back.layerEffectUuid)
    }

    @Test
    fun `a press between the GO's two holds gives a handed-on run back as it was`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null)
        val row1 = adHoc(q1, "hex-1", run)
        val q2 = cue("Q2", null)
        val row2 = adHoc(q2, "hex-1", run, division = 0.5)
        val (s, entries) = runStack(q1, q2)
        val id = one(q1).id
        val data = transaction(state.database) { buildCueGoData(DaoCue[q2], DaoCueStackEntry[entries[1]]) }

        val acquired = holds.acquire(
            state, data, CueHold.Stack(s, entries[1]), cueDerivedPriority(data), CueHolds.Arrival.SNAP,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), fireEvents = false, stackTag = s, adoptFrom = q1,
        )
        assertEquals(id, one(q2).id, "handed on")
        assertEquals(0.5, one(q2).timing.beatDivision)
        assertEquals(row2, one(q2).layerEffectUuid)
        holds.takeLooseCue(state, q1)
        assertFalse(holds.release(state, q1, CueHold.Stack(s, entries[0]), CueHolds.Departure.SNAP).last)

        holds.giveBack(state, acquired.adopted, respawnFor = q2)

        val back = one(q1)
        assertEquals(id, back.id, "Q1, still on, has its effect back")
        assertEquals(1.0, back.timing.beatDivision, "at its own speed")
        assertEquals(row1, back.layerEffectUuid, "stamped with its own row")
        assertEquals(holds.priorityOf(q1), back.priority)
        val own = one(q2)
        assertTrue(own.id != id, "and Q2 runs one of its own")
        assertEquals(0.5, own.timing.beatDivision)
        assertEquals(row2, own.layerEffectUuid)
    }

    @Test
    fun `a run that cannot be edited back marks the cue it returns to`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        // Wings under Centre out spawn (the stored row is read leniently) but are refused by an edit
        // that moves the Order back onto it.
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run, order = "CENTER_OUT", wings = 2) }
        val q2 = cue("Q2", null).also { adHoc(it, "hex-1", run, wings = 2) }
        val (s, entries) = runStack(q1, q2)
        val id = one(q1).id
        val data = transaction(state.database) { buildCueGoData(DaoCue[q2], DaoCueStackEntry[entries[1]]) }
        val acquired = holds.acquire(
            state, data, CueHold.Stack(s, entries[1]), cueDerivedPriority(data), CueHolds.Arrival.SNAP,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), fireEvents = false, stackTag = s, adoptFrom = q1,
        )
        assertEquals(id, one(q2).id, "handed on")
        holds.takeLooseCue(state, q1)
        holds.release(state, q1, CueHold.Stack(s, entries[0]), CueHolds.Departure.SNAP)

        holds.giveBack(state, acquired.adopted, respawnFor = q2)

        assertEquals(id, one(q1).id, "Q1 has its effect back")
        assertEquals(DistributionStrategy.LINEAR, one(q1).distributionStrategy, "still as Q2 had it")
        assertTrue(holds.isEdited(q1), "so Q1 reads edited since GO")
    }

    @Test
    fun `a run key on two rows hands one instance to one row`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run); adHoc(it, "hex-1", run, sort = 1) }
        val q2 = cue("Q2", 2000).also { adHoc(it, "hex-1", run); adHoc(it, "hex-1", run, sort = 1); adHoc(it, "hex-1", run, sort = 2) }
        val (s, _) = runStack(q1, q2)
        val outgoing = effectsOf(q1).map { it.id }.toSet()
        assertEquals(2, outgoing.size)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(effectsOf(q1).isEmpty(), "both of Q1's are taken")
        val arrived = effectsOf(q2)
        assertEquals(3, arrived.size)
        assertEquals(3, arrived.map { it.id }.toSet().size, "no instance runs twice")
        assertEquals(outgoing, arrived.filter { it.id in outgoing }.map { it.id }.toSet())
        assertEquals(3, arrived.map { it.layerEffectUuid }.toSet().size, "each row has its own")
        assertNear(0.0, levelAfter(arrived.single { it.id !in outgoing }, 0), "the third grows from nothing")
    }

    @Test
    fun `Update stage on a cue a run was handed to reconciles by its row`() = testApplication {
        mountTestApp(state)
        jsonClient().seedLooks()
        val run = UUID.randomUUID()
        val q1 = cue("Q1", null).also { adHoc(it, "hex-1", run) }
        val q2 = cue("Q2", null)
        val row2 = adHoc(q2, "hex-1", run)
        val (s, _) = runStack(q1, q2)
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        val running = one(q2)
        transaction(state.database) { DaoCue[q2].adHocEffects.single { it.uuid == row2 }.beatDivision = 0.25 }

        val answer = assertNotNull(uk.me.cormack.lighting7.routes.updateStage(state, q2, dryRun = false))

        assertEquals(uk.me.cormack.lighting7.routes.UpdateStageEffects(edited = 1, stopped = 0, started = 0), answer.effects)
        val after = one(q2)
        assertEquals(running.id, after.id, "the handed-on instance is Q2's own row's")
        assertEquals(0.25, after.timing.beatDivision)
    }

    @Test
    fun `a GO cut short removes the abandoned outgoing effects and pins the incoming at full`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.sine to "hex-1")
        val q3 = cue("Q3", 3000, looks.pulse to "hex-2")
        val (s, entries) = stack("S", 0, q1, q2, q3)
        manager.activateEntry(state, s, entries[0])
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)
        val sine = one(q2)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertTrue(effectsOf(q1).isEmpty(), "Q1's fade was abandoned: its effect is gone")
        assertSame(sine, one(q2))
        assertTrue(sine.leaving, "Q2 now leaves")
        assertNear(1.0, levelAfter(sine, 0), "from full, pinned there by the cancel")
    }

    @Test
    fun `a stack stop snaps its effects`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 3000, looks.sine to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        manager.deactivateStack(s, state)

        assertTrue(effectsOf(q1).isEmpty() && effectsOf(q2).isEmpty())
    }

    @Test
    fun `a cue's timed ad-hoc effects are not faded but its immediate ones are`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null)
        transaction(state.database) {
            DaoCueAdHocEffect.new {
                cue = DaoCue[q1]
                targetType = "fixture"; targetKey = "hex-2"
                effectType = "SineWave"; category = "dimmer"; propertyName = "dimmer"
                beatDivision = 1.0; blendMode = "OVERRIDE"; distribution = "LINEAR"
                parameters = emptyMap()
            }
        }
        val q2 = cue("Q2", 1000, looks.pulse to "hex-1")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val adHoc = one(q1)

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertSame(adHoc, one(q1))
        assertTrue(adHoc.leaving, "an ad-hoc effect shrinks like a layer's")
        assertNotNull(adHoc.layerEffectUuid, "matched by its own row when Q1 is taken back")
    }

    // ─── The programmer ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a pad press at a 2 s programmer fade grows the effect, and a release shrinks it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val looks = client.seedLooks()
        suspend fun press() = client.post("/api/rest/projects/$projectId/looks/${looks.pulse.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1")), fadeMs = 2000))
        }

        assertEquals(HttpStatusCode.OK, press().status)
        val effect = bandEffects().single()
        assertEquals(2000, effect.envelope.durationMs)
        assertNear(0.0, levelAfter(effect, 0), "it grows from nothing")
        assertNear(1.0, levelAfter(effect, 2000), "to full")

        assertEquals(HttpStatusCode.OK, press().status)
        assertSame(effect, bandEffects().single(), "the release leaves it to shrink")
        assertTrue(effect.leaving)
        assertTrue(engine.programmerLayerEffects().isEmpty(), "and it is no longer its layer's")
        assertEquals(1, engine.retireFinishedLeaving(effect.envelope.startMs + 2000))
        assertTrue(bandEffects().isEmpty())
    }

    @Test
    fun `a press over a value it moves lands its effect at full, and its release stops it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.seedLooks()
        val washPulse = client.look("Wash pulse", "Pulse", rowOn = "hex-1")
        val source = LayerSource.look(washPulse.id, UUID.fromString(washPulse.uuid), washPulse.name)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        state.show.programmerLayerStack.toggle(source, targets, fadeMs = 2000)
        val effect = bandEffects().single()
        assertFull(effect, "its own row's value would snap under a growing effect")

        state.show.programmerLayerStack.toggle(source, targets, fadeMs = 2000)
        assertTrue(bandEffects().isEmpty(), "stopped, so the value ramps")
    }

    @Test
    fun `a press with no fade snaps, as before`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val source = LayerSource.look(looks.pulse.id, UUID.fromString(looks.pulse.uuid), looks.pulse.name)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        state.show.programmerLayerStack.toggle(source, targets)
        assertFull(bandEffects().single())

        state.show.programmerLayerStack.toggle(source, targets)
        assertTrue(bandEffects().isEmpty(), "and comes off at once")
    }

    @Test
    fun `Clear at a fade shrinks the band, and a Release shrinks its local effects`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val source = LayerSource.look(looks.pulse.id, UUID.fromString(looks.pulse.uuid), looks.pulse.name)
        state.show.programmerLayerStack.toggle(source, listOf(CueTargetDto("fixture", "hex-1")))
        val layerEffect = bandEffects().single()

        clearProgrammerCompletely(state, fadeMs = 1500)

        assertTrue(layerEffect.leaving, "Clear lets it go over its fade")
        assertEquals(1500, layerEffect.envelope.durationMs)

        // A local effect: the programmer's own, on hex-2.
        val local = EffectTestSupport.start(state, "SineWave", "hex-2", "dimmer", programmerBand = true)
        assertTrue(local.isLocalEffect)

        val answer = ProgrammerHandler.clearTarget(
            state, ProgrammerClearTargetInMessage("fixture", "hex-2", fadeMs = 800, requestId = "r"),
        )

        assertEquals(1, answer.effects)
        assertTrue(local.leaving, "Release lets it go over the programmer fade")
        assertFalse(local.isLocalEffect, "and it is no longer the operator's")
        assertEquals(800, local.envelope.durationMs)
    }

    @Test
    fun `Clear at a fade stops a band effect over a programmer value`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val source = LayerSource.look(looks.pulse.id, UUID.fromString(looks.pulse.uuid), looks.pulse.name)
        ProgrammerHandler.setTyped(state, TargetRef.Fixture("hex-1"), "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u), fadeMs = 0)
        state.show.programmerLayerStack.toggle(source, listOf(CueTargetDto("fixture", "hex-1")))

        clearProgrammerCompletely(state, fadeMs = 1500)

        assertTrue(bandEffects().isEmpty(), "stopped, so the 200 under it ramps out")
    }

    @Test
    fun `leaving Blind grows the programmer's effects over the Blind fade`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val source = LayerSource.look(looks.pulse.id, UUID.fromString(looks.pulse.uuid), looks.pulse.name)
        engine.programmer.setBlind(true)
        state.show.programmerLayerStack.toggle(source, listOf(CueTargetDto("fixture", "hex-1")))
        val effect = bandEffects().single()
        assertFull(effect, "held, not faded, while blind")

        engine.programmer.setBlind(false, fadeMs = 1200)

        assertEquals(1200, effect.envelope.durationMs)
        assertNear(0.0, levelAfter(effect, 0), "grows from nothing")
        assertNear(1.0, levelAfter(effect, 1200), "to full")
    }

    @Test
    fun `leaving Blind lands an effect over a programmer value at full`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val source = LayerSource.look(looks.pulse.id, UUID.fromString(looks.pulse.uuid), looks.pulse.name)
        ProgrammerHandler.setTyped(state, TargetRef.Fixture("hex-1"), "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u), fadeMs = 0)
        engine.programmer.setBlind(true)
        state.show.programmerLayerStack.toggle(source, listOf(CueTargetDto("fixture", "hex-1")))
        val effect = bandEffects().single()

        engine.programmer.setBlind(false, fadeMs = 1200)

        assertFull(effect, "the staged 200 under it would snap")
    }

    // ─── The envelope itself ─────────────────────────────────────────────────────────────────

    @Test
    fun `an instance the engine retired cannot be taken back`() = testApplication {
        mountTestApp(state)
        val looks = jsonClient().seedLooks()
        val q1 = cue("Q1", null, looks.pulse to "hex-1")
        val q2 = cue("Q2", 1000, looks.sine to "hex-2")
        val (s, entries) = stack("S", 0, q1, q2)
        manager.activateEntry(state, s, entries[0])
        val pulse = one(q1)
        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.FORWARD)

        assertEquals(1, engine.retireFinishedLeaving(pulse.envelope.startMs + 1000))

        assertSame(EffectEnvelope.RETIRED, pulse.envelope)
        assertTrue(engine.reclaimEffects(listOf(pulse)).isEmpty(), "a retired instance stays gone")
        holds.takeLooseCue(state, q1)
        val fresh = one(q1)
        assertTrue(fresh !== pulse, "the cue spawns afresh")
        assertFull(fresh)
    }

    @Test
    fun `an envelope ramps on its curve and lands on its target`() {
        val ramp = EffectEnvelope.ramp(0.2, 1.0, 1000, uk.me.cormack.lighting7.dmx.EasingCurve.LINEAR, nowMs = 5000)
        assertNear(0.2, ramp.at(4000), "before it starts")
        assertNear(0.6, ramp.at(5500), "halfway")
        assertNear(1.0, ramp.at(9000), "after it ends")
        assertFalse(ramp.finished(5999))
        assertTrue(ramp.finished(6000))

        val snap = EffectEnvelope.ramp(0.2, 0.0, 0, uk.me.cormack.lighting7.dmx.EasingCurve.LINEAR, leaving = true)
        assertTrue(snap.isConstant && snap.spentAt(0), "a 0 ms fade lands on its target at once")
        assertFalse(EffectEnvelope.constant(0.0).spentAt(0), "only a leaving envelope is ever spent")
    }
}
