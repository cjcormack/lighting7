package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.LayerSource
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoTemplateRows
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.state.State

/** Error code the client keys the "this template is still applied somewhere" flow off. */
internal const val CODE_TEMPLATE_IN_USE = "TEMPLATE_IN_USE"

private const val TEMPLATE_NOT_FOUND = "Template not found"

/**
 * The template library: CRUD, the two apply gestures, and the editor's resolves-to panel.
 *
 * Modelled on [routeApiRestProjectLooks] and deliberately *not* merged with it. The two entities
 * differ in what they are for (a template composes values, a Look composes cues), in what they hold
 * (one family of intents, versus any families of literals plus effects plus a positional colour
 * list) and in how they are applied (to a selection, versus as a layer only). Sharing a route file
 * would mean a `when` on kind in every handler.
 *
 * Three of these routes have no Look counterpart, and each exists for a stated reason:
 *
 *  - **`/resolve`** takes a *draft* rather than a saved id, because the editor's panel has to answer
 *    "what will this do to my rig?" before anything is written. It is the only reason the resolver
 *    is reachable without a template row.
 *  - **`/apply`** writes **literals** into the programmer. That is the plain-click gesture, and the
 *    literals are the point: retuning the template later must not move what you busked.
 *  - **`/from-programmer`** records a selection as a new template, collapsing to one generic row per
 *    property where every target agrees and keeping per-fixture rows where they do not.
 */
