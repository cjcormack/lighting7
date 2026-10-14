/**
 * Replay and feedback, in a chunk of their own: `lib/sentry.ts` imports this module dynamically,
 * after `Sentry.init` and only while telemetry is on, so neither is in the entry chunk. Not the
 * SDK's `lazyLoadIntegration`, which fetches the bundle from Sentry's CDN — around the desk, and
 * nowhere at an offline venue. `feedbackIntegration` is the bundled one for the same reason.
 */
export { feedbackIntegration, replayIntegration } from '@sentry/react'
