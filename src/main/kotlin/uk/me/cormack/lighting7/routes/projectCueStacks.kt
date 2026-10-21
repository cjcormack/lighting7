package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.resources.patch
import io.ktor.server.resources.delete
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.CueEntryRef
import uk.me.cormack.lighting7.fx.stackEntryPriorities
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectCueStacks(state: State) {
    routeApiRestCueStackTimeline(state)
    routeApiRestCueStackTimelineEdit(state)

    // GET /{projectId}/cue-stacks - List stacks + separators in show order, with cues + active cue info
    get<ProjectCueStacksResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val manager = state.show.cueStackManager
            val stacks = transaction(state.database) {
                DaoCueStack.find { DaoCueStacks.project eq project.id }
                    .orderBy(DaoCueStacks.sortOrder to SortOrder.ASC, DaoCueStacks.name to SortOrder.ASC)
                    .map { it.toCueStackDetails(isCurrentProject, manager) }
            }
            call.respond(stacks)
        }
    }

    // POST /{projectId}/cue-stacks - Create a stack or a separator (type=SEPARATOR), appended to order
    post<ProjectCueStacksResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot create cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<NewCueStack>()
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stackType = input.type ?: CueStackType.STACK.name
                val resolvedName = if (stackType == CueStackType.SEPARATOR.name) {
                    (input.label ?: input.name).ifBlank { "Separator" }
                } else {
                    input.name
                }
                val nextSort = input.sortOrder ?: ((project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                val stack = DaoCueStack.new {
                    name = resolvedName
                    this.project = project
                    loop = input.loop
                    type = stackType
                    label = input.label
                    sortOrder = nextSort
                }
                stack.toCueStackDetails(isCurrentProject = true, manager)
            }
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.Created, details)
        }
    }

    // POST /{projectId}/cue-stacks/reorder - Reorder the project's stacks + separators (show order)
    post<ProjectStacksReorderResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ReorderStacksRequest>()
            transaction(state.database) {
                for ((index, stackId) in request.stackIds.withIndex()) {
                    val stack = DaoCueStack.findById(stackId) ?: continue
                    if (stack.project.id == project.id) {
                        stack.sortOrder = index
                    }
                }
            }
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.OK)
        }
    }

    // GET /{projectId}/cue-stacks/{stackId} - Get stack details
    get<ProjectCueStackResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null
                stack.toCueStackDetails(isCurrentProject, manager)
            }

            if (details != null) {
                call.respond(details)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
        }
    }

    // PUT /{projectId}/cue-stacks/{stackId} - Update stack settings
    put<ProjectCueStackResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<NewCueStack>()
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null

                if (stack.type == CueStackType.SEPARATOR.name) {
                    // A separator only carries a display label; keep `name` in sync so the NOT NULL
                    // column stays populated and list ordering by name is stable.
                    val newLabel = (input.label ?: input.name)
                    stack.label = newLabel
                    if (newLabel.isNotBlank()) stack.name = newLabel
                } else {
                    stack.name = input.name
                    stack.loop = input.loop
                }

                stack.toCueStackDetails(isCurrentProject = true, manager)
            }

            if (details != null) {
                // Looping decides what follows the last entry.
                if (manager.isStackActive(resource.stackId)) manager.runState.publishRunState(state, resource.stackId)
                state.show.fixtures.cueStackListChanged()
                call.respond(details)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
        }
    }

    // DELETE /{projectId}/cue-stacks/{stackId} - Delete a stack or separator. Its cues stay, loose if
    // it was their last stack; ?deleteLooseCues=true deletes those with everything that names them
    // (cue model plan D17).
    delete<ProjectCueStackResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null
                deleteCueStackRows(state, project, stack, deleteLooseCues = resource.deleteLooseCues)
            }

            if (result != null) {
                announceCueStacksDeleted(state, result)
                call.respond(DeleteCueStackResponse(result.looseCueIds, result.deletion.cueIds.sorted()))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/reorder - Reorder the stack's entries
    post<CueStackReorderResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<ReorderCuesRequest>()
            val priorities = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId)
                    ?.takeIf { it.project.id == project.id }
                    ?: return@transaction null
                val before = cueEntryIds(stack)
                val current = entriesOf(stack)
                val byId = current.associateBy { it.id.value }
                // The named entries in the order given, then any the request left out where they
                // were — an entry of another stack, or an unknown id, is ignored.
                val named = request.entryIds.distinct().mapNotNull { byId[it] }
                val namedIds = named.mapTo(HashSet()) { it.id.value }
                writeEntryOrder(named + current.filter { it.id.value !in namedIds })
                // Auto numbers are derived from position, so the moved entries take new ones.
                renumberAutoEntries(stack, before)
                stackEntryPriorities(stack)
            }
            if (priorities != null) announceEntriesPlaced(state, resource.parent.stackId, priorities)
            call.respond(HttpStatusCode.OK)
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/add-cue - Add a cue to this stack: a new entry, so a cue
    // already in another stack (or this one) is shared, never moved (cue model plan D1, D7)
    post<CueStackAddCueResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<AddCueToStackRequest>()
            val manager = state.show.cueStackManager
            var numberTaken = false
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId)
                    ?.takeIf { it.project.id == project.id }
                    ?: return@transaction Triple("Stack not found", null, null)
                if (stack.type == CueStackType.SEPARATOR.name) return@transaction Triple("Cannot add cues to a separator", null, null)
                val cue = DaoCue.findById(request.cueId) ?: return@transaction Triple("Cue not found", null, null)
                if (cue.project.id != project.id) return@transaction Triple("Cue does not belong to project", null, null)
                val number = request.cueNumber?.takeIf { it.isNotEmpty() }
                number?.let { explicitNumberClash(stack, it) }?.let { numberTaken = true; return@transaction Triple(it, null, null) }

                // Every entry already in the stack, markers included — the insert below has to
                // place it among separators too, or they drift away from the cues they label.
                val existing = entriesOf(stack)

                val insertAt = if (request.insertByNumber) {
                    val parsed = number?.let { parseCueNumber(it) }
                        ?: return@transaction Triple(
                            "insertByNumber requires a cueNumber with a numeric component",
                            null,
                            null,
                        )

                    // Position the cue within its own prefix group; other groups sort
                    // independently, so their numbers say nothing about where this one belongs.
                    val group = existing.withIndex().mapNotNull { (index, other) ->
                        val otherParsed = other.cueNumber?.takeIf { !other.isMarker }?.let { parseCueNumber(it) }
                        if (otherParsed != null && otherParsed.prefix.equals(parsed.prefix, ignoreCase = true)) {
                            index to otherParsed
                        } else {
                            null
                        }
                    }
                    val insertAfter = group.lastOrNull { (_, other) -> compareWithinGroup(other, parsed) < 0 }
                    when {
                        insertAfter != null -> insertAfter.first + 1
                        group.isNotEmpty() -> group.first().first
                        // Nothing in this group yet — append.
                        else -> existing.size
                    }
                } else {
                    // `sortOrder` is a position in the stack's list, as it always read in practice.
                    request.sortOrder?.coerceIn(0, existing.size) ?: existing.size
                }

                placeCueInStack(stack, cue, cueNumber = number, position = insertAt)
                Triple(
                    null,
                    stack.toCueStackDetails(isCurrentProject = true, manager),
                    stackEntryPriorities(stack),
                )
            }

            val (error, details, priorities) = result
            if (error != null) {
                // A number another entry holds explicitly is a conflict, as on the entry PATCH and POST /cues.
                call.respond(if (numberTaken) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, ErrorResponse(error))
            } else {
                announceEntriesPlaced(state, resource.parent.stackId, priorities ?: emptyMap())
                call.respond(details!!)
            }
        }
    }

    // DELETE /{projectId}/cue-stacks/{stackId}/entries/{entryId} - Remove an entry from the stack.
    // Never deletes its cue, which is loose once its last entry goes; removing the live entry leaves
    // the stage alone until the stack's next GO (cue model plan §3.6, D16).
    delete<CueStackEntryResource> { resource ->
        withCurrentProject(state, resource.parent.parent.parent.projectId) { project ->
            val removal = transaction(state.database) {
                val entry = stackEntry(project, resource.parent.parent.stackId, resource.entryId) ?: return@transaction null
                removeEntries(state, project, listOf(entry))
            }
            if (removal == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Entry not found"))
            } else {
                announceEntriesRemoved(state, removal, resource.parent.parent.stackId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    // PATCH /{projectId}/cue-stacks/{stackId}/entries/{entryId} - An entry's number and follow (cue
    // model plan D2): its place, not its cue's content, so a shared cue numbers itself per stack.
    // Only the fields present are written.
    patch<CueStackEntryResource> { resource ->
        withCurrentProject(state, resource.parent.parent.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            var numberTaken = false
            val result = transaction(state.database) {
                val entry = stackEntry(project, resource.parent.parent.stackId, resource.entryId)
                    ?.takeIf { !it.isMarker }
                    ?: return@transaction null to "Entry not found"
                // A typed number is explicit and wins any auto number a sibling was given; clearing
                // it hands the entry back to the auto scheme, which fills it in again from position.
                // An unchanged number leaves the auto flag alone, so a save that round-trips the
                // current number cannot freeze an auto number as explicit.
                if ("cueNumber" in body) {
                    val incoming = body["cueNumber"].nullableString()?.takeIf { it.isNotEmpty() }
                    if (incoming != entry.cueNumber) {
                        incoming?.let { explicitNumberClash(entry.stack, it, entry.id.value) }
                            ?.let { numberTaken = true; return@transaction null to it }
                        setEntryNumber(entry, incoming)
                    }
                }
                if ("autoAdvance" in body) {
                    entry.autoAdvance = (body["autoAdvance"] as? JsonPrimitive)?.booleanOrNull
                        ?: return@transaction null to "autoAdvance must be true or false"
                }
                if ("autoAdvanceDelayMs" in body) entry.autoAdvanceDelay = body["autoAdvanceDelayMs"].nullableLong().asDuration()
                entry.toCueStackCueEntry(cueSceneryByCue(listOfNotNull(entry.cue?.id))) to null
            }
            val renumbered = if ("cueNumber" in body) result.first?.id else null
            val (row, error) = result
            when {
                row != null -> {
                    state.show.fixtures.cueStackListChanged()
                    state.show.fixtures.cueListChanged()
                    // A renumber moves the auto numbers after it too, which other cues' pads show.
                    if (renumbered != null) announceCuePadsChanged(state, cueIdsOfStack(state, resource.parent.parent.stackId))
                    call.respond(row)
                }
                error == "Entry not found" -> call.respond(HttpStatusCode.NotFound, ErrorResponse(error))
                // A number another entry holds explicitly, as the unique index would refuse it.
                numberTaken -> call.respond(HttpStatusCode.Conflict, ErrorResponse(error ?: "Cue number taken"))
                else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(error ?: "Failed to update entry"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/entries/{entryId}/move - Move an entry to the end of
    // another stack whole: the same row, so its uuid, number, follow and prompt-book anchor go with
    // it. A live entry leaves the stage alone, as a removal does (cue model plan D16).
    post<CueStackEntryMoveResource> { resource ->
        val entryResource = resource.parent
        val stackId = entryResource.parent.parent.stackId
        withCurrentProject(state, entryResource.parent.parent.parent.projectId) { project ->
            val request = call.receive<MoveEntryRequest>()
            val outcome = transaction(state.database) {
                moveEntryChecked(state, project, stackEntry(project, stackId, entryResource.entryId), request.toStackId)
            }
            when (outcome) {
                is EntryMoveOutcome.Refused -> call.respond(outcome.status, ErrorResponse(outcome.message))
                is EntryMoveOutcome.Moved -> {
                    announceEntryMoved(state, outcome.move)
                    call.respond(MoveEntryResponse(stackId = outcome.move.toStackId, entryId = outcome.move.entryId))
                }
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/markers - Add a marker entry
    post<CueStackMarkersResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<MarkerEntryRequest>()
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId)
                    ?.takeIf { it.project.id == project.id }
                    ?: return@transaction Triple(null, "Cue stack not found", null)
                if (stack.type == CueStackType.SEPARATOR.name) return@transaction Triple(null, "Cannot add a marker to a separator", null)
                val existing = entriesOf(stack)
                val at = request.sortOrder?.coerceIn(0, existing.size) ?: existing.size
                val marker = DaoCueStackEntry.new {
                    this.stack = stack
                    kind = CueStackEntryKind.MARKER.name
                    label = request.label.ifBlank { "Separator" }.take(255)
                    ordinal = 0.0
                }
                writeEntryOrder(existing.take(at) + marker + existing.drop(at), fresh = listOf(marker))
                // A marker counts in the positions a live cue's priority is derived from.
                Triple(marker.toCueStackCueEntry(emptyMap()), null, stackEntryPriorities(stack))
            }
            val (entry, error, priorities) = result
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(error ?: "Cue stack not found"))
            } else {
                state.show.cueStackManager.holds.restampEntries(priorities ?: emptyMap())
                state.show.fixtures.cueStackListChanged()
                call.respond(HttpStatusCode.Created, entry)
            }
        }
    }

    // PUT /{projectId}/cue-stacks/{stackId}/markers/{entryId} - Rename a marker entry
    put<CueStackMarkerResource> { resource ->
        withCurrentProject(state, resource.parent.parent.parent.projectId) { project ->
            val request = call.receive<MarkerEntryRequest>()
            val entry = transaction(state.database) {
                val marker = markerEntry(project, resource.parent.parent.stackId, resource.entryId) ?: return@transaction null
                marker.label = request.label.ifBlank { "Separator" }.take(255)
                marker.toCueStackCueEntry(emptyMap())
            }
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Marker not found"))
            } else {
                state.show.fixtures.cueStackListChanged()
                call.respond(entry)
            }
        }
    }

    // DELETE /{projectId}/cue-stacks/{stackId}/markers/{entryId} - Remove a marker entry
    delete<CueStackMarkerResource> { resource ->
        withCurrentProject(state, resource.parent.parent.parent.projectId) { project ->
            val removed = transaction(state.database) {
                val marker = markerEntry(project, resource.parent.parent.stackId, resource.entryId) ?: return@transaction null
                val stack = marker.stack
                val anchors = DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.entry eq marker.id }
                marker.delete()
                // The entries after it move up a place, and a live cue's priority is its place.
                anchors to stackEntryPriorities(stack)
            }
            if (removed == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Marker not found"))
            } else {
                val (removedAnchors, priorities) = removed
                state.show.cueStackManager.holds.restampEntries(priorities)
                state.show.fixtures.cueStackListChanged()
                if (removedAnchors > 0) state.show.fixtures.promptBookChanged()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/activate - Activate stack
    post<CueStackActivateResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot activate - not current project") { _ ->
            val request = try { call.receive<ActivateCueStackRequest>() } catch (_: Exception) { ActivateCueStackRequest() }
            val manager = state.show.cueStackManager

            try {
                // A bare /activate on an already-active stack short-circuits, like /show/activate:
                // it routes to activateAtFirstCue, so honouring the repeat would throw the playhead
                // back to cue 1 on a live rig. Only a request naming an entry is a deliberate re-fire.
                val activeEntryId = if (request.entryId == null) manager.getActiveEntryId(resource.parent.stackId) else null
                val activeCueId = manager.getActiveCueId(resource.parent.stackId)
                if (activeEntryId != null && activeCueId != null) {
                    val cueName = transaction(state.database) { DaoCue.findById(activeCueId)?.name } ?: ""
                    call.respond(CueStackActivateResponse(
                        stackId = resource.parent.stackId,
                        entryId = activeEntryId,
                        cueId = activeCueId,
                        cueName = cueName,
                        effectCount = 0,
                    ))
                    return@withCurrentProject
                }

                val result = if (request.entryId != null) {
                    manager.activateEntry(state, resource.parent.stackId, request.entryId)
                } else {
                    manager.activateAtFirstCue(state, resource.parent.stackId)
                }
                state.show.fixtures.cueStackListChanged()
                call.respond(result.toResponse())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to activate stack"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/deactivate - Deactivate stack
    post<CueStackDeactivateResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot deactivate - not current project") { _ ->
            val removedCount = state.show.cueStackManager.deactivateStack(resource.parent.stackId, state)
            state.show.fixtures.cueStackListChanged()
            call.respond(CueStackDeactivateResponse(stackId = resource.parent.stackId, removedCount = removedCount))
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/advance - Advance forward/backward
    post<CueStackAdvanceResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot advance - not current project") { _ ->
            val request = call.receive<AdvanceCueStackRequest>()
            val direction = try {
                CueStackManager.AdvanceDirection.valueOf(request.direction.uppercase())
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid direction: ${request.direction}"))
                return@withCurrentProject
            }

            try {
                val result = state.show.cueStackManager.advanceStack(state, resource.parent.stackId, direction)
                state.show.fixtures.cueStackListChanged()
                if (result != null) {
                    call.respond(result.toResponse())
                } else {
                    call.respond(CueStackDeactivateResponse(stackId = resource.parent.stackId, removedCount = 0))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to advance stack"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/go-to - Go to specific cue
    post<CueStackGoToResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot go-to - not current project") { _ ->
            val request = call.receive<GoToCueRequest>()

            try {
                val result = state.show.cueStackManager.goToEntry(state, resource.parent.stackId, request.entryId)
                state.show.fixtures.cueStackListChanged()
                call.respond(result.toResponse())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to go to cue"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/standby - Arm (or disarm) the next GO
    post<CueStackStandbyResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot arm - not current project") { _ ->
            val request = try { call.receive<SetStandbyRequest>() } catch (_: Exception) { SetStandbyRequest() }
            val manager = state.show.cueStackManager
            val stackId = resource.parent.stackId

            try {
                if (request.entryId != null) {
                    manager.runState.setStandby(state, stackId, request.entryId)
                } else {
                    manager.runState.clearStandby(state, stackId)
                }
                // No `cueStackListChanged` — the manager already broadcast the run state, and
                // this changes nothing about the stack collection.
                val next = manager.runState.effectiveNext(state, stackId)
                call.respond(
                    CueStackRunStateResponse(
                        stackId = stackId,
                        activeCueId = manager.getActiveCueId(stackId),
                        standbyCueId = manager.runState.getStandbyCueId(stackId),
                        nextCueId = next?.cueId,
                        activeEntryId = manager.getActiveEntryId(stackId),
                        standbyEntryId = manager.runState.getStandbyEntryId(stackId),
                        nextEntryId = next?.entryId,
                    )
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to arm cue"))
            }
        }
    }

    // GET /{projectId}/cue-stacks/{stackId}/preview - Compose a cue without firing it
    get<CueStackPreviewResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot preview - not current project") { _ ->
            try {
                when (val result = previewCueLook(state, resource.parent.stackId, resource.entryId)) {
                    null -> call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Nothing to preview - the stack has no next cue"),
                    )
                    else -> call.respond(result)
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to preview cue"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/sort-by-cue-number - Group-aware natural sort
    post<CueStackSortByNumberResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val manager = state.show.cueStackManager
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null

                val before = cueEntryIds(stack)
                val all = entriesOf(stack)
                val cueEntries = all.filter { !it.isMarker }

                // Each prefix group is sorted within the slots it already occupies, so groups
                // keep their relative placement. Markers and numbers with nothing to order by
                // ("A", blanks) never move — that's the replacement for the old rule that a cue
                // number had to *start* with a digit to participate, which excluded every
                // prefixed number like "S1-3".
                val sorted = groupAwareCueOrder(cueEntries) { it.cueNumber }.iterator()
                writeEntryOrder(all.map { if (it.isMarker) it else sorted.next() })

                // Counted before renumbering: that call is what fills blanks in, so reading these
                // afterwards would report zero unnumbered cues no matter what came in.
                val unparseable = cueEntries.count {
                    val number = it.cueNumber
                    !number.isNullOrEmpty() && parseCueNumber(number) == null
                }
                val unnumbered = cueEntries.count { it.cueNumber.isNullOrEmpty() }

                // The entries the sort moved take numbers from where they landed; the rest keep
                // theirs, so *Fix Order* moves an out-of-order cue rather than renumbering its
                // neighbours.
                renumberAutoEntries(stack, before)

                val details = stack.toCueStackDetails(isCurrentProject = true, manager)
                SortByNumberResponse(
                    updatedCues = details.cues,
                    pinnedCount = unparseable,
                    nullNumberCount = unnumbered,
                ) to stackEntryPriorities(stack)
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            } else {
                val (response, priorities) = result
                announceEntriesPlaced(state, resource.parent.stackId, priorities)
                call.respond(response)
            }
        }
    }
}

// ─── Resource classes ──────────────────────────────────────────────────

@Resource("/{projectId}/cue-stacks")
data class ProjectCueStacksResource(val projectId: String)

// Project-level stack reorder. The literal `/reorder` segment is preferred by Ktor over the
// sibling `/{stackId}` (which only matches an Int), so there is no routing collision.
@Resource("/reorder")
data class ProjectStacksReorderResource(val parent: ProjectCueStacksResource)

@Resource("/{stackId}")
data class ProjectCueStackResource(
    val parent: ProjectCueStacksResource,
    val stackId: Int,
    /** DELETE: also delete the cues the stack leaves in no stack (cue model plan D17). */
    val deleteLooseCues: Boolean = false,
)

@Resource("/reorder")
data class CueStackReorderResource(val parent: ProjectCueStackResource)

@Resource("/add-cue")
data class CueStackAddCueResource(val parent: ProjectCueStackResource)

@Resource("/markers")
data class CueStackMarkersResource(val parent: ProjectCueStackResource)

@Resource("/{entryId}")
data class CueStackMarkerResource(val parent: CueStackMarkersResource, val entryId: Int)

@Resource("/entries")
data class CueStackEntriesResource(val parent: ProjectCueStackResource)

@Resource("/{entryId}")
data class CueStackEntryResource(val parent: CueStackEntriesResource, val entryId: Int)

@Resource("/move")
data class CueStackEntryMoveResource(val parent: CueStackEntryResource)

@Resource("/activate")
data class CueStackActivateResource(val parent: ProjectCueStackResource)

@Resource("/deactivate")
data class CueStackDeactivateResource(val parent: ProjectCueStackResource)

@Resource("/advance")
data class CueStackAdvanceResource(val parent: ProjectCueStackResource)

@Resource("/go-to")
data class CueStackGoToResource(val parent: ProjectCueStackResource)

@Resource("/standby")
data class CueStackStandbyResource(val parent: ProjectCueStackResource)

@Resource("/preview")
data class CueStackPreviewResource(
    val parent: ProjectCueStackResource,
    /** The entry to preview (cue model plan P2); absent previews the stack's effective next (what GO would fire). */
    val entryId: Int? = null,
)

@Resource("/sort-by-cue-number")
data class CueStackSortByNumberResource(val parent: ProjectCueStackResource)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class NewCueStack(
    val name: String = "",
    val loop: Boolean = false,
    /** "STACK" (default) or "SEPARATOR". */
    val type: String? = null,
    /** Display text for a separator; ignored for a real stack. */
    val label: String? = null,
    /** Explicit position; when null the row is appended to the end of the project's order. */
    val sortOrder: Int? = null,
)

@Serializable
data class CueStackDetails(
    val id: Int,
    val name: String,
    val loop: Boolean,
    val sortOrder: Int,
    val type: String,
    val label: String?,
    val cues: List<CueStackCueEntry>,
    val activeCueId: Int?,
    /** The cue an operator has explicitly armed, if any. */
    val standbyCueId: Int?,
    /**
     * The cue the next GO fires — [standbyCueId] when set, else the positional next. Server-side
     * so a page load agrees with every other session; see `CueRunStateTracker.effectiveNext`.
     */
    val nextCueId: Int?,
    /**
     * The entries behind the three cue ids above (cue model plan P2): the live entry, the armed one
     * and the one the next GO fires. A cue twice in one stack (D7) is told apart only by these.
     */
    val activeEntryId: Int?,
    val standbyEntryId: Int?,
    val nextEntryId: Int?,
    val canEdit: Boolean,
    val canDelete: Boolean,
    /** The stack's *set* (stage-view plan session 8): held while it is live, under its cues. */
    val scenery: List<SceneryChangeDto> = emptyList(),
)

/**
 * One row of a stack's list — an **entry** (cue model plan §3.1). A cue entry carries its cue's
 * content summary beside the entry's own place (position, number, follow); a marker entry
 * (`cueType: "MARKER"`) carries only its label, as [name], and no cue.
 */
@Serializable
data class CueStackCueEntry(
    /** The cue this entry places; null for a marker, which is an entry and not a cue (D12). */
    val id: Int?,
    /** The entry's own id: what reorder, standby and go-to name (P2). */
    val entryId: Int,
    val name: String,
    /** The entry's position in its stack's list, from 0, markers counted. */
    val sortOrder: Int,
    /**
     * How many Look layers the cue carries.
     *
     * Was `presetCount`, counting `cue_preset_applications` — which retired in session 4. Renamed
     * rather than deleted because it is the count a collapsed cue row wants: a cue built entirely
     * from layers otherwise reads as empty in the Run list. **No client reads it yet** (the
     * frontend's `CueStackCueEntry` declares it and nothing consumes it, along with
     * `adHocEffectCount`), so treat it as available rather than as load-bearing.
     */
    val layerCount: Int,
    val adHocEffectCount: Int,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    /** True when [cueNumber] was derived from position rather than typed by the operator. */
    val cueNumberAuto: Boolean = false,
    val notes: String? = null,
    /** `STANDARD` for a cue entry, `MARKER` for a marker entry — the entry's kind, as the wire says it. */
    val cueType: String = "STANDARD",
    /**
     * The cue's own scenery changes — what it moves on GO, each on its clock (scenery-programmer
     * plan D13, D14): the cue table's Scenery column, the Prompt Book's margin glyph and its rail
     * cards read every cue's at once from here, rather than fetching each cue. Never what the cue
     * only tracks (`CueDetails.trackedScenery`). Batched in one query per stack
     * ([cueSceneryByCue]). `@EncodeDefault(ALWAYS)` so a cue with none arrives as "no scenery"
     * rather than as a field an older desk never sent; the client treats it as optional all the
     * same.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val scenery: List<SceneryChangeDto> = emptyList(),
)

@Serializable
data class ReorderCuesRequest(
    /** The stack's entries in their new order (cue model plan P2) — markers included. */
    val entryIds: List<Int>,
)

/** A marker entry's label, and where a new one goes (a position in the list; absent appends). */
@Serializable
data class MarkerEntryRequest(
    val label: String = "",
    val sortOrder: Int? = null,
)

@Serializable
data class ReorderStacksRequest(
    val stackIds: List<Int>,
)

@Serializable
data class AddCueToStackRequest(
    val cueId: Int,
    /** Where the new entry goes: a position in the list; absent appends. */
    val sortOrder: Int? = null,
    /** Place the entry by [cueNumber] among its prefix group instead of at [sortOrder]. */
    val insertByNumber: Boolean = false,
    /** The new entry's explicit number; absent numbers it from where it lands. */
    val cueNumber: String? = null,
)

@Serializable
data class MoveEntryRequest(val toStackId: Int)

/** Where the entry now is: the same entry, in [stackId]. */
@Serializable
data class MoveEntryResponse(val stackId: Int, val entryId: Int)

@Serializable
data class ActivateCueStackRequest(
    /** The entry to start at (cue model plan P2); absent starts at the armed standby, else the first cue entry. */
    val entryId: Int? = null,
)

@Serializable
data class AdvanceCueStackRequest(
    val direction: String,
)

@Serializable
data class GoToCueRequest(
    /** The entry to go to (cue model plan P2). */
    val entryId: Int,
)

@Serializable
data class SetStandbyRequest(
    /** The entry to arm (cue model plan P2); null disarms and leaves the positional next on deck. */
    val entryId: Int? = null,
)

@Serializable
data class CueStackRunStateResponse(
    val stackId: Int,
    val activeCueId: Int?,
    val standbyCueId: Int?,
    val nextCueId: Int?,
    val activeEntryId: Int?,
    val standbyEntryId: Int?,
    val nextEntryId: Int?,
)

@Serializable
data class CueStackActivateResponse(
    val stackId: Int,
    val entryId: Int,
    val cueId: Int,
    val cueName: String,
    val effectCount: Int,
)

private fun CueStackManager.ActivateResult.toResponse(): CueStackActivateResponse =
    CueStackActivateResponse(stackId = stackId, entryId = entryId, cueId = cueId, cueName = cueName, effectCount = effectCount)

@Serializable
data class CueStackDeactivateResponse(
    val stackId: Int,
    val removedCount: Int,
)

@Serializable
data class SortByNumberResponse(
    val updatedCues: List<CueStackCueEntry>,
    val pinnedCount: Int,
    val nullNumberCount: Int,
)

// The old flat `naturalSortKey` / `naturalCompare` / `CueNumberComparator` trio lived here.
// They compared cue numbers as one undifferentiated token stream, which is what forced the
// "must start with a digit" participation rule — a prefixed number like "S1-3" would otherwise
// sort against "T2-1" as if the prefixes were meaningful ordering data. `cueNumbering.kt` now
// models prefix / decimal run / suffix separately and compares only within a group.

// ─── Entity helpers ────────────────────────────────────────────────────

private fun DaoCueStack.toCueStackDetails(
    isCurrentProject: Boolean,
    manager: CueStackManager,
): CueStackDetails {
    val entries = entriesOf(this)
    val cueIds = entries.mapNotNull { it.cueId }
    val sceneryByCue = cueSceneryByCue(cueIds.map { EntityID(it, DaoCues) })
    val cuesById = if (cueIds.isEmpty()) emptyMap() else DaoCue.find { DaoCues.id inList cueIds }.associateBy { it.id.value }
    val orderedCues = entries.mapIndexed { position, entry ->
        entry.toCueStackCueEntry(sceneryByCue, position, entry.cueId?.let(cuesById::get))
    }
    val ordered = orderedCues.mapNotNull { row -> row.id?.let { CueEntryRef(row.entryId, it) } }
    // The list overload: the entries are already loaded here, and the rules stay in the tracker
    // rather than being re-derived from `orderedCues` by the client.
    val next = manager.runState.effectiveNext(id.value, ordered, loop)
    return CueStackDetails(
        id = id.value,
        name = name,
        loop = loop,
        sortOrder = sortOrder,
        type = type,
        label = label,
        cues = orderedCues,
        activeCueId = manager.getActiveCueId(id.value),
        standbyCueId = manager.runState.getStandbyCueId(id.value),
        nextCueId = next?.cueId,
        activeEntryId = manager.getActiveEntryId(id.value),
        standbyEntryId = manager.runState.getStandbyEntryId(id.value),
        nextEntryId = next?.entryId,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
        scenery = stackSceneryOf(id).map { it.toDto() },
    )
}

/**
 * One entry as a row of its stack's list. [position] is its index in the list, read once by the
 * caller rather than per row; [cue] likewise, when the caller has loaded the stack's cues at once.
 */
internal fun DaoCueStackEntry.toCueStackCueEntry(
    sceneryByCue: Map<Int, List<DaoCueSceneryRow>>,
    position: Int = entriesOfStack(stack.id).indexOfFirst { it.id == id }.coerceAtLeast(0),
    cue: DaoCue? = this.cue,
): CueStackCueEntry {
    if (isMarker || cue == null) {
        return CueStackCueEntry(
            id = null,
            entryId = id.value,
            name = displayName,
            sortOrder = position,
            layerCount = 0,
            adHocEffectCount = 0,
            cueType = CueType.MARKER.name,
        )
    }
    return CueStackCueEntry(
        id = cue.id.value,
        entryId = id.value,
        name = cue.name,
        sortOrder = position,
        layerCount = cue.layers.count().toInt(),
        adHocEffectCount = cue.adHocEffects.count().toInt(),
        autoAdvance = autoAdvance,
        autoAdvanceDelayMs = autoAdvanceDelay?.toMillis(),
        fadeDurationMs = cue.fadeDuration?.toMillis(),
        fadeCurve = cue.fadeCurve,
        cueNumber = cueNumber,
        cueNumberAuto = cueNumberAuto,
        notes = cue.notes,
        cueType = CueType.STANDARD.name,
        scenery = sceneryByCue[cue.id.value].orEmpty().map { it.toDto() },
    )
}

/** The cues [stackId]'s entries place, in their own transaction. */
internal fun cueIdsOfStack(state: State, stackId: Int): List<Int> = transaction(state.database) {
    entriesOfStack(EntityID(stackId, DaoCueStacks)).mapNotNull { it.cueId }.distinct()
}

/** Entry [entryId] of stack [stackId] in [project], a cue's or a marker, or null. Inside a transaction. */
private fun stackEntry(project: DaoProject, stackId: Int, entryId: Int): DaoCueStackEntry? {
    val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == project.id } ?: return null
    return DaoCueStackEntry.findById(entryId)?.takeIf { it.stackId == stack.id.value }
}

/** What [moveEntry] did, for the runtime and the broadcasts once the transaction has committed. */
internal data class EntryMove(
    val entryId: Int,
    val fromStackId: Int,
    val toStackId: Int,
    /** A prompt-book anchor stands on the entry: the book shows its new place. */
    val anchored: Boolean,
    /** Both stacks' entries' priorities; a hold is restamped only within its own stack's band. */
    val priorities: Map<Int, Int>,
)

/**
 * Move cue [entry] to the end of [target], keeping the row: its uuid, explicit number, follow and
 * prompt-book anchor stay with it, and both stacks' auto numbers follow the change. A live stack
 * whose live entry it was keeps holding the cue and plays on from where the entry stood, as a
 * removal does (D16), its hold at that stack's priority until its next GO (`restampEntries` restamps
 * a hold only within its own stack's band). The caller has
 * checked that an explicit number is free in [target]. Inside the caller's transaction.
 */
internal fun moveEntry(state: State, project: DaoProject, entry: DaoCueStackEntry, target: DaoCueStack): EntryMove {
    val source = entry.stack
    val manager = state.show.cueStackManager
    val current = project.id == state.projectManager.currentProject.id
    val beforeSource = cueEntryIds(source)
    val beforeTarget = cueEntryIds(target)
    if (current) {
        val list = entriesOf(source)
        val survivors = list.mapNotNullTo(HashSet()) { e -> e.id.value.takeIf { it != entry.id.value } }
        manager.detachEntry(source.id.value, entry.id.value, EntryPlace.of(entry, list, survivors))
    }
    // An explicit number goes with it, taken from any auto entry there that holds it; an auto number
    // is source's, so the entry is labelled again where it lands.
    if (!entry.cueNumberAuto && !entry.cueNumber.isNullOrEmpty()) {
        releaseAutoNumber(target, entry.cueNumber, exceptEntryId = entry.id.value)
    } else {
        entry.cueNumber = null
        flushPendingEntryWrites()
    }
    val existing = entriesOf(target)
    entry.stack = target
    writeEntryOrder(existing + entry, fresh = listOf(entry))
    renumberAutoEntries(source, beforeSource)
    renumberAutoEntries(target, beforeTarget)
    val priorities = if (current) stackEntryPriorities(source) + stackEntryPriorities(target) else emptyMap()
    return EntryMove(
        entryId = entry.id.value,
        fromStackId = source.id.value,
        toStackId = target.id.value,
        anchored = !DaoPromptBookAnchor.find { DaoPromptBookAnchors.entry eq entry.id }.empty(),
        priorities = priorities,
    )
}

/**
 * What placing, reordering or sorting cue entries of [stackId] owes once its transaction has committed:
 * the holds restamped from [priorities] (a live cue's priority is its place), the stack's run state
 * when it is active (its next entry may have moved), the lists, and the pads of the stack's cues,
 * whose auto-numbered entries may have taken new numbers. Every handler that places a cue entry in a
 * stack of the current project ends here.
 */
internal fun announceEntriesPlaced(state: State, stackId: Int, priorities: Map<Int, Int>) {
    val manager = state.show.cueStackManager
    manager.holds.restampEntries(priorities)
    if (manager.isStackActive(stackId)) manager.runState.publishRunState(state, stackId)
    state.show.fixtures.cueStackListChanged()
    state.show.fixtures.cueListChanged()
    announceCuePadsChanged(state, cueIdsOfStack(state, stackId))
}

/** What [moveEntryChecked] decided. */
internal sealed interface EntryMoveOutcome {
    data class Moved(val move: EntryMove) : EntryMoveOutcome
    data class Refused(val status: HttpStatusCode, val message: String) : EntryMoveOutcome
}

/**
 * *Move to stack…* for [entry] — a cue entry of [project], or null when the caller found none —
 * into stack [toStackId], refused before anything moves: a marker or a missing entry, the same
 * stack, a separator, or an explicit number [toStackId] already holds. The REST route and
 * `move_cue_to_stack` both come here. Inside the caller's transaction; [announceEntryMoved] once it
 * commits.
 */
internal fun moveEntryChecked(state: State, project: DaoProject, entry: DaoCueStackEntry?, toStackId: Int): EntryMoveOutcome {
    fun refused(status: HttpStatusCode, message: String) = EntryMoveOutcome.Refused(status, message)
    val moving = entry?.takeIf { !it.isMarker } ?: return refused(HttpStatusCode.NotFound, "Entry not found")
    if (toStackId == moving.stackId) return refused(HttpStatusCode.BadRequest, "The entry is already in that stack")
    val target = DaoCueStack.findById(toStackId)?.takeIf { it.project.id == project.id }
        ?: return refused(HttpStatusCode.BadRequest, "Stack not found")
    if (target.type == CueStackType.SEPARATOR.name) return refused(HttpStatusCode.BadRequest, "Cannot add cues to a separator")
    val number = moving.cueNumber?.takeIf { !moving.cueNumberAuto && it.isNotEmpty() }
    number?.let { explicitNumberClash(target, it) }?.let { return refused(HttpStatusCode.Conflict, it) }
    return EntryMoveOutcome.Moved(moveEntry(state, project, moving, target))
}

/** The runtime half of [moveEntry] and its broadcasts, once its transaction has committed. */
internal fun announceEntryMoved(state: State, move: EntryMove) {
    val manager = state.show.cueStackManager
    manager.holds.restampEntries(move.priorities)
    manager.runState.clearStandbyForEntries(state, setOf(move.entryId))
    for (id in listOf(move.fromStackId, move.toStackId)) {
        if (manager.isStackActive(id)) manager.runState.publishRunState(state, id)
    }
    state.show.fixtures.cueStackListChanged()
    state.show.fixtures.cueListChanged()
    if (move.anchored) state.show.fixtures.promptBookChanged()
    announceCuePadsChanged(state, cueIdsOfStack(state, move.fromStackId) + cueIdsOfStack(state, move.toStackId))
}

/** Marker entry [entryId] of stack [stackId] in [project], or null. Inside a transaction. */
private fun markerEntry(project: DaoProject, stackId: Int, entryId: Int): DaoCueStackEntry? {
    val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == project.id } ?: return null
    return DaoCueStackEntry.findById(entryId)?.takeIf { it.stackId == stack.id.value && it.isMarker }
}

/**
 * What deleting a cue stack did beyond the stack: the cues it left in no stack, and — when asked —
 * the deletion of those ([deletion]).
 */
internal data class DeletedCueStackRows(
    /** Every cue the stack placed: their pads' faces lose a place. */
    val cueIds: List<Int>,
    val removedAnchors: Int,
    val entryIds: Set<Int>,
    val looseCueIds: List<Int>,
    val deletion: CueDeletion,
)

/**
 * Delete [stack] of [project]: its entries (with their anchors) and its set, never its cues (cue
 * model plan D1). A cue whose last entry was here is left loose, or with [deleteLooseCues] deleted
 * as a forced cue delete would (D17). In the current project the stack is deactivated first; another
 * project's never ran, and deactivating it would still disarm the desk and recompute the live
 * scenery. The project's playhead is cleared if it pointed here. Inside the caller's transaction;
 * [announceCueStacksDeleted] after it commits.
 */
internal fun deleteCueStackRows(
    state: State,
    project: DaoProject,
    stack: DaoCueStack,
    deleteLooseCues: Boolean = false,
): DeletedCueStackRows {
    if (project.id == state.projectManager.currentProject.id) {
        state.show.cueStackManager.deactivateStack(stack.id.value, state)
        state.show.cueStackManager.forgetStack(stack.id.value)
    }
    if (project.activeStackId == stack.id.value) {
        project.activeStackId = null
    }

    val entries = entriesOf(stack)
    val cueIds = entries.mapNotNull { it.cueId }.distinct()
    var removedAnchors = 0
    entries.forEach { entry ->
        removedAnchors += DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.entry eq entry.id }
        entry.delete()
    }
    deleteStackScenery(stack.id)
    stack.delete()

    val stillPlaced = if (cueIds.isEmpty()) emptySet() else
        DaoCueStackEntries.select(DaoCueStackEntries.cue)
            .where { DaoCueStackEntries.cue inList cueIds }
            .mapNotNullTo(HashSet()) { it[DaoCueStackEntries.cue]?.value }
    val loose = cueIds.filter { it !in stillPlaced }
    val deletion = if (deleteLooseCues) deleteCues(state, project, loose.mapNotNull { DaoCue.findById(it) }) else CueDeletion.NONE
    return DeletedCueStackRows(
        cueIds = cueIds,
        removedAnchors = removedAnchors,
        entryIds = entries.mapTo(HashSet()) { it.id.value },
        looseCueIds = if (deleteLooseCues) emptyList() else loose,
        deletion = deletion,
    )
}

/** The runtime half and the broadcasts a stack delete owes, once its transaction has committed. */
internal fun announceCueStacksDeleted(state: State, deleted: DeletedCueStackRows) {
    state.show.programmerStore.forgetIncludedEntries(deleted.entryIds)
    afterCuesDeleted(state, deleted.deletion)
    announceCuePadsChanged(state, deleted.cueIds - deleted.deletion.cueIds)
    state.show.fixtures.cueStackListChanged()
    state.show.fixtures.cueListChanged()
    if (deleted.removedAnchors > 0) state.show.fixtures.promptBookChanged()
}
