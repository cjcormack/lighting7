package uk.me.cormack.lighting7.sync

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.2's rewrites of one stored FX (terminology plan), as database step 4 and the importer share them. */
class LegacyNamesTest {

    private fun fx(
        effectType: String,
        parameters: Map<String, String> = emptyMap(),
        category: String = "dimmer",
        beatDivision: Double = 4.0,
        distribution: String = "LINEAR",
        phaseOffset: Double = 0.0,
        direction: String = "FORWARD",
        nShot: Int? = null,
        spread: Double = 1.0,
    ) = LegacyNames.StoredFx(effectType, category, beatDivision, distribution, phaseOffset, parameters, direction, nShot, spread)

    private val red = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val blue = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val effectPalette = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val palettes = mapOf(
        red to LegacyNames.Palette("Red", listOf(LegacyNames.PaletteRow("rgbColour", "#ff0000;policy=rgbonly", true)), false),
        blue to LegacyNames.Palette("Blue", listOf(LegacyNames.PaletteRow("rgbColour", "#0000ff;policy=rgbonly", true)), false),
        effectPalette to LegacyNames.Palette("Chase", emptyList(), true),
    )

    /** A script FX definition this project holds: a colour output with a colourList of its own. */
    private val definitions = mapOf("MyChase" to LegacyNames.Definition("COLOUR", setOf("steps")))

    private fun rewrite(fx: LegacyNames.StoredFx, absentPingPongIsOn: Boolean = true) =
        LegacyNames.rewriteFx(fx, LegacyNames.Context(palettes::get, definitions::get, absentPingPongIsOn))

    @Test
    fun `the renamed ids take their new names, however a row spelled them`() {
        for ((old, new) in listOf("Triangle" to "Ramp", "Colour Pulse" to "ColourWave", "strobe" to "DimmerStrobe", "ZoomBreathe" to "ZoomWave")) {
            assertEquals(new, rewrite(fx(old))?.fx?.effectType, old)
        }
        assertNull(rewrite(fx("SineWave")), "an FX holding no old name is left alone")
    }

    @Test
    fun `Breathe is Sine Wave a quarter-cycle on, Forward`() {
        val out = assertNotNull(rewrite(fx("Breathe", mapOf("min" to "10", "max" to "200"), phaseOffset = 0.5, direction = "BOUNCE"))).fx
        assertEquals("SineWave", out.effectType)
        assertEquals(mapOf("min" to "10", "max" to "200"), out.parameters)
        assertEquals(0.25, out.phaseOffset, 1e-12)
        assertEquals("FORWARD", out.direction)
    }

    @Test
    fun `pingPong on is Bounce at half the cycle, its phase offset, spread and runs doubled`() {
        val stored = fx("Sweep", mapOf("startPan" to "0", "pingPong" to "true"), category = "position", beatDivision = 4.0, phaseOffset = 0.75, nShot = 2, spread = 0.5)
        val out = assertNotNull(rewrite(stored)).fx
        assertEquals(2.0, out.beatDivision)
        assertEquals(1.5, out.phaseOffset, 1e-12, "kept modulo 2: Bounce's parity is part of where each head is")
        assertEquals(1.0, out.spread)
        assertEquals(4, out.nShot)
        assertEquals("BOUNCE", out.direction)
        assertEquals(mapOf("startPan" to "0"), out.parameters)
    }

    @Test
    fun `a pingPong spread above 1 stops at 2, and says so`() {
        val result = assertNotNull(rewrite(fx("PanSweep", mapOf("pingPong" to "true"), spread = 1.5)))
        assertEquals(2.0, result.fx.spread)
        assertTrue(result.notes.any { "150 %" in it && "200 %" in it }, "${result.notes}")
    }

    @Test
    fun `pingPong off is dropped and nothing else moves`() {
        val result = assertNotNull(rewrite(fx("ColourFade", mapOf("pingPong" to "false", "fromColour" to "#000000"))))
        assertEquals(fx("ColourFade", mapOf("fromColour" to "#000000")), result.fx)
    }

