package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntries
import uk.me.cormack.lighting7.models.DaoPaletteEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.includedTargetDto
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("programmerPaletteRecord")

/*
 * **The filename is now wider than the contents, deliberately for one session.** Recording the
 * programmer into a palette is gone — its destination was `DaoPalette`, which no consumer resolves
 * through any more, and `routes/lookRecord.kt` replaces it. What is left here is two things:
 *
 * - **Include, for a Look and for a palette**, sharing one body in [includeExpandedIntoProgrammer].
 *   That half survives the retirement entirely; only its palette wrapper goes.
 * - **The palette arm of Mode A Update** ([updateIncludedPalette] and [writeRecordingIntoPalette]),
 *   reachable only by a caller that names `paletteId` explicitly. The desk never does, and the
 *   Looks migration leaves no palette rows behind — so this is dead in practice already.
 *
 * Renaming the file now and again when session 4 deletes `models/palettes.kt` would be two renames
 * for one outcome, so the name waits for the deletion it belongs to. Read it as
 * "programmer include, plus the palette write-back still awaiting retirement".
 */

internal data class PaletteWriteOutcome(
    val paletteId: Int,
    val paletteUuid: java.util.UUID,
    val written: Int,
    val removed: Int,
)

/**
 * Apply a collapsed recording to a palette's entries.
 *
 * - CREATE / UPDATE_EXISTING replace the contents outright.
 * - MERGE upserts the recorded rows and leaves everything else alone — the console re-record.
 * - REMOVE deletes the rows the recording names, values ignored.
 *
 * Recorded values are always literals: a programmer entry that was itself a ref is flattened,
 * because a palette entry holding a ref would make resolution recursive (and the entry write
 * boundary rejects it anyway).
 *
 * Must be called inside a transaction.
 */
