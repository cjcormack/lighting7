package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fx.FxTargetable

/**
 * Common interface for entities that can be targeted by the FX system.
 *
 * This includes standalone [Fixture] classes, [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement]
 * components, and [FixtureGroup][uk.me.cormack.lighting7.fixture.group.FixtureGroup] collections.
 *
 * This interface extends [FxTargetable] to enable effect targeting, and adds
 * the [withTransaction] method required for batched DMX operations.
 */
interface FixtureTarget : FxTargetable {
    /**
     * Human-readable display name for this target.
     *
     * For fixtures, this is typically the fixture name.
     * For elements, this is typically "ParentName Element N" or similar.
     * For groups, this is typically the group name.
     */
    val displayName: String

    /**
     * Create a transaction-bound copy of this target.
     *
     * All DMX operations on the returned target will be performed within
     * the given transaction context, enabling batched channel updates with fades.
     *
     * @param transaction The transaction to bind to
     * @return A new instance bound to the transaction
     */
    fun withTransaction(transaction: ControllerTransaction): FixtureTarget
}

/**
 * Marker interface for fixture targets that can be members of a [FixtureGroup][uk.me.cormack.lighting7.fixture.group.FixtureGroup].
 *
 * This interface is implemented by:
 * - [Fixture] - standalone lighting fixtures
 * - [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement] - elements within multi-element fixtures
 *
 * Notably, [FixtureGroup][uk.me.cormack.lighting7.fixture.group.FixtureGroup] does NOT implement this interface,
 * which prevents recursive group types like `FixtureGroup<FixtureGroup<T>>`.
 * Instead, use the [subGroups][uk.me.cormack.lighting7.fixture.group.FixtureGroup.subGroups] property
 * for hierarchical group composition.
 */
interface GroupableFixture : FixtureTarget {
    override fun withTransaction(transaction: ControllerTransaction): GroupableFixture
}
