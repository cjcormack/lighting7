package uk.me.cormack.lighting7.history

import uk.me.cormack.lighting7.cloud.McpRelay
import uk.me.cormack.lighting7.mcp.McpAuthService
import kotlinx.coroutines.ThreadContextElement
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Who a change belongs to (undo plan §3.1, D1): an account, a Claude grant, a control surface, or
 * the desk itself when no one is signed in. [key] is what two writes compare to be one source; [name]
 * is what the History sheet and a step's tooltip show.
 */
data class HistorySource(val kind: Kind, val id: String?, val name: String) {
    enum class Kind { ACCOUNT, MCP, SURFACE, DESK }

    val key: String
        get() = when (kind) {
            Kind.ACCOUNT -> "account:$id"
            Kind.MCP -> "mcp:$id"
            Kind.SURFACE -> "surface:$id"
            Kind.DESK -> "desk"
        }

    companion object {
        val DESK = HistorySource(Kind.DESK, null, "Desk")

        fun account(uuid: java.util.UUID, name: String) = HistorySource(Kind.ACCOUNT, uuid.toString(), name)

        /**
         * A Claude grant, by its `mcp-grant:<id>` session hash, or a cloud grant's `cloud-grant:<id>` as
         * `cloud:<id>`, named by the client it was granted to (D7): *Claude · Cursor*. A client that calls
         * itself Claude, or gives no name, is plain *Claude*.
         */
        fun mcp(sessionTokenHash: String, clientName: String? = null) = HistorySource(
            Kind.MCP,
            if (sessionTokenHash.startsWith(McpRelay.CLOUD_GRANT_PREFIX)) "cloud:" + sessionTokenHash.removePrefix(McpRelay.CLOUD_GRANT_PREFIX)
            else sessionTokenHash.removePrefix("mcp-grant:"),
            clientName?.trim()?.takeUnless { it.isEmpty() || it.equals("Claude", ignoreCase = true) || it == McpAuthService.UNNAMED_CLIENT }
                ?.let { "Claude · $it" } ?: "Claude",
        )

        fun surface(deviceKey: String) = HistorySource(Kind.SURFACE, deviceKey, deviceKey)
    }
}

/**
 * One door's write origin (undo plan §3.2): which source made the request, frame, tool call or
 * surface input now running, and the id every write it makes shares. A coroutine context element
 * that is also a [ThreadContextElement], so the blocking code a handler calls reads it through
 * [current] on whatever thread it runs; a door that is not a coroutine uses [with].
 *
 * Code with no origin — the clock, a reload, a recook a Look edit set off — is untracked (D14), so
 * the programmer's history takes no step for it.
 */
class WriteOrigin(
    val source: HistorySource,
    val window: String? = null,
    val requestId: String = nextRequestId(),
    val kind: Kind = Kind.TRACKED,
) : AbstractCoroutineContextElement(Key), ThreadContextElement<WriteOrigin?> {
    /** [UNTRACKED] marks code that must take no step whoever set it off — a project switch or reload. */
    enum class Kind { TRACKED, UNDO, REDO, UNTRACKED }

    /** The show entries an [Kind.UNDO] or [Kind.REDO] write puts back, which its commit marks. */
    @Volatile
    var entryIds: List<String> = emptyList()

    /** What the programmer step this origin makes is called, when the door or the route says. */
    @Volatile
    var label: String? = null

    /**
     * What the show-history entry this origin makes is called, when the route or tool says
     * ([historyLabel]); otherwise the entry is named from the records it changed ([RecordLabeler]).
     */
    @Volatile
    var entryLabel: String? = null

    /**
     * A continuous gesture's identity — the frame type, its targets and its attribute — so a fader
     * drag folds into one step while it repeats within a second ([ProgrammerHistory]).
     */
    @Volatile
    var coalesceKey: String? = null

    /** Whether this origin's request has its step, or folded into one ([ProgrammerHistory]). */
    @Volatile
    internal var stepped: Boolean = false

    override fun updateThreadContext(context: CoroutineContext): WriteOrigin? {
        val previous = current.get()
        current.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: WriteOrigin?) {
        current.set(oldState)
    }

    companion object Key : CoroutineContext.Key<WriteOrigin> {
        private val current = ThreadLocal<WriteOrigin?>()
        private val counter = AtomicLong()

        fun nextRequestId(): String = "h-" + counter.incrementAndGet().toString(36)

        /** An origin under which nothing is tracked, for code a gesture sets off that is no gesture itself. */
        fun untracked() = WriteOrigin(HistorySource.DESK, kind = Kind.UNTRACKED)

        /** The origin of the write running on this thread, or null for an untracked one. */
        fun current(): WriteOrigin? = current.get()

        /** Run [block] under [origin] on this thread, for a door that is not a coroutine. */
        fun <T> with(origin: WriteOrigin?, block: () -> T): T {
            val previous = current.get()
            current.set(origin)
            try {
                return block()
            } finally {
                current.set(previous)
            }
        }
    }
}

/**
 * Say the running request is one frame of a drag — a fan, a jog, an FX editor's slider — so its
 * repeats within a second fold into one step ([ProgrammerHistory]).
 */
fun historyGesture(key: String) {
    WriteOrigin.current()?.coalesceKey = key
}

/** Name the show-history entry the running request, frame or tool call makes (undo plan §3.2). */
fun historyLabel(label: String) {
    WriteOrigin.current()?.entryLabel = label
}
