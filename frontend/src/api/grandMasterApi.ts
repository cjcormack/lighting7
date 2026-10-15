import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'

/**
 * The live project's **grand master** (venue-visit plan S5, D5; lighting7
 * `plugins/GrandMasterSocket.kt` is the wire contract): a level, 0–100 %, that scales the rig's
 * output at transmit — every dimmer and UV channel, and the colour mix of a head with no dimmer
 * (D12). A strobe channel is cut only at 0.
 *
 * - `grandMaster.state` `{projectId, level}` — a `StateFlow`, so the subscription is the connect
 *   snapshot and every change is a whole frame.
 * - `grandMaster.set` `{level}` — the Grand Master panel's fader, at frame rate (floored by the caller).
 *
 * REST reads and sets the same value (`GET/PUT projects/{id}/grand-master`), and a MIDI
 * `grandMasterFader` binding moves it too, so this frame is what keeps every window's fader honest.
 */
export interface GrandMasterState {
  projectId: number | null
  level: number
}

export const GRAND_MASTER_FULL = 100

export const AT_FULL: GrandMasterState = { projectId: null, level: GRAND_MASTER_FULL }

/** A `grandMaster.state` frame. The desk's Json drops defaults, so a missing level is full. */
export function parseGrandMasterFrame(raw: unknown): GrandMasterState {
  if (raw == null || typeof raw !== 'object') return AT_FULL
  const r = raw as Record<string, unknown>
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    level: typeof r.level === 'number' ? r.level : GRAND_MASTER_FULL,
  }
}

export interface GrandMasterWsApi {
  /** The level on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: GrandMasterState) => void): Subscription
  /** The last frame, or [AT_FULL] before the first. */
  get(): GrandMasterState
  /** Set the live project's level, 0–100. Toasts when the desk is unreachable. */
  set(level: number): void
}

export function createGrandMasterWsApi(conn: InternalApiConnection): GrandMasterWsApi {
  const state = createWsSubscribable<GrandMasterState>()
  let last: GrandMasterState = AT_FULL
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'grandMaster.state') return
    last = parseGrandMasterFrame(message)
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
    set: (level) => {
      sendGesture(conn, { type: 'grandMaster.set', level })
    },
  }
}
