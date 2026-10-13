import { useSyncExternalStore } from 'react'

/**
 * Whether the router may be created: once Sentry has started or decided not to (`lib/sentry.ts`).
 * The SDK instruments only a router created after `Sentry.init`, so `App` waits for this — at most
 * the telemetry fetch's one second, and the fallback below if nothing ever settles it.
 */
let settled = false
const listeners = new Set<() => void>()

const FALLBACK_MS = 2_000

export function markRouterMayStart(): void {
  if (settled) return
  settled = true
  for (const fn of [...listeners]) fn()
}

setTimeout(markRouterMayStart, FALLBACK_MS)

function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function useRouterMayStart(): boolean {
  return useSyncExternalStore(subscribe, () => settled)
}
