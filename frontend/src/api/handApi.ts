import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'
import type { BuskCue, BuskPadKind } from './buskApi'
import type { LookSummary } from './looksApi'
import type { TemplateSummary } from './templatesApi'
import { parseSelectionSource, type SelectionSource } from './selectionApi'

/**
 * The `hand.*` family — the desk's **one held record**, picked up on any window and placed on any
 * other (multi-screen plan §3.5, D12; lighting7 `plugins/HandSocket.kt` and `state/HandState.kt`,
 * which are the wire contract wherever this comment and the plan's sketch differ).
 *
 * It exists because a pointer drag cannot cross an OS window boundary: the window that saw the
 * press keeps the pointer for the whole gesture, so the neighbour never receives a pointer event of
 * its own. The hand replaces one drag with two half-gestures the desk holds between.
 *
 * **There are three frames and none of them places** (D12). Every target a place can land on
 * already has a mutation with its own validation — `POST /busk/banks/{bankId}/pads`,
 * `assignCueSlot`, `programmer.addLayer`, `patchProjectCue` through `buildCueInput` — so one frame
 * that placed would reimplement four of them behind one name. **A place is the placing window's own
 * mutation followed by [HandWsApi.drop]**, and Undo is that window's inverse mutation. The desk's
 * whole share of a place is letting go. Do not add a `hand.place`.
 *
 * **The frame carries the record's own summary DTOs**, exactly as `BuskPadDto` does, so every
 * window draws the ghost from this frame alone with no second fetch — which is what lets `HandChip`
 * build its face through `padFaceOf` and be frozen and hookless (`padFace.ts`'s rule). The
 * corollary lighting7 recorded as `FU-DTO-RECORD-SUMMARY`: the embedded summaries' `usage` and
 * `buskPageCount` are computed **at pick-up**, so a chip held for five minutes can still read
 * "on 3 pages" after a fourth was added. That is intended — a pad's face is frozen between reads
 * too — but nothing may present those two fields here as live.
 *
 * **Project-scoped**, unlike `windows.*`: the ids a hold carries belong to one project's rows, so
 * the desk's project collector drops it. Two other things end a hold — a five-minute timeout armed
 * on every pick-up, and a reconcile from the three list-changed listeners, so a record deleted
 * while held leaves every window's chip. A record *edited* while held keeps its place and its
 * frozen face.
 *
 * There is deliberately **no `open` branch** in this module, and it is the `selectionApi` rule
 * rather than the `windowsApi` one: the hand is a `StateFlow` the desk pushes on every connect, so
 * a reconnecting tab is told what is held without asking, and a write on connect would be this
 * window silently changing the desk's hand.
 */

/** What the desk is holding. Exactly one of [template] / [look] / [cue] is set, matching [kind]. */
export interface HeldRecord {
  kind: BuskPadKind
  /** The record's int id **in the current project** — what every place mutation takes. */
  id: number
  /** The record's uuid, as a string. What a guarded [HandWsApi.drop] names. */
  uuid: string
  template?: TemplateSummary | null
  look?: LookSummary | null
  cue?: BuskCue | null
  /**
   * Who picked it up — the same `SelectionSource` `selection.state`'s `source` carries, stamped by
   * the desk from the socket's announced window (D7) and never sent. Null for a socket that has
   * announced nothing; kind `surface` for a MIDI pick-up.
   */
  pickedUpOn: SelectionSource | null
  /**
   * **The identity of *this* hold**, monotonic and server-stamped.
   *
   * It exists because `MutableStateFlow` conflates by `equals`: two pick-ups of one record inside a
   * single clock tick would otherwise be indistinguishable, the second assignment a no-op, and the
   * desk left holding an item with no armed timeout. On this side it is what a React key must
   * carry when a re-pick-up should remount — `uuid` alone would not move.
   */
  holdId: number
  pickedUpAtMs: number
  expiresAtMs: number
}

