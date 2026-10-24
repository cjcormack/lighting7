package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.TargetRef
import java.awt.Color
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `pal:` colour grammar, the fixture-free resolution behind it, and what [TypedParams] does
 * with a reference it cannot honour.
 *
 * This is the replacement for the positional-palette tests that used to live in
 * `CueAssignmentResolverTest` and `CueComposerTest`. The property being pinned is different in one
 * important way: a positional ref was resolved against whatever list the *caller* threaded in, so
 * those tests were about scope. A template reference has one answer wherever it is read, so these
 * are about **identity and refusal** instead.
 */
class PaletteColourSourceTest {
    private val paletteUuid: UUID = UUID.fromString("2f1c8a3e-0000-4000-8000-000000000001")

    private fun registryOf(vararg snapshots: PaletteSnapshot): PaletteRegistry {
        val byUuid = snapshots.associateBy { it.paletteUuid }
        return PaletteRegistry(loader = { byUuid[it] })
    }

    private fun template(
        vararg rows: PaletteRowEntry,
        uuid: UUID = paletteUuid,
        name: String = "Warm Key",
    ) = PaletteSnapshot(
        paletteId = 1,
        paletteUuid = uuid,
        name = name,
        fadeDurationMs = null,
        rows = rows.toList(),
    )

    private fun colourRow(
        value: String = "#ff9d4a;policy=extract",
        target: TargetRef? = null,
    ) = PaletteRowEntry(target = target, propertyName = "rgbColour", value = value)

    // ─── The grammar ────────────────────────────────────────────────────

    @Test
    fun `the grammar recognises a reference and parses its uuid`() {
        assertTrue(isPaletteColourRef("pal:$paletteUuid"))
        assertTrue(isPaletteColourRef("  PAL:$paletteUuid  "), "case and surrounding space are not significant")
        assertEquals(paletteUuid, parsePaletteColourRefUuid("pal:$paletteUuid"))
        assertEquals("pal:$paletteUuid", serializePaletteColourRef(paletteUuid))
    }

    @Test
    fun `a literal is not a reference, and a malformed uuid is a reference with no target`() {
        assertTrue(!isPaletteColourRef("#ff0000"))
        assertNull(parsePaletteColourRefUuid("#ff0000"))
        // Shape yes, uuid no: the caller must treat this as an unresolvable reference rather than
        // fall through to the literal parser, which would answer white for `pal:oops`.
        assertTrue(isPaletteColourRef("pal:oops"))
        assertNull(parsePaletteColourRefUuid("pal:oops"))
    }

    @Test
    fun `ref colon is not this grammar`() {
        // `ref:{uuid}` is retired *and* still refused at the Look write boundary as the
        // no-nesting guarantee. The two must not be confusable.
        assertTrue(!isPaletteColourRef("ref:$paletteUuid"))
    }

    // ─── Fixture-free resolution ────────────────────────────────────────

