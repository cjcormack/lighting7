package uk.me.cormack.lighting7.routes

import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.effectiveKind
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.EffectivePatch
import uk.me.cormack.lighting7.models.FacetValue
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.MAX_FIXTURE_LENGTH_M
import uk.me.cormack.lighting7.models.MIN_FIXTURE_LENGTH_M
import uk.me.cormack.lighting7.models.MediaFacet
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PlacementFocus
import uk.me.cormack.lighting7.models.PlacementMedia
import uk.me.cormack.lighting7.models.PlacementPosition
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.kindUnderLantern
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.ShowOverrides
import uk.me.cormack.lighting7.venue.VenueException
import java.util.UUID

/**
 * A venue patch's **facets** in a show (venue plan §3.5, session 5). `venue/ShowOverrides.kt` holds the
 * rules; this file reads and checks the bodies.
 *
 * - `PUT /projects/{id}/patches/{pid}/facets/{facet}` `{value}` — make [PatchFacet] `facet` of venue
 *   patch `pid` this show's, the whole facet (`models/patchFacets.kt` lists its fields), recording the
 *   pinned version's value as its base when the override is new. `{value: null}` reverts it to the
 *   venue's — which is also *Take venue's* on a drifted facet. The value is checked like the patch write
 *   it stands for, every problem at once (400 `FACET_INVALID`): Address a universe of this rig and a
 *   start channel the type fits from (an overlap is allowed — P8 — and answered in `warnings`, the way
 *   the Patch list rings one); Position the stage ranges, a rigging of this rig, a length only on a type
 *   that takes one; Focus the lantern library and the type's focus rules; Media the type's loadable
 *   settings. A placement is named by its venue uuid and must be one of the patch's.
 * - `POST …/facets/{facet}/keep-mine` — *Keep mine*: the override stays and its base becomes the
 *   venue's current value, so the drift clears.
 *
 * Both answer `{patch, changed, warnings}`: the patch as the rig now sees it. A stored show write — the
 * same as editing a patch, so an operator may make one; a venue (`VENUE_ADMIN_ONLY` for an operator)
 * and a version (`VENUE_VERSION_READ_ONLY`) are refused before this runs (`projectWriteGuard.kt`), and
 * anything that is not a show on a venue, or a show's own patch, is 409 `NOT_A_VENUE_RECORD`. Stored
 * data, ungated by the current project, as every patch route is; an Address or Media write to the live
 * show reloads it.
 */
internal fun Route.routeApiRestPatchFacets(state: State) {
    put<ProjectPatchFacetResource> { resource ->
        withProject(state, resource.parent.parent.projectId) { project ->
            val facet = ShowOverrides.facetOf(resource.facet)
            val body = call.receive<JsonObject>()
            if ("value" !in body) {
                throw VenueException.badRequest(
                    ShowOverrides.CODE_FACET_INVALID,
                    "A facet write carries {value}: the facet's fields, or null to take the venue's",
                )
            }
            val raw = body["value"]
            val response = transaction(state.database) {
                val target = ShowOverrides.target(project, resource.parent.patchId)
                val value = if (raw == null || raw is JsonNull) null else parseFacet(facet, raw, target)
                val changed = ShowOverrides.put(project, target, facet, value)
                facetResponse(target, changed, if (value is AddressFacet) addressOverlaps(target) else emptyList())
            }
            announceFacetChange(state, project.id.value, facet, response.changed)
            call.respond(response)
        }
    }

    post<ProjectPatchFacetKeepResource> { resource ->
        withProject(state, resource.parent.parent.parent.projectId) { project ->
            val facet = ShowOverrides.facetOf(resource.parent.facet)
            val response = transaction(state.database) {
                val target = ShowOverrides.target(project, resource.parent.parent.patchId)
                facetResponse(target, ShowOverrides.keepMine(project, target, facet), emptyList())
            }
            // Keep mine moves only the base: the rig is unchanged, the Patch list's chip is not.
            if (response.changed) state.show.fixtures.patchListChanged()
            call.respond(response)
        }
    }
}

/** What a facet write answers: the patch as the rig now sees it, and any overlap an Address landed on. */
@Serializable
data class FacetWriteResponse(
    val patch: FixturePatchDto,
    val changed: Boolean,
    /** An Address override's overlaps with other units of the rig — allowed (P8), and said. */
    val warnings: List<String> = emptyList(),
)

private fun facetResponse(target: ShowOverrides.Target, changed: Boolean, warnings: List<String>): FacetWriteResponse {
    // A fresh scope: the target's has cached the overrides it read before the write.
    val rig = RigScope.of(target.rig.projectId)
    return FacetWriteResponse(target.patch.toDto(target.placements, rig), changed, warnings)
}

