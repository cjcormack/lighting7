import { useEffect, useRef } from 'react'

/** Whether [event] is ⌘Z or ⇧⌘Z (Ctrl on Windows and Linux): `'undo'`, `'redo'` or null. */
export function undoKeyOf(event: Pick<KeyboardEvent, 'key' | 'metaKey' | 'ctrlKey' | 'altKey' | 'shiftKey'>): 'undo' | 'redo' | null {
  if (!(event.metaKey || event.ctrlKey) || event.altKey || event.key.toLowerCase() !== 'z') return null
  return event.shiftKey ? 'redo' : 'undo'
}

/**
 * Whether ⌘Z belongs to something else where [target] is (undo plan §4.6): a text field or a
 * contenteditable, whose own undo is the browser's, or an open dialog, which has its own keys. A
 * sheet is not such a dialog — the History sheet and an editor in a sheet take the key.
 */
export function undoKeyBlocked(target: EventTarget | null, doc: Document = document): boolean {
  if (target instanceof HTMLElement) {
    if (target.isContentEditable) return true
    if (target.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]')) return true
  }
  return doc.querySelector('[role="alertdialog"], [role="dialog"]:not([data-slot="sheet-content"])') != null
}

/**
 * ⌘Z and ⇧⌘Z on `window` (undo plan §4.6). A handler that takes the key says so with
 * `preventDefault()` and keeps it — the Timeline's own ⌘Z among them — whatever order the listeners
 * were added in: the key is acted on once the event has finished dispatching.
 */
export function useUndoKeys(onUndo: () => void, onRedo: () => void) {
  const latest = useRef({ onUndo, onRedo })
  latest.current = { onUndo, onRedo }
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat) return
      const key = undoKeyOf(event)
      if (key == null || undoKeyBlocked(event.target)) return
      // A task, not a microtask: a browser runs microtasks between one listener and the next.
      setTimeout(() => {
        if (event.defaultPrevented) return
        if (key === 'undo') latest.current.onUndo()
        else latest.current.onRedo()
      })
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])
}

/** `⌘Z` on a Mac, `Ctrl+Z` elsewhere. */
export function undoShortcutLabel(direction: 'undo' | 'redo', platform: string = navigator.platform): string {
  const mac = /Mac|iPhone|iPad/.test(platform)
  if (mac) return direction === 'undo' ? '⌘Z' : '⇧⌘Z'
  return direction === 'undo' ? 'Ctrl+Z' : 'Ctrl+Shift+Z'
}
