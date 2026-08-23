package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.LayerSource
import uk.me.cormack.lighting7.fx.LayerSourceKind
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.effects.StaticValue
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level tests for the PROGRAMMER layer's half-(b) behaviours: the blind gate
 * (enter/exit republish, state preservation, writes-while-blind), fades on clear/set
 * (uncovered keys ramp; effect-covered keys settle next tick and snap), and provenance
 * winner transitions across layer events.
 */
class ProgrammerLayerTest {

    private val universe = Universe(0, 0)

    private data class Rig(
        val controller: MockDmxController,
        val fixtures: Fixtures,
        val engine: FxEngine,
        val programmerStore: ProgrammerStore,
    )

    private fun newRig(firstChannel: Int = 1): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", firstChannel))
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
        return Rig(controller, fixtures, engine, programmerStore)
    }

    private fun Rig.hex(): HexFixture = fixtures.fixture("hex-a")

    private fun dimmerAssignment(
        cueId: Int,
        value: UByte,
        layerWinner: CookWinner? = null,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = 1,
        fadeWeight = 1.0,
        targetKey = "hex-a",
        targetIsGroup = false,
        propertyName = "dimmer",
        category = PropertyCategory.DIMMER,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
        layerWinner = layerWinner,
    )

    private val warmWashUuid: UUID = UUID.nameUUIDFromBytes("warm-wash".toByteArray())

    private fun warmLayer(layerId: Int, sortOrder: Int) = ProgrammerLayer(
        layerId = layerId,
        source = LayerSource.look(7, warmWashUuid, "Warm Wash"),
        sortOrder = sortOrder,
    )

    private fun tick(n: Long) = MasterClock.ClockTick(
        tickNumber = n, beatNumber = 0, tickInBeat = n.toInt(), phase = 0.0,
        timestampMs = 1_000_000L + n * 20L,
    )

    // ─── Blind ───────────────────────────────────────────────────────────────

    @Test
    fun `blind enter reveals the cue value and exit restores the programmer value`() {
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "programmer over cue")

        rig.engine.setProgrammerBlind(true)
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "blind reveals the cue value")

        // The stored state is untouched — entry present, touched preserved.
        val slot = rig.programmerStore.get("hex-a", "dimmer")!!
        assertEquals(ProgrammerOwner.WEB, slot.owner)
        assertTrue(slot.touched, "blind must not clear the touched flag")

        rig.engine.setProgrammerBlind(false)
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "exit restores the staged value")
    }

    @Test
    fun `writes while blind stage silently and land on exit`() {
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        rig.engine.setProgrammerBlind(true)

        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(222u),
        )
        assertEquals(
            100u.toUByte(), rig.controller.currentValues[1],
            "a write while blind must not reach the stage",
        )

        rig.engine.setProgrammerBlind(false)
        assertEquals(222u.toUByte(), rig.controller.currentValues[1], "staged value lands on exit")
    }

    @Test
    fun `blind gates effect suppression too`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )
        val effect = FxInstance(
            effect = StaticValue(60u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)

        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "effect suppressed under programmer")

        rig.engine.setProgrammerBlind(true)
        rig.engine.processBeatTick(tick(1))
        assertEquals(60u.toUByte(), rig.controller.currentValues[1], "blind releases the suppression")

        rig.engine.setProgrammerBlind(false)
        rig.engine.processBeatTick(tick(2))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "suppression resumes on exit")
    }

    // ─── Fades ───────────────────────────────────────────────────────────────

    @Test
    fun `clearing an uncovered key with fadeMs requests a ramp back to the cue value`() {
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )

        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, rig.hex(), "dimmer", fadeMs = 1000)

        val last = rig.controller.changesTo(1).last()
        assertEquals(100u.toUByte(), last.newValue, "release target is the cue value")
        assertEquals(1000L, last.fadeMs, "the release rides the DmxController ramp")
    }

    @Test
    fun `setting a value with fadeMs requests a fade-to-value`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u), fadeMs = 500,
        )
        val last = rig.controller.changesTo(1).last()
        assertEquals(200u.toUByte(), last.newValue)
        assertEquals(500L, last.fadeMs)
    }

    @Test
    fun `clearing an effect-covered key snaps on the next tick instead of fading`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )
        val effect = FxInstance(
            effect = StaticValue(60u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        // The publish skips effect-covered keys entirely — no faded write appears.
        val changesBefore = rig.controller.changesTo(1).size
        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, rig.hex(), "dimmer", fadeMs = 1000)
        assertEquals(changesBefore, rig.controller.changesTo(1).size, "covered key publish is skipped")

        // The next tick settles the channel: the effect resumes painting (snap).
        rig.engine.processBeatTick(tick(1))
        assertEquals(60u.toUByte(), rig.controller.currentValues[1], "effect resumes on the next tick")
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs, "tick writes snap")
    }

    // ─── Provenance ──────────────────────────────────────────────────────────

    @Test
    fun `provenance follows the winner across programmer, effect, and cue transitions`() {
        val rig = newRig()

        fun sourceOf(property: String = "dimmer"): FxEngine.ProvenanceSource? =
            rig.engine.computeProvenance()
                .firstOrNull { it.targetKey == "hex-a" && it.propertyName == property }?.source

        assertNull(sourceOf(), "nothing contributes — baseline is omitted")

        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        assertEquals(FxEngine.ProvenanceSource.CUE, sourceOf())
        assertEquals(
            10,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.cueId,
            "the winning cue is named",
        )

        val effectId = rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(60u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.OVERRIDE,
            )
        )
        assertEquals(FxEngine.ProvenanceSource.EFFECT, sourceOf())
        assertEquals(
            effectId,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.effectId,
        )

        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )
        assertEquals(
            FxEngine.ProvenanceSource.PROGRAMMER, sourceOf(),
            "a programmer entry suppresses the effect and owns the value",
        )

        rig.engine.setProgrammerBlind(true)
        assertEquals(FxEngine.ProvenanceSource.EFFECT, sourceOf(), "blind hands the win back")
        rig.engine.setProgrammerBlind(false)

        rig.engine.removeEffect(effectId)
        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, rig.hex(), "dimmer")
        assertEquals(FxEngine.ProvenanceSource.CUE, sourceOf(), "back to the cue after both release")

        rig.engine.removeCueAssignments(10)
        assertNull(sourceOf(), "everything released — baseline again")
    }

    @Test
    fun `a stomped layer's effect loses provenance to the cue it is stomped by`() {
        // What is painting and what provenance reports come off one helper for exactly this case.
        // Left apart, a stomped effect would still be named the winner, and the operator asking
        // "why is this fixture this colour?" would be pointed at an effect nobody can see.
        val rig = newRig()
        val effectId = rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(60u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.OVERRIDE,
            ).also { it.cueId = 10; it.cueLayerId = 4 }
        )

        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        assertEquals(
            FxEngine.ProvenanceSource.EFFECT,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.source,
            "unstomped, the effect sits above the cue's value and owns it",
        )

        rig.engine.setCueAssignments(
            10, listOf(dimmerAssignment(10, 100u)),
            stompSuppression = mapOf(4 to mapOf("hex-a" to setOf("dimmer"))),
        )
        val stomped = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.CUE, stomped.source)
        assertNull(stomped.effectId, "and it does not name the effect it stopped reporting")

        // Still running, so clearing the stomp hands provenance straight back.
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        assertEquals(
            effectId,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.effectId,
        )
    }

    @Test
    fun `a programmer-band effect wins provenance over the programmer value it rides`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )
        rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(30u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.ADDITIVE,
            ).also { it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }
        )
        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.EFFECT, entry.source, "band effects modulate on top")
    }

    @Test
    fun `a CUE provenance entry names the stack the cue was published from`() {
        // `FU-PROG-PROVENANCE-STACKID`: the cue layer is keyed by cue alone, so this used to be
        // permanently null while EFFECT sources carried it — the asymmetry Update's Mode B
        // checklist needs closed in order to group overridden cues by stack.
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)), cueStackId = 7)

        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.CUE, entry.source)
        assertEquals(10, entry.cueId)
        assertEquals(7, entry.cueStackId)
        assertEquals(7, rig.engine.cueStackIdFor(10))

        rig.engine.removeCueAssignments(10)
        assertNull(rig.engine.cueStackIdFor(10), "the mapping goes with the assignments")
    }

    @Test
    fun `a CUE provenance entry names the Look layer that won, when a layer did`() {
        // The operator-facing payoff of the cook step knowing its winner: "why is this fixture this
        // colour?" can answer *Warm Wash* rather than *cue 10*. Carried as extra fields on a CUE
        // entry rather than a new ProvenanceSource, so a client that ignores them is unaffected.
        val rig = newRig()
        rig.engine.setCueAssignments(
            10,
            listOf(dimmerAssignment(10, 100u, CookWinner(index = 1, layerId = 42, source = LayerSource.look(7, warmWashUuid, "Warm Wash")))),
            cueStackId = 7,
        )

        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.CUE, entry.source, "the source is still the cue")
        assertEquals(42, entry.layerId)
        assertEquals(7, entry.layerSource?.id)
        assertEquals("Warm Wash", entry.layerSource?.name)
        assertEquals(LayerSourceKind.LOOK, entry.layerSource?.kind)
    }

    @Test
    fun `a PROGRAMMER provenance entry names the Look layer that won`() {
        // The programmer half of the entry above, and it was missing until session 4: the branch
        // built a bare PROGRAMMER entry, so a cell lit by a busking pad answered "the programmer"
        // and the layer-aware hover the redesign exists for never appeared. Found on a desk, not by
        // a test — which is why this one exists. The rank is decoded from the reserved seq band, so
        // this also pins that ProgrammerStore.layerWinnerRankByKey and putLayerSlots agree.
        val rig = newRig()
        rig.programmerStore.mutateLayers { listOf(warmLayer(layerId = 9, sortOrder = 0)) to Unit }
        rig.programmerStore.putLayerSlots(
            listOf(ProgrammerStore.LayerSlotWrite("hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u), layerIndex = 0)),
        )

        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.PROGRAMMER, entry.source, "the source is still the programmer")
        assertEquals(9, entry.layerId)
        assertEquals(7, entry.layerSource?.id)
        assertEquals("Warm Wash", entry.layerSource?.name)
    }

    @Test
    fun `a local busk over a layer reports no layer, because the layer did not win`() {
        // The restriction that makes the entry above trustworthy. A layer slot sits at the tail of
        // its stack, so an operator's own write outranks it — and naming the Look there would tell
        // them their value came from a Look when it came from their hand.
        val rig = newRig()
        rig.programmerStore.mutateLayers { listOf(warmLayer(layerId = 9, sortOrder = 0)) to Unit }
        rig.programmerStore.putLayerSlots(
            listOf(ProgrammerStore.LayerSlotWrite("hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u), layerIndex = 0)),
        )
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(10u),
        )

        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.PROGRAMMER, entry.source)
        assertNull(entry.layerId, "the operator's own write won, so no layer is named")
        assertNull(entry.layerSource)
    }

    @Test
    fun `a cue's local row reports no layer at all`() {
        // Absence, not a null-valued entry: a local row is not attributable to a layer, and the
        // desk must not render an empty layer chip for one.
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)), cueStackId = 7)

        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.CUE, entry.source)
        assertNull(entry.layerId)
        assertNull(entry.layerSource)
    }

    @Test
    fun `underlyingSources names the cue beneath an active programmer entry`() {
        // This is exactly what computeProvenance deliberately does *not* report: with the
        // programmer on top, provenance says PROGRAMMER (correctly), so Mode B needs the
        // programmer-independent Layer 4 winner map instead.
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)), cueStackId = 7)
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )

        assertEquals(
            FxEngine.ProvenanceSource.PROGRAMMER,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.source,
        )

        val key = CueAssignmentResolver.Key.fixture("hex-a", "dimmer")
        val source = rig.engine.underlyingSources(listOf(key)).single()
        assertEquals(10, source.cueId, "the cue underneath, not the programmer on top")
        assertEquals(7, source.cueStackId)
        assertNull(source.viaEffectId)
    }

    @Test
    fun `underlyingSources falls back to a cue-owned effect, and ignores band effects`() {
        val rig = newRig()
        val key = CueAssignmentResolver.Key.fixture("hex-a", "dimmer")

        val cueEffect = FxInstance(
            effect = StaticValue(60u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        ).also { it.cueId = 42; it.cueStackId = 5 }
        val cueEffectId = rig.engine.addEffect(cueEffect)

        val viaEffect = rig.engine.underlyingSources(listOf(key)).single()
        assertEquals(42, viaEffect.cueId, "a cue driving the property through an FX still counts")
        assertEquals(5, viaEffect.cueStackId)
        assertEquals(cueEffectId, viaEffect.viaEffectId)

        rig.engine.removeEffect(cueEffectId)
        rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(30u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.OVERRIDE,
            ).also { it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE },
        )
        val bandOnly = rig.engine.underlyingSources(listOf(key)).single()
        assertNull(
            bandOnly.cueId,
            "a programmer-band effect is part of the busk being written back, not something under it",
        )
    }

    @Test
    fun `a band effect does not hide the cue effect underneath it`() {
        // Band effects outrank every cue-derived priority, so a "highest priority per key" scan
        // that included them would only ever surface the band one — and filtering it out
        // afterwards would report the key as unattributed even though a cue is driving it.
        val rig = newRig()
        val key = CueAssignmentResolver.Key.fixture("hex-a", "dimmer")

        val cueEffectId = rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(60u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.OVERRIDE,
            ).also { it.cueId = 42; it.cueStackId = 5 },
        )
        rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(30u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.ADDITIVE,
            ).also { it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE },
        )

        val source = rig.engine.underlyingSources(listOf(key)).single()
        assertEquals(42, source.cueId, "the cue's own effect is still what's underneath")
        assertEquals(5, source.cueStackId)
        assertEquals(cueEffectId, source.viaEffectId)
    }
}
