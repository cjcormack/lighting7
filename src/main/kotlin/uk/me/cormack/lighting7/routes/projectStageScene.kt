package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * A seating element still has seat views naming it: deleting it, or reshaping it so a named seat
 * no longer exists, would leave them looking from nowhere. `?force=true` goes ahead and leaves them
 * dangling (`docs/api-conventions.md` §"Guard overrides"); the picker shows a dangling one disabled.
 */
internal const val CODE_STAGE_ELEMENT_IN_USE = "STAGE_ELEMENT_IN_USE"

internal const val STAGE_ELEMENT_NOT_FOUND = "Stage element not found"
internal const val STAGE_VIEWPOINT_NOT_FOUND = "Stage viewpoint not found"

/**
 * The scene document's two tables over REST (stage-view plan session 2, §3.3): `stage-elements` and
 * `stage-viewpoints`, both plain CRUD under the project. Stored data only — nothing here touches
 * the running show — so, like stage regions, the writes are ungated by the current project
 * (`docs/api-conventions.md` §"Project scoping").
 *
 * A `PUT` is partial, as every stage route's is: fields present overwrite, fields absent keep. The
 * merged element is then checked **whole** ([validateStageElement]), because what a params
 * document may say depends on the kind and the size beside it. A 400 lists every problem at once.
 */
internal fun Route.routeApiRestProjectStageScene(state: State) {
    // ─── Elements ───────────────────────────────────────────────────────

    get<ProjectStageElementsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val elements = transaction(state.database) {
                stageElementsOf(project).map { it.toDto() }
            }
            call.respond(elements)
        }
    }

    get<ProjectStageElementResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                DaoStageElement.findById(resource.elementId)?.takeIf { it.project.id == project.id }?.toDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_ELEMENT_NOT_FOUND))
            } else {
                call.respond(dto)
            }
        }
    }

    post<ProjectStageElementsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val body = call.receive<JsonObject>()
            val outcome = transaction(state.database) {
                writeStageElement(project, null, body, force = false)
            }
            respondElementWrite(outcome, HttpStatusCode.Created)
            if (outcome is ElementWrite.Written) state.show.fixtures.stageElementListChanged()
        }
    }

    put<ProjectStageElementResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val outcome = transaction(state.database) {
                val element = DaoStageElement.findById(resource.elementId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction ElementWrite.NotFound
                writeStageElement(project, element, body, resource.force)
            }
            respondElementWrite(outcome, HttpStatusCode.OK)
            if (outcome is ElementWrite.Written) state.show.fixtures.stageElementListChanged()
        }
    }

    delete<ProjectStageElementResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val outcome = transaction(state.database) {
                val element = DaoStageElement.findById(resource.elementId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction ElementWrite.NotFound
                val views = seatViewsOf(project, element.uuid)
                if (views.isNotEmpty() && !resource.force) return@transaction ElementWrite.InUse(views)
                // Its scenery changes go with it, as a group's busk-rig tiles go with the group.
                deleteSceneryForElements(listOf(element.id))
                element.delete()
                ElementWrite.Deleted
            }
            when (outcome) {
                ElementWrite.Deleted -> {
                    state.show.fixtures.stageElementListChanged()
                    call.respond(HttpStatusCode.NoContent)
                }
                else -> respondElementWrite(outcome, HttpStatusCode.NoContent)
            }
        }
    }

    // ─── Viewpoints ─────────────────────────────────────────────────────

    get<ProjectStageViewpointsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val viewpoints = transaction(state.database) {
                stageViewpointsOf(project).map { it.toDto() }
            }
            call.respond(viewpoints)
        }
    }

    get<ProjectStageViewpointResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                DaoStageViewpoint.findById(resource.viewpointId)?.takeIf { it.project.id == project.id }?.toDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_VIEWPOINT_NOT_FOUND))
            } else {
                call.respond(dto)
            }
        }
    }

    post<ProjectStageViewpointsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val body = call.receive<JsonObject>()
            val outcome = transaction(state.database) { writeStageViewpoint(project, null, body) }
            respondViewpointWrite(outcome, HttpStatusCode.Created)
            if (outcome is ViewpointWrite.Written) state.show.fixtures.stageViewpointListChanged()
        }
    }

    put<ProjectStageViewpointResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val outcome = transaction(state.database) {
                val viewpoint = DaoStageViewpoint.findById(resource.viewpointId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction ViewpointWrite.NotFound
                writeStageViewpoint(project, viewpoint, body)
            }
            respondViewpointWrite(outcome, HttpStatusCode.OK)
            if (outcome is ViewpointWrite.Written) state.show.fixtures.stageViewpointListChanged()
        }
    }

    delete<ProjectStageViewpointResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val viewpoint = DaoStageViewpoint.findById(resource.viewpointId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction false
                viewpoint.delete()
                true
            }
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_VIEWPOINT_NOT_FOUND))
                return@withProject
            }
            state.show.fixtures.stageViewpointListChanged()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// ─── Writes ─────────────────────────────────────────────────────────────

