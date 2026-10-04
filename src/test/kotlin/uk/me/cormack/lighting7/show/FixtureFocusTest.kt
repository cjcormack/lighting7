package uk.me.cormack.lighting7.show

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.BodyArchetype
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.MoverHead
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.LocateValueResolver
import uk.me.cormack.lighting7.testsupport.TestFocusHead
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FocusRange] — the inverse of the Stage view's `resolveDeclaredFocusDistance` that *Focus here*,
 * `aim_fixtures`' `focus` and Locate solve with (fixture-optics plan session 1).
 *
 * The load-bearing pin is the first test: `src/test/resources/stage/focusInverse.fixture.json` is
 * read here and by the view's `beamOptics.test.ts`, so a head this file focuses at a distance is the
 * head the view draws sharp at it. Change the rule, the vector and both pins in one commit.
 */
class FixtureFocusTest {

    @Serializable
    private data class Point(val distanceM: Double, val exactLevel: Double, val level: Int)

    @Serializable
    private data class Case(
        val name: String,
        val focusNearM: Double,
        val focusFarM: Double,
        val inverted: Boolean,
        val min: Int,
        val max: Int,
        val points: List<Point>,
        val outside: List<Double>,
        val middleM: Double,
        val middleLevel: Int,
    ) {
        val range get() = FocusRange(focusNearM, focusFarM, inverted, min, max)
    }

    @Serializable
    private data class Head(val name: String, val head: String, val heightM: Double, val pivotM: Double, val lensM: Double)

    @Serializable
    private data class Vector(val cases: List<Case>, val heads: List<Head>)

    private val vector: Vector = lenient.decodeFromString(
        checkNotNull(javaClass.getResource("/stage/focusInverse.fixture.json")) { "the shared vector is missing" }.readText(),
    )

    private companion object {
        val lenient = Json { ignoreUnknownKeys = true }
    }

    @Test
    fun `the inverse matches the shared vector, inverted and the range ends included`() {
        assertTrue(vector.cases.any { it.inverted } && vector.cases.any { !it.inverted }, "both directions are pinned")
        for (case in vector.cases) {
            val range = case.range
            assertTrue(range.usable, case.name)
            for (p in case.points) {
                val exact = assertNotNull(range.levelFor(p.distanceM), "${case.name} at ${p.distanceM} m")
                assertTrue(abs(exact - p.exactLevel) < 1e-5, "${case.name} at ${p.distanceM} m: $exact, not ${p.exactLevel}")
                assertEquals(p.level, range.dmxFor(p.distanceM), "${case.name} at ${p.distanceM} m")
                // And it is the inverse: the forward direction takes the exact level back to the distance.
                assertTrue(abs(range.distanceAt(exact) - p.distanceM) < 1e-9, "${case.name} round trip at ${p.distanceM} m")
            }
            // The ends are the declared ends, at the slider's own min and max.
            val near = if (case.inverted) case.max else case.min
            val far = if (case.inverted) case.min else case.max
            assertEquals(near, range.dmxFor(case.focusNearM), "${case.name}'s near end")
            assertEquals(far, range.dmxFor(case.focusFarM), "${case.name}'s far end")
            for (d in case.outside) assertNull(range.levelFor(d), "${case.name}: $d m is outside the range")
            assertEquals(case.middleLevel, range.middleDmx(), "${case.name}'s middle distance, ${case.middleM} m")
        }
    }

    @Test
    fun `a range no lens has focuses nothing`() {
        assertNull(FocusRange(5.0, 2.0, false, 0, 255).levelFor(3.0), "far nearer than near")
        assertNull(FocusRange(0.0, 40.0, false, 0, 255).levelFor(3.0), "a near end at the lens")
        assertNull(FocusRange(2.0, 40.0, false, 128, 128).levelFor(3.0), "a slider that does not move")
        assertNull(FocusRange(2.0, 40.0, false, 0, 255).levelFor(Double.NaN))
    }

    @Test
    fun `a focus slider with no declared range has no focus range`() {
        val head = TestFocusHead(Universe(0, 0), "head", 1)
        val focus = assertNotNull(head.fixtureProperty("focus"))
        assertNull(focus.focusRange(head.focus))
        val rev = Source4RevolutionFixture.BaseFrame31Ch(Universe(0, 0), "rev", "Rev", 1)
        val range = assertNotNull(rev.fixtureProperty("focus")!!.focusRange(rev.focus as DmxSlider))
        assertEquals(FocusRange(2.0, 40.0, false, 0, 255), range)
    }

