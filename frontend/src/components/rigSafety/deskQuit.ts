import { useSyncExternalStore } from 'react'
import type { BackupStatus } from '@/store/cloud'

/**
 * Whether this window quit the desk (venue-visit plan S6, D13), and when on this browser's clock the
 * lamps will have cooled, if they were still cooling. Module state rather than a cache entry: once the
 * desk has gone the socket closes and the gates below `App` start reconnecting, and the screen that
 * says *the desk has quit* must outlive all of that.
 */
export interface DeskQuit {
  /** `Date.now()` at which the mains can be cut, or null when nothing was cooling. */
  coolsAt: number | null
  /** The backups' status as the quit began, while the desk backs up before it exits. */
  backup: BackupStatus | null
  /** `Date.now()` when this window marked the quit: a status read before it says nothing of the quit. */
  at: number
}

let quit: DeskQuit | null = null
const listeners = new Set<() => void>()

export function markDeskQuit(coolsAt: number | null = null, backup: BackupStatus | null = null): void {
  quit = { coolsAt, backup, at: Date.now() }
  listeners.forEach((l) => l())
}

export function useDeskQuit(): DeskQuit | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => quit,
  )
}
