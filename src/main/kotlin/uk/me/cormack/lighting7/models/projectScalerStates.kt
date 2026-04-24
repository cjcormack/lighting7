package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * One row per project persisting the blackout + grand-master transmit scalers
 * (see [uk.me.cormack.lighting7.midi.GlobalScalerStateHolder]). Written through on
 * every state change so operator intent survives a backend restart.
 */
/**
 * Immutable snapshot of a project's transmit-scaler state. Also acts as the single source
 * of truth for the per-scaler defaults used on first boot before any row exists.
 */
data class ProjectScalerStateSnapshot(
    val blackout: Boolean = false,
    val grandMaster: Boolean = true,
)

object DaoProjectScalerStates : IntIdTable("project_scaler_states") {
    val project = reference("project_id", DaoProjects)
    val blackout = bool("blackout").default(ProjectScalerStateSnapshot().blackout)
    val grandMaster = bool("grand_master").default(ProjectScalerStateSnapshot().grandMaster)

    init {
        uniqueIndex(project)
    }
}

class DaoProjectScalerState(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProjectScalerState>(DaoProjectScalerStates)

    var project by DaoProject referencedOn DaoProjectScalerStates.project
    var blackout by DaoProjectScalerStates.blackout
    var grandMaster by DaoProjectScalerStates.grandMaster

    fun toSnapshot(): ProjectScalerStateSnapshot =
        ProjectScalerStateSnapshot(blackout = blackout, grandMaster = grandMaster)
}