internal fun Route.routeApiRestProjectTemplates(state: State) {
    // GET /project/{id}/templates?family=COLOUR
    get<ProjectTemplatesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val familyFilter = resource.family?.trim()?.takeIf { it.isNotEmpty() }
            val parsedFamily = familyFilter?.let { raw ->
                PropertyMaskGroup.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            if (familyFilter != null && parsedFamily == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unknown family '$familyFilter' — expected one of " +
                            PropertyMaskGroup.entries.joinToString { it.name },
                    ),
                )
                return@withProject
            }
            val templates = transaction(state.database) {
                val all = DaoTemplate.find { DaoTemplates.project eq project.id }
                    .orderBy(DaoTemplates.sortOrder to SortOrder.ASC, DaoTemplates.name to SortOrder.ASC)
                    .toList()
                val usage = templateUsageFor(all.map { it.id.value })
                all.map { it.toDto(usage[it.id.value]) }
            }
            // Filtered after the DTOs are built, because the family is derived from the rows and
            // there is no column to query on — the same reason `/looks` filters in memory.
            call.respond(
                if (parsedFamily == null) templates
                else templates.filter { it.family == parsedFamily.name },
            )
        }
    }

    // GET /project/{id}/templates/{templateId}
    get<ProjectTemplateResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val template = DaoTemplate.findById(resource.templateId) ?: return@transaction null
                if (template.project.id != project.id) return@transaction null
                template.toDto(templateUsage(template.id.value))
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
            } else {
                call.respond(dto)
            }
        }
    }

    // POST /project/{id}/templates
    post<ProjectTemplatesResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<TemplateInput>()
            val name = request.name?.trim().orEmpty()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Template name must not be blank"))
                return@withCurrentProject
            }
            val rows = request.rows ?: emptyList()
            validateTemplateRows(rows)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            val result = transaction(state.database) {
                val duplicate = DaoTemplate.find {
                    (DaoTemplates.project eq project.id) and (DaoTemplates.name eq name)
                }.firstOrNull()
                if (duplicate != null) return@transaction null
                val template = DaoTemplate.new {
                    this.project = project
                    this.name = name
                    this.notes = request.notes
                    this.sortOrder = request.sortOrder
                        ?: ((DaoTemplate.find { DaoTemplates.project eq project.id }
                            .maxOfOrNull { it.sortOrder } ?: -1) + 1)
                    this.fadeDurationMs = request.fadeDurationMs
                }
                createTemplateRows(template, rows)
                template.toDto(templateUsage(template.id.value))
            }
            if (result == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A template named '$name' already exists in this project"),
                )
                return@withCurrentProject
            }
            state.show.fixtures.templateListChanged()
            call.respond(HttpStatusCode.Created, result)
        }
    }

    // PUT /project/{id}/templates/{templateId}
    //
    // Unlike the Look PUT this takes a typed body rather than a raw `JsonObject`. The
    // absent-versus-empty distinction that forced the raw decode there does not arise: a template
    // has one child collection, and a template with no rows is not a thing you can save — so
    // "rows omitted" and "rows empty" both mean "leave the rows alone", which a nullable field says
    // exactly.
    put<ProjectTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<TemplateInput>()
            request.rows?.let { rows ->
                validateTemplateRows(rows)?.let { problem ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                    return@withCurrentProject
                }
            }
            val outcome = transaction(state.database) {
                val template = DaoTemplate.findById(resource.templateId)
                    ?: return@transaction TemplateWriteOutcome.NotFound
                if (template.project.id != project.id) return@transaction TemplateWriteOutcome.NotFound

                request.name?.trim()?.takeIf { it.isNotEmpty() }?.let { newName ->
                    if (newName != template.name) {
                        val clash = DaoTemplate.find {
                            (DaoTemplates.project eq project.id) and (DaoTemplates.name eq newName)
                        }.firstOrNull()
                        if (clash != null) return@transaction TemplateWriteOutcome.NameTaken(newName)
                        template.name = newName
                    }
                }
                if (request.notesPresent) template.notes = request.notes
                request.sortOrder?.let { template.sortOrder = it }
                if (request.fadeDurationMsPresent) template.fadeDurationMs = request.fadeDurationMs

                val rows = request.rows
                if (rows != null) {
                    template.rows.forEach { it.delete() }
                    createTemplateRows(template, rows)
                }
                TemplateWriteOutcome.Written(
                    template.toDto(templateUsage(template.id.value)),
                    template.uuid,
                    contentsChanged = rows != null,
                )
            }
            when (outcome) {
                is TemplateWriteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                is TemplateWriteOutcome.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A template named '${outcome.name}' already exists in this project"),
                )
                is TemplateWriteOutcome.Written -> {
                    if (outcome.contentsChanged) {
                        // A contents change republishes the live consumers directly — one retune
                        // moving every cue that layers this template, with no cue re-fired. That is
                        // the feature. `templateListChanged` would only drop every cached snapshot.
                        republishForTemplateEdit(state, outcome.uuid)
                    } else {
                        state.show.fixtures.templateListChanged()
                    }
                    call.respond(outcome.dto)
                }
            }
        }
    }

    // DELETE /project/{id}/templates/{templateId}?force=true
    delete<ProjectTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val outcome = transaction(state.database) {
                val template = DaoTemplate.findById(resource.templateId)
                    ?: return@transaction TemplateDeleteOutcome.NotFound
                if (template.project.id != project.id) return@transaction TemplateDeleteOutcome.NotFound
                val usage = templateUsage(template.id.value)
                if (usage.layerCount > 0 && !resource.force) {
                    return@transaction TemplateDeleteOutcome.InUse(usage)
                }
                // No DB-level ON DELETE CASCADE — SQLite does not enforce cascades without a
                // per-connection pragma, so children go first, explicitly.
                if (resource.force) {
                    DaoCueLayer.find { DaoCueLayers.template eq template.id }.forEach { it.delete() }
                }
                template.rows.forEach { it.delete() }
                template.delete()
                TemplateDeleteOutcome.Deleted
            }
            when (outcome) {
                is TemplateDeleteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                is TemplateDeleteOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    TemplateInUseResponse(
                        error = "This template is still applied by ${outcome.usage.describe()}",
                        code = CODE_TEMPLATE_IN_USE,
                        layerCount = outcome.usage.layerCount,
                        cueIds = outcome.usage.cueIds,
                        cueNames = outcome.usage.cueNames,
                    ),
                )
                is TemplateDeleteOutcome.Deleted -> {
                    state.show.fixtures.templateListChanged()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    // POST /project/{id}/templates/resolve — the editor's "resolves to" panel.
    post<TemplateResolveResource> { resource ->
        withProject(state, resource.parent.projectId) { _ ->
            val request = call.receive<TemplateResolveRequest>()
            validateTemplateRows(request.rows)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withProject
            }
            call.respond(resolveTemplateAgainstPatch(state, request))
        }
    }

    // POST /project/{id}/templates/{templateId}/apply — click: literals into the programmer.
    post<ApplyTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ApplyTemplateRequest>()
            val snapshot = transaction(state.database) {
                DaoTemplate.findById(resource.templateId)
                    ?.takeIf { it.project.id == project.id }
                    ?.uuid
            }?.let { state.show.templateRegistry.snapshot(it) }
            if (snapshot == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                return@withCurrentProject
            }
            val outcome = applyTemplateToProgrammer(
                state,
                snapshot,
                request.targets.map { CueTargetDto(it.type, it.key) },
                request.fadeMs ?: snapshot.fadeDurationMs ?: 0,
            )
            call.respond(outcome)
        }
    }

    // POST /project/{id}/templates/{templateId}/toggle — ⌥click and the busking pads.
    post<ToggleTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ToggleTemplateRequest>()
            val source = transaction(state.database) {
                DaoTemplate.findById(resource.templateId)
                    ?.takeIf { it.project.id == project.id }
                    ?.let { LayerSource.template(it.id.value, it.uuid, it.name) }
            }
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                return@withCurrentProject
            }
            try {
                val (action, effectCount) = state.show.programmerLayerStack.toggle(
                    source = source,
                    targets = request.targets.map { CueTargetDto(it.type, it.key) },
                )
                call.respond(ToggleTemplateResponse(action, effectCount, request.propertyMask))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
            }
        }
    }
}

