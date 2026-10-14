import { isEnabled, metrics } from '@sentry/react'

/**
 * The browser's metrics (Sentry plan §4.3). Each is a call into the SDK, which buffers and sends
 * only while Sentry is running; nothing here is recorded per frame or per WS message.
 * See `docs/observability-engineering.md` §"The browser".
 */

let bootReadyMs: number | null = null
let bootReadySent = false
let signInShown = false
let telemetrySettled = false

/** The sign-in or setup screen was drawn: this page load's boot.ready would time the operator. */
export function markSignInShown(): void {
  signInShown = true
}

/** BootGate's first ready on this page load: milliseconds since navigation began. */
export function markBootReady(): void {
  if (bootReadyMs != null || signInShown) return
  bootReadyMs = performance.now()
  sendBootReady()
}

/**
 * Sends `boot.ready` once Sentry is running: the show is often ready before the telemetry fetch
 * settles. A boot that finishes with Sentry off after that is dropped, never sent on a later switch-on.
 */
export function sendBootReady(): void {
  if (bootReadyMs == null || bootReadySent) return
  if (!isEnabled()) {
    if (telemetrySettled) bootReadySent = true
    return
  }
  bootReadySent = true
  metrics.distribution('boot.ready', Math.round(bootReadyMs), { unit: 'millisecond' })
}

/** The page's telemetry fetch has settled, and Sentry started or will not. */
export function settleBootReady(): void {
  telemetrySettled = true
  sendBootReady()
}

/** `forced`: the app re-handshook after sign-in, sign-out or setup. `dropped`: anything else. */
export type ReconnectCause = 'dropped' | 'forced'

/** A socket open after this page's first. */
export function countWsReconnect(cause: ReconnectCause): void {
  metrics.count('ws.reconnect', 1, { attributes: { cause } })
}

export const STAGE_FPS_INTERVAL_MS = 10_000

/**
 * Samples the Stage view's frame rate every 10 s while the page is visible. [read] answers null
 * while the canvas is idle (it renders on demand), which sends nothing.
 */
export function sampleStageFps(read: () => number | null): () => void {
  const timer = setInterval(() => {
    if (document.visibilityState !== 'visible') return
    const fps = read()
    if (fps != null) metrics.distribution('stage.fps', fps)
  }, STAGE_FPS_INTERVAL_MS)
  return () => clearInterval(timer)
}

/** For tests: forget this page load's boot. */
export function resetSentryMetrics(): void {
  bootReadyMs = null
  bootReadySent = false
  signInShown = false
  telemetrySettled = false
}
