/**
 * The browser's half of the desk's token scrubbing (Sentry plan §7). The patterns are the
 * backend's own list, `src/main/resources/telemetry/secret-patterns.json`, compiled here as
 * `TokenScrubber` compiles them; `scrubCases.fixture.json` is run by both test suites, so a
 * difference in how the two apply them fails one.
 */
import type { Breadcrumb, BrowserOptions, ErrorEvent, Event } from '@sentry/react'
import secrets from '../../../src/main/resources/telemetry/secret-patterns.json'

export type TransactionEvent = Parameters<NonNullable<BrowserOptions['beforeSendTransaction']>>[0]

export const REDACTED: string = secrets.replacement

const PATTERNS: readonly RegExp[] = secrets.patterns.map(
  (p) => new RegExp(p.pattern, 'ignoreCase' in p && p.ignoreCase ? 'gi' : 'g'),
)

/** Redacts every secret in [text], keeping each pattern's group 1 where it has one. */
export function scrub(text: string): string {
  return PATTERNS.reduce(
    (acc, pattern) =>
      acc.replace(pattern, (_match, kept: unknown) => (typeof kept === 'string' ? kept : '') + REDACTED),
    text,
  )
}

const MAX_DEPTH = 8

function scrubValue(value: unknown, depth = 0): unknown {
  if (typeof value === 'string') return scrub(value)
  if (value == null || typeof value !== 'object') return value
  // Deeper than anything the SDK builds: sent as scrubbed text rather than walked further.
  if (depth >= MAX_DEPTH) {
    try {
      return scrub(JSON.stringify(value))
    } catch {
      return REDACTED
    }
  }
  if (Array.isArray(value)) return value.map((v) => scrubValue(v, depth + 1))
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(value)) out[k] = scrubValue(v, depth + 1)
  return out
}

export function scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
  return {
    ...crumb,
    ...(crumb.message != null && { message: scrub(crumb.message) }),
    ...(crumb.data != null && { data: scrubValue(crumb.data) as Breadcrumb['data'] }),
  }
}

function scrubCommon<E extends Event>(event: E): E {
  if (event.message != null) event.message = scrub(event.message)
  if (event.transaction != null) event.transaction = scrub(event.transaction)
  if (event.request?.url != null) event.request.url = scrub(event.request.url)
  if (typeof event.request?.query_string === 'string') event.request.query_string = scrub(event.request.query_string)
  if (event.request?.headers?.Referer != null) event.request.headers.Referer = scrub(event.request.headers.Referer)
  if (event.breadcrumbs != null) event.breadcrumbs = event.breadcrumbs.map(scrubBreadcrumb)
  for (const exception of event.exception?.values ?? []) {
    if (exception.value != null) exception.value = scrub(exception.value)
    const mechanism = exception.mechanism
    if (mechanism?.data != null) mechanism.data = scrubValue(mechanism.data) as typeof mechanism.data
  }
  if (event.extra != null) event.extra = scrubValue(event.extra) as typeof event.extra
  if (event.tags != null) event.tags = scrubValue(event.tags) as typeof event.tags
  if (event.contexts != null) event.contexts = scrubValue(event.contexts) as typeof event.contexts
  return event
}

export function scrubEvent(event: ErrorEvent): ErrorEvent {
  return scrubCommon(event)
}

/** Span descriptions and string data too: a fetch span is named by its URL. */
export function scrubTransaction(event: TransactionEvent): TransactionEvent {
  scrubCommon(event)
  for (const span of event.spans ?? []) {
    if (span.description != null) span.description = scrub(span.description)
    if (span.data != null) span.data = scrubValue(span.data) as typeof span.data
  }
  return event
}

/**
 * A replay's and a feedback's events, which no `beforeSend` sees: the replay event's `urls` and
 * request, and the feedback's message and page URL (in `contexts`).
 */
export function scrubOtherEvent<E extends Event & { urls?: unknown }>(event: E): E {
  if (event.type !== 'replay_event' && event.type !== 'feedback') return event
  scrubCommon(event)
  if (Array.isArray(event.urls)) event.urls = event.urls.map((u) => (typeof u === 'string' ? scrub(u) : u))
  return event
}

/**
 * Replay's own recording events — its breadcrumbs, navigation and network spans. The page URL in a
 * DOM snapshot is not one of them, which is why a page loaded on a secret URL records no replay.
 */
export function scrubRecordingEvent<R extends { data?: unknown }>(event: R): R {
  return event.data == null ? event : { ...event, data: scrubValue(event.data) }
}
