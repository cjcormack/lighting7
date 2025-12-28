package uk.me.cormack.lighting7.fx.group

import uk.me.cormack.lighting7.fixture.group.GroupMember
import kotlin.math.abs

/**
 * Strategies for distributing effect phases across group members.
 *
 * Distribution strategies determine how the phase offset is calculated
 * for each fixture in a group, enabling various chase and synchronized
 * effect patterns.
 */
sealed interface DistributionStrategy {
    /**
     * Calculate the phase offset for a group member.
     *
     * @param member The group member
     * @param groupSize Total number of members in the group
     * @return Phase offset from 0.0 to 1.0
     */
    fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double

    /**
     * Linear distribution: evenly spaced phases across the group.
     *
     * Creates a classic chase effect where each fixture fires in sequence.
     * Member 0 has offset 0.0, and offsets increase linearly.
     */
    data object LINEAR : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
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
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int) = 0.0
    }

    /**
     * Effects radiate outward from the center of the group.
     *
     * Center fixtures fire first (offset 0.0), with edge fixtures firing last.
     * Useful for "explosion" or "bloom" effects.
     */
    data object CENTER_OUT : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            val center = (groupSize - 1) / 2.0
            val distanceFromCenter = abs(member.index - center)
            val maxDistance = center
            return if (maxDistance > 0) distanceFromCenter / maxDistance else 0.0
        }
    }

    /**
     * Effects converge toward the center from the edges.
     *
     * Edge fixtures fire first (offset 0.0), with center fixtures firing last.
     * The inverse of CENTER_OUT.
     */
    data object EDGES_IN : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            return 1.0 - CENTER_OUT.calculateOffset(member, groupSize)
        }
    }

    /**
     * Random but deterministic offsets based on member index.
     *
     * Creates a pseudo-random distribution that remains consistent
     * across effect applications (same seed produces same pattern).
     *
     * @param seed Random seed for offset calculation
     */
    data class RANDOM(val seed: Int = 0) : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            // Simple hash-based pseudo-random that's deterministic
            val hash = (member.index * 31 + seed).hashCode()
            return (hash and 0x7FFFFFFF).toDouble() / Int.MAX_VALUE
        }
    }

    /**
     * Ping-pong sweep: left-to-right then right-to-left.
     *
     * Creates a bouncing effect where the active point travels
     * back and forth across the group. One full cycle goes from
     * left edge to right edge and back.
     */
    data object PING_PONG : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            // For a ping-pong, we want indices to go 0,1,2,3,2,1,0,1,2...
            // But for offset calculation, we just space them evenly for the sweep
            return member.normalizedPosition
        }
    }

    /**
     * Reverse linear distribution: effect travels in reverse order.
     *
     * Same as LINEAR but in the opposite direction.
     * Last member has offset 0.0, first member fires last.
     */
    data object REVERSE : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            return if (groupSize > 1) 1.0 - (member.index.toDouble() / groupSize) else 0.0
        }
    }

    /**
     * Split distribution: left and right halves run simultaneously.
     *
     * The group is split in half, with each half running the same
     * linear distribution from center outward. Creates a mirrored effect.
     */
    data object SPLIT : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            if (groupSize <= 1) return 0.0
            val halfSize = groupSize / 2
            return if (member.index < halfSize) {
                // Left half: 0 to 0.5
                member.index.toDouble() / groupSize
            } else {
                // Right half: mirrors left half
                (groupSize - 1 - member.index).toDouble() / groupSize
            }
        }
    }

    /**
     * Custom offset calculation using a provided function.
     *
     * The function receives the member's normalized position (0.0-1.0)
     * and should return a phase offset (0.0-1.0).
     *
     * @param offsetFn Function mapping normalized position to phase offset
     */
    data class CUSTOM(val offsetFn: (Double) -> Double) : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            return offsetFn(member.normalizedPosition).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Position-based distribution using the member's normalized position.
     *
     * Uses the member's normalizedPosition directly as the offset,
     * which accounts for any filtering or subsetting of the group.
     */
    data object POSITIONAL : DistributionStrategy {
        override fun calculateOffset(member: GroupMember<*>, groupSize: Int): Double {
            return member.normalizedPosition
        }
    }

    companion object {
        /**
         * Parse a distribution strategy from a string name.
         *
         * @param name Strategy name (case-insensitive)
         * @return The distribution strategy, or LINEAR if not found
         */
        fun fromName(name: String): DistributionStrategy {
            return when (name.uppercase()) {
                "LINEAR" -> LINEAR
                "UNIFIED" -> UNIFIED
                "CENTER_OUT" -> CENTER_OUT
                "EDGES_IN" -> EDGES_IN
                "RANDOM" -> RANDOM()
                "PING_PONG" -> PING_PONG
                "REVERSE" -> REVERSE
                "SPLIT" -> SPLIT
                "POSITIONAL" -> POSITIONAL
                else -> LINEAR
            }
        }

        /**
         * All available strategy names for API documentation.
         */
        val availableStrategies = listOf(
            "LINEAR", "UNIFIED", "CENTER_OUT", "EDGES_IN",
            "RANDOM", "PING_PONG", "REVERSE", "SPLIT", "POSITIONAL"
        )
    }
}
