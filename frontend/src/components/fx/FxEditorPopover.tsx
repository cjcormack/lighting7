import { useMemo, useState, type RefObject } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import type { ActiveEffect } from '@/store/fixtureFx'
import { EditorSurface } from '../editor/EditorSurface'
import { SplitFxEditor, type SplitSession } from './SplitFxEditor'

/**
 * `FxEditor` in `EditorSurface` (fixture-fx-sheets plan D17): the programmer's FX table's *Edit…*,
 * the FX lens's chips and a cell popup's Effects band open the live editor beside
 * what was pressed — a popover anchored at the row or the chip on a desk, a bottom sheet on an
 * upright phone, a side sheet where the viewport is short, the three forms every cell editor takes.
 *
 * Mounted once by its host with no trigger of its own: [anchorRef] is the element the gesture was
 * made at, set before [effect] is, so the popover is positioned at it (`EditorSurface`'s virtual
 * anchor). [effect] is the **live** instance the host reads from the running list each render; a
 * host closes the editor when its effect stops, rather than editing an id the desk no longer has.
 *
 * **Edits follow the selection** (apply-model plan D5, §4.5's split editor): see [SplitFxEditor],
 * which the busk Effects tab draws inline.
 */
export function FxEditorPopover({
  effect,
  anchorRef,
  onClose,
  onStop,
  onFollow,
  selection,
}: {
  effect: ActiveEffect | null
  anchorRef: RefObject<HTMLElement | null>
  onClose: () => void
  onStop: (effect: ActiveEffect) => void
  /** A split replaced the effect: the host reads this id from now on — the edited copy it follows. */
  onFollow?: (effectId: number) => void
  /** The heads an edit is for — a cell's batch. Absent, the window's selection pair's targets. */
  selection?: readonly CueTarget[]
}) {
  // Kept here, outside the editor: a close ends it, so a copy opened again later is edited alone.
  const [session, setSession] = useState<SplitSession>(null)
  const close = () => {
    setSession(null)
    onClose()
  }
  const anchor = useStableAnchor(anchorRef)
  return (
    <EditorSurface
      open={effect != null}
      onOpenChange={(next) => !next && close()}
      title={effect ? `Edit ${effect.effectType}` : 'Edit FX'}
      anchorRef={anchor}
      // Bounded by the room Radix measured beside the anchor and scrolled inside it: an Absolute
      // Circle is ~530px of controls, and a popover taller than its room renders above the viewport
      // with its × and Speed unreachable (`EditorSurface`'s note on a popover that cannot fit).
      contentClassName="w-80 max-h-[var(--radix-popover-content-available-height)] overflow-y-auto p-3"
      align="start"
    >
      {effect != null && (
        <SplitFxEditor
          effect={effect}
          selection={selection}
          session={session}
          onSessionChange={setSession}
          onClose={close}
          onStop={(stopped) => {
            setSession(null)
            onStop(stopped)
          }}
          onFollow={onFollow}
        />
      )}
    </EditorSurface>
  )
}

/**
 * The anchor a host set, kept where it last was once it leaves the page: a split replaces the effect
 * whose row or chip the editor hangs from, and a detached element measures as the viewport's corner.
 */
function useStableAnchor(anchorRef: RefObject<HTMLElement | null>): RefObject<{ getBoundingClientRect(): DOMRect }> {
  return useMemo(() => {
    let last = new DOMRect()
    return {
      current: {
        getBoundingClientRect: () => {
          const element = anchorRef.current
          if (element?.isConnected) last = element.getBoundingClientRect()
          return last
        },
      },
    }
  }, [anchorRef])
}
