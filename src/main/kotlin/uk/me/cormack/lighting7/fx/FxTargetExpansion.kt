package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.show.Fixtures

/**
 * One group member, reduced to the three things the FX path actually reads.
 *
 * Deliberately **not** the `GroupMember` it was built from: that holds the `Fixture` itself, so
 * caching a member list would pin a whole discarded rig — including `DmxFixture`s bound to
 * `ArtNetController`s that `Fixtures.register` has already closed — for as long as the effect
 * lives. A paused effect never reads its expansion again, so "until the next read" means
 * "forever". `metadata` (the pan/tilt offsets) is not read anywhere on the FX path.
 */
internal class MemberSlot(
    val key: String,
    override val index: Int,
    override val normalizedPosition: Double,
) : DistributionMemberInfo

/**
 * What one [FxInstance] resolves to against a given generation of the fixture register: which
 * branch the engine takes for it, and the exact key lists that branch walks.
 *
 * The engine used to re-derive all of this per effect per tick — twice over, once in
 * `resolveEffectFixtureKeys` for the reset pass and again in the `process*` pass — with every
 * `untypedGroup` / `untypedFixture` lookup taking `Fixtures.registerLock.read`. None of it can
 * change between ticks: `FixtureGroup.members` is an immutable list, `allMembers` is `by lazy`,
 * and `MultiElementFixture.elements` is bound in each fixture's constructor. The only thing that
 * moves it is a whole-register rebuild through `Fixtures.register {}`, which is what
 * [Fixtures.structureVersion] counts.
 *
 * Immutable, and valid **as a unit** for its stamped [structureVersion] and [elementFilter].
 * Never mix one field read against one generation with another read against the next.
 *
 * **Why this has none of [LookRegistry]'s machinery.** That cache re-checks its version at fill
 * time, retries, and inserts with `putIfAbsent`, because its fast path returns a hit without
 * re-validating — so one stale insert is trusted forever. Here the stamp travels *inside* the
 * value and every read re-validates it, so a value built across an invalidation is rebuilt on the
 * very next read; at worst the thread that built it uses it once, which is the exposure the
 * uncached code already had. And the builder is a pure function of the register rather than a DB
 * read, so two threads racing to build the same expansion produce equal values.
 */
internal class FxTargetExpansion(
    /** The [Fixtures.structureVersion] this was resolved against. */
    val structureVersion: Long,
    /**
     * The [FxInstance.elementFilter] the filtered lists below were built for.
     *
     * `elementMode` is deliberately *not* part of the identity: it selects between [perFixture]
     * and [flat] at dispatch time, and both are built here, so toggling it costs nothing.
     * `distributionStrategy` is not either — it moves the offsets, not which keys resolve.
     */
    val elementFilter: ElementFilter,
    /** Which branch the engine takes for this effect. */
    val kind: Kind,
    /**
     * Every fixture/element key the effect writes to — what the effect *covers*, for the reset
     * pass, provenance and the Layer 4 publish paths.
     *
     * **Deliberately not filtered by [elementFilter]**, matching the `resolveEffectFixtureKeys`
     * this replaces. Coverage and application are different questions and the two lists really
     * do differ: `SECOND_HALF` on a one-element fixture covers that element while painting
     * nothing on it, so the reset pass returns it to the layer below and nothing repaints. That
     * asymmetry is pre-existing and preserved on purpose.
     */
    val coverageKeys: List<String>,
    /**
     * [Kind.FIXTURE_ELEMENTS], and [Kind.GROUP_ELEMENTS] under [ElementMode.FLAT]: the keys the
     * apply loop walks, in distribution order, with [elementFilter] already applied.
     *
     * For a group this is filtered against the **global** flat index across every member, which
     * is what makes a FLAT chase sweep the whole rig.
     *
     * [Kind.DIRECT_FIXTURE] also fills it, with the single target key — an element filter has
     * nothing to bite on there, so it is *not* applied, and the engine reads the key off
     * [FxTarget.targetKey] rather than out of here. [Kind.GROUP_MEMBERS] leaves it empty; that
     * branch walks [members].
     */
    val flat: List<String>,
    /**
     * [Kind.GROUP_ELEMENTS] under [ElementMode.PER_FIXTURE]: one filtered element-key list per
     * group member, so each fixture distributes across its own elements independently.
     *
     * Each inner list is filtered against **that member's own** element count, and its `size` is
     * the group size handed to the phase calculation — so a group of fixtures with different
     * element counts keeps giving its members genuinely different phases, as it does today.
     * Members that are not multi-element contribute no entry at all.
     */
    val perFixture: List<List<String>>,
    /** [Kind.GROUP_MEMBERS] only: the members to distribute across. Empty otherwise. */
    val members: List<MemberSlot>,
) {
    /**
     * The branch the engine takes. Mirrors the decision the `process*` functions used to make
     * inline: does the target itself have the property, do its elements, and — for a group of
     * multi-element fixtures — [ElementMode] picks between [perFixture] and [flat].
     */
    enum class Kind {
        /** Nothing to do: target missing, group empty, or neither it nor its elements have the property. */
        NONE,

        /** A fixture target that has the property itself. */
        DIRECT_FIXTURE,

        /** A fixture target whose elements have the property; distributes across [flat]. */
        FIXTURE_ELEMENTS,

        /** A group whose members have the property directly; distributes across [members]. */
        GROUP_MEMBERS,

        /** A group of multi-element fixtures; [ElementMode] picks [perFixture] or [flat]. */
        GROUP_ELEMENTS,
    }

    /** True when the effect paints element keys rather than the target's own key. */
    val isElementExpanded: Boolean
        get() = kind == Kind.FIXTURE_ELEMENTS || kind == Kind.GROUP_ELEMENTS

    companion object {
        /** A resolved "nothing to do", carrying only the stamps that decide when to re-derive. */
        fun none(structureVersion: Long, elementFilter: ElementFilter) = FxTargetExpansion(
            structureVersion = structureVersion,
            elementFilter = elementFilter,
            kind = Kind.NONE,
            coverageKeys = emptyList(),
            flat = emptyList(),
            perFixture = emptyList(),
            members = emptyList(),
        )
    }
}
