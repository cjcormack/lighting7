package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Immutable snapshot of a project's transmit-scaler state. Also acts as the single source
 * of truth for the per-scaler defaults used on first boot before any row exists.
 *
 * [grandMasterLevel] is a percentage, 0–100.
 */
data class ProjectScalerStateSnapshot(
    val blackout: Boolean = false,
    val grandMasterLevel: Double = GRAND_MASTER_FULL,
)

/** The grand master's level at full: nothing scaled. */
const val GRAND_MASTER_FULL = 100.0

/**
 * One row per project persisting the blackout + grand-master transmit scalers
 * (see [uk.me.cormack.lighting7.midi.GlobalScalerStateHolder]), so operator intent survives a
 * backend restart. Machine-local: never synced.
 *
 * `grand_master` is the on/off the grand master was before it became a level. It is still
 * written, as `level > 0`, and an old row whose flag is off loads at 0 whatever its level says,
 * so the added `grand_master_level` column needs no migration.
 */
object DaoProjectScalerStates : IntIdTable("project_scaler_states") {
    val project = reference("project_id", DaoProjects)
    val blackout = bool("blackout").default(ProjectScalerStateSnapshot().blackout)
    val grandMaster = bool("grand_master").default(true)
    val grandMasterLevel = double("grand_master_level").default(GRAND_MASTER_FULL)

    init {
        uniqueIndex(project)
    }
}

class DaoProjectScalerState(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProjectScalerState>(DaoProjectScalerStates)

    var project by DaoProject referencedOn DaoProjectScalerStates.project
    var blackout by DaoProjectScalerStates.blackout
    var grandMaster by DaoProjectScalerStates.grandMaster
    var grandMasterLevel by DaoProjectScalerStates.grandMasterLevel

    fun toSnapshot(): ProjectScalerStateSnapshot = ProjectScalerStateSnapshot(
        blackout = blackout,
        grandMasterLevel = if (grandMaster) grandMasterLevel else 0.0,
    )
}

/** A project's persisted scaler state, or the defaults when it has no row. */
fun loadProjectScalerState(database: Database, projectId: Int): ProjectScalerStateSnapshot =
    transaction(database) {
        DaoProjectScalerState.find { DaoProjectScalerStates.project eq projectId }
            .firstOrNull()
            ?.toSnapshot()
            ?: ProjectScalerStateSnapshot()
    }

/** Upsert a project's scaler row. A project deleted meanwhile is skipped. */
fun saveProjectScalerState(database: Database, projectId: Int, snapshot: ProjectScalerStateSnapshot) {
    transaction(database) {
        val project = DaoProject.findById(projectId) ?: return@transaction
        val row = DaoProjectScalerState.find { DaoProjectScalerStates.project eq projectId }.firstOrNull()
            ?: DaoProjectScalerState.new { this.project = project }
        row.blackout = snapshot.blackout
        row.grandMaster = snapshot.grandMasterLevel > 0.0
        row.grandMasterLevel = snapshot.grandMasterLevel
    }
}
