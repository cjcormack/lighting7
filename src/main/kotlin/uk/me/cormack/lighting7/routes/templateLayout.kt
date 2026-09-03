package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateGroup
import uk.me.cormack.lighting7.models.DaoTemplateGroups
import uk.me.cormack.lighting7.models.DaoTemplates

/**
 * Error code for "that would put two families in one group" — a 409, keyed by the client the way
 * the delete guards are. One code for every surface that can cause it: a create or PUT naming a
 * `groupId`, a PUT whose new contents change a grouped template's family, and a reorder.
 */
internal const val CODE_TEMPLATE_GROUP_FAMILY = "TEMPLATE_GROUP_FAMILY"

/**
 * One top-level position in the template library: **either** a template **or** a group with its
 * members in order. Exactly one of [templateId] / [groupId] is set; [templateIds] is meaningful only
 * beside [groupId].
 *
 * The shape is the layout as the operator sees it rather than a flat `(id, sortOrder, groupId)`
 * triple per template, because that is what a drag produces and what the renumbering consumes —
 * the server assigns the numbers, the client never does.
 */
@Serializable
internal data class TemplateLayoutEntry(
    val templateId: Int? = null,
    val groupId: Int? = null,
    val templateIds: List<Int> = emptyList(),
)

/**
 * The **whole** layout — every template and every group in the project, each named exactly once.
 *
 * Whole rather than "the ids you moved", unlike the cue-stack reorder it is otherwise modelled on,
 * because a partial list cannot express a move *out* of a group: an unnamed template keeps its
 * `group_id`, so the only way to say "top level now" is to name it there. The client always holds
 * the unfiltered list — its drag reduces over every entry even when it is *drawing* one family's
 * bank — so completeness costs it nothing and buys the server one invariant: after this write,
 * every position in both tables is dense and unique.
 */
@Serializable
internal data class ReorderTemplatesRequest(val entries: List<TemplateLayoutEntry>)

internal sealed interface TemplateLayoutOutcome {
    data object Ok : TemplateLayoutOutcome

    /** Malformed or incomplete — a 400. */
    data class Invalid(val message: String) : TemplateLayoutOutcome

    /** Two families in one group — a 409 carrying [CODE_TEMPLATE_GROUP_FAMILY]. */
    data class MixedFamily(val message: String) : TemplateLayoutOutcome
}

/**
 * The layout as stored, in the shape [ReorderTemplatesRequest] takes — so a route that wants to
 * edit it (group DELETE inlines the members at the group's position) reads, edits and re-applies
 * through the one renumbering rather than assigning numbers of its own.
 *
 * Tie-break is `(sortOrder, template before group, name)`: a create appends, so ties are only ever
 * transient, and the same rule is mirrored client-side in `lib/templateLayout.ts` so the two never
 * draw a different order from the same numbers.
 *
 * Must be called inside a transaction.
 */
internal fun currentTemplateLayout(project: DaoProject): List<TemplateLayoutEntry> {
    val templates = DaoTemplate.find { DaoTemplates.project eq project.id }.toList()
    val groups = DaoTemplateGroup.find { DaoTemplateGroups.project eq project.id }.toList()
    val membersByGroup = templates.filter { it.group != null }.groupBy { it.group!!.id.value }

    data class TopLevel(val sortOrder: Int, val isGroup: Boolean, val name: String, val entry: TemplateLayoutEntry)

    val topLevel = templates.filter { it.group == null }.map { t ->
        TopLevel(t.sortOrder, false, t.name, TemplateLayoutEntry(templateId = t.id.value))
    } + groups.map { g ->
        val members = (membersByGroup[g.id.value] ?: emptyList())
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .map { it.id.value }
        TopLevel(g.sortOrder, true, g.name, TemplateLayoutEntry(groupId = g.id.value, templateIds = members))
    }
    return topLevel
        .sortedWith(compareBy({ it.sortOrder }, { it.isGroup }, { it.name }))
        .map { it.entry }
}

/**
 * The next free top-level position — what a create (of a template or a group) appends at.
 * Must be called inside a transaction.
 */
internal fun nextTopLevelSortOrder(project: DaoProject): Int {
    val templateMax = DaoTemplate.find { (DaoTemplates.project eq project.id) and (DaoTemplates.group.isNull()) }
        .maxOfOrNull { it.sortOrder } ?: -1
    val groupMax = DaoTemplateGroup.find { DaoTemplateGroups.project eq project.id }
        .maxOfOrNull { it.sortOrder } ?: -1
    return maxOf(templateMax, groupMax) + 1
}

/** The next free position inside [group]. Must be called inside a transaction. */
internal fun nextSortOrderIn(group: DaoTemplateGroup): Int =
    (group.members.maxOfOrNull { it.sortOrder } ?: -1) + 1

