package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.PaletteAttribute
import uk.me.cormack.lighting7.fx.PaletteReferenceInvalidException
import uk.me.cormack.lighting7.fx.PaletteReferences
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.fx.paletteFamily
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignments
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.models.PaletteReferenceDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID

/** One value a write is about to store, and where it is, for the refusal's wording. */
internal data class ReferenceSite(val where: String, val attribute: String, val value: String)

/**
 * Why [value] cannot be stored as a palette reference in [project], or null when it is a literal or
 * a reference that may be stored (palette-references plan D1): a value palette of the same project,
 * of the row's family where both are known. Must be called inside a transaction.
 */
internal fun paletteReferenceProblem(project: EntityID<Int>, attribute: String, value: String): String? {
    if (!PaletteReferences.isReference(value)) return null
    val uuid = PaletteReferences.uuidOf(value) ?: return "'${value.trim()}' is not a palette reference — expected pal:{uuid}"
    val palette = DaoPalette.find { (DaoPalettes.uuid eq uuid) and (DaoPalettes.project eq project) }.firstOrNull()
        ?: return "pal:$uuid names no palette in this project"
    if (palette.effect != null) return "'${palette.name}' is an FX palette — only a value palette can be a value"
    val rowFamily = PaletteAttribute.ofOrNull(canonicalAttributeName(attribute))?.family
    val paletteFamily = paletteFamily(palette.rows.sortedBy { it.sortOrder }.map { it.attribute }, null, null)
    if (rowFamily != null && paletteFamily != null && rowFamily != paletteFamily) {
        return "'${palette.name}' is a ${paletteFamily.label()} palette — it cannot be this row's ${attribute}"
    }
    return null
}

private fun AttributeFamily.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * [paletteReferenceProblem] over every site at once, thrown as [PaletteReferenceInvalidException]
 * naming them all, so a write's transaction rolls back whole. Must be called inside a transaction.
 */
internal fun checkPaletteReferences(project: EntityID<Int>, sites: List<ReferenceSite>) {
    if (sites.none { PaletteReferences.isReference(it.value) }) return
    val problems = sites.mapNotNull { site ->
        paletteReferenceProblem(project, site.attribute, site.value)?.let { "${site.where}: $it" }
    }
    if (problems.isNotEmpty()) throw PaletteReferenceInvalidException(problems.joinToString("; "))
}

/** The palette [uuid] names in [project], or null. Must be called inside a transaction. */
internal fun paletteInProject(project: EntityID<Int>, uuid: UUID): DaoPalette? =
    DaoPalette.find { (DaoPalettes.uuid eq uuid) and (DaoPalettes.project eq project) }.firstOrNull()

/**
 * Every cue row of [project] referencing [paletteUuid], read from the indexed column (D4). Must be
 * called inside a transaction.
 */
internal fun cueRowsReferencing(project: EntityID<Int>, paletteUuid: UUID): List<DaoCueAttributeAssignment> {
    val cueIds = DaoCues.select(DaoCues.id).where { DaoCues.project eq project }.map { it[DaoCues.id] }
    if (cueIds.isEmpty()) return emptyList()
    return DaoCueAttributeAssignment.find {
        (DaoCueAttributeAssignments.paletteUuid eq paletteUuid) and (DaoCueAttributeAssignments.cue inList cueIds)
    }.toList()
}

/** Every Look row of [project] referencing [paletteUuid], as [cueRowsReferencing] reads cue rows. */
internal fun lookRowsReferencing(project: EntityID<Int>, paletteUuid: UUID): List<DaoLookRow> {
    val lookIds = DaoLooks.select(DaoLooks.id).where { DaoLooks.project eq project }.map { it[DaoLooks.id] }
    if (lookIds.isEmpty()) return emptyList()
    return DaoLookRow.find {
        (DaoLookRows.paletteUuid eq paletteUuid) and (DaoLookRows.look inList lookIds)
    }.toList()
}

