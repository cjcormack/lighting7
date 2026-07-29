package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.json.json
import org.jetbrains.exposed.v1.core.java.javaUUID
import uk.me.cormack.lighting7.fx.EffectMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.TimingSource

object DaoFxDefinitions : IntIdTable("fx_definitions") {
    val effectId = varchar("effect_id", 255)
    val name = varchar("name", 255)
    val category = varchar("category", 50)
    val outputType = enumerationByName<FxOutputType>("output_type", 20)
    val effectMode = enumerationByName<EffectMode>("effect_mode", 20).default(EffectMode.STANDARD)
    val parameters = json<List<ParameterInfo>>("parameters", Json).default(emptyList())
    val compatibleProperties = json<List<String>>("compatible_properties", Json).default(emptyList())
    val script = text("script")
    val project = reference("project_id", DaoProjects)
    val defaultStepTiming = bool("default_step_timing").default(false)
    val timingSource = enumerationByName<TimingSource>("timing_source", 20).default(TimingSource.BEAT)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Scoped to the project, not global: definitions are always loaded per project
        // (see Show.loadFxDefinitions), and two projects legitimately hold the same
        // effectId — a cloned or imported project is a copy of one, so a global unique
        // index made cloning/importing any project with a script-defined effect fail
        // outright. State drops the legacy `fx_definitions_effect_id` index.
        uniqueIndex(project, effectId)
    }
}

class DaoFxDefinition(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFxDefinition>(DaoFxDefinitions)

    var effectId by DaoFxDefinitions.effectId
    var name by DaoFxDefinitions.name
    var category by DaoFxDefinitions.category
    var outputType by DaoFxDefinitions.outputType
    var effectMode by DaoFxDefinitions.effectMode
    var parameters by DaoFxDefinitions.parameters
    var compatibleProperties by DaoFxDefinitions.compatibleProperties
    var script by DaoFxDefinitions.script
    var project by DaoProject referencedOn DaoFxDefinitions.project
    var defaultStepTiming by DaoFxDefinitions.defaultStepTiming
    var timingSource by DaoFxDefinitions.timingSource
    var uuid by DaoFxDefinitions.uuid
}
