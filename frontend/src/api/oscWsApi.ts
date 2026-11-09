import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/** A log row's outcome, mirroring `OscOutcome` in lighting7's `osc/OscHandler.kt`. */
export type OscOutcome = 'DONE' | 'NOT_FOUND' | 'AMBIGUOUS' | 'REFUSED' | 'FAILED' | 'BAD_MESSAGE'

/** One received message and what it did. */
export interface OscLogRow {
  at: string
  /** The allowlist's name for the sender, which may be a range's. */
  sender: string
  /** The address the message came from. */
  source: string
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

/**
 * A received address a change moved: it reached `was` and now reaches `now`, or nothing (`now`
 * null, `reason` why). Mirrors `OscAddressChangeDto`.
 */
export interface OscAddressChange {
  address: string
  was: string | null
  now: string | null
  reason?: string | null
}

/** One change's moved addresses; `id` only grows, so a window announces those it has not seen. */
export interface OscAddressWarning {
  id: number
  at: string
  changes: OscAddressChange[]
}

/** Mirrors `OscStateDto` in lighting7's `osc/OscService.kt`. Counts are since the desk started. */
export interface OscState {
  enabled: boolean
  /** On UDP. */
  listening: boolean
  /** On TCP, the same port, SLIP-framed; absent is false. */
  tcpListening?: boolean
  port: number
  /** Why the desk is not listening on UDP or TCP although enabled — the port in use, say. */
  error?: string | null
  received: number
  dropped: number
  refused: number
  /** Most recently refused first. */
  refusedSenders: OscRefusedSender[]
  /** Newest first, at most 50. */
  recent: OscLogRow[]
  /** Received addresses a change moved, newest first, at most 10. Absent from a session-1 desk. */
  warnings?: OscAddressWarning[]
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
