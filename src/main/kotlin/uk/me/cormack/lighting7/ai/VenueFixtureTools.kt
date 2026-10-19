package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.effectiveKind
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.FacetValue
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.SentColour
import uk.me.cormack.lighting7.models.ShowColours
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.facetElement
import uk.me.cormack.lighting7.models.kindUnderLantern
import uk.me.cormack.lighting7.models.venueFacetOf
import uk.me.cormack.lighting7.routes.addressOverlaps
import uk.me.cormack.lighting7.routes.parseFacet
import uk.me.cormack.lighting7.routes.syncStoredLocates
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.ShowHides
import uk.me.cormack.lighting7.venue.ShowOverrides
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenuePins
import uk.me.cormack.lighting7.venue.VenueRigGuard

/**
 * What a show changes about its venue's fixtures, over MCP (venue plan sessions 3 and 5) — the fixture
 * sheet's *Venue | This show* switch per facet, *Keep mine*, *Hide in this show* and an Address override
 * (`override_venue_fixtures`), and `place_fixtures`' edit of a venue fixture's overridden facets
 * ([resolvePlacement]). The rules are `venue/ShowOverrides.kt`'s and `venue/ShowHides.kt`'s, and every
 * facet value is checked by the facet route's own parser, so a tool write and the sheet's agree.
 */
internal class VenueFixtureTools(private val state: State) {

    // ─── override_venue_fixtures ────────────────────────────────────────

    private enum class Use { SHOW, VENUE, KEEP_MINE }

    private class Row(
        val index: Int,
        val key: String,
        val facets: Map<PatchFacet, Use>,
        val hidden: Boolean?,
        val address: JsonObject?,
        /** The colour the row sends, when it sends one (a null value takes the venue's back). */
        val colour: SentColour? = null,
    ) {
        val where get() = "fixtures[$index] ('$key')"
    }

    /** What one row did, for the answer and for the live rig. */
    private class Done(val key: String) {
        val changed = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var reloads = false
    }

    fun overrideVenueFixtures(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("fixtures", "dryRun", "projectId"), "override_venue_fixtures").toMutableList()
        val list = input["fixtures"] as? JsonArray
        if (list == null || list.isEmpty()) problems += "fixtures must be a non-empty array"
        val dryRun = when (val e = input["dryRun"]) {
            null, is JsonNull -> false
            else -> (e as? JsonPrimitive)?.booleanOrNull ?: run { problems += "dryRun must be a boolean"; false }
        }
        val rows = list.orEmpty().mapIndexedNotNull { i, e -> parseRow(i, e, problems) }
        rows.groupBy { it.key }.filter { it.value.size > 1 }.keys.forEach { problems += "key '$it' is listed twice" }
        if (problems.isNotEmpty()) return rejected(problems)

        val (done, states) = transaction(state.database) {
            val project = DaoProject[target.project.id]
            val venue = RigScope.of(project).venue
            if (venue == null) {
                if (VenuePins.isMissing(project)) throw VenuePins.missing(project)
                problems += "'${project.name}' is not a show on a venue: only a show's venue fixtures take overrides " +
                    "(a standalone show's and a venue's own fixtures are edited with patch_fixtures and place_fixtures)"
                return@transaction emptyList<Done>() to emptyMap()
            }
            // By key in the pinned version, hidden ones included: showing one again is a row here.
            val venuePatches = DaoFixturePatch.find { DaoFixturePatches.project eq venue.snapshotId }.associateBy { it.key }
            val ownKeys = RigScope.of(project).own.patches().map { it.key }.toSet()
            val done = rows.mapNotNull { row ->
                val patch = venuePatches[row.key]
                if (patch == null) {
                    problems += "${row.where}: " + if (row.key in ownKeys) {
                        "this show's own fixture — edit it with patch_fixtures and place_fixtures instead"
                    } else {
                        "${venue.venueName} v${venue.version} has no fixture with this key (get_patch lists the venue's)"
                    }
                    return@mapNotNull null
                }
                try {
                    apply(project, patch, row)
                } catch (e: VenueException) {
                    problems += "${row.where}: ${e.code}: ${e.message}"
                    null
                }
            }
            // Read before a dry run rolls back, so it answers what the call would leave.
            val states = if (problems.isEmpty()) overrideStates(project, done.map { it.key }) else emptyMap()
            if (problems.isNotEmpty() || dryRun) TransactionManager.current().rollback()
            done to states
        }
        if (problems.isNotEmpty()) return rejected(problems)