// ─── Resources ──────────────────────────────────────────────────────────

@Resource("/{projectId}/templates")
internal data class ProjectTemplatesResource(val projectId: String, val family: String? = null)

@Resource("/{templateId}")
internal data class ProjectTemplateResource(
    val parent: ProjectTemplatesResource,
    val templateId: Int,
    val force: Boolean = false,
)

/**
 * Unambiguous against [ProjectTemplateResource] despite sitting at the same depth, on the same
 * reasoning `LookPreviewResource` records: nothing serves `POST /{templateId}`, and `templateId` is
 * an `Int` that `"resolve"` cannot parse as.
 */
@Resource("/resolve")
internal data class TemplateResolveResource(val parent: ProjectTemplatesResource)

@Resource("/{templateId}/apply")
internal data class ApplyTemplateResource(val parent: ProjectTemplatesResource, val templateId: Int)

@Resource("/{templateId}/toggle")
internal data class ToggleTemplateResource(val parent: ProjectTemplatesResource, val templateId: Int)

// ─── DTOs ───────────────────────────────────────────────────────────────

/**
 * A template as the library lists it — **rows included**, unlike `LookDto`.
 *
 * A Look needs a summary/detail split because its derived counts would go stale beside its contents.
 * A template has no effects, no palette and few rows (one for a generic template, one per head for a
 * focus position), so one shape serves the list and the editor — and the library row can preview the
 * actual value without a second fetch, which is what `LookLibrary`'s row does with `preview`.
 */
@Serializable
internal data class TemplateDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val sortOrder: Int,
    val fadeDurationMs: Long? = null,
    /**
     * The one family this template is in, **derived** from its rows — never stored. Null only for a
     * template whose rows have all been deleted, which the write boundary does not allow but a
     * hand-edited database could produce.
     */
    val family: String? = null,
    /**
     * True when every row takes its targets from whatever applies the template (the *Generic* case);
     * false when the rows name their own heads (the *Per fixture* case, a focus position).
     */
    val isGeneric: Boolean,
    val rows: List<TemplateRowDto> = emptyList(),
    /** How many layers apply this template. Gates delete. */
    val layerCount: Int,
)

/**
 * Create / update payload.
 *
 * `notes` and `fadeDurationMs` carry an explicit presence flag rather than relying on null, because
 * for both of them null is a *value* an operator can set (clear the notes, use the caller's default
 * fade) and PUT has to be able to tell that from "leave it alone".
 */
