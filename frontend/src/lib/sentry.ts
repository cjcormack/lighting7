/**
 * The browser's Sentry (Sentry plan §4.1). Started by `main.tsx` and nothing else — `vitest` never
 * imports this module (`sentryImports.test.ts`), so no test can report. It asks the desk first:
 * `GET /install/telemetry` within a second, and no answer, or `enabled: false`, means no Sentry.
 * Rendering never waits; only the router does (`lib/sentryReady.ts`). Every envelope goes through
 * the desk's tunnel, `/api/sentry`, which forwards only while telemetry is on.
 * See `docs/observability-engineering.md` §"The browser".
 */
import * as Sentry from '@sentry/react'
import { reactRouterBrowserTracingIntegration } from '@sentry/react/react-router'
import { markRouterMayStart } from './sentryReady'
import { scrubBreadcrumb, scrubEvent, scrubTransaction } from './sentryScrub'
import { subscribeWindowName, windowName } from './windowIdentity'

interface Telemetry {
  enabled: boolean
  release: string
  environment: string
  tracesSampleRate: number
  browserDsn?: string | null
}

const TELEMETRY_URL = '/api/rest/install/telemetry'
const TELEMETRY_TIMEOUT_MS = 1_000

const IGNORE_ERRORS: (string | RegExp)[] = [
  /^ResizeObserver loop/,
  /context lost/i,
  /CONTEXT_LOST_WEBGL/,
  /^AbortError: /,
]

let running = false

async function fetchTelemetry(): Promise<Telemetry | null> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TELEMETRY_TIMEOUT_MS)
  try {
    const response = await fetch(TELEMETRY_URL, { credentials: 'same-origin', signal: controller.signal })
    return response.ok ? ((await response.json()) as Telemetry) : null
  } catch {
    return null
  } finally {
    clearTimeout(timer)
  }
}

function start(telemetry: Telemetry): void {
  if (running || !telemetry.enabled || !telemetry.browserDsn) return
  running = true
  Sentry.init({
    dsn: telemetry.browserDsn,
    tunnel: '/api/sentry',
    release: __LIGHTING7_RELEASE__ || telemetry.release,
    environment: telemetry.environment,
    tracesSampleRate: telemetry.tracesSampleRate,
    // 11.7.0 defaults to 'stream', where a fetch with no open span is a root span of its own and
    // beforeSendTransaction never runs, so scrubTransaction would scrub nothing.
    traceLifecycle: 'static',
    tracePropagationTargets: [/^\/api\//],
    // v11's replacement for `sendDefaultPii: false`, every default of which is to collect; userInfo
    // is the one 11.7.0's browser reads, and off it stops Sentry inferring the client's IP.
    dataCollection: { userInfo: false, cookies: false, httpHeaders: false, httpBodies: [], urlQueryParams: false },
    integrations: [reactRouterBrowserTracingIntegration()],
    ignoreErrors: IGNORE_ERRORS,
    beforeSend: scrubEvent,
    beforeSendTransaction: scrubTransaction,
    beforeBreadcrumb: scrubBreadcrumb,
    initialScope: { tags: { window: windowName() } },
  })
}

function stop(): void {
  if (!running) return
  running = false
  void Sentry.close(2_000)
}

/** Starts Sentry if the desk says to, then lets the router start either way. */
export function startSentry(): void {
  subscribeWindowName(() => Sentry.setTag('window', windowName()))
  void fetchTelemetry()
    .then((telemetry) => {
      if (telemetry) start(telemetry)
    })
    .finally(markRouterMayStart)
}

/**
 * The install changed — perhaps its telemetry switch. Off stops this browser; on starts it, without
 * route transactions until the next load, since the SDK instruments only a router made after init.
 */
export function refreshTelemetry(): void {
  void fetchTelemetry().then((telemetry) => {
    if (telemetry == null) return
    if (telemetry.enabled) start(telemetry)
    else stop()
  })
}
