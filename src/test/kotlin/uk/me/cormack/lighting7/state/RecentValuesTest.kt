package uk.me.cormack.lighting7.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentValuesTest {

    private val rig = setOf("dimmer", "zoom")
    private val isRigAttribute: (String) -> Boolean = { it in rig }

    private fun RecentValuesService.put(projectId: Int, key: String, value: String) = record(projectId, key, value, isRigAttribute)

    @Test
    fun `newest first, a repeat moves to the front, and the list stops at twelve`() {
        val service = RecentValuesService()
        service.put(1, "level:dimmer", "pct:10")
        service.put(1, "level:dimmer", "pct:20")
        service.put(1, "level:dimmer", "pct:30")
        assertEquals(listOf("pct:30", "pct:20", "pct:10"), service.listsFor(1)["level:dimmer"])

        service.put(1, "level:dimmer", "pct:10")
        assertEquals(listOf("pct:10", "pct:30", "pct:20"), service.listsFor(1)["level:dimmer"], "no repeats")

        repeat(20) { service.put(1, "level:dimmer", "pct:${50 + it}") }
        val list = service.listsFor(1).getValue("level:dimmer")
        assertEquals(RecentValuesService.CAP, list.size)
        assertEquals("pct:69", list.first())
    }

    @Test
    fun `each project keeps its own lists, and each attribute its own`() {
        val service = RecentValuesService()
        service.put(1, "colour", "#ff0000")
        service.put(2, "colour", "#00ff00")
        service.put(1, "level:zoom", "pct:40")
        assertEquals(listOf("#ff0000"), service.listsFor(1)["colour"])
        assertEquals(listOf("#00ff00"), service.listsFor(2)["colour"])
        assertEquals(listOf("pct:40"), service.listsFor(1)["level:zoom"])
        assertNull(service.listsFor(1)["level:dimmer"])
        assertTrue(service.listsFor(3).isEmpty())
    }

    @Test
    fun `an unknown key or a bad value is dropped`() {
        val service = RecentValuesService()
        val refused = listOf(
            "gobo" to "Open",
            "level:pan" to "pct:50",
            "level:" to "pct:50",
            "level:dimmer" to "62",
            "level:dimmer" to "pct:101",
            "palette-level:gobo" to "pct:50",
            "palette-level:position" to "pct:50",
            "palette-level:rgbColour" to "pct:50",
            "palette-level:prism" to "pct:50",
            "colour" to "red",
            "colour" to "#ff00",
            "colour" to "#ff0000;w256",
            "colour" to "#ff0000;w1;w2",
            "colour" to "#ff0000;x10",
            "position" to "pos:128,0",
            "position" to "deg:10,20",
            "warmth" to "3200K",
            "warmth" to "50K;80",
            "colour" to "#ff0000" + ";w1".repeat(30),
        )
        for ((key, value) in refused) assertFalse(service.put(1, key, value), "$key = $value")
        assertTrue(service.listsFor(1).isEmpty(), "nothing was recorded: ${service.listsFor(1)}")
    }

    @Test
    fun `values are kept in one spelling`() {
        val service = RecentValuesService()
        assertTrue(service.put(1, "colour", "#FF8800;UV3;W0"))
        assertTrue(service.put(1, "colour", "#ff8800;w0;uv3"))
        assertEquals(listOf("#ff8800;w0;uv3"), service.listsFor(1)["colour"], "the palette editor's #HEX and the grid's #hex are one recent")

        assertTrue(service.put(1, "palette-level:dimmer", "pct:50.0"))
        assertTrue(service.put(1, "palette-level:zoom", "pct:100.00"))
        assertEquals(listOf("pct:100"), service.listsFor(1)["palette-level:zoom"])
        assertEquals(listOf("pct:50"), service.listsFor(1)["palette-level:dimmer"])
        assertTrue(service.put(1, "position", "pos:-20,12"))
        assertEquals(listOf("pos:-20,12"), service.listsFor(1)["position"])
        assertTrue(service.put(1, "warmth", "3200K;80"))
        assertEquals(listOf("3200K;80"), service.listsFor(1)["warmth"])
    }
}