@Serializable
internal data class TemplateInput(
    val name: String? = null,
    val notes: String? = null,
    val notesPresent: Boolean = false,
    val sortOrder: Int? = null,
    val fadeDurationMs: Long? = null,
    val fadeDurationMsPresent: Boolean = false,
    val rows: List<TemplateRowDto>? = null,
)

@Serializable
internal data class TemplateInUseResponse(
    val error: String,
    val code: String,
    val layerCount: Int,
    val cueIds: List<Int>,
    val cueNames: List<String>,
)

@Serializable
internal data class TemplateTargetDto(val type: String, val key: String)

@Serializable
internal data class ApplyTemplateRequest(
    val targets: List<TemplateTargetDto> = emptyList(),
    val fadeMs: Long? = null,
)

@Serializable
internal data class ApplyTemplateResponse(
    /** Programmer entries written. */
    val written: Int,
    /** Heads the template could not reach, with the reason — the same notes the panel shows. */
    val skipped: List<TemplateSkipDto> = emptyList(),
)

@Serializable
internal data class TemplateSkipDto(
    val fixtureKey: String,
    val propertyName: String,
    val reason: String,
)

@Serializable
internal data class ToggleTemplateRequest(
    val targets: List<TemplateTargetDto> = emptyList(),
    /**
     * Echoed back rather than applied here. The mask a template layer wants is its own family, which
     * the server derives — but the caller states what it *believed* it was masking to, so a client
     * and server that disagree show up in the response instead of silently on the rig.
     */
    val propertyMask: String? = null,
)

@Serializable
internal data class ToggleTemplateResponse(
    val action: String,
    val effectCount: Int,
    val propertyMask: String? = null,
)

/** The editor's live panel: a draft, and optionally which heads to answer for. */
@Serializable
internal data class TemplateResolveRequest(
    val rows: List<TemplateRowDto> = emptyList(),
    /** Empty means "every patched fixture" — what the editor wants while nothing is selected. */
    val targets: List<TemplateTargetDto> = emptyList(),
)

@Serializable
internal data class TemplateResolveResponse(
    val entries: List<TemplateResolutionDto> = emptyList(),
)

/** One head's answer, in the shape the panel renders a row from. */
@Serializable
internal data class TemplateResolutionDto(
    val fixtureKey: String,
    val fixtureName: String,
    val typeKey: String,
    val propertyName: String,
    /** The property the value actually landed on — differs from [propertyName] on a colour wheel. */
    val resolvedPropertyName: String,
    /** `EXACT` | `CLAMPED` | `SNAPPED` | `DEGRADED` | `UNSUPPORTED`. */
    val outcome: String,
    /** Operator-facing detail: the clamp, the slot name, or the reason. Null for an exact resolve. */
    val detail: String? = null,
    /** ΔE against the requested colour, on a wheel snap only. */
    val deltaE: Double? = null,
    /** The resolved value in the canonical assignment grammar, for a swatch or a number. */
    val value: String? = null,
)

// ─── Outcomes ───────────────────────────────────────────────────────────

private sealed interface TemplateWriteOutcome {
    data object NotFound : TemplateWriteOutcome
    data class NameTaken(val name: String) : TemplateWriteOutcome
    data class Written(val dto: TemplateDto, val uuid: java.util.UUID, val contentsChanged: Boolean) :
        TemplateWriteOutcome
}

private sealed interface TemplateDeleteOutcome {
    data object NotFound : TemplateDeleteOutcome
    data class InUse(val usage: TemplateUsage) : TemplateDeleteOutcome
    data object Deleted : TemplateDeleteOutcome
}

// ─── Validation ─────────────────────────────────────────────────────────

/**
 * The template write boundary. Returns the problem, or null when the rows are acceptable.
 *
 * Four rules, and each is load-bearing rather than defensive:
 *
 *  1. **Exactly one family.** This is what makes a template a template — `TwoThings` calls it the
 *     single real backend ask — and it is enforced here because there is no column to constrain.
 *  2. **A closed property vocabulary** ([TemplateProperty]), which is where "a template cannot carry
 *     a gobo" actually lives. A slotted role is refused by name.
 *  3. **The intent must parse, and match the property's shape.** A `deg:` value on a dimmer is a
 *     client bug, and storing it would produce a row that resolves to nothing on every head.
 *  4. **No group rows.** A template names no targets of its own; the only reason a row names a
 *     fixture is that its value is specific to that head, which a group cannot be.
 */
