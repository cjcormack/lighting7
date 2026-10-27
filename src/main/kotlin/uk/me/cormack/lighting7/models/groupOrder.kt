package uk.me.cormack.lighting7.models

/**
 * The one rule for putting some of a group's members in a wanted order (venue-visit plan S7, group-order
 * plan D3), shared by a stored reorder ([reorderGroupMembers]) and a show's order of a venue group
 * ([RigScope.members]), so the two can never disagree.
 */
object GroupOrder {
    /**
     * [order] with the members [wanted] names (by [keyOf]) filling, in [wanted]'s order, the places those
     * members hold in [order]. A member [wanted] leaves out keeps its place; a key naming no member, and a
     * repeat, are ignored.
     */
    fun <T, K> merge(order: List<T>, wanted: List<K>, keyOf: (T) -> K): List<T> {
        val byKey = order.associateBy(keyOf)
        val named = wanted.distinct().mapNotNull { byKey[it] }
        val namedSet = named.toSet()
        val next = named.iterator()
        return order.map { if (it in namedSet) next.next() else it }
    }
}
