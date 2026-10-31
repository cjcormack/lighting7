import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * **Move in dark** as the desk is running it (lighting7 move-in-dark plan D11; `plugins/MoveInDarkSocket.kt`
 * is the wire contract): every head the live show's stacks are moving while it is dark, or waiting
 * to, so the cue that lights it next only brings it up. The desk owns the plan (P2); this side draws it.
 *
 * - `moveInDark.state` `{projectId, stacks: [{stackId, heads: [{target, entryId, cueId, label, state,
 *   remainingMs?, families, position?}]}]}` — a `StateFlow`, so the subscription is the connect
 *   snapshot and every change is a whole frame. Republished when a head's state, target or move
 *   changes, at most every 100 ms; never for the time left alone, which a client counts down from
 *   the frame's `remainingMs`, anchored to this browser's clock on receipt.
 *
 * There is no inbound frame: the settings are stored writes over REST.
 */
export type MoveInDarkHeadState = 'WAITING' | 'MOVING' | 'SET' | 'LIT_EARLY'

export type MoveInDarkFamily = 'POSITION' | 'COLOUR' | 'BEAM'

export interface MoveInDarkHead {
  /** The head's key: a fixture or an element. */
  target: string
  /** The entry and cue it is moved for, and that cue as a GO labels it (`Q12`). */
  entryId: number
  cueId: number
  label: string
  /** Dark and counting the delay, moving, in place, or lit before its GO and let go. */
  state: MoveInDarkHeadState
  /** When the delay (`WAITING`) or the move (`MOVING`) ends, on this browser's clock; null otherwise. */
  endsAtMs: number | null
  families: MoveInDarkFamily[]
  /** The target's pan and tilt bytes, when the move carries Position. */
  position: { pan: number; tilt: number } | null
}

export interface MoveInDarkState {
  projectId: number | null
  /** Stack id → the heads it moves; a stack absent moves none. */
  stacks: Record<number, MoveInDarkHead[]>
}

export const NOTHING_MOVING: MoveInDarkState = { projectId: null, stacks: {} }

const STATES: readonly MoveInDarkHeadState[] = ['WAITING', 'MOVING', 'SET', 'LIT_EARLY']
const FAMILIES: readonly MoveInDarkFamily[] = ['POSITION', 'COLOUR', 'BEAM']

function parseHead(raw: unknown, receivedAtMs: number): MoveInDarkHead | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (typeof r.target !== 'string' || typeof r.entryId !== 'number' || typeof r.cueId !== 'number') return null
  if (!STATES.includes(r.state as MoveInDarkHeadState)) return null
  const position = r.position as Record<string, unknown> | null | undefined
  return {
    target: r.target,
    entryId: r.entryId,
    cueId: r.cueId,
    label: typeof r.label === 'string' ? r.label : '',
    state: r.state as MoveInDarkHeadState,
    endsAtMs: typeof r.remainingMs === 'number' ? receivedAtMs + r.remainingMs : null,
    families: Array.isArray(r.families) ? (r.families.filter((f) => FAMILIES.includes(f as MoveInDarkFamily)) as MoveInDarkFamily[]) : [],
    position:
      position != null && typeof position.pan === 'number' && typeof position.tilt === 'number'
        ? { pan: position.pan, tilt: position.tilt }
        : null,
  }
}

/** A `moveInDark.state` frame, received at [receivedAtMs]; a missing list is nothing moving. */
export function parseMoveInDarkFrame(raw: unknown, receivedAtMs: number = Date.now()): MoveInDarkState {
  if (raw == null || typeof raw !== 'object') return NOTHING_MOVING
  const r = raw as Record<string, unknown>
  const stacks: Record<number, MoveInDarkHead[]> = {}
  if (Array.isArray(r.stacks)) {
    for (const entry of r.stacks) {
      const e = entry as Record<string, unknown> | null
      if (e == null || typeof e.stackId !== 'number' || !Array.isArray(e.heads)) continue
      const heads = e.heads.flatMap((h) => parseHead(h, receivedAtMs) ?? [])
      if (heads.length > 0) stacks[e.stackId] = heads
    }
  }
  return { projectId: typeof r.projectId === 'number' ? r.projectId : null, stacks }
}

/** The heads [stackId] moves in [state], none when it is not listed. */
export function moveInDarkHeadsOf(state: MoveInDarkState, stackId: number): MoveInDarkHead[] {
  return state.stacks[stackId] ?? NO_HEADS
}

const NO_HEADS: MoveInDarkHead[] = []

/** The time left on [head]'s delay or move at [nowMs], or null when it is set or lit early. */
export function moveInDarkRemainingMs(head: MoveInDarkHead, nowMs: number): number | null {
  return head.endsAtMs == null ? null : Math.max(0, head.endsAtMs - nowMs)
}

export interface MoveInDarkWsApi {
  /** Every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: MoveInDarkState) => void): Subscription
  /** The last frame, or [NOTHING_MOVING] before the first. */
  get(): MoveInDarkState
}

export function createMoveInDarkWsApi(conn: InternalApiConnection): MoveInDarkWsApi {
  const state = createWsSubscribable<MoveInDarkState>()
  let last: MoveInDarkState = NOTHING_MOVING
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'moveInDark.state') return
    last = parseMoveInDarkFrame(message)
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
  }
}
