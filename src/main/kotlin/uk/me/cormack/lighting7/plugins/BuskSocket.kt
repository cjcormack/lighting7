package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Inbound ────────────────────────────────────────────────────────────

/**
 * The `busk.*` **page** family: which busk page the desk is showing
 * ([uk.me.cormack.lighting7.state.BuskPageState]).
 *
 * `busk.layoutChanged` is a *broadcast* frame in [BroadcastSocket] and shares only the namespace —
 * it names pages whose **document** changed, this one names the page being **shown**. They cannot
 * be folded: one is a cache-invalidation signal keyed by page, the other a position, and
 * `store/busk.ts`'s echo suppression is written against the first alone.
 *
 * Reply convention 3, like `selection.*`: the write lands and the `busk.pageState` broadcast carries
 * it back to this client along with every other, so a client renders from the frame rather than from
 * its own echo.
 */
@Serializable
sealed class BuskInMessage : InMessage()

/** Show one page. A page id that is not the current project's is ignored. */
@Serializable
@SerialName("busk.setPage")
data class BuskSetPageInMessage(val pageId: Int) : BuskInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class BuskOutMessage : OutMessage()

/**
 * The showing page: the connect snapshot and the broadcast on every change.
 *
 * `null` means the desk has not been pointed at a page — nothing has moved it, or the one it held
 * was deleted. It is deliberately *not* "the first page": the busk view already resolves a page it
 * cannot find against the list it fetched, so saying nothing leaves each client on its own
 * fallback rather than dragging every client onto a page none of them asked for.
 */
@Serializable
@SerialName("busk.pageState")
data class BuskPageStateOutMessage(val pageId: Int? = null) : BuskOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleBusk(scope: SocketScope, message: BuskInMessage) {
    when (message) {
        is BuskSetPageInMessage -> scope.state.buskPageState.set(message.pageId)
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

/** A `StateFlow` subscription is the snapshot, exactly as `selection.state` is. */
fun setupBuskSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.buskPageState.pageId) { scope.send(BuskPageStateOutMessage(it)) }
}