private sealed interface ElementWrite {
    data class Written(val dto: StageElementDto) : ElementWrite
    data class Invalid(val problems: List<String>) : ElementWrite
    data class NameTaken(val name: String) : ElementWrite
    data class InUse(val views: List<String>) : ElementWrite
    data object NotFound : ElementWrite
    data object Deleted : ElementWrite
}

private suspend fun io.ktor.server.routing.RoutingContext.respondElementWrite(outcome: ElementWrite, ok: HttpStatusCode) {
    when (outcome) {
        is ElementWrite.Written -> call.respond(ok, outcome.dto)
        is ElementWrite.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.problems.joinToString("; ")))
        is ElementWrite.NameTaken -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Stage element '${outcome.name}' already exists"))
        is ElementWrite.InUse -> call.respond(
            HttpStatusCode.Conflict,
            ErrorResponse(
                "Seat views still look from this seating: ${outcome.views.joinToString { "'$it'" }}. " +
                    "Send ?force=true to go ahead and leave them without a seat.",
                code = CODE_STAGE_ELEMENT_IN_USE,
            ),
        )
        ElementWrite.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_ELEMENT_NOT_FOUND))
        ElementWrite.Deleted -> call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Reads a partial body into typed fields, collecting a problem for each field present with the
 * wrong type rather than throwing on the first. An explicit `null` reads as absent-with-a-value
 * (the caller asks [has] first where null means "clear").
 */
private class BodyReader(private val body: JsonObject, val problems: MutableList<String>) {
    fun has(name: String) = name in body

    private fun primitive(name: String): JsonPrimitive? {
        val e: JsonElement = body[name] ?: return null
        if (e is JsonNull) return null
        return e as? JsonPrimitive ?: run { problems += "$name has the wrong type"; null }
    }

    fun double(name: String): Double? = primitive(name)?.let { p ->
        p.takeIf { !it.isString }?.doubleOrNull ?: run { problems += "$name must be a number"; null }
    }

    fun int(name: String): Int? = primitive(name)?.let { p ->
        p.takeIf { !it.isString }?.intOrNull ?: run { problems += "$name must be a whole number"; null }
    }

    fun bool(name: String): Boolean? = primitive(name)?.let { p ->
        p.takeIf { !it.isString }?.booleanOrNull ?: run { problems += "$name must be true or false"; null }
    }

    fun string(name: String): String? = primitive(name)?.let { p ->
        p.takeIf { it.isString }?.contentOrNull ?: run { problems += "$name must be a string"; null }
    }

    fun obj(name: String): JsonObject? {
        val e = body[name] ?: return null
        if (e is JsonNull) return null
        return e as? JsonObject ?: run { problems += "$name must be an object"; null }
    }
}

/** A stored element as the fields a write starts from. */
internal fun DaoStageElement.toFields(): StageElementFields = StageElementFields(
    name = name,
    kind = enumOrNull<StageElementKind>(kind) ?: StageElementKind.OBJECT,
    layer = enumOrNull<StageElementLayer>(layer) ?: StageElementLayer.VENUE,
    positionX = positionX,
    positionY = positionY,
    positionZ = positionZ,
    yawDeg = yawDeg,
    widthM = widthM,
    depthM = depthM,
    heightM = heightM,
    finishColour = finishColour,
    finishPattern = enumOrNull<SurfacePattern>(finishPattern),
    emissive = emissive,
    params = storedParamsObject(params),
    hidden = hidden,
)

/** A new element's fields before the write lays its own over them. */
internal fun blankElementFields(name: String, kind: StageElementKind) = StageElementFields(
    name = name,
    kind = kind,
    layer = StageElementLayer.VENUE,
    positionX = 0.0, positionY = 0.0, positionZ = 0.0, yawDeg = 0.0,
    widthM = 0.0, depthM = 0.0, heightM = 0.0,
    finishColour = null, finishPattern = null, emissive = false,
    params = JsonObject(emptyMap()),
    hidden = false,
)

