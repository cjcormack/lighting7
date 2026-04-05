package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests for FixtureTypeRegistry, including the new instantiateByTypeKey,
 * typeKeyForClass, classNameForTypeKey, and channelCountForTypeKey methods.
 */
class FixtureTypeRegistryTest {

    @Test
    fun `allTypes returns non-empty list`() {
        assertTrue(FixtureTypeRegistry.allTypes.isNotEmpty())
    }

    @Test
    fun `allTypes contains hex fixture`() {
        val hex = FixtureTypeRegistry.allTypes.find { it.typeKey == "hex" }
        assertNotNull(hex)
        assertEquals("Chauvet", hex.manufacturer)
        assertEquals("Freedom Par Hex", hex.model)
        assertNotNull(hex.channelCount)
        assertTrue(hex.channelCount!! > 0)
    }

    @Test
    fun `instantiateByTypeKey creates valid fixture for hex`() {
        val universe = Universe(0, 1)
        val fixture = FixtureTypeRegistry.instantiateByTypeKey(
            typeKey = "hex",
            universe = universe,
            key = "test-par",
            fixtureName = "Test PAR",
            firstChannel = 10,
        )

        assertEquals("test-par", fixture.key)
        assertEquals("Test PAR", fixture.fixtureName)
        assertEquals(universe, fixture.universe)
        assertEquals(10, fixture.firstChannel)
        assertTrue(fixture.channelCount > 0)
    }

    @Test
    fun `instantiateByTypeKey throws for unknown type key`() {
        assertFailsWith<IllegalArgumentException> {
            FixtureTypeRegistry.instantiateByTypeKey(
                typeKey = "nonexistent-fixture",
                universe = Universe(0, 0),
                key = "test",
                fixtureName = "Test",
                firstChannel = 1,
            )
        }
    }

    @Test
    fun `instantiateByTypeKey works for all registered types`() {
        val universe = Universe(0, 0)
        for (typeInfo in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(
                typeKey = typeInfo.typeKey,
                universe = universe,
                key = "test-${typeInfo.typeKey}",
                fixtureName = "Test ${typeInfo.model}",
                firstChannel = 1,
            )

            assertEquals("test-${typeInfo.typeKey}", fixture.key)
            assertEquals("Test ${typeInfo.model}", fixture.fixtureName)
            if (typeInfo.channelCount != null) {
                assertEquals(typeInfo.channelCount, fixture.channelCount,
                    "Channel count mismatch for ${typeInfo.typeKey}")
            }
        }
    }

    @Test
    fun `classNameForTypeKey returns correct class name`() {
        val className = FixtureTypeRegistry.classNameForTypeKey("hex")
        assertEquals("HexFixture", className)
    }

    @Test
    fun `classNameForTypeKey returns null for unknown key`() {
        assertNull(FixtureTypeRegistry.classNameForTypeKey("nonexistent"))
    }

    @Test
    fun `channelCountForTypeKey returns correct count`() {
        val count = FixtureTypeRegistry.channelCountForTypeKey("hex")
        assertNotNull(count)
        assertTrue(count > 0)
    }

    @Test
    fun `channelCountForTypeKey returns null for unknown key`() {
        assertNull(FixtureTypeRegistry.channelCountForTypeKey("nonexistent"))
    }

    @Test
    fun `typeKeyForClass returns correct key for annotated class`() {
        val typeKey = FixtureTypeRegistry.typeKeyForClass(
            uk.me.cormack.lighting7.fixture.dmx.HexFixture::class
        )
        assertEquals("hex", typeKey)
    }

    @Test
    fun `typeKeyForClass returns null for unannotated class`() {
        assertNull(FixtureTypeRegistry.typeKeyForClass(String::class))
    }
}
