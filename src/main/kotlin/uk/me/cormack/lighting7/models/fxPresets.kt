package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json

@Serializable
data class FxPresetEffectDto(
    val effectType: String,
    val category: String,
    val propertyName: String? = null,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val parameters: Map<String, String> = emptyMap(),
)

object DaoFxPresets : IntIdTable("fx_presets") {
    val name = varchar("name", 255)
    val description = varchar("description", 1000).nullable()
    val project = reference("project_id", DaoProjects)
    val fixtureType = varchar("fixture_type", 255).nullable()
    val effects = json<List<FxPresetEffectDto>>("effects", Json)

    init {
        uniqueIndex(project, fixtureType, name)
    }
}

class DaoFxPreset(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFxPreset>(DaoFxPresets)

    var name by DaoFxPresets.name
    var description by DaoFxPresets.description
    var project by DaoProject referencedOn DaoFxPresets.project
    var fixtureType by DaoFxPresets.fixtureType
    var effects by DaoFxPresets.effects
}
