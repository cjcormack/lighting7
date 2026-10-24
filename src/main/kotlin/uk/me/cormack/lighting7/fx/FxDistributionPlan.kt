package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.SpreadOrder

/**
 * The per-member distribution values for one (order, member list) pair: each member's phase
 * offset, and the [FxContext] handed to the effect for it.
 *
 * Every field of an [FxContext] on a member pass — `groupSize`, `memberIndex`,
 * `distributionOffset`, `hasDistributionSpread`, `numDistinctSlots`, `trianglePhase` — is a pure
 * function of the order and the member list, so the whole array can be built once and reused
 * for every tick that pass covers. Before this the tick loops built one synthetic
 * [DistributionMemberInfo] *and* one [FxContext] per member per tick, and asked
 * [SpreadOrder.calculateOffset] for an offset each time — which for
 * `SpreadOrder.RANDOM` shuffled a fresh permutation per member, making one tick O(N²).
 *
 * Immutable once built, and [FxContext] is a data class nothing mutates, so sharing one
 * instance across ticks and across the effects that resolve to the same plan is safe.
 */
internal class DistributionPlan private constructor(
    /** Phase offset per member, in the member list's own order. */
    val offsets: DoubleArray,
    /** The context handed to the effect per member, same order as [offsets]. */
    val contexts: Array<FxContext>,
    /**
     * The rank count after the spread's shape (effects plan §3.3, step 4) — every context's
     * [FxContext.numDistinctSlots], and what [FxDynamics.distinctSlots] answers per pass.
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
            order: SpreadOrder,
            members: List<DistributionMemberInfo>,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): DistributionPlan {
            val size = members.size
            if (size == 0) return EMPTY
            val offsets: DoubleArray
            val distinctSlots: Int
            if (shape.isDefault) {
                offsets = order.offsets(members, size)
                distinctSlots = order.distinctSlots(size)
            } else {
                val rankCount = order.distinctSlots(size)
                val wingsApply = !order.isMirrored
                // Segments, Wings and Parts count in whole ranks, so a continuous Order (Position) is
                // snapped first; Spread alone scales its continuous ranks.
                val ranks = if (SpreadShaping.countsRanks(rankCount, shape, wingsApply)) {
                    order.wholeRanks(members, size)
                } else {
                    order.ranks(members, size)
                }
                val shaped = SpreadShaping.shape(ranks, rankCount, shape, wingsApply)
                offsets = shaped.offsets
                distinctSlots = shaped.slots
            }
            val hasSpread = order.hasSpread
            val trianglePhase = order.usesTrianglePhase
            return DistributionPlan(
                offsets,
                Array(size) { i ->
                    FxContext(
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
            order: SpreadOrder,
            count: Int,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): DistributionPlan {
            if (count == 0) return EMPTY
            return of(
                order,
                List(count) { i ->
                    ElementSlot(i, if (count > 1) i.toDouble() / (count - 1) else 0.5)
                },
                shape,
            )
        }
    }
}

/**
 * Every [DistributionPlan] one [FxInstance] can need for a given expansion, order and spread
 * shape, cached on the instance the same way [FxTargetExpansion] is and validated the same way: by
 * identity against the expansion it was built from, plus the order and shape it was built for.
 *
 * Both the element shapes are built up front for the same reason [FxTargetExpansion] builds both
 * key lists — [ElementMode] is not part of the expansion's identity, so a mode toggle must not
 * need an invalidation. The unused arms are [DistributionPlan.EMPTY], which costs nothing.
 */
internal class FxDistributionPlans private constructor(
    /** The expansion these were built from; compared by identity, not equality. */
    val expansion: FxTargetExpansion,
    /** The [FxDynamics.order] these were built for. */
    val order: SpreadOrder,
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
            order: SpreadOrder,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): FxDistributionPlans =
            when (expansion.kind) {
                FxTargetExpansion.Kind.GROUP_MEMBERS -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.of(order, expansion.members, shape),
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                )

                FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(order, expansion.flat.size, shape),
                    perFixture = emptyList(),
                )

                FxTargetExpansion.Kind.GROUP_ELEMENTS -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(order, expansion.flat.size, shape),
                    perFixture = expansion.perFixture.map {
                        DistributionPlan.forElements(order, it.size, shape)
                    },
                )

                // DIRECT_FIXTURE uses FxContext.SINGLE; NONE applies nothing.
                FxTargetExpansion.Kind.DIRECT_FIXTURE,
                FxTargetExpansion.Kind.NONE,
                -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                )
            }
    }
}
