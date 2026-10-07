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
 * moves it. Templates carry none (D11) and none of it is DMX (D12). Record captures what the
 * programmer holds (scenery-programmer plan D7, narrowing D13), and Include loads an owner's own rows
 * back into it (D8).
 *
 * The programmer holds scenery too (scenery-programmer plan D1): a runtime overlay above every
 * Look, cue and set, written by `programmer.setScenery` / `programmer.clearScenery` and streamed as
 * `programmer.sceneryState` — that half's wire is `api/programmerWsApi.ts`; its parse is here.
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

/** What holds an element where it is — the tier its highest state comes from (scenery-programmer plan D4). */
export type ScenerySourceKind = 'base' | 'set' | 'cue' | 'cueLook' | 'programmerLook' | 'programmer'

/**
 * The source of an element's live state, as `scenery.state` names it on every entry: its `kind`, and
 * the fields that kind carries — a stack's set (`stackId`, the stack's `name`), a cue (`stackId`,
 * `cueId`, its `label`), a Look a live cue layers (`stackId`, `lookId`, `name`), a pressed Look
 * (`lookId`, `name`). The programmer's own hands and the base carry nothing more. Only the top tier
 * is named: what the tier below would hold is not on the wire.
 */
export interface ScenerySource {
  kind: ScenerySourceKind
  stackId?: number
  cueId?: number
  label?: string
  lookId?: number
  name?: string
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
  /**
   * What holds the piece there (scenery-programmer plan D4, P3). Optional: an older desk does not
   * send it, and a staged entry never carries one — it is the programmer's by definition.
   */
  source?: ScenerySource
}

/** The stage's scenery: every element a change names, by element uuid. Absent elements show their base. */
export interface LiveScenery {
  projectId: number | null
  /** By element uuid. A plain record, not a `Map`: it lives in the RTK store, which wants plain data. */
  entries: Readonly<Record<string, LiveSceneryEntry>>
  /**
   * What leaving Blind would land (scenery-programmer plan D12), by element uuid: present only while
   * the programmer is blind **and** holds a change that differs from live, absent otherwise — and
   * from an older desk, which never sends it (P3).
   */
  staged?: Readonly<Record<string, LiveSceneryEntry>>
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

const SOURCE_KINDS: ReadonlySet<string> = new Set<ScenerySourceKind>(['base', 'set', 'cue', 'cueLook', 'programmerLook', 'programmer'])

/** An entry's `source` off the wire, or undefined for a kind this client does not know (a newer desk's). */
export function parseScenerySource(raw: unknown): ScenerySource | undefined {
  if (raw == null || typeof raw !== 'object') return undefined
  const r = raw as Record<string, unknown>
  if (typeof r.kind !== 'string' || !SOURCE_KINDS.has(r.kind)) return undefined
  const out: ScenerySource = { kind: r.kind as ScenerySourceKind }
  if (typeof r.stackId === 'number') out.stackId = r.stackId
  if (typeof r.cueId === 'number') out.cueId = r.cueId
  if (typeof r.label === 'string') out.label = r.label
  if (typeof r.lookId === 'number') out.lookId = r.lookId
  if (typeof r.name === 'string') out.name = r.name
  return out
}

/** One list of a frame — `elements` or `staged` — by element uuid, anchored at [receivedAtMs]. */
function parseEntries(list: unknown, receivedAtMs: number): Record<string, LiveSceneryEntry> {
  const entries: Record<string, LiveSceneryEntry> = {}
  for (const item of Array.isArray(list) ? list : []) {
    if (item == null || typeof item !== 'object') continue
    const e = item as Record<string, unknown>
    if (typeof e.elementUuid !== 'string') continue
    // The desk's Json drops defaults, so a missing number is its zero.
    const elapsed = typeof e.elapsedMs === 'number' ? Math.max(0, e.elapsedMs) : 0
    const duration = typeof e.durationMs === 'number' ? Math.max(0, e.durationMs) : 0
    const entry: LiveSceneryEntry = {
      elementUuid: e.elementUuid,
      state: parseSceneryState(e.state),
      from: parseSceneryState(e.from),
      startedAtMs: receivedAtMs - elapsed,
      durationMs: duration,
    }
    const source = parseScenerySource(e.source)
    if (source != null) entry.source = source
    entries[e.elementUuid] = entry
  }
  return entries
}

/**
 * A `scenery.state` frame, anchored at [receivedAtMs] (`performance.now()`). `source` and `staged`
 * are read where present and left out where not, so an older desk's frame parses exactly as before.
 */
export function parseSceneryFrame(raw: unknown, receivedAtMs: number): LiveScenery {
  if (raw == null || typeof raw !== 'object') return NO_SCENERY
  const r = raw as Record<string, unknown>
  const out: LiveScenery = {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    entries: parseEntries(r.elements, receivedAtMs),
  }
  if (Array.isArray(r.staged)) out.staged = parseEntries(r.staged, receivedAtMs)
  return out
}

/**
 * The programmer's own scenery (scenery-programmer plan D1): every element it holds, with only the
 * states it holds, in the order first held — `programmer.sceneryState`, the desk's `StateFlow`, so
 * the subscription is the snapshot. Runtime only; Clear empties it and a project switch drops it.
 */
export interface ProgrammerSceneryEntry {
  elementUuid: string
  state: SceneryState
}

export interface ProgrammerScenery {
  projectId: number | null
  elements: readonly ProgrammerSceneryEntry[]
  /**
   * How many held pieces Update would write that the included cue or Look does not already say
   * (scenery-programmer plan session 3) — the scenery half of the source strip's dirty count, counted
   * by the desk because the include target and this frame arrive on two unordered streams. Absent
   * until an Include, a Record or an Update set a baseline, and from an older desk.
   */
  changedSinceInclude?: number
}

export const NO_PROGRAMMER_SCENERY: ProgrammerScenery = { projectId: null, elements: [] }

/** A `programmer.sceneryState` frame. `elements` is always sent by the desk; absent reads as nothing held. */
export function parseProgrammerSceneryFrame(raw: unknown): ProgrammerScenery {
  if (raw == null || typeof raw !== 'object') return NO_PROGRAMMER_SCENERY
  const r = raw as Record<string, unknown>
  const elements: ProgrammerSceneryEntry[] = []
  for (const item of Array.isArray(r.elements) ? r.elements : []) {
    if (item == null || typeof item !== 'object') continue
    const e = item as Record<string, unknown>
    if (typeof e.elementUuid !== 'string') continue
    elements.push({ elementUuid: e.elementUuid, state: parseSceneryState(e.state) })
  }
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    elements,
    ...(typeof r.changedSinceInclude === 'number' ? { changedSinceInclude: r.changedSinceInclude } : {}),
  }
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
