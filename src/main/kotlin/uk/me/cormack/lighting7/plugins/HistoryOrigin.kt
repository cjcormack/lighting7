package uk.me.cormack.lighting7.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.header
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.cloud.McpRelay
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.history.WriteOrigin
import uk.me.cormack.lighting7.state.State

/**
 * The write origin's doors (undo plan §3.2): REST here, the socket in `Sockets.kt`, MCP in
 * `McpProtocol.callTool`, surfaces in `SurfaceInputRouter`. Each runs the request, frame, tool call or
 * input under one [WriteOrigin], so every write it makes is one step of the programmer's history.
 */

/**
 * The source a signed-in caller writes as: their account, Claude's grant (named by its client through
 * [clientName], given the grant's id), a grant through the cloud (named by the client its call names),
 * or the desk with no accounts.
 */
internal fun historySourceOf(user: AuthenticatedUser?, clientName: (Int) -> String? = { null }): HistorySource = when {
    user == null -> HistorySource.DESK
    user.sessionTokenHash.startsWith(MCP_GRANT_PREFIX) -> HistorySource.mcp(
        user.sessionTokenHash,
        user.sessionTokenHash.removePrefix(MCP_GRANT_PREFIX).toIntOrNull()?.let(clientName),
    )
    user.sessionTokenHash.startsWith(McpRelay.CLOUD_GRANT_PREFIX) -> HistorySource.mcp(user.sessionTokenHash, user.clientName)
    else -> HistorySource.account(user.uuid, user.displayName)
}

/** [historySourceOf] with Claude's client named from [state]'s grants. */
internal fun historySourceOf(state: State, user: AuthenticatedUser?): HistorySource =
    historySourceOf(user) { state.mcpAuthService.clientNameOf(it) }

private const val MCP_GRANT_PREFIX = "mcp-grant:"

/** The request header naming the desk window a REST call comes from: its `windows.state` row id. */
const val DESK_WINDOW_HEADER = "X-Desk-Window"

/**
 * The response header carrying a REST write's request id. The show entry that holds its writes lists it
 * in `requestIds` (`GET /projects/{id}/history`); a write that changed no record is in none.
 */
const val HISTORY_ENTRY_HEADER = "X-History-Entry"

private val PROJECT_PATH = Regex("^/api/rest/projects/(\\d+)(/|$)")

/** The project a REST path names, if it is under `/projects/{id}`. */
internal fun projectIdOfPath(path: String): Int? = PROJECT_PATH.find(path)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Run every REST call that is not a read under an origin of its caller's and the window its
 * [DESK_WINDOW_HEADER] names, labelled from its path for the programmer's history; a route that knows
 * better names its show entry with [historyLabel]. The answer carries the request's id as
 * [HISTORY_ENTRY_HEADER] whether or not it writes anything, and the project the path names — else the
 * current one — has a record cache before the handler runs, so its writes are journaled (§3.2).
 */
// Route.intercept is deprecated in favour of route-scoped plugins, whose `onCall` cannot wrap the rest
// of the pipeline in a coroutine context — the same trade-off as `installProjectWriteGuard`.
@Suppress("DEPRECATION")
internal fun Route.installHistoryOrigin(state: State) {
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@intercept
        val path = call.request.path()
        val window = call.request.headers[DESK_WINDOW_HEADER]?.takeIf { it.isNotBlank() && it.length <= MAX_WINDOW_ID }
        val origin = WriteOrigin(historySourceOf(state, call.authenticatedUserOrNull), window = window)
        origin.label = restHistoryLabel(path, method)
        call.response.header(HISTORY_ENTRY_HEADER, origin.requestId)
        state.ensureHistory()
        projectIdOfPath(path)?.let { state.ensureHistory(it) }
        withContext(origin) { proceed() }
    }
}

private const val MAX_WINDOW_ID = 64

/**
 * Name the show-history entry this request makes, in place of one read from the records it changed.
 * Before the write commits: the entry takes its name then.
 */
fun ApplicationCall.historyLabel(label: String) = uk.me.cormack.lighting7.history.historyLabel(label)

