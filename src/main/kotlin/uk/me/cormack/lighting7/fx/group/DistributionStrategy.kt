package uk.me.cormack.lighting7.fx.group

import kotlin.math.abs

/**
 * Information about a member for distribution calculation.
 *
 * This interface abstracts the member properties needed by distribution strategies,
 * allowing both GroupMember and synthetic members (for FxEngine processing) to be used.
 */
interface DistributionMemberInfo {
    /** Zero-based index within the group */
    val index: Int

    /** Position normalized to 0.0-1.0 range across the group */
    val normalizedPosition: Double
}

/**
 * Strategies for distributing effect phases across group members.
 *
 * Distribution strategies determine how the phase offset is calculated
 * for each fixture in a group, enabling various chase and synchronized
 * effect patterns.
 */
sealed interface DistributionStrategy {
    /**
     * Whether this strategy produces different phase offsets for different members.
     *
     * Returns false only for strategies that give all members the same offset
     * (e.g., UNIFIED). Effects use this to decide whether to apply windowed
     * chase behaviour or output uniformly to all members.
     */
    val hasSpread: Boolean get() = true

    /**
     * Whether the base phase should be remapped through a triangle wave
     * before windowing, creating a bounce/ping-pong chase pattern.
     */
    val usesTrianglePhase: Boolean get() = false

    /**
     * Number of distinct offset slots for a given group size.
     *
     * For asymmetric distributions (LINEAR, REVERSE), this equals groupSize.
     * For symmetric distributions (CENTER_OUT, SPLIT), this is fewer because
     * multiple members share the same offset. Static effects use this for
     * window width: `1/distinctSlots` ensures no gaps in the chase.
     *
     * @param groupSize Total number of members in the group
     * @return Number of unique offset positions
     */
    fun distinctSlots(groupSize: Int): Int = groupSize

    /**
     * Calculate the phase offset for a group member.
     *
     * @param member The member info (index and normalized position)
     * @param groupSize Total number of members in the group
     * @return Phase offset from 0.0 to 1.0
     */
    fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double

    /**
     * Every member's offset in one call — what the FX tick path actually wants, and the hook a
     * strategy overrides when doing the whole list at once beats doing it one member at a time
     * (see [RANDOM], whose per-member form derives an O(n) permutation).
     *
     * @param members The members to calculate for, in the order the offsets are wanted
     * @param groupSize Total number of members in the group, which is [members]`.size` on every
     *   current call site but stays explicit to match [calculateOffset]'s contract
     */
    fun offsets(members: List<DistributionMemberInfo>, groupSize: Int): DoubleArray =
        DoubleArray(members.size) { calculateOffset(members[it], groupSize) }

