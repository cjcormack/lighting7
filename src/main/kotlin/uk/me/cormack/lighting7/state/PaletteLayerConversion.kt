package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.PaletteAttribute
import uk.me.cormack.lighting7.fx.PaletteReferences
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.fx.family
import uk.me.cormack.lighting7.fx.paletteFamily
import uk.me.cormack.lighting7.fx.parseFamilies
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignments
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoVenueVersions
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID

/**
 * Which stored cue palette layers become palette references on the cue's own rows
 * (palette-references plan D17, call B), shared by database step 10 ([PaletteLayerMigration]) and the
 * importer's reading of a v46 archive.
 *
 * A layer converts only when rows say exactly what it did. It must be **plain** — a value palette's,
 * enabled, amount 1, `OVERRIDE`, no stomp, not timed, masked to nothing or to the palette's family —
 * and nothing may sit over it that its rows would then beat: own rows beat every layer, so no
 * contributing layer above it (a Look, or another palette, whatever becomes of it) may assert the
 * palette's family on a head it reaches, and the cue's own rows must still win where they did. Where
 * an own row names the same target and attribute it already won, so that row is left out. Anything
 * the rig would have to answer — a group's members, an attribute's family — is read from the stored
 * patch, and a question it cannot answer keeps the layer.
 *
 * An FX palette's layer asserts no value, so it is neither converted nor in the way.
 */
internal object PaletteLayerConversion {
    /** One palette row: [targetKey] its fixture or cell, null for a generic row. */
    data class PaletteRow(val targetKey: String?, val attribute: String)

    /** [fadeMs] is the palette's own fade, which its layer gave every row it cooked; its rows keep it. */
    data class Palette(val uuid: UUID, val name: String, val isFx: Boolean, val rows: List<PaletteRow>, val fadeMs: Long? = null)

    data class LookRow(val target: TargetRef, val elementKey: String?, val attribute: String)

    data class Look(val name: String, val rows: List<LookRow>)

    /** One cue layer; exactly one of [look] and [palette] is set for a well-formed one. */
    data class Layer(
        val id: Int,
        val uuid: UUID,
        val sortOrder: Int,
        val look: Look?,
        val palette: Palette?,
        val enabled: Boolean = true,
        val targets: List<TargetRef> = emptyList(),
        val mask: String? = null,
        val blendMode: String = "OVERRIDE",
        val amount: Double = 1.0,
        val stomp: Boolean = false,
        val delayed: Boolean = false,
        val interval: Boolean = false,
        val randomWindow: Boolean = false,
    ) {
        val contributes: Boolean get() = enabled && amount > 0.0
    }

    data class OwnRow(val target: TargetRef, val attribute: String)

    data class Cue(val name: String, val layers: List<Layer>, val ownRows: List<OwnRow>, val nextSortOrder: Int)

    /** What the stored patch says, without a rig loaded. Null is "not known". */
    interface Rig {
        fun members(group: String): List<String>?

        fun familyOf(head: String, attribute: String): AttributeFamily?
    }

    data class NewRow(val uuid: UUID, val target: TargetRef, val attribute: String, val value: String, val sortOrder: Int, val fadeMs: Long? = null)

    data class Kept(val layer: UUID, val palette: String, val reason: String)

    data class Decision(val converted: List<UUID>, val rows: List<NewRow>, val kept: List<Kept>) {
        val changes: Boolean get() = converted.isNotEmpty()
    }

    private val POSITION_ATTRIBUTES = setOf("position", "pan", "tilt", "panFine", "tiltFine")

    /** Where one layer target lands: its heads (null when a group is unknown) and the attributes it reaches there. */
    private class Reach(val target: TargetRef, val heads: Set<String>?, val attributes: List<String>)

    /** What one contributing layer above asserts: on [heads] (null: any), in [families] (null: any). */
    private class Claim(val heads: Set<String>?, val families: Set<AttributeFamily>?, val who: String)

