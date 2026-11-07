import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/** A log row's outcome, mirroring `OscOutcome` in lighting7's `osc/OscHandler.kt`. */
export type OscOutcome = 'DONE' | 'NOT_FOUND' | 'AMBIGUOUS' | 'REFUSED' | 'FAILED' | 'BAD_MESSAGE'

/** One received message and what it did. */
export interface OscLogRow {
  at: string
  /** The allowlist's name for the sender. */
  sender: string
  address: string
  args: string[]
  outcome: OscOutcome
  detail: string
}

/** A sender not on the allowlist, one row per address. */
export interface OscRefusedSender {
  address: string
  count: number
  lastAt: string
}

/** Mirrors `OscStateDto` in lighting7's `osc/OscService.kt`. Counts are since the desk started. */
export interface OscState {
  enabled: boolean
  listening: boolean
  port: number
  /** Why the desk is not listening although enabled — the port in use, say. */
  error?: string | null
  received: number
  dropped: number
  refused: number
  /** Most recently refused first. */
  refusedSenders: OscRefusedSender[]
  /** Newest first, at most 50. */
  recent: OscLogRow[]
}

/**
 * OSC in: the machine-scoped `osc.state` frame (`plugins/MachineSocket.kt`), sent to every socket on
 * the desk's own listener and none on the public one. It carries the whole state and arrives as a
 * snapshot on connect, before anything here may have subscribed, so the latest frame is kept.
 */
export interface OscWsApi {
  subscribe(fn: (state: OscState) => void): Subscription
  /** The last frame, or null before the first. */
  current(): OscState | null
}

type OscInMessage = { type: 'osc.state'; state: OscState }

export function createOscWsApi(conn: InternalApiConnection): OscWsApi {
  const changed = createWsSubscribable<OscState>()
  let latest: OscState | null = null

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as OscInMessage | null
    if (message == null || message.type !== 'osc.state' || message.state == null) return
    latest = message.state
    changed.notify(message.state)
  })

  return {
    subscribe: changed.api.subscribe,
    current: () => latest,
  }
}
