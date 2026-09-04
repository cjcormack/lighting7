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
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.fx.EffectSpecCoercion
import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import uk.me.cormack.lighting7.fx.serializeTemplateColourRef
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateEffect
import uk.me.cormack.lighting7.models.DaoTemplateGroup
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoTemplateRows
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.TemplateEffectDto
import uk.me.cormack.lighting7.models.TemplateRowDto
import java.util.UUID
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
    // GET /projects/{id}/templates?family=COLOUR
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
                all.map { it.toDto(state.show.fxRegistry, usage[it.id.value]) }
            }
            // Filtered after the DTOs are built, because the family is derived from the rows and
            // there is no column to query on — the same reason `/looks` filters in memory.
            call.respond(
                if (parsedFamily == null) templates
                else templates.filter { it.family == parsedFamily.name },
            )
        }
    }

    // GET /projects/{id}/templates/{templateId}
    get<ProjectTemplateResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val template = DaoTemplate.findById(resource.templateId) ?: return@transaction null
                if (template.project.id != project.id) return@transaction null
                template.toDto(state.show.fxRegistry, templateUsage(template.id.value))
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
            } else {
                call.respond(dto)
            }
        }
    }

    // POST /projects/{id}/templates
    post<ProjectTemplatesResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            when (val result = performTemplateCreate(state, project, call.receive())) {
                is TemplateCreateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                is TemplateCreateResult.Duplicate ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(result.message))
                is TemplateCreateResult.Refused ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(result.message, code = result.code))
                is TemplateCreateResult.Ok ->
                    call.respond(HttpStatusCode.Created, result.template)
            }
        }
    }

    // PUT /projects/{id}/templates/{templateId}
    //
    // Unlike the Look PUT this takes a typed body rather than a raw `JsonObject`. The
    // absent-versus-empty distinction that forced the raw decode there does not arise for either
    // child: neither a rows-less value template nor an effect-less effect template is a thing you
    // can save, so "omitted" and "empty" both mean "leave that half alone" — which a nullable
    // field says exactly. That is also why `effect` needs no `effectPresent` twin the way `notes`
    // does: an effect can be replaced but never cleared, because clearing it would flip Holds,
    // which the write boundary refuses outright.
    //
    // The validation therefore has to run *inside* the transaction, against the template's stored
    // Holds — a rows body arriving for an effect template is only detectable with the record in
    // hand.
    put<ProjectTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<TemplateInput>()
            val outcome = transaction(state.database) {
                val template = DaoTemplate.findById(resource.templateId)
                    ?: return@transaction TemplateWriteOutcome.NotFound
                if (template.project.id != project.id) return@transaction TemplateWriteOutcome.NotFound

                // Holds is the template's identity, like its family: a value template stays a
                // value template (D1). Refused here rather than trusted to the sheet's locked
                // segment, because the AI surface and a hand-rolled PUT reach the same route.
                val holdsEffect = template.effect != null
                if (request.rows?.isNotEmpty() == true && holdsEffect) {
                    return@transaction TemplateWriteOutcome.Invalid(
                        "'${template.name}' holds an effect — a template cannot hold values as " +
                            "well, and what it holds is fixed when it is created",
                    )
                }
                if (request.effect != null && !holdsEffect && !template.rows.empty()) {
                    return@transaction TemplateWriteOutcome.Invalid(
                        "'${template.name}' holds values — a template cannot hold an effect as " +
                            "well, and what it holds is fixed when it is created",
                    )
                }
                // Only what this request actually *sends* goes through the content rules — the
                // same carve-out `validateSpeedMasterSettings` documents. Contents already stored
                // are upstream of this write (an import writes them verbatim, deliberately, so a
                // category from a newer build can land here), and re-checking them would 400
                // every later PUT on the row, rename and notes edits included. The Holds check
                // above is the one rule that genuinely needs the stored state, which is why it is
                // separate.
                if (request.rows != null || request.effect != null) {
                    validateTemplateContents(
                        request.rows ?: emptyList(),
                        request.effect,
                        state.show.fxRegistry,
                        template.uuid,
                    )?.let { return@transaction TemplateWriteOutcome.Invalid(it) }
                }

                // The group's one-family rule, judged on the family this write *leaves* the
                // template with — the new contents when it sends any, the stored ones otherwise.
                // Two ways to break it: joining a group of another family, and re-contenting a
                // grouped template into another family. `validateTemplateContents` cannot see the
                // second, because it checks only that the new rows are one family, not which.
                val contentsSent = request.rows != null || request.effect != null
                val resultingFamily =
                    if (contentsSent) contentsFamily(request.rows ?: emptyList(), request.effect)
                    else template.familyOf()
                val destinationGroup: DaoTemplateGroup? = if (request.groupIdPresent) {
                    request.groupId?.let { id ->
                        DaoTemplateGroup.findById(id)?.takeIf { it.project.id == project.id }
                            ?: return@transaction TemplateWriteOutcome.Invalid("Template group not found")
                    }
                } else {
                    template.group
                }
                if (destinationGroup != null) {
                    groupFamilyClash(destinationGroup, resultingFamily, excluding = template)?.let {
                        return@transaction TemplateWriteOutcome.Refused(CODE_TEMPLATE_GROUP_FAMILY, it)
                    }
                }

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
                // A move appends at the destination — the group's end, or the top level's — and an
                // explicit `sortOrder` in the same body still wins, so a client that knows the
                // slot it wants can say so. Where the group is unchanged nothing moves.
                if (request.groupIdPresent && destinationGroup?.id != template.group?.id) {
                    template.sortOrder =
                        if (destinationGroup != null) nextSortOrderIn(destinationGroup)
                        else nextTopLevelSortOrder(project)
                    template.group = destinationGroup
                }
                request.sortOrder?.let { template.sortOrder = it }
                if (request.fadeDurationMsPresent) template.fadeDurationMs = request.fadeDurationMs

                val rows = request.rows
                if (rows != null) {
                    template.rows.forEach { it.delete() }
                    createTemplateRows(template, rows)
                }
                val effect = request.effect
                if (effect != null) {
                    template.effects.forEach { it.delete() }
                    createTemplateEffect(template, effect)
                }
                TemplateWriteOutcome.Written(
                    template.toDto(state.show.fxRegistry, templateUsage(template.id.value)),
                    template.uuid,
                    // An effect-only edit is a contents change like a rows edit: without it
                    // `republishForTemplateEdit` never runs, and retuning an effect template would
                    // reach nothing already live — which is the feature.
                    contentsChanged = rows != null || effect != null,
                )
            }
            when (outcome) {
                is TemplateWriteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                is TemplateWriteOutcome.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A template named '${outcome.name}' already exists in this project"),
                )
                is TemplateWriteOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message))
                is TemplateWriteOutcome.Refused ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, code = outcome.code))
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

    // DELETE /projects/{id}/templates/{templateId}?force=true
    delete<ProjectTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val outcome = transaction(state.database) {
                val template = DaoTemplate.findById(resource.templateId)
                    ?: return@transaction TemplateDeleteOutcome.NotFound
                if (template.project.id != project.id) return@transaction TemplateDeleteOutcome.NotFound
                val usage = templateUsage(template.id.value)
                // Three kinds of usage, and all must block. A cue layer *applies* the template; a
                // programmer layer is *running* it right now; an effect parameter *references* it
                // by uuid (`tmpl:{uuid}`) — and deleting out from under that last one is worse than
                // out from under a layer, because the effect keeps running and its colour silently
                // falls back to white.
                val fxRefs = templateFxReferenceCount(template.uuid)
                // Read off the in-memory stack, not the DB: a programmer layer is live state with
                // no row of its own, which is exactly why this cannot live in `TemplateUsage`.
                val running = state.show.programmerStore.layers.count {
                    it.source.isTemplate && it.source.uuid == template.uuid
                }
                if ((usage.layerCount > 0 || fxRefs > 0 || running > 0) && !resource.force) {
                    return@transaction TemplateDeleteOutcome.InUse(usage, fxRefs, running)
                }
                // No DB-level ON DELETE CASCADE — SQLite does not enforce cascades without a
                // per-connection pragma, so children go first, explicitly.
                if (resource.force) {
                    DaoCueLayer.find { DaoCueLayers.template eq template.id }.forEach { it.delete() }
                }
                template.rows.forEach { it.delete() }
                template.effects.forEach { it.delete() }
                // Its busk pads go with it, unconditionally — a pad is an enrichment, not a use
                // the guard above counts (busk-layout plan D3).
                val pageIds = deleteBuskPadsReferencing(templateId = template.id.value)
                val uuid = template.uuid
                template.delete()
                TemplateDeleteOutcome.Deleted(uuid, pageIds)
            }
            when (outcome) {
                is TemplateDeleteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                is TemplateDeleteOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    TemplateInUseResponse(
                        error = "This template is still in use by " +
                            describeTemplateUse(
                                outcome.usage, outcome.fxReferenceCount, outcome.runningCount,
                            ),
                        code = CODE_TEMPLATE_IN_USE,
                        layerCount = outcome.usage.layerCount,
                        cueIds = outcome.usage.cueIds,
                        cueNames = outcome.usage.cueNames,
                        fxReferenceCount = outcome.fxReferenceCount,
                        runningCount = outcome.runningCount,
                    ),
                )
                is TemplateDeleteOutcome.Deleted -> {
                    // *Delete anyway* releases the programmer layers the guard counted, the same
                    // way it deleted the cue-layer rows. Nothing else can: a layer naming a uuid
                    // with no row behind it resolves to null on every recook, so it sits in the
                    // stack asserting nothing while the effect it spawned keeps running, and the
                    // operator has no template left to press to take it off.
                    state.show.programmerStore.layers
                        .filter { it.source.isTemplate && it.source.uuid == outcome.uuid }
                        .forEach { state.show.programmerLayerStack.remove(it.layerId) }
                    state.show.fixtures.templateListChanged()
                    if (outcome.pageIds.isNotEmpty()) state.show.fixtures.buskLayoutChanged(outcome.pageIds.toList())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    // POST /projects/{id}/templates/resolve — the editor's "resolves to" panel.
    post<TemplateResolveResource> { resource ->
        withProject(state, resource.parent.projectId) { _ ->
            val request = call.receive<TemplateResolveRequest>()
            // Rows only: the panel answers "what will each head receive", which an effect template
            // has no answer to — its *Runs on* panel is a head count and a client-side preview.
            validateTemplateContents(
                request.rows, effect = null, registry = state.show.fxRegistry,
            )?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withProject
            }
            call.respond(resolveTemplateAgainstPatch(state, request))
        }
    }

    // POST /projects/{id}/templates/{templateId}/apply — click: literals into the programmer.
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

    // POST /projects/{id}/templates/{templateId}/toggle — ⌥click and the busking pads.
    post<ToggleTemplateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ToggleTemplateRequest>()
            // Source *and* mask in one transaction: the mask is derived by the same [familyOf]
            // `toDto` uses for `family`, so the layer cannot be masked to something the template
            // list never showed. Deriving it here rather than trusting `request.propertyMask` is
            // also what gives the response an independent answer to report: an echo could never
            // disagree with the caller.
            //
            // One shared derivation rather than the row expression this inlined before: an effect
            // template has no rows, so the inline copy would have answered null and added an
            // *unmasked* layer — which asserts across every family instead of the effect's own.
            //
            // The siblings come out of the same transaction too: a group's exclusivity is a fact
            // about the library at the moment of the press, and reading it beside the source is
            // what keeps a template moved between groups by another client from releasing the
            // wrong set.
            val found = transaction(state.database) {
                DaoTemplate.findById(resource.templateId)
                    ?.takeIf { it.project.id == project.id }
                    ?.let { template ->
                        Triple(
                            LayerSource.template(template.id.value, template.uuid, template.name),
                            template.familyOf()?.name,
                            template.siblingUuids(),
                        )
                    }
            }
            if (found == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(TEMPLATE_NOT_FOUND))
                return@withCurrentProject
            }
            val (source, derivedMask, siblings) = found
            try {
                val outcome = state.show.programmerLayerStack.toggle(
                    source = source,
                    targets = request.targets.map { CueTargetDto(it.type, it.key) },
                    propertyMask = derivedMask,
                    releaseSiblings = siblings,
                )
                call.respond(
                    ToggleTemplateResponse(outcome.action, outcome.effectCount, derivedMask, outcome.released),
                )
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
            }
        }
    }

    // POST /projects/{id}/templates/reorder — the whole layout, templates and groups together
    //
    // Modelled on `POST /cue-stacks/reorder` with one difference that matters: the body is the
    // *complete* layout, not the ids that moved (see `ReorderTemplatesRequest`). Position and
    // membership are metadata — nothing a cue composes to changes — so this takes the
    // `templateListChanged` branch a rename takes, never a republish.
    post<TemplatesReorderResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ReorderTemplatesRequest>()
            val outcome = transaction(state.database) { applyTemplateLayout(project, request.entries) }
            when (outcome) {
                is TemplateLayoutOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message))
                is TemplateLayoutOutcome.MixedFamily ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, code = CODE_TEMPLATE_GROUP_FAMILY))
                is TemplateLayoutOutcome.Ok -> {
                    state.show.fixtures.templateListChanged()
                    call.respond(HttpStatusCode.OK)
                }
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
 * Unambiguous against [ProjectTemplateResource] despite sitting at the same depth: nothing serves
 * `POST /{templateId}`, and `templateId` is an `Int` that `"resolve"` cannot parse as.
 */
