package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the Layer 4 transmit path: [CueAssignmentLayer.setAssignments] /
 * [CueAssignmentLayer.removeAssignments] / [CueAssignmentLayer.clearAll] must write the composed
 * Layer 2 → Layer 4 → Layer 5 fallback onto the [uk.me.cormack.lighting7.dmx.DmxController]
 * even when no effects are running.
 *
 * Regression target: the 2026-04-19d smoke-check found that pure-assignment cues (no effects)
 * never painted the stage because the tick loop early-returns and the effect-reset pass is
 * the only code path that currently composes Layer 4 onto controllers. These tests assert the
 * fix via real [MockDmxController] reads — no tick loop involvement.
 */
class FxEnginePublishCueLayerTest {

    private val universe = Universe(0, 0)

    private data class Rig(
        val controller: MockDmxController,
        val fixtures: Fixtures,
        val engine: FxEngine,
        val programmerStore: ProgrammerStore,
    )

    /**
     * Build a minimal test rig: one controller, one [HexFixture] at [firstChannel], wired
     * into an [FxEngine]. Dimmer lives at [firstChannel]; R/G/B at +1/+2/+3.
     */
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

    private fun slider(
        cueId: Int,
        priority: Int = 1,
        targetKey: String = "hex-a",
        propertyName: String = "dimmer",
        value: UByte,
        category: PropertyCategory = PropertyCategory.DIMMER,
        fadeDurationMs: Long? = null,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = propertyName,
        category = category,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
        fadeDurationMs = fadeDurationMs,
    )

    // ─── Per-row fades (sweep item B1) ──────────────────────────────────

