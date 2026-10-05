package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.fixture.TriggerIndex

/**
 * Delete every stored row of [project] that names a one-shot trigger or its arm (stage-view plan
 * session 9, D15): Look rows and effects, template rows and effects, cue rows and cue effects. The
 * write boundaries refuse such a row from now on (`fixture/TriggerGuard.kt`); this clears what was
 * stored before they did — the Twin Shot's `output1`, `output2` and `master` were plain sliders until
 * this session, so any of them could have been recorded.
 *
 * Judged exactly as the write boundary judges a new row ([TriggerIndex.refusal]): a row naming a
 * trigger on a fixture or group that carries one, and a row with no target of its own (a generic
 * template row, a deferred effect) naming any trigger's name at all. Returns how many rows went.
 * Must run inside a transaction.
 *
 * Every sync import runs it, so an archive written before v21 cannot bring a raised trigger back.
 */
fun stripTriggerRows(project: DaoProject): Int {
    val index = TriggerIndex.of(project)
    fun refused(type: String?, key: String?, property: String?) = index.refusal(type, key, property, "") != null

    var stripped = 0
    val looks = DaoLook.find { DaoLooks.project eq project.id }.map { it.id }
    if (looks.isNotEmpty()) {
        DaoLookRow.find { DaoLookRows.look inList looks }
            .filter { refused(it.targetType, it.elementKey?.let { e -> "${it.targetKey}.$e" } ?: it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
        DaoLookEffect.find { DaoLookEffects.look inList looks }
            .filter { refused(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
    }
    val templates = DaoTemplate.find { DaoTemplates.project eq project.id }.map { it.id }
    if (templates.isNotEmpty()) {
        DaoTemplateRow.find { DaoTemplateRows.template inList templates }
            .filter { refused(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
        DaoTemplateEffect.find { DaoTemplateEffects.template inList templates }
            .filter { refused(null, null, it.propertyName) }
            .forEach { it.delete(); stripped++ }
    }
    val cues = DaoCue.find { DaoCues.project eq project.id }.map { it.id }
    if (cues.isNotEmpty()) {
        DaoCuePropertyAssignment.find { DaoCuePropertyAssignments.cue inList cues }
            .filter { refused(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
        DaoCueAdHocEffect.find { DaoCueAdHocEffects.cue inList cues }
            .filter { refused(it.targetType, it.targetKey, it.propertyName) }
            .forEach { it.delete(); stripped++ }
    }
    return stripped
}