/**
 * Validate [entries] against [project] and, if they are a complete and single-family layout,
 * write every position and membership they describe. **The one renumbering**: the reorder route
 * and the group DELETE both come through here, and nothing else assigns a `sortOrder` except a
 * create, which appends.
 *
 * Must be called inside a transaction. Returns before touching a row on any refusal, so a caller
 * that respects the outcome never leaves a half-applied layout behind.
 */
internal fun applyTemplateLayout(project: DaoProject, entries: List<TemplateLayoutEntry>): TemplateLayoutOutcome {
    val templates = DaoTemplate.find { DaoTemplates.project eq project.id }.associateBy { it.id.value }
    val groups = DaoTemplateGroup.find { DaoTemplateGroups.project eq project.id }.associateBy { it.id.value }

    // Shape: exactly one of templateId / groupId, and members only under a group.
    for (entry in entries) {
        val hasTemplate = entry.templateId != null
        val hasGroup = entry.groupId != null
        if (hasTemplate == hasGroup) {
            return TemplateLayoutOutcome.Invalid("Each layout entry names exactly one of templateId or groupId")
        }
        if (hasTemplate && entry.templateIds.isNotEmpty()) {
            return TemplateLayoutOutcome.Invalid("A template entry cannot carry templateIds — only a group can")
        }
    }

    // Identity: every id known to this project, none named twice, none left out.
    val namedTemplates = entries.flatMap { e -> listOfNotNull(e.templateId) + e.templateIds }
    val namedGroups = entries.mapNotNull { it.groupId }
    val unknownTemplates = namedTemplates.filter { it !in templates }
    val unknownGroups = namedGroups.filter { it !in groups }
    if (unknownTemplates.isNotEmpty() || unknownGroups.isNotEmpty()) {
        return TemplateLayoutOutcome.Invalid(
            "Layout names ids not in this project — templates $unknownTemplates, groups $unknownGroups",
        )
    }
    val duplicateTemplates = namedTemplates.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val duplicateGroups = namedGroups.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicateTemplates.isNotEmpty() || duplicateGroups.isNotEmpty()) {
        return TemplateLayoutOutcome.Invalid(
            "Layout names ids more than once — templates $duplicateTemplates, groups $duplicateGroups",
        )
    }
    val missingTemplates = templates.keys - namedTemplates.toSet()
    val missingGroups = groups.keys - namedGroups.toSet()
    if (missingTemplates.isNotEmpty() || missingGroups.isNotEmpty()) {
        return TemplateLayoutOutcome.Invalid(
            "Layout must name every template and group in the project — missing templates " +
                "$missingTemplates, groups $missingGroups",
        )
    }

    // One family per group, judged on what the layout *would* hold rather than what is stored.
    for (entry in entries) {
        val group = entry.groupId?.let { groups.getValue(it) } ?: continue
        val families = entry.templateIds
            .mapNotNull { templates.getValue(it).familyOf() }
            .toCollection(LinkedHashSet())
        if (families.size > 1) {
            return TemplateLayoutOutcome.MixedFamily(mixedFamilyMessage(group.name, families))
        }
    }

    // Write. Dense from zero on both sides; a grouped template's number is its index in the group.
    for ((index, entry) in entries.withIndex()) {
        val templateId = entry.templateId
        if (templateId != null) {
            val template = templates.getValue(templateId)
            template.group = null
            template.sortOrder = index
        } else {
            val group = groups.getValue(entry.groupId!!)
            group.sortOrder = index
            for ((memberIndex, memberId) in entry.templateIds.withIndex()) {
                val member = templates.getValue(memberId)
                member.group = group
                member.sortOrder = memberIndex
            }
        }
    }
    return TemplateLayoutOutcome.Ok
}

/**
 * The family [group] would have with [template] (or a template of [family]) in it, and whether
 * that clashes. Returns the refusal message, or null when the join is fine.
 *
 * [excluding] is the template being moved or re-contented, so its *stored* family does not count
 * against its own new one — a lone member can change family freely, and a member moving within
 * its own group is not "joining".
 *
 * Must be called inside a transaction.
 */
internal fun groupFamilyClash(
    group: DaoTemplateGroup,
    family: PropertyMaskGroup?,
    excluding: DaoTemplate? = null,
): String? {
    if (family == null) return null
    val groupFamily = group.members
        .filter { excluding == null || it.id != excluding.id }
        .sortedBy { it.sortOrder }
        .firstNotNullOfOrNull { it.familyOf() }
        ?: return null
    if (groupFamily == family) return null
    return mixedFamilyMessage(group.name, linkedSetOf(groupFamily, family))
}

private fun mixedFamilyMessage(groupName: String, families: Set<PropertyMaskGroup>): String =
    "A template group holds one family — '$groupName' would hold " +
        families.joinToString(" and ") { it.name.lowercase() } +
        ". A colour pad and a position pad never fight over a channel, so they are never exclusive"
