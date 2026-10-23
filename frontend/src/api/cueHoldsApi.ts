import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * What holds each live cue (lighting7 cue model plan §3.4; `fx/CueHolds.kt` and
 * `plugins/BroadcastSocket.kt`'s `CueHoldsStateOutMessage` are the wire contract). A cue is live
 * while anything holds it: a running stack whose live entry names it (`STACK`), or a press from a
 * pad, a slot, a binding, the hand or `/cues/{id}/apply` (`LOOSE`, newest on top). It comes on with
 * its first hold and off with its last.
 *
 * One outbound frame, `cueHolds.state`, a `StateFlow`: the subscription is the connect snapshot, and
 * every change is a whole frame. `projectId` is the live show's.
 */
export type CueHold =
  | { kind: 'STACK'; stackId: number; entryId: number }
  | { kind: 'LOOSE'; seq: number }

export interface LiveCueHolds {
  cueId: number
  /** The priority it composes at — its highest hold's. */
  priority: number
  holds: CueHold[]
  /**
   * Its highest hold's stack, null when that hold is a press: the stack whose FX amount weighs its
   * effects. Absent from a desk before effects plan session 5.
   */
  topStackId?: number | null
  /**
   * Its stored rows, layers or effects changed since it came on (show-cue-editing plan §3.5): the
   * stage still shows it as it was, until *Update stage* or its next first hold. Sent only while true.
   */
  edited?: boolean
}

export interface CueHoldsState {
  projectId: number | null
  cues: LiveCueHolds[]
}

export const NO_HOLDS: CueHoldsState = { projectId: null, cues: [] }

/**
 * How a cue pad's ring draws a cue (D11): `loose` (solid) when a press holds it — what the next
 * press would let go of; `stack` (outline) when it is live only through a stack; null when off.
 */
export type CueRing = 'loose' | 'stack' | null

export function ringOf(cue: LiveCueHolds | undefined): CueRing {
  if (cue == null || cue.holds.length === 0) return null
  return cue.holds.some((h) => h.kind === 'LOOSE') ? 'loose' : 'stack'
}

function parseHold(raw: unknown): CueHold | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (r.kind === 'STACK' && typeof r.stackId === 'number' && typeof r.entryId === 'number') {
    return { kind: 'STACK', stackId: r.stackId, entryId: r.entryId }
  }
  if (r.kind === 'LOOSE' && typeof r.seq === 'number') return { kind: 'LOOSE', seq: r.seq }
  return null
}

/** A `cueHolds.state` frame. Anything malformed is dropped rather than drawn. */
export function parseCueHoldsFrame(raw: unknown): CueHoldsState {
  if (raw == null || typeof raw !== 'object') return NO_HOLDS
  const r = raw as Record<string, unknown>
  const cues = Array.isArray(r.cues) ? r.cues : []
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    cues: cues.flatMap((c) => {
      if (c == null || typeof c !== 'object') return []
      const cue = c as Record<string, unknown>
      if (typeof cue.cueId !== 'number') return []
      const holds = (Array.isArray(cue.holds) ? cue.holds : []).map(parseHold).filter((h): h is CueHold => h != null)
      return [{
        cueId: cue.cueId,
        priority: typeof cue.priority === 'number' ? cue.priority : 0,
        holds,
        topStackId: typeof cue.stackId === 'number' ? cue.stackId : null,
        ...(cue.edited === true ? { edited: true } : {}),
      }]
    }),
  }
}

export interface CueHoldsWsApi {
  /** The holds on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: CueHoldsState) => void): Subscription
  /** The last frame, or [NO_HOLDS] before the first. */
  get(): CueHoldsState
}

export function createCueHoldsWsApi(conn: InternalApiConnection): CueHoldsWsApi {
  const holds = createWsSubscribable<CueHoldsState>()
  let last: CueHoldsState = NO_HOLDS
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'cueHolds.state') return
    last = parseCueHoldsFrame(message)
    seen = true
    holds.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = holds.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    get: () => last,
  }
}
