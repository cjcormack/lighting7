package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.json.json
import org.jetbrains.exposed.v1.core.java.javaUUID
import uk.me.cormack.lighting7.fx.FxMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.TimingSource

object DaoFxDefinitions : IntIdTable("fx_definitions") {
    val fxId = varchar("fx_id", 255)
    val name = varchar("name", 255)
    val family = varchar("family", 50)
    val outputType = enumerationByName<FxOutputType>("output_type", 20)
    val fxMode = enumerationByName<FxMode>("fx_mode", 20).default(FxMode.STANDARD)
    val parameters = json<List<ParameterInfo>>("parameters", Json).default(emptyList())
    val compatibleAttributes = json<List<String>>("compatible_attributes", Json).default(emptyList())
    val script = text("script")
    val project = reference("project_id", DaoProjects)
    val defaultTiming = varchar("default_timing", 8).default(TIMING_CYCLE)
    val timingSource = enumerationByName<TimingSource>("timing_source", 20).default(TimingSource.BEAT)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Scoped to the project, not global: definitions are always loaded per project
        // (see Show.loadFxDefinitions), and two projects legitimately hold the same
        // fxId — a cloned or imported project is a copy of one, so a global unique
        // index made cloning/importing any project with a script-defined effect fail
        // outright. State drops the legacy `fx_definitions_effect_id` index.
        uniqueIndex(project, fxId)
    }
}

class DaoFxDefinition(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFxDefinition>(DaoFxDefinitions)

    var fxId by DaoFxDefinitions.fxId
    var name by DaoFxDefinitions.name
    var family by DaoFxDefinitions.family
    var outputType by DaoFxDefinitions.outputType
    var fxMode by DaoFxDefinitions.fxMode
    var parameters by DaoFxDefinitions.parameters
    var compatibleAttributes by DaoFxDefinitions.compatibleAttributes
    var script by DaoFxDefinitions.script
    var project by DaoProject referencedOn DaoFxDefinitions.project
    var defaultTiming by DaoFxDefinitions.defaultTiming
    var timingSource by DaoFxDefinitions.timingSource
    var uuid by DaoFxDefinitions.uuid
}
