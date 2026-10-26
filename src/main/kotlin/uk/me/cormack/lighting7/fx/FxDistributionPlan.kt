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
                val shaped = shaped(order, members, shape, ladder = null)
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
         * The offset ladder for [members] (FX editor plan D9): each one's rank after each of §3.3's
         * steps 1–4, from the same ranks and shaping [of] runs. Under [SpreadShape.DEFAULT] every
         * rung is the Order's rank, and the plan's offsets are the Order's own.
         */
        fun ladder(
            order: SpreadOrder,
            members: List<DistributionMemberInfo>,
            shape: SpreadShape = SpreadShape.DEFAULT,
        ): SpreadShaping.Ladder = SpreadShaping.Ladder(members.size).also { shaped(order, members, shape, it) }

        /** [ladder] for an element list, as [forElements] plans one. */
        fun elementLadder(order: SpreadOrder, count: Int, shape: SpreadShape = SpreadShape.DEFAULT): SpreadShaping.Ladder =
            ladder(order, elementSlots(count), shape)

        /** Steps 1–5 of §3.3 over [members], filling [ladder] when one is given. */
        private fun shaped(
            order: SpreadOrder,
            members: List<DistributionMemberInfo>,
            shape: SpreadShape,
            ladder: SpreadShaping.Ladder?,
        ): SpreadShaping.Result {
            val size = members.size
            val rankCount = order.distinctSlots(size)
            val wingsApply = !order.isMirrored
            // Segments, Wings and Parts count in whole ranks, so a continuous Order (Position) is
            // snapped first; Spread alone scales its continuous ranks.
            val ranks = if (SpreadShaping.countsRanks(rankCount, shape, wingsApply)) {
                order.wholeRanks(members, size)
            } else {
                order.ranks(members, size)
            }
            return SpreadShaping.shape(ranks, rankCount, shape, wingsApply, ladder)
        }

        private fun elementSlots(count: Int): List<DistributionMemberInfo> =
            List(count) { i -> ElementSlot(i, if (count > 1) i.toDouble() / (count - 1) else 0.5) }

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
            return of(order, elementSlots(count), shape)
        }
    }
}

/**
 * Every [DistributionPlan] one [FxInstance] can need for a given expansion, order and spread
 * shape, cached on the instance the same way [FxTargetExpansion] is and validated the same way: by
 * identity against the expansion it was built from, plus the order and shape it was built for.
 *
 * Every element shape is built up front for the same reason [FxTargetExpansion] builds every
 * key list — [ElementMode] is not part of the expansion's identity, so a mode toggle must not
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
    /** Over [FxTargetExpansion.whole]'s units, one offset per fixture. */
    val whole: DistributionPlan,
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
                    whole = DistributionPlan.EMPTY,
                )

                FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(order, expansion.flat.size, shape),
                    perFixture = emptyList(),
                    whole = DistributionPlan.forElements(order, expansion.whole.size, shape),
                )

                FxTargetExpansion.Kind.GROUP_ELEMENTS -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.forElements(order, expansion.flat.size, shape),
                    perFixture = expansion.perFixture.map {
                        DistributionPlan.forElements(order, it.size, shape)
                    },
                    whole = DistributionPlan.forElements(order, expansion.whole.size, shape),
                )

                // DIRECT_FIXTURE uses FxContext.SINGLE; NONE applies nothing.
                FxTargetExpansion.Kind.DIRECT_FIXTURE,
                FxTargetExpansion.Kind.NONE,
                -> FxDistributionPlans(
                    expansion, order, shape,
                    members = DistributionPlan.EMPTY,
                    flat = DistributionPlan.EMPTY,
                    perFixture = emptyList(),
                    whole = DistributionPlan.EMPTY,
                )
            }
    }
}