    @Test
    fun `extract pulls the neutral part into white, matching an RGBW head`() {
        // The whole reason `resolveColourGeneric` exists: an effect's output is one colour for every
        // head it targets, so the policy is resolved as though the head were RGBW — which makes the
        // channels identical to the same template applied as a layer on any RGBW or RGBWA head.
        val resolved = PaletteResolver.resolveColourGeneric(
            PaletteIntent.Colour("#ff9d4a", WhitePolicy.EXTRACT),
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
        val resolved = PaletteResolver.resolveColourGeneric(
            PaletteIntent.Colour("#ff9d4a", WhitePolicy.EXTRACT),
        )!!
        assertEquals(Color(0xB5, 0x53, 0x00), resolved.color, "#FF9D4A lands as #B55300 without the emitter")
    }

    @Test
    fun `rgb-only leaves the emitters alone`() {
        val resolved = PaletteResolver.resolveColourGeneric(
            PaletteIntent.Colour("#ff9d4a", WhitePolicy.RGB_ONLY),
        )!!
        assertEquals(Color(255, 157, 74), resolved.color)
        assertEquals(0u.toUByte(), resolved.white)
    }

    @Test
    fun `an unparseable hex resolves to nothing rather than to a colour`() {
        assertNull(PaletteResolver.resolveColourGeneric(PaletteIntent.Colour("#nope", WhitePolicy.EXTRACT)))
    }

    // ─── The source ─────────────────────────────────────────────────────

    @Test
    fun `a generic colour template resolves`() {
        val source = paletteColourSource(registryOf(template(colourRow())))
        val resolved = source("pal:$paletteUuid")!!
        assertEquals(74u.toUByte(), resolved.white)
    }

    @Test
    fun `a literal answers null so the caller falls through to its own parser`() {
        val source = paletteColourSource(registryOf(template(colourRow())))
        assertNull(source("#ff0000"))
        assertNull(source("red"))
    }

    @Test
    fun `an unknown uuid, a non-colour template and a per-fixture one are all refused`() {
        val position = template(
            PaletteRowEntry(target = null, propertyName = "position", value = "deg:45,12.5"),
            uuid = UUID.fromString("2f1c8a3e-0000-4000-8000-000000000002"),
            name = "Centre",
        )
        val perFixture = template(
            colourRow(target = TargetRef.Fixture("hex-1")),
            colourRow(value = "#0000ff;policy=extract", target = TargetRef.Fixture("hex-2")),
            uuid = UUID.fromString("2f1c8a3e-0000-4000-8000-000000000003"),
            name = "Per Head",
        )
        val source = paletteColourSource(registryOf(position, perFixture))

        assertNull(source("pal:$paletteUuid"), "no such template")
        assertNull(source("pal:${position.paletteUuid}"), "a position template holds no colour")
        assertNull(source("pal:${perFixture.paletteUuid}"), "a per-fixture template holds no single colour")
    }

    // ─── What TypedParams does with the answers ─────────────────────────

    private fun params(raw: Map<String, String>, registry: PaletteRegistry): TypedParams {
        // Scoped exactly as `createFxWithPalettes` scopes it: the version this effect's
        // colour cache watches covers only the templates its own parameters name.
        val refs = paletteColourRefsIn(raw)
        return TypedParams(
            raw = raw,
            schema = emptyList(),
            resolveColourSource = paletteColourSource(registry),
            colourSourceVersion = { registry.versionFor(refs) },
        )
    }

    @Test
    fun `colourList takes literals only, and drops a palette reference`() {
        // Colour Cycle takes literal colours (terminology plan D14): a reference resolves to nothing
        // in a list, even one that names a real colour palette, and the list comes back shorter.
        val registry = registryOf(template(colourRow()))
        val p = params(mapOf("colours" to "#ff0000,pal:$paletteUuid,pal:oops,#0000ff"), registry)
        val colours = p.colourList("colours")
        assertEquals(listOf(Color.RED, Color.BLUE), colours.map { it.color })
    }

    @Test
    fun `a palette reference in a colourList is refused at the write boundary`() {
        val schema = listOf(ParameterInfo("colours", "colourList", "#ff0000"), ParameterInfo("colour", "colour", "#ffffff"))
        assertEquals(null, colourListPaletteProblem(schema, mapOf("colours" to "#ff0000,#0000ff", "colour" to "pal:$paletteUuid")))
        val problem = colourListPaletteProblem(schema, mapOf("colours" to "#ff0000, pal:$paletteUuid"))
        assertTrue(problem != null && "'colours'" in problem, "$problem")
    }

    @Test
    fun `a single colour falls back to the literal parser, which reads a bad reference as white`() {
        // Loud rather than dark: a black head is indistinguishable from an intentional blackout,
        // and the refusal has already been logged with its reason.
        val p = params(mapOf("colour" to "pal:oops"), registryOf())
        assertEquals(Color.WHITE, p.colour("colour").color)
    }

    @Test
    fun `retuning a template re-resolves a running effect's cached colour`() {
        // The property the whole change is for, and the mechanism that delivers it: the version
        // counter the palette's used to serve now belongs to the template registry.
        var stored = "#ff0000;policy=rgbonly"
        val registry = PaletteRegistry(loader = { uuid ->
            template(colourRow(value = stored), uuid = uuid).takeIf { uuid == paletteUuid }
        })
        val p = params(mapOf("colour" to "pal:$paletteUuid"), registry)
        assertEquals(Color.RED, p.colour("colour").color)

        stored = "#0000ff;policy=rgbonly"
        registry.refresh(paletteUuid)
        assertEquals(Color.BLUE, p.colour("colour").color, "the cached colour must not outlive the edit")
    }

    // ─── Which effects an edit actually reaches ─────────────────────────

    @Test
    fun `an edit to one template leaves an effect naming another one alone`() {
        // The reason the version is per uuid. With one global counter this reload happened on every
        // template edit on the desk, for every running colour effect — each one a `snapshot` miss,
        // and (with nothing re-warming) a DB read from whichever thread got there first.
        val other = UUID.fromString("2f1c8a3e-0000-4000-8000-00000000000a")
        var stored = "#ff0000;policy=rgbonly"
        val loaded = mutableListOf<UUID>()
        val registry = PaletteRegistry(loader = { uuid ->
            loaded += uuid
            template(colourRow(value = stored), uuid = uuid)
        })
        val p = params(mapOf("colour" to "pal:$paletteUuid"), registry)
        assertEquals(Color.RED, p.colour("colour").color)
        loaded.clear()

        stored = "#0000ff;policy=rgbonly"
        registry.refresh(other)
        assertEquals(Color.RED, p.colour("colour").color, "an unrelated template's edit is not this effect's")
        assertEquals(listOf(other), loaded, "and this effect's template was not re-read at all")
    }

    @Test
    fun `an effect naming no template never re-resolves`() {
        var loads = 0
        val registry = PaletteRegistry(loader = { loads++; null })
        val p = params(mapOf("colour" to "#ff0000"), registry)
        assertEquals(Color.RED, p.colour("colour").color)
        assertEquals(0, loads, "a literal has nothing to load")

        registry.invalidateAll()
        assertEquals(0L, registry.versionFor(emptySet()), "no references means a constant version")
        assertEquals(Color.RED, p.colour("colour").color)
        assertEquals(0, loads, "and nothing to re-read when the template list changes")
    }

    @Test
    fun `a list change moves every scoped version, including one whose template does not exist yet`() {
        // An effect is allowed to name a uuid before the template exists — its colour cache holds
        // the white fallback, and the create that gives it a template has to reach it. Only
        // `invalidateAll` can, since there is no per-uuid edit to bump.
        val registry = registryOf(template(colourRow()))
        val absent = UUID.fromString("2f1c8a3e-0000-4000-8000-00000000000b")
        val before = registry.versionFor(setOf(absent))
        registry.invalidateAll()
        assertTrue(registry.versionFor(setOf(absent)) > before, "a create must reach a reference that missed")
    }

    @Test
    fun `invalidateAll reloads what it dropped before it publishes the bump`() {
        // The half of C4 that is about *where* the load happens: `invalidateAll` runs on a route
        // thread (`Fixtures.paletteListChanged`), and `loadPaletteSnapshot` opens a transaction,
        // so a cached entry has to be re-read there — and published together with the version
        // bump that invalidates the colour caches watching it, or the next tick misses anyway.
        val loaded = mutableListOf<UUID>()
        val versionsSeenWhileLoading = mutableListOf<Long>()
        lateinit var registry: PaletteRegistry
        registry = PaletteRegistry(loader = { uuid ->
            loaded += uuid
            versionsSeenWhileLoading += registry.versionFor(setOf(uuid))
            template(colourRow(), uuid = uuid)
        })
        registry.snapshot(paletteUuid)
        assertEquals(listOf(paletteUuid), loaded)
        val versionBefore = registry.versionFor(setOf(paletteUuid))

        registry.invalidateAll()
        assertEquals(listOf(paletteUuid, paletteUuid), loaded, "the dropped entry reloads on this thread")
        assertEquals(
            listOf(versionBefore, versionBefore), versionsSeenWhileLoading,
            "and it reloads *before* the bump, so no reader ever sees the new version over an empty cache",
        )
        assertTrue(registry.versionFor(setOf(paletteUuid)) > versionBefore)

        registry.snapshot(paletteUuid)
        assertEquals(2, loaded.size, "the published cache is warm, so a later read does not load")
    }

    @Test
    fun `a reference that resolved to nothing is re-warmed once its template exists`() {
        // The case the un-scoped epoch bump is justified by, and the one a cache-keys-only re-warm
        // cannot reach: an effect names a uuid before the template exists (import, clone), so the
        // miss is never cached. The create has to both invalidate its colour cache *and* leave a
        // warm entry behind, or the effect's first re-resolve is a DB read from the tick loop.
        var exists = false
        val loaded = mutableListOf<UUID>()
        val registry = PaletteRegistry(loader = { uuid ->
            loaded += uuid
            if (exists) template(colourRow(), uuid = uuid) else null
        })
        assertNull(registry.snapshot(paletteUuid), "no template yet")
        loaded.clear()

        exists = true
        registry.invalidateAll()
        assertEquals(listOf(paletteUuid), loaded, "the create re-reads the uuid that missed")

        registry.snapshot(paletteUuid)
        assertEquals(1, loaded.size, "and it is cached, so the next read — a tick's — does not load")
    }

    @Test
    fun `a re-warm read that fails is logged, not thrown at the listener chain`() {
        // `invalidateAll` was a pure memory swap before C4 and is called from an unguarded
        // `Fixtures.paletteListChanged` loop, ahead of the listener that broadcasts the change.
        // A transient DB error during the re-warm must not cost every client its notification.
        var fail = false
        val registry = PaletteRegistry(loader = { uuid ->
            if (fail) throw IllegalStateException("SQLITE_BUSY")
            template(colourRow(), uuid = uuid)
        })
        registry.snapshot(paletteUuid)

        fail = true
        registry.invalidateAll()

        fail = false
        assertEquals(
            paletteUuid, registry.snapshot(paletteUuid)?.paletteUuid,
            "the failed re-warm left the entry uncached, not the registry broken",
        )
    }
}