/** Store validated [fields] and their parsed [params] on [element]. */
internal fun DaoStageElement.store(fields: StageElementFields, params: ElementParams) {
    name = fields.name
    kind = fields.kind.name
    layer = fields.layer.name
    positionX = fields.positionX
    positionY = fields.positionY
    positionZ = fields.positionZ
    yawDeg = fields.yawDeg
    widthM = fields.widthM
    depthM = fields.depthM
    heightM = fields.heightM
    finishColour = fields.finishColour
    finishPattern = fields.finishPattern?.name
    emissive = fields.emissive
    this.params = encodeElementParams(fields.kind, params)
    hidden = fields.hidden
}

internal fun stageElementsOf(project: DaoProject): List<DaoStageElement> =
    DaoStageElement.find { DaoStageElements.project eq project.id }
        .orderBy(DaoStageElements.sortOrder to SortOrder.ASC, DaoStageElements.name to SortOrder.ASC)
        .toList()

internal fun stageViewpointsOf(project: DaoProject): List<DaoStageViewpoint> =
    DaoStageViewpoint.find { DaoStageViewpoints.project eq project.id }
        .orderBy(DaoStageViewpoints.sortOrder to SortOrder.ASC, DaoStageViewpoints.name to SortOrder.ASC)
        .toList()

internal fun regionUuidsOf(project: DaoProject): Set<String> =
    DaoStageRegion.find { DaoStageRegions.project eq project.id }.map { it.uuid.toString() }.toSet()

/** Names of the seat views that name [elementUuid] as their seating. */
internal fun seatViewsOf(project: DaoProject, elementUuid: UUID): List<String> =
    DaoStageViewpoint.find {
        (DaoStageViewpoints.project eq project.id) and (DaoStageViewpoints.seatElementUuid eq elementUuid)
    }.map { it.name }

/** [fields] as a seating element, or null when the element is not one (or its params are unreadable). */
internal fun seatingOf(fields: StageElementFields, params: ElementParams?): SeatingElement? {
    val seating = params as? SeatingParams ?: return null
    if (fields.kind != StageElementKind.SEATING) return null
    return SeatingElement(fields.name, ElementPose(fields.positionX, fields.positionY, fields.positionZ, fields.yawDeg), seating)
}

/** Every seating element of [project], by uuid, as stored. */
internal fun seatingElementsOf(project: DaoProject): Map<UUID, SeatingElement> =
    DaoStageElement.find { DaoStageElements.project eq project.id }.mapNotNull { e ->
        val fields = e.toFields()
        val params = if (fields.kind == StageElementKind.SEATING) readElementParams(fields.kind, e.params) else null
        seatingOf(fields, params)?.let { e.uuid to it }
    }.toMap()

/** Whether [seating] has the seat [seatId] (false for no seating, or no id). */
internal fun seatResolves(seating: SeatingElement?, seatId: String?): Boolean =
    seating != null && seatId != null && seating.params.seat(seating.pose, seatId) != null

/**
 * The seat views that [seating] (null: the element is no longer seating) would leave without their
 * seat, of those naming [elementUuid] — only the ones this write **newly** breaks. A view already
 * dangling under [before] (a forced reshape left it) is not this write's doing, and counting it
 * would refuse every later edit of the seating, a rename included, until it was forced too.
 */
internal fun seatViewsBrokenBy(
    project: DaoProject,
    elementUuid: UUID,
    before: SeatingElement?,
    seating: SeatingElement?,
): List<String> =
    DaoStageViewpoint.find {
        (DaoStageViewpoints.project eq project.id) and (DaoStageViewpoints.seatElementUuid eq elementUuid)
    }.filter { view -> seatResolves(before, view.seatId) && !seatResolves(seating, view.seatId) }
        .map { it.name }

/**
 * Take a deleted region's link off every platform that named it (D5: a platform may be a region's
 * deck). The link would dangle harmlessly — a reader treats it as none — but it would also travel
 * in every export naming a record that is gone. Answers how many platforms were unlinked, so the
 * caller fires `stageElementListChanged` only when something moved.
 */
