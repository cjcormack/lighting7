package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.SpreadOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The point of [FxSpecCoercion] is that one bad string has one outcome *per policy*, not one
 * per field — which is what these tests pin. Before it existed, `blendMode` and `elementMode`
 * threw on an unknown value while `order` and `elementFilter` silently defaulted,
 * so the same typo produced a 400 or a quiet NORMAL depending on where it landed.
 */
class FxSpecCoercionTest {

    private val CONTEXT = { "test" }

    @Test
    fun `strict rejects an unknown value in every field`() {
        assertFailsWith<IllegalArgumentException> { FxSpecCoercion.Strict.blendMode("nope") }
        assertFailsWith<IllegalArgumentException> { FxSpecCoercion.Strict.elementMode("nope") }
        assertFailsWith<IllegalArgumentException> { FxSpecCoercion.Strict.elementFilter("nope") }
        assertFailsWith<IllegalArgumentException> { FxSpecCoercion.Strict.order("nope") }
    }

    @Test
    fun `strict rejects blank rather than treating it as absent`() {
        assertFailsWith<IllegalArgumentException> { FxSpecCoercion.Strict.blendMode("") }
        assertFailsWith<IllegalArgumentException> { FxSpecCoercion.Strict.order("   ") }
    }

    @Test
    fun `the rejection names the valid set, because the client has to fix it`() {
        val message = assertFailsWith<IllegalArgumentException> {
            FxSpecCoercion.Strict.blendMode("Overide")
        }.message ?: ""
        assertTrue("Overide" in message, "should quote the offending value: $message")
        assertTrue("OVERRIDE" in message && "MULTIPLY" in message, "should list valid modes: $message")
    }

    @Test
    fun `both policies accept any casing and surrounding whitespace`() {
        assertEquals(BlendMode.MULTIPLY, FxSpecCoercion.Strict.blendMode(" multiply "))
        assertEquals(ElementMode.FLAT, FxSpecCoercion.Strict.elementMode("Flat"))
        assertEquals(ElementFilter.SECOND_HALF, FxSpecCoercion.Strict.elementFilter("second_half"))
        assertEquals(SpreadOrder.PING_PONG, FxSpecCoercion.Strict.order("Ping_Pong"))
        assertEquals(BlendMode.MAX, FxSpecCoercion.Lenient.blendMode("max", CONTEXT))
    }

    @Test
    fun `lenient defaults every field, so a stored spec still fires`() {
        assertEquals(BlendMode.OVERRIDE, FxSpecCoercion.Lenient.blendMode("nope", CONTEXT))
        assertEquals(ElementMode.PER_FIXTURE, FxSpecCoercion.Lenient.elementMode("nope", CONTEXT))
        assertEquals(ElementFilter.ALL, FxSpecCoercion.Lenient.elementFilter("nope", CONTEXT))
        assertEquals(SpreadOrder.NORMAL, FxSpecCoercion.Lenient.order("nope", CONTEXT))
    }

    @Test
    fun `lenient treats null as absent, without building the log context`() {
        var contextBuilt = false
        val context = { contextBuilt = true; "test" }
        assertEquals(ElementMode.PER_FIXTURE, FxSpecCoercion.Lenient.elementMode(null, context))
        assertEquals(ElementFilter.ALL, FxSpecCoercion.Lenient.elementFilter(null, context))
        assertFalse(contextBuilt, "an unset optional field is not a fault and must not log")
    }

    @Test
    fun `lenient treats a present blank as a fault, not as absent`() {
        // `DaoCueLayers.blend_mode` is NOT NULL with a stored default, so "" is a written-wrong
        // row rather than an omission — and the warn is the operator's only clue that the blend
        // shown in the UI is not the blend being played.
        var contextBuilt = false
        val context = { contextBuilt = true; "test" }
        assertEquals(BlendMode.OVERRIDE, FxSpecCoercion.Lenient.blendMode("", context))
        assertTrue(contextBuilt, "a present-but-blank value should be reported")

        assertEquals(SpreadOrder.NORMAL, FxSpecCoercion.Lenient.order("  ", CONTEXT))
    }

    @Test
    fun `problem answers the message strict would throw, and null when everything parses`() {
        assertNull(
            FxSpecCoercion.Strict.problem(
                blendMode = "override", order = "Ping_Pong",
                elementMode = "FLAT", elementFilter = " second_half ",
            )
        )

        // Each field reports itself: the write boundaries surface this string verbatim, so a
        // message naming the wrong field would send an operator hunting the wrong control.
        assertTrue(FxSpecCoercion.Strict.problem(blendMode = "ADD")!!.contains("blendMode 'ADD'"))
        assertTrue(FxSpecCoercion.Strict.problem(order = "SPIRAL")!!.contains("order"))
        assertTrue(FxSpecCoercion.Strict.problem(elementMode = "nope")!!.contains("elementMode"))
        assertTrue(FxSpecCoercion.Strict.problem(elementFilter = "nope")!!.contains("elementFilter"))

        // And it names the valid set, which is the only reason a rejection is actionable.
        assertTrue(FxSpecCoercion.Strict.problem(blendMode = "ADD")!!.contains("ADDITIVE"))
    }

    @Test
    fun `problem treats a null field as absent, not as a fault`() {
        // The optional half of every spec — `elementMode` / `elementFilter` are nullable columns,
        // and a WS layer patch sends only the fields it changes. Rejecting those would make the
        // strict policy unusable at exactly the write sites that need it.
        assertNull(FxSpecCoercion.Strict.problem())
        assertNull(FxSpecCoercion.Strict.problem(blendMode = "MAX", elementMode = null))
    }

    @Test
    fun `the shared name lookup is the nullable one both policies sit on`() {
        assertNull(FxSpecCoercion.Names.blendMode("nope"))
        assertNull(FxSpecCoercion.Names.elementMode("nope"))
        assertNull(ElementFilter.byName("nope"))
        assertNull(SpreadOrder.byName("nope"))

        // …and the enums' own `fromName` shims keep their default-on-miss contract for the
        // internal callers (a group's `defaultOrderName` metadata) that still use them.
        assertEquals(ElementFilter.ALL, ElementFilter.fromName("nope"))
        assertEquals(SpreadOrder.NORMAL, SpreadOrder.fromName("nope"))
    }
}
