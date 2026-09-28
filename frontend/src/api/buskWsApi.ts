import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * `busk.layoutChanged {pageIds}` — the busk layout of those pages changed.
 *
 * Hand-written rather than built on `createChangeSignalApi`, because the frame **carries a body**
 * and that factory's own docblock says it is the wrong tool the moment a bridge needs one. The ids
 * matter here: `store/busk.ts` invalidates exactly those pages, and suppresses the echo of its own
 * write, both of which need to read them.
 *
 * Fires for page CRUD and reorder, for every whole-page layout write — **including this client's
 * own** — and for a template / Look / cue / cue-stack delete that took pads off a page. On a page
 * delete it carries the deleted id *plus every survivor*, because their `sortOrder` moved.
 */
export interface BuskWsApi {
  subscribe(fn: (pageIds: number[]) => void): Subscription
  /**
   * `busk.rigChanged` — the busk **rig** changed: a whole-document write (this client's own
   * included), or a group or patch delete that took tiles off it.
   *
   * Payload-free, because there is one rig per project (busk-further plan D1) and so nothing to key
   * on; `store/busk.ts` re-reads `GET /busk/rig` on it, suppressing the echo of its own write the
   * way the page bridge does. Still hand-written beside `subscribe` rather than through
   * `createChangeSignalApi`, so the two frames of one family share one connection subscription.
   */
  subscribeRigChanged(fn: () => void): Subscription
}

interface BuskLayoutChangedMessage {
  type: 'busk.layoutChanged'
  pageIds: number[]
}

interface BuskRigChangedMessage {
  type: 'busk.rigChanged'
}

export function createBuskWsApi(conn: InternalApiConnection): BuskWsApi {
  const layoutChanged = createWsSubscribable<number[]>()
  const rigChanged = createWsSubscribable<void>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as BuskLayoutChangedMessage | BuskRigChangedMessage | null
    if (message?.type === 'busk.layoutChanged') layoutChanged.notify(message.pageIds ?? [])
    else if (message?.type === 'busk.rigChanged') rigChanged.notify(undefined)
  })

  return { subscribe: layoutChanged.api.subscribe, subscribeRigChanged: rigChanged.api.subscribe }
}