    @Test
    fun `a row's own fade ramps the channel when the cue arrives`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(
            10,
            listOf(slider(cueId = 10, value = 180u, fadeDurationMs = 2_500)),
            honourRowFades = true,
        )
        assertEquals(
            2_500L, rig.controller.changesTo(1).last().fadeMs,
            "the winning row's fadeDurationMs must reach the controller as a ramp",
        )
    }

    @Test
    fun `a single-contributor HTP key still fades`() {
        // DIMMER is HTP, so treating every HTP bucket as sourceless would mean a Look's fade-up
        // never faded. `composeHtp` on one contributor returns that row's value — it *is* the source.
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(
            10,
            listOf(slider(cueId = 10, value = 180u, category = PropertyCategory.DIMMER, fadeDurationMs = 900)),
            honourRowFades = true,
        )
        assertEquals(900L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `a blended HTP key snaps, because no single row is its source`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 40u)))
        // Two live cues on one HTP dimmer key: `composeHtp` ignores priority, so the LTP-shaped
        // winner map cannot say which row the composed value came from. Timing it off the winner
        // would apply cue 11's 5 s ramp to a max() of both.
        rig.engine.cueLayer.setAssignments(
            11,
            listOf(slider(cueId = 11, priority = 2, value = 200u, fadeDurationMs = 5_000)),
            honourRowFades = true,
        )
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `a row with no fade still snaps`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(
            10, listOf(slider(cueId = 10, value = 180u)), honourRowFades = true,
        )
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `a Record or Update rewrite of a live cue snaps rather than re-running the fade`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(
            10, listOf(slider(cueId = 10, value = 100u, fadeDurationMs = 2_000)), honourRowFades = true,
        )
        // `republishCueLayer`'s shape: same cue, new rows, no arrival flag. Honouring the fade here
        // would set a 2 s crossfade running on every cue-edit persist.
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 220u, fadeDurationMs = 2_000)))
        assertEquals(220u.toUByte(), rig.controller.currentValues[1])
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `an edit tour republish snaps but a timed-layer fire does not`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 100u, fadeDurationMs = 2_000)))

        // `republishForLookEdit`: the operator nudged a live Look's value. An edit, not an entrance.
        rig.engine.cueLayer.replaceAssignments(
            mapOf(10 to listOf(slider(cueId = 10, value = 220u, fadeDurationMs = 2_000))),
            emptyMap(),
        )
        assertEquals(220u.toUByte(), rig.controller.currentValues[1])
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs)

        // `CueTriggerManager` firing a timed layer through the same entry point: an arrival.
        rig.engine.cueLayer.replaceAssignments(
            mapOf(10 to listOf(slider(cueId = 10, value = 60u, fadeDurationMs = 2_000))),
            emptyMap(),
            honourRowFades = true,
        )
        assertEquals(2_000L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `a crossfade weight tick never ramps a per-row fade`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 200u)))
        rig.engine.cueLayer.setAssignments(
            11,
            listOf(slider(cueId = 11, priority = 2, value = 90u, fadeDurationMs = 3_000)),
            weight = 0.0,
            honourRowFades = true,
        )
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.5, 11 to 0.5))
        assertTrue(
            rig.controller.changesTo(1).all { it.fadeMs == 0L },
            "a ramp restarted every crossfade frame never arrives: ${rig.controller.changesTo(1)}",
        )
    }

    @Test
    fun `releasing a key snaps, whatever fade the departing row asked for`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(
            10, listOf(slider(cueId = 10, value = 180u, fadeDurationMs = 4_000)), honourRowFades = true,
        )
        rig.engine.cueLayer.removeAssignments(10)
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `setCueAssignments writes dimmer value to controller with no effects running`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "dimmer channel must reflect Layer 4 publish")
    }

    @Test
    fun `a programmer value wins over a cue and survives its release`() {
        val rig = newRig(firstChannel = 1)
        // Sticky programmer write — sits ABOVE the cue layer.
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(55u))

        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(
            55u.toUByte(), rig.controller.currentValues[1],
            "the programmer value overrides the cue's assignment",
        )

        rig.engine.cueLayer.removeAssignments(10)
        assertEquals(
            55u.toUByte(), rig.controller.currentValues[1],
            "the programmer value stands after the cue releases",
        )

        // Clearing the programmer entry with no cue below cascades to baseline.
        rig.engine.programmer.clearProperty(
            ProgrammerOwner.WEB, rig.fixtures.fixture<uk.me.cormack.lighting7.fixture.dmx.HexFixture>("hex-a"), "dimmer",
        )
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `removeCueAssignments with no programmer entry releases to baseline zero`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        rig.engine.cueLayer.removeAssignments(10)
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `setCueAssignments with empty list releases that cue's contribution`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        rig.engine.cueLayer.setAssignments(10, emptyList())
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `clearAllCueAssignments releases every previously-asserted channel`() {
        val rig = newRig(firstChannel = 1)
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(30u))

        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(
            30u.toUByte(), rig.controller.currentValues[1],
            "the programmer value overrides the cue while both are present",
        )

        rig.engine.cueLayer.clearAll()
        assertEquals(
            30u.toUByte(), rig.controller.currentValues[1],
            "clearAllCueAssignments leaves the programmer value on stage"
        )
    }

    @Test
    fun `colour assignment writes R G B channels`() {
        val rig = newRig(firstChannel = 1) // R/G/B at channels 2/3/4
        val assignment = CueAssignmentResolver.Assignment(
            cueId = 10,
            priority = 1,
            fadeWeight = 1.0,
            targetKey = "hex-a",
            targetIsGroup = false,
            propertyName = "rgbColour",
            category = PropertyCategory.COLOUR,
            compositionOverride = CompositionRule.UNSET,
            value = CueAssignmentResolver.PropertyValue.Colour(
                ExtendedColour(java.awt.Color(128, 64, 200), 0u, 0u, 0u)
            ),
        )
        rig.engine.cueLayer.setAssignments(10, listOf(assignment))

        assertEquals(128u.toUByte(), rig.controller.currentValues[2], "red")
        assertEquals(64u.toUByte(), rig.controller.currentValues[3], "green")
        assertEquals(200u.toUByte(), rig.controller.currentValues[4], "blue")
    }

    @Test
    fun `two cues HTP dimmer composition writes max onto channel`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        assertEquals(100u.toUByte(), rig.controller.currentValues[1])

        rig.engine.cueLayer.setAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))
        assertEquals(200u.toUByte(), rig.controller.currentValues[1], "HTP takes max across cues")

        rig.engine.cueLayer.removeAssignments(20)
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "cue 10 still asserts 100")
    }

    @Test
    fun `publish does not touch unrelated channels`() {
        val rig = newRig(firstChannel = 1)
        // Touch an unrelated channel via direct write. Layer 4 publish walks only affected
        // keys, so the unrelated channel must remain untouched.
        rig.controller.setValue(200, 77u, 0L)

        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
        assertEquals(77u.toUByte(), rig.controller.currentValues[200], "unrelated channel untouched")
    }

    // ─── Layer 4 crossfade-weight integration ───────────────────────────────
    //
    // Drives [CueAssignmentLayer.updateFadeWeights] directly, covering the composition behaviour
    // that [CueCrossfadeDriver] ticks each frame (Layer 4 only — effects snap on
    // cue transition and don't participate in the crossfade). Simulates the
    // outgoing-at-1.0 / incoming-at-0.0 start, the 0.5 / 0.5 mid-fade, and the 0.0 / 1.0 end.

    // Uses a composition override to force LTP semantics on the dimmer channel so a
    // crossfade produces a linear blend. HexFixture has no LTP slider with a dedicated
    // channel number convenient for assertions — overriding DIMMER to LTP lets us keep
    // the test on the well-known channel 1 while still exercising linear interpolation.
    private fun ltpSlider(
        cueId: Int,
        priority: Int = 1,
        targetKey: String = "hex-a",
        propertyName: String = "dimmer",
        value: UByte,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = propertyName,
        category = PropertyCategory.DIMMER,
        compositionOverride = CompositionRule.LTP,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
    )

    @Test
    fun `crossfade start — outgoing 1_0 incoming 0_0 pins outgoing value`() {
        val rig = newRig(firstChannel = 1)
        // Both cues register assignments at default weight 1.0; then we set crossfade weights.
        rig.engine.cueLayer.setAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1, value = 100u)))
        rig.engine.cueLayer.setAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2, value = 200u)))
        // Mid-step state: both at weight 1.0. LTP winner is cueId 20 → 200.
        assertEquals(200u.toUByte(), rig.controller.currentValues[1], "dimmer channel at baseline winner")

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 1.0, 20 to 0.0))
        assertEquals(
            100u.toUByte(), rig.controller.currentValues[1],
            "crossfade start should pin outgoing value onto stage, not snap-cut to incoming",
        )
    }

    @Test
    fun `crossfade mid — outgoing 0_5 incoming 0_5 blends linear`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1, value = 100u)))
        rig.engine.cueLayer.setAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2, value = 200u)))
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.5, 20 to 0.5))
        // winner = cueId 20 (priority 2). progress = 0.5 (incoming's effective weight).
        // blend = 100 + (200 - 100) * 0.5 = 150.
        assertEquals(150u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `crossfade end — outgoing 0_0 incoming 1_0 pins incoming value`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1, value = 100u)))
        rig.engine.cueLayer.setAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2, value = 200u)))
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.0, 20 to 1.0))
        assertEquals(200u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `weight-only republishes leave the programmer revision alone, everything else bumps it`() {
        // Unstarted engine → ProvenanceService emits synchronously per trigger, so the
        // replay cache holds exactly the frame the last trigger produced.
        val rig = newRig(firstChannel = 1)
        val lastRevision = { rig.engine.provenance.flow.replayCache.last().programmerRevision }

        rig.engine.cueLayer.setAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1, value = 100u)))
        val afterSet = lastRevision()

        // A mid-fade weight tick carries winners forward unchanged — the client must be able
        // to skip its refetch, so the revision must not move.
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.5))
        assertEquals(afterSet, lastRevision(), "a weight-only tick cannot have moved the programmer")

        // Any other layer event mid-fade must bump it, or the client would skip the refetch
        // that propagates the change to other tabs.
        rig.engine.cueLayer.setAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2, value = 200u)))
        val afterSecondSet = lastRevision()
        assertTrue(afterSecondSet > afterSet, "a full republish could have changed anything")

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.75, 20 to 0.25))
        assertEquals(afterSecondSet, lastRevision())

        // A weight reaching 1.0 ends that cue's fade with a forced full republish — the
        // fade's last frame must re-arm the client refetch.
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 1.0, 20 to 1.0))
        assertTrue(lastRevision() > afterSecondSet, "fade completion is a full republish, not a weight tick")
    }

    @Test
    fun `updateCueFadeWeights unknown cue id is a no-op`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        // Cue id 99 is not registered; should be ignored without republish-for-nothing.
        rig.engine.cueLayer.updateFadeWeights(mapOf(99 to 0.5))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `updateCueFadeWeights at 1_0 clears the weight entry`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.5))
        // HTP: 180 * 0.5 = 90.
        assertEquals(90u.toUByte(), rig.controller.currentValues[1])

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 1.0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "weight back to default should restore full value")
    }

    @Test
    fun `removeCueAssignments clears any crossfade weight entry`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.25))
        rig.engine.cueLayer.removeAssignments(10)

        // Re-register with same cueId — weight should NOT be leftover from prior session.
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(
            180u.toUByte(), rig.controller.currentValues[1],
            "remove + re-register must not carry over the 0.25 weight",
        )
    }

    // ─── setCueAssignments(cueId, assignments, weight) atomic crossfade-start ─

    @Test
    fun `atomic weight arg starts incoming at given weight without flashing its full value`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 50u)))
        rig.engine.cueLayer.setAssignments(
            cueId = 20,
            assignments = listOf(slider(cueId = 20, priority = 2, value = 200u)),
            weight = 0.0,
        )
        assertEquals(50u.toUByte(), rig.controller.currentValues[1])
        assertTrue(
            200u.toUByte() !in rig.controller.writesTo(1),
            "incoming value must never flash: writes were ${rig.controller.writesTo(1)}",
        )
    }

    @Test
    fun `reapplying a cue clears any stale crossfade weight`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.3))
        assertEquals(54u.toUByte(), rig.controller.currentValues[1], "180 * 0.3 = 54")

        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `atomic weight arg clamps out-of-range values`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 50u)))
        rig.engine.cueLayer.setAssignments(
            cueId = 20,
            assignments = listOf(slider(cueId = 20, priority = 2, value = 200u)),
            weight = -0.5,
        )
        assertEquals(50u.toUByte(), rig.controller.currentValues[1])

        rig.engine.cueLayer.setAssignments(
            cueId = 20,
            assignments = listOf(slider(cueId = 20, priority = 2, value = 200u)),
            weight = 1.5,
        )
        assertEquals(200u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `crossfade weights ramp colour RGB linearly`() {
        val rig = newRig(firstChannel = 1)
        val red = CueAssignmentResolver.Assignment(
            cueId = 10, priority = 1, fadeWeight = 1.0,
            targetKey = "hex-a", targetIsGroup = false,
            propertyName = "rgbColour", category = PropertyCategory.COLOUR,
            value = CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(java.awt.Color(255, 0, 0), 0u, 0u, 0u)),
        )
        val blue = red.copy(
            cueId = 20, priority = 2,
            value = CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(java.awt.Color(0, 0, 255), 0u, 0u, 0u)),
        )
        rig.engine.cueLayer.setAssignments(10, listOf(red))
        rig.engine.cueLayer.setAssignments(20, listOf(blue))

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 1.0, 20 to 0.0))
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "start: red")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4])

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.5, 20 to 0.5))
        // Linear RGB blend at 50%: (1 - 0.5)*255 + 0.5*0 = 127 red; 0.5*255 = 127 blue.
        assertEquals(127u.toUByte(), rig.controller.currentValues[2], "mid: half red")
        assertEquals(127u.toUByte(), rig.controller.currentValues[4], "mid: half blue")

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.0, 20 to 1.0))
        assertEquals(0u.toUByte(), rig.controller.currentValues[2], "end: no red")
        assertEquals(255u.toUByte(), rig.controller.currentValues[4], "end: full blue")
    }

    @Test
    fun `a programmer colour wins over a cue colour and survives its release`() {
        val rig = newRig(firstChannel = 1)
        // The programmer holds a dim red on the colour property.
        rig.programmerStore.put(
            ProgrammerOwner.WEB, "hex-a", "rgbColour",
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(java.awt.Color(40, 10, 10))),
        )

        val assignment = CueAssignmentResolver.Assignment(
            cueId = 10, priority = 1, fadeWeight = 1.0,
            targetKey = "hex-a", targetIsGroup = false,
            propertyName = "rgbColour", category = PropertyCategory.COLOUR,
            value = CueAssignmentResolver.PropertyValue.Colour(
                ExtendedColour(java.awt.Color(255, 255, 255), 0u, 0u, 0u)
            ),
        )
        rig.engine.cueLayer.setAssignments(10, listOf(assignment))
        assertEquals(40u.toUByte(), rig.controller.currentValues[2], "programmer red beats the cue's white")

        rig.engine.cueLayer.removeAssignments(10)
        assertEquals(40u.toUByte(), rig.controller.currentValues[2], "programmer red stands after the cue")
        assertEquals(10u.toUByte(), rig.controller.currentValues[3])
        assertEquals(10u.toUByte(), rig.controller.currentValues[4])
    }

    // ─── Sweep item C3: crossfade republish hot path ─────────────────────

    @Test
    fun `weight ticks reuse the winner maps and a cue mutation recomputes them`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 200u)))
        rig.engine.cueLayer.setAssignments(11, listOf(slider(cueId = 11, priority = 2, value = 90u)))
        val winners = rig.engine.layerResolver.current.winners
        assertEquals(11, winners[CueAssignmentResolver.Key.fixture("hex-a", "dimmer")])

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.7, 11 to 0.3))
        kotlin.test.assertSame(
            winners, rig.engine.layerResolver.current.winners,
            "a weight-only republish must carry the winner maps forward, not re-resolve them",
        )
        // The composed value still recomputes: LTP blend 200 + (90 - 200) * 0.3 = 167.
        assertEquals(167u.toUByte(), rig.controller.currentValues[1])

        rig.engine.cueLayer.setAssignments(12, listOf(slider(cueId = 12, priority = 3, value = 40u)))
        kotlin.test.assertNotSame(
            winners, rig.engine.layerResolver.current.winners,
            "a row-set mutation must re-resolve the winner maps",
        )
    }

    @Test
    fun `a weight reaching 1_0 re-resolves the winner maps`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 200u)))
        rig.engine.cueLayer.setAssignments(11, listOf(slider(cueId = 11, priority = 1, value = 90u)))
        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.9, 11 to 0.1))
        val pinned = rig.engine.layerResolver.current.winners

        // End the fade the way CueStackManager does when the outgoing cue contributed no
        // Layer 4 rows: its removal is a silent no-op, so this call is the fade's last
        // publish — it must not carry the pinned winner maps into steady state.
        rig.engine.cueLayer.updateFadeWeights(mapOf(11 to 1.0))
        kotlin.test.assertNotSame(
            pinned, rig.engine.layerResolver.current.winners,
            "a completed weight must force a full winner re-resolve",
        )
        assertEquals(
            11,
            rig.engine.layerResolver.current.winners[CueAssignmentResolver.Key.fixture("hex-a", "dimmer")],
            "steady-state attribution must reflect steady-state weights",
        )
    }

    @Test
    fun `an effect added mid-crossfade is respected by the next weight tick`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.cueLayer.setAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 200u)))
        rig.engine.cueLayer.setAssignments(11, listOf(slider(cueId = 11, priority = 2, value = 90u)))

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.9, 11 to 0.1))
        // LTP blend 200 + (90 - 200) * 0.1 = 189.
        assertEquals(189u.toUByte(), rig.controller.currentValues[1])

        // A running effect now covers the key; the effect-coverage cache built by the earlier
        // publishes must be invalidated, or the next weight tick would keep painting Layer 4
        // under the effect (visible flicker between the effect's ticks).
        rig.engine.addEffect(
            FxInstance(
                effect = uk.me.cormack.lighting7.testsupport.SineSlider(),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            ),
        )

        rig.engine.cueLayer.updateFadeWeights(mapOf(10 to 0.5, 11 to 0.5))
        assertEquals(
            189u.toUByte(), rig.controller.currentValues[1],
            "a weight tick must skip keys a running effect covers",
        )
    }
}
