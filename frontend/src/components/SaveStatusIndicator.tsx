import { useEffect, useRef, useState } from 'react'
import { useSelector } from 'react-redux'
import { Check, Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  selectCueSaveStatus,
  selectSaveStatus,
  type SaveStatusState,
} from '@/store/saveStatusSlice'

/** How long "Saved" lingers before the pill fades back out. */
const SAVED_LINGER_MS = 2500

/**
 * "Saving… / Saved" pill for the show header.
 *
 * Edits across these views auto-save: an inline field commits on blur, the props pane PATCHes as
 * you leave each input, a drag reorders on drop. A failure toasts (see `errorToastMiddleware`),
 * but success used to be completely silent, so there was no way to tell a saved edit from one
 * that never fired. This is the other half of that pair.
 *
 * It reads global save state rather than taking props, so every auto-saving control in the three
 * show views is covered without threading `isLoading` through each call site — none of which
 * currently read their mutation's status at all.
 */
export function SaveStatusIndicator({
  cueId,
  className,
}: {
  /**
   * Report only this cue's saves. Omit for the show-wide pill — a cue card scoped to itself
   * must not light up because some other cue in the stack was written.
   */
  cueId?: number
  className?: string
}) {
  const { pending, savedTick } = useSelector((state: { saveStatus: SaveStatusState }) =>
    cueId === undefined ? selectSaveStatus(state) : selectCueSaveStatus(state, cueId),
  )
  const [showSaved, setShowSaved] = useState(false)

  // The tick this instance has already accounted for. Seeded from the mount-time value, and
  // re-seeded when the indicator is pointed at a different cue, so only a save that happens
  // while this indicator is watching shows "Saved". Without it, collapsing and re-expanding a
  // cue that was edited earlier would greet the operator with a "Saved" for a write long past.
  const seen = useRef({ cueId, tick: savedTick })

  useEffect(() => {
    if (seen.current.cueId !== cueId) {
      seen.current = { cueId, tick: savedTick }
      return
    }
    if (savedTick === seen.current.tick) return
    seen.current.tick = savedTick
    setShowSaved(true)
    const timer = setTimeout(() => setShowSaved(false), SAVED_LINGER_MS)
    return () => clearTimeout(timer)
  }, [savedTick, cueId])

  const saving = pending > 0
  // While a save is in flight its "Saving…" wins: a burst of edits would otherwise flip the pill
  // between the two states as each one lands.
  const state = saving ? 'saving' : showSaved ? 'saved' : 'idle'

  return (
    <span
      // Announced politely so a screen-reader operator hears the outcome without the pill
      // stealing focus mid-edit. The live region is always mounted — one that appears only when
      // it has something to say is not reliably announced.
      role="status"
      aria-live="polite"
      className={cn(
        // Width is held even when idle, so the header controls beside it never shift.
        'inline-flex w-[5.5rem] shrink-0 items-center justify-end gap-1 text-xs transition-opacity',
        state === 'idle' ? 'opacity-0' : 'opacity-100',
        state === 'saved' ? 'text-green-500' : 'text-muted-foreground',
        className,
      )}
    >
      {state === 'saving' && (
        <>
          <Loader2 className="size-3 shrink-0 animate-spin" aria-hidden="true" />
          Saving…
        </>
      )}
      {state === 'saved' && (
        <>
          <Check className="size-3 shrink-0" aria-hidden="true" />
          Saved
        </>
      )}
    </span>
  )
}
