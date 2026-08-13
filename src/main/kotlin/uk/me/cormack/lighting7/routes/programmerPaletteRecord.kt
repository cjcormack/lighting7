package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntries
import uk.me.cormack.lighting7.models.DaoPaletteEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("programmerPaletteRecord")

@Resource("/record-palette")
internal class ProgrammerRecordPaletteResource

@Serializable
internal data class ProgrammerRecordPaletteRequest(
    val projectId: String,
    /** [RecordMode] name. CREATE makes a new palette; the rest need [paletteId]. */
    val mode: String = "CREATE",
    /** [PaletteType] name. Required on CREATE; must match the existing palette otherwise. */
    val type: String? = null,
    val paletteId: Int? = null,
    val name: String? = null,
    val notes: String? = null,
    /** [RecordSource] name. Defaults to TOUCHED — what the operator actually edited. */
    val source: String = "TOUCHED",
    /**
     * The operator's selection. Groups are expanded server-side.
     *
     * Strongly recommended: a palette recorded from the whole programmer captures every head the
     * programmer happens to hold, which is almost never what "Warm Amber" is meant to mean.
     */
    val targets: List<CueTargetDto>? = null,
)

@Serializable
internal data class ProgrammerRecordPaletteResponse(
    val palette: PaletteDetails,
    val created: Boolean,
    val entriesWritten: Int,
    val entriesRemoved: Int,
    val groupRowsEmitted: Int,
    /** Programmer entries that were themselves refs — flattened, since palettes don't nest. */
    val refsFlattened: Int,
    val skipped: List<ProgrammerSkipDto>,
    /** Set when the palette was live: what the re-resolve moved. */
    val programmerKeysRefreshed: Int = 0,
    val cuesRepublished: List<Int> = emptyList(),
)

/**
 * Record the programmer into a palette, masked by the palette's own type.
 *
 * The type *is* the mask — [PaletteType] is [PropertyMaskGroup] — so a COLOUR palette records only
 * colour properties with no separate mask argument to get wrong.
 *
 * Writing contents ends with [republishForPaletteEdit], so re-recording a palette that cues already
 * reference moves them immediately. That is the same path a palette edit takes, and it is what makes
 * re-record the natural editing gesture.
 */
internal suspend fun RoutingContext.handleProgrammerRecordPalette(state: State) {
    val request = call.receive<ProgrammerRecordPaletteRequest>()

    val mode = parseEnumOrNull<RecordMode>(request.mode) ?: return call.respond(
        HttpStatusCode.BadRequest, ErrorResponse("Unknown record mode '${request.mode}'"),
    )
    val source = parseEnumOrNull<RecordSource>(request.source) ?: return call.respond(
        HttpStatusCode.BadRequest, ErrorResponse("Unknown record source '${request.source}'"),
    )
    if (mode == RecordMode.CREATE && request.name.isNullOrBlank()) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("CREATE requires a name"))
    }
    if (mode != RecordMode.CREATE && request.paletteId == null) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("${mode.name} requires paletteId"))
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot record into project '${p.name}' — only the current project can be modified"
    }) { project ->
        // Resolve the target palette (and therefore the mask) before reading the programmer.
        val resolved = transaction(state.database) {
            resolvePaletteTarget(project, mode, request)
        }
        val (existing, type, targetError) = resolved
        if (targetError != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(targetError))
            return@withCurrentProject
        }
        requireNotNull(type)

        // Expand the selection outside the transaction — it reads the patch, not the DB.
        val scope = request.targets?.let { expandTargetsToFixtureKeys(state, it) }
        if (request.targets != null && scope!!.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("None of the requested targets resolve to a fixture"),
            )
            return@withCurrentProject
        }

        val (entries, skips) = collectProgrammerEntries(state, source, mask = setOf(type), targets = scope)
        val collapsed = collapseRecordingToAssignments(entries, state.show.fixtures, preserveRefs = false)
        val refsFlattened = entries.count { it.paletteUuid != null }

        val outcome = transaction(state.database) {
            val palette = existing ?: DaoPalette.new {
                this.project = project
                this.name = request.name!!.trim()
                this.type = type.name
                this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                this.sortOrder = (
                    DaoPalette.find {
                        (DaoPalettes.project eq project.id) and (DaoPalettes.type eq type.name)
                    }.maxOfOrNull { it.sortOrder } ?: -1
                    ) + 1
            }
            writeRecordingIntoPalette(palette, collapsed, mode)
        }

        // Re-resolve and republish: a re-record of a referenced palette must move its consumers,
        // exactly as an edit does.
        val republish = republishForPaletteEdit(state, outcome.paletteUuid)
        state.show.fixtures.paletteListChanged()

        val details = transaction(state.database) {
            DaoPalette.findById(outcome.paletteId)!!.toDetailsDtoForRecord()
        }
        logger.info(
            "record-palette {} '{}': {} written, {} removed, {} group row(s), {} ref(s) flattened",
            mode, details.name, outcome.written, outcome.removed, collapsed.groupRows, refsFlattened,
        )
        call.respond(
            ProgrammerRecordPaletteResponse(
                palette = details,
                created = existing == null,
                entriesWritten = outcome.written,
                entriesRemoved = outcome.removed,
                groupRowsEmitted = collapsed.groupRows,
                refsFlattened = refsFlattened,
                skipped = skips.map { it.toDto() },
                programmerKeysRefreshed = republish.programmerKeysRefreshed,
                cuesRepublished = republish.cuesRepublished,
            )
        )
    }
}

