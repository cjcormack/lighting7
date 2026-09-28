import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'

/**
 * The **showing busk page** — one per desk, server-owned, and what a window follows by default.
 *
 * `selectionApi`'s shape and its argument: a hardware *next page* button and a tab click are two
 * ways of making one gesture, so the surface needs one thing to move. Transient — cleared on project
 * switch, never persisted — because it is a position, not a document.
 *
 * **A window may decline to be on it.** `lib/buskPageFollow.ts` is the per-tab follow/local flag,
 * `lib/deskFollow.ts`'s twin for the page, and it is a *client* choice: the desk keeps exactly the
 * fact it always kept and this module is unchanged by it. A local window still receives every frame
 * and simply does not resolve against it; the MIDI page buttons still move every following window.
 * `?page=` mirrors whichever page the window is showing, so a link still opens where it says — and
 * a window arriving with one takes it as its own (see `BuskingView`).
 *
 * `busk.pageState` is both the snapshot on connect and the broadcast on every change; the write
 * gets no reply, the state frame being the acknowledgement (reply convention 3).
 *
 * **Unrelated to `busk.layoutChanged`**, which shares only the namespace: that frame names pages
 * whose *document* changed and is what `store/busk.ts`'s echo suppression is written against. This
 * one is a position and carries no layout, so a page-state frame arriving mid-write is not the case
 * that machinery exists for.
 *
 * `null` means the desk has not been pointed at a page — nothing has moved it, or the page it held
 * was deleted. Deliberately *not* "the first page": the busk view already resolves a page it cannot
 * find against the list it fetched, so a null leaves each client on its own fallback rather than
 * dragging every client onto a page none of them asked for.
 */
export interface BuskPageWsApi {
  subscribe(fn: (pageId: number | null) => void): Subscription
  /** The last frame, or undefined before the first — for an RTK Query `queryFn` seeding its entry. */
  getState(): number | null | undefined
  /**
   * Show one page. A page id that is not the current project's is ignored server-side.
   *
   * Returns `sendGesture`'s own boolean — `false` when the socket is down and the gesture never
   * left. This is the one shared-state write whose caller has a same-tab fallback worth taking on
   * that: unlike a programmer edit or a blackout, "which page is showing" also has a purely local
   * answer, so a click the desk never heard **unlinks this window** onto the page clicked rather
   * than leaving the tab strip silently unresponsive. That used to be a separate offline override;
   * it is the local page now, because two mechanisms meaning "this tab's page" is one too many.
   */
  setPage(pageId: number): boolean
}

interface BuskPageStateMessage {
  type: 'busk.pageState'
  pageId: number | null
}

export function createBuskPageWsApi(conn: InternalApiConnection): BuskPageWsApi {
  const pageState = createWsSubscribable<number | null>()
  let last: number | null | undefined

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as BuskPageStateMessage | null
    if (message?.type !== 'busk.pageState') return
    last = message.pageId ?? null
    pageState.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = pageState.api.subscribe(fn)
      if (last !== undefined) fn(last)
      return sub
    },
    getState: () => last,
    setPage: (pageId) => sendGesture(conn, { type: 'busk.setPage', pageId }),
  }
}
