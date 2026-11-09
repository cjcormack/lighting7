package uk.me.cormack.lighting7.midi

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import java.util.UUID

/**
 * One project's cue stacks, cues and entries by int and by uuid, read once: what [SurfaceGrammar]
 * needs to translate an old binding that names a stack or cue by int, and to say a binding in the
 * old union's words for the screens session 3 replaces.
 */
class LegacyRefs private constructor(
    private val stacks: Map<Int, UUID>,
    private val cues: Map<Int, UUID>,
    private val entries: Map<UUID, UUID>,
) : SurfaceGrammar.Ints, SurfaceGrammar.Ids {
    private val stackIds = stacks.entries.associate { (id, uuid) -> uuid to id }
    private val cueIds = cues.entries.associate { (id, uuid) -> uuid to id }

    override fun stackUuid(id: Int): UUID? = stacks[id]
    override fun cueUuid(id: Int): UUID? = cues[id]
    override fun stackId(uuid: UUID): Int? = stackIds[uuid]
    override fun cueId(uuid: UUID): Int? = cueIds[uuid]
    override fun entryStack(entry: UUID): UUID? = entries[entry]

    companion object {
        /** [projectId]'s records, read in a transaction of their own. */
        fun of(database: Database, projectId: Int): LegacyRefs = transaction(database) { inTransaction(projectId) }

        /** [projectId]'s records, read inside the caller's transaction. */
        fun inTransaction(projectId: Int): LegacyRefs {
            val stacks = DaoCueStack.find { DaoCueStacks.project eq projectId }.associate { it.id.value to it.uuid }
            val cues = DaoCue.find { DaoCues.project eq projectId }.associate { it.id.value to it.uuid }
            val entries = if (stacks.isEmpty()) emptyMap() else
                DaoCueStackEntry.find { DaoCueStackEntries.stack inList stacks.keys.toList() }
                    .mapNotNull { entry -> stacks[entry.stackId]?.let { entry.uuid to it } }
                    .toMap()
            return LegacyRefs(stacks, cues, entries)
        }
    }
}
