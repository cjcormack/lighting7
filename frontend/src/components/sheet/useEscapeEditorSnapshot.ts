import { useEffect, useRef } from 'react'
import { editorIsOpen } from '../editor/EditorSurface'

/**
 * Was a cell editor open when Escape was pressed?
 *
 * The grid's Escape is a ladder: an open editor takes the key and the selection survives, and only
 * a press with nothing open clears. Deciding that needs the answer to *is an editor open* — not to
 * *where was the key pressed*, which is what `isEditableTarget` and `closest('[role="dialog"]')`
 * answer and which is a different question, wrong the moment focus is not inside the panel (on the
 * Set button that opened it, say). Escape then closed the editor **and** took the selection it was
 * opened for.
 *
 * **It has to be asked in the capture phase, which is the whole reason this is a hook and not a
 * line in the keydown handler.** Radix's dismissable layer listens on the *document*, and the
 * grid's own handler on the *window*. Capture runs outermost-in and bubble innermost-out, so on the
 * way back up Radix has already closed the panel — and a keydown is a discrete event, so React has
 * already flushed the unmount — by the time the grid's handler runs. Asking there always answers
 * "nothing open", and the selection is cleared anyway. A **window capture** listener is the first
 * thing any keydown in this document reaches, before Radix's document-capture listener and before
 * anything else can act; the answer it records is what the bubble handler then reads.
 *
 * The ref is rewritten on every Escape, so it can never be read stale: whenever the bubble handler
 * looks, it is looking at a value written for that same keypress.
 *
 * **[extra] is for a caller whose ladder is longer than the grid's**, not for a different mechanism.
 * The hand's chip (`components/hand/HandChip.tsx`) also has to stand aside for an open dialog or
 * popper, which a cell editor is only one of — but the capture/bubble trick is delicate enough that
 * a second copy of it is how one of them silently stops working. So the predicate is the parameter
 * and the timing is shared.
 */
export function useEscapeEditorSnapshot(extra?: () => boolean): React.RefObject<boolean> {
  // `extra` is held in a ref so a caller may pass an inline arrow without re-registering the
  // listener on every render — the predicate is read at keydown time, never at subscribe time.
  const extraRef = useRef(extra)
  extraRef.current = extra
  const ref = useRef(false)
  useEffect(() => {
    const onCapture = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      ref.current = editorIsOpen() || extraRef.current?.() === true
    }
    window.addEventListener('keydown', onCapture, true)
    return () => window.removeEventListener('keydown', onCapture, true)
  }, [])
  return ref
}