    // ─── The lens a focus is measured from ──────────────────────────────────

    @Test
    fun `a mover's lens sits where the view draws it — the shared vector's heads`() {
        assertTrue(vector.heads.isNotEmpty())
        for (h in vector.heads) {
            val lens = MoverLens.of(h.heightM, MoverHead.valueOf(h.head.uppercase()))
            assertTrue(abs(lens.pivotM - h.pivotM) < 1e-9, "${h.name}'s pivot: ${lens.pivotM}")
            assertTrue(abs(lens.lensM - h.lensM) < 1e-9, "${h.name}'s lens: ${lens.lensM}")
        }
    }

    @Test
    fun `the distance runs from the lens — up the body to the pivot, then along the beam`() {
        val rev = MoverLens.of(0.856, MoverHead.PROFILE)
        // Hung (pitch 180): the pivot hangs below the placement; a wall point level with it, 24 m off.
        val hung = lensDistance(StagePoint(0.0, -17.3, 2.8), null, 180.0, null, rev, StagePoint(0.0, 6.7, 2.8 - rev.pivotM))
        assertTrue(abs(hung - (24.0 - rev.lensM)) < 1e-9, "hung: $hung")
        // Standing (pitch 0): the pivot is above it; straight down to the deck 3 m below the placement.
        val standing = lensDistance(StagePoint(1.0, 2.0, 3.0), null, 0.0, null, rev, StagePoint(1.0, 2.0, 0.0))
        assertTrue(abs(standing - (3.0 + rev.pivotM - rev.lensM)) < 1e-9, "standing: $standing")
        // A short throw is where it matters: 0.22 m is 7 % of 3 m, about a DMX step's softness.
        assertTrue(rev.lensM > 0.2 && rev.lensM < 0.25)
    }

    @Test
    fun `every focus type declares a mover body with its head, so the desk measures from its lens`() {
        val focusTypes = FixtureTypeRegistry.allTypes.filter { t ->
            t.properties.any { (it as? uk.me.cormack.lighting7.routes.SliderPropertyDescriptor)?.category == "focus" }
        }
        assertTrue(focusTypes.size >= 5, focusTypes.map { it.typeKey }.toString())
        for (t in focusTypes) {
            val body = assertNotNull(t.body, "${t.typeKey} declares no body, so the desk would measure from its placement")
            assertEquals(BodyArchetype.MOVER, body.archetype, t.typeKey)
            assertNotNull(body.head, "${t.typeKey} leaves its head to the view's words")
        }
    }

    // ─── Locate ─────────────────────────────────────────────────────────────

    @Test
    fun `Locate parks a declared range at its middle distance, solved back to DMX`() {
        val rev = Source4RevolutionFixture.BaseFrame31Ch(Universe(0, 0), "rev", "Rev", 1)
        val focus = LocateValueResolver.resolve(rev).single { it.propertyName == "focus" }.value
        // 21 m, the middle of 2–40 m; mid-DMX would be 3.8 m.
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(243u), focus)
        assertTrue(abs(FocusRange(2.0, 40.0, false, 0, 255).distanceAt(243.0) - 21.0) < 0.5)
    }

    @Test
    fun `Locate keeps mid-DMX for a focus with no declared range`() {
        val head = TestFocusHead(Universe(0, 0), "head", 1)
        val focus = LocateValueResolver.resolve(head).single { it.propertyName == "focus" }.value
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(128u), focus)
    }

    // ─── depthOfField ───────────────────────────────────────────────────────

    @Test
    fun `depthOfField reflects onto the type info, and the sentinel reflects as unset`() {
        val declared = FixtureTypeRegistry.typeInfosFor(TestFocusHead::class).single()
        assertEquals(4.5, declared.depthOfField)
        val rev = assertNotNull(FixtureTypeRegistry.typeInfoForKey("etc-source4-revolution-base-frame"))
        assertNull(rev.depthOfField, "the Revolution uses its family's depth of field")
        assertTrue(FixtureTypeRegistry.allTypes.all { (it.depthOfField ?: 1.0) > 0.0 })
    }
}
