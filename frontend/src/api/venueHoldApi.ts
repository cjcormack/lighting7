import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * The desk's **hold** (venue plan P5, D7, session 6; lighting7 `state/VenueHoldService.kt` and
 * `plugins/VenueSocket.kt` are the wire contract): a show on a venue whose pinned version is behind
 * the installed one **on output** is held — every controller sends zero but for a park — while it still
 * composes and draws. *Run against* lifts that for the session (REST, `store/venueHold.ts`).
 *
 * One outbound frame, `venue.hold`, a `StateFlow`: the subscription is the connect snapshot.
 * `projectId` is the live project's; `pinned` / `installed` the show's version and the venue's
 * latest, absent for a standalone show or a venue.
 */
export interface VenueHoldState {
  projectId: number | null
  held: boolean
  pinned: number | null
  installed: number | null
  runAgainst: boolean
}

export const NOT_HELD: VenueHoldState = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }

/** A `venue.hold` frame. The desk's Json drops defaults, so a missing field is its zero. */
export function parseHoldFrame(raw: unknown): VenueHoldState {
  if (raw == null || typeof raw !== 'object') return NOT_HELD
  const r = raw as Record<string, unknown>
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    held: r.held === true,
    pinned: typeof r.pinned === 'number' ? r.pinned : null,
    installed: typeof r.installed === 'number' ? r.installed : null,
    runAgainst: r.runAgainst === true,
  }
}

export interface VenueHoldWsApi {
  /** The hold on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: VenueHoldState) => void): Subscription
  /** The last frame, or [NOT_HELD] before the first. */
  get(): VenueHoldState
}

export function createVenueHoldWsApi(conn: InternalApiConnection): VenueHoldWsApi {
  const hold = createWsSubscribable<VenueHoldState>()
  let last: VenueHoldState = NOT_HELD
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'venue.hold') return
    last = parseHoldFrame(message)
    seen = true
    hold.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = hold.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    get: () => last,
  }
}