internal fun validateTemplateRows(rows: List<TemplateRowDto>): String? {
    if (rows.isEmpty()) return "A template must hold at least one value"
    val families = LinkedHashSet<PropertyMaskGroup>()
    for (row in rows) {
        if (row.targetType != DEFERRED_TARGET_TYPE) {
            val target = TargetRef.ofOrNull(row.targetType, row.targetKey)
            if (target !is TargetRef.Fixture) {
                return "A template row targets a fixture or nothing at all, not '${row.targetType}'"
            }
        }
        val property = TemplateProperty.ofOrNull(row.propertyName)
            ?: return "A template cannot hold '${row.propertyName}' — " +
                "slotted properties (gobo, colour wheel, macros) are per-model, so they live in a " +
                "recorded look. Templates hold: " +
                TemplateProperty.entries.joinToString { it.propertyName }
        val intent = parseTemplateIntent(row.value)
            ?: return "'${row.value}' is not a template value for ${property.propertyName}"
        if (!property.accepts(intent)) {
            return "'${row.value}' is the wrong kind of value for ${property.propertyName}"
        }
        families.add(property.family)
    }
    if (families.size > 1) {
        return "A template holds exactly one attribute family, but these rows span " +
            families.joinToString { it.name }
    }
    return null
}

/** Must be called inside a transaction. */
private fun createTemplateRows(template: DaoTemplate, rows: List<TemplateRowDto>) {
    for ((index, row) in rows.withIndex()) {
        DaoTemplateRow.new {
            this.template = template
            targetType = row.targetType
            targetKey = if (row.targetType == DEFERRED_TARGET_TYPE) "" else row.targetKey
            propertyName = row.propertyName
            value = row.value
            sortOrder = if (row.sortOrder != 0) row.sortOrder else index
        }
    }
}

// ─── Usage ──────────────────────────────────────────────────────────────

internal data class TemplateUsage(
    val layerCount: Int,
    val cueIds: List<Int>,
    val cueNames: List<String>,
) {
    fun describe(): String = when {
        layerCount == 0 -> "nothing"
        cueNames.size == 1 -> "1 layer in ${cueNames.first()}"
        else -> "$layerCount layers across ${cueNames.size} cues"
    }
}

/** Must be called inside a transaction. */
internal fun templateUsage(templateId: Int): TemplateUsage =
    templateUsageFor(listOf(templateId))[templateId]
        ?: TemplateUsage(0, emptyList(), emptyList())

/**
 * Batched form for a list response — one query instead of one per template, the same shape
 * `lookUsageFor` uses and for the same reason: a layer's children are a table, so touching them per
 * record would be a lazy query each on every library read.
 *
 * Must be called inside a transaction.
 */
internal fun templateUsageFor(templateIds: Collection<Int>): Map<Int, TemplateUsage> {
    if (templateIds.isEmpty()) return emptyMap()
    val ids = templateIds.toList()
    val layers = DaoCueLayer.find { DaoCueLayers.template inList ids }.toList()
    // `template` is nullable (a layer may name a Look instead) but this query filtered on it, so
    // every row here has one.
    val byTemplate = layers.groupBy { it.template!!.id.value }
    return ids.associateWith { templateId ->
        val mine = byTemplate[templateId].orEmpty()
        val cues = mine.map { it.cue }.distinctBy { it.id.value }
        TemplateUsage(
            layerCount = mine.size,
            cueIds = cues.map { it.id.value },
            cueNames = cues.map { it.name },
        )
    }
}

// ─── Mapping ────────────────────────────────────────────────────────────