/** What a stored row's read carries for its reference (palette-references plan D6). */
internal class StoredReferenceRead(val reference: PaletteReferenceDto, val resolvedByHead: Map<String, String>?)

/**
 * The reference [value] makes on a row of [project] — the palette named, and, when [references] is
 * the live project's, what each head the row reaches resolves to and the first reason one does not.
 * Null for a literal. Must be called inside a transaction.
 */
internal fun storedReferenceRead(
    project: EntityID<Int>,
    fixtures: Fixtures,
    references: PaletteReferences?,
    target: TargetRef?,
    elementKey: String?,
    attribute: String,
    value: String,
): StoredReferenceRead? {
    val uuid = PaletteReferences.uuidOf(value) ?: return null
    val palette = paletteInProject(project, uuid)
        ?: return StoredReferenceRead(PaletteReferenceDto(0, uuid.toString(), uuid.toString(), "names no palette in this project"), null)
    if (references == null || target == null) {
        return StoredReferenceRead(PaletteReferenceDto(palette.id.value, uuid.toString(), palette.name), null)
    }
    val heads: List<GroupableFixture> = when (target) {
        is TargetRef.Fixture -> listOfNotNull(runCatching { fixtures.untypedGroupableFixture(elementKey ?: target.key) }.getOrNull())
        is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key).fixtures }.getOrNull()?.filterIsInstance<Fixture>().orEmpty()
    }
    val byHead = LinkedHashMap<String, String>()
    var unresolved: String? = null
    for (head in heads) {
        val resolved = references.resolve(head, attribute, uuid)
        resolved.value?.let { byHead[head.targetKey] = it.serialize() }
        if (unresolved == null) unresolved = resolved.unresolved
    }
    return StoredReferenceRead(PaletteReferenceDto(palette.id.value, uuid.toString(), palette.name, unresolved), byHead)
}

/**
 * How many cue rows and Look rows reference each of [paletteIds], each counted only within its
 * palette's own project — two grouped queries for the whole set, since the library reads this on
 * every list. Must be called inside a transaction.
 */
internal fun paletteReferenceCountsFor(paletteIds: List<Int>): Map<Int, Pair<Int, Int>> {
    if (paletteIds.isEmpty()) return emptyMap()
    val palettes = DaoPalettes.select(DaoPalettes.id, DaoPalettes.uuid, DaoPalettes.project)
        .where { DaoPalettes.id inList paletteIds }
        .map { Triple(it[DaoPalettes.id].value, it[DaoPalettes.uuid], it[DaoPalettes.project].value) }
    val uuids = palettes.map { it.second }.distinct()
    val projects = palettes.map { it.third }.distinct()

    val cueCount = DaoCueAttributeAssignments.id.count()
    val cueRows = (DaoCueAttributeAssignments innerJoin DaoCues)
        .select(DaoCueAttributeAssignments.paletteUuid, DaoCues.project, cueCount)
        .where { (DaoCueAttributeAssignments.paletteUuid inList uuids) and (DaoCues.project inList projects) }
        .groupBy(DaoCueAttributeAssignments.paletteUuid, DaoCues.project)
        .associate { (it[DaoCueAttributeAssignments.paletteUuid] to it[DaoCues.project].value) to it[cueCount].toInt() }
    val lookCount = DaoLookRows.id.count()
    val lookRows = (DaoLookRows innerJoin DaoLooks)
        .select(DaoLookRows.paletteUuid, DaoLooks.project, lookCount)
        .where { (DaoLookRows.paletteUuid inList uuids) and (DaoLooks.project inList projects) }
        .groupBy(DaoLookRows.paletteUuid, DaoLooks.project)
        .associate { (it[DaoLookRows.paletteUuid] to it[DaoLooks.project].value) to it[lookCount].toInt() }
    return palettes.associate { (id, uuid, project) ->
        id to ((cueRows[uuid to project] ?: 0) to (lookRows[uuid to project] ?: 0))
    }
}
