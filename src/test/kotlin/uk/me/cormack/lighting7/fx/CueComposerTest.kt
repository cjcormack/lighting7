package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.LayerSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CueComposer.cook] — the flattening step that turns a cue's ordered layer stack plus
 * its local rows into exactly one contributor per (fixture, property).
 *
 * These are the tests whose absence let the old accidental behaviour survive: every
 * multi-contributor case in `CueAssignmentResolverTest` uses distinct priorities, so the
 * exact-tie path that decided within-cue precedence never ran there.
 */
class CueComposerTest {

    private val universe = Universe(0, 0)
    private val cueId = 7
    private val priority = 3_002_001

    private fun fixturesWithTwoHexesInAGroup(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            createGroup<HexFixture>("front-wash") {
                addSpread(listOf(hex1, hex2))
            }
        }
        return fixtures
    }

    // ─── Look / layer builders ──────────────────────────────────────────

    private fun lookRow(
        targetType: String = "deferred",
        targetKey: String = "",
        propertyName: String,
        value: String,
    ) = LookRowEntry(
        target = if (targetType == "deferred") null else uk.me.cormack.lighting7.models.TargetRef.of(targetType, targetKey),
        propertyName = propertyName,
        value = value,
    )

    private fun look(
        name: String,
        vararg rows: LookRowEntry,
    ): LookSnapshot = LookSnapshot(
        lookId = name.hashCode(),
        lookUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        rows = rows.toList(),
        effects = emptyList(),
    )

    /** A registry backed by a plain map — no database, no invalidation traffic. */
    private fun registryOf(fixtures: Fixtures, vararg looks: LookSnapshot): LookRegistry {
        val byUuid = looks.associateBy { it.lookUuid }
        return LookRegistry(fixtures = { fixtures }, loader = { byUuid[it] })
    }

    private fun layer(
        look: LookSnapshot,
        sortOrder: Int,
        targets: List<CueTargetDto> = emptyList(),
        propertyMask: String? = null,
        blendMode: String = "OVERRIDE",
        amount: Double = 1.0,
        enabled: Boolean = true,
        layerId: Int = look.lookId,
        stomp: Boolean = false,
    ) = CookLayer(
        source = LayerSource.look(look.lookId, look.lookUuid, look.name),
        sortOrder = sortOrder,
        enabled = enabled,
        targets = targets,
        propertyMask = propertyMask,
        blendMode = blendMode,
        amount = amount,
        layerId = layerId,
        stomp = stomp,
    )

    private fun localSlider(
        targetKey: String,
        propertyName: String = "dimmer",
        value: UByte,
        targetIsGroup: Boolean = false,
        moveInDark: Boolean = false,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = targetIsGroup,
        propertyName = propertyName,
        category = PropertyCategory.DIMMER,
        compositionOverride = CompositionRule.UNSET,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
        moveInDark = moveInDark,
    )

    private fun cook(
        fixtures: Fixtures,
        registry: LookRegistry,
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment> = emptyList(),
    ) = cookFull(fixtures, registry, layers, localRows).rows

    /** As [cook], but the whole [CookResult] — for the tests that assert on stomp suppression. */
    private fun cookFull(
        fixtures: Fixtures,
        registry: LookRegistry,
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment> = emptyList(),
    ) = CueComposer.cook(
        fixtures = fixtures,
        cueId = cueId,
        priority = priority,
        layers = layers,
        localRows = localRows,
        lookRegistry = registry,
    )

    /** The cooked winner for one key, as `lookName@index`, or null when a local row won. */
    private fun List<CueAssignmentResolver.Assignment>.winnerAt(
        targetKey: String,
        propertyName: String = "dimmer",
    ): String? {
        val row = single { it.targetKey == targetKey && it.propertyName == propertyName }
        return row.layerWinner?.let { "${it.source.name}@${it.index}" }
    }

    private fun sliderAt(
        rows: List<CueAssignmentResolver.Assignment>,
        targetKey: String,
        propertyName: String = "dimmer",
    ): UByte {
        val row = rows.single { it.targetKey == targetKey && it.propertyName == propertyName }
        return assertIs<CueAssignmentResolver.PropertyValue.Slider>(row.value).value
    }

    private fun colourAt(
        rows: List<CueAssignmentResolver.Assignment>,
        targetKey: String,
    ): ExtendedColour {
        val row = rows.single { it.targetKey == targetKey && it.propertyName == "rgbColour" }
        return assertIs<CueAssignmentResolver.PropertyValue.Colour>(row.value).value
    }

    // ─── The invariant ──────────────────────────────────────────────────

    @Test
    fun `cook never emits two rows for the same fixture and property`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        // Every way of hitting one key at once: two layers, a group row and a fixture row inside
        // one Look, and a local row on top.
        val a = look("A", lookRow(propertyName = "dimmer", value = "100"))
        val b = look(
            "B",
            lookRow(targetType = "group", targetKey = "front-wash", propertyName = "dimmer", value = "150"),
            lookRow(targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "200"),
        )
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("group", "front-wash"))),
                layer(b, 1),
            ),
            localRows = listOf(localSlider("hex-2", value = 250u)),
        )
        val keys = rows.map { it.targetKey to it.propertyName }
        assertEquals(keys.size, keys.toSet().size, "one contributor per (fixture, property): $keys")
    }

    // ─── Precedence: later layer wins, local wins over all ──────────────

    @Test
    fun `HTP category resolves later-layer-wins inside a cue, not max`() {
        // The named behaviour change: layered intensity becomes later-wins. Under the old
        // concatenate-at-equal-priority scheme this pair composed as max() = 200.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val bright = look("Bright", lookRow(propertyName = "dimmer", value = "200"))
        val dim = look("Dim", lookRow(propertyName = "dimmer", value = "40"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, bright, dim),
            listOf(
                layer(bright, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(dim, 1, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            ),
        )
        assertEquals(40u.toUByte(), sliderAt(rows, "hex-1"), "the later layer wins even though it is darker")
    }

    @Test
    fun `LTP category resolves later-layer-wins inside a cue`() {
        // Under the old scheme the *earlier* contributor won an exact priority tie, so this is the
        // ordering flip the migration bakes in.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val red = look("Red", lookRow(propertyName = "rgbColour", value = "#ff0000"))
        val blue = look("Blue", lookRow(propertyName = "rgbColour", value = "#0000ff"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, red, blue),
            listOf(
                layer(red, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(blue, 1, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            ),
        )
        assertEquals(Color(0, 0, 255), colourAt(rows, "hex-1").color)
    }

    @Test
    fun `local rows beat every layer`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "100"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "200"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(b, 1, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            ),
            localRows = listOf(localSlider("hex-1", value = 12u)),
        )
        assertEquals(12u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `sortOrder decides, not list order`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "100"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "200"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            // b is declared first but sorts last, so it must win.
            listOf(
                layer(b, 9, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(a, 1, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            ),
        )
        assertEquals(200u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `a disabled layer contributes nothing`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "100"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "200"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(b, 1, targets = listOf(CueTargetDto("fixture", "hex-1")), enabled = false),
            ),
        )
        assertEquals(100u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `a fixture row beats a group row inside one Look regardless of sort order`() {
        // The same fixture-beats-group specificity LookRegistry.expand applies, resolved here
        // because after cooking there is only one contributor left to carry the flag.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val mixed = look(
            "Mixed",
            lookRow(targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "20"),
            lookRow(targetType = "group", targetKey = "front-wash", propertyName = "dimmer", value = "180"),
        )
        val rows = cook(fixtures, registryOf(fixtures, mixed), listOf(layer(mixed, 0)))
        assertEquals(20u.toUByte(), sliderAt(rows, "hex-1"), "fixture row wins on hex-1")
        assertEquals(180u.toUByte(), sliderAt(rows, "hex-2"), "hex-2 keeps the group value")
        assertEquals(false, rows.single { it.targetKey == "hex-1" }.targetIsGroup)
        assertEquals(true, rows.single { it.targetKey == "hex-2" }.targetIsGroup)
    }

    // ─── Blend and amount ───────────────────────────────────────────────

    @Test
    fun `OVERRIDE at amount 0 point 5 mixes halfway over the layer beneath`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "100"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "200"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(b, 1, targets = listOf(CueTargetDto("fixture", "hex-1")), amount = 0.5),
            ),
        )
        assertEquals(150u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `a lone layer at amount 0 point 5 reads as half, mixing up from zero`() {
        // Nothing beneath, so OVERRIDE's identity (zero) stands in — which is what makes a single
        // dimmer layer at half amount read as half intensity, as an operator expects.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "200"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a),
            listOf(layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1")), amount = 0.5)),
        )
        assertEquals(100u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `an amount 0 layer contributes nothing at all`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "200"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a),
            listOf(layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1")), amount = 0.0)),
        )
        assertTrue(rows.isEmpty(), "an amount-0 layer asserts nothing rather than asserting zero")
    }

    @Test
    fun `MAX keeps the brighter of the two layers`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "180"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "60"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(b, 1, targets = listOf(CueTargetDto("fixture", "hex-1")), blendMode = "MAX"),
            ),
        )
        assertEquals(180u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `MIN keeps the darker of the two layers`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "180"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "60"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(b, 1, targets = listOf(CueTargetDto("fixture", "hex-1")), blendMode = "MIN"),
            ),
        )
        assertEquals(60u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `MULTIPLY scales the layer beneath`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "200"))
        val b = look("B", lookRow(propertyName = "dimmer", value = "128"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a, b),
            listOf(
                layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(b, 1, targets = listOf(CueTargetDto("fixture", "hex-1")), blendMode = "MULTIPLY"),
            ),
        )
        assertEquals((200 * 128 / 255).toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `a lone MULTIPLY layer reads as its own value, mixing down from full`() {
        // MULTIPLY's identity is full scale, not zero — otherwise a lone multiply layer would
        // blackout rather than assert itself.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "128"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a),
            listOf(layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1")), blendMode = "MULTIPLY")),
        )
        assertEquals(128u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `an unknown blend mode falls back to OVERRIDE rather than dropping the layer`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "77"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a),
            listOf(layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1")), blendMode = "SCREEN")),
        )
        assertEquals(77u.toUByte(), sliderAt(rows, "hex-1"))
    }

    // ─── Mask and target restriction ────────────────────────────────────

    @Test
    fun `a masked layer asserts only in-mask properties`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val both = look(
            "Both",
            lookRow(propertyName = "dimmer", value = "180"),
            lookRow(propertyName = "rgbColour", value = "#00ff00"),
        )
        val rows = cook(
            fixtures,
            registryOf(fixtures, both),
            listOf(layer(both, 0, targets = listOf(CueTargetDto("fixture", "hex-1")), propertyMask = "COLOUR")),
        )
        assertEquals(Color(0, 255, 0), colourAt(rows, "hex-1").color)
        assertNull(
            rows.firstOrNull { it.propertyName == "dimmer" },
            "dimmer is INTENSITY, outside a COLOUR mask",
        )
    }

    @Test
    fun `a layer with explicit targets asserts only those, even when the Look covers more`() {
        // This is what makes the migration coverage-preserving: a cue that referenced a palette for
        // one fixture must not start asserting every fixture the palette covered.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val wide = look(
            "Wide",
            lookRow(targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "111"),
            lookRow(targetType = "fixture", targetKey = "hex-2", propertyName = "dimmer", value = "222"),
        )
        val rows = cook(
            fixtures,
            registryOf(fixtures, wide),
            listOf(layer(wide, 0, targets = listOf(CueTargetDto("fixture", "hex-1")))),
        )
        assertEquals(1, rows.size)
        assertEquals(111u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `a bound Look with no layer targets asserts everything it covers`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val wide = look(
            "Wide",
            lookRow(targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "111"),
            lookRow(targetType = "fixture", targetKey = "hex-2", propertyName = "dimmer", value = "222"),
        )
        val rows = cook(fixtures, registryOf(fixtures, wide), listOf(layer(wide, 0)))
        assertEquals(111u.toUByte(), sliderAt(rows, "hex-1"))
        assertEquals(222u.toUByte(), sliderAt(rows, "hex-2"))
    }

    @Test
    fun `a deferred Look takes its targets from the layer, expanding groups`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val deferred = look("Deferred", lookRow(propertyName = "dimmer", value = "90"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, deferred),
            listOf(layer(deferred, 0, targets = listOf(CueTargetDto("group", "front-wash")))),
        )
        assertEquals(setOf("hex-1", "hex-2"), rows.map { it.targetKey }.toSet())
        assertTrue(rows.all { it.targetIsGroup }, "rows fanned from a group target are group-derived")
    }

    @Test
    fun `a deferred Look on a layer with no targets contributes nothing`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val deferred = look("Deferred", lookRow(propertyName = "dimmer", value = "90"))
        val rows = cook(fixtures, registryOf(fixtures, deferred), listOf(layer(deferred, 0)))
        assertTrue(rows.isEmpty(), "nothing to apply to, so nothing is asserted")
    }

    @Test
    fun `a group layer target filters a Look's bound group row to the intersection`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val grouped = look(
            "Grouped",
            lookRow(targetType = "group", targetKey = "front-wash", propertyName = "dimmer", value = "140"),
        )
        val rows = cook(
            fixtures,
            registryOf(fixtures, grouped),
            listOf(layer(grouped, 0, targets = listOf(CueTargetDto("fixture", "hex-2")))),
        )
        assertEquals(1, rows.size)
        assertEquals(140u.toUByte(), sliderAt(rows, "hex-2"))
    }

    // ─── What the local pass must carry through ─────────────────────────

    @Test
    fun `cook preserves moveInDark on local rows`() {
        // Cook must not launder this: moveInDark drives the resolver's cross-cue arming pre-pass and
        // is invisible to composition, so only a test keeps it alive. It used to check `paletteUuid`
        // alongside — the field that let Include lift a row back as a reference rather than
        // hardening it — which retired with the `ref:` grammar in session 4.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val rows = cook(
            fixtures,
            registryOf(fixtures),
            layers = emptyList(),
            localRows = listOf(localSlider("hex-1", value = 0u, moveInDark = true)),
        )
        val row = rows.single()
        assertEquals(true, row.moveInDark)
        assertEquals(0u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Slider>(row.value).value)
    }

    @Test
    fun `cook preserves the cue id, priority and a fade weight of 1`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val a = look("A", lookRow(propertyName = "dimmer", value = "100"))
        val rows = cook(
            fixtures,
            registryOf(fixtures, a),
            listOf(layer(a, 0, targets = listOf(CueTargetDto("fixture", "hex-1")))),
        )
        val row = rows.single()
        assertEquals(cueId, row.cueId)
        assertEquals(priority, row.priority)
        assertEquals(1.0, row.fadeWeight, "crossfade progress is applied per-cue at publish time")
    }

    @Test
    fun `a local fixture row beats a local group-derived row for the same key`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val rows = cook(
            fixtures,
            registryOf(fixtures),
            layers = emptyList(),
            localRows = listOf(
                localSlider("hex-1", value = 150u, targetIsGroup = true),
                localSlider("hex-1", value = 50u, targetIsGroup = false),
                localSlider("hex-2", value = 150u, targetIsGroup = true),
            ),
        )
        assertEquals(50u.toUByte(), sliderAt(rows, "hex-1"))
        assertEquals(150u.toUByte(), sliderAt(rows, "hex-2"))
    }

    // ─── Timed layers ───────────────────────────────────────────────────

    @Test
    fun `a timed layer is excluded until its id is passed in`() {
        // Timed layers contribute at fire time. CueTriggerManager re-cooks the whole cue with the
        // fired layer included rather than appending its rows, because appending would put two
        // contributors on one key.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val base = look("Base", lookRow(propertyName = "dimmer", value = "100"))
        val timed = look("Timed", lookRow(propertyName = "dimmer", value = "255"))
        val layers = listOf(
            layer(base, 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            layer(timed, 1, targets = listOf(CueTargetDto("fixture", "hex-1"))).copy(delayMs = 500L),
        )
        val registry = registryOf(fixtures, base, timed)

        val beforeFire = CueComposer.cook(
            fixtures, cueId, priority, layers, emptyList(), registry,
        ).rows
        assertEquals(100u.toUByte(), sliderAt(beforeFire, "hex-1"))

        val afterFire = CueComposer.cook(
            fixtures, cueId, priority, layers, emptyList(), registry,
            includeTimed = setOf(timed.lookId),
        ).rows
        assertEquals(255u.toUByte(), sliderAt(afterFire, "hex-1"))
        val keys = afterFire.map { it.targetKey to it.propertyName }
        assertEquals(keys.size, keys.toSet().size, "firing a timed layer must not double up a key")
    }

    @Test
    fun `two timed layers on one look fire independently`() {
        // Keyed on the *look*, firing either layer would pull in both, so the second layer's
        // contribution would land at the first layer's delay. ADDITIVE makes the difference
        // observable: two layers of one look are otherwise idempotent under OVERRIDE.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val same = look("Same", lookRow(propertyName = "dimmer", value = "100"))
        val target = listOf(CueTargetDto("fixture", "hex-1"))
        val first = layer(same, 0, targets = target, blendMode = "ADDITIVE", layerId = 101)
            .copy(delayMs = 100L)
        val second = layer(same, 1, targets = target, blendMode = "ADDITIVE", layerId = 102)
            .copy(delayMs = 900L)
        val layers = listOf(first, second)
        val registry = registryOf(fixtures, same)

        val afterFirstFire = CueComposer.cook(
            fixtures, cueId, priority, layers, emptyList(), registry,
            includeTimed = setOf(first.layerId),
        ).rows
        assertEquals(
            100u.toUByte(),
            sliderAt(afterFirstFire, "hex-1"),
            "the second layer must not contribute until its own delay elapses",
        )

        val afterBoth = CueComposer.cook(
            fixtures, cueId, priority, layers, emptyList(), registry,
            includeTimed = setOf(first.layerId, second.layerId),
        ).rows
        assertEquals(200u.toUByte(), sliderAt(afterBoth, "hex-1"))
    }

    @Test
    fun `an amount-0 layer spawns no effects`() {
        // `cook` drops an amount-0 layer outright, so `cookEffects` must agree — otherwise pulling
        // Amount to zero mutes a layer's values while leaving its effects running.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val withEffect = LookSnapshot(
            lookId = 55,
            lookUuid = UUID.nameUUIDFromBytes("muted".toByteArray()),
            name = "Muted",
            rows = emptyList(),
            effects = listOf(
                LookEffectEntry(
                    target = null,
                    effectType = "SineWave",
                    category = "dimmer",
                    propertyName = "dimmer",
                    beatDivision = 1.0,
                    blendMode = "OVERRIDE",
                    distribution = "LINEAR",
                    phaseOffset = 0.0,
                    elementMode = null,
                    elementFilter = null,
                    stepTiming = null,
                    parameters = emptyMap(),
                    speedMasterUuid = null,
                    rateSpeedMasterUuid = null,
                ),
            ),
        )
        val registry = registryOf(fixtures, withEffect)
        val target = listOf(CueTargetDto("fixture", "hex-1"))

        assertEquals(
            1,
            CueComposer.cookEffects(
                fixtures, cueId, listOf(layer(withEffect, 0, targets = target)), registry,
            ).size,
        )
        assertTrue(
            CueComposer.cookEffects(
                fixtures, cueId, listOf(layer(withEffect, 0, targets = target, amount = 0.0)), registry,
            ).isEmpty(),
        )
    }

    // ─── which layer won ────────────────────────────────────────────────

    @Test
    fun `cook names the layer that produced each row`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val warm = look("Warm", lookRow(propertyName = "dimmer", value = "200"))
        val cool = look("Cool", lookRow(propertyName = "dimmer", value = "40"))
        val registry = registryOf(fixtures, warm, cool)

        val rows = cook(
            fixtures, registry,
            listOf(
                layer(warm, sortOrder = 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(cool, sortOrder = 1, targets = listOf(CueTargetDto("fixture", "hex-2"))),
            ),
        )

        assertEquals("Warm@0", rows.winnerAt("hex-1"))
        assertEquals("Cool@1", rows.winnerAt("hex-2"))
    }

    @Test
    fun `the winner is the last layer to write a key, not the first`() {
        // The trap this exists for: the accumulator is keyed by (target, property) and keeps the
        // *insertion* position, so hex-1 still sits where Warm put it even after Cool overwrites
        // it. Anything deriving the winner from output index reports Warm — wrong exactly in the
        // overlapping case that matters. hex-2 is here to give the map a second slot, so an
        // index-derived answer would be visibly off rather than coincidentally right.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val warm = look(
            "Warm",
            lookRow("fixture", "hex-1", "dimmer", "200"),
            lookRow("fixture", "hex-2", "dimmer", "200"),
        )
        val cool = look("Cool", lookRow("fixture", "hex-1", "dimmer", "40"))
        val registry = registryOf(fixtures, warm, cool)

        val rows = cook(
            fixtures, registry,
            listOf(layer(warm, sortOrder = 0), layer(cool, sortOrder = 1)),
        )

        assertEquals("Cool@1", rows.winnerAt("hex-1"), "the later layer owns the key")
        assertEquals("Warm@0", rows.winnerAt("hex-2"))
        assertEquals(40u.toUByte(), sliderAt(rows, "hex-1"))
    }

    @Test
    fun `a blend that keeps the value beneath still names the blending layer as the winner`() {
        // MAX at 60 over 200 leaves 200 on the wire, but Cool is still the layer that decided it.
        // Reporting Warm would tell the operator to go and edit a layer that is no longer in
        // control of that key.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val warm = look("Warm", lookRow("fixture", "hex-1", "dimmer", "200"))
        val cool = look("Cool", lookRow("fixture", "hex-1", "dimmer", "60"))
        val registry = registryOf(fixtures, warm, cool)

        val rows = cook(
            fixtures, registry,
            listOf(layer(warm, sortOrder = 0), layer(cool, sortOrder = 1, blendMode = "MAX")),
        )

        assertEquals(200u.toUByte(), sliderAt(rows, "hex-1"))
        assertEquals("Cool@1", rows.winnerAt("hex-1"))
    }

    @Test
    fun `a local row wins and reports no layer at all`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val warm = look("Warm", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, warm)

        val rows = cook(
            fixtures, registry,
            listOf(layer(warm, sortOrder = 0)),
            localRows = listOf(localSlider("hex-1", value = 111u)),
        )

        assertEquals(111u.toUByte(), sliderAt(rows, "hex-1"))
        assertNull(rows.winnerAt("hex-1"), "a local row belongs to no layer")
    }

    @Test
    fun `the winner index ranks contributing layers, skipping disabled and amount-zero ones`() {
        // The index feeds ProgrammerStore's reserved seq band, where it has to be a dense rank over
        // the layers that actually contribute — a gap would leave an unused seq and, worse, imply a
        // layer is present when it is not.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val off = look("Off", lookRow("fixture", "hex-1", "dimmer", "1"))
        val zero = look("Zero", lookRow("fixture", "hex-1", "dimmer", "2"))
        val warm = look("Warm", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, off, zero, warm)

        val rows = cook(
            fixtures, registry,
            listOf(
                layer(off, sortOrder = 0, enabled = false),
                layer(zero, sortOrder = 1, amount = 0.0),
                layer(warm, sortOrder = 2),
            ),
        )

        assertEquals("Warm@0", rows.winnerAt("hex-1"), "the only contributing layer ranks first")
    }

    @Test
    fun `the winner rides on the row, so it survives the resolver's own ordering`() {
        // cook's output order is insertion order, and LayerResolver picks the winning *assignment*
        // per key before reading any field off it. Carrying the winner on the row rather than in a
        // parallel map is what makes those two facts independent — there is no second structure to
        // fall out of step with the assignments it describes.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val warm = look("Warm", lookRow(propertyName = "dimmer", value = "200"))
        val registry = registryOf(fixtures, warm)

        val rows = cook(fixtures, registry, listOf(layer(warm, sortOrder = 0)))
        assertTrue(rows.all { it.layerWinner != null }, "every layer-produced row names its layer")
        assertEquals(rows.map { it.targetKey }.distinct(), rows.map { it.targetKey })
    }

    // ─── Within-cue stomp (FU-LOOK-STOMP-WITHIN-CUE) ────────────────────

    @Test
    fun `a stomping layer suppresses the layers below it on every property it asserts`() {
        // The escape hatch for the one thing layer order cannot express: effects are Layer 3 and
        // values Layer 4, so a lower layer's colour effect beats a higher layer's static colour
        // whatever the order says. `stomp` on the higher layer is how the operator says otherwise.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val chase = look("Chase", lookRow("fixture", "hex-1", "dimmer", "10"))
        val hold = look("Hold", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, chase, hold)

        val cooked = cookFull(
            fixtures, registry,
            listOf(
                layer(chase, sortOrder = 0, layerId = 11),
                layer(hold, sortOrder = 1, layerId = 12, stomp = true),
            ),
        )

        assertEquals(
            mapOf(11 to mapOf("hex-1" to setOf("dimmer"))),
            cooked.stompSuppression,
            "only the lower layer is suppressed, and only on the property the stomper asserts",
        )
    }

    @Test
    fun `a stomping layer does not suppress itself or anything above it`() {
        // Strictly below. Suppressing its own effects would leave a layer no way to express
        // itself at all; suppressing the layers above would invert the precedence rule that the
        // whole cook exists to state.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val bottom = look("Bottom", lookRow("fixture", "hex-1", "dimmer", "10"))
        val middle = look("Middle", lookRow("fixture", "hex-1", "dimmer", "120"))
        val top = look("Top", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, bottom, middle, top)

        val cooked = cookFull(
            fixtures, registry,
            listOf(
                layer(bottom, sortOrder = 0, layerId = 21),
                layer(middle, sortOrder = 1, layerId = 22, stomp = true),
                layer(top, sortOrder = 2, layerId = 23),
            ),
        )

        assertEquals(setOf(21), cooked.stompSuppression.keys)
    }

    @Test
    fun `stomp suppression follows sortOrder, not array position`() {
        // `sortOrder` is authoritative everywhere else in the layer model; a stomper handed to
        // cook first but sorted last must still suppress the layer that sorts below it.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val quiet = look("Quiet", lookRow("fixture", "hex-1", "dimmer", "10"))
        val loud = look("Loud", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, quiet, loud)

        val cooked = cookFull(
            fixtures, registry,
            listOf(
                layer(loud, sortOrder = 5, layerId = 32, stomp = true),
                layer(quiet, sortOrder = 1, layerId = 31),
            ),
        )

        assertEquals(setOf(31), cooked.stompSuppression.keys)
    }

    @Test
    fun `a masked stomping layer only suppresses the family it still asserts`() {
        // The mask runs before the assertion is recorded, so stomp inherits masking for free —
        // which is what makes a colour-masked layer a colour-only stomp.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val below = look(
            "Below",
            lookRow("fixture", "hex-1", "dimmer", "10"),
            lookRow("fixture", "hex-1", "colour", "#ff0000"),
        )
        val stomper = look(
            "Stomper",
            lookRow("fixture", "hex-1", "dimmer", "200"),
            lookRow("fixture", "hex-1", "colour", "#00ff00"),
        )
        val registry = registryOf(fixtures, below, stomper)

        val cooked = cookFull(
            fixtures, registry,
            listOf(
                layer(below, sortOrder = 0, layerId = 41),
                layer(stomper, sortOrder = 1, layerId = 42, stomp = true, propertyMask = "COLOUR"),
            ),
        )

        assertEquals(
            setOf("rgbColour"),
            cooked.stompSuppression.getValue(41).getValue("hex-1"),
            "the masked-out dimmer row asserted nothing, so it stomps nothing",
        )
    }

    @Test
    fun `a disabled stomping layer stomps nothing`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val below = look("Below", lookRow("fixture", "hex-1", "dimmer", "10"))
        val stomper = look("Stomper", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, below, stomper)

        val cooked = cookFull(
            fixtures, registry,
            listOf(
                layer(below, sortOrder = 0, layerId = 51),
                layer(stomper, sortOrder = 1, layerId = 52, stomp = true, enabled = false),
            ),
        )

        assertTrue(cooked.stompSuppression.isEmpty())
    }

    @Test
    fun `no stomping layer means no suppression map at all`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val warm = look("Warm", lookRow("fixture", "hex-1", "dimmer", "200"))
        val registry = registryOf(fixtures, warm)

        assertTrue(cookFull(fixtures, registry, listOf(layer(warm, sortOrder = 0))).stompSuppression.isEmpty())
    }

    // ─── The cross-cue overlap's layer half ─────────────────────────────

    @Test
    fun `assertedKeys covers a layer's fixtures and names the group they came through`() {
        // The gap this closes: the cue-level stomp overlap was built from local rows alone, so a
        // cue whose colour came entirely from a layer stomped nothing on colour. A group-targeted
        // effect is matched on the *group's* key, which cook's per-fixture rows never carry — so
        // the alias has to be recorded here or that effect can never overlap.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val wash = look("Wash", lookRow(propertyName = "dimmer", value = "200"))
        val registry = registryOf(fixtures, wash)

        val cooked = cookFull(
            fixtures, registry,
            listOf(layer(wash, sortOrder = 0, targets = listOf(CueTargetDto("group", "front-wash")))),
        )

        assertEquals(
            setOf(
                FxEngine.PropertyKey("hex-1", "dimmer"),
                FxEngine.PropertyKey("hex-2", "dimmer"),
                FxEngine.PropertyKey("front-wash", "dimmer"),
            ),
            cooked.assertedKeys,
        )
    }

    @Test
    fun `assertedKeys records a key a higher layer went on to overwrite`() {
        // Which is the reason it is a separate field rather than something read off the rows: the
        // rows keep only the winner per key, and a layer that asserted and then lost still
        // asserted.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val one = look("One", lookRow(propertyName = "dimmer", value = "10"))
        val two = look("Two", lookRow(propertyName = "dimmer", value = "200"))
        val registry = registryOf(fixtures, one, two)

        val cooked = cookFull(
            fixtures, registry,
            listOf(
                layer(one, sortOrder = 0, targets = listOf(CueTargetDto("fixture", "hex-1"))),
                layer(two, sortOrder = 1, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            ),
        )

        assertEquals(1, cooked.rows.size, "one contributor per key, as always")
        assertEquals(setOf(FxEngine.PropertyKey("hex-1", "dimmer")), cooked.assertedKeys)
    }
}
