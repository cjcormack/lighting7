import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'
import type { CueTarget } from './cuesApi'
import type { AttributeFamily } from '../lib/attributeFamily'
import { normaliseFamilies, parseFamilies, sameFamilies } from '../lib/selectionMask'

/**
 * The **desk selection** — one selection per project, server-owned and shared by every client,
 * for the reason the programmer is: DMX is one byte per channel, so two selections would force a
 * "whose press wins?" policy nothing expresses. It is transient (cleared on project switch, never
 * persisted) and a target that stops resolving is dropped from it server-side, so no client needs
 * a second rule for that.
 *
 * **It is one fact with three parts** (multi-screen plan D2, D7): the `targets`, the attribute
 * `families` a press on them is masked to, and the `source` that moved it last. The mask is part
 * of the selection and not a second fact because a mask without targets means nothing and clearing
 * the selection must clear it; the rule that keeps them one fact is the desk's: `set` replaces the
 * whole fact (absent families = every attribute), `toggle` edits the heads and keeps the mask,
 * `clear` drops both.
 *
 * `selection.state` is both the snapshot on connect and the broadcast on every change — one frame
 * type, because it is a `StateFlow` and a delta frame would carry nothing the whole fact doesn't.
 * Both new fields are **omitted** when absent (the desk's Json drops defaults), never null: absent
 * `families` is every attribute, absent `source` is nobody since the last clear. A `set` of the
 * same heads from a different mover *does* emit a frame — that is what the desk chip reads.
 *
 * `source` is never sent: the desk stamps it from the socket's **announced window** (D7) — the
 * `windows.announce` this tab sends on every connect (`api/windowsApi.ts`), which carries a name
 * *and* buys the row id `source.id` names. Session 1's `sourceName` on each write is gone from this
 * side; the desk still accepts it as a fallback until `FU-WINDOWS-RETIRE-SOURCENAME` deletes it.
 * The three writes get no reply; the state frame is the acknowledgement.
 *
 * A group and one of its members are two separate entries: what the desk holds is what was
 * *said*, and expanding a group to its heads is a question asked later (server-side `coverage()`),
 * not a normalisation done on the way in. That is what lets a select button's LED light for the
 * group rather than for eight loose fixtures.
 */
export interface SelectionSource {
  /** A browser window, by the name it announced, or a control surface. */
  kind: 'window' | 'surface'
  /**
   * The window's socket-minted row id in `windows.state` — what the desk chip compares against
   * *its own* row's id. Absent for a surface write and for a socket that never announced (a
   * pre-registry client on the `sourceName` fallback), where the chip falls back to the name.
   */
  id?: string
  name: string
}

/**
 * The ways `selection.subselect` rewrites the selection's **targets** (busk-further plan D12) — the
 * Cells menu and its two step buttons, and a MIDI `SelectionCells` / `SelectionNext` / `SelectionPrev`
 * button, sharing one rule on the desk. The nine names mirror `SubselectMode` in
 * lighting7's `state/DeskSelection.kt`, and `cellsSubSelection.test.ts` pins the list against the
 * server's own fixture: a tenth mode there fails here. The frame carries the name and nothing else —
 * the mask is kept, and the desk stamps `source` from the announce as for every other write.
 */
export const SUBSELECT_MODES = [
  'ALL',
  'ODD',
  'EVEN',
  'FIRST_HALF',
  'SECOND_HALF',
  'INVERT',
  'NEXT',
  'PREV',
  'MASTERS',
] as const
export type SubselectMode = (typeof SUBSELECT_MODES)[number]

export interface DeskSelectionSnapshot {
  /** The desk selection, in the order targets were added. */
  targets: CueTarget[]
  /** The attribute mask. `null` is every attribute — the one spelling of "no mask". */
  families: AttributeFamily[] | null
  /** Who moved it last. `null` after a clear, a project switch, or a write from an unnamed socket. */
  source: SelectionSource | null
}

