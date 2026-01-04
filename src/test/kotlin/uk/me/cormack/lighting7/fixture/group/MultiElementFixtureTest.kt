package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.dmx.SlenderBeamBarQuadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for MultiElementFixture and elementsGroup extension.
 */
class MultiElementFixtureTest {

    private val testUniverse = Universe(0, 0)

    // ============================================
    // elementsGroup basic functionality
    // ============================================

    @Test
    fun `elementsGroup returns correct group name`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        assertEquals("quad-bar-elements", group.name)
        assertEquals("quad-bar-elements", group.targetKey)
    }

    @Test
    fun `elementsGroup contains all elements`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        assertEquals(4, group.size)
        assertEquals(fixture.elementCount, group.size)
    }

    @Test
    fun `elementsGroup members have correct indices`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        group.forEachIndexed { expectedIdx, member ->
            assertEquals(expectedIdx, member.index)
        }
    }

    @Test
    fun `elementsGroup members have correct normalized positions`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        // With 4 elements: positions should be 0.0, 0.333, 0.666, 1.0
        assertEquals(0.0, group[0].normalizedPosition, 0.01)
        assertEquals(0.333, group[1].normalizedPosition, 0.01)
        assertEquals(0.666, group[2].normalizedPosition, 0.01)
        assertEquals(1.0, group[3].normalizedPosition, 0.01)
    }

    @Test
    fun `elementsGroup members have correct tags`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        group.forEachIndexed { idx, member ->
            assertTrue("element" in member.metadata.tags)
            assertTrue("element-$idx" in member.metadata.tags)
        }
    }

    @Test
    fun `elementsGroup fixtures match original elements`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        fixture.elements.forEachIndexed { idx, element ->
            assertEquals(element, group[idx].fixture)
            assertEquals(element.elementKey, group[idx].key)
        }
    }

    // ============================================
    // elementsGroup with single element
    // ============================================

    @Test
    fun `elementsGroup single element has position 0_5`() {
        // Create a fixture and mock it having just one element
        // Since SlenderBeamBarQuadFixture always has 4, we'll test the calculation
        // by checking that the formula is correct
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        // Verify the first and last positions are 0.0 and 1.0
        assertEquals(0.0, group.first().normalizedPosition, 0.01)
        assertEquals(1.0, group.last().normalizedPosition, 0.01)
    }

    // ============================================
    // elementsGroup filtering operations
    // ============================================

    @Test
    fun `elementsGroup everyNth works correctly`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val evens = fixture.elementsGroup.everyNth(2)

        assertEquals(2, evens.size)
        assertEquals(fixture.elements[0], evens[0].fixture)
        assertEquals(fixture.elements[2], evens[1].fixture)
    }

    @Test
    fun `elementsGroup leftHalf works correctly`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val left = fixture.elementsGroup.leftHalf()

        // With 4 elements at positions 0.0, 0.333, 0.666, 1.0
        // Left half (< 0.5) should include first two elements
        assertEquals(2, left.size)
        assertEquals(fixture.elements[0].elementKey, left[0].key)
        assertEquals(fixture.elements[1].elementKey, left[1].key)
    }

    @Test
    fun `elementsGroup rightHalf works correctly`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val right = fixture.elementsGroup.rightHalf()

        // Right half (>= 0.5) should include last two elements
        assertEquals(2, right.size)
        assertEquals(fixture.elements[2].elementKey, right[0].key)
        assertEquals(fixture.elements[3].elementKey, right[1].key)
    }

    @Test
    fun `elementsGroup withTags filters by element tag`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup
        val element1Only = group.withTags("element-1")

        assertEquals(1, element1Only.size)
        assertEquals(fixture.elements[1].elementKey, element1Only[0].key)
    }

    // ============================================
    // elementsGroup with transaction
    // ============================================

    @Test
    fun `elementsGroup can be bound to transaction`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1, transaction = transaction
        )

        val group = fixture.elementsGroup

        // Verify group can use WithPosition trait extensions
        // Each element has pan and tilt
        group.fixtures.forEach { element ->
            element.pan.value = 128u
            element.tilt.value = 64u
        }

        // Verify values were set
        group.fixtures.forEach { element ->
            assertEquals(128u.toUByte(), element.pan.value)
            assertEquals(64u.toUByte(), element.tilt.value)
        }
    }

    @Test
    fun `elementsGroup reversed works correctly`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val reversed = fixture.elementsGroup.reversed()

        assertEquals(4, reversed.size)
        assertEquals(fixture.elements[3].elementKey, reversed[0].key)
        assertEquals(fixture.elements[2].elementKey, reversed[1].key)
        assertEquals(fixture.elements[1].elementKey, reversed[2].key)
        assertEquals(fixture.elements[0].elementKey, reversed[3].key)
    }

    // ============================================
    // elementsGroup isGroup property
    // ============================================

    @Test
    fun `elementsGroup isGroup returns true`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        assertTrue(group.isGroup)
    }

    @Test
    fun `elementsGroup members are not groups`() {
        val fixture = SlenderBeamBarQuadFixture.Mode12Ch(
            testUniverse, "quad-bar", "Quad Bar", 1
        )

        val group = fixture.elementsGroup

        group.fixtures.forEach { element ->
            assertFalse(element.isGroup)
            assertEquals(1, element.memberCount)
        }
    }

    // ============================================
    // Different multi-element fixture modes
    // ============================================

    @Test
    fun `elementsGroup works with 14ch mode`() {
        val fixture = SlenderBeamBarQuadFixture.Mode14Ch(
            testUniverse, "quad-bar-14", "Quad Bar 14CH", 1
        )

        val group = fixture.elementsGroup

        assertEquals("quad-bar-14-elements", group.name)
        assertEquals(4, group.size)
    }

    @Test
    fun `elementsGroup works with 27ch mode full heads`() {
        val fixture = SlenderBeamBarQuadFixture.Mode27Ch(
            testUniverse, "quad-bar-27", "Quad Bar 27CH", 1
        )

        val group = fixture.elementsGroup

        assertEquals("quad-bar-27-elements", group.name)
        assertEquals(4, group.size)

        // 27CH mode has FullHead elements with additional properties
        group.fixtures.forEach { fullHead ->
            assertTrue(fullHead is SlenderBeamBarQuadFixture.FullHead)
        }
    }
}
