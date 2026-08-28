package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The point of [EffectSpecCoercion] is that one bad string has one outcome *per policy*, not one
 * per field — which is what these tests pin. Before it existed, `blendMode` and `elementMode`
 * threw on an unknown value while `distributionStrategy` and `elementFilter` silently defaulted,
 * so the same typo produced a 400 or a quiet LINEAR depending on where it landed.
 */
class EffectSpecCoercionTest {

    private val CONTEXT = { "test" }

    @Test
    fun `strict rejects an unknown value in every field`() {
        assertFailsWith<IllegalArgumentException> { EffectSpecCoercion.Strict.blendMode("nope") }
        assertFailsWith<IllegalArgumentException> { EffectSpecCoercion.Strict.elementMode("nope") }
        assertFailsWith<IllegalArgumentException> { EffectSpecCoercion.Strict.elementFilter("nope") }
        assertFailsWith<IllegalArgumentException> { EffectSpecCoercion.Strict.distribution("nope") }
    }

    @Test
    fun `strict rejects blank rather than treating it as absent`() {
        assertFailsWith<IllegalArgumentException> { EffectSpecCoercion.Strict.blendMode("") }
        assertFailsWith<IllegalArgumentException> { EffectSpecCoercion.Strict.distribution("   ") }
    }

    @Test
    fun `the rejection names the valid set, because the client has to fix it`() {
        val message = assertFailsWith<IllegalArgumentException> {
            EffectSpecCoercion.Strict.blendMode("Overide")
        }.message ?: ""
        assertTrue("Overide" in message, "should quote the offending value: $message")
        assertTrue("OVERRIDE" in message && "MULTIPLY" in message, "should list valid modes: $message")
    }

    @Test
    fun `both policies accept any casing and surrounding whitespace`() {
        assertEquals(BlendMode.MULTIPLY, EffectSpecCoercion.Strict.blendMode(" multiply "))
        assertEquals(ElementMode.FLAT, EffectSpecCoercion.Strict.elementMode("Flat"))
        assertEquals(ElementFilter.SECOND_HALF, EffectSpecCoercion.Strict.elementFilter("second_half"))
        assertEquals(DistributionStrategy.PING_PONG, EffectSpecCoercion.Strict.distribution("Ping_Pong"))
        assertEquals(BlendMode.MAX, EffectSpecCoercion.Lenient.blendMode("max", CONTEXT))
    }

    @Test
    fun `lenient defaults every field, so a stored spec still fires`() {
        assertEquals(BlendMode.OVERRIDE, EffectSpecCoercion.Lenient.blendMode("nope", CONTEXT))
        assertEquals(ElementMode.PER_FIXTURE, EffectSpecCoercion.Lenient.elementMode("nope", CONTEXT))
        assertEquals(ElementFilter.ALL, EffectSpecCoercion.Lenient.elementFilter("nope", CONTEXT))
        assertEquals(DistributionStrategy.LINEAR, EffectSpecCoercion.Lenient.distribution("nope", CONTEXT))
    }

    @Test
    fun `lenient treats null as absent, without building the log context`() {
        var contextBuilt = false
        val context = { contextBuilt = true; "test" }
        assertEquals(ElementMode.PER_FIXTURE, EffectSpecCoercion.Lenient.elementMode(null, context))
        assertEquals(ElementFilter.ALL, EffectSpecCoercion.Lenient.elementFilter(null, context))
        assertFalse(contextBuilt, "an unset optional field is not a fault and must not log")
    }

    @Test
    fun `lenient treats a present blank as a fault, not as absent`() {
        // `DaoCueLayers.blend_mode` is NOT NULL with a stored default, so "" is a written-wrong
        // row rather than an omission — and the warn is the operator's only clue that the blend
        // shown in the UI is not the blend being played.
        var contextBuilt = false
        val context = { contextBuilt = true; "test" }
        assertEquals(BlendMode.OVERRIDE, EffectSpecCoercion.Lenient.blendMode("", context))
        assertTrue(contextBuilt, "a present-but-blank value should be reported")

        assertEquals(DistributionStrategy.LINEAR, EffectSpecCoercion.Lenient.distribution("  ", CONTEXT))
    }

    @Test
    fun `problem answers the message strict would throw, and null when everything parses`() {
        assertNull(
            EffectSpecCoercion.Strict.problem(
                blendMode = "override", distribution = "Ping_Pong",
                elementMode = "FLAT", elementFilter = " second_half ",
            )
        )

        // Each field reports itself: the write boundaries surface this string verbatim, so a
        // message naming the wrong field would send an operator hunting the wrong control.
        assertTrue(EffectSpecCoercion.Strict.problem(blendMode = "ADD")!!.contains("blendMode 'ADD'"))
        assertTrue(EffectSpecCoercion.Strict.problem(distribution = "SPIRAL")!!.contains("distributionStrategy"))
        assertTrue(EffectSpecCoercion.Strict.problem(elementMode = "nope")!!.contains("elementMode"))
        assertTrue(EffectSpecCoercion.Strict.problem(elementFilter = "nope")!!.contains("elementFilter"))

        // And it names the valid set, which is the only reason a rejection is actionable.
        assertTrue(EffectSpecCoercion.Strict.problem(blendMode = "ADD")!!.contains("ADDITIVE"))
    }

    @Test
    fun `problem treats a null field as absent, not as a fault`() {
        // The optional half of every spec — `elementMode` / `elementFilter` are nullable columns,
        // and a WS layer patch sends only the fields it changes. Rejecting those would make the
        // strict policy unusable at exactly the write sites that need it.
        assertNull(EffectSpecCoercion.Strict.problem())
        assertNull(EffectSpecCoercion.Strict.problem(blendMode = "MAX", elementMode = null))
    }

    @Test
    fun `the shared name lookup is the nullable one both policies sit on`() {
        assertNull(EffectSpecCoercion.Names.blendMode("nope"))
        assertNull(EffectSpecCoercion.Names.elementMode("nope"))
        assertNull(ElementFilter.byName("nope"))
        assertNull(DistributionStrategy.byName("nope"))

        // …and the enums' own `fromName` shims keep their default-on-miss contract for the
        // internal callers (a group's `defaultDistributionName` metadata) that still use them.
        assertEquals(ElementFilter.ALL, ElementFilter.fromName("nope"))
        assertEquals(DistributionStrategy.LINEAR, DistributionStrategy.fromName("nope"))
    }
}
