package uk.me.cormack.lighting7.midi

import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import java.util.UUID

/**
 * One project's cue stacks and cues by int, read once: what [SurfaceGrammar] needs to translate an
 * old binding that names a stack or cue by int alone.
 */
class LegacyRefs private constructor(
    private val stacks: Map<Int, UUID>,
    private val cues: Map<Int, UUID>,
) : SurfaceGrammar.Ints {
    override fun stackUuid(id: Int): UUID? = stacks[id]
    override fun cueUuid(id: Int): UUID? = cues[id]

    companion object {
        /** [projectId]'s records, read inside the caller's transaction. */
        fun inTransaction(projectId: Int): LegacyRefs {
            val stacks = DaoCueStack.find { DaoCueStacks.project eq projectId }.associate { it.id.value to it.uuid }
            val cues = DaoCue.find { DaoCues.project eq projectId }.associate { it.id.value to it.uuid }
            return LegacyRefs(stacks, cues)
        }
    }
}