/** Must be called inside a transaction. */
internal fun DaoTemplate.toDto(usage: TemplateUsage? = null): TemplateDto {
    val rowList = rows.orderBy(DaoTemplateRows.sortOrder to SortOrder.ASC).toList()
    val resolvedUsage = usage ?: templateUsage(id.value)
    return TemplateDto(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        notes = notes,
        sortOrder = sortOrder,
        fadeDurationMs = fadeDurationMs,
        family = rowList.firstNotNullOfOrNull { TemplateProperty.ofOrNull(it.propertyName)?.family }?.name,
        isGeneric = rowList.isNotEmpty() && rowList.all { it.isDeferred },
        rows = rowList.map {
            TemplateRowDto(
                targetType = it.targetType,
                targetKey = it.targetKey,
                propertyName = it.propertyName,
                value = it.value,
                sortOrder = it.sortOrder,
            )
        },
        layerCount = resolvedUsage.layerCount,
    )
}

// ─── Resolve ────────────────────────────────────────────────────────────

/**
 * Answer the editor's panel: what will each head actually receive?
 *
 * Runs the **same** [TemplateResolver] the cook does, which is the entire point — see §6 of the
 * plan: "the ΔE shown in the editor and the value actually written at cook must come from one
 * implementation". A head the template cannot reach at all is reported rather than dropped, because
 * "why is that bar missing?" is exactly the question the panel exists to answer; a head with nothing
 * in the family is absent, because it was never a candidate.
 */
private fun resolveTemplateAgainstPatch(
    state: State,
    request: TemplateResolveRequest,
): TemplateResolveResponse {
    val fixtures = state.show.fixtures
    val candidates: List<Fixture> = if (request.targets.isEmpty()) {
        fixtures.fixtures
    } else {
        val keys = expandTargetsToFixtureKeys(
            state,
            request.targets.map { CueTargetDto(it.type, it.key) },
        )
        fixtures.fixtures.filter { it.key in keys }
    }

    val entries = ArrayList<TemplateResolutionDto>()
    for (row in request.rows) {
        val intent = parseTemplateIntent(row.value) ?: continue
        // A per-fixture row answers only for the head it names; a generic row answers for all.
        val forThisRow = when (val target = row.target) {
            is TargetRef.Fixture -> candidates.filter { it.key == target.key }
            else -> candidates
        }
        for (fixture in forThisRow) {
            val resolution = TemplateResolver.resolve(fixture, row.propertyName, intent)
            val (outcome, detail, deltaE) = describeNote(resolution.note)
            // A head with nothing in this family was never a candidate — omitted rather than listed
            // as a failure. `Unsupported` still appears when the head *could* have taken it but
            // cannot (no degree range, no dimmer), which is the distinction worth drawing.
            if (resolution.value == null && detail == NOT_A_CANDIDATE) continue
            entries.add(
                TemplateResolutionDto(
                    fixtureKey = fixture.key,
                    fixtureName = fixture.fixtureName,
                    typeKey = fixture.typeKey,
                    propertyName = row.propertyName,
                    resolvedPropertyName = resolution.propertyName,
                    outcome = outcome,
                    detail = detail.takeIf { it != NOT_A_CANDIDATE },
                    deltaE = deltaE,
                    value = resolution.value?.serialize(),
                )
            )
        }
    }
    return TemplateResolveResponse(entries)
}

/**
 * The sentinel that marks "this head has nothing in this family", so the panel can drop it while
 * every other [TemplateResolver.Note.Unsupported] is shown.
 *
 * A magic string rather than a note arm because it is a *presentation* distinction, not a resolution
 * one: the resolver's job is to say why it could not resolve, and "no colour at all" and "no degree
 * range annotated" are the same kind of answer to it. Only the panel cares which to draw.
 */
private const val NOT_A_CANDIDATE = "no colour"

private fun describeNote(note: TemplateResolver.Note): Triple<String, String?, Double?> = when (note) {
    is TemplateResolver.Note.Exact -> Triple("EXACT", null, null)
    is TemplateResolver.Note.Clamped -> Triple("CLAMPED", note.to, null)
    is TemplateResolver.Note.Snapped -> Triple("SNAPPED", note.slot, note.deltaE)
    is TemplateResolver.Note.Degraded -> Triple("DEGRADED", note.how, null)
    is TemplateResolver.Note.Unsupported -> Triple("UNSUPPORTED", note.reason, null)
}