    @Test
    fun `a blank or unreadable pingPong was on, as TypedParams read it`() {
        for (raw in listOf("", "TRUE", "yes")) {
            assertEquals("BOUNCE", rewrite(fx("FocusPull", mapOf("pingPong" to raw)))?.fx?.direction, "'$raw'")
        }
    }

    @Test
    fun `an absent pingPong was on in a database, and is today's in an archive`() {
        assertEquals("BOUNCE", rewrite(fx("TiltSweep"), absentPingPongIsOn = true)?.fx?.direction)
        assertNull(rewrite(fx("TiltSweep", direction = "BOUNCE", beatDivision = 2.0), absentPingPongIsOn = false))
    }

    @Test
    fun `the Ping-pong Order's sweep moves with the halved cycle, and is noted`() {
        val result = assertNotNull(rewrite(fx("Sweep", mapOf("pingPong" to "true"), distribution = "PING_PONG")))
        assertTrue(result.notes.any { "Ping-pong" in it }, "${result.notes}")
    }

    @Test
    fun `a Colour Cycle over palettes alone is Palette Steps over them, with its fade`() {
        val stored = fx("ColourCycle", mapOf("colours" to "tmpl:$red, tmpl:$blue", "fadeRatio" to "0.2"), category = "colour")
        val result = assertNotNull(rewrite(stored))
        assertEquals("PaletteSteps", result.fx.effectType)
        assertEquals("colour", result.fx.category)
        assertEquals(mapOf("palettes" to "tmpl:$red,tmpl:$blue", "fadeRatio" to "0.2"), result.fx.parameters)
        assertTrue(result.notes.isNotEmpty(), "D14's change of output is noted")
    }

    @Test
    fun `a mixed Colour Cycle takes each palette's colour now, dropping one that names none`() {
        val stored = fx("ColourCycle", mapOf("colours" to "tmpl:$red,#00ff00,tmpl:$effectPalette,tmpl:not-a-uuid"), category = "colour")
        val result = assertNotNull(rewrite(stored))
        assertEquals("ColourCycle", result.fx.effectType)
        assertEquals("#ff0000,#00ff00", result.fx.parameters["colours"])
        assertEquals(2, result.notes.size, "${result.notes}")
    }

    @Test
    fun `a Colour Cycle over palettes it cannot step reads each as a colour instead`() {
        val missing = UUID.randomUUID()
        val result = assertNotNull(rewrite(fx("ColourCycle", mapOf("colours" to "tmpl:$red,tmpl:$missing"), category = "colour")))
        assertEquals("ColourCycle", result.fx.effectType)
        assertEquals("#ff0000", result.fx.parameters["colours"])
    }

    @Test
    fun `a Colour Cycle whose palettes all name no colour is black, as it played`() {
        val result = assertNotNull(rewrite(fx("ColourCycle", mapOf("colours" to "tmpl:$effectPalette,tmpl:not-a-uuid"), category = "colour")))
        assertEquals("#000000", result.fx.parameters["colours"], "never blank, which would read as the default cycle")
    }

    @Test
    fun `a Colour Cycle over one palette takes its colour, since Palette Steps steps two or more`() {
        val result = assertNotNull(rewrite(fx("ColourCycle", mapOf("colours" to "tmpl:$red"), category = "colour")))
        assertEquals("ColourCycle", result.fx.effectType)
        assertEquals("#ff0000", result.fx.parameters["colours"])
    }

    @Test
    fun `a script FX's colourList takes each palette's colour, and its id is never renamed`() {
        val result = assertNotNull(rewrite(fx("MyChase", mapOf("steps" to "tmpl:$blue,#00ff00", "pingPong" to "true"), category = "composite")))
        assertEquals("MyChase", result.fx.effectType)
        assertEquals("#0000ff,#00ff00", result.fx.parameters["steps"])
        assertEquals("true", result.fx.parameters["pingPong"], "a script FX's own parameter is its own")
        assertEquals("colour", result.fx.category, "a composite is filed under the family of its output")
    }

