package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for [LookRegistry]: a real [Fixtures] plus a lambda loader, no database.
 */
class LookRegistryTest {

    private val universe = Universe(0, 0)
    private val uuid: UUID = UUID.fromString("2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02")

    private fun fixtures(groupMembers: List<String> = listOf("hex-1", "hex-2")): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hexes = listOf("hex-1", "hex-2", "hex-3").mapIndexed { i, key ->
                key to addFixture(HexFixture(universe, key, "Hex ${i + 1}", firstChannel = 1 + i * 12))
            }.toMap()
            createGroup<HexFixture>("front-wash") {
                addSpread(groupMembers.map { hexes.getValue(it) })
            }
        }
        return fixtures
    }

    private fun snapshot(vararg rows: LookRowEntry) = LookSnapshot(
        lookId = 4,
        lookUuid = uuid,
        name = "Warm Amber",
        editorFixtureType = null,
        palette = emptyList(),
        rows = rows.toList(),
        effects = emptyList(),
    )

    private fun registry(
        fixtures: Fixtures,
        snapshot: LookSnapshot?,
        loads: AtomicInteger = AtomicInteger(),
    ) = LookRegistry(
        fixtures = { fixtures },
        loader = { requested -> loads.incrementAndGet(); snapshot?.takeIf { it.lookUuid == requested } },
    )

    @Test
    fun `a group row expands to every member`() {
        val reg = registry(
            fixtures(),
            snapshot(LookRowEntry(TargetRef.Group("front-wash"), "colour", "#ff8800")),
        )
        assertEquals("#ff8800", reg.literalFor(uuid, "hex-1", "colour"))
        assertEquals("#ff8800", reg.literalFor(uuid, "hex-2", "colour"))
        assertNull(reg.literalFor(uuid, "hex-3", "colour"), "a non-member is not covered")
    }

    @Test
    fun `a fixture row beats a group row covering the same fixture`() {
        // Same specificity rule the cue resolver applies, resolved once at expansion time.
        val reg = registry(
            fixtures(),
            snapshot(
                LookRowEntry(TargetRef.Group("front-wash"), "colour", "#ff8800"),
                LookRowEntry(TargetRef.Fixture("hex-2"), "colour", "#00ff00"),
            ),
        )
        assertEquals("#ff8800", reg.literalFor(uuid, "hex-1", "colour"))
        assertEquals("#00ff00", reg.literalFor(uuid, "hex-2", "colour"))
    }

    @Test
    fun `entry order does not decide the winner`() {
        // The fixture row is declared *first* here; it must still win, because expansion applies
        // group rows then fixture rows rather than trusting declaration order.
        val reg = registry(
            fixtures(),
            snapshot(
                LookRowEntry(TargetRef.Fixture("hex-2"), "colour", "#00ff00"),
                LookRowEntry(TargetRef.Group("front-wash"), "colour", "#ff8800"),
            ),
        )
        assertEquals("#00ff00", reg.literalFor(uuid, "hex-2", "colour"))
    }

    @Test
    fun `property names are canonicalised on both sides`() {
        // `colour` / `color` / `rgbColour` all name one property. Whichever alias the palette was
        // stored with, any alias must find it — a silent miss here is indistinguishable from
        // "this palette doesn't cover the fixture".
        listOf("colour", "color", "rgbColour").forEach { stored ->
            val reg = registry(
                fixtures(),
                snapshot(LookRowEntry(TargetRef.Fixture("hex-1"), stored, "#ff8800")),
            )
            listOf("colour", "color", "rgbColour").forEach { queried ->
                assertEquals(
                    "#ff8800", reg.literalFor(uuid, "hex-1", queried),
                    "stored as '$stored', queried as '$queried'",
                )
            }
        }
    }

    @Test
    fun `an unknown group contributes nothing rather than throwing`() {
        val reg = registry(
            fixtures(),
            snapshot(LookRowEntry(TargetRef.Group("gone"), "colour", "#ff8800")),
        )
        assertNull(reg.literalFor(uuid, "hex-1", "colour"))
    }

    @Test
    fun `an unknown palette resolves to null`() {
        val reg = registry(fixtures(), snapshot = null)
        assertNull(reg.expanded(UUID.randomUUID()))
    }

    @Test
    fun `expansions are cached until invalidated`() {
        val loads = AtomicInteger()
        val reg = registry(
            fixtures(),
            snapshot(LookRowEntry(TargetRef.Group("front-wash"), "colour", "#ff8800")),
            loads,
        )

        reg.literalFor(uuid, "hex-1", "colour")
        reg.literalFor(uuid, "hex-2", "colour")
        assertEquals(1, loads.get(), "a second read of the same palette must not re-query")

        val versionBefore = reg.version
        reg.invalidate(uuid)
        assertTrue(reg.version > versionBefore, "invalidate bumps the version consumers cache against")
        reg.literalFor(uuid, "hex-1", "colour")
        assertEquals(2, loads.get())
    }

    @Test
    fun `invalidateAll bumps the version even with an empty cache`() {
        // A consumer caching against `version` has to see an edit that happened before anything
        // was ever expanded.
        val reg = registry(fixtures(), snapshot = null)
        val before = reg.version
        reg.invalidateAll()
        assertTrue(reg.version > before)
    }

    @Test
    fun `a group membership change is picked up after invalidateAll`() {
        // The failure this guards: a fixture added to a group silently not picking up the
        // palette, which reads as a palette bug and isn't.
        val loads = AtomicInteger()
        var live = fixtures(groupMembers = listOf("hex-1"))
        val snap = snapshot(LookRowEntry(TargetRef.Group("front-wash"), "colour", "#ff8800"))
        val reg = LookRegistry(
            fixtures = { live },
            loader = { loads.incrementAndGet(); snap },
        )

        assertEquals("#ff8800", reg.literalFor(uuid, "hex-1", "colour"))
        assertNull(reg.literalFor(uuid, "hex-2", "colour"))

        live = fixtures(groupMembers = listOf("hex-1", "hex-2"))
        assertNull(
            reg.literalFor(uuid, "hex-2", "colour"),
            "without invalidation the stale expansion is still served — this is the hazard",
        )

        reg.invalidateAll()
        assertEquals("#ff8800", reg.literalFor(uuid, "hex-2", "colour"))
    }

    @Test
    fun `an invalidation during an in-flight load is not cached as stale`() {
        // The regression this guards: invalidate() on a cache that has no entry yet is a no-op, so
        // a load already in flight inserts its *pre-edit* snapshot afterwards and every later read
        // serves it — permanently. Concretely: a cue GO resolving a ref while the operator saves an
        // edit to that palette, after which the rig keeps the old colour despite the save
        // succeeding.
        //
        // The loader below reads the value it will return *first*, then simulates the edit landing
        // and invalidating mid-build, then returns what it originally observed — i.e. a genuinely
        // stale snapshot. That ordering is the whole point: reading `current` after mutating it
        // would hand back a fresh snapshot and the test would pass even against the racy code.
        var current = "#ff8800"
        var raceOnce = true
        lateinit var reg: LookRegistry

        reg = LookRegistry(
            fixtures = ::fixtures,
            loader = {
                val observed = current
                if (raceOnce) {
                    raceOnce = false
                    current = "#0000ff"
                    reg.invalidate(uuid)
                }
                LookSnapshot(
                    lookId = 1, lookUuid = uuid, name = "Warm Amber",
                    editorFixtureType = null, palette = emptyList(), effects = emptyList(),
                    rows = listOf(LookRowEntry(TargetRef.Fixture("hex-1"), "colour", observed)),
                )
            },
        )

        assertEquals(
            "#0000ff", reg.literalFor(uuid, "hex-1", "colour"),
            "the stale build raced an invalidation and must be discarded, not cached",
        )
        assertEquals(
            "#0000ff", reg.literalFor(uuid, "hex-1", "colour"),
            "and what got cached must be the fresh build",
        )
    }
}
