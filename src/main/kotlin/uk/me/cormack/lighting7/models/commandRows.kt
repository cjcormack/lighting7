package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fixture.FixtureCommands

/**
 * Delete every stored row of [project] that is a fixture command in disguise (fixture optics plan
 * session 7, D13, §6). Two kinds:
 *
 * - **By name** — a row or effect naming a command, judged exactly as the write boundary judges a new
 *   one ([CommandIndex.refusal]). The Varytec's and the Shehds' `reset` were two-option settings until
 *   this session, so a Look, a cue or an effect could hold `reset = 255`.
 * - **By level** — a Look or cue row on a property that shares its channel with a command, holding a
 *   level inside that command's band: the Fusion's motor mode at 251, the Orbit's program at 200, the
 *   Slender's special function at 200, the RESET options those settings lost; and a MAC 250 strobe
 *   row at 208–255, which its clamp kept out of the panel but not out of a typed row. A fixture row is
 *   judged by its own type; a group row by every member's, since it writes them all.
 *
 * Not judged by level: template rows, which hold intents resolved per head rather than levels, and
 * effects, whose parameters are the effect's own — the output's band guard
 * ([uk.me.cormack.lighting7.state.CommandOutput]) catches whatever those produce, as it does every
 * other writer. Parks are channel-level and judged when the show starts (`Show.dropRefusedParks`).
 *
 * Returns how many rows went. Must run inside a transaction. Two callers: the one-off startup pass
 * ([uk.me.cormack.lighting7.state.stripStoredCommandRows], to be deleted once it has run on the one
 * install), and every sync import, so an archive written before this session cannot bring one back.
 */
fun stripCommandRows(project: DaoProject): Int {
    val index = CommandIndex.of(project)
    val patches = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.toList()
    val typeByKey = patches.associate { it.key to it.fixtureTypeKey }
    val memberTypesByGroup: Map<String, List<String>> by lazy {
        DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }.associate { g -> g.name to g.members.map { it.fixturePatch.fixtureTypeKey } }
    }

    fun namedCommand(type: String?, key: String?, property: String?) = index.refusal(type, key, property, "") != null

    fun inSharedBand(targetType: String?, targetKey: String?, property: String, value: String): Boolean {
        val level = value.trim().toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        val types = when (targetType?.lowercase()) {
            "fixture" -> listOfNotNull(targetKey?.let { typeByKey[it] })
            "group" -> memberTypesByGroup[targetKey].orEmpty()
            else -> return false
        }
        return types.any { t -> FixtureCommands.sharedBandsForTypeKey(t)[property]?.any { level in it.range } == true }
    }

    var stripped = 0
    val looks = DaoLook.find { DaoLooks.project eq project.id }.map { it.id }
    if (looks.isNotEmpty()) {
        DaoLookRow.find { DaoLookRows.look inList looks }
            .filter {
                val key = it.elementKey?.let { e -> "${it.targetKey}.$e" } ?: it.targetKey
                namedCommand(it.targetType, key, it.propertyName) ||
                    (it.elementKey == null && inSharedBand(it.targetType, it.targetKey, it.propertyName, it.value))
            }
            .forEach { it.delete(); stripped++ }
        DaoLookEffect.find { DaoLookEffects.look inList looks }
            .filter { namedCommand(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
    }
    val templates = DaoTemplate.find { DaoTemplates.project eq project.id }.map { it.id }
    if (templates.isNotEmpty()) {
        DaoTemplateRow.find { DaoTemplateRows.template inList templates }
            .filter { namedCommand(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
        DaoTemplateEffect.find { DaoTemplateEffects.template inList templates }
            .filter { namedCommand(null, null, it.propertyName) }
            .forEach { it.delete(); stripped++ }
    }
    val cues = DaoCue.find { DaoCues.project eq project.id }.map { it.id }
    if (cues.isNotEmpty()) {
        DaoCuePropertyAssignment.find { DaoCuePropertyAssignments.cue inList cues }
            .filter {
                namedCommand(it.targetType, it.targetKey, it.propertyName) ||
                    inSharedBand(it.targetType, it.targetKey, it.propertyName, it.value)
            }
            .forEach { it.delete(); stripped++ }
        DaoCueAdHocEffect.find { DaoCueAdHocEffects.cue inList cues }
            .filter { namedCommand(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
    }
    return stripped
}
