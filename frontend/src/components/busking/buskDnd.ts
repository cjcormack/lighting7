import type { Active, ClientRect } from '@dnd-kit/core'
import type { BuskPage } from '@/api/buskApi'
import type { BuskRig } from '@/api/buskRigApi'
import {
  dropTargetFor,
  parseBuskDragId,
  type BankAddress,
  type DragSource,
  type DropTarget,
  type PadAddress,
  type PaletteRecord,
  type ParsedBuskId,
} from '@/lib/buskLayout'
import {
  parseRigDragId,
  rigDropTargetFor,
  type ParsedRigId,
  type RigDragSource,
  type RigDropTarget,
  type RigPaletteRecord,
  type RigTileAddress,
} from '@/lib/buskRig'
import type { PadFace } from './padFace'

/**
 * What a busk drag carries, and how deep the thing it is over sits.
 *
 * The busk page shares the app's single `DndContext` with the FX cue-slot grid (see
 * `dnd/DeskDndProvider.tsx`), so both ends of every handler are typed and **both ignore what they
 * do not recognise**. That mutual ignorance is the whole coexistence mechanism: no registry, no
 * priority, no shared enum.
 */

export interface BuskPadDragData {
  type: 'busk-pad'
  at: PadAddress
  face: PadFace
}

export interface BuskBankDragData {
  type: 'busk-bank'
  at: BankAddress
  name: string
  padCount: number
}

export interface BuskPaletteDragData {
  type: 'busk-palette'
  record: PaletteRecord
  face: PadFace
  /**
   * Whether a cue slot could take this — a cue, or a Look with no deferred effect (D7).
   *
   * Derived at the palette rather than at the slot, because the palette is the surface that knows
   * what each row is, and one answer to "can this land in a place with no selection" is better than
   * two. Read in two places: `dnd/slotDrop.ts` gates the drop on it (and re-checks the record kind,
   * so a wrong value here cannot put a template in a slot), and `LibraryPalette` dims the row while
   * a slot is the drop target.
   */
  slotEligible: boolean
}

export type BuskDragData = BuskPadDragData | BuskBankDragData | BuskPaletteDragData

/**
 * The rig's three sources (busk-further plan session 3). Same contract, other document: the rig
 * band joins the one `DndContext` with `rig-` prefixed data and `r…` ids, and neither the page's
 * monitor nor the cue-slot handler recognises either.
 */
export interface RigPaletteDragData {
  type: 'rig-palette'
  record: RigPaletteRecord
  name: string
}

export interface RigTileDragData {
  type: 'rig-tile'
  at: RigTileAddress
  name: string
}

export interface RigRowDragData {
  type: 'rig-row'
  row: number
  name: string
  tileCount: number
}

export type RigDragData = RigPaletteDragData | RigTileDragData | RigRowDragData

export function rigDragData(active: Active | null): RigDragData | null {
  const data = active?.data.current
  if (data == null) return null
  if (data.type === 'rig-palette' || data.type === 'rig-tile' || data.type === 'rig-row') {
    return data as RigDragData
  }
  return null
}

export function rigDragSourceOf(data: RigDragData): RigDragSource {
  if (data.type === 'rig-palette') return { kind: 'rig-palette', record: data.record }
  if (data.type === 'rig-tile') return { kind: 'rig-tile', at: data.at }
  return { kind: 'rig-row', row: data.row }
}

/** A drop target on the rig, which the page's droppables never carry. */
export interface RigDropData {
  type: 'rig-drop'
  target: RigDropTarget
  depth: number
}

export const RIG_DROP_DEPTH = { tile: 3, rowBody: 2, rowGap: 1, newRow: 0 } as const

export interface BuskDropData {
  type: 'busk-drop'
  target: DropTarget
  /**
   * How deeply nested this droppable is, largest wins.
   *
   * A pad sits geometrically inside its bank's body, so a pointer inside the pad is inside both;
   * `pointerWithin` returns every containing droppable and only *usually* puts the innermost first.
   * Reading the deepest busk drop out of `event.collisions` makes it deterministic.
   */
  depth: number
}

