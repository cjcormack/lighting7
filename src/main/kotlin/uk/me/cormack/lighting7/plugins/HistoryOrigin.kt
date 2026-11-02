package uk.me.cormack.lighting7.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.history.WriteOrigin

/**
 * The write origin's doors (undo plan §3.2): REST here, the socket in `Sockets.kt`, MCP in
 * `McpProtocol.callTool`, surfaces in `SurfaceInputRouter`. Each runs the request, frame, tool call or
 * input under one [WriteOrigin], so every write it makes is one step of the programmer's history.
 */

/** The source a signed-in caller writes as: their account, Claude's grant, or the desk with no accounts. */
internal fun historySourceOf(user: AuthenticatedUser?): HistorySource = when {
    user == null -> HistorySource.DESK
    user.sessionTokenHash.startsWith("mcp-grant:") -> HistorySource.mcp(user.sessionTokenHash)
    else -> HistorySource.account(user.uuid, user.displayName)
}

/**
 * Run every REST call that is not a read under an origin of its caller's, labelled from its path; a
 * route that knows better names its step with `historyLabel`. Session 3 of the undo plan adds the
 * window header and answers the entry id.
 */
// Route.intercept is deprecated in favour of route-scoped plugins, whose `onCall` cannot wrap the rest
// of the pipeline in a coroutine context — the same trade-off as `installProjectWriteGuard`.
@Suppress("DEPRECATION")
internal fun Route.installHistoryOrigin() {
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@intercept
        val origin = WriteOrigin(historySourceOf(call.authenticatedUserOrNull))
        origin.label = restHistoryLabel(call.request.path(), method)
        withContext(origin) { proceed() }
    }
}

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

/** A socket frame's origin: the socket's account and announced window, its label and, for a drag, its gesture. */
internal fun SocketScope.writeOrigin(message: InMessage): WriteOrigin =
    WriteOrigin(historySourceOf(user), window = window?.id).apply {
        label = socketHistoryLabel(message)
        coalesceKey = socketCoalesceKey(message)
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
    // The REST door says the same for `PUT /fx/{id}`, so a slider's frames fold whichever door they take.
    is UpdateFxInMessage -> "updateFx|${message.fxId}"
    else -> null
}
