import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * The end of the night (venue-visit plan S6; lighting7 `plugins/RigSafetySocket.kt` is the wire
 * contract): bulk fixture commands, *Make rig safe*, its *Undo*, and *Ready to switch off*.
 *
 * - `rigSafety.state` — the live project's, a `StateFlow`, so the subscription is the connect snapshot
 *   and every change is a whole frame. Outbound only: every operation is REST (`store/rigSafety.ts`).
 *
 * Times left (`remainingMs`, `restrikeRemainingMs`) are measured when the desk sent the frame, so
 * they are anchored here to this browser's receive time ([RigSafetyState.receivedAt]) and counted
 * down from it, as `effects.armed` is.
 */
export type CommandRunStatus = 'QUEUED' | 'WAITING' | 'RUNNING' | 'DONE' | 'CUT_SHORT' | 'SKIPPED' | 'CANCELLED'

export type CommandRole = 'LAMP_ON' | 'LAMP_OFF' | 'RESET'

export type CommandRunPurpose = 'BULK' | 'MAKE_SAFE' | 'UNDO'

export interface CommandRunUnit {
  fixture: string
  fixtureName: string
  command: string
  status: CommandRunStatus
  /** A wait's or a hold's time left when the frame was sent. */
  remainingMs?: number | null
  code?: string | null
  message?: string | null
}

export interface CommandRun {
  id: string
  role: CommandRole
  purpose: CommandRunPurpose
  startedAt: string
  units: CommandRunUnit[]
  withoutCommand?: number
  finished: boolean
  cancelled?: boolean
}

export interface LampOff {
  fixture: string
  offAt: string
  restrikeRemainingMs: number
  /** Time left at the frame until this lamp's mains can be cut; absent from a desk that predates it. */
  coolRemainingMs?: number
}

export interface RigSafetyState {
  projectId: number | null
  /** Lamped off and blacked out by *Make rig safe*, nothing has sent output since, and every lamp has cooled. */
  ready: boolean
  readySince: string | null
  /** Made safe, but a lamp is still cooling: the time left at the frame until the rig is [ready]. */
  coolingRemainingMs: number | null
  /** *Make rig safe*'s blackout holds on this project. */
  blackout: boolean
  /** The units *Undo* would strike. */
  undo: { fixture: string; fixtureName: string }[]
  /** Lamped units on the rig not known to be off after the last *Make rig safe*, by name. */
  notOff: string[]
  run: CommandRun | null
  lampsOff: LampOff[]
  /** This window may quit the desk: an admin on the desk's own listener. */
  quitAllowed: boolean
  /** `Date.now()` when this frame arrived, which every time left counts from. */
  receivedAt: number
}

export const NOTHING_SAFE: RigSafetyState = {
  projectId: null,
  ready: false,
  readySince: null,
  coolingRemainingMs: null,
  blackout: false,
  undo: [],
  notOff: [],
  run: null,
  lampsOff: [],
  quitAllowed: false,
  receivedAt: 0,
}

/** A `rigSafety.state` frame. Every list may be absent on a desk that drops defaults. */
export function parseRigSafetyFrame(raw: unknown, receivedAt: number): RigSafetyState {
  if (raw == null || typeof raw !== 'object') return { ...NOTHING_SAFE, receivedAt }
  const r = raw as Record<string, unknown>
  const run = r.run && typeof r.run === 'object' ? (r.run as CommandRun) : null
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    ready: r.ready === true,
    readySince: typeof r.readySince === 'string' ? r.readySince : null,
    coolingRemainingMs: typeof r.coolingRemainingMs === 'number' ? r.coolingRemainingMs : null,
    blackout: r.blackout === true,
    undo: Array.isArray(r.undo) ? (r.undo as RigSafetyState['undo']) : [],
    notOff: Array.isArray(r.notOff) ? (r.notOff as string[]) : [],
    run: run ? { ...run, units: Array.isArray(run.units) ? run.units : [], finished: run.finished === true } : null,
    lampsOff: Array.isArray(r.lampsOff) ? (r.lampsOff as LampOff[]) : [],
    quitAllowed: r.quitAllowed === true,
    receivedAt,
  }
}

export interface RigSafetyWsApi {
  /** Every frame; a late subscriber gets the last one at once. */
  subscribe(fn: (state: RigSafetyState) => void): Subscription
  /** The last frame, or [NOTHING_SAFE] before the first. */
  get(): RigSafetyState
}

export function createRigSafetyWsApi(conn: InternalApiConnection): RigSafetyWsApi {
  const state = createWsSubscribable<RigSafetyState>()
  let last: RigSafetyState = NOTHING_SAFE
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'rigSafety.state') return
    last = parseRigSafetyFrame(message, Date.now())
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