export const DROP_DEPTH = { pad: 3, bankBody: 2, bankUnder: 2, gutter: 1, newRow: 0 } as const

export function buskDragData(active: Active | null): BuskDragData | null {
  const data = active?.data.current
  if (data == null) return null
  if (data.type === 'busk-pad' || data.type === 'busk-bank' || data.type === 'busk-palette') {
    return data as BuskDragData
  }
  return null
}

export function dragSourceOf(data: BuskDragData): DragSource {
  if (data.type === 'busk-palette') return { kind: 'palette', record: data.record }
  if (data.type === 'busk-pad') return { kind: 'pad', at: data.at }
  return { kind: 'bank', at: data.at }
}

/**
 * Which side of the pad it was dropped on: 0 to land before it, 1 to land after.
 *
 * The axis is chosen per drag rather than per bank, because a `WRAP` bank lays its pads out in rows
 * *and* columns — the operator crossing a pad from the left means something different from crossing
 * it from above, and the bank's `flow` cannot tell you which they did. Whichever axis the two
 * centres differ on more is the one they were moving along.
 */
export function insertionSide(activeRect: ClientRect | null, overRect: ClientRect): 0 | 1 {
  if (activeRect == null) return 0
  const dx = activeRect.left + activeRect.width / 2 - (overRect.left + overRect.width / 2)
  const dy = activeRect.top + activeRect.height / 2 - (overRect.top + overRect.height / 2)
  const along = Math.abs(dx) >= Math.abs(dy) ? dx : dy
  return along > 0 ? 1 : 0
}

/**
 * How deep a parsed droppable id sits, for the largest-wins rule above.
 *
 * Derived from the **id** rather than read off the droppable's `data`, because a collision only
 * reports an id and reaching its container's data through `collisions[n].data` is both awkward and
 * undocumented. The id already says what it names.
 */
export function depthOf(parsed: ParsedBuskId): number {
  switch (parsed.kind) {
    case 'pad':
      return DROP_DEPTH.pad
    case 'bank-body':
      return DROP_DEPTH.bankBody
    case 'bank-under':
      return DROP_DEPTH.bankUnder
    case 'gutter':
      return DROP_DEPTH.gutter
    case 'new-row':
      return DROP_DEPTH.newRow
    default:
      return -1
  }
}

/** Do two hover targets name the same landing place? A repeat hover must write no state. */
export function sameTarget<T extends DropTarget | RigDropTarget>(a: T | null, b: T | null): boolean {
  if (a == null || b == null) return a === b
  return JSON.stringify(a) === JSON.stringify(b)
}

/** Every source either surface can lift. */
export type AnyDragSourceKind = DragSource['kind'] | RigDragSource['kind']

/** Every landing place either surface registers. */
export type AnyDroppableKind = ParsedBuskId['kind'] | ParsedRigId['kind']

/**
 * Which droppable kinds a source can land on.
 *
 * A pad or a palette row lands in a bank — on a pad, or on the body for an append; a bank lands on
 * the three bank zones. On the rig, a tile or a palette target lands on a tile, a row body or the
 * new-row zone, and a row lands on a row gap. **A rig source lands nowhere on the page and a page
 * source nowhere on the rig**: the two documents share one drag context and one pointer, and a
 * palette row dropped on a rig row is not a gesture. The droppables are also `disabled` per source
 * in the components (so dnd-kit's `over` never lights an illegal target), but the rule is stated
 * here as well because this is the half a test can reach: before it was, a bank drag over a pad
 * resolved to a pad target, drew a dashed slot inside the bank, and then went nowhere on drop —
 * `dropBank` refuses a pad target, so the gesture ended with no commit and no request.
 */
function canLand(source: AnyDragSourceKind, kind: AnyDroppableKind): boolean {
  switch (source) {
    case 'bank':
      return kind === 'bank-under' || kind === 'gutter' || kind === 'new-row'
    case 'pad':
    case 'palette':
      return kind === 'pad' || kind === 'bank-body'
    case 'rig-row':
      return kind === 'rig-row-gap'
    case 'rig-tile':
    case 'rig-palette':
      return kind === 'rig-tile' || kind === 'rig-row-body' || kind === 'rig-new-row'
  }
}

