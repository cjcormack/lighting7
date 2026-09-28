import type { BuskPaletteDragData } from '@/components/busking/buskDnd'
import type { CueSlotDropTargetData, CueSlotSwapDragData } from '@/components/cueSlotShared'

/**
 * What a drop onto an FX cue slot means — as pure functions, so the rule is testable without a
 * pointer sequence.
 *
 * The busk page's own `resolveDropTarget` is pure for the same reason, and the same bug class:
 * a mapping that is only reachable through jsdom rects (which are all zero) is a mapping nothing
 * checks. Everything here takes `unknown` because dnd-kit's `data.current` is untyped at the
 * boundary; the imports are type-only, so this file adds no runtime edge from the app shell into
 * the busk feature.
 */

/** What `assignCueSlot` needs, minus the position — exactly one of the two ids. */
export interface SlotAssignment {
  cueId?: number
  lookId?: number
}

export function isSlotTarget(overData: unknown): overData is CueSlotDropTargetData {
  return (overData as CueSlotDropTargetData | undefined)?.type === 'slot-target'
}

export function isSlotSwap(activeData: unknown): activeData is CueSlotSwapDragData {
  return (activeData as CueSlotSwapDragData | undefined)?.type === 'slot-item'
}

function paletteDrag(activeData: unknown): BuskPaletteDragData | null {
  const data = activeData as BuskPaletteDragData | undefined
  return data?.type === 'busk-palette' ? data : null
}

/**
 * The assignment a palette row would make, or null if it cannot land here.
 *
 * Null for a **template** and for a Look with a deferred effect: a slot has no selection, so it can
 * hold only what needs none (busk-layout plan D7). The rule itself is `lib/handTargets.ts`'s
 * `canHandLand(…, 'slot')`, which the palette calls to set `slotEligible` and the hand's target
 * affordance calls directly — one statement of it, where there were three.
 *
 * **What the kind switch below re-checks, and what it does not.** A wrongly-true `slotEligible` on a
 * *template* row still cannot put one in a slot: the trailing `return null` catches it. The
 * deferred-effect *Look* refusal has no such second line — a Look reaching here with the flag set
 * is assigned — so that half rests on `canHandLand` alone. That asymmetry is why it matters that
 * the flag is computed from the shared rule rather than restated per row, and it is stated here
 * because the sentence that used to stand in its place implied both cases were covered.
 */
export function slotAssignmentFor(activeData: unknown): SlotAssignment | null {
  const drag = paletteDrag(activeData)
  if (drag == null || !drag.slotEligible) return null
  const record = drag.record
  if (record.kind === 'CUE') return { cueId: record.cue.id }
  if (record.kind === 'LOOK') return { lookId: record.look.id }
  return null
}

/**
 * A palette row that is over a slot but cannot land in it — a template, or a Look that needs a
 * selection. Purely an affordance: the drop is a no-op either way, and this only lets the tile say
 * why rather than swallowing the gesture silently.
 */
export function isSlotRefusedDrag(activeData: unknown): boolean {
  const drag = paletteDrag(activeData)
  if (drag == null) return false
  return slotAssignmentFor(drag) == null
}
