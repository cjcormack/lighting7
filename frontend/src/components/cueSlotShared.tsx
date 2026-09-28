import { ListMusic, SwatchBook } from 'lucide-react'
import { padPresenceClass } from './busking/padFace'
import type { CueSlot } from '../store/cueSlots'

/**
 * The cue-slot types and glyph, shared by `CueSlotOverviewPanel` and `dnd/DeskDndProvider`.
 *
 * Extracted when the panel and its own assign panel were a value-import cycle. That panel is gone —
 * slots are filled by dragging from the busk view's library palette now — but the split still
 * earns its keep: the provider resolves every slot drop and must not import the panel, and the
 * panel imports the provider's hook.
 */

/**
 * A slot being dragged to another slot.
 *
 * Here rather than in the panel because `DeskDndProvider` — which is no longer a cue-slot module —
 * resolves the drop, and the panel imports the provider's hook. Same non-cycle reason as the rest
 * of this file.
 */
export interface CueSlotSwapDragData {
  type: 'slot-item'
  page: number
  slotIndex: number
  slot: CueSlot
}

/** A slot offering itself as a landing place. */
export interface CueSlotDropTargetData {
  type: 'slot-target'
  page: number
  slotIndex: number
}

/** The label and type glyph, identical in a slot and under the drag cursor. */
export function SlotItemContent({
  name,
  itemType,
}: {
  name: string
  itemType: CueSlot['itemType']
}) {
  return (
    <>
      <span className="text-xs font-medium leading-tight text-center line-clamp-2 w-full">
        {name}
      </span>
      <div className="flex items-center gap-1 mt-0.5">
        {itemType === 'look' ? (
          <SwatchBook className="size-3 text-muted-foreground shrink-0" />
        ) : (
          <ListMusic className="size-3 text-muted-foreground shrink-0" />
        )}
      </div>
    </>
  )
}

/**
 * How a lit tile looks, by what it holds — two different claims, so two different faces.
 *
 * A live **cue** keeps the overlay's own shipped fill: this cue is its stack's live cue, a fact
 * about the show. A **Look** on the rig is a *layer this tile would remove*, so it borrows the busk
 * pad's presence vocabulary — literally, through [padPresenceClass], rather than by hand. It was
 * hand-rolled first and had already drifted a shade from the pad's `all` state; a shared function
 * is the only version of "one dialect" that stays true.
 *
 * A slot has no selection, so a Look is either present or it is not — `'all'` or `'none'`; the pad's
 * middle rung has no meaning here. The corner pip is the tile's own.
 */
export function slotLitClass(itemType: CueSlot['itemType'], lit: boolean): string {
  if (!lit) return ''
  return itemType === 'cue' ? 'border-primary bg-primary/20' : padPresenceClass('all')
}