/** Exported for `buskDnd.test.ts` alone, which pins the cross-family refusals. */
export const canLandForTest = canLand

/** The deepest landing place, among everything the pointer is inside, that the source may take. */
function deepest(source: DragSource['kind'], collisionIds: readonly string[]): ParsedBuskId | null {
  let best: ParsedBuskId | null = null
  let bestDepth = -1
  for (const id of collisionIds) {
    const parsed = parseBuskDragId(id)
    if (parsed == null || !canLand(source, parsed.kind)) continue
    const depth = depthOf(parsed)
    if (depth > bestDepth) {
      best = parsed
      bestDepth = depth
    }
  }
  return best
}

function sameBank(a: BankAddress, b: BankAddress): boolean {
  return a.row === b.row && a.column === b.column && a.bank === b.bank
}

/**
 * How far the pointer must travel before an open slot may relocate.
 *
 * **The operator's pointer moves the slot; the slot does not move the slot.** Opening a slot
 * physically displaces the pads after it, so with a stationary pointer the pad *underneath* it
 * changes — and the answer to "what are you over" changes with it, which moves the slot, which
 * displaces the pads back. Observed on the desk between two banks in one column: the slot flipped
 * between them every two or three frames while the pointer moved **one pixel**, and because
 * `BuskEditProvider` forces a re-measure on every target change, React counted the nested updates
 * and threw *Maximum update depth exceeded* rather than merely flickering.
 *
 * The stickiness above this could not catch it: it holds `current` only while the pointer is in the
 * body of the **same** bank, and this oscillation is precisely *between* banks — "entering another
 * bank's body still appends" is the rule it is written to allow. Bank scope is the wrong axis;
 * pointer movement is the right one, because it is the one thing the slot cannot change.
 *
 * Six pixels, against the pointer sensor's own 8px activation: large enough to swallow the
 * displacement (which is zero real movement) and small enough that a deliberate drag never notices.
 */
export const TARGET_HYSTERESIS_PX = 6

/**
 * Where a hover would land, or null for no landing place.
 *
 * Pure, and separated from the monitor that calls it for the reason every reducer in this feature
 * is: the alternative is a jsdom pointer sequence against a `DndContext` whose rects are all zero,
 * which tests dnd-kit rather than this decision.
 *
 * `current` is the target already showing, and it matters for one case: the pointer is inside a
 * bank's **body** but on no pad, while the slot is already open in that bank. Opening the slot is
 * what put the pointer there — the pad it was over shifted along by one cell, and the dashed slot
 * that took its place is not a droppable — so collapsing to the body's append would send the slot
 * to the end of the bank the moment it opened. Keeping `current` is what makes the slot stay put.
 * Entering a *different* bank's body still appends, which is the only way into an empty one.
 */
export function resolveDropTarget(args: {
  page: BuskPage
  source: DragSource['kind']
  activeId: string
  overId: string | null
  collisionIds: readonly string[]
  activeRect: ClientRect | null
  overRect: ClientRect | null
  current?: DropTarget | null
  /**
   * How far the pointer has travelled since [current] was chosen, in client pixels. Omitted (or
   * `Infinity`) means "no anchor yet", which is the first resolve of a drag.
   */
  movedSinceTarget?: number
}): DropTarget | null {
  const {
    page,
    source,
    activeId,
    overId,
    collisionIds,
    activeRect,
    overRect,
    current = null,
    movedSinceTarget = Number.POSITIVE_INFINITY,
  } = args
  // **Self-hover stays ahead of the hysteresis.** A pad is its own droppable as well as a draggable,
  // and hovering yourself is not a gesture — that rule predates the hysteresis and must outrank it,
  // or easing back over the dragged pad within the threshold would hold the neighbouring gap and
  // *commit* it on release. A stale commit is worse than a flicker, and this ordering is simply the
  // behaviour that was here before.
  if (overId === activeId) return null
  // The hysteresis, though, sits **ahead of the `overId == null` arm**: that one is displacement, not
  // the operator. The dashed slot is no droppable, so opening it can leave the pointer over nothing
  // at all — and closing the slot on that is the same feedback loop with an extra frame in it.
  // See TARGET_HYSTERESIS_PX.
  if (current != null && movedSinceTarget < TARGET_HYSTERESIS_PX) return current
  if (overId == null) return null

  const parsed = deepest(source, collisionIds.length > 0 ? collisionIds : [overId])
  if (parsed == null) return null

  if (parsed.kind === 'bank-body' && current?.kind === 'pad' && sameBank(current.at, parsed.at)) {
    return current
  }

  const target = dropTargetFor(parsed, page)
  if (target?.kind !== 'pad' || overRect == null) return target

  // The half-of-the-pad test only means anything when the pad that won is the one dnd-kit measured.
  const overParsed = parseBuskDragId(overId)
  if (
    overParsed?.kind !== 'pad' ||
    overParsed.at.row !== target.at.row ||
    overParsed.at.column !== target.at.column ||
    overParsed.at.bank !== target.at.bank ||
    overParsed.at.pad !== target.at.pad
  ) {
    return target
  }
  return { kind: 'pad', at: { ...target.at, pad: target.at.pad + insertionSide(activeRect, overRect) } }
}

