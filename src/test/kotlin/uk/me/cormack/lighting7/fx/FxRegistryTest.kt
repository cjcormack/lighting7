package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.effects.SineWave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FxRegistryTest {

    private fun createTestRegistry(): FxRegistry {
        val registry = FxRegistry()
        registry.register(EffectRegistration(
            id = "TestEffect",
            aliases = setOf("test_effect", "test"),
            name = "Test Effect",
            category = "dimmer",
            outputType = FxOutputType.SLIDER,
            parameters = listOf(ParameterInfo("min", "ubyte", "0", "Min value")),
            compatibleProperties = listOf("dimmer"),
            factory = { params, _, _ ->
                SineWave(
                    min = params["min"]?.toIntOrNull()?.toUByte() ?: 0u,
                )
            },
        ))
        return registry
    }

    @Test
    fun `register and lookup by canonical id`() {
        val registry = createTestRegistry()
        val reg = registry.getRegistration("TestEffect")
        assertNotNull(reg)
        assertEquals("TestEffect", reg.id)
        assertEquals("Test Effect", reg.name)
    }

    @Test
    fun `lookup by alias is case-insensitive`() {
        val registry = createTestRegistry()
        assertNotNull(registry.getRegistration("test_effect"))
        assertNotNull(registry.getRegistration("TEST_EFFECT"))
        assertNotNull(registry.getRegistration("testeffect"))
        assertNotNull(registry.getRegistration("test"))
        assertNotNull(registry.getRegistration("TEST"))
    }

    @Test
    fun `lookup by canonical id is case-insensitive`() {
        val registry = createTestRegistry()
        assertNotNull(registry.getRegistration("testeffect"))
        assertNotNull(registry.getRegistration("TESTEFFECT"))
    }

    @Test
    fun `createEffect returns correct type with parameters`() {
        val registry = createTestRegistry()
        val effect = registry.createEffect("test", mapOf("min" to "50"))
        assertTrue(effect is SineWave)
        assertEquals(50.toUByte(), effect.min)
    }

    @Test
    fun `createEffect throws for unknown type`() {
        val registry = createTestRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.createEffect("nonexistent")
        }
    }

    @Test
    fun `createEffect passes palette suppliers`() {
        val registry = FxRegistry()
        var receivedPalette: (() -> List<ExtendedColour>)? = null
        registry.register(EffectRegistration(
            id = "PaletteTest",
            name = "Palette Test",
            category = "colour",
            outputType = FxOutputType.COLOUR,
            compatibleProperties = listOf("rgbColour"),
            factory = { _, paletteSupplier, _ ->
                receivedPalette = paletteSupplier
                SineWave() // dummy return
            },
        ))

        val supplier: () -> List<ExtendedColour> = { listOf(ExtendedColour.BLACK) }
        registry.createEffect("PaletteTest", paletteSupplier = supplier, paletteVersionSupplier = { 1L })
        assertEquals(supplier, receivedPalette)
    }

    @Test
    fun `unregister removes registration and aliases`() {
        val registry = createTestRegistry()
        assertNotNull(registry.getRegistration("test"))
        registry.unregister("TestEffect")
        assertNull(registry.getRegistration("TestEffect"))
        assertNull(registry.getRegistration("test"))
        assertNull(registry.getRegistration("test_effect"))
        assertEquals(0, registry.size)
    }

    @Test
    fun `getLibrary returns DTOs for all registrations`() {
        val registry = createTestRegistry()
        val library = registry.getLibrary()
        assertEquals(1, library.size)
        assertEquals("TestEffect", library[0].name)
        assertEquals("dimmer", library[0].category)
        assertEquals("SLIDER", library[0].outputType)
    }

    @Test
    fun `loadBuiltInEffects loads effects from fx kts resource files`() {
        val registry = FxRegistry()
        val compiler = FxScriptCompiler()
        val loader = FxFileLoader(compiler)
        val loaded = loader.loadBuiltInEffects(registry)

        // Check we have a reasonable number of effects loaded
        assertTrue(loaded >= 25, "Expected at least 25 effects loaded, got $loaded")
        assertTrue(registry.size >= 25, "Expected at least 25 effects registered, got ${registry.size}")

        // Spot-check key effects exist across categories
        assertNotNull(registry.getRegistration("SineWave"), "SineWave should be registered")
        assertNotNull(registry.getRegistration("ColourCycle"), "ColourCycle should be registered")
        assertNotNull(registry.getRegistration("Circle"), "Circle should be registered")
        assertNotNull(registry.getRegistration("StaticSetting"), "StaticSetting should be registered")
        assertNotNull(registry.getRegistration("CandleFlicker"), "CandleFlicker should be registered")
        assertNotNull(registry.getRegistration("LightningStrike"), "LightningStrike should be registered")

        // Normalized lookup should work (case-insensitive, spaces/underscores stripped)
        assertNotNull(registry.getRegistration("sinewave"), "Normalized 'sinewave' should resolve")
        assertNotNull(registry.getRegistration("Sine Wave"), "Spaced 'Sine Wave' should resolve")

        // Verify source is BUILT_IN
        val sineWave = registry.getRegistration("SineWave")!!
        assertEquals(EffectSource.BUILT_IN, sineWave.source)
    }

    @Test
    fun `normalize strips spaces and underscores`() {
        val registry = createTestRegistry()
        // "Test Effect" with spaces -> should resolve
        assertNotNull(registry.getRegistration("Test Effect"))
        assertNotNull(registry.getRegistration("test effect"))
    }
}
