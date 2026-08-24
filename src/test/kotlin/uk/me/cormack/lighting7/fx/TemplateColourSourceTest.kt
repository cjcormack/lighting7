package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.TargetRef
import java.awt.Color
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `tmpl:` colour grammar, the fixture-free resolution behind it, and what [TypedParams] does
 * with a reference it cannot honour.
 *
 * This is the replacement for the positional-palette tests that used to live in
 * `CueAssignmentResolverTest` and `CueComposerTest`. The property being pinned is different in one
 * important way: a positional ref was resolved against whatever list the *caller* threaded in, so
 * those tests were about scope. A template reference has one answer wherever it is read, so these
 * are about **identity and refusal** instead.
 */
class TemplateColourSourceTest {
    private val templateUuid: UUID = UUID.fromString("2f1c8a3e-0000-4000-8000-000000000001")

    private fun registryOf(vararg snapshots: TemplateSnapshot): TemplateRegistry {
        val byUuid = snapshots.associateBy { it.templateUuid }
        return TemplateRegistry(loader = { byUuid[it] })
    }

    private fun template(
        vararg rows: TemplateRowEntry,
        uuid: UUID = templateUuid,
        name: String = "Warm Key",
    ) = TemplateSnapshot(
        templateId = 1,
        templateUuid = uuid,
        name = name,
        fadeDurationMs = null,
        rows = rows.toList(),
    )

    private fun colourRow(
        value: String = "#ff9d4a;policy=extract",
        target: TargetRef? = null,
    ) = TemplateRowEntry(target = target, propertyName = "rgbColour", value = value)

    // ─── The grammar ────────────────────────────────────────────────────

    @Test
    fun `the grammar recognises a reference and parses its uuid`() {
        assertTrue(isTemplateColourRef("tmpl:$templateUuid"))
        assertTrue(isTemplateColourRef("  TMPL:$templateUuid  "), "case and surrounding space are not significant")
        assertEquals(templateUuid, parseTemplateColourRefUuid("tmpl:$templateUuid"))
        assertEquals("tmpl:$templateUuid", serializeTemplateColourRef(templateUuid))
    }

    @Test
    fun `a literal is not a reference, and a malformed uuid is a reference with no target`() {
        assertTrue(!isTemplateColourRef("#ff0000"))
        assertNull(parseTemplateColourRefUuid("#ff0000"))
        // Shape yes, uuid no: the caller must treat this as an unresolvable reference rather than
        // fall through to the literal parser, which would answer white for `tmpl:oops`.
        assertTrue(isTemplateColourRef("tmpl:oops"))
        assertNull(parseTemplateColourRefUuid("tmpl:oops"))
    }

    @Test
    fun `ref colon is not this grammar`() {
        // `ref:{uuid}` is retired *and* still refused at the Look write boundary as the
        // no-nesting guarantee. The two must not be confusable.
        assertTrue(!isTemplateColourRef("ref:$templateUuid"))
    }

    // ─── Fixture-free resolution ────────────────────────────────────────

    @Test
    fun `extract pulls the neutral part into white, matching an RGBW head`() {
        // The whole reason `resolveColourGeneric` exists: an effect's output is one colour for every
        // head it targets, so the policy is resolved as though the head were RGBW — which makes the
        // channels identical to the same template applied as a layer on any RGBW or RGBWA head.
        val resolved = TemplateResolver.resolveColourGeneric(
            TemplateIntent.Colour("#ff9d4a", WhitePolicy.EXTRACT),
        )!!
        // min(255, 157, 74) = 74 moves out of RGB and into white.
        assertEquals(74u.toUByte(), resolved.white)
        assertEquals(Color(255 - 74, 157 - 74, 0), resolved.color)
        assertEquals(0u.toUByte(), resolved.amber, "amber stays clear while there is a white emitter")
    }

    @Test
    fun `the cost of extract on a head with no white emitter is stated here, not implied`() {
        // Pinned because it is the sharpest edge of the fixture-free reading and easy to under-read.
        // `ColourTarget.applyExtendedChannel` drops the white byte on a head with no white property,
        // and the RGB it drops it beside has *already* had the neutral taken out — so such a head
        // receives this, uncompensated: dimmer and more saturated than the hex asked for, which is
        // worse than the RGB-only reading rather than equal to it.
        val resolved = TemplateResolver.resolveColourGeneric(
            TemplateIntent.Colour("#ff9d4a", WhitePolicy.EXTRACT),
        )!!
        assertEquals(Color(0xB5, 0x53, 0x00), resolved.color, "#FF9D4A lands as #B55300 without the emitter")
    }