private fun announceFacetChange(state: State, projectId: Int, facet: PatchFacet, changed: Boolean) {
    if (!changed) return
    // The loader builds a fixture at its address and carries its fitted media in the patch metadata;
    // position and focus are drawn, never built from.
    if ((facet == PatchFacet.ADDRESS || facet == PatchFacet.MEDIA) &&
        state.projectManager.currentProject.id.value == projectId
    ) {
        DbFixtureLoader.loadFixtures(projectId, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
    }
    state.show.fixtures.patchListChanged()
}

/**
 * Every other unit of the rig on the channels the target's patch now takes, judged by universe number
 * (a show's universe and the version's may be different rows): an overlap is a decision, never refused
 * (venue plan P8), and the Patch list rings it as it rings any other.
 */
private fun addressOverlaps(target: ShowOverrides.Target): List<String> {
    val rig = RigScope.of(target.rig.projectId)
    val all = rig.effective(rig.patches().toList())
    val self = all.firstOrNull { it.id == target.patch.id } ?: return emptyList()
    fun span(p: EffectivePatch) = p.startChannel..(p.startChannel + channelCountOf(p.fixtureTypeKey) - 1)
    val mine = span(self)
    return all.filter { other ->
        other.id != self.id &&
            other.universeConfig.subnet == self.universeConfig.subnet &&
            other.universeConfig.universe == self.universeConfig.universe &&
            span(other).let { it.first <= mine.last && it.last >= mine.first }
    }.map { other ->
        "Channels ${self.universeConfig.universe}-${mine.first}…${mine.last} overlap '${other.displayName}' (${other.key}) " +
            "at ${other.universeConfig.universe}-${other.startChannel}"
    }
}

private fun channelCountOf(typeKey: String) = FixtureTypeRegistry.typeInfoForKey(typeKey)?.channelCount ?: 1

/**
 * [raw] as [facet]'s value for the target's patch, checked like the patch write it stands for, or a 400
 * `FACET_INVALID` naming every problem. Inside the write's transaction: a universe and a rigging are
 * resolved in the show's rig.
 */
internal fun parseFacet(facet: PatchFacet, raw: JsonElement, target: ShowOverrides.Target): FacetValue {
    val obj = raw as? JsonObject
        ?: throw invalid(listOf("value must be an object of the ${facet.name.lowercase()} facet's fields, or null"))
    val reader = FieldReader(obj)
    val typeKey = target.patch.fixtureTypeKey
    val value: FacetValue = when (facet) {
        PatchFacet.ADDRESS -> parseAddress(reader, target)
        PatchFacet.POSITION -> parsePosition(reader, target, typeKey)
        PatchFacet.FOCUS -> parseFocus(reader, obj, target, typeKey)
        PatchFacet.MEDIA -> parseMedia(reader, obj, target, typeKey)
    }
    if (reader.problems.isNotEmpty()) throw invalid(reader.problems)
    return value
}

private fun invalid(problems: List<String>) =
    VenueException.badRequest(ShowOverrides.CODE_FACET_INVALID, problems.joinToString("; "))

/** Reads one JSON object's fields, every type problem collected rather than thrown. */
private class FieldReader(val obj: JsonObject, val where: String = "") {
    val problems = mutableListOf<String>()

    fun at(name: String) = if (where.isEmpty()) name else "$where.$name"

    fun allow(keys: Set<String>) {
        (obj.keys - keys).takeIf { it.isNotEmpty() }?.let {
            problems += "${if (where.isEmpty()) "value" else where} has field(s) outside the facet: ${it.sorted().joinToString()}"
        }
    }

    fun double(name: String): Double? {
        val e = obj[name] ?: return null
        if (e is JsonNull) return null
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (v == null || !v.isFinite()) {
            problems += "${at(name)} must be a number"
            return null
        }
        return v
    }

    fun int(name: String): Int? {
        val e = obj[name] ?: return null
        if (e is JsonNull) return null
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (v == null) problems += "${at(name)} must be a whole number"
        return v
    }

    fun string(name: String): String? {
        val e = obj[name] ?: return null
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive
        if (p == null || !p.isString) {
            problems += "${at(name)} must be a string"
            return null
        }
        return p.content.trim().takeIf { it.isNotEmpty() }
    }

    /** The facet's `placements`, each entry read by [read] with its own reader; the problems join this one's. */
    fun <T> placements(read: (FieldReader, Int) -> T?): List<T> {
        val e = obj["placements"] ?: return emptyList()
        if (e is JsonNull) return emptyList()
        val array = e as? JsonArray ?: run { problems += "${at("placements")} must be an array"; return emptyList() }
        return array.mapIndexedNotNull { i, item ->
            val entry = item as? JsonObject ?: run { problems += "${at("placements")}[$i] must be an object"; return@mapIndexedNotNull null }
            val reader = FieldReader(entry, at("placements") + "[$i]")
            read(reader, i).also { problems += reader.problems }
        }
    }
}

/**
 * The placement a facet entry names — a venue placement of the patch by uuid — or null with a problem.
 * [seen] refuses an entry naming one twice.
 */
private fun FieldReader.placementUuid(target: ShowOverrides.Target, seen: MutableSet<String>): String? {
    val raw = string("placementUuid") ?: run { problems += "${at("placementUuid")} is required"; return null }
    val uuid = runCatching { UUID.fromString(raw) }.getOrNull()?.toString()
        ?: run { problems += "${at("placementUuid")} is not a uuid"; return null }
    if (target.placements.none { it.uuid.toString() == uuid }) {
        problems += "${at("placementUuid")} $uuid is not one of '${target.patch.key}''s placements"
        return null
    }
    if (!seen.add(uuid)) {
        problems += "${at("placementUuid")} $uuid appears twice"
        return null
    }
    return uuid
}

private fun parseAddress(reader: FieldReader, target: ShowOverrides.Target): AddressFacet {
    reader.allow(setOf("universeConfigUuid", "universe", "subnet", "startChannel"))
    val rig = target.rig
    val byUuid = reader.string("universeConfigUuid")
    val number = reader.int("universe")
    val subnet = reader.int("subnet") ?: 0
    val start = reader.int("startChannel")
    if (start == null && reader.obj["startChannel"].let { it == null || it is JsonNull }) {
        reader.problems += "startChannel is required"
    }
    val universe = when {
        byUuid != null && number != null -> { reader.problems += "name the universe once: universeConfigUuid or universe, not both"; null }
        byUuid != null -> runCatching { UUID.fromString(byUuid) }.getOrNull()
            ?.let { u -> rig.universeConfigs(DaoUniverseConfigs.uuid eq u).firstOrNull() }
            ?: run { reader.problems += "universeConfigUuid $byUuid is not a universe of this rig"; null }
        number != null -> rig.universeConfigs((DaoUniverseConfigs.subnet eq subnet) and (DaoUniverseConfigs.universe eq number)).firstOrNull()
            ?: run { reader.problems += "universe $number (subnet $subnet) is not a universe of this rig — add it first"; null }
        else -> { reader.problems += "a universe is required: universeConfigUuid, or universe (and subnet)"; null }
    }
    if (start != null) {
        val count = channelCountOf(target.patch.fixtureTypeKey)
        val last = start + count - 1
        if (start < 1) reader.problems += "startChannel must be at least 1"
        else if (last > 512) reader.problems += "'${target.patch.key}' takes $count channels, so from $start it runs to $last (max 512): start at ${512 - count + 1} or below"
    }
    return AddressFacet(universe?.uuid?.toString() ?: "", start ?: 0)
}

private val POSITION_FIELDS = setOf("riggingUuid", "stageX", "stageY", "stageZ", "baseYawDeg", "basePitchDeg", "baseRollDeg", "lengthM")

/** The position fields of one entry — the patch's or a placement's — range-checked, the rigging resolved. */
private class PositionFields(
    val riggingUuid: String?, val stageX: Double?, val stageY: Double?, val stageZ: Double?,
    val baseYawDeg: Double?, val basePitchDeg: Double?, val baseRollDeg: Double?, val lengthM: Double?,
)

private fun FieldReader.positionFields(target: ShowOverrides.Target, typeKey: String): PositionFields {
    val rigging = string("riggingUuid")?.let { raw ->
        resolveRiggingForProject(projectOf(target), raw)?.uuid?.toString()
            ?: run { problems += "${at("riggingUuid")} $raw is not a rigging of this rig"; null }
    }
    val f = PositionFields(
        rigging, double("stageX"), double("stageY"), double("stageZ"),
        double("baseYawDeg"), double("basePitchDeg"), double("baseRollDeg"), double("lengthM"),
    )
    listOfNotNull(
        checkStageCoord(at("stageX"), f.stageX),
        checkStageCoord(at("stageY"), f.stageY),
        checkStageCoord(at("stageZ"), f.stageZ),
        checkAngle(at("baseYawDeg"), f.baseYawDeg, -360.0, 360.0),
        checkAngle(at("basePitchDeg"), f.basePitchDeg, -180.0, 180.0),
        checkAngle(at("baseRollDeg"), f.baseRollDeg, -180.0, 180.0),
        checkMetres(at("lengthM"), f.lengthM, MIN_FIXTURE_LENGTH_M, MAX_FIXTURE_LENGTH_M),
        f.lengthM?.let { fixedLengthRefusal(typeKey)?.let { r -> "${at("lengthM")}: $r" } },
    ).forEach { problems += it }
    return f
}

/** The show the target is read in (a venue patch's own `project` is its version's). */
private fun projectOf(target: ShowOverrides.Target) = DaoProject[target.rig.projectId]

private fun parsePosition(reader: FieldReader, target: ShowOverrides.Target, typeKey: String): PositionFacet {
    reader.allow(POSITION_FIELDS + "placements")
    val p = reader.positionFields(target, typeKey)
    val seen = mutableSetOf<String>()
    val placements = reader.placements { r, _ ->
        r.allow(POSITION_FIELDS + "placementUuid")
        val uuid = r.placementUuid(target, seen)
        val f = r.positionFields(target, typeKey)
        uuid?.let {
            PlacementPosition(it, f.riggingUuid, f.stageX, f.stageY, f.stageZ, f.baseYawDeg, f.basePitchDeg, f.baseRollDeg, f.lengthM)
        }
    }
    return PositionFacet(p.riggingUuid, p.stageX, p.stageY, p.stageZ, p.baseYawDeg, p.basePitchDeg, p.baseRollDeg, p.lengthM, placements)
}

private val FOCUS_FIELDS = LanternFocus.KEYS.toSet()

private fun parseFocus(reader: FieldReader, obj: JsonObject, target: ShowOverrides.Target, typeKey: String): FocusFacet {
    reader.allow(FOCUS_FIELDS + "beamAngleDeg" + "placements")
    val focus = LanternFocus.parse(obj).getOrElse { reader.problems += it.message.orEmpty(); LanternFocus() }
    val beam = reader.int("beamAngleDeg")
    if (beam != null && beam !in 2..120) reader.problems += "beamAngleDeg must be between 2 and 120"
    // The kind the facet leaves the patch with, as a write derives it, and the type's rules against it.
    val patch = target.patch
    val kind = effectiveKind(typeKey, kindUnderLantern(focus.lanternType, patch.lanternType, patch.kindOverride), focus.lanternType)
        ?: FixtureKind.GENERIC
    reader.problems += focus.typeProblems(typeKey, kind)
    val seen = mutableSetOf<String>()
    val placements = reader.placements { r, _ ->
        r.allow(FOCUS_FIELDS + "placementUuid")
        val uuid = r.placementUuid(target, seen)
        val f = LanternFocus.parse(r.obj, r.where).getOrElse { r.problems += it.message.orEmpty(); LanternFocus() }
        r.problems += f.typeProblems(typeKey, kind, inheritedLantern = focus.lanternType).map { "${r.where}: $it" }
        uuid?.let { PlacementFocus(it, f.lanternType, f.zoomDeg, f.lampRotationDeg, f.shutters, f.gateRotationDeg, f.iris, f.focusSoftness) }
    }
    // A placement the value does not name keeps the venue's focus — and one that names no lantern of its
    // own takes the override's, so its venue zoom must still fit (a patch write clears a stale one,
    // `clearStaleInheritedZooms`; a whole-facet override names it instead).
    for (venuePlacement in target.placements) {
        if (venuePlacement.uuid.toString() in seen || venuePlacement.lanternType != null) continue
        venuePlacement.focus.zoomProblem(kind, inheritedLantern = focus.lanternType)?.let { problem ->
            val name = venuePlacement.label?.let { "'$it'" } ?: venuePlacement.uuid.toString()
            reader.problems += "placement $name takes this lantern and keeps the venue's focus: $problem — name it in placements"
        }
    }
    return FocusFacet(
        focus.lanternType, beam, focus.zoomDeg, focus.lampRotationDeg, focus.shutters, focus.gateRotationDeg,
        focus.iris, focus.focusSoftness, placements,
    )
}

private fun parseMedia(reader: FieldReader, obj: JsonObject, target: ShowOverrides.Target, typeKey: String): MediaFacet {
    reader.allow(setOf("gelCode", "media", "placements"))
    val gel = normaliseGelCode(reader.string("gelCode"))
    val media = parseMediaField(reader, obj["media"], "media", typeKey)
    val seen = mutableSetOf<String>()
    val placements = reader.placements { r, _ ->
        r.allow(setOf("placementUuid", "media"))
        val uuid = r.placementUuid(target, seen)
        val m = parseMediaField(r, r.obj["media"], r.at("media"), typeKey)
        uuid?.let { PlacementMedia(it, m) }
    }
    return MediaFacet(gel, media, placements)
}

private fun parseMediaField(reader: FieldReader, element: JsonElement?, where: String, typeKey: String): FittedMedia? {
    val media = FittedMedia.parse(element, where).getOrElse { reader.problems += it.message.orEmpty(); return null }
    media?.typeProblems(typeKey, where)?.let { reader.problems += it }
    return media
}

@Resource("/facets/{facet}")
data class ProjectPatchFacetResource(val parent: ProjectPatchResource, val facet: String)

@Resource("/keep-mine")
data class ProjectPatchFacetKeepResource(val parent: ProjectPatchFacetResource)
