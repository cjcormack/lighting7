package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.state.State

/** `POST /projects/{id}/cues/copy-from`: cues of [fromProjectId] copied into the path's project. */
@Serializable
data class CopyCuesFromRequest(
    val fromProjectId: Int,
    /** In the order they are placed. */
    val cueIds: List<Int>,
    /** The stack each copy gets a new entry in; absent leaves every copy loose. */
    val stackId: Int? = null,
    /** An entry of [stackId] the copies go after, in order; absent is the end of the stack. */
    val after: Int? = null,
    /** The same run rolled back: the answer says what a copy would do and nothing is written. */
    val dryRun: Boolean = false,
)

/** One copy, and its new entry when it was placed in a stack. A dry run's ids name nothing. */
@Serializable
data class CopiedCueDto(val fromCueId: Int, val cueId: Int, val entryId: Int? = null)

/** A reference a copy could not carry, and the records (a cue, a Look, a palette) that hold it. */
@Serializable
data class CopyUnresolvedDto(val problem: String, @SerialName("in") val records: List<String>)

@Serializable
data class CopyCuesFromResponse(
    val dryRun: Boolean,
    val copied: List<CopiedCueDto>,
    val unresolved: List<CopyUnresolvedDto>,
    val stackId: Int? = null,
    /** Looks and palettes copied with the cues, and those the target already had by name. */
    val looksCopied: List<String> = emptyList(),
    val looksLinked: List<String> = emptyList(),
    val palettesCopied: List<String> = emptyList(),
    val palettesLinked: List<String> = emptyList(),
)

internal sealed interface CopyCuesOutcome {
    data class Copied(val response: CopyCuesFromResponse) : CopyCuesOutcome
    data class Refused(val status: HttpStatusCode, val message: String) : CopyCuesOutcome
}

/**
 * The copy behind `POST /cues/copy-from` (cue model plan §3.8): each of [request]'s cues through
 * one [CueCopier], placed unnumbered in its stack — after its `after` entry, in order, else at the
 * end — or loose. Refused before anything is written. Must run inside a transaction, which the
 * caller rolls back for a dry run.
 */
internal fun copyCuesFrom(state: State, target: DaoProject, request: CopyCuesFromRequest): CopyCuesOutcome {
    fun refused(status: HttpStatusCode, message: String) = CopyCuesOutcome.Refused(status, message)

    if (request.cueIds.isEmpty()) return refused(HttpStatusCode.BadRequest, "cueIds names no cue")
    if (request.fromProjectId == target.id.value) {
        return refused(HttpStatusCode.BadRequest, "fromProjectId is this project: duplicate a cue to copy it here")
    }
    val source = state.resolveProject(request.fromProjectId.toString())
        ?: return refused(HttpStatusCode.NotFound, "Source project not found")
    val ids = request.cueIds.distinct()
    val cues = ids.associateWith { id -> DaoCue.findById(id)?.takeIf { it.project.id == source.id } }
    val missing = cues.filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        return refused(HttpStatusCode.NotFound, "Project '${source.name}' has no cue ${missing.joinToString(", ")}")
    }
    val stack = request.stackId?.let { id ->
        val stack = DaoCueStack.findById(id)?.takeIf { it.project.id == target.id }
            ?: return refused(HttpStatusCode.NotFound, "Cue stack not found")
        if (stack.type == CueStackType.SEPARATOR.name) return refused(HttpStatusCode.BadRequest, "Cannot add cues to a separator")
        stack
    }
    val afterIndex = request.after?.let { after ->
        if (stack == null) return refused(HttpStatusCode.BadRequest, "after names an entry, so it needs a stackId")
        entriesOf(stack).indexOfFirst { it.id.value == after }.takeIf { it >= 0 }
            ?: return refused(HttpStatusCode.BadRequest, "Entry $after is not in ${stack.name}")
    }

    val copier = CueCopier(source, target)
    val copied = ids.mapIndexed { i, id ->
        val cue = cues.getValue(id)!!
        val copy = copier.copyCue(cue, cue.name)
        val entry = stack?.let { placeCueInStack(it, copy, position = afterIndex?.let { at -> at + 1 + i }) }
        CopiedCueDto(fromCueId = id, cueId = copy.id.value, entryId = entry?.id?.value)
    }
    val report = copier.report
    return CopyCuesOutcome.Copied(
        CopyCuesFromResponse(
            dryRun = request.dryRun,
            copied = copied,
            unresolved = report.unresolved.map { (problem, records) -> CopyUnresolvedDto(problem, records.toList()) },
            stackId = stack?.id?.value,
            looksCopied = report.looks.toList(),
            looksLinked = report.looksLinked.toList(),
            palettesCopied = report.templates.toList(),
            palettesLinked = report.templatesLinked.toList(),
        ),
    )
}