@Resource("/resolve")
internal data class TemplateResolveResource(val parent: ProjectTemplatesResource)

/** Unambiguous beside [ProjectTemplateResource] for the reason [TemplateResolveResource] gives. */
@Resource("/reorder")
internal data class TemplatesReorderResource(val parent: ProjectTemplatesResource)

@Resource("/{templateId}/apply")
internal data class ApplyTemplateResource(val parent: ProjectTemplatesResource, val templateId: Int)

@Resource("/{templateId}/toggle")
internal data class ToggleTemplateResource(val parent: ProjectTemplatesResource, val templateId: Int)

// ─── DTOs ───────────────────────────────────────────────────────────────

/**
 * A template as the library lists it — **contents included**, unlike `LookDto`.
 *
 * A Look needs a summary/detail split because its derived counts would go stale beside its contents.
 * A template holds no palette and either one effect or few rows (one for a generic template, one per
 * head for a focus position), so one shape still serves the list and the editor — and the library
 * row can preview the actual value without a second fetch, which is what `LookLibrary`'s row does
 * with `preview`.
 */
@Serializable
internal data class TemplateDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    /**
     * Position within [groupId]'s group when grouped, otherwise in the project's top-level
     * sequence — which ungrouped templates share with the groups (`TemplateGroupDto.sortOrder`).
     */
    val sortOrder: Int,
    val fadeDurationMs: Long? = null,
    /** The group this template sits in, or null at top level. Membership lives here, not on the group. */
    val groupId: Int? = null,
    /**
     * The one family this template is in, **derived** — from its rows, or from its effect's library
     * category (D4) — never stored. Null only for a template whose contents have all been deleted,
     * which the write boundary does not allow but a hand-edited database could produce.
     */
    val family: String? = null,
    /**
     * True when every row takes its targets from whatever applies the template (the *Generic* case);
     * false when the rows name their own heads (the *Per fixture* case, a focus position).
     *
     * Always true for an **effect** template: an effect fans over whatever the layer names, so
     * there is no per-fixture case for it to be (D3).
     */
    val isGeneric: Boolean,
    /**
     * What this template holds — `"value"` or `"effect"` (D1). Derived, and the field the library
     * row branches its swatch on.
     *
     * A string rather than a boolean because it is a two-arm vocabulary the UI renders by name, and
     * because `FU-TMPL-MULTI-EFFECT` would give it a third arm before it gave it a second flag.
     */
    val kind: String,
    val rows: List<TemplateRowDto> = emptyList(),
    /** The one effect an effect template holds; null for a value template. */
    val effect: TemplateEffectDto? = null,
    /**
     * How many **cue** layers apply this template. Gates delete.
     *
     * Cue layers only, deliberately — programmer layers tracking it live are counted by the delete
     * guard instead ([TemplateInUseResponse.runningCount]), because that count needs the in-memory
     * store and this mapping has only the row.
     */
    val layerCount: Int,
)

