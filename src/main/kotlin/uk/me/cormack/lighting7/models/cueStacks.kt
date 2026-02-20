package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json

// ─── Cue Stacks table ──────────────────────────────────────────────────

object DaoCueStacks : IntIdTable("cue_stacks") {
    val name = varchar("name", 255)
    val project = reference("project_id", DaoProjects)
    val palette = json<List<String>>("palette", Json)
    val loop = bool("loop").default(false)

    init {
        uniqueIndex(project, name)
    }
}

class DaoCueStack(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueStack>(DaoCueStacks)

    var name by DaoCueStacks.name
    var project by DaoProject referencedOn DaoCueStacks.project
    var palette by DaoCueStacks.palette
    var loop by DaoCueStacks.loop
    val cues by DaoCue optionalReferrersOn DaoCues.cueStack
}