    /**
     * Linear distribution: evenly spaced phases across the group.
     *
     * Creates a classic chase effect where each fixture fires in sequence.
     * Member 0 has offset 0.0, and offsets increase linearly.
     */
    data object LINEAR : DistributionStrategy {
        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            return if (groupSize > 1) member.index.toDouble() / groupSize else 0.0
        }
    }

    /**
     * Unified distribution: all members have the same phase.
     *
     * All fixtures in the group perform the effect simultaneously,
     * useful for synchronized group effects.
     */
    data object UNIFIED : DistributionStrategy {
        override val hasSpread: Boolean = false
        override fun distinctSlots(groupSize: Int): Int = 1
        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int) = 0.0
    }

    /**
     * Effects radiate outward from the center of the group.
     *
     * Center fixtures fire first (offset 0.0), with edge fixtures firing last.
     * Symmetric pairs (equidistant from center) share the same offset and fire together.
     * Useful for "explosion" or "bloom" effects.
     */
    data object CENTER_OUT : DistributionStrategy {
        override fun distinctSlots(groupSize: Int): Int = (groupSize + 1) / 2

        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            val slots = distinctSlots(groupSize)
            val center = (groupSize - 1) / 2.0
            val distance = abs(member.index - center)
            // Map continuous distance to integer rank (0 = center, slots-1 = edge)
            val rank = if (groupSize % 2 == 0) {
                (distance - 0.5).toInt()
            } else {
                distance.toInt()
            }
            return rank.toDouble() / slots
        }
    }

    /**
     * Effects converge toward the center from the edges.
     *
     * Edge fixtures fire first (offset 0.0), with center fixtures firing last.
     * Symmetric pairs share the same offset and fire together.
     * The inverse of CENTER_OUT.
     */
    data object EDGES_IN : DistributionStrategy {
        override fun distinctSlots(groupSize: Int): Int = CENTER_OUT.distinctSlots(groupSize)

        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            val slots = distinctSlots(groupSize)
            val centerOutOffset = CENTER_OUT.calculateOffset(member, groupSize)
            // Invert: rank 0 (center in CENTER_OUT) becomes rank (slots-1)
            val centerOutRank = (centerOutOffset * slots).toInt().coerceIn(0, slots - 1)
            val invertedRank = slots - 1 - centerOutRank
            return invertedRank.toDouble() / slots
        }
    }

    /**
     * Random but deterministic offsets based on member index.
     *
     * Creates a pseudo-random distribution that remains consistent
     * across effect applications (same seed produces same pattern).
     * Uses a deterministic shuffle to ensure evenly-spaced offsets.
     *
     * @param seed Random seed for offset calculation
     */
    data class RANDOM(val seed: Int = 0) : DistributionStrategy {
        /** One group size's permutation, published as a unit so a read can't tear across the two. */
        private class Permutation(val groupSize: Int, val order: IntArray)

        /**
         * Last permutation derived, memoised because the shuffle is O(n) and the offset it
         * serves is asked for once per member. Deliberately outside the data class's identity
         * (constructor properties only), so two `RANDOM(seed)` instances stay equal — the FX
         * plan cache compares strategies by equality.
         *
         * One entry, not a map: the sizes asked for come from one effect's expansion, and the
         * only shape that alternates between them is a `GROUP_ELEMENTS` per-fixture build over
         * members with *different* element counts — which happens once per plan build, off the
         * tick. A miss re-shuffles rather than misanswering, and a race just recomputes the
         * same values.
         */
        @Volatile
        private var cached: Permutation? = null

        private fun permutation(groupSize: Int): IntArray {
            cached?.let { if (it.groupSize == groupSize) return it.order }
            // Deterministic Fisher-Yates shuffle to get evenly-spaced offsets in random order
            val rng = java.util.Random(seed.toLong() * 31 + groupSize)
            val order = IntArray(groupSize) { it }
            for (i in groupSize - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val swap = order[i]
                order[i] = order[j]
                order[j] = swap
            }
            cached = Permutation(groupSize, order)
            return order
        }

        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            return permutation(groupSize)[member.index].toDouble() / groupSize
        }

        override fun offsets(members: List<DistributionMemberInfo>, groupSize: Int): DoubleArray {
            if (groupSize <= 1) return DoubleArray(members.size)
            val order = permutation(groupSize)
            return DoubleArray(members.size) { order[members[it].index].toDouble() / groupSize }
        }
    }

    /**
     * Ping-pong sweep: left-to-right then right-to-left.
     *
     * Creates a bouncing effect where the active point travels
     * back and forth across the group. One full cycle goes from
     * left edge to right edge and back.
     *
     * Uses LINEAR offsets combined with a triangle wave phase remap
     * so that static effects sweep forward then backward.
     */
    data object PING_PONG : DistributionStrategy {
        override val usesTrianglePhase: Boolean = true

        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            return LINEAR.calculateOffset(member, groupSize)
        }
    }

    /**
     * Reverse linear distribution: effect travels in reverse order.
     *
     * Same as LINEAR but in the opposite direction.
     * Last member has offset 0.0, first member fires last.
     */
    data object REVERSE : DistributionStrategy {
        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            return if (groupSize > 1) (groupSize - 1 - member.index).toDouble() / groupSize else 0.0
        }
    }

    /**
     * Split distribution: left and right halves run simultaneously.
     *
     * The group is split in half, with each half running the same
     * linear distribution from center outward. Members at mirrored
     * positions share the same offset and fire together.
     */
    data object SPLIT : DistributionStrategy {
        override fun distinctSlots(groupSize: Int): Int = (groupSize + 1) / 2

        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            val slots = distinctSlots(groupSize)
            // Mirror: member i and member (N-1-i) get the same rank
            val rank = minOf(member.index, groupSize - 1 - member.index)
            return rank.toDouble() / slots
        }
    }

    /**
     * Position-based distribution using the member's normalized position.
     *
     * Uses the member's normalizedPosition directly as the offset,
     * which accounts for any filtering or subsetting of the group.
     */
    data object POSITIONAL : DistributionStrategy {
        override fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double {
            return member.normalizedPosition
        }
    }

    companion object {
        /**
         * Parse a distribution strategy from a string name, or null if the name names none.
         *
         * The nullable form is the primitive: [uk.me.cormack.lighting7.fx.EffectSpecCoercion] layers the strict
         * (reject) and lenient (default + log) policies on top of it, so that a bad string
         * has one outcome per policy rather than one per call site.
         *
         * @param name Strategy name (case-insensitive)
         */
        fun byName(name: String): DistributionStrategy? {
            return when (name.trim().uppercase()) {
                "LINEAR" -> LINEAR
                "UNIFIED" -> UNIFIED
                "CENTER_OUT" -> CENTER_OUT
                "EDGES_IN" -> EDGES_IN
                "RANDOM" -> RANDOM()
                "PING_PONG" -> PING_PONG
                "REVERSE" -> REVERSE
                "SPLIT" -> SPLIT
                "POSITIONAL" -> POSITIONAL
                else -> null
            }
        }

        /**
         * Parse a distribution strategy from a string name, falling back to [LINEAR].
         *
         * Kept for the places whose input is internal rather than a boundary — a group's own
         * `defaultDistributionName` metadata — where there is no caller to report to. Anything
         * reading a request body or a stored spec should go through [uk.me.cormack.lighting7.fx.EffectSpecCoercion].
         *
         * @param name Strategy name (case-insensitive)
         * @return The distribution strategy, or LINEAR if not found
         */
        fun fromName(name: String): DistributionStrategy = byName(name) ?: LINEAR

        /**
         * All available strategy names for API documentation.
         */
        val availableStrategies = listOf(
            "LINEAR", "UNIFIED", "CENTER_OUT", "EDGES_IN",
            "RANDOM", "PING_PONG", "REVERSE", "SPLIT", "POSITIONAL"
        )
    }
}