/** A REST write's step name, from its method and the last words of its path. */
internal fun restHistoryLabel(path: String, method: HttpMethod = HttpMethod.Post): String {
    val words = path.split('/').filter { it.isNotEmpty() && it.toIntOrNull() == null && it != "api" && it != "rest" }
    if (method == HttpMethod.Delete) {
        return when (words.lastOrNull()) {
            "fx" -> "Stop FX"
            null -> "Delete"
            else -> "Delete " + words.last().removeSuffix("s").replace('-', ' ')
        }
    }
    return when (words.lastOrNull()) {
        "fan" -> "Fan"
        "aim" -> "Aim"
        "focus" -> "Focus here"
        "fx" -> if (words.getOrNull(words.size - 2) == "programmer") "Add FX" else "Change FX"
        "toggle" -> when {
            "palettes" in words -> "Press palette"
            "looks" in words -> "Press Look"
            "locate" in words -> "Locate"
            else -> "Press"
        }
        "apply" -> when {
            "palettes" in words -> "Apply palette as values"
            "looks" in words -> "Apply Look as values"
            else -> "Apply"
        }
        "press" -> "Press pad"
        "include" -> "Include"
        "update" -> "Update"
        "record" -> "Record"
        "record-look" -> "Record Look"
        else -> words.lastOrNull()?.replace('-', ' ')?.replaceFirstChar { it.uppercase() } ?: "Change"
    }
}

/**
 * A socket frame's origin: the socket's account and announced window, its label and, for a drag, its
 * gesture. The current project has a record cache before the frame writes, so its writes are journaled.
 */
internal fun SocketScope.writeOrigin(message: InMessage): WriteOrigin {
    state.ensureHistory()
    return WriteOrigin(historySourceOf(state, user), window = window?.id).apply {
        label = socketHistoryLabel(message)
        coalesceKey = socketCoalesceKey(message)
    }
}

private fun target(type: String, key: String) = if (type == "group") "group $key" else key

internal fun socketHistoryLabel(message: InMessage): String = when (message) {
    is ProgrammerSetInMessage -> "${target(message.targetType, message.targetKey)} ${message.attribute}"
    is ProgrammerSetColourInMessage -> "${target(message.targetType, message.targetKey)} colour"
    is ProgrammerSetPositionInMessage -> "${target(message.targetType, message.targetKey)} position"
    is ProgrammerClearEntryInMessage -> "Release ${target(message.targetType, message.targetKey)} ${message.attribute}"
    is ProgrammerClearAllInMessage -> "Clear"
    is ProgrammerClearTargetInMessage -> "Release ${message.targetKey}"
    is ProgrammerAddLayerInMessage -> "Add layer"
    is ProgrammerRemoveLayerInMessage -> "Remove layer"
    is ProgrammerDetachLayerInMessage -> "Detach layer"
    is ProgrammerMoveLayerInMessage -> "Move layer"
    is ProgrammerPatchLayerInMessage -> "Change layer"
    is ProgrammerSetSceneryInMessage -> "Move scenery"
    is ProgrammerClearSceneryInMessage -> "Release scenery"
    is ProgrammerSetTempoInMessage -> "Hold tempo"
    is ProgrammerTapTempoInMessage -> "Tap tempo"
    is ProgrammerHoldTempoInMessage -> "Capture tempo"
    is ProgrammerClearTempoInMessage -> "Release tempo"
    is UpdateChannelInMessage -> "Channel ${message.universe}/${message.id}"
    is UpdateFxInMessage -> "Change FX"
    is RemoveFxInMessage -> "Stop FX"
    is PauseFxInMessage -> "Pause FX"
    is ResumeFxInMessage -> "Resume FX"
    is ClearFxInMessage -> "Stop all FX"
    else -> message::class.java.getAnnotation(kotlinx.serialization.SerialName::class.java)?.value
        ?: message::class.java.simpleName
}

/** The identity of a gesture that repeats as it is dragged, or null for one that does not. */
internal fun socketCoalesceKey(message: InMessage): String? = when (message) {
    is ProgrammerSetInMessage -> "set|${message.targetType}:${message.targetKey}|${message.attribute}"
    is ProgrammerSetColourInMessage -> "setColour|${message.targetType}:${message.targetKey}|${message.attribute}"
    is ProgrammerSetPositionInMessage -> "setPosition|${message.targetType}:${message.targetKey}"
    is UpdateChannelInMessage -> "updateChannel|${message.universe}:${message.id}"
    is ProgrammerPatchLayerInMessage -> "patchLayer|${message.layerId}"
    is ProgrammerSetSceneryInMessage -> "setScenery|${message.elementUuid}"
    is ProgrammerSetTempoInMessage -> "setTempo|${message.speedMasterUuid}"
    is ProgrammerTapTempoInMessage -> "tapTempo|${message.speedMasterUuid}"
    // The REST door says the same for `PUT /fx/{id}`, so a slider's frames fold whichever door they take.
    is UpdateFxInMessage -> "updateFx|${message.fxId}"
    else -> null
}