    fun decide(cue: Cue, rig: Rig): Decision {
        val claims = ArrayList<Claim>()
        val kept = ArrayList<Kept>()
        val converting = ArrayList<Pair<Layer, List<Pair<TargetRef, String>>>>()
        for (layer in cue.layers.sortedWith(compareBy({ it.sortOrder }, { it.id })).asReversed()) {
            val look = layer.look
            val palette = layer.palette
            if (look != null) {
                if (layer.contributes) claims += lookClaims(look, layer, rig)
                continue
            }
            if (palette == null || palette.isFx) continue
            val family = paletteFamily(palette.rows.map { it.attribute }, null, null)
            val mask = runCatching { parseFamilies(layer.mask?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }) }
            val reach = reachOf(layer, palette, mask.getOrNull(), rig)
            val reason = settingsProblem(layer, family, mask.isSuccess, mask.getOrNull())
                ?: (if (reach.isEmpty()) "it lands nothing" else null)
                ?: aboveProblem(reach, family!!, claims)
            val rows = if (reason == null) ownRowProblem(reach, cue.ownRows, rig) else null
            if (layer.contributes && family != null && maskAllowsFamily(mask.getOrNull(), family)) {
                claims += Claim(reach.unionHeads(), setOf(family), "palette '${palette.name}'")
            }
            when {
                reason != null -> kept += Kept(layer.uuid, palette.name, reason)
                rows is RowsOrProblem.Problem -> kept += Kept(layer.uuid, palette.name, rows.reason)
                rows is RowsOrProblem.Rows -> converting += layer to rows.rows
            }
        }
        var sortOrder = cue.nextSortOrder
        val newRows = converting.asReversed().flatMap { (layer, rows) ->
            val value = PaletteReferences.valueOf(layer.palette!!.uuid)
            rows.map { (target, attribute) ->
                NewRow(rowUuid(layer.uuid, target, attribute), target, attribute, value, sortOrder++, layer.palette.fadeMs)
            }
        }
        return Decision(converting.asReversed().map { it.first.uuid }, newRows, kept.asReversed())
    }

    /**
     * A converted row's uuid, named from its layer, target and attribute, so two desks converting
     * one archive write the same record.
     */
    fun rowUuid(layer: UUID, target: TargetRef, attribute: String): UUID =
        UUID.nameUUIDFromBytes("palette-layer:$layer:${target.discriminator}:${target.key}:$attribute".toByteArray())

    private fun settingsProblem(layer: Layer, family: AttributeFamily?, maskParses: Boolean, mask: Set<AttributeFamily>?): String? = when {
        !layer.enabled -> "it is disabled"
        layer.amount != 1.0 -> "its amount is ${"%.0f".format(layer.amount * 100)} %"
        !layer.blendMode.equals("OVERRIDE", ignoreCase = true) -> "it blends ${layer.blendMode}"
        layer.stomp -> "it stomps the layers below"
        layer.delayed || layer.interval || layer.randomWindow -> "it is timed"
        family == null -> "its palette names no attribute"
        !maskParses -> "its mask '${layer.mask}' does not parse"
        mask != null && family !in mask -> "it is masked to ${mask.joinToString(",") { it.name }}"
        else -> null
    }

    private fun maskAllowsFamily(mask: Set<AttributeFamily>?, family: AttributeFamily) = mask == null || family in mask

    private fun heads(target: TargetRef, rig: Rig): Set<String>? = when (target) {
        is TargetRef.Fixture -> setOf(target.key)
        is TargetRef.Group -> rig.members(target.key)?.toSet()
    }

    /** Whether a head named [a] and one named [b] can be the same light: one key, or a fixture and its cell. */
    private fun sameLight(a: String, b: String) = a == b || a.startsWith("$b.") || b.startsWith("$a.")

    private fun overlaps(a: Set<String>?, b: Set<String>?): Boolean =
        if (a == null || b == null) true else a.any { x -> b.any { y -> sameLight(x, y) } }

    private fun List<Reach>.unionHeads(): Set<String>? =
        if (any { it.heads == null }) null else flatMapTo(LinkedHashSet()) { it.heads!! }

    private fun reachOf(layer: Layer, palette: Palette, mask: Set<AttributeFamily>?, rig: Rig): List<Reach> {
        fun attributesOf(rows: List<PaletteRow>) = rows.map { canonicalAttributeName(it.attribute) }
            .filter { mask == null || PaletteAttribute.ofOrNull(it)?.family.let { f -> f == null || f in mask } }
            .distinct()
        val generic = palette.rows.filter { it.targetKey == null }
        val bound = palette.rows.filter { it.targetKey != null }
        if (layer.targets.isEmpty()) {
            return bound.map { it.targetKey!! }.distinct().mapNotNull { key ->
                val attributes = attributesOf(bound.filter { it.targetKey == key })
                if (attributes.isEmpty()) null else Reach(TargetRef.Fixture(key), setOf(key), attributes)
            }
        }
        return layer.targets.distinct().mapNotNull { target ->
            val heads = heads(target, rig)
            val boundHere = bound.filter { row ->
                heads == null || heads.any { head -> row.targetKey == head || row.targetKey!!.startsWith("$head.") }
            }
            val attributes = attributesOf(generic + boundHere)
            if (attributes.isEmpty()) null else Reach(target, heads, attributes)
        }
    }

    private fun familyOf(head: String, attribute: String, rig: Rig): AttributeFamily? {
        val canonical = canonicalAttributeName(attribute)
        PaletteAttribute.ofOrNull(canonical)?.let { return it.family }
        if (canonical in POSITION_ATTRIBUTES) return AttributeFamily.POSITION
        return rig.familyOf(head, canonical)
    }

    private fun lookClaims(look: Look, layer: Layer, rig: Rig): List<Claim> {
        // An unparsable mask is no mask to the cook.
        val mask = runCatching { parseFamilies(layer.mask?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }) }.getOrNull()
        return look.rows.flatMap { row ->
            val heads = row.elementKey?.let { setOf(it) } ?: heads(row.target, rig)
            if (heads == null) return@flatMap listOf(Claim(null, null, "Look '${look.name}'"))
            heads.mapNotNull { head ->
                val family = familyOf(head, row.attribute, rig)
                if (mask != null && family != null && family !in mask) null else Claim(setOf(head), family?.let { setOf(it) }, "Look '${look.name}'")
            }
        }
    }

    private fun aboveProblem(reach: List<Reach>, family: AttributeFamily, claims: List<Claim>): String? {
        val heads = reach.unionHeads()
        val over = claims.firstOrNull { (it.families == null || family in it.families) && overlaps(it.heads, heads) } ?: return null
        return "${over.who} above it asserts ${family.name.lowercase()} on the heads it reaches"
    }

    private sealed interface RowsOrProblem {
        data class Rows(val rows: List<Pair<TargetRef, String>>) : RowsOrProblem
        data class Problem(val reason: String) : RowsOrProblem
    }

    /**
     * The rows [reach] becomes beside the cue's [own] rows, or why it cannot: an own row on the same
     * target and attribute already won, so its row is left out; one on another target that the new
     * row would beat — a group's under a fixture's, or one of two group rows, where the later wins —
     * keeps the layer. A colour row lands on whatever colour property a head has, so an own row on a
     * colour property outside the palette vocabulary counts as the same attribute.
     */
    private fun ownRowProblem(reach: List<Reach>, own: List<OwnRow>, rig: Rig): RowsOrProblem {
        val rows = ArrayList<Pair<TargetRef, String>>()
        for (r in reach) {
            for (attribute in r.attributes) {
                if (own.any { it.target == r.target && canonicalAttributeName(it.attribute) == attribute }) continue
                for (o in own) {
                    val ownHeads = heads(o.target, rig)
                    if (!overlaps(ownHeads, r.heads)) continue
                    val canonical = canonicalAttributeName(o.attribute)
                    val same = canonical == attribute ||
                        (attribute == PaletteAttribute.COLOUR.attribute && PaletteAttribute.ofOrNull(canonical) == null &&
                            canonical !in POSITION_ATTRIBUTES &&
                            (ownHeads ?: setOf(o.target.key)).any { familyOf(it, canonical, rig).let { f -> f == null || f == AttributeFamily.COLOUR } })
                    if (!same) continue
                    val ownFixtureBeatsGroup = r.target is TargetRef.Group && o.target is TargetRef.Fixture && r.heads?.contains(o.target.key) == true
                    if (!ownFixtureBeatsGroup) {
                        return RowsOrProblem.Problem(
                            "the cue's own ${o.target.key} ${o.attribute} row would no longer beat it on ${r.target.key}",
                        )
                    }
                }
                rows += r.target to attribute
            }
        }
        return RowsOrProblem.Rows(rows)
    }

    // ─── The stored data ─────────────────────────────────────────────────────

    /** One cue's decision, with what the log names it by. */
    class CuePlan(val cueId: Int, val cueName: String, val projectName: String, val decision: Decision)

    /**
     * The decision for every cue holding a value palette layer — in [projectIds] only, when given.
     * Reads the tables directly, so it runs with no rig loaded.
     */
    fun JdbcTransaction.plan(projectIds: Set<Int>? = null): List<CuePlan> {
        val palettesWithLayers = DaoCueLayers.select(DaoCueLayers.cue, DaoCueLayers.palette)
            .where { DaoCueLayers.palette.isNotNull() }
            .map { it[DaoCueLayers.cue].value to it[DaoCueLayers.palette]!!.value }
        if (palettesWithLayers.isEmpty()) return emptyList()
        val fxPalettes = DaoPaletteFx.select(DaoPaletteFx.palette).mapTo(HashSet()) { it[DaoPaletteFx.palette].value }
        val cueIds = palettesWithLayers.filter { it.second !in fxPalettes }.mapTo(HashSet()) { it.first }
        if (cueIds.isEmpty()) return emptyList()

        val cues = DaoCues.selectAll().where { DaoCues.id inList cueIds }
            .associate { it[DaoCues.id].value to (it[DaoCues.name] to it[DaoCues.project].value) }
            .filterValues { projectIds == null || it.second in projectIds }
        if (cues.isEmpty()) return emptyList()
        val projects = DaoProjects.selectAll().associate { it[DaoProjects.id].value to it }

        // Only the projects the cues are in: a layer names its own project's palettes and Looks.
        val inProjects = cues.values.mapTo(HashSet()) { EntityID(it.second, DaoProjects) }
        val paletteIds = DaoPalettes.select(DaoPalettes.id).where { DaoPalettes.project inList inProjects }.map { it[DaoPalettes.id] }
        val lookIds = DaoLooks.select(DaoLooks.id).where { DaoLooks.project inList inProjects }.map { it[DaoLooks.id] }
        val paletteRows = DaoPaletteRows.selectAll().where { DaoPaletteRows.palette inList paletteIds }.orderBy(DaoPaletteRows.sortOrder to SortOrder.ASC, DaoPaletteRows.id to SortOrder.ASC)
            .groupBy({ it[DaoPaletteRows.palette].value }) { row ->
                val generic = row[DaoPaletteRows.targetType] == DEFERRED_TARGET_TYPE
                PaletteRow(if (generic) null else row[DaoPaletteRows.targetKey], row[DaoPaletteRows.attribute])
            }
        val palettes = DaoPalettes.selectAll().where { DaoPalettes.id inList paletteIds }.associate { row ->
            val id = row[DaoPalettes.id].value
            id to Palette(row[DaoPalettes.uuid], row[DaoPalettes.name], id in fxPalettes, paletteRows[id].orEmpty(), row[DaoPalettes.fadeDuration]?.toMillis())
        }
        val lookRows = DaoLookRows.selectAll().where { DaoLookRows.look inList lookIds }.groupBy({ it[DaoLookRows.look].value }) { row ->
            TargetRef.ofOrNull(row[DaoLookRows.targetType], row[DaoLookRows.targetKey])
                ?.let { LookRow(it, row[DaoLookRows.elementKey], row[DaoLookRows.attribute]) }
        }
        val looks = DaoLooks.selectAll().where { DaoLooks.id inList lookIds }.associate {
            it[DaoLooks.id].value to Look(it[DaoLooks.name], lookRows[it[DaoLooks.id].value].orEmpty().filterNotNull())
        }
        val layers = DaoCueLayers.selectAll().where { DaoCueLayers.cue inList cues.keys }.groupBy({ it[DaoCueLayers.cue].value }) { row ->
            Layer(
                id = row[DaoCueLayers.id].value,
                uuid = row[DaoCueLayers.uuid],
                sortOrder = row[DaoCueLayers.sortOrder],
                look = row[DaoCueLayers.look]?.let { looks[it.value] },
                palette = row[DaoCueLayers.palette]?.let { palettes[it.value] },
                enabled = row[DaoCueLayers.enabled],
                targets = row[DaoCueLayers.targets].mapNotNull { TargetRef.ofOrNull(it.type, it.key) },
                mask = row[DaoCueLayers.attributeMask]?.takeIf { it.isNotBlank() },
                blendMode = row[DaoCueLayers.blendMode],
                amount = row[DaoCueLayers.amount],
                stomp = row[DaoCueLayers.stomp],
                delayed = row[DaoCueLayers.delay] != null,
                interval = row[DaoCueLayers.interval] != null,
                randomWindow = row[DaoCueLayers.randomWindow] != null,
            )
        }
        val ownRows = DaoCueAttributeAssignments.selectAll().where { DaoCueAttributeAssignments.cue inList cues.keys }
            .groupBy { it[DaoCueAttributeAssignments.cue].value }
        val rigs = HashMap<Int, Rig>()
        return cues.entries.sortedBy { it.key }.map { (cueId, cue) ->
            val (name, projectId) = cue
            val own = ownRows[cueId].orEmpty()
            val decision = decide(
                Cue(
                    name = name,
                    layers = layers[cueId].orEmpty(),
                    ownRows = own.mapNotNull { row ->
                        TargetRef.ofOrNull(row[DaoCueAttributeAssignments.targetType], row[DaoCueAttributeAssignments.targetKey])
                            ?.let { OwnRow(it, row[DaoCueAttributeAssignments.attribute]) }
                    },
                    nextSortOrder = (own.maxOfOrNull { it[DaoCueAttributeAssignments.sortOrder] } ?: -1) + 1,
                ),
                rigs.getOrPut(projectId) { storedRig(projectId, projects) },
            )
            CuePlan(cueId, name, projects[projectId]?.get(DaoProjects.name) ?: "#$projectId", decision)
        }
    }

    /** Write [plans]: each converted layer's rows in, the layer out. */
    fun JdbcTransaction.apply(plans: List<CuePlan>) {
        for (plan in plans) {
            val decision = plan.decision
            if (!decision.changes) continue
            if (decision.rows.isNotEmpty()) {
                DaoCueAttributeAssignments.batchInsert(decision.rows) { row ->
                    this[DaoCueAttributeAssignments.cue] = plan.cueId
                    this[DaoCueAttributeAssignments.targetType] = row.target.discriminator
                    this[DaoCueAttributeAssignments.targetKey] = row.target.key
                    this[DaoCueAttributeAssignments.attribute] = row.attribute
                    this[DaoCueAttributeAssignments.value] = row.value
                    this[DaoCueAttributeAssignments.paletteUuid] = PaletteReferences.uuidOf(row.value)
                    this[DaoCueAttributeAssignments.sortOrder] = row.sortOrder
                    this[DaoCueAttributeAssignments.fadeDuration] = row.fadeMs?.let(java.time.Duration::ofMillis)
                    this[DaoCueAttributeAssignments.uuid] = row.uuid
                }
            }
            DaoCueLayers.deleteWhere { (DaoCueLayers.cue eq plan.cueId) and (DaoCueLayers.uuid inList decision.converted) }
        }
    }

    /**
     * A project's stored rig: its patches and groups, and a show's pinned venue version's beneath
     * them, a group of the show's own name winning.
     */
    private fun JdbcTransaction.storedRig(projectId: Int, projects: Map<Int, ResultRow>): Rig {
        val project = projects[projectId]
        val venueProject = project?.takeIf { it[DaoProjects.kind] == ProjectKind.SHOW }?.let { show ->
            val venueUuid = show[DaoProjects.venueUuid] ?: return@let null
            val number = show[DaoProjects.venueVersion] ?: return@let null
            val venue = projects.values.firstOrNull { it[DaoProjects.uuid] == venueUuid && it[DaoProjects.kind] == ProjectKind.VENUE }
                ?: return@let null
            DaoVenueVersions.selectAll()
                .where { (DaoVenueVersions.venueProject eq venue[DaoProjects.id]) and (DaoVenueVersions.number eq number) }
                .firstOrNull()?.get(DaoVenueVersions.snapshotProject)?.value
        }
        val scope = listOfNotNull(venueProject, projectId)
        val patches = DaoFixturePatches.select(DaoFixturePatches.id, DaoFixturePatches.key, DaoFixturePatches.fixtureTypeKey, DaoFixturePatches.project)
            .where { DaoFixturePatches.project inList scope }
            .toList()
        val keyOf = patches.associate { it[DaoFixturePatches.id].value to it[DaoFixturePatches.key] }
        val typeOf = patches.sortedBy { scope.indexOf(it[DaoFixturePatches.project].value) }
            .associate { it[DaoFixturePatches.key] to it[DaoFixturePatches.fixtureTypeKey] }
        val groups = DaoFixtureGroups.select(DaoFixtureGroups.id, DaoFixtureGroups.name, DaoFixtureGroups.project)
            .where { DaoFixtureGroups.project inList scope }
            .sortedBy { scope.indexOf(it[DaoFixtureGroups.project].value) }
            .associate { it[DaoFixtureGroups.name] to it[DaoFixtureGroups.id].value }
        val members = DaoFixtureGroupMembers.selectAll().where { DaoFixtureGroupMembers.group inList groups.values }
            .groupBy({ it[DaoFixtureGroupMembers.group].value }) { keyOf[it[DaoFixtureGroupMembers.fixturePatch].value] }
        return object : Rig {
            override fun members(group: String): List<String>? = groups[group]?.let { id -> members[id].orEmpty().filterNotNull() }

            override fun familyOf(head: String, attribute: String): AttributeFamily? = typeFamily(typeOf[head], attribute)
        }
    }

    /** The family of [attribute] on a fixture of type [typeKey], from its class's declared attributes. */
    fun typeFamily(typeKey: String?, attribute: String): AttributeFamily? {
        val klass = typeKey?.let { FixtureTypeRegistry.classForTypeKey(it) } ?: return null
        return FixtureAttributeCatalogue.of(klass).byName[attribute]?.category?.family()
    }
}
