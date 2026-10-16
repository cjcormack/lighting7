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
    /**
     * The rank count after the spread's shape (effects plan §3.3, step 4) — every context's
     * [EffectContext.numDistinctSlots], and what [FxDynamics.distinctSlots] answers per pass.
     */
    val slots: Int,
) {
    /** Synthetic member for an element list, whose positions are always evenly spaced. */
    private class ElementSlot(
        override val index: Int,
        override val normalizedPosition: Double,
    ) : DistributionMemberInfo

    companion object {
        /** No members — the empty-list early return in the tick loops. */
        val EMPTY = DistributionPlan(DoubleArray(0), emptyArray(), 0)

        /**
         * Plan for a real member list ([FxTargetExpansion.members]): the Order's offsets (§3.3 step
         * 1), shaped by [shape] (steps 2–5, [SpreadShaping]). At [SpreadShape.DEFAULT] the shaping is
         * not run at all, so an effect that names no shape keeps its offsets bit for bit (P6).
         */
        fun of(
            strategy: DistributionStrategy,
            members: List<DistributionMemberInfo>,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): DistributionPlan {
            val size = members.size
            if (size == 0) return EMPTY
            val offsets: DoubleArray
            val distinctSlots: Int
            if (shape.isDefault) {
                offsets = strategy.offsets(members, size)
                distinctSlots = strategy.distinctSlots(size)
            } else {
                val rankCount = strategy.distinctSlots(size)
                val wingsApply = !strategy.isMirrored
                // Blocks, Wings and Groups count in whole ranks, so a continuous Order (Position) is
                // snapped first; Spread alone scales its continuous ranks.
                val ranks = if (SpreadShaping.countsRanks(rankCount, shape, wingsApply)) {
                    strategy.wholeRanks(members, size)
                } else {
                    strategy.ranks(members, size)
                }
                val shaped = SpreadShaping.shape(ranks, rankCount, shape, wingsApply)
                offsets = shaped.offsets
                distinctSlots = shaped.slots
            }
            val hasSpread = strategy.hasSpread
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
                distinctSlots,
            )
        }

        /**
         * Plan for an element key list, whose members are the list positions themselves —
         * the shape the tick loops used to synthesise inline, preserved exactly (including
         * the `0.5` a single element gets).
         */
        fun forElements(
            strategy: DistributionStrategy,
            count: Int,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): DistributionPlan {
            if (count == 0) return EMPTY
            return of(
                strategy,
                List(count) { i ->
                    ElementSlot(i, if (count > 1) i.toDouble() / (count - 1) else 0.5)
                },
                shape,
            )
        }
    }
}

/**
 * Every [DistributionPlan] one [FxInstance] can need for a given expansion, strategy and spread
 * shape, cached on the instance the same way [FxTargetExpansion] is and validated the same way: by
 * identity against the expansion it was built from, plus the strategy and shape it was built for.
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
    /** The [FxDynamics.spreadShape] these were built for. */
    val shape: SpreadShape,
    /** [FxTargetExpansion.Kind.GROUP_MEMBERS]. */
    val members: DistributionPlan,
    /** [FxTargetExpansion.flat]. */
    val flat: DistributionPlan,
    /** One per [FxTargetExpansion.perFixture] list, same order. */
    val perFixture: List<DistributionPlan>,
) {
    companion object {
        fun build(
            expansion: FxTargetExpansion,
            strategy: DistributionStrategy,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): FxDistributionPlans =
            when (expansion.kind) {
                FxTargetExpansion.Kind.GROUP_MEMBERS -> FxDistributionPlans(
                    expansion, strategy, shape,
                    members = DistributionPlan.of(strategy, expansion.members, shape),
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                )

                FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> FxDistributionPlans(
                    expansion, strategy, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(strategy, expansion.flat.size, shape),
                    perFixture = emptyList(),
                )

                FxTargetExpansion.Kind.GROUP_ELEMENTS -> FxDistributionPlans(
                    expansion, strategy, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(strategy, expansion.flat.size, shape),
                    perFixture = expansion.perFixture.map {
                        DistributionPlan.forElements(strategy, it.size, shape)
                    },
                )

                // DIRECT_FIXTURE uses EffectContext.SINGLE; NONE applies nothing.
                FxTargetExpansion.Kind.DIRECT_FIXTURE,
                FxTargetExpansion.Kind.NONE,
                -> FxDistributionPlans(
                    expansion, strategy, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                )
            }
    }
}
