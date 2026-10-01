package uk.me.cormack.lighting7.fixture.lantern

import org.junit.Test
import uk.me.cormack.lighting7.fixture.BodyArchetype
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lantern library as the desk ships it (stage-view plan session 7): it loads, it holds the
 * plan's defaults, and every entry passes the checks the loader makes — so a typo in the resource
 * fails here rather than drawing a wrong beam.
 */
class LanternLibraryTest {

    @Test
    fun `the shipped library loads about 25 lanterns`() {
        val all = LanternLibrary.all
        assertTrue(all.size in 20..32, "about 25, got ${all.size}")
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun `the plan's defaults are the kinds' defaults`() {
        assertEquals("s4-19", LanternLibrary.defaultFor(FixtureKind.PROFILE)?.id)
        assertEquals("cantata-f", LanternLibrary.defaultFor(FixtureKind.FRESNEL)?.id)
        assertEquals("par64-cp62", LanternLibrary.defaultFor(FixtureKind.PAR)?.id)
        assertEquals("downlight", LanternLibrary.defaultFor(FixtureKind.GENERIC)?.id)
        assertNull(LanternLibrary.defaultFor(FixtureKind.MOVING_HEAD))
        assertEquals("s4-26", LanternLibrary.effective("s4-26", FixtureKind.FRESNEL)?.id, "a named lantern wins")
        assertEquals("cantata-f", LanternLibrary.effective("not-in-this-library", FixtureKind.FRESNEL)?.id)
    }

    @Test
    fun `a Source Four 19 has the lens the design measures from`() {
        val s4 = assertNotNull(LanternLibrary.byId("s4-19"))
        assertEquals(19.0, s4.fieldDeg)
        assertEquals(0.17, s4.lensDiameterM)
        assertEquals(BodyArchetype.PROFILE, s4.archetype)
        assertTrue(s4.accessories.shutters && s4.accessories.iris)
    }

    @Test
    fun `PAR 64 ovals are the datasheet's, wide by narrow`() {
        val cp62 = assertNotNull(LanternLibrary.byId("par64-cp62"))
        assertEquals(LanternOval(44.0, 21.0), cp62.oval)
        assertEquals(FixtureKind.PAR, cp62.kind)
        assertTrue(LanternLibrary.all.filter { it.oval != null }.all { it.family == LanternFamily.PAR })
    }

    @Test
    fun `the loader refuses a malformed entry, naming every problem`() {
        val bad = """
            [
              {"id": "Bad Id", "name": "", "maker": "x", "family": "FRESNEL", "archetype": "mover", "fieldDeg": 40,
               "zoom": {"minDeg": 10, "maxDeg": 30}, "oval": {"wideDeg": 40, "narrowDeg": 20},
               "lensDiameterM": 0.1, "lengthM": 0.3, "widthM": 0.2, "heightM": 0.2, "source": "x"},
              {"id": "a", "name": "A", "maker": "x", "family": "PAR", "archetype": "par", "fieldDeg": 20,
               "lensDiameterM": 0.1, "lengthM": 0.3, "widthM": 0.2, "heightM": 0.2, "defaultFor": ["PAR"], "source": "x"},
              {"id": "a", "name": "A", "maker": "x", "family": "PAR", "archetype": "par", "fieldDeg": 20,
               "lensDiameterM": 0.1, "lengthM": 0.3, "widthM": 0.2, "heightM": 0.2, "defaultFor": ["PAR"], "source": "x"}
            ]
        """.trimIndent()
        val e = assertFailsWith<IllegalArgumentException> { LanternLibrary.parse(bad) }
        val message = e.message.orEmpty()
        for (expected in listOf("lower-case", "name is blank", "outside its zoom", "only a PAR", "not a conventional", "appears twice", "default of")) {
            assertTrue(message.contains(expected), "expected '$expected' in: $message")
        }
    }

    @Test
    fun `shutters round-trip through the column and a bad column reads as none`() {
        val blades = listOf(ShutterBlade(0.2, 5.0), ShutterBlade(), ShutterBlade(0.35, -10.0), ShutterBlade(1.0, 0.0))
        assertEquals(blades, LanternFocus.shuttersFromText(LanternFocus.shuttersToText(blades)))
        assertNull(LanternFocus.shuttersFromText("not json"))
        assertNull(LanternFocus.shuttersFromText("""[{"depth":0.2,"angleDeg":0}]"""), "three blades short is none")
    }

    /**
     * A type that declares a body agrees with the mover test both the view and `describe_rig` make:
     * a mover's body iff the kind is a moving head or scanner, or the type tilts. A declared static
     * body on a head that tilts would be drawn still while the briefing aimed it.
     */
    @Test
    fun `a declared body agrees with how the desk classifies a mover`() {
        val declared = FixtureTypeRegistry.allTypes.filter { it.body != null }
        assertTrue(declared.isNotEmpty())
        for (t in declared) {
            val moves = t.kind == FixtureKind.MOVING_HEAD || t.kind == FixtureKind.SCANNER ||
                t.properties.any { it is SliderPropertyDescriptor && it.axis == "TILT" }
            assertEquals(moves, t.body!!.archetype == BodyArchetype.MOVER, "${t.typeKey} declares ${t.body}")
        }
        assertTrue(FixtureTypeRegistry.allTypes.filter { it.acceptsLantern }.none { it.body != null }, "a lantern is the body")
    }
}