internal fun writeRecordingIntoPalette(
    palette: DaoPalette,
    collapsed: CollapsedAssignments,
    mode: RecordMode,
): PaletteWriteOutcome {
    var written = 0
    var removed = 0

    fun key(targetType: String, targetKey: String, propertyName: String) =
        Triple(targetType, targetKey, canonicalPropertyName(propertyName))

    val existingByKey = palette.entries
        .orderBy(DaoPaletteEntries.sortOrder to SortOrder.ASC)
        .associateBy { key(it.targetType, it.targetKey, it.propertyName) }

    when (mode) {
        RecordMode.CREATE, RecordMode.UPDATE_EXISTING -> {
            palette.entries.forEach { it.delete() }
            removed = if (mode == RecordMode.CREATE) 0 else existingByKey.size
            collapsed.rows.forEachIndexed { index, row ->
                DaoPaletteEntry.new {
                    this.palette = palette
                    targetType = row.targetType
                    targetKey = row.targetKey
                    propertyName = canonicalPropertyName(row.propertyName)
                    value = row.value
                    sortOrder = index
                }
                written++
            }
        }

        RecordMode.MERGE -> {
            var nextSort = (existingByKey.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (row in collapsed.rows) {
                val existing = existingByKey[key(row.targetType, row.targetKey, row.propertyName)]
                if (existing != null) {
                    existing.value = row.value
                } else {
                    DaoPaletteEntry.new {
                        this.palette = palette
                        targetType = row.targetType
                        targetKey = row.targetKey
                        propertyName = canonicalPropertyName(row.propertyName)
                        value = row.value
                        sortOrder = nextSort++
                    }
                }
                written++
            }
        }

        RecordMode.REMOVE -> {
            for (row in collapsed.rows) {
                existingByKey[key(row.targetType, row.targetKey, row.propertyName)]?.let {
                    it.delete()
                    removed++
                }
            }
        }
    }

    return PaletteWriteOutcome(palette.id.value, palette.uuid, written, removed)
}


/**
 * Expand a selection of fixture and group targets into the fixture keys Record filters on.
 * Unknown targets contribute nothing — the caller reports an empty result as a 400.
 */
internal fun expandTargetsToFixtureKeys(state: State, targets: List<CueTargetDto>): Set<String> {
    val out = LinkedHashSet<String>()
    for (target in targets) {
        when (val ref = TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture -> out += ref.key
            is TargetRef.Group -> {
                val members = runCatching {
                    state.show.fixtures.untypedGroup(ref.key).fixtures
                        .filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()
                }.getOrNull() ?: continue
                members.forEach { out += it.key }
            }
            null -> continue
        }
    }
    return out
}

/**
 * Mode A Update where the include target is a palette: write back what changed since Include.
 *
 * The "only what changed" rule is what keeps a palette's untouched entries untouched, exactly as it
 * does for a cue — Include stakes an INCLUDE slot per entry, and anything still matching it was not
 * edited. MERGE rather than replace, so entries outside the current selection or mask survive.
 */
internal suspend fun RoutingContext.updateIncludedPalette(
    state: State,
    project: DaoProject,
    includeTarget: uk.me.cormack.lighting7.fx.IncludedTarget,
    mask: Set<PropertyMaskGroup>?,
) {
    val paletteId = includeTarget.paletteId!!
    val located = transaction(state.database) {
        DaoPalette.findById(paletteId)?.takeIf { it.project.id == project.id }?.let {
            Triple(it.name, it.type, it.paletteType)
        }
    }
    if (located == null) {
        // Stale target: the palette was deleted since Include. Clear it so the indicator stops
        // offering it, and say so rather than silently writing nothing.
        state.show.programmerStore.clearIncludeTargetForPalette(paletteId)
        call.respond(
            HttpStatusCode.Conflict,
            ProgrammerConflictResponse(
                "The palette that was included no longer exists in this project.",
                CODE_INCLUDE_TARGET_GONE,
                paletteId,
            ),
        )
        return
    }
    val (paletteName, paletteTypeName, paletteType) = located

    // The palette's own type narrows the mask further — an Update can't put a position into a
    // colour palette even if the operator's mask allowed positions through.
    val effectiveMask = if (paletteType == null) mask else (mask?.intersect(setOf(paletteType)) ?: setOf(paletteType))
    val (changed, skips) = changedSinceInclude(state, effectiveMask)
    val collapsed = collapseRecordingToAssignments(changed, state.show.fixtures, preserveRefs = false)

    val outcome = transaction(state.database) {
        writeRecordingIntoPalette(DaoPalette.findById(paletteId)!!, collapsed, RecordMode.MERGE)
    }
    val republish = republishForLookEdit(state, outcome.paletteUuid)
    state.show.fixtures.lookListChanged()

    logger.info(
        "update-palette '{}': {} entr{} written, {} programmer key(s) refreshed, {} cue(s) republished",
        paletteName, outcome.written, if (outcome.written == 1) "y" else "ies",
        republish.programmerKeysRefreshed, republish.cuesRepublished.size,
    )
    call.respond(
        ProgrammerUpdateResponse(
            applied = outcome.written > 0,
            mode = "A",
            paletteResult = ProgrammerPaletteUpdateResult(
                paletteId = paletteId,
                paletteName = paletteName,
                paletteType = paletteTypeName,
                entriesWritten = outcome.written,
                programmerKeysRefreshed = republish.programmerKeysRefreshed,
                cuesRepublished = republish.cuesRepublished,
            ),
            skipped = skips.map { it.toDto() },
        )
    )
}

/**
 * Load a palette's entries into the programmer as the edit buffer, and mark it as the include
 * target so a bare Update writes back to it.
 *
 * A thin wrapper over [includeExpandedIntoProgrammer] — the resolution happens against the
 * palette's **uuid**, which the Looks migration preserved, so this reads the migrated Look.
 */
internal fun includePaletteIntoProgrammer(
    state: State,
    palette: DaoPalette,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
): PaletteIncludeOutcome = includeExpandedIntoProgrammer(
    state, palette.uuid, mask, fadeMs,
    uk.me.cormack.lighting7.fx.IncludedTarget.palette(palette.id.value, palette.uuid),
)

/**
 * Load a Look's rows into the programmer as the edit buffer.
 *
 * **One-way for now.** Update-back is not wired to Looks — `updateIncludedPalette` writes into
 * `DaoPalette`, which no consumer reads any more — so the client disables Update for a `LOOK`
 * include target rather than being allowed to write rows nothing sees. The record rewrite is what
 * closes that.
 *
 * Only bound rows arrive: a deferred row has no target of its own, and the programmer has no layer
 * to take one from until the programmer becomes a layer stack. `LookRegistry.expanded` already
 * drops them.
 */
internal fun includeLookIntoProgrammer(
    state: State,
    lookId: Int,
    lookUuid: java.util.UUID,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
): PaletteIncludeOutcome = includeExpandedIntoProgrammer(
    state, lookUuid, mask, fadeMs,
    uk.me.cormack.lighting7.fx.IncludedTarget.look(lookId, lookUuid),
)

/**
 * The shared body: expand [sourceUuid] through [uk.me.cormack.lighting7.fx.LookRegistry], write the
 * literals into the programmer, and record [includedTarget] so Update knows what it is looking at.
 *
 * Writes **[ProgrammerValue.Hard]** slots, not refs: you are editing the source's own contents, and
 * a slot referencing the thing it is about to rewrite is meaningless. Contrast Include-a-cue,
 * which does write refs — there the reference is part of what the cue means.
 *
 * Group rows expand to members, and a member the source covers directly wins over the group row,
 * matching how it resolves at compose time.
 */
internal fun includeExpandedIntoProgrammer(
    state: State,
    sourceUuid: java.util.UUID,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
    includedTarget: uk.me.cormack.lighting7.fx.IncludedTarget,
): PaletteIncludeOutcome {
    val expanded = state.show.lookRegistry.expanded(sourceUuid)
        ?: return PaletteIncludeOutcome(0, emptyList(), emptyList())

    val writes = ArrayList<uk.me.cormack.lighting7.fx.FxEngine.ProgrammerPropertyWrite>()
    val skips = ArrayList<RecordSkip>()
    val fixtureKeys = LinkedHashSet<String>()

    // Which group each member was covered by, so the slot keeps the operator's group shape and a
    // later Record can collapse back to a group row.
    val groupHints = HashMap<Pair<String, String>, String>()
    for (entry in expanded.snapshot.rows) {
        val group = entry.target as? TargetRef.Group ?: continue
        val members = runCatching {
            state.show.fixtures.untypedGroup(group.key).fixtures
                .filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()
        }.getOrNull() ?: continue
        for (member in members) {
            groupHints[member.key to canonicalPropertyName(entry.propertyName)] = group.key
        }
    }

    for ((fixtureKey, byProperty) in expanded.byFixture) {
        val fixture = runCatching { state.show.fixtures.untypedGroupableFixture(fixtureKey) }.getOrNull()
        if (fixture == null) {
            skips += RecordSkip(fixtureKey, reason = RecordSkipReason.MISSING_FIXTURE)
            continue
        }
        for ((propertyName, literal) in byProperty) {
            val group = uk.me.cormack.lighting7.fx.maskGroupForProperty(fixture, propertyName)
            if (group == null) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
                continue
            }
            if (!uk.me.cormack.lighting7.fx.maskAllows(mask, group)) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MASKED_OUT)
                continue
            }
            val category = uk.me.cormack.lighting7.fx.PropertyChannelWriter
                .resolveProperty(fixture, propertyName)?.category
                ?: uk.me.cormack.lighting7.fixture.PropertyCategory.OTHER
            val value = uk.me.cormack.lighting7.fx.CueAssignmentResolver
                .parseAssignmentValue(category, propertyName, literal)
            if (value == null) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
                continue
            }
            writes += uk.me.cormack.lighting7.fx.FxEngine.ProgrammerPropertyWrite(
                fixture, propertyName, value,
                sourceGroup = groupHints[fixtureKey to propertyName],
            )
            fixtureKeys += fixtureKey
        }
    }

    if (writes.isNotEmpty()) {
        // One batched write, as Include-a-cue does: a large palette is hundreds of properties and
        // per-property publishing would visibly stutter the rig.
        state.show.fxEngine.writeProgrammerProperties(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.INCLUDE, writes, fadeMs = fadeMs,
        )
    }
    state.show.programmerStore.lastIncludedTarget = includedTarget

    return PaletteIncludeOutcome(writes.size, fixtureKeys.toList(), skips)
}

