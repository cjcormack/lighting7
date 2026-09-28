import { useSyncExternalStore } from 'react'

/**
 * Whether the Screens sheet is open — a module-level flag, because it has two openers in two
 * trees: the user menu's *Screens…* item and ⌘K's command, and neither is an ancestor of the
 * other. The sheet itself is mounted once, in `Layout`, and reads this.
 */
let open = false
const listeners = new Set<() => void>()

export function openScreensSheet(): void {
  setScreensSheetOpen(true)
}

export function setScreensSheetOpen(next: boolean): void {
  if (open === next) return
  open = next
  for (const fn of [...listeners]) fn()
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

const getSnapshot = () => open
const getServerSnapshot = () => false

export function useScreensSheetOpen(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)
}
