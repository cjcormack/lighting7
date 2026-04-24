package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Lifecycle tests for the project-keyed preview slot used by the PresetEditor's
 * "Live Preview" toggle. Exercises [swapPresetPreviewSlot] with stand-in callbacks so the
 * concurrency contract (clear-prior, install-new, no-orphans on empty) can be validated
 * without spinning up a [uk.me.cormack.lighting7.state.State] / `Show`.
 */
class PresetPreviewSlotTest {

    private val projectA = "1"
    private val projectB = "2"

    @AfterTest
    fun resetSlots() {
        presetPreviewStates.clear()
    }

    private fun write(fixture: String, property: String) =
        PresetToggleWrite(fixtureKey = fixture, propertyName = property)

    private fun request(
        targetKey: String = "hex-1",
        propertyName: String = "dimmer",
        value: String = "180",
    ) = PresetPreviewRequest(
        propertyAssignments = listOf(
            FxPresetPropertyAssignmentDto(propertyName = propertyName, value = value),
        ),
        targets = listOf(TogglePresetTarget(type = "fixture", key = targetKey)),
    )

    @Test
    fun `first push records writes in the project slot`() {
        val cleared = mutableListOf<PresetToggleWrite>()
        val newWrites = listOf(write("hex-1", "dimmer"))

        val installed = swapPresetPreviewSlot(
            projectA, request(),
            clear = { cleared += it },
            install = { newWrites },
        )

        assertEquals(newWrites, installed)
        assertEquals(emptyList(), cleared)
        assertEquals(newWrites, presetPreviewStates[projectA]?.writes)
    }

    @Test
    fun `second push clears prior writes before installing new ones`() {
        val firstWrites = listOf(write("hex-1", "dimmer"), write("hex-1", "rgbColour"))
        swapPresetPreviewSlot(projectA, request("hex-1"), clear = {}, install = { firstWrites })

        val cleared = mutableListOf<PresetToggleWrite>()
        val secondWrites = listOf(write("hex-2", "dimmer"))
        val installed = swapPresetPreviewSlot(
            projectA, request("hex-2"),
            clear = { cleared += it },
            install = { secondWrites },
        )

        assertEquals(firstWrites, cleared)
        assertEquals(secondWrites, installed)
        assertEquals(secondWrites, presetPreviewStates[projectA]?.writes)
    }

    @Test
    fun `re-pushing identical request is a no-op (no clears, no install)`() {
        val firstWrites = listOf(write("hex-1", "dimmer"))
        swapPresetPreviewSlot(projectA, request(), clear = {}, install = { firstWrites })
        val priorSlot = presetPreviewStates[projectA]

        var installCalls = 0
        val cleared = mutableListOf<PresetToggleWrite>()
        val installed = swapPresetPreviewSlot(
            projectA, request(),
            clear = { cleared += it },
            install = { installCalls++; emptyList() },
        )

        assertEquals(firstWrites, installed)
        assertEquals(emptyList(), cleared)
        assertEquals(0, installCalls)
        assertSame(priorSlot, presetPreviewStates[projectA])
    }

    @Test
    fun `installing empty list removes the slot entirely`() {
        swapPresetPreviewSlot(
            projectA, request(),
            clear = {},
            install = { listOf(write("hex-1", "dimmer")) },
        )

        val cleared = mutableListOf<PresetToggleWrite>()
        swapPresetPreviewSlot(
            projectA, request(targetKey = "hex-2"),
            clear = { cleared += it },
            install = { emptyList() },
        )

        assertEquals(listOf(write("hex-1", "dimmer")), cleared)
        assertNull(presetPreviewStates[projectA])
    }

    @Test
    fun `clearing an empty slot is a no-op`() {
        val cleared = mutableListOf<PresetToggleWrite>()
        swapPresetPreviewSlot(
            projectA, request(),
            clear = { cleared += it },
            install = { emptyList() },
        )

        assertEquals(emptyList(), cleared)
        assertNull(presetPreviewStates[projectA])
    }

    @Test
    fun `slots are project-scoped — push to one project does not affect another`() {
        val writesA = listOf(write("hex-1", "dimmer"))
        val writesB = listOf(write("hex-9", "rgbColour"))

        swapPresetPreviewSlot(projectA, request("hex-1"), clear = {}, install = { writesA })
        swapPresetPreviewSlot(projectB, request("hex-9"), clear = {}, install = { writesB })

        assertEquals(writesA, presetPreviewStates[projectA]?.writes)
        assertEquals(writesB, presetPreviewStates[projectB]?.writes)

        val clearedB = mutableListOf<PresetToggleWrite>()
        swapPresetPreviewSlot(
            projectB, request("hex-9", value = "0"),
            clear = { clearedB += it },
            install = { emptyList() },
        )

        assertEquals(writesB, clearedB)
        assertEquals(writesA, presetPreviewStates[projectA]?.writes)
        assertNull(presetPreviewStates[projectB])
    }

    @Test
    fun `empty install on a fresh slot still receives no clears`() {
        // Sanity: untouched-slot path doesn't accidentally invoke the clear callback.
        var clearCalls = 0
        swapPresetPreviewSlot(
            projectA, request(),
            clear = { clearCalls++ },
            install = { emptyList() },
        )
        assertEquals(0, clearCalls)
    }
}