internal fun unlinkRegionFromPlatforms(project: DaoProject, regionUuid: UUID): Int {
    var unlinked = 0
    for (element in DaoStageElement.find {
        (DaoStageElements.project eq project.id) and (DaoStageElements.kind eq StageElementKind.PLATFORM.name)
    }) {
        val params = readElementParams(StageElementKind.PLATFORM, element.params) as? PlatformParams ?: continue
        if (params.regionUuid != regionUuid.toString()) continue
        element.params = encodeElementParams(StageElementKind.PLATFORM, params.copy(regionUuid = null))
        unlinked++
    }
    return unlinked
}

private fun writeStageElement(
    project: DaoProject,
    element: DaoStageElement?,
    body: JsonObject,
    force: Boolean,
): ElementWrite {
    val problems = mutableListOf<String>()
    val r = BodyReader(body, problems)
    val name = r.string("name")?.trim()
    val kindRaw = r.string("kind")
    val kind = kindRaw?.let { enumOrNull<StageElementKind>(it) ?: run { problems += "kind must be one of ${enumNames<StageElementKind>()}"; null } }
    if (element == null) {
        if (name == null && "name" !in body) problems += "name is required"
        if (kindRaw == null) problems += "kind is required (one of ${enumNames<StageElementKind>()})"
    }
    val layer = r.string("layer")?.let { enumOrNull<StageElementLayer>(it) ?: run { problems += "layer must be one of ${enumNames<StageElementLayer>()}"; null } }
    val pattern = r.string("finishPattern")?.let { enumOrNull<SurfacePattern>(it) ?: run { problems += "finishPattern must be one of ${enumNames<SurfacePattern>()}"; null } }
    val colour = normaliseFinishColour(r.string("finishColour"), "finishColour", problems)
    val numbers = listOf("positionX", "positionY", "positionZ", "yawDeg", "widthM", "depthM", "heightM").associateWith { r.double(it) }
    val emissive = r.bool("emissive")
    val hidden = r.bool("hidden")
    val params = r.obj("params")
    val sortOrder = r.int("sortOrder")
    if (problems.isNotEmpty()) return ElementWrite.Invalid(problems)

    val start = element?.toFields() ?: blankElementFields(name.orEmpty(), kind!!)
    fun num(key: String, current: Double) = if (r.has(key)) numbers[key] ?: 0.0 else current
    val fields = start.copy(
        name = name ?: start.name,
        kind = kind ?: start.kind,
        layer = layer ?: if (r.has("layer")) StageElementLayer.VENUE else start.layer,
        positionX = num("positionX", start.positionX),
        positionY = num("positionY", start.positionY),
        positionZ = num("positionZ", start.positionZ),
        yawDeg = num("yawDeg", start.yawDeg),
        widthM = num("widthM", start.widthM),
        depthM = num("depthM", start.depthM),
        heightM = num("heightM", start.heightM),
        finishColour = if (r.has("finishColour")) colour else start.finishColour,
        finishPattern = if (r.has("finishPattern")) pattern else start.finishPattern,
        emissive = emissive ?: if (r.has("emissive")) false else start.emissive,
        params = if (r.has("params")) params ?: JsonObject(emptyMap()) else start.params,
        hidden = hidden ?: if (r.has("hidden")) false else start.hidden,
    )
    val stored = element?.let { readElementParams(start.kind, it.params) }
    val parsed = validateStageElement(
        fields, regionUuidsOf(project), "", problems,
        storedRegionUuid = (stored as? PlatformParams)?.regionUuid,
        positionNames = listOf("positionX", "positionY", "positionZ"),
    )
    if (parsed == null) return ElementWrite.Invalid(problems)

    val collision = DaoStageElement.find {
        (DaoStageElements.project eq project.id) and (DaoStageElements.name eq fields.name)
    }.firstOrNull()
    if (collision != null && collision.id != element?.id) return ElementWrite.NameTaken(fields.name)

    if (element != null && !force) {
        val broken = seatViewsBrokenBy(project, element.uuid, seatingOf(start, stored), seatingOf(fields, parsed))
        if (broken.isNotEmpty()) return ElementWrite.InUse(broken)
    }

    val target = element ?: DaoStageElement.new {
        this.project = project
        this.name = fields.name
        this.kind = fields.kind.name
        this.layer = fields.layer.name
        this.sortOrder = (stageElementsOf(project).maxOfOrNull { it.sortOrder } ?: -1) + 1
    }
    target.store(fields, parsed)
    sortOrder?.let { target.sortOrder = it }
    return ElementWrite.Written(target.toDto())
}

