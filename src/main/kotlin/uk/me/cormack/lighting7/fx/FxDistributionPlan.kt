package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.DistributionStrategy

/**
 * The per-member distribution values for one (strategy, member list) pair: each member's phase
 * offset, and the [EffectContext] handed to the effect for it.
 *
 * Every field of an [EffectContext] on a member pass — `groupSize`, `memberIndex`,
 * `distributionOffset`, `hasDistributionSpread`, `numDistinctSlots`, `trianglePhase` — is a pure
 * function of the strategy and the member list, so the whole array can be built once and reused
 * for every tick that pass covers. Before this the tick loops built one synthetic
 * [DistributionMemberInfo] *and* one [EffectContext] per member per tick, and asked
 * [DistributionStrategy.calculateOffset] for an offset each time — which for
 * `DistributionStrategy.RANDOM` shuffled a fresh permutation per member, making one tick O(N²).
 *
 * Immutable once built, and [EffectContext] is a data class nothing mutates, so sharing one
 * instance across ticks and across the effects that resolve to the same plan is safe.
 */
internal class DistributionPlan private constructor(
    /** Phase offset per member, in the member list's own order. */
    val offsets: DoubleArray,
    /** The context handed to the effect per member, same order as [offsets]. */
    val contexts: Array<EffectContext>,
) {
    /** Synthetic member for an element list, whose positions are always evenly spaced. */
    private class ElementSlot(
        override val index: Int,
        override val normalizedPosition: Double,
    ) : DistributionMemberInfo

    companion object {
        /** No members — the empty-list early return in the tick loops. */
        val EMPTY = DistributionPlan(DoubleArray(0), emptyArray())

        /** Plan for a real member list ([FxTargetExpansion.members]). */
        fun of(strategy: DistributionStrategy, members: List<DistributionMemberInfo>): DistributionPlan {
            val size = members.size
            if (size == 0) return EMPTY
            val offsets = strategy.offsets(members, size)
            val hasSpread = strategy.hasSpread
            val distinctSlots = strategy.distinctSlots(size)
            val trianglePhase = strategy.usesTrianglePhase
            return DistributionPlan(
                offsets,
                Array(size) { i ->
                    EffectContext(
                        groupSize = size,
                        memberIndex = members[i].index,
                        distributionOffset = offsets[i],
                        hasDistributionSpread = hasSpread,
                        numDistinctSlots = distinctSlots,
                        trianglePhase = trianglePhase,
                    )
                },
            )
        }

        /**
         * Plan for an element key list, whose members are the list positions themselves —
         * the shape the tick loops used to synthesise inline, preserved exactly (including
         * the `0.5` a single element gets).
         */
        fun forElements(strategy: DistributionStrategy, count: Int): DistributionPlan {
            if (count == 0) return EMPTY
            return of(
                strategy,
                List(count) { i ->
                    ElementSlot(i, if (count > 1) i.toDouble() / (count - 1) else 0.5)
                },
            )
        }
    }
}

/**
 * Every [DistributionPlan] one [FxInstance] can need for a given expansion and strategy, cached
 * on the instance the same way [FxTargetExpansion] is and validated the same way: by identity
 * against the expansion it was built from, plus the strategy it was built for.
 *
 * Both the element shapes are built up front for the same reason [FxTargetExpansion] builds both
 * key lists — [ElementMode] is not part of the expansion's identity, so a mode toggle must not
 * need an invalidation. The unused arms are [DistributionPlan.EMPTY], which costs nothing.
 */
internal class FxDistributionPlans private constructor(
    /** The expansion these were built from; compared by identity, not equality. */
    val expansion: FxTargetExpansion,
    /** The [FxDynamics.distributionStrategy] these were built for. */
    val strategy: DistributionStrategy,
    /** [FxTargetExpansion.Kind.GROUP_MEMBERS]. */
    val members: DistributionPlan,
    /** [FxTargetExpansion.flat]. */
    val flat: DistributionPlan,
    /** One per [FxTargetExpansion.perFixture] list, same order. */
    val perFixture: List<DistributionPlan>,
) {
    companion object {
        fun build(expansion: FxTargetExpansion, strategy: DistributionStrategy): FxDistributionPlans =
            when (expansion.kind) {
                FxTargetExpansion.Kind.GROUP_MEMBERS -> FxDistributionPlans(
                    expansion, strategy,
                    members = DistributionPlan.of(strategy, expansion.members),
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                )

                FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> FxDistributionPlans(
                    expansion, strategy,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(strategy, expansion.flat.size),
                    perFixture = emptyList(),
                )

                FxTargetExpansion.Kind.GROUP_ELEMENTS -> FxDistributionPlans(
                    expansion, strategy,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(strategy, expansion.flat.size),
                    perFixture = expansion.perFixture.map {
                        DistributionPlan.forElements(strategy, it.size)
                    },
                )

                // DIRECT_FIXTURE uses EffectContext.SINGLE; NONE applies nothing.
                FxTargetExpansion.Kind.DIRECT_FIXTURE,
                FxTargetExpansion.Kind.NONE,
                -> FxDistributionPlans(
                    expansion, strategy,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                )
            }
    }
}