internal data class PaletteIncludeOutcome(
    val entriesWritten: Int,
    val fixtureKeys: List<String>,
    val skipped: List<RecordSkip>,
)

/** `POST /programmer/include` with a `lookId`: load a Look's bound rows in as the edit buffer. */
internal suspend fun RoutingContext.handleIncludeLook(
    state: State,
    request: ProgrammerIncludeRequest,
    mask: Set<PropertyMaskGroup>?,
) {
    withCurrentProject(state, request.projectId, { p ->
        "Cannot include from project '${p.name}' — only the current project can be modified"
    }) { project ->
        val look = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoLook.findById(request.lookId!!)
                ?.takeIf { it.project.id == project.id }
                ?.let { it.id.value to (it.name to it.uuid) }
        }
        if (look == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Look not found in current project"))
            return@withCurrentProject
        }
        val (lookId, nameAndUuid) = look
        val (name, uuid) = nameAndUuid

        val outcome = includeLookIntoProgrammer(state, lookId, uuid, mask, request.fadeMs ?: 0)
        call.respond(
            ProgrammerIncludeResponse(
                kind = uk.me.cormack.lighting7.fx.IncludedTarget.Kind.LOOK.name,
                lookId = lookId,
                name = name,
                entriesWritten = outcome.entriesWritten,
                fixtureKeys = outcome.fixtureKeys,
                // A Look's group rows expand to members before they reach the programmer, so there
                // is no group-shaped selection to hand back.
                groupKeys = emptyList(),
                fxSpawned = 0,
                fxAlreadyRunning = 0,
                fxTimedSkipped = 0,
                lastIncluded = includedTargetDto(state, state.show.programmerStore.lastIncludedTarget),
                skipped = outcome.skipped.map { it.toDto() },
                warnings = if (outcome.entriesWritten == 0) {
                    // A fully-deferred Look has nothing bound to stage, and silently writing
                    // nothing reads as a broken button.
                    listOf("'$name' has no rows naming a fixture or group, so nothing was staged.")
                } else {
                    emptyList()
                },
            ),
        )
    }
}