        val anyChange = done.any { it.changed.isNotEmpty() }
        if (!dryRun && anyChange) {
            if (target.isCurrent && done.any { it.reloads }) {
                DbFixtureLoader.loadFixtures(
                    target.project.id.value, state.show.fixtures, state.database,
                    parkSource = state.show.outputSource, hold = state.show.rigHold,
                )
            } else if (target.isCurrent) {
                // A Focus taken back to the venue's (or made this show's) can move a stored locate,
                // which the live patch metadata carries for Locate without a reload.
                syncStoredLocates(state, target.project.id.value, done.map { it.key })
            }
            state.show.fixtures.patchListChanged()
        }
        val count = done.count { it.changed.isNotEmpty() }
        return success(
            if (dryRun) "Dry run: would change $count venue fixture(s)" else "Changed $count venue fixture(s)",
            buildJsonObject {
                put("dryRun", dryRun)
                putJsonArray("fixtures") {
                    done.forEach { d ->
                        addJsonObject {
                            put("key", d.key)
                            putJsonArray("changed") { d.changed.forEach { add(it) } }
                            if (d.warnings.isNotEmpty()) putJsonArray("warnings") { d.warnings.forEach { add(it) } }
                            states[d.key]?.let { s ->
                                if (s.hidden) put("hidden", true)
                                putJsonArray("overrides") {
                                    s.facets.forEach { f ->
                                        addJsonObject {
                                            put("facet", f.facet.name)
                                            if (f.drifted) put("drifted", true)
                                            if (f.dormant) put("dormant", true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    private fun parseRow(index: Int, element: JsonElement, problems: MutableList<String>): Row? {
        val row = element as? JsonObject ?: run { problems += "fixtures[$index]: not an object"; return null }
        val key = (row["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val where = "fixtures[$index] ('$key')"
        val before = problems.size
        if (key.isEmpty()) problems += "fixtures[$index]: key is required"
        problems += unknownFields(row, listOf("key", "facets", "hidden", "address", "colour"), where)

        val facets = linkedMapOf<PatchFacet, Use>()
        when (val e = row["facets"]) {
            null, is JsonNull -> Unit
            is JsonObject -> e.forEach { (name, value) ->
                val facet = PatchFacet.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val use = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { v -> Use.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } }
                when {
                    facet == null -> problems += "$where: '$name' is not a facet (${PatchFacet.entries.joinToString(" | ")})"
                    use == null -> problems += "$where: facets.$name must be one of ${Use.entries.joinToString(" | ")}"
                    else -> facets[facet] = use
                }
            }
            else -> problems += "$where: facets must be an object of facet → ${Use.entries.joinToString(" | ")}"
        }
        val hidden = when (val e = row["hidden"]) {
            null -> null
            else -> (e as? JsonPrimitive)?.booleanOrNull ?: run { problems += "$where: hidden must be a boolean"; null }
        }
        val address = when (val e = row["address"]) {
            null, is JsonNull -> null
            is JsonObject -> e
            else -> { problems += "$where: address must be an object {universe, subnet?, startChannel}"; null }
        }
        if (address != null) {
            problems += unknownFields(address, listOf("universe", "subnet", "startChannel"), "$where.address")
            when (facets[PatchFacet.ADDRESS]) {
                null, Use.SHOW -> facets[PatchFacet.ADDRESS] = Use.SHOW
                else -> problems += "$where: an address makes the Address this show's, so facets.ADDRESS cannot be ${facets[PatchFacet.ADDRESS]}"
            }
        }
        if (hidden == true && facets.values.any { it != Use.VENUE }) {
            problems += "$where: a hidden fixture takes no override — only facets set to VENUE may go with hidden: true"
        }
        val colourSent = FixtureColours.FIELD in row
        val colour = if (colourSent) FixtureColours.parseSent(row[FixtureColours.FIELD], where, problems) else null
        if (hidden == true && colourSent) problems += "$where: a hidden fixture takes no colour — leave colour out"
        if (facets.isEmpty() && hidden == null && !colourSent) problems += "$where: nothing to change — send facets, address, colour or hidden"
        return if (problems.size == before) Row(index, key, facets, hidden, address, colour) else null
    }

    /**
     * Lands one row on [patch], a venue patch of [project]'s pinned version: shown again first (a hidden
     * patch takes no override), then each facet, then hidden. Throws the first [VenueException]. Each
     * step re-reads the rig — a [ShowOverrides.Target] caches the hides and overrides it was made with.
     */
    private fun apply(project: DaoProject, patch: DaoFixturePatch, row: Row): Done {
        val done = Done(row.key)
        if (row.hidden == false && ShowHides.setHiddenByUuid(project, HideKind.PATCH, patch.uuid, false)) {
            done.changed += "shown"
            done.reloads = true
        }
        for ((facet, use) in row.facets.entries.sortedBy { it.key.ordinal }) {
            val target = ShowOverrides.target(project, patch.id.value)
            val label = facet.name.lowercase()
            val changed = when (use) {
                Use.VENUE -> ShowOverrides.put(project, target, facet, null)
                Use.KEEP_MINE -> ShowOverrides.keepMine(project, target, facet)
                Use.SHOW -> {
                    val stored = target.rig.overrides[patch.uuid]?.get(facet)
                    val value = when {
                        facet == PatchFacet.ADDRESS && row.address != null -> parseFacet(facet, row.address, target)
                        // Already this show's: the switch is on, and its value stays as it is.
                        stored != null -> null
                        // The switch's first value is the venue's, so turning it on changes nothing drawn or sent.
                        else -> venueFacetOf(patch, target.placements, facet)
                    }
                    value != null && ShowOverrides.put(project, target, facet, value)
                }
            }
            if (changed) {
                done.changed += when (use) {
                    Use.VENUE -> "$label: venue's"
                    Use.KEEP_MINE -> "$label: kept mine"
                    Use.SHOW -> "$label: this show's"
                }
                if (use != Use.KEEP_MINE && (facet == PatchFacet.ADDRESS || facet == PatchFacet.MEDIA)) done.reloads = true
            }
            if (facet == PatchFacet.ADDRESS && row.address != null) {
                done.warnings += addressOverlaps(ShowOverrides.target(project, patch.id.value))
            }
        }
        row.colour?.let { sent ->
            // As the REST patch PUT refuses any write to a hidden venue patch.
            if (RigScope.of(project).isHidden(HideKind.PATCH, patch.uuid)) {
                throw VenueException.conflict(
                    VenueRigGuard.CODE_VENUE_OWNED,
                    "the fixture is hidden in this show, and a hidden fixture takes no colour: bring it back with hidden: false first",
                )
            }
            if (ShowColours.put(project.id, patch.uuid, sent.value)) {
                done.changed += sent.value?.let { "colour: $it" } ?: "colour: venue's"
            }
        }
        if (row.hidden == true && ShowHides.setHiddenByUuid(project, HideKind.PATCH, patch.uuid, true)) {
            done.changed += "hidden"
            done.reloads = true
        }
        return done
    }

    private class OverrideState(val hidden: Boolean, val facets: List<ShowOverrides.FacetStateDto>)

    private fun overrideStates(project: DaoProject, keys: List<String>): Map<String, OverrideState> {
        val rig = RigScope.of(project)
        val venue = rig.venue ?: return emptyMap()
        return DaoFixturePatch.find { DaoFixturePatches.project eq venue.snapshotId }
            .filter { it.key in keys }
            .associate { p ->
                p.key to OverrideState(rig.isHidden(HideKind.PATCH, p.uuid), ShowOverrides.statesOf(rig, p, extraPlacementsOf(p)))
            }
    }

    // ─── place_fixtures on a venue fixture ──────────────────────────────

    /**
     * One venue fixture's `place_fixtures` row, resolved: each facet it touches as the show will hold it,
     * and its colour when the row sends one ([colour]'s value null takes the venue's back).
     */
    class VenuePlacement(val patchId: Int, val key: String, val values: List<FacetValue>, val colour: SentColour? = null) {
        val reloads: Boolean get() = values.any { it.facet == PatchFacet.MEDIA }
    }

    /**
     * [row], a `place_fixtures` row naming the venue patch [key] in [project] — a show on a venue — as
     * the facet values it leaves the show holding, or null with every problem added to [problems]. Each
     * field lands in its facet (rigging and x/y/z/yaw/pitch/roll/length → Position, the lantern and its
     * focus, beamAngleDeg and the lamp → Focus, gelCode and media → Media), over the show's current value of
     * that facet; a facet the row touches must already be this show's. `alsoAt` addresses the venue's
     * own lanterns in order, one entry each, and an entry changes only the fields it sends. Inside a
     * transaction.
     */
    fun resolvePlacement(project: DaoProject, key: String, row: JsonObject, where: String, problems: MutableList<String>): VenuePlacement? {
        val before = problems.size
        val rig = RigScope.of(project)
        val venue = rig.venue ?: return null
        val patch = DaoFixturePatch.find { (DaoFixturePatches.project eq venue.snapshotId) and (DaoFixturePatches.key eq key) }
            .firstOrNull() ?: return null
        val target = ShowOverrides.target(project, patch.id.value)
        val riggingUuids = rig.riggings().associate { it.name to it.uuid.toString() }

        listOf("kind", "stageHidden").filter { it in row }.forEach {
            problems += "${VenueRigGuard.CODE_VENUE_OWNED}: $where: $it is the venue's on a venue fixture — a show can make only " +
                "its address, position, focus and media its own (override_venue_fixtures); to change it for every show, edit the " +
                "venue (projectId = the venue's id) and publish_venue"
        }

        // The row's fields by facet, each renamed to the facet's own name.
        val sent = mutableMapOf<PatchFacet, MutableMap<String, JsonElement>>()
        fun field(facet: PatchFacet, name: String, value: JsonElement) {
            sent.getOrPut(facet) { mutableMapOf() }[name] = value
        }
        fun rigging(raw: JsonElement, at: String): JsonElement? {
            if (raw is JsonNull) return JsonNull
            val name = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?: run { problems += "$at must be a rigging's name"; return null }
            if (name.isEmpty()) return JsonNull
            return riggingUuids[name]?.let(::JsonPrimitive)
                ?: run { problems += "$at: no rigging named '$name' (known: ${riggingUuids.keys.joinToString()})"; null }
        }
        var colour: SentColour? = null
        for ((name, value) in row) {
            when (name) {
                FixtureColours.FIELD -> colour = FixtureColours.parseSent(value, where, problems)
                "rigging" -> rigging(value, "$where: rigging")?.let { field(PatchFacet.POSITION, "riggingUuid", it) }
                in POSITION_NAMES -> field(PatchFacet.POSITION, POSITION_NAMES.getValue(name), value)
                in LanternFocus.KEYS, "beamAngleDeg", "lampWatts", "lampKelvin", "locate" -> field(PatchFacet.FOCUS, name, value)
                "gelCode", "media" -> field(PatchFacet.MEDIA, name, value)
            }
        }

        // `alsoAt`: the venue's lanterns, matched in order — a show cannot add or remove one.
        val placementSent = mutableMapOf<PatchFacet, MutableMap<String, MutableMap<String, JsonElement>>>()
        val zoomSentFor = mutableSetOf<String>()
        when (val e = row["alsoAt"]) {
            null -> Unit
            !is JsonArray -> problems += "$where: alsoAt must be an array"
            else -> if (e.size != target.placements.size) {
                problems += "$where: alsoAt has ${e.size} entr${if (e.size == 1) "y" else "ies"}, but the venue hangs " +
                    "'$key' as ${target.placements.size} other lantern(s): list one entry per lantern in get_patch's order " +
                    "({} leaves one as it is) — a venue fixture's lanterns are the venue's, so a show cannot add or remove one"
            } else e.forEachIndexed { i, entryElement ->
                val at = "$where: alsoAt[$i]"
                val entry = entryElement as? JsonObject ?: run { problems += "$at is not an object"; return@forEachIndexed }
                val placement = target.placements[i]
                val uuid = placement.uuid.toString()
                fun put(facet: PatchFacet, name: String, value: JsonElement) {
                    placementSent.getOrPut(facet) { mutableMapOf() }.getOrPut(uuid) { mutableMapOf() }[name] = value
                }
                for ((name, value) in entry) {
                    when (name) {
                        "label" -> {
                            val label = (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            if (label != placement.label?.trim()?.takeIf { it.isNotEmpty() }) {
                                problems += "$at: label is the venue's ('${placement.label.orEmpty()}'): leave it out, or send it unchanged"
                            }
                        }
                        "rigging" -> rigging(value, "$at.rigging")?.let { put(PatchFacet.POSITION, "riggingUuid", it) }
                        in POSITION_NAMES -> put(PatchFacet.POSITION, POSITION_NAMES.getValue(name), value)
                        in LanternFocus.KEYS -> {
                            put(PatchFacet.FOCUS, name, value)
                            if (name == "zoomDeg") zoomSentFor += uuid
                        }
                        "media" -> put(PatchFacet.MEDIA, name, value)
                        else -> problems += "$at: '$name' is not a lantern's field"
                    }
                }
            }
        }

        val touched = (sent.keys + placementSent.keys).sortedBy { it.ordinal }
        val stored = rig.overrides[patch.uuid].orEmpty()
        val values = touched.mapNotNull { facet ->
            val current = stored[facet] ?: run {
                val whose = "${venue.venueName} v${venue.version}"
                problems += "${VenueRigGuard.CODE_VENUE_OWNED}: $where: Patch '$key''s ${facet.name.lowercase()} is the venue's " +
                    "($whose): make it this show's first with override_venue_fixtures " +
                    "({key: '$key', facets: {${facet.name}: 'SHOW'}}), then place it — or, to change it for every show, edit the venue " +
                    "(projectId = the venue's id) and publish_venue"
                return@mapNotNull null
            }
            val base = facetElement(current).jsonObject
            val venuePlacements = (facetElement(venueFacetOf(patch, target.placements, facet)).jsonObject["placements"] as? JsonArray)
                .orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.placementUuid() }
            val merged = overlay(base, sent[facet].orEmpty(), placementSent[facet].orEmpty(), venuePlacements)
            val ready = if (facet == PatchFacet.FOCUS) {
                clearStaleZooms(merged, patch, zoomSent = "zoomDeg" in sent[facet].orEmpty(), zoomSentFor)
            } else merged
            try {
                parseFacet(facet, ready, target)
            } catch (e: VenueException) {
                problems += "$where: ${facet.name.lowercase()}: ${e.message}"
                null
            }
        }
        return if (problems.size == before) VenuePlacement(patch.id.value, key, values, colour) else null
    }

    /** [base] with [fields] laid over it, and each placement's [placementFields] over its entry — the venue's when [base] names none. */
    private fun overlay(
        base: JsonObject,
        fields: Map<String, JsonElement>,
        placementFields: Map<String, Map<String, JsonElement>>,
        venuePlacements: Map<String, JsonObject>,
    ): JsonObject {
        val out = base.toMutableMap()
        fields.forEach { (k, v) -> out[k] = v }
        if (placementFields.isNotEmpty()) {
            val entries = (base["placements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .associateBy { it.placementUuid() }.toMutableMap()
            placementFields.forEach { (uuid, sent) ->
                val start = entries[uuid] ?: venuePlacements[uuid] ?: JsonObject(mapOf("placementUuid" to JsonPrimitive(uuid)))
                entries[uuid] = JsonObject(start + sent)
            }
            out["placements"] = JsonArray(entries.values.toList())
        }
        return JsonObject(out)
    }

    /**
     * A Focus write that does not send a zoom drops a stored one its lantern cannot take — the patch's,
     * and each lantern's, its own or the one it inherits — as a patch write does (`resolvePatchFocus`,
     * `clearStaleInheritedZooms`): the facet is checked whole, so one stale zoom would refuse every edit.
     * [value] answered as it is when it does not decode, for the parser to name why.
     */
    private fun clearStaleZooms(value: JsonObject, patch: DaoFixturePatch, zoomSent: Boolean, zoomSentFor: Set<String>): JsonObject {
        var focus = decodeFacet(PatchFacet.FOCUS, value) as FocusFacet? ?: return value
        val kind = effectiveKind(
            patch.fixtureTypeKey, kindUnderLantern(focus.lanternType, patch.lanternType, patch.kindOverride), focus.lanternType,
        ) ?: FixtureKind.GENERIC
        if (!zoomSent && focus.lantern.zoomProblem(kind) != null) focus = focus.copy(zoomDeg = null)
        focus = focus.copy(
            placements = focus.placements.map { p ->
                val stale = p.zoomDeg != null && p.placementUuid !in zoomSentFor &&
                    p.lantern.zoomProblem(kind, inheritedLantern = focus.lanternType) != null
                if (stale) p.copy(zoomDeg = null) else p
            },
        )
        return facetElement(focus).jsonObject
    }

    private fun JsonObject.placementUuid(): String = (this["placementUuid"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun rejected(problems: List<String>): ToolExecutionResult = ToolExecutionResult(
        success = false,
        description = "Nothing was written: ${problems.size} problem(s)",
        result = buildJsonObject {
            put("error", "Nothing was written: fix these ${problems.size} problem(s) and send the request again")
            VenueRigGuard.codeOf(problems)?.let { put("code", it) }
            putJsonArray("problems") { problems.distinct().forEach { add(it) } }
        }.toString(),
    )

    private fun unknownFields(row: JsonObject, known: List<String>, where: String): List<String> =
        row.keys.filter { it !in known }.map { "$where: unknown field '$it' (known: ${known.joinToString()})" }

    private companion object {
        /** `place_fixtures`' position fields, by the Position facet's name for each. */
        val POSITION_NAMES = mapOf(
            "x" to "stageX", "y" to "stageY", "z" to "stageZ",
            "yawDeg" to "baseYawDeg", "pitchDeg" to "basePitchDeg", "rollDeg" to "baseRollDeg", "lengthM" to "lengthM",
        )
    }
}
