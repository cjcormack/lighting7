import { useEffect } from 'react'
import { isEditableTarget } from '../lib/domUtils'

/**
 * Space = GO, Backspace = BACK, L = toggle the edit lock.
 *
 * One handler, replacing the two that Run and the Prompt Book each grew separately. They had
 * diverged into a contradiction, and each was missing a guard the other had:
 *
 *  - **`isEditableTarget`** — neither used it, so both checked `INPUT`/`TEXTAREA`/`SELECT` by tag
 *    name and let a `contentEditable` element through. Space would fire a cue mid-word.
 *  - **Open dialogs** — Run's guard. Without it, Space in a Stop-confirm dialog advances the show
 *    behind the dialog.
 *  - **Activatable elements** — the Prompt Book's guard, and the important one. A focused button
 *    receives Space as its own activation, so firing GO here as well makes one press do two
 *    things — press Space after clicking GO with the mouse and the show advances *twice*. Run
 *    deliberately guarded only dialogs, on the grounds that guarding buttons breaks Space whenever
 *    a toolbar button holds focus; that cost is real but much smaller than a double GO mid-show.
 *
 * `enabled` gates the *transport* only. `L` stays live either way, so there is always a keyboard
 * route back to a locked desk — including from the state in which Space deliberately does nothing.
 */
export function useTransportKeys({
  enabled,
  onGo,
  onBack,
  onToggleLock,
}: {
  /**
   * Whether Space/Backspace should act. The merged Show view passes `locked`: unlocked means the
   * operator is editing, and in an editing surface Space is a space.
   */
  enabled: boolean
  onGo?: () => void
  onBack?: () => void
  onToggleLock?: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Browser and system shortcuts (Cmd+L, Cmd+Backspace, …) are not ours.
      if (e.metaKey || e.ctrlKey || e.altKey) return
      // A key another handler has already claimed is not ours either. The cue sheet's keyboard
      // (`sheet/useSheetKeyboard`) opens an editor on a character typed at the grid — and `l` is
      // one of them — from a **capture** listener, so by the time this bubble listener runs the
      // sheet has said so with `preventDefault()`. Without this, typing a name beginning with `l`
      // into a cue's cell would also toggle the lock, which is exactly the stray keypress this
      // handler exists to keep away from a running show.
      if (e.defaultPrevented) return
      const target = e.target as HTMLElement | null
      if (isEditableTarget(target)) return
      // `isEditableTarget` reads `isContentEditable`, which jsdom does not implement — so a guard
      // resting on it alone is one no test can see. The attribute form is checked too, and via
      // `closest` so a keypress on a child of an editable region is caught as well. `false` is
      // excluded deliberately: `contenteditable="false"` means *not* editable.
      if (target?.closest?.('[contenteditable=""], [contenteditable="true"]')) return
      if (target?.closest?.('[role="dialog"]')) return

      // `L` is checked *before* the activatable-element guard below, and only `L`. That guard exists
      // because a focused button takes Space as its own activation, so firing GO as well is a double
      // advance — but no button treats `L` as activation, and clicking the lock toggle (or GO) with
      // the mouse leaves focus sitting on it. Behind the guard, the keyboard route back to a locked
      // desk would be dead in exactly the state it is most often reached from.
      if (e.code === 'KeyL' && onToggleLock) {
        e.preventDefault()
        onToggleLock()
        return
      }

      if (target?.closest?.('button, a[href], [role="button"]')) return
      if (!enabled) return
      if (e.code === 'Space' && onGo) {
        e.preventDefault()
        onGo()
      }
      if (e.code === 'Backspace' && onBack) {
        e.preventDefault()
        onBack()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enabled, onGo, onBack, onToggleLock])
}