private sealed interface ViewpointWrite {
    data class Written(val dto: StageViewpointDto) : ViewpointWrite
    data class Invalid(val problems: List<String>) : ViewpointWrite
    data class NameTaken(val name: String) : ViewpointWrite
    data object NotFound : ViewpointWrite
}

private suspend fun io.ktor.server.routing.RoutingContext.respondViewpointWrite(outcome: ViewpointWrite, ok: HttpStatusCode) {
    when (outcome) {
        is ViewpointWrite.Written -> call.respond(ok, outcome.dto)
        is ViewpointWrite.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.problems.joinToString("; ")))
        is ViewpointWrite.NameTaken -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Stage viewpoint '${outcome.name}' already exists"))
        ViewpointWrite.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_VIEWPOINT_NOT_FOUND))
    }
}

internal fun DaoStageViewpoint.toFields(): StageViewpointFields = StageViewpointFields(
    name = name,
    kind = enumOrNull<StageViewpointKind>(kind) ?: StageViewpointKind.ORBIT,
    eye = pointOrNull(eyeX, eyeY, eyeZ),
    target = pointOrNull(targetX, targetY, targetZ),
    fovDeg = fovDeg,
    seatElementUuid = seatElementUuid,
    seatId = seatId,
)

internal fun DaoStageViewpoint.store(fields: StageViewpointFields) {
    name = fields.name
    kind = fields.kind.name
    eyeX = fields.eye?.x
    eyeY = fields.eye?.y
    eyeZ = fields.eye?.z
    targetX = fields.target?.x
    targetY = fields.target?.y
    targetZ = fields.target?.z
    fovDeg = fields.fovDeg
    seatElementUuid = fields.seatElementUuid
    seatId = fields.seatId?.trim()?.uppercase()
}

private fun pointOrNull(x: Double?, y: Double?, z: Double?): StagePoint? =
    if (x != null && y != null && z != null) StagePoint(x, y, z) else null

private fun writeStageViewpoint(project: DaoProject, viewpoint: DaoStageViewpoint?, body: JsonObject): ViewpointWrite {
    val problems = mutableListOf<String>()
    val r = BodyReader(body, problems)
    val name = r.string("name")?.trim()
    val kindRaw = r.string("kind")
    val kind = kindRaw?.let { enumOrNull<StageViewpointKind>(it) ?: run { problems += "kind must be one of ${enumNames<StageViewpointKind>()}"; null } }
    if (viewpoint == null) {
        if (name == null) problems += "name is required"
        if (kindRaw == null) problems += "kind is required (one of ${enumNames<StageViewpointKind>()})"
    }
    fun point(prefix: String, current: StagePoint?): StagePoint? {
        val keys = listOf("${prefix}X", "${prefix}Y", "${prefix}Z")
        val sent = keys.filter { r.has(it) }
        if (sent.isEmpty()) return current
        val values = keys.map { r.double(it) }
        if (values.all { it == null }) return null
        if (values.any { it == null }) {
            problems += "${prefix}X, ${prefix}Y and ${prefix}Z go together — send all three, or all null"
            return current
        }
        return StagePoint(values[0]!!, values[1]!!, values[2]!!)
    }
    val start = viewpoint?.toFields()
    // A kind change forgets what only the old kind carried, unless the body restates it — as
    // `set_scene` does: a seat view turned into an eye view drops its seat, an eye view turned into
    // a seat view drops its eye, and an orbit view has no lens.
    val resolvedKind = kind ?: start?.kind
    val eye = point("eye", start?.eye.takeIf { resolvedKind != StageViewpointKind.SEAT })
    val target = point("target", start?.target)
    val fov = if (r.has("fovDeg")) r.double("fovDeg") else start?.fovDeg.takeIf { resolvedKind != StageViewpointKind.ORBIT }
    val keepSeat = resolvedKind == StageViewpointKind.SEAT
    val seatElement = if (r.has("seatElementUuid")) {
        r.string("seatElementUuid")?.let { raw ->
            runCatching { UUID.fromString(raw.trim()) }.getOrNull() ?: run { problems += "seatElementUuid must be a uuid"; null }
        }
    } else {
        start?.seatElementUuid.takeIf { keepSeat }
    }
    val seatId = if (r.has("seatId")) r.string("seatId")?.trim()?.takeIf { it.isNotEmpty() } else start?.seatId.takeIf { keepSeat }
    val sortOrder = r.int("sortOrder")
    if (problems.isNotEmpty()) return ViewpointWrite.Invalid(problems)

    val fields = StageViewpointFields(
        name = name ?: start!!.name,
        kind = kind ?: start!!.kind,
        eye = eye,
        target = target,
        fovDeg = fov,
        seatElementUuid = seatElement,
        seatId = seatId,
    )
    val seating = seatingElementsOf(project)
    // A seat view whose seating was force-deleted still takes a rename or a new lens: the seat it
    // already names is let stand while the write leaves it as it is.
    val storedSeat = start?.takeIf { it.kind == StageViewpointKind.SEAT }?.let { s ->
        val uuid = s.seatElementUuid
        val id = s.seatId
        if (uuid != null && id != null) uuid to id else null
    }
    validateStageViewpoint(fields, { seating[it] }, "", problems, storedSeat)
    if (problems.isNotEmpty()) return ViewpointWrite.Invalid(problems)

    val collision = DaoStageViewpoint.find {
        (DaoStageViewpoints.project eq project.id) and (DaoStageViewpoints.name eq fields.name)
    }.firstOrNull()
    if (collision != null && collision.id != viewpoint?.id) return ViewpointWrite.NameTaken(fields.name)

    val row = viewpoint ?: DaoStageViewpoint.new {
        this.project = project
        this.name = fields.name
        this.kind = fields.kind.name
        this.sortOrder = (stageViewpointsOf(project).maxOfOrNull { it.sortOrder } ?: -1) + 1
    }
    row.store(fields)
    sortOrder?.let { row.sortOrder = it }
    return ViewpointWrite.Written(row.toDto())
}