// ─── The rig's resolver ─────────────────────────────────────────────────

function rigDepthOf(parsed: ParsedRigId): number {
  switch (parsed.kind) {
    case 'rig-tile':
      return RIG_DROP_DEPTH.tile
    case 'rig-row-body':
      return RIG_DROP_DEPTH.rowBody
    case 'rig-row-gap':
      return RIG_DROP_DEPTH.rowGap
    case 'rig-new-row':
      return RIG_DROP_DEPTH.newRow
    default:
      return -1
  }
}

function deepestRig(source: RigDragSource['kind'], collisionIds: readonly string[]): ParsedRigId | null {
  let best: ParsedRigId | null = null
  let bestDepth = -1
  for (const id of collisionIds) {
    const parsed = parseRigDragId(id)
    if (parsed == null || !canLand(source, parsed.kind)) continue
    const depth = rigDepthOf(parsed)
    if (depth > bestDepth) {
      best = parsed
      bestDepth = depth
    }
  }
  return best
}

/**
 * Where a hover over the rig would land, or null — {@link resolveDropTarget} over the rig document.
 *
 * The same four rules, because they were each learned on the page and the rig has the same
 * anatomy: self-hover stays ahead of the hysteresis, the hysteresis stays ahead of the
 * `overId == null` arm, an open slot is sticky while the pointer is in the same row's body, and the
 * half-of-the-tile test only runs when the tile that won is the one dnd-kit measured. A row has
 * one axis — tiles run left to right — but `insertionSide` still reads whichever axis the centres
 * differ on more, so a tile row that has wrapped is answered the way a wrapping bank is.
 */
export function resolveRigDropTarget(args: {
  rig: BuskRig
  source: RigDragSource['kind']
  activeId: string
  overId: string | null
  collisionIds: readonly string[]
  activeRect: ClientRect | null
  overRect: ClientRect | null
  current?: RigDropTarget | null
  movedSinceTarget?: number
}): RigDropTarget | null {
  const {
    rig,
    source,
    activeId,
    overId,
    collisionIds,
    activeRect,
    overRect,
    current = null,
    movedSinceTarget = Number.POSITIVE_INFINITY,
  } = args
  if (overId === activeId) return null
  if (current != null && movedSinceTarget < TARGET_HYSTERESIS_PX) return current
  if (overId == null) return null

  const parsed = deepestRig(source, collisionIds.length > 0 ? collisionIds : [overId])
  if (parsed == null) return null

  if (parsed.kind === 'rig-row-body' && current?.kind === 'tile' && current.at.row === parsed.row) {
    return current
  }

  const target = rigDropTargetFor(parsed, rig)
  if (target?.kind !== 'tile' || overRect == null) return target

  const overParsed = parseRigDragId(overId)
  if (
    overParsed?.kind !== 'rig-tile' ||
    overParsed.at.row !== target.at.row ||
    overParsed.at.tile !== target.at.tile
  ) {
    return target
  }
  return { kind: 'tile', at: { ...target.at, tile: target.at.tile + insertionSide(activeRect, overRect) } }
}
