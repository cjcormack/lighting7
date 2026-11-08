package uk.me.cormack.lighting7.fx

/**
 * Where each running stack composes (busk-stack plan D5, P3): **latest GO wins, in two bands**. The
 * show's stacks, those in the running order, share the [Band.SHOW] band; busk stacks share
 * [Band.BUSK], above it. Within a band a stack's [Place.rank] is its place among the band's stacks by
 * latest operator GO, 1 the oldest, dense: an operator's start, GO, GO TO or BACK takes the top of the
 * band ([takeTop]), a follow keeps the stack where it is ([keep]), and a stack leaving the band
 * ([remove]) closes the gap. A stack stopped with its release still fading keeps its place until the
 * release ends, so its fading rows never share a rank with a live stack's.
 *
 * Ranks are slots in fixed bands rather than a counter, so priorities stay `Int`s below
 * [CueHolds.LOOSE_BAND] however many GOs a show takes, and creating or deleting a stack moves nothing.
 * [stackPriority] turns a place and an entry position into a hold's priority; [stackAt] reads one back.
 *
 * Every change answers the places it moved, the stack's own included, for the caller to restamp what
 * those stacks have on stage. Runtime only: a reload carries the places ([snapshot], [restore]).
 */
class StackRanks {
    enum class Band { SHOW, BUSK }

    data class Place(val band: Band, val rank: Int)

    private val lock = Any()

    /** Each band's stacks, oldest GO first: a stack's rank is its index + 1. */
    private val order = mapOf(Band.SHOW to ArrayList<Int>(), Band.BUSK to ArrayList<Int>())

    /** Read lock-free on the engine's hot path ([stackAt]): each band's stacks by rank, and each stack's place. */
    private class View(val byRank: Map<Band, IntArray>, val places: Map<Int, Place>)

    @Volatile private var view = View(mapOf(Band.SHOW to IntArray(0), Band.BUSK to IntArray(0)), emptyMap())

    /** [stackId]'s place, or null when it is neither running nor releasing. */
    fun placeOf(stackId: Int): Place? = view.places[stackId]

    /**
     * An operator's GO on [stackId] in [band]: it takes the top of the band, out of the other band if it
     * was there. Refused with [CueStackLimitException] when [band] already holds [MAX_RANK] stacks and
     * this is not one of them.
     */
    fun takeTop(stackId: Int, band: Band): Map<Int, Place> = change { place(stackId, band, top = true) }

    /** A follow on [stackId] in [band]: where it is; at the top of [band] when it has no place there. */
    fun keep(stackId: Int, band: Band): Map<Int, Place> = change { place(stackId, band, top = false) }

    /** [stackId] has stopped and its release, if any, has ended: the stacks above it close the gap. */
    fun remove(stackId: Int): Map<Int, Place> = change {
        for (list in order.values) list.remove(stackId)
    }

    /** The place a stack not yet in [band] would take at its GO: the top. */
    fun nextPlace(band: Band): Place = Place(band, (view.byRank.getValue(band).size + 1).coerceAtMost(MAX_RANK))

    /** Every place, for a reload to carry. */
    fun snapshot(): Map<Int, Place> = view.places

    /**
     * Take [carried] as these places — a reload's, before its GOs, which then [keep] them. Each band's
     * stacks keep the order their ranks gave them, renumbered dense.
     */
    fun restore(carried: Map<Int, Place>) {
        change {
            for (list in order.values) list.clear()
            for (band in Band.entries) {
                carried.filterValues { it.band == band }.entries.sortedBy { it.value.rank }.take(MAX_RANK)
                    .forEach { order.getValue(band).add(it.key) }
            }
        }
    }

    /** The stack whose place [priority], a [stackPriority], lies in; null outside both bands' ranks. */
    fun stackAt(priority: Int): Int? {
        if (priority < STACK_PRIORITY_BAND || priority >= CueHolds.LOOSE_BAND) return null
        val band = if (priority >= BUSK_BASE) Band.BUSK else Band.SHOW
        val rank = (priority - baseOf(band)) / STACK_PRIORITY_BAND
        val ranks = view.byRank.getValue(band)
        return if (rank in 1..ranks.size) ranks[rank - 1] else null
    }

    private fun place(stackId: Int, band: Band, top: Boolean) {
        val list = order.getValue(band)
        val here = stackId in list
        if (here && !top) return
        if (!here && list.size >= MAX_RANK) throw CueStackLimitException(band)
        for (other in order.values) other.remove(stackId)
        list.add(stackId)
    }

    /** Run [mutate] under [lock], republish, and answer every place that moved. */
    private fun change(mutate: () -> Unit): Map<Int, Place> = synchronized(lock) {
        val before = view.places
        mutate()
        val places = HashMap<Int, Place>()
        val byRank = HashMap<Band, IntArray>()
        for ((band, list) in order) {
            byRank[band] = list.toIntArray()
            list.forEachIndexed { index, id -> places[id] = Place(band, index + 1) }
        }
        view = View(byRank, places)
        places.filter { (id, place) -> before[id] != place }
    }

    companion object {
        /** The most stacks one band holds at once (P3): ranks 1–999 keep both bands below [CueHolds.LOOSE_BAND]. */
        const val MAX_RANK = 999

        /** Where the busk band starts: above every show-band rank. */
        const val BUSK_BASE = 1_000 * STACK_PRIORITY_BAND

        fun baseOf(band: Band): Int = if (band == Band.BUSK) BUSK_BASE else 0

        fun bandOf(inRunningOrder: Boolean): Band = if (inRunningOrder) Band.SHOW else Band.BUSK
    }
}

/** A stack hold's priority: [place]'s band and rank, then the entry's [position] within it. No place is today's `position × 1 000 + 1`, below every stack. */
internal fun stackPriority(place: StackRanks.Place?, position: Int): Int =
    (place?.let { StackRanks.baseOf(it.band) + it.rank * STACK_PRIORITY_BAND } ?: 0) + position * 1_000 + 1

/** [priority], a [stackPriority], moved to [place]: the same entry position in the new rank. */
internal fun rebasedPriority(priority: Int, place: StackRanks.Place): Int =
    StackRanks.baseOf(place.band) + place.rank * STACK_PRIORITY_BAND + priority.mod(STACK_PRIORITY_BAND)

/** The width of one rank: a stack's entry positions sit inside it. */
internal const val STACK_PRIORITY_BAND = 1_000_000

const val CODE_CUE_STACK_LIMIT = "CUE_STACK_LIMIT"

/** A [StackRanks.MAX_RANK] + 1st stack started in one band (P3). */
class CueStackLimitException(band: StackRanks.Band) : IllegalStateException(
    "At most ${StackRanks.MAX_RANK} ${if (band == StackRanks.Band.BUSK) "busk" else "show"} stacks can run at once: stop one first",
)