/**
 * Create / update payload.
 *
 * `notes` and `fadeDurationMs` carry an explicit presence flag rather than relying on null, because
 * for both of them null is a *value* an operator can set (clear the notes, use the caller's default
 * fade) and PUT has to be able to tell that from "leave it alone".
 *
 * `rows` and `effect` need no such flag: null cannot mean "clear" for either, because clearing
 * would leave a template holding nothing — or flip its Holds, which the write boundary refuses
 * (D1). Exactly one of them is set on create.
 */
@Serializable
internal data class TemplateInput(
    val name: String? = null,
    val notes: String? = null,
    val notesPresent: Boolean = false,
    val sortOrder: Int? = null,
    val fadeDurationMs: Long? = null,
    val fadeDurationMsPresent: Boolean = false,
    /**
     * The group to sit in; null with [groupIdPresent] means "top level". The same absent-versus-
     * null distinction as `notes`, because a null here is a real instruction (leave the group).
     * On create, null and absent both mean top level. A move appends at the destination's end;
     * `POST /templates/reorder` is the way to say *where*.
     */
    val groupId: Int? = null,
    val groupIdPresent: Boolean = false,
    val rows: List<TemplateRowDto>? = null,
    /** The effect an effect template holds (D1/D2). Never set beside a non-empty [rows]. */
    val effect: TemplateEffectDto? = null,
)