export interface SelectionWsApi {
  /** The whole fact, on connect and on every change. */
  subscribe(fn: (snapshot: DeskSelectionSnapshot) => void): Subscription
  /**
   * The last frame, or null before the first. For a reader that is not a subscriber — an RTK
   * Query `queryFn` seeding its cache entry — the same value without one.
   */
  getState(): DeskSelectionSnapshot | null

  /** Replace the whole fact. [families] absent or null clears the mask. */
  set(targets: CueTarget[], families?: readonly AttributeFamily[] | null): void
  /** Add the target, or take it off if its heads are already covered. The mask is kept. */
  toggle(target: CueTarget): void
  /** Nothing selected, no mask, no mover. */
  clear(): void
  /**
   * Rewrite the targets by [mode] over the desk's rig order. The mask is kept; the answer is the
   * ordinary `selection.state` frame, so the following arm needs nothing but this send. An unlinked
   * window does not send this — it mirrors the rule client-side (`lib/cellsSubSelection.ts`).
   */
  subselect(mode: SubselectMode): void
}

interface SelectionStateMessage {
  type: 'selection.state'
  targets: CueTarget[]
  families?: unknown
  source?: unknown
}

/** Read the frame's `source`, or null for anything that is not one. */
export function parseSelectionSource(raw: unknown): SelectionSource | null {
  if (raw == null || typeof raw !== 'object') return null
  const { kind, id, name } = raw as { kind?: unknown; id?: unknown; name?: unknown }
  if ((kind !== 'window' && kind !== 'surface') || typeof name !== 'string') return null
  return typeof id === 'string' ? { kind, id, name } : { kind, name }
}

export function sameSelectionSource(a: SelectionSource | null, b: SelectionSource | null): boolean {
  if (a == null || b == null) return a === b
  return a.kind === b.kind && a.id === b.id && a.name === b.name
}

function sameTargets(a: readonly CueTarget[], b: readonly CueTarget[]): boolean {
  return a.length === b.length && a.every((t, i) => t.type === b[i]!.type && t.key === b[i]!.key)
}

/** Whole-fact equality — what "did the selection actually change?" means for every reader. */
export function sameSelectionSnapshot(a: DeskSelectionSnapshot, b: DeskSelectionSnapshot): boolean {
  return (
    sameTargets(a.targets, b.targets) &&
    sameFamilies(a.families, b.families) &&
    sameSelectionSource(a.source, b.source)
  )
}

/**
 * The frame as a snapshot, **keeping the previous frame's identities for the parts that did not
 * move**. A source-only frame (another window set the same heads) then hands every reader keyed on
 * `targets` the same array it already had, so the bridge's apply effect and the busk band's
 * rehydration do not re-run for a change that is only the chip's.
 */
export function decodeSelectionState(
  message: SelectionStateMessage,
  previous: DeskSelectionSnapshot | null,
): DeskSelectionSnapshot {
  const targets = message.targets ?? []
  const families = parseFamilies(message.families)
  return {
    targets: previous != null && sameTargets(previous.targets, targets) ? previous.targets : targets,
    families:
      previous != null && sameFamilies(previous.families, families) ? previous.families : families,
    source: parseSelectionSource(message.source),
  }
}

export function createSelectionWsApi(conn: InternalApiConnection): SelectionWsApi {
  const selection = createWsSubscribable<DeskSelectionSnapshot>()
  let last: DeskSelectionSnapshot | null = null

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as SelectionStateMessage | null
    if (message?.type !== 'selection.state') return
    last = decodeSelectionState(message, last)
    selection.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = selection.api.subscribe(fn)
      if (last != null) fn(last)
      return sub
    },
    getState: () => last,
    set: (targets, families) =>
      sendGesture(conn, {
        type: 'selection.set',
        targets,
        // The one spelling: none or all four is no mask, and the desk would fold either to null
        // anyway — sending it folded is what lets the echo compare equal to what was sent.
        families: normaliseFamilies(families) ?? undefined,
      }),
    toggle: (target) => sendGesture(conn, { type: 'selection.toggle', target }),
    clear: () => sendGesture(conn, { type: 'selection.clear' }),
    subselect: (mode) => sendGesture(conn, { type: 'selection.subselect', mode }),
  }
}
