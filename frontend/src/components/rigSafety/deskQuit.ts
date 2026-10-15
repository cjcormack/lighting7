import { useSyncExternalStore } from 'react'

/**
 * Whether this window quit the desk (venue-visit plan S6, D13). Module state rather than a cache
 * entry: once the desk has gone the socket closes and the gates below `App` start reconnecting, and
 * the screen that says *the desk has quit* must outlive all of that.
 */
let quit = false
const listeners = new Set<() => void>()

export function markDeskQuit(): void {
  quit = true
  listeners.forEach((l) => l())
}

export function useDeskQuit(): boolean {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => quit,
  )
}
