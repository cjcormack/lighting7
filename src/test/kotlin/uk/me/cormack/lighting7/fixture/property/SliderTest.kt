package uk.me.cormack.lighting7.fixture.property

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for DmxSlider implementation of Slider interface.
 *
 * Key behaviors tested:
 * - Single fixture sliders always return non-null values
 * - Value clamping to min/max range
 * - Fade operations
 */
class SliderTest {

    private val testUniverse = Universe(0, 0)

    @Test
    fun `value returns current channel value`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1)

        slider.value = 128u
        assertEquals(128u.toUByte(), slider.value)
    }

    @Test
    fun `value is non-null for single fixture slider`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1)

        // Even before setting, value should be 0 (from transaction default)
        assertNotNull(slider.value)
        assertEquals(0u.toUByte(), slider.value)

        slider.value = 200u
        assertNotNull(slider.value)
        assertEquals(200u.toUByte(), slider.value)
    }

    @Test
    fun `setting null value is ignored`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1)

        slider.value = 100u
        slider.value = null
        assertEquals(100u.toUByte(), slider.value)
    }

    @Test
    fun `value is clamped to max`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1, max = 200u)

        slider.value = 255u
        assertEquals(200u.toUByte(), slider.value)
    }

    @Test
    fun `value is clamped to min`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1, min = 50u)

        slider.value = 25u
        assertEquals(50u.toUByte(), slider.value)
    }

    @Test
    fun `value within range is not clamped`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1, min = 50u, max = 200u)

        slider.value = 128u
        assertEquals(128u.toUByte(), slider.value)
    }

    @Test
    fun `fadeToValue sets target value`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1)

        slider.fadeToValue(200u, 1000)
        // The mock transaction sets value immediately
        assertEquals(200u.toUByte(), slider.value)
    }

    @Test
    fun `fadeToValue clamps to range`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1, max = 150u)

        slider.fadeToValue(255u, 1000)
        assertEquals(150u.toUByte(), slider.value)
    }

    @Test
    fun `sliders on different channels are independent`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider1 = DmxSlider(transaction, testUniverse, channelNo = 1)
        val slider2 = DmxSlider(transaction, testUniverse, channelNo = 2)

        slider1.value = 100u
        slider2.value = 200u

        assertEquals(100u.toUByte(), slider1.value)
        assertEquals(200u.toUByte(), slider2.value)
    }

    @Test
    fun `slider stores min and max properties`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1, min = 10u, max = 245u)

        assertEquals(10u.toUByte(), slider.min)
        assertEquals(245u.toUByte(), slider.max)
    }

    @Test
    fun `slider stores channel number`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 42)

        assertEquals(42, slider.channelNo)
    }

    @Test
    fun `slider stores universe`() {
        val (_, transaction) = createTestTransaction(testUniverse)
        val slider = DmxSlider(transaction, testUniverse, channelNo = 1)

        assertEquals(testUniverse, slider.universe)
    }
}
