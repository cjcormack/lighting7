import { InternalApiConnection, InternalEventType } from './internalApi'
import { Status } from './statusApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * The value pop-ups' **recent values** (programmer-fixes plan D14–D16; lighting7
 * `plugins/RecentValuesSocket.kt` is the wire contract): per list key, the live project's last
 * committed values, newest first, kept by the desk for every window and device.
 *
 * - `recentValues.state` `{projectId, lists: {<key>: [value, …]}}` — a `StateFlow`, so the
 *   subscription is the connect snapshot and every change is a whole frame.
 * - `recentValues.record` `{key, value}` — one per committed gesture, for the live project.
 *
 * Keys and spellings are `components/editor/recentValueSpelling.ts`'s. A desk that predates the
 * frame sends none, and every list reads empty.
 */
export interface RecentValuesState {
  projectId: number | null
  lists: Readonly<Record<string, readonly string[]>>
}

export const NO_RECENT_VALUES: RecentValuesState = { projectId: null, lists: {} }

/** A `recentValues.state` frame; anything malformed reads as no lists. */
export function parseRecentValuesFrame(raw: unknown): RecentValuesState {
  if (raw == null || typeof raw !== 'object') return NO_RECENT_VALUES
  const r = raw as Record<string, unknown>
  const lists: Record<string, readonly string[]> = {}
  if (r.lists != null && typeof r.lists === 'object') {
    for (const [key, values] of Object.entries(r.lists as Record<string, unknown>)) {
      if (Array.isArray(values)) lists[key] = values.filter((v): v is string => typeof v === 'string')
    }
  }
  return { projectId: typeof r.projectId === 'number' ? r.projectId : null, lists }
}

export interface RecentValuesWsApi {
  /** The lists on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: RecentValuesState) => void): Subscription
  /** The last frame, or [NO_RECENT_VALUES] before the first. */
  get(): RecentValuesState
  /**
   * Put [value] at the front of the live project's list [key]. Silent when the desk is
   * unreachable: the commit it follows has already said so.
   */
  record(key: string, value: string): void
}

export function createRecentValuesWsApi(conn: InternalApiConnection): RecentValuesWsApi {
  const state = createWsSubscribable<RecentValuesState>()
  let last: RecentValuesState = NO_RECENT_VALUES
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'recentValues.state') return
    last = parseRecentValuesFrame(message)
    seen = true
    state.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = state.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    get: () => last,
    record: (key, value) => {
      if (conn.readyState() !== Status.OPEN) return
      conn.send(JSON.stringify({ type: 'recentValues.record', key, value }))
    },
  }
}