    @Test
    fun `rgb-only leaves the emitters alone`() {
        val resolved = TemplateResolver.resolveColourGeneric(
            TemplateIntent.Colour("#ff9d4a", WhitePolicy.RGB_ONLY),
        )!!
        assertEquals(Color(255, 157, 74), resolved.color)
        assertEquals(0u.toUByte(), resolved.white)
    }

    @Test
    fun `an unparseable hex resolves to nothing rather than to a colour`() {
        assertNull(TemplateResolver.resolveColourGeneric(TemplateIntent.Colour("#nope", WhitePolicy.EXTRACT)))
    }

    // ─── The source ─────────────────────────────────────────────────────

    @Test
    fun `a generic colour template resolves`() {
        val source = templateColourSource(registryOf(template(colourRow())))
        val resolved = source("tmpl:$templateUuid")!!
        assertEquals(74u.toUByte(), resolved.white)
    }

    @Test
    fun `a literal answers null so the caller falls through to its own parser`() {
        val source = templateColourSource(registryOf(template(colourRow())))
        assertNull(source("#ff0000"))
        assertNull(source("red"))
    }

    @Test
    fun `an unknown uuid, a non-colour template and a per-fixture one are all refused`() {
        val position = template(
            TemplateRowEntry(target = null, propertyName = "position", value = "deg:45,12.5"),
            uuid = UUID.fromString("2f1c8a3e-0000-4000-8000-000000000002"),
            name = "Centre",
        )
        val perFixture = template(
            colourRow(target = TargetRef.Fixture("hex-1")),
            colourRow(value = "#0000ff;policy=extract", target = TargetRef.Fixture("hex-2")),
            uuid = UUID.fromString("2f1c8a3e-0000-4000-8000-000000000003"),
            name = "Per Head",
        )
        val source = templateColourSource(registryOf(position, perFixture))

        assertNull(source("tmpl:$templateUuid"), "no such template")
        assertNull(source("tmpl:${position.templateUuid}"), "a position template holds no colour")
        assertNull(source("tmpl:${perFixture.templateUuid}"), "a per-fixture template holds no single colour")
    }

    // ─── What TypedParams does with the answers ─────────────────────────

    private fun params(raw: Map<String, String>, registry: TemplateRegistry) = TypedParams(
        raw = raw,
        schema = emptyList(),
        resolveColourSource = templateColourSource(registry),
        colourSourceVersion = { registry.version },
    )

    @Test
    fun `colourList mixes literals and references, and drops a reference it cannot resolve`() {
        // The one place the list differs from a single colour: a list is allowed to come back
        // shorter, because a white entry in the middle of a cycle reads as a deliberate flash.
        val registry = registryOf(template(colourRow()))
        val p = params(mapOf("colours" to "#ff0000,tmpl:$templateUuid,tmpl:oops,#0000ff"), registry)
        val colours = p.colourList("colours")
        assertEquals(3, colours.size, "the unresolvable reference is dropped, not substituted")
        assertEquals(Color.RED, colours[0].color)
        assertEquals(74u.toUByte(), colours[1].white)
        assertEquals(Color.BLUE, colours[2].color)
    }

    @Test
    fun `a single colour falls back to the literal parser, which reads a bad reference as white`() {
        // Loud rather than dark: a black head is indistinguishable from an intentional blackout,
        // and the refusal has already been logged with its reason.
        val p = params(mapOf("colour" to "tmpl:oops"), registryOf())
        assertEquals(Color.WHITE, p.colour("colour").color)
    }

    @Test
    fun `retuning a template re-resolves a running effect's cached colour`() {
        // The property the whole change is for, and the mechanism that delivers it: the version
        // counter the palette's used to serve now belongs to the template registry.
        var stored = "#ff0000;policy=rgbonly"
        val registry = TemplateRegistry(loader = { uuid ->
            template(colourRow(value = stored), uuid = uuid).takeIf { uuid == templateUuid }
        })
        val p = params(mapOf("colour" to "tmpl:$templateUuid"), registry)
        assertEquals(Color.RED, p.colour("colour").color)

        stored = "#0000ff;policy=rgbonly"
        registry.invalidate(templateUuid)
        assertEquals(Color.BLUE, p.colour("colour").color, "the cached colour must not outlive the edit")
    }
}
