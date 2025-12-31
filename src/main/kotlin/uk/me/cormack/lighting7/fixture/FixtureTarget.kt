package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fx.FxTargetable

/**
 * Common interface for entities that can be members of fixture groups.
 *
 * Both standalone [Fixture] classes and [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement]
 * components implement this interface, enabling them to be used interchangeably
 * in groups and FX targeting.
 *
 * This interface extends [FxTargetable] to enable effect targeting, and adds
 * the [withTransaction] method required for batched DMX operations within groups.
 */
interface FixtureTarget : FxTargetable {
    /**
     * Human-readable display name for this target.
     *
     * For fixtures, this is typically the fixture name.
     * For elements, this is typically "ParentName Element N" or similar.
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
