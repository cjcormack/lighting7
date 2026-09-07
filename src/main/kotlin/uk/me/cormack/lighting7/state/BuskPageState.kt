package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * The desk's **showing busk page** — which page the busk view is on, as a fact about the desk
 * rather than about one browser tab.
 *
 * `DeskSelection`'s shape and, for the same reason, its argument: a hardware *next page* button and
 * a tab click are two ways of making one gesture, so there has to be one answer to "which page is
 * showing" or the button and the screen disagree the moment either is used. Transient — cleared on
 * project switch, never persisted — because it is a position, not a document; `?page=` keeps
 * mirroring it so a link still opens where it says.
 *
 * **Ids, not uuids, on the wire.** The client addresses a page by id everywhere else (`?page=`, the
 * page CRUD routes), and a binding addresses one by uuid because a binding has to survive a clone;
 * [setByUuid] is where the two meet, and it is the only place a uuid appears.
 *
 * [pages] is the project's page ids in `sortOrder`, read fresh on each move rather than cached: a
 * press happens at button rate, and a cached list would be wrong the moment another client added a
 * page. It answers an empty list when there is no project, which makes every move a no-op.
 */
class BuskPageState(private val pages: () -> List<Pair<Int, UUID>>) {
    private val _pageId = MutableStateFlow<Int?>(null)

    /** The showing page's id, or null when nothing has been shown yet (or the project has no pages). */
    val pageId: StateFlow<Int?> = _pageId.asStateFlow()

    /** Show one page by id. A page id that is not this project's is ignored. */
    fun set(pageId: Int) {
        if (pages().none { it.first == pageId }) return
        _pageId.update { if (it == pageId) it else pageId }
    }

    /** Show the page a binding names. A uuid that resolves to nothing is ignored. */
    fun setByUuid(pageUuid: UUID) {
        val id = pages().firstOrNull { it.second == pageUuid }?.first ?: return
        _pageId.update { if (it == id) it else id }
    }

    fun clear() = _pageId.update { null }

    /**
     * Step [delta] pages, **wrapping**, and land on the first page when nothing is showing yet.
     *
     * Wrapping rather than clamping because these are the only two page controls a surface has: a
     * *next* that stops at the last page leaves the operator with no way back but a mouse, on the
     * one surface whose whole point is not needing one.
     */
    fun step(delta: Int) {
        val ids = pages().map { it.first }
        if (ids.isEmpty()) return
        val current = ids.indexOf(_pageId.value)
        val next = if (current < 0) ids.first() else ids[((current + delta) % ids.size + ids.size) % ids.size]
        _pageId.update { if (it == next) it else next }
    }

    fun next() = step(1)
    fun prev() = step(-1)

    /**
     * Drop a showing page that no longer exists, keeping the rest — the same rule
     * [DeskSelection.prune] applies to a stale target. Called when the layout changes, which is the
     * signal a page delete fires.
     *
     * It resolves to **null, not to the first page**: the desk deliberately says nothing rather than
     * moving every client to a page none of them asked for. The busk view already resolves a
     * `?page=` it cannot find against the fetched list, so a null here leaves each client where its
     * own fallback puts it.
     */
    fun reconcile() {
        val id = _pageId.value ?: return
        if (pages().none { it.first == id }) _pageId.update { null }
    }
}