@Serializable
internal data class TemplateInUseResponse(
    val error: String,
    val code: String,
    val layerCount: Int,
    val cueIds: List<Int>,
    val cueNames: List<String>,
    /** Effect parameters holding a `tmpl:{uuid}` reference to this template. */
    val fxReferenceCount: Int = 0,
    /**
     * Programmer layers tracking this template *right now*.
     *
     * Live state with no row of its own, so it is absent from [layerCount] (which counts stored
     * cue layers) and from `TemplateUsage` — the dialog reports the two separately because
     * *Delete anyway* undoes them differently: a cue layer row is deleted, a programmer layer is
     * simply released.
     */
    val runningCount: Int = 0,
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
    /**
     * Programmer entries written. Always 0 for an **effect** template, which writes no values —
     * its count is [effectIds]`.size`.
     */
    val written: Int,
    /** Heads the template could not reach, with the reason — the same notes the panel shows. */
    val skipped: List<TemplateSkipDto> = emptyList(),
    /**
     * The programmer-band effects a click on an **effect** template minted, one per target it
     * named; empty for a value template.
     *
     * Ids rather than a count, because the caller has to be able to take *these* copies off again
     * without sweeping the whole band — the copies carry no template identity to find them by, by
     * design (see `applyEffectTemplateToProgrammer`).
     */
    val effectIds: List<Long> = emptyList(),
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
     * What the caller *believed* it was masking to. Advisory: the mask actually applied is derived
     * from the template's rows server-side, because the mask a template layer wants is a fact about
     * the template rather than about the press. Stating it anyway is what lets a client and server
     * that disagree show up in [ToggleTemplateResponse.propertyMask] instead of silently on the rig.
     */
    val propertyMask: String? = null,
)

