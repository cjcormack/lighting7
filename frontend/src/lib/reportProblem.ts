import { useSyncExternalStore } from 'react'

/**
 * The user menu's *Report a problem*: an opener `lib/sentry.ts` sets while its feedback integration
 * is in place, and clears when telemetry stops. Null hides the item.
 */
type Opener = () => void

let opener: Opener | null = null
const listeners = new Set<() => void>()

export function setReportProblem(next: Opener | null): void {
  if (opener === next) return
  opener = next
  for (const fn of [...listeners]) fn()
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function useReportProblem(): Opener | null {
  return useSyncExternalStore(subscribe, () => opener)
}
