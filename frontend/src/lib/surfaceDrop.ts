import type { Active, Over } from '@dnd-kit/core'
import type { BindingTarget, ControlDescriptor } from '@/api/surfacesApi'
import { exactBindingAt, type SurfaceBindingIndex } from '@/lib/surfaceResolve'
import type { CueTarget } from '@/api/cuesApi'

/**
 * What a drag from the surface library carries, where it may land, and the one binding write a
 * drop produces — as pure functions, so the rules are testable without a pointer sequence.
 *
 * `dnd/slotDrop.ts` is the shape this follows and the reason is the same: a mapping only reachable
 * through jsdom rects (which are all zero) is a mapping nothing checks. Everything that reads
 * dnd-kit's `data.current` takes `unknown`, because it is untyped at that boundary.
 *
 * The surface shares the app's single `DndContext` with the busk page and the FX cue-slot grid
 * (see `dnd/DeskDndProvider.tsx`), so both ends of every handler ignore what they do not recognise:
 * `surfaceDragData` answers null for a `busk-…` or `slot-…` id and the slot handler returns for any
 * `over` that is not a slot.
 */

// ─── What is lifted ───────────────────────────────────────────────────

/**
 * A group or fixture **row**, which lands on a strip and covers its four controls at once.
 *
 * It carries the whole `CueTarget` rather than a key, because `Strip`'s payload is exactly that
 * shape and a group is addressed by name where a fixture is addressed by key — the same
 * distinction `lookLayerTarget` exists to keep in one place.
 */
export interface SurfaceRowDrag {
  type: 'surface-row'
  target: CueTarget
  name: string
  /** The ghost's second line — `4 fixtures · strip`. */
  detail: string
}

/** One **chip**: a finished `BindingTarget` looking for a single control. */
export interface SurfaceChipDrag {
  type: 'surface-chip'
  target: BindingTarget
  label: string
  /** A CSS colour or gradient drawn beside the label, or null. Colour chips carry one. */
  swatch: string | null
}

export type SurfaceDragData = SurfaceRowDrag | SurfaceChipDrag

export function surfaceDragData(active: Active | null): SurfaceDragData | null {
  const data = active?.data.current
  if (data == null) return null
  if (data.type === 'surface-row' || data.type === 'surface-chip') return data as SurfaceDragData
  return null
}

// ─── Where it may land ────────────────────────────────────────────────

/** Which half of the router's dispatch a control reaches. */
export type ControlKind = 'continuous' | 'button'

export interface SurfaceControlDrop {
  type: 'surface-control'
  controlId: string
  /** What the router will actually dispatch here — see [controlKinds]. */
  kinds: readonly ControlKind[]
}

export interface SurfaceStripDrop {
  type: 'surface-strip'
  stripId: string
}

export type SurfaceDropData = SurfaceControlDrop | SurfaceStripDrop

export function surfaceDropData(over: Over | null): SurfaceDropData | null {
  const data = over?.data.current
  if (data == null) return null
  if (data.type === 'surface-control' || data.type === 'surface-strip') {
    return data as SurfaceDropData
  }
  return null
}

/**
 * What the desk will dispatch on this control, from the profile alone.
 *
 * Read off `SurfaceInputRouter.matchEvent` rather than guessed from the descriptor's name, and the
 * two surprises are both in it:
 *
 * - An **encoder with a push note reaches both halves** — its CC is a `Continuous` and its note a
 *   `ButtonPress`, on one control id. So an encoder legitimately takes a cue chip as well as a
 *   property one, and the X-Touch declares a push on all sixteen.
 * - A **bank button takes nothing at all**. `route` answers `ResolvedInput.BankButton` and switches
 *   the bank *before* resolving a binding — "bank buttons short-circuit binding resolution", in the
 *   router's own words — so a binding on one is a row that can never fire. Returning `[]` is what
 *   keeps the library from offering the operator a control that does nothing.
 */
export function controlKinds(descriptor: ControlDescriptor): readonly ControlKind[] {
  switch (descriptor.type) {
    case 'fader':
      return ['continuous']
    case 'encoder':
      return descriptor.pushNote != null ? ['continuous', 'button'] : ['continuous']
    case 'button':
      return ['button']
    case 'bankButton':
      return []
  }
}

/**
 * Which half of the dispatch a target belongs to, or null for a target no chip may carry.
 *
 * `strip` is null because it addresses a strip id, never a control, and the backend refuses the
 * swap by name (`refuseWrongSlot`); `unknown` is null because `refuseUnknown` will not accept one
 * from a request at all — it exists to be *rebound*, not re-sent.
 *
 * This is now the **mirror** of `midi/BindingControlKind.kt` rather than the only copy: session 4
 * closed `FU-MIDI-BIND-CONTROL-KIND`, so `ControlSurfaceBindingService.refuseWrongKind` refuses a
 * mismatch at the write boundary whichever door it comes through. Keep the two tables in step; a
 * divergence here only makes the palette offer or withhold a chip the server would have judged
 * differently.
 */
