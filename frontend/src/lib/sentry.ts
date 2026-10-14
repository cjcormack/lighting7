/**
 * The browser's Sentry (Sentry plan §4.1). Started by `main.tsx` and nothing else — `vitest` never
 * imports this module (`sentryImports.test.ts`), so no test can report. It asks the desk first:
 * `GET /install/telemetry` within a second, and no answer, or `enabled: false`, means no Sentry.
 * Rendering never waits; only the router does (`lib/sentryReady.ts`). Every envelope goes through
 * the desk's tunnel, `/api/sentry`, which forwards only while telemetry is on. Replay and feedback
 * arrive after init in their own chunk (`lib/sentryLazy.ts`).
 * See `docs/observability-engineering.md` §"The browser".
 */
import * as Sentry from '@sentry/react'
import { reactRouterBrowserTracingIntegration } from '@sentry/react/react-router'
import { toast } from 'sonner'
import { setReportProblem } from './reportProblem'
import { sendBootReady, settleBootReady } from './sentryMetrics'
import { markRouterMayStart } from './sentryReady'
import { fitScreenshots } from './sentryScreenshot'
import { scrub, scrubBreadcrumb, scrubEvent, scrubOtherEvent, scrubRecordingEvent, scrubTransaction } from './sentryScrub'
import { getTheme } from './theme'
import { subscribeWindowName, windowName } from './windowIdentity'

interface Telemetry {
  enabled: boolean
  release: string
  environment: string
  tracesSampleRate: number
  browserDsn?: string | null
  /** Absent from a desk that predates replay, which then records none. */
  replaysSessionSampleRate?: number
  replaysOnErrorSampleRate?: number
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
/** Bumped by every stop, so a chunk that loads after one is not added to the next client. */
let generation = 0

type Lazy = typeof import('./sentryLazy')
type Replay = ReturnType<Lazy['replayIntegration']>
type Feedback = ReturnType<Lazy['feedbackIntegration']>

/** One per page: the SDK refuses a second, so after a stop replay stays off until the next load. */
let replay: Replay | null = null
let feedback: Feedback | null = null
type FeedbackForm = Awaited<ReturnType<Feedback['createForm']>>
let feedbackForm: FeedbackForm | null = null
let feedbackOpening = false

/** Replay and feedback events, which no `beforeSend` sees; and a screenshot that fits the tunnel. */
const scrubOthers: Parameters<typeof Sentry.addIntegration>[0] = {
  name: 'Lighting7Scrub',
  processEvent(event, hint) {
    if (event.type !== 'replay_event' && event.type !== 'feedback') return event
    scrubOtherEvent(event)
    return event.type === 'feedback' ? fitScreenshots(hint).then(() => event) : event
  },
}

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
    replaysSessionSampleRate: telemetry.replaysSessionSampleRate,
    replaysOnErrorSampleRate: telemetry.replaysOnErrorSampleRate,
    tracePropagationTargets: [/^\/api\//],
    // v11's replacement for `sendDefaultPii: false`, every default of which is to collect; userInfo
    // is the one 11.7.0's browser reads, and off it stops Sentry inferring the client's IP.
    dataCollection: { userInfo: false, cookies: false, httpHeaders: false, httpBodies: [], urlQueryParams: false },
    integrations: [reactRouterBrowserTracingIntegration(), scrubOthers],
    ignoreErrors: IGNORE_ERRORS,
    beforeSend: scrubEvent,
    beforeSendTransaction: scrubTransaction,
    beforeBreadcrumb: scrubBreadcrumb,
    initialScope: { tags: { window: windowName() } },
  })
  sendBootReady()
  void addLazyIntegrations(telemetry, generation)
}

/**
 * Replay is not recorded on a page loaded at a secret URL (a reset or device-login link): the DOM
 * snapshot carries the page's URL, and `beforeAddRecordingEvent` never sees it.
 */
function wantsReplay(telemetry: Telemetry): boolean {
  const rates = (telemetry.replaysSessionSampleRate ?? 0) + (telemetry.replaysOnErrorSampleRate ?? 0)
  return rates > 0 && scrub(window.location.href) === window.location.href
}

async function addLazyIntegrations(telemetry: Telemetry, started: number): Promise<void> {
  let lazy: Lazy
  try {
    lazy = await import('./sentryLazy')
  } catch {
    return
  }
  if (started !== generation) return
  if (replay == null && wantsReplay(telemetry)) {
    replay = lazy.replayIntegration({
      maskAllText: false,
      maskAllInputs: true,
      beforeAddRecordingEvent: scrubRecordingEvent,
    })
    Sentry.addIntegration(replay)
  }
  const added = lazy.feedbackIntegration({
    autoInject: false,
    showBranding: false,
    showName: false,
    showEmail: false,
    formTitle: 'Report a problem',
    messageLabel: 'What went wrong?',
    messagePlaceholder: 'What you did, what the desk did, and what you expected.',
    submitButtonLabel: 'Send report',
    onSubmitSuccess: () => toast.success('Report sent'),
    onSubmitError: () => toast.error('The report could not be sent'),
  })
  Sentry.addIntegration(added)
  feedback = added
  setReportProblem(() => void openFeedback(added))
}

/** The feedback form, with a screenshot button and the replay buffer; one at a time. */
async function openFeedback(integration: Feedback): Promise<void> {
  if (feedbackOpening || feedbackForm) return
  feedbackOpening = true
  let form: FeedbackForm | null = null
  const done = () => {
    form?.removeFromDom()
    if (feedbackForm === form) feedbackForm = null
  }
  try {
    form = await integration.createForm({ colorScheme: getTheme(), onFormClose: done, onFormSubmitted: done })
    if (integration !== feedback) {
      form.removeFromDom()
      return
    }
    feedbackForm = form
    form.appendToDom()
    form.open()
  } catch {
    // No form: the menu item stays and can be tried again.
  } finally {
    feedbackOpening = false
  }
}

function stop(): void {
  if (!running) return
  running = false
  generation++
  setReportProblem(null)
  feedbackForm?.removeFromDom()
  feedbackForm = null
  feedback?.remove()
  feedback = null
  // 11.7.0's remove() asks the shadow root for its parentElement, which is always null, so the host stays.
  document.getElementById('sentry-feedback')?.remove()
  // Off withdraws consent, so the buffer is dropped rather than flushed.
  void replay?.stop({ flush: false })
  void Sentry.close(2_000)
}

/** Starts Sentry if the desk says to, then lets the router start either way. */
export function startSentry(): void {
  subscribeWindowName(() => Sentry.setTag('window', windowName()))
  void fetchTelemetry()
    .then((telemetry) => {
      if (telemetry) start(telemetry)
    })
    .finally(() => {
      settleBootReady()
      markRouterMayStart()
    })
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