@Serializable
internal data class ToggleTemplateResponse(
    val action: String,
    val effectCount: Int,
    /**
     * The mask the layer actually carries — the template's own family, derived from its rows. Null
     * only for a template whose rows name no known property. Compare against what was sent: this is
     * the *server's* answer, not an echo, so the two genuinely can differ.
     */
    val propertyMask: String? = null,
    /**
     * How many sibling layers this press took off first — a template group's exclusivity, applied
     * to layers on the *same* target set. Always 0 on a `removed`, and for an ungrouped template.
     */
    val released: Int = 0,
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

    /**
     * The body would have written contents the write boundary refuses — a 400.
     *
     * Separate from [NameTaken]'s 409 because these are two different client mistakes: a clash is
     * about the project's *other* templates, this is about this template's own contents.
     */
    data class Invalid(val message: String) : TemplateWriteOutcome

    /** Refused by a rule about the project's *other* records, with a code the client keys on — a 409. */
    data class Refused(val code: String, val message: String) : TemplateWriteOutcome
    data class Written(val dto: TemplateDto, val uuid: java.util.UUID, val contentsChanged: Boolean) :
        TemplateWriteOutcome
}

private sealed interface TemplateDeleteOutcome {
    data object NotFound : TemplateDeleteOutcome
    data class InUse(
        val usage: TemplateUsage,
        val fxReferenceCount: Int,
        val runningCount: Int,
    ) : TemplateDeleteOutcome

    /**
     * Carries the uuid so the handler can release the programmer layers naming it, and the busk
     * pages that lost a pad so it can say so.
     */
    data class Deleted(val uuid: java.util.UUID, val pageIds: Set<Int>) : TemplateDeleteOutcome
}

// ─── Validation ─────────────────────────────────────────────────────────

/*
 * The template write boundary lives in [validateTemplateContents] below, and every rule there is
 * load-bearing rather than defensive.
 *
 * A **value** template's rows:
 *  1. **Exactly one family.** This is what makes a template a template — `TwoThings` calls it the
 *     single real backend ask — and it is enforced here because there is no column to constrain.
 *  2. **A closed property vocabulary** ([TemplateProperty]), which is where "a template cannot carry
 *     a gobo" actually lives. A slotted role is refused by name.
 *  3. **The intent must parse, and match the property's shape.** A `deg:` value on a dimmer is a
 *     client bug, and storing it would produce a row that resolves to nothing on every head.
 *  4. **No group rows.** A template names no targets of its own; the only reason a row names a
 *     fixture is that its value is specific to that head, which a group cannot be.
 *
 * An **effect** template's one effect (fx-templates D1–D4, D12): exactly one of rows/effect, a
 * category that maps to a bankable family, a type the [FxRegistry] resolves whose own category
 * agrees with the stored one, enum-valued fields the desk recognises, and no `tmpl:` parameter
 * naming the template itself.
 */

internal sealed interface TemplateCreateResult {
    data class Ok(val template: TemplateDto) : TemplateCreateResult
    data class Invalid(val message: String) : TemplateCreateResult
    data class Duplicate(val message: String) : TemplateCreateResult

    /** A 409 with a code the client keys on — today only [CODE_TEMPLATE_GROUP_FAMILY]. */
    data class Refused(val code: String, val message: String) : TemplateCreateResult
}

/**
 * Create a template — the shared body behind `POST /templates` and the AI surface's
 * `create_template`.
 *
 * Shared rather than copied because the validation *is* the feature: [validateTemplateContents]
 * enforces one attribute family per template, rejects slotted properties, and refuses an effect
 * whose category no family banks — and a second caller that skipped it could write a template no
 * consumer can resolve.
 */
internal fun performTemplateCreate(
    state: State,
    project: DaoProject,
    input: TemplateInput,
): TemplateCreateResult {
    val name = input.name?.trim().orEmpty()
    if (name.isEmpty()) return TemplateCreateResult.Invalid("Template name must not be blank")

    val rows = input.rows ?: emptyList()
    val effect = input.effect
    // No `ownUuid`: the template does not exist yet, so it cannot name itself. The self-reference
    // rule is the update route's to enforce.
    validateTemplateContents(rows, effect, state.show.fxRegistry)
        ?.let { return TemplateCreateResult.Invalid(it) }

    val result = transaction(state.database) {
        val duplicate = DaoTemplate.find {
            (DaoTemplates.project eq project.id) and (DaoTemplates.name eq name)
        }.firstOrNull()
        if (duplicate != null) {
            return@transaction TemplateCreateResult.Duplicate(
                "A template named '$name' already exists in this project",
            )
        }
        // Into a group, or at the end of the top-level sequence the groups share. The family rule
        // is judged on the contents being created, before anything is written.
        val group = input.groupId?.let { id ->
            DaoTemplateGroup.findById(id)?.takeIf { it.project.id == project.id }
                ?: return@transaction TemplateCreateResult.Invalid("Template group not found")
        }
        if (group != null) {
            groupFamilyClash(group, contentsFamily(rows, effect))?.let {
                return@transaction TemplateCreateResult.Refused(CODE_TEMPLATE_GROUP_FAMILY, it)
            }
        }
        val template = DaoTemplate.new {
            this.project = project
            this.name = name
            this.notes = input.notes
            this.group = group
            this.sortOrder = input.sortOrder
                ?: if (group != null) nextSortOrderIn(group) else nextTopLevelSortOrder(project)
            this.fadeDurationMs = input.fadeDurationMs
        }
        createTemplateRows(template, rows)
        effect?.let { createTemplateEffect(template, it) }
        TemplateCreateResult.Ok(template.toDto(state.show.fxRegistry, templateUsage(template.id.value)))
    }
    if (result is TemplateCreateResult.Ok) state.show.fixtures.templateListChanged()
    return result
}

