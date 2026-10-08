import type { RefObject } from 'react'
import type { ActiveEffect } from '@/store/fixtureFx'
import { EditorSurface } from '../editor/EditorSurface'
import { FxEditor } from './FxEditor'

/**
 * `FxEditor` in `EditorSurface` (fixture-fx-sheets plan D17): the programmer rail's *Edit…* and
 * `FxSheet`'s chips open the live editor beside what was pressed — a popover anchored at the row or
 * the chip on a desk, a bottom sheet on an upright phone, a side sheet where the viewport is short,
 * the three forms every cell editor takes.
 *
 * Mounted once by its host with no trigger of its own: [anchorRef] is the element the gesture was
 * made at, set before [effect] is, so the popover is positioned at it (`EditorSurface`'s virtual
 * anchor). [effect] is the **live** instance the host reads from the running list each render; a
 * host closes the editor when its effect stops, rather than editing an id the desk no longer has.
 */
export function FxEditorPopover({
  effect,
  anchorRef,
  onClose,
  onStop,
}: {
  effect: ActiveEffect | null
  anchorRef: RefObject<HTMLElement | null>
  onClose: () => void
  onStop: (effect: ActiveEffect) => void
}) {
  return (
    <EditorSurface
      open={effect != null}
      onOpenChange={(open) => !open && onClose()}
      title={effect ? `Edit ${effect.effectType}` : 'Edit effect'}
      anchorRef={anchorRef}
      // Bounded by the room Radix measured beside the anchor and scrolled inside it: an Absolute
      // Circle is ~530px of controls, and a popover taller than its room renders above the viewport
      // with its × and Speed unreachable (`EditorSurface`'s note on a popover that cannot fit).
      contentClassName="w-80 max-h-[var(--radix-popover-content-available-height)] overflow-y-auto p-3"
      align="start"
    >
      {effect != null && (
        <FxEditor
          key={`${effect.id}:${effect.effectType}`}
          effect={effect}
          onDone={onClose}
          onStop={() => onStop(effect)}
        />
      )}
    </EditorSurface>
  )
}
