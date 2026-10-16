package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.models.CueStackEntryKind
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.nextOrdinal
import java.time.Duration
import uk.me.cormack.lighting7.models.soleEntryOfCueId
import uk.me.cormack.lighting7.state.State
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Place this cue in [stack] as one entry, the way a test seeded a cue's stack columns before the
 * cue model plan's session 1 moved them onto entries: [sortOrder] becomes the entry's ordinal (the
 * end of the stack when absent), and the number and follow are the entry's. Writes the row
 * directly — no renumbering — as the seeds it replaces did. Inside a transaction.
 */
fun DaoCue.inStack(
    stack: DaoCueStack,
    sortOrder: Int? = null,
    cueNumber: String? = null,
    cueNumberAuto: Boolean = false,
    autoAdvance: Boolean = false,
    autoAdvanceDelay: Duration? = null,
): DaoCue {
    val cue = this
    DaoCueStackEntry.new {
        this.stack = stack
        this.cue = cue
        ordinal = sortOrder?.toDouble() ?: nextOrdinal(stack)
        this.cueNumber = cueNumber
        this.cueNumberAuto = cueNumberAuto
        this.autoAdvance = autoAdvance
        this.autoAdvanceDelay = autoAdvanceDelay
    }
    return this
}

/** A marker entry in [stack] — what a test seeded as a MARKER cue before markers became entries. */
fun markerIn(stack: DaoCueStack, label: String, sortOrder: Int? = null): DaoCueStackEntry =
    DaoCueStackEntry.new {
        this.stack = stack
        kind = CueStackEntryKind.MARKER.name
        this.label = label
        ordinal = sortOrder?.toDouble() ?: nextOrdinal(stack)
    }

/** The id of cue [cueId]'s one entry — what reorder, standby and go-to name since entries. */
fun State.entryIdOf(cueId: Int): Int =
    transaction(database) { soleEntryOfCueId(cueId)?.id?.value ?: error("cue $cueId is not in exactly one stack") }