export function targetControlKind(target: BindingTarget): ControlKind | null {
  switch (target.type) {
    case 'fixtureProperty':
    case 'groupProperty':
    case 'selectionProperty':
    case 'speedMasterBpm':
      return 'continuous'
    // Every button target, records and busk pages included, and the busk-further plan's five (D14:
    // two window commands and three sub-selection rewrites, BUTTON in `midi/BindingControlKind.kt`):
    // each of those is a press, and a fader has no press. (One run of labels with no comment inside
    // it — `no-fallthrough` reads a comment between two `case`s as the end of the run.)
    case 'flash':
    case 'cueStackGo':
    case 'cueStackBack':
    case 'cueStackPause':
    case 'fireCue':
    case 'selectTarget':
    case 'clearSelection':
    case 'locateSelection':
    case 'encoderBankSet':
    case 'blackout':
    case 'grandMasterToggle':
    case 'setBank':
    case 'speedMasterTap':
    case 'applyLook':
    case 'pressTemplate':
    case 'pressPad':
    case 'pickUpPad':
    case 'handPlaceInBank':
    case 'handDrop':
    case 'buskPageNext':
    case 'buskPagePrev':
    case 'buskPageSet':
    case 'buskFocusSet':
    case 'buskSheetToggle':
    case 'selectionNext':
    case 'selectionPrev':
    case 'selectionCells':
      return 'button'
    case 'strip':
    case 'unknown':
      return null
  }
}

/**
 * May this drag land here?
 *
 * **A row lands on a strip and nothing else; a chip lands on a matching control and nothing else.**
 * The panel enforces the same rule a second time through dnd-kit's own `disabled` — strips off
 * while a chip is lifted, controls off while a row is — and both halves are needed for the busk
 * page's reason: `disabled` is what keeps `over` (and so the highlight) off a place the drop would
 * refuse, and this is the half a test can reach.
 *
 * The backend enforces the chip half too since session 4 (`refuseWrongKind`), so this is no longer
 * the *whole* of the guard — but it is still the half that keeps the operator from dropping onto a
 * control that would answer a 400, which is a worse way to learn the rule than a dimmed target.
 */
export function canLand(drag: SurfaceDragData, drop: SurfaceDropData): boolean {
  if (drag.type === 'surface-row') return drop.type === 'surface-strip'
  if (drop.type !== 'surface-control') return false
  const kind = targetControlKind(drag.target)
  return kind != null && drop.kinds.includes(kind)
}

// ─── What the drop writes ─────────────────────────────────────────────

/** The one REST call a drop makes. `bindingId` is present exactly when `kind` is `update`. */
export type BindingWrite =
  | { kind: 'create'; controlId: string; bank: string | null; target: BindingTarget }
  | { kind: 'update'; bindingId: number; controlId: string; bank: string | null; target: BindingTarget }

/**
 * The binding request a drop makes, or null when it may not land.
 *
 * Two things here are easy to get wrong and both move a binding the operator was not pointing at:
 *
 * - **The row it replaces is the slot's own row at the exact bank** — [exactBindingAt], and nothing
 *   else. Not `resolveControl` or [activeBindingAt], which are the *reading* question and
 *   answers a strip's row for a strip's control and a bank-agnostic row when no exact-bank one
 *   exists. Patching either would be wrong in opposite ways: a chip dropped on a strip's fader must
 *   **create** a direct row — that is the only way "direct beats strip" is reachable from the UI —
 *   and a chip dropped on a control whose only row is global must create an exact-bank one rather
 *   than silently retarget every bank.
 * - **A created row takes the active bank**, because that is the bank the panel is drawing and the
 *   operator dropped onto what they could see. `null` when the device has no banks, which is the
 *   bank-agnostic row and also correct there.
 */
export function bindingWriteFor(
  drag: SurfaceDragData,
  drop: SurfaceDropData,
  index: SurfaceBindingIndex,
  activeBank: string | null,
): BindingWrite | null {
  if (!canLand(drag, drop)) return null

  const controlId = drop.type === 'surface-strip' ? drop.stripId : drop.controlId
  const target: BindingTarget =
    drag.type === 'surface-row' ? { type: 'strip', target: drag.target } : drag.target

  const existing = exactBindingAt(index, controlId, activeBank)
  if (existing) {
    return { kind: 'update', bindingId: existing.id, controlId, bank: activeBank, target }
  }
  return { kind: 'create', controlId, bank: activeBank, target }
}