// ─── Resources and DTOs ─────────────────────────────────────────────────

@Resource("/{projectId}/stage-elements")
data class ProjectStageElementsResource(val projectId: String)

@Resource("/{elementId}")
data class ProjectStageElementResource(
    val parent: ProjectStageElementsResource,
    val elementId: Int,
    val force: Boolean = false,
)

@Resource("/{projectId}/stage-viewpoints")
data class ProjectStageViewpointsResource(val projectId: String)

@Resource("/{viewpointId}")
data class ProjectStageViewpointResource(val parent: ProjectStageViewpointsResource, val viewpointId: Int)

/**
 * One scene element on the wire. `params` is the kind's document as stored — canonical, keys
 * sorted, defaults omitted (see [ElementParams] for each kind's fields). Enumerations are their
 * upper-case names.
 */
@Serializable
data class StageElementDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val kind: String,
    val layer: String,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val yawDeg: Double,
    val widthM: Double,
    val depthM: Double,
    val heightM: Double,
    val finishColour: String? = null,
    val finishPattern: String? = null,
    val emissive: Boolean,
    val params: JsonObject,
    val hidden: Boolean,
    val sortOrder: Int,
)

@Serializable
data class StageViewpointDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val kind: String,
    val eyeX: Double? = null,
    val eyeY: Double? = null,
    val eyeZ: Double? = null,
    val targetX: Double? = null,
    val targetY: Double? = null,
    val targetZ: Double? = null,
    val fovDeg: Double? = null,
    val seatElementUuid: String? = null,
    val seatId: String? = null,
    val sortOrder: Int,
)

internal fun DaoStageElement.toDto() = StageElementDto(
    id = id.value,
    uuid = uuid.toString(),
    name = name,
    kind = kind,
    layer = layer,
    positionX = positionX,
    positionY = positionY,
    positionZ = positionZ,
    yawDeg = yawDeg,
    widthM = widthM,
    depthM = depthM,
    heightM = heightM,
    finishColour = finishColour,
    finishPattern = finishPattern,
    emissive = emissive,
    params = storedParamsObject(params),
    hidden = hidden,
    sortOrder = sortOrder,
)

internal fun DaoStageViewpoint.toDto() = StageViewpointDto(
    id = id.value,
    uuid = uuid.toString(),
    name = name,
    kind = kind,
    eyeX = eyeX,
    eyeY = eyeY,
    eyeZ = eyeZ,
    targetX = targetX,
    targetY = targetY,
    targetZ = targetZ,
    fovDeg = fovDeg,
    seatElementUuid = seatElementUuid?.toString(),
    seatId = seatId,
    sortOrder = sortOrder,
)
