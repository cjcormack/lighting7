package uk.me.cormack.lighting7.routes

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header
import io.ktor.utils.io.readBuffer
import kotlinx.io.readString
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.mcp.originOf
import uk.me.cormack.lighting7.state.State

/** The meta that lets the SPA enter remote mode when the cloud's shell frames it (cloud-p1 plan D9). */
internal const val REMOTE_PARENT_META = "lighting7-remote-parent"

/** No page but the desk's own may frame the desk's own listener. */
internal const val DESK_FRAME_POLICY = "frame-ancestors 'self'"

/**
 * The frontend bundle's two listener-specific touches (cloud-p1 plan D9). On the desk's own listener
 * every file carries [DESK_FRAME_POLICY]. On the public listener `index.html` gains
 * `<meta name="lighting7-remote-parent">` naming the origin of `cloud.baseUrl`, which is what the
 * SPA's boot entry looks for before it waits for the shell; with no cloud configured it gains nothing.
 */
internal fun spaPagePlugin(state: State) = createRouteScopedPlugin("SpaPage") {
    onCall { call ->
        if (!call.isRemote) call.response.header("Content-Security-Policy", DESK_FRAME_POLICY)
    }
    onCallRespond { call, _ ->
        if (!call.isRemote) return@onCallRespond
        val parent = remoteParentOrigin(state) ?: return@onCallRespond
        transformBody { body ->
            if (body !is OutgoingContent || body.contentType?.match(ContentType.Text.Html) != true) return@transformBody body
            // The index is a file on its own path and bytes on the paths it answers for the app's routes.
            val html = when (body) {
                is OutgoingContent.ReadChannelContent -> body.readFrom().readBuffer().readString()
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                else -> return@transformBody body
            }
            TextContent(withRemoteParent(html, parent), ContentType.Text.Html.withCharset(Charsets.UTF_8), body.status)
        }
    }
}

internal fun remoteParentOrigin(state: State): String? {
    val config = state.cloud.config
    if (!config.enabled) return null
    return config.baseUrl?.let(::originOf)
}

/** [html] with the meta as the first thing in its head; unchanged if it has no head. */
internal fun withRemoteParent(html: String, parentOrigin: String): String {
    val head = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html) ?: return html
    val meta = "<meta name=\"$REMOTE_PARENT_META\" content=\"${escapeAttribute(parentOrigin)}\" />"
    return html.substring(0, head.range.last + 1) + meta + html.substring(head.range.last + 1)
}

private fun escapeAttribute(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