/** Result of resolving which palette a record targets: (existing, type, error). */
private fun resolvePaletteTarget(
    project: DaoProject,
    mode: RecordMode,
    request: ProgrammerRecordPaletteRequest,
): Triple<DaoPalette?, PaletteType?, String?> {
    val requestedType = request.type?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        PaletteType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: return Triple(null, null, "Unknown palette type '$raw'")
    }

    if (mode == RecordMode.CREATE) {
        val type = requestedType ?: return Triple(null, null, "CREATE requires a type")
        val name = request.name!!.trim()
        val clash = DaoPalette.find {
            (DaoPalettes.project eq project.id) and
                (DaoPalettes.type eq type.name) and
                (DaoPalettes.name eq name)
        }.firstOrNull()
        if (clash != null) {
            return Triple(null, null, "A ${type.name} palette called '$name' already exists")
        }
        return Triple(null, type, null)
    }

    val palette = DaoPalette.findById(request.paletteId!!)
        ?: return Triple(null, null, "Palette not found")
    if (palette.project.id != project.id) return Triple(null, null, "Palette not found")
    val type = palette.paletteType
        ?: return Triple(null, null, "Palette '${palette.name}' has an unknown type '${palette.type}'")
    if (requestedType != null && requestedType != type) {
        return Triple(null, null, "Palette '${palette.name}' is a ${type.name} palette, not ${requestedType.name}")
    }
    return Triple(palette, type, null)
}

private data class PaletteWriteOutcome(
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
private fun writeRecordingIntoPalette(
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

/** [PaletteDetails] for the record response. Must be called inside a transaction. */
private fun DaoPalette.toDetailsDtoForRecord(): PaletteDetails {
    val usage = paletteUsage(uuid)
    return PaletteDetails(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        type = type,
        notes = notes,
        sortOrder = sortOrder,
        entries = entries
            .orderBy(DaoPaletteEntries.sortOrder to SortOrder.ASC)
            .map {
                uk.me.cormack.lighting7.models.PaletteEntryDto(
                    targetType = it.targetType,
                    targetKey = it.targetKey,
                    propertyName = it.propertyName,
                    value = it.value,
                    sortOrder = it.sortOrder,
                )
            },
        referenceCount = usage.total,
        referencedByCueIds = usage.cueIds,
    )
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