/** `POST /programmer/include` with a `paletteId`: load a palette in as the edit buffer. */
internal suspend fun RoutingContext.handleIncludePalette(
    state: State,
    request: ProgrammerIncludeRequest,
    mask: Set<PropertyMaskGroup>?,
) {
    withCurrentProject(state, request.projectId, { p ->
        "Cannot include from project '${p.name}' — only the current project can be modified"
    }) { project ->
        val palette = transaction(state.database) {
            DaoPalette.findById(request.paletteId!!)?.takeIf { it.project.id == project.id }
        }
        if (palette == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Palette not found in current project"))
            return@withCurrentProject
        }
        val name = transaction(state.database) { palette.name }

        val outcome = includePaletteIntoProgrammer(state, palette, mask, request.fadeMs ?: 0)
        call.respond(
            ProgrammerIncludeResponse(
                kind = uk.me.cormack.lighting7.fx.IncludedTarget.Kind.PALETTE.name,
                paletteId = request.paletteId,
                name = name,
                entriesWritten = outcome.entriesWritten,
                fixtureKeys = outcome.fixtureKeys,
                // A palette's group rows expand to members before they reach the programmer, so
                // there is no group-shaped selection to hand back.
                groupKeys = emptyList(),
                fxSpawned = 0,
                fxAlreadyRunning = 0,
                fxTimedSkipped = 0,
                lastIncluded = includedTargetDto(state, state.show.programmerStore.lastIncludedTarget),
                skipped = outcome.skipped.map { it.toDto() },
            ),
        )
    }
}