export interface HandWsApi {
  /** What is held, or null for an empty hand. Connect snapshot and broadcast, one frame type. */
  subscribe(fn: (held: HeldRecord | null) => void): Subscription
  /**
   * The last frame, or null before the first — for an RTK Query `queryFn` seeding its cache entry
   * without a subscription. Note the two nulls are different: this answers `null` both for "no
   * frame yet" and for "the hand is empty", which is the right conflation for a reader that only
   * ever asks *what is held*.
   */
  getState(): HeldRecord | null
  /**
   * Take `{kind, id}` into the hand. A second pick-up replaces; there is no "put it back".
   *
   * **Answers whether the frame left the browser** — `sendGesture`'s own return. Every door but one
   * ignores it, because a pick-up that went nowhere has already toasted and the operator's next
   * gesture is simply to press again. The edge drag reads it: it cancels a live drag in exchange
   * for the pick-up, and cancelling for a frame that never arrived would take the gesture away and
   * give nothing back.
   */
  pickUp(kind: BuskPadKind, id: number): boolean
  /**
   * Let go.
   *
   * **Pass `uuid` after a place**, naming the record this window believes it placed. A place is two
   * independent round-trips — the window's own mutation, then this — and another window may have
   * picked something up in the gap, so a bare drop would clear an item this window never touched,
   * on exactly the two-screen case the hand exists for. With a `uuid` the desk lets go only if that
   * record is what it holds.
   *
   * **Omit it for the chip's × and for Escape**, where "let go of whatever is there" is precisely
   * what the operator means.
   */
  drop(uuid?: string): void
}

interface HandStateMessage {
  type: 'hand.state'
  item?: unknown
}

/**
 * Read a `hand.state` payload, or null for an empty hand and for anything malformed.
 *
 * The exactly-one-summary rule the desk asserts on its side is **not** re-asserted here: a frame
 * naming two would already have failed `Held`'s own `require`, and a client that threw on one would
 * turn a server bug into a blank desk. `padFaceOf` degrades a mismatched pair to its `Missing`
 * face, which is the honest reading.
 */
export function parseHeldRecord(raw: unknown): HeldRecord | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (r.kind !== 'TEMPLATE' && r.kind !== 'LOOK' && r.kind !== 'CUE') return null
  if (typeof r.id !== 'number' || typeof r.uuid !== 'string') return null
  return {
    kind: r.kind,
    id: r.id,
    uuid: r.uuid,
    template: (r.template as TemplateSummary | undefined) ?? null,
    look: (r.look as LookSummary | undefined) ?? null,
    cue: (r.cue as BuskCue | undefined) ?? null,
    pickedUpOn: parseSelectionSource(r.pickedUpOn),
    // The desk's Json drops defaults, so an absent stamp is the declared zero. Reading them as 0
    // rather than refusing the frame keeps a hold usable even if the desk ever stops sending them;
    // nothing on this side does arithmetic on them that a 0 would corrupt.
    holdId: typeof r.holdId === 'number' ? r.holdId : 0,
    pickedUpAtMs: typeof r.pickedUpAtMs === 'number' ? r.pickedUpAtMs : 0,
    expiresAtMs: typeof r.expiresAtMs === 'number' ? r.expiresAtMs : 0,
  }
}

/**
 * Whole-hold equality, so a repeated frame does not churn the cache entry.
 *
 * **All four fields, and `holdId` is not enough on its own.** Within one desk process it would be —
 * that is what it exists for, and why a re-pick-up of the same record is a *different* hold readers
 * must see. But it is a per-process monotonic counter, so a desk restart takes it back to zero: the
 * first hold after a restart can carry a `holdId` this client still has cached against a **different
 * record**, and comparing that field alone would read the new hold as the one already on screen and
 * suppress the frame, leaving a stale chip naming something nobody is holding. The other three are
 * what make that collision impossible.
 */
export function sameHeldRecord(a: HeldRecord | null, b: HeldRecord | null): boolean {
  if (a == null || b == null) return a === b
  return a.holdId === b.holdId && a.kind === b.kind && a.id === b.id && a.uuid === b.uuid
}

export function createHandWsApi(conn: InternalApiConnection): HandWsApi {
  const hand = createWsSubscribable<HeldRecord | null>()
  let last: HeldRecord | null = null
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as HandStateMessage | null
    if (message?.type !== 'hand.state') return
    const held = parseHeldRecord(message.item)
    // An empty hand arriving twice is one frame's worth of work, not two renders.
    if (seen && sameHeldRecord(last, held)) return
    seen = true
    last = held
    hand.notify(held)
  })

  return {
    subscribe: (fn) => {
      const sub = hand.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    getState: () => last,
    pickUp: (kind, id) => sendGesture(conn, { type: 'hand.pickUp', kind, id }),
    // `uuid` is omitted rather than sent as undefined: the desk reads an absent field as "let go of
    // whatever is there", and `JSON.stringify` would drop an explicit `undefined` to the same
    // thing — but saying it here is what makes the two meanings visible at the call site.
    drop: (uuid) => sendGesture(conn, uuid == null ? { type: 'hand.drop' } : { type: 'hand.drop', uuid }),
  }
}