    @Test
    fun `a write naming an old name is refused, so no row written today is rewritten on import`() {
        assertEquals("'Triangle' is now 'Ramp'", LegacyNames.writeProblem("Triangle", emptyMap()))
        assertNotNull(LegacyNames.writeProblem("Breathe", emptyMap()))
        assertNotNull(LegacyNames.writeProblem("Sweep", mapOf("pingPong" to "false")))
        assertNull(LegacyNames.writeProblem("Sweep", mapOf("startPan" to "0")))
        assertNull(LegacyNames.writeProblem("Ramp", emptyMap()))
        assertNull(LegacyNames.writeProblem("MyChase", mapOf("pingPong" to "true")), "a script FX's own parameter")
    }

    @Test
    fun `a retired id is matched as the registry matches one`() {
        for (id in listOf("Triangle", "strobe", "Zoom Breathe", "breathe")) assertTrue(LegacyNames.isRetiredFxId(id), id)
        assertTrue(!LegacyNames.isRetiredFxId("DimmerStrobe"))
    }

    @Test
    fun `Lightning Strike leaves the composite category and the colours it never applied`() {
        val stored = fx(
            "LightningStrike",
            mapOf("maxBrightness" to "255", "flashColour" to "#ffffff", "decayColour" to "#5064ff", "ambientColour" to "#000000"),
            category = "composite",
        )
        val out = assertNotNull(rewrite(stored)).fx
        assertEquals("dimmer", out.category)
        assertEquals(mapOf("maxBrightness" to "255"), out.parameters)
    }

    @Test
    fun `SPLIT reads as EDGES_IN`() {
        assertEquals("EDGES_IN", rewrite(fx("Pulse", distribution = "SPLIT"))?.fx?.distribution)
        assertEquals("EDGES_IN", LegacyNames.distribution("split"))
    }

    @Test
    fun `a rewritten FX holds no old name, so a second pass leaves it alone`() {
        val stored = listOf(
            fx("Breathe"),
            fx("Triangle", distribution = "SPLIT"),
            fx("Sweep", mapOf("pingPong" to "true")),
            fx("ColourCycle", mapOf("colours" to "tmpl:$red,tmpl:$blue")),
            fx("ColourCycle", mapOf("colours" to "tmpl:$red,#00ff00")),
            fx("LightningStrike", mapOf("flashColour" to "#ffffff"), category = "composite"),
        )
        for (each in stored) {
            val once = assertNotNull(rewrite(each), each.effectType).fx
            assertNull(rewrite(once, absentPingPongIsOn = false), "${each.effectType} rewrote twice")
        }
    }

    @Test
    fun `an older desk's bouncing FX with no pingPong is spelled out as on, never a project's own FX`() {
        val json = """{"effects":[{"beatDivision":4.0,"effectType":"Pan Sweep"},{"beatDivision":1.0,"effectType":"Sweep","parameters":{"pingPong":"false"}},{"beatDivision":1.0,"effectType":"Mine"}]}"""
        val out = canonicalJson.parseToJsonElement(assertNotNull(LegacyNames.spellOutAbsentPingPong(json, emptySet())))
        val parameters = out.jsonObject.getValue("effects").jsonArray.map { effect ->
            effect.jsonObject["parameters"]?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content }
        }
        assertEquals(listOf(mapOf("pingPong" to "true"), mapOf("pingPong" to "false"), null), parameters)
        assertNull(LegacyNames.spellOutAbsentPingPong(json, setOf("PanSweep")))
    }

    @Test
    fun `the composite mode and script type map to the standard ones`() {
        assertEquals("STANDARD", LegacyNames.effectMode("COMPOSITE"))
        assertEquals("STATEFUL", LegacyNames.effectMode("STATEFUL"))
        assertEquals("FX_CALC", LegacyNames.scriptType("FX_CALC_COMPOSITE"))
        assertEquals("GENERAL", LegacyNames.scriptType("GENERAL"))
    }
}
