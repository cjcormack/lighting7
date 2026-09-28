import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi, createWsSubscribable } from './wsSubscriptionFactory'

export interface CuesWsApi {
  /** `cueListChanged` — a cue was created, renamed, reordered or deleted. */
  subscribe(fn: () => void): Subscription

  /**
   * `cuesRecomposed` — a Look or template **contents** edit changed what these cues compose to.
   *
   * The keyed counterpart to `lookListChanged` / `templateListChanged`, which deliberately do not
   * fire for a contents edit because they invalidate every cached expansion. A retune moves a
   * handful of cues, so this frame names them and the subscriber re-reads exactly those.
   *
   * The ids are every cue layering the edited record, not only the live ones the server
   * re-transmitted: `/cues/{id}/cooked` composes on read, so a dark cue reads stale from the same
   * edit as a live one.
   */
  subscribeRecomposed(fn: (cueIds: number[]) => void): Subscription
}

type CuesInMessage = {
  type?: string
  cueIds?: unknown
}

export function createCuesWsApi(conn: InternalApiConnection): CuesWsApi {
  const listChanged = createChangeSignalApi(conn, 'cueListChanged')
  const recomposed = createWsSubscribable<number[]>()

  // Hand-written rather than a second `createChangeSignalApi`: this frame carries a body, which is
  // the line that factory draws.
  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as CuesInMessage | null
    if (message?.type !== 'cuesRecomposed' || !Array.isArray(message.cueIds)) return
    // Element-checked rather than cast: a non-number id would build the tag
    // `{ type: 'Cue', id: <not a number> }`, which matches no cached entry — so a malformed frame
    // would present as an invalidation that silently did nothing, not as an error.
    const cueIds = message.cueIds.filter((id): id is number => typeof id === 'number')
    if (cueIds.length > 0) recomposed.notify(cueIds)
  })

  return {
    subscribe: listChanged.subscribe,
    subscribeRecomposed: recomposed.api.subscribe,
  }
}