/**
 * The family a set of contents is in — [DaoTemplate.familyOf] for contents that are not yet a
 * row. Null when nothing in them names a family, which the write boundary refuses anyway.
 */
internal fun contentsFamily(rows: List<TemplateRowDto>, effect: TemplateEffectDto?): PropertyMaskGroup? =
    rows.firstNotNullOfOrNull { TemplateProperty.ofOrNull(it.propertyName)?.family }
        ?: effect?.let { familyForEffectCategory(it.category) }

/**
 * The template write boundary: a value template's rows *or* an effect template's one effect.
 *
 * Called with the **resulting** (post-write) contents, so create and update share the
 * implementation — the [uk.me.cormack.lighting7.models.validateSpeedMasterSettings] pattern.
 * [ownUuid] is the template being edited (null on create), needed for the self-reference rule.
 *
 * Returns the problem, or null when the contents are acceptable.
 */
internal fun validateTemplateContents(
    rows: List<TemplateRowDto>,
    effect: TemplateEffectDto?,
    registry: FxRegistry,
    ownUuid: java.util.UUID? = null,
): String? {
    // Rule 1 (D1): a template holds a value *or* an effect. A colour *and* a chase is a Look,
    // which already holds rows plus deferred effects and has its own busk pool.
    if (rows.isNotEmpty() && effect != null) {
        return "A template holds a value or an effect, never both — for both together, record a look"
    }
    if (rows.isEmpty() && effect == null) return "A template must hold at least one value"
    if (effect != null) return validateTemplateEffect(effect, registry, ownUuid)

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

/**
 * Rules 2–5 for the effect arm of [validateTemplateContents].
 *
 * Split out rather than inlined because the row arm's four rules and these four answer different
 * questions — the rows arm asks "is this a resolvable value", this asks "is this a runnable
 * effect of a family a template can be banked under".
 */
private fun validateTemplateEffect(
    effect: TemplateEffectDto,
    registry: FxRegistry,
    ownUuid: java.util.UUID?,
): String? {
    // Rule 2 (D4): the family is the effect library's own category, through the same map a Look's
    // derived families use. `controls` has no tempo, `composite` spans families, and BEAM is
    // refused by name rather than by the library happening to ship no beam effect — a
    // script-registered one must not be able to mint a Beam effect template behind this rule.
    val family = familyForEffectCategory(effect.category)
        ?: return "A template cannot hold a '${effect.category}' effect — an effect template is " +
            "banked by family, and that category has none. Effect templates are: " +
            TEMPLATE_EFFECT_FAMILIES.joinToString { it.name }
    if (family !in TEMPLATE_EFFECT_FAMILIES) {
        return "A ${family.name} template cannot hold an effect — the effect library has no " +
            "${family.name.lowercase()} category. A beam effect lives in a recorded look."
    }

    // Rule 3: the library is the authority on what an effect *is*; the stored `category` is a
    // denormalisation for the family derivation and the list DTO, so it must agree or the two
    // disagree silently — the template banks under one family and runs as another.
    val registration = registry.getRegistration(effect.effectType)
        ?: return "'${effect.effectType}' is not an effect this desk knows"
    if (!registration.category.equals(effect.category, ignoreCase = true)) {
        return "'${effect.effectType}' is a ${registration.category} effect, not ${effect.category}"
    }

    // Rule 4: the enum-valued fields, exactly as `validateLookEffects` checks them and for the
    // reason its KDoc gives — an unrecognised `blendMode` reaches `varchar(50)` intact, reads back
    // as itself and renders in the UI as the template's blend, while every spawn warns and plays
    // `OVERRIDE`. `EffectSpecCoercion.Lenient` exists to *survive* that, not to be the only thing
    // standing between an authoring route and a template that permanently misreports itself.
    EffectSpecCoercion.Strict.problem(
        blendMode = effect.blendMode,
        distribution = effect.distribution,
        elementMode = effect.elementMode,
        elementFilter = effect.elementFilter,
    )?.let { return it }

    // Rule 5 (D12): a template naming itself would recurse in `createEffectWithTemplates`. The
    // other direction — an effect template's colour parameter naming a *value* colour template —
    // is allowed and is the useful case.
    if (ownUuid != null) {
        val selfRef = serializeTemplateColourRef(ownUuid)
        val namesSelf = effect.parameters.values.any { value ->
            value.split(",").any { it.trim().equals(selfRef, ignoreCase = true) }
        }
        if (namesSelf) return "A template's effect cannot reference the template itself"
    }
    return null
}

/**
 * The families an effect template can be banked under (D4).
 *
 * Not all of [PropertyMaskGroup]: BEAM is absent because the effect library has no beam category,
 * so there would be nothing to put in the column. Adding one is an FX-library change, not a
 * template change.
 */
private val TEMPLATE_EFFECT_FAMILIES = setOf(
    PropertyMaskGroup.INTENSITY,
    PropertyMaskGroup.COLOUR,
    PropertyMaskGroup.POSITION,
)

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

/**
 * Write a template's one effect (D2). Must be called inside a transaction, on a template with none.
 *
 * The enum-valued fields go in as given rather than being coerced here: the write boundary has
 * already rejected an unrecognised blend / distribution / element mode through
 * `EffectSpecCoercion.Strict`, exactly as `validateLookEffects` does, and spawn time coerces
 * leniently — the same treatment a Look effect gets, so the two cannot disagree about what a
 * stored effect means.
 */
private fun createTemplateEffect(template: DaoTemplate, effect: TemplateEffectDto) {
    DaoTemplateEffect.new {
        this.template = template
        effectType = effect.effectType
        category = effect.category
        propertyName = effect.propertyName
        beatDivision = effect.beatDivision
        blendMode = effect.blendMode
        distribution = effect.distribution
        phaseOffset = effect.phaseOffset
        elementMode = effect.elementMode
        elementFilter = effect.elementFilter
        stepTiming = effect.stepTiming
        parameters = effect.parameters
        // `speedMasterUuidOrNull`, not `UUID.fromString`: this is a client-supplied string, and
        // the bare parse throws `IllegalArgumentException` out of the transaction and out of the
        // handler — a 500 on a malformed body. The Look path (`createLookChildren`) already goes
        // through the tolerant helper, and null here means master 1, which is the documented
        // meaning of an absent master everywhere else.
        speedMasterUuid = speedMasterUuidOrNull(effect.speedMasterUuid)
        rateSpeedMasterUuid = speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
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

/**
 * How many **effect parameters** reference this template by uuid.
 *
 * Deliberately not folded into [TemplateUsage]: that is computed on every library read, and this is
 * a scan of three JSON columns rather than an indexed FK lookup. Delete is rare; listing is not.
 *
 * The scan is over the whole show's ad-hoc, Look and template effects rather than a `LIKE` on the JSON text,
 * because the stored form is a serialised `Map<String, String>` and a substring match on it would
 * also hit a parameter whose *name* happened to contain the uuid. Effect counts are in the hundreds.
 *
 * `fx_definitions.parameters` is **not** scanned: it holds a definition's schema *defaults*, which
 * seed new effects rather than driving running ones. A default naming a deleted template produces
 * one white effect the next time someone adds it, not a change to anything on stage.
 *
 * Must be called inside a transaction.
 */
internal fun templateFxReferenceCount(templateUuid: java.util.UUID): Int {
    val ref = serializeTemplateColourRef(templateUuid)
    fun Map<String, String>.holdsRef() = values.any { value ->
        value.split(",").any { it.trim().equals(ref, ignoreCase = true) }
    }
    val adHoc = DaoCueAdHocEffect.all().count { it.parameters.holdsRef() }
    val look = DaoLookEffect.all().count { it.parameters.holdsRef() }
    // The third carrier of effect parameters, since a template can hold an effect (D12): an effect
    // template's colour parameter naming a *value* colour template is the allowed and useful
    // direction, and without this arm that value template stays deletable out from under a running
    // effect — the exact failure this guard exists to prevent.
    val templateEffect = DaoTemplateEffect.all().count { it.parameters.holdsRef() }
    return adHoc + look + templateEffect
}

/** Phrase all three kinds of usage for the 409 body. */
private fun describeTemplateUse(
    usage: TemplateUsage,
    fxReferenceCount: Int,
    runningCount: Int,
): String {
    val parts = buildList {
        if (usage.layerCount > 0) add(usage.describe())
        if (fxReferenceCount > 0) {
            add(if (fxReferenceCount == 1) "1 effect parameter" else "$fxReferenceCount effect parameters")
        }
        // Phrased as a state rather than a count: an operator does not think of the programmer as
        // holding "2 layers", they think of the template as being up right now.
        if (runningCount > 0) add("the programmer now")
    }
    return if (parts.isEmpty()) "nothing" else parts.joinToString(" and ")
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

/** One stored template row on the wire. Must be called inside a transaction. */
internal fun DaoTemplateRow.toDto() = TemplateRowDto(
    targetType = targetType,
    targetKey = targetKey,
    propertyName = propertyName,
    value = value,
    sortOrder = sortOrder,
)

/**
 * One stored template effect on the wire. Must be called inside a transaction.
 *
 * Takes the registry rather than defaulting it away, so [TemplateEffectDto.timingSource] cannot be
 * silently omitted by a new call site: a DTO without it makes `beatDivision` unreadable, and the
 * failure is a client rendering "2 Bars" for a two-second cycle rather than anything that throws.
 */
internal fun DaoTemplateEffect.toDto(registry: FxRegistry) = TemplateEffectDto(
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
    // The registry is the authority, exactly as it is for `category` — which the write boundary
    // checks rather than trusts. Unlike `category` this one is not stored at all: an effect's
    // timing source is a property of the *effect type*, so a column would be a second copy that a
    // re-registered script could contradict.
    timingSource = registry.getRegistration(effectType)?.timingSource?.name,
)

/**
 * The one family this template is in — the **single** derivation, from rows or from the effect's
 * library category (D4).
 *
 * One function because there are two readers that must never disagree: [toDto], which is what the
 * library banks the template under, and the toggle route, which masks the programmer layer it adds
 * to that same family. They were two copies of the row expression before an effect template
 * existed, at which point the toggle route's copy would have derived null and added an unmasked
 * layer.
 *
 * Must be called inside a transaction.
 */
internal fun DaoTemplate.familyOf(): PropertyMaskGroup? =
    // `sortedBy` rather than Exposed's `orderBy`: this is called from `toDto` *after* the rows have
    // been loaded, and re-ordering a loaded `SizedIterable` throws "Can't order already loaded
    // data". A template has a handful of rows, so sorting them in memory costs nothing and leaves
    // this function safe to call whatever the caller has already read.
    rows.sortedBy { it.sortOrder }
        .firstNotNullOfOrNull { TemplateProperty.ofOrNull(it.propertyName)?.family }
        ?: effect?.let { familyForEffectCategory(it.category) }

/**
 * The one family a group is in, derived from its members the way [DaoTemplate.familyOf] derives a
 * template's from its rows. Null for an empty group.
 *
 * The write boundary keeps the members single-family (`TEMPLATE_GROUP_FAMILY`), so this is the
 * first member's family by construction; on a hand-edited database holding a mix it is *still* the
 * first member's, because a read is not the place to throw over data the write boundary already
 * forbids — the stance [DaoTemplate.effect] takes with `firstOrNull`.
 *
 * Must be called inside a transaction.
 */
internal fun DaoTemplateGroup.familyOf(): PropertyMaskGroup? =
    members.sortedBy { it.sortOrder }.firstNotNullOfOrNull { it.familyOf() }

/**
 * The uuids of the *other* templates in this template's group — what a busk press on this one
 * releases (`ProgrammerLayerStack.toggle`'s `releaseSiblings`). Empty for an ungrouped template.
 *
 * Must be called inside a transaction.
 */
internal fun DaoTemplate.siblingUuids(): Set<UUID> {
    val groupId = group?.id ?: return emptySet()
    return DaoTemplate.find { (DaoTemplates.group eq groupId) and (DaoTemplates.id neq id) }
        .map { it.uuid }
        .toSet()
}

/** Must be called inside a transaction. */
internal fun DaoTemplate.toDto(registry: FxRegistry, usage: TemplateUsage? = null): TemplateDto {
    // `sortedBy`, not Exposed's `orderBy`, for the reason [familyOf] gives: the PUT route reads
    // the rows before it validates, so by the time this runs the collection is loaded and
    // re-ordering it throws.
    val rowList = rows.sortedBy { it.sortOrder }
    val storedEffect = effect
    val resolvedUsage = usage ?: templateUsage(id.value)
    return TemplateDto(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        notes = notes,
        sortOrder = sortOrder,
        fadeDurationMs = fadeDurationMs,
        groupId = group?.id?.value,
        family = familyOf()?.name,
        // An effect template is generic by construction (D3) — it names no target at all, so the
        // "every row is deferred" test has nothing to run over and would answer false.
        isGeneric = if (storedEffect != null) true else rowList.isNotEmpty() && rowList.all { it.isDeferred },
        kind = if (storedEffect != null) TEMPLATE_KIND_EFFECT else TEMPLATE_KIND_VALUE,
        rows = rowList.map { it.toDto() },
        effect = storedEffect?.toDto(registry),
        layerCount = resolvedUsage.layerCount,
    )
}

/** [TemplateDto.kind] for a template holding rows. */
internal const val TEMPLATE_KIND_VALUE = "value"

/** [TemplateDto.kind] for a template holding one effect. */
internal const val TEMPLATE_KIND_EFFECT = "effect"

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
