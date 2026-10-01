import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * Scenery on cues, stacks and Looks (stage-view plan session 8; lighting7 `models/scenery.kt`,
 * `show/SceneryResolver.kt` and `plugins/ScenerySocket.kt` are the wire contract).
 *
 * A **scenery change** is an element of the scene document and the states it takes: `visible`
 * (any element), `open` (a drawn drape — 0 closed … 1 drawn) and `trimM` (a flown piece's height).
 * A cue's changes move on GO into it, each on its own clock; a stack's are its *set*, held while it
 * is live; a Look's show while it is live. Scenery **tracks**: a change stays put until something
 * moves it. Templates carry none (D11), Record captures none (D13), and none of it is DMX (D12).
 *
 * The desk resolves what the stage shows and pushes it as `scenery.state` — a `StateFlow`, so the
 * subscription is the connect snapshot and there is nothing to re-request on reconnect (no `open`
 * branch here, the `handApi` rule).
 */

/** An element's scenery states. Each is present only where the change or the resolution sets it. */
export interface SceneryState {
  visible?: boolean
  open?: number
  trimM?: number
}

/** One stored change, as an owner's DTO carries it and a whole-list `PUT` answers it. */
export interface SceneryChange {
  uuid: string
  elementUuid: string
  elementName: string
  elementKind: string
  state: SceneryState
  /** A cue's own clock for the move; null (or absent) moves with the cue's fade. Never set off a cue. */
  transitionMs?: number | null
  sortOrder: number
}

/**
 * What a cue shows that it does not move itself: tracked from an earlier cue of its stack
 * (`fromCueLabel`) or held by the stack's set (`fromSet`). Read-only.
 */
export interface TrackedScenery {
  elementUuid: string
  elementName: string
  state: SceneryState
  fromCueId?: number | null
  fromCueLabel?: string | null
  fromSet?: boolean
}

/** One change of a whole-list write: `PUT cues|cue-stacks|looks/{id}/scenery`. */
export interface SceneryWriteItem {
  elementUuid: string
  state: SceneryState
  /** A cue's only; the desk refuses it on a stack's set or a Look. */
  transitionMs?: number | null
}

/** One element's scenery as the Next GO preview answers it: where it would go, from where it is. */
export interface PreviewScenery {
  elementUuid: string
  state: SceneryState
  from: SceneryState
  durationMs: number
}

/**
 * One element's live scenery, anchored to **this** browser's clock: `startedAtMs` is on
 * `performance.now()`'s timeline, computed from the frame's `elapsedMs` at receipt, so a tablet with
 * a skewed wall clock does not replay a move that has finished (the `cueRunStateChanged` rule).
 */
export interface LiveSceneryEntry {
  elementUuid: string
  state: SceneryState
  from: SceneryState
  startedAtMs: number
  durationMs: number
}

/** The stage's scenery: every element a change names, by element uuid. Absent elements show their base. */
export interface LiveScenery {
  projectId: number | null
  /** By element uuid. A plain record, not a `Map`: it lives in the RTK store, which wants plain data. */
  entries: Readonly<Record<string, LiveSceneryEntry>>
}

export const NO_SCENERY: LiveScenery = { projectId: null, entries: {} }

/** A state object off the wire, keeping only well-typed states. */
export function parseSceneryState(raw: unknown): SceneryState {
  if (raw == null || typeof raw !== 'object') return {}
  const r = raw as Record<string, unknown>
  const out: SceneryState = {}
  if (typeof r.visible === 'boolean') out.visible = r.visible
  if (typeof r.open === 'number' && Number.isFinite(r.open)) out.open = r.open
  if (typeof r.trimM === 'number' && Number.isFinite(r.trimM)) out.trimM = r.trimM
  return out
}

/** A `scenery.state` frame, anchored at [receivedAtMs] (`performance.now()`). */
export function parseSceneryFrame(raw: unknown, receivedAtMs: number): LiveScenery {
  if (raw == null || typeof raw !== 'object') return NO_SCENERY
  const r = raw as Record<string, unknown>
  const entries: Record<string, LiveSceneryEntry> = {}
  for (const item of Array.isArray(r.elements) ? r.elements : []) {
    if (item == null || typeof item !== 'object') continue
    const e = item as Record<string, unknown>
    if (typeof e.elementUuid !== 'string') continue
    // The desk's Json drops defaults, so a missing number is its zero.
    const elapsed = typeof e.elapsedMs === 'number' ? Math.max(0, e.elapsedMs) : 0
    const duration = typeof e.durationMs === 'number' ? Math.max(0, e.durationMs) : 0
    entries[e.elementUuid] = {
      elementUuid: e.elementUuid,
      state: parseSceneryState(e.state),
      from: parseSceneryState(e.from),
      startedAtMs: receivedAtMs - elapsed,
      durationMs: duration,
    }
  }
  return { projectId: typeof r.projectId === 'number' ? r.projectId : null, entries }
}

export interface SceneryWsApi {
  /** The stage's scenery on every change. The desk pushes it on connect, so a late subscriber gets the last one. */
  subscribe(fn: (scenery: LiveScenery) => void): Subscription
  /** The last frame, or [NO_SCENERY] before the first — for an RTK Query `queryFn` seeding its entry. */
  getState(): LiveScenery
}

export function createSceneryWsApi(conn: InternalApiConnection): SceneryWsApi {
  const scenery = createWsSubscribable<LiveScenery>()
  let last: LiveScenery = NO_SCENERY
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'scenery.state') return
    last = parseSceneryFrame(message, performance.now())
    seen = true
    scenery.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = scenery.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    getState: () => last,
  }
}
