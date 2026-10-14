import { describe, expect, it } from 'vitest'
import type { ErrorEvent } from '@sentry/react'
import type { TransactionEvent } from './sentryScrub'
import fixture from '../../../src/test/resources/telemetry/scrubCases.fixture.json'
import secrets from '../../../src/main/resources/telemetry/secret-patterns.json'
import { REDACTED, scrub, scrubBreadcrumb, scrubEvent, scrubOtherEvent, scrubRecordingEvent, scrubTransaction } from './sentryScrub'

const cases: { input: string; expected?: string }[] = fixture.cases

describe('sentryScrub', () => {
  it('scrubs every shared case as TokenScrubberTest does', () => {
    expect(cases.length).toBeGreaterThan(20)
    for (const c of cases) expect(scrub(c.input), c.input).toBe(c.expected ?? c.input)
  })

  it('has a redacting case for every pattern', () => {
    for (const p of secrets.patterns) {
      const re = new RegExp(p.pattern, 'ignoreCase' in p && p.ignoreCase ? 'i' : '')
      expect(cases.some((c) => c.expected != null && re.test(c.input)), p.about).toBe(true)
    }
  })

  it('scrubs a reset link wherever an event carries it', () => {
    const url = 'http://desk.local:8413/reset/AbC123xyz'
    const event = scrubEvent({
      type: undefined,
      message: `opened ${url}`,
      request: { url },
      exception: { values: [{ type: 'Error', value: `fetch ${url} failed` }] },
      breadcrumbs: [{ category: 'navigation', data: { from: '/', to: '/reset/AbC123xyz' } }],
    } as ErrorEvent)
    expect(JSON.stringify(event)).not.toContain('AbC123xyz')
    expect(event.request?.url).toBe(`http://desk.local:8413/reset/${REDACTED}`)
  })

  it('scrubs span descriptions and data in a transaction', () => {
    const tx = scrubTransaction({
      type: 'transaction',
      transaction: '/device/:token',
      spans: [{ description: 'GET /api/rest/auth/device/Zz9-Yy8', data: { url: '/api/rest/auth/device/Zz9-Yy8' } }],
    } as unknown as TransactionEvent)
    expect(tx.transaction).toBe('/device/:token')
    expect(JSON.stringify(tx)).not.toContain('Zz9-Yy8')
  })

  it('scrubs an error event\'s extra, tags, contexts and mechanism data, at any depth', () => {
    const url = 'https://desk.example/device/Zz9-Yy8'
    let deep: Record<string, unknown> = { url }
    for (let i = 0; i < 12; i++) deep = { deeper: deep }
    const event = scrubEvent({
      type: undefined,
      extra: { deep },
      tags: { link: url },
      contexts: { trace: { trace_id: 't', span_id: 's', data: { 'url.full': url } } },
      exception: { values: [{ type: 'Error', value: 'x', mechanism: { type: 'generic', handled: true, data: { url } } }] },
    } as ErrorEvent)
    expect(JSON.stringify(event)).not.toContain('Zz9-Yy8')
    expect(event.contexts?.trace?.trace_id).toBe('t')
  })

  it('leaves a breadcrumb without secrets alone', () => {
    const crumb = { category: 'fetch', data: { url: '/api/rest/projects/4', status_code: 200 } }
    expect(scrubBreadcrumb(crumb)).toEqual(crumb)
  })

  it("scrubs a replay event's URLs and a feedback's page and message, which no beforeSend sees", () => {
    const replay = scrubOtherEvent({
      type: 'replay_event',
      urls: ['http://desk.local:8413/reset/AbC123xyz', '/projects/1/busk'],
      request: { url: 'http://desk.local:8413/device/Zz9-Yy8' },
    } as Parameters<typeof scrubOtherEvent>[0])
    expect(replay.urls).toEqual([`http://desk.local:8413/reset/${REDACTED}`, '/projects/1/busk'])
    expect(replay.request?.url).toBe(`http://desk.local:8413/device/${REDACTED}`)

    const feedback = scrubOtherEvent({
      type: 'feedback',
      contexts: { feedback: { message: 'pasted ghp_abcdefghijklmnopqrstuvwxyz0123', url: '/reset/AbC123xyz' } },
    } as Parameters<typeof scrubOtherEvent>[0])
    expect(JSON.stringify(feedback)).not.toMatch(/ghp_abcdef|AbC123xyz/)

    const error = { message: '/reset/AbC123xyz' } as Parameters<typeof scrubOtherEvent>[0]
    expect(scrubOtherEvent(error).message).toBe('/reset/AbC123xyz')
  })

  it("scrubs a replay recording event's payload", () => {
    const event = scrubRecordingEvent({
      type: 5,
      timestamp: 1,
      data: { tag: 'performanceSpan', payload: { op: 'navigation.push', description: '/device/Zz9-Yy8' } },
    })
    expect(JSON.stringify(event)).not.toContain('Zz9-Yy8')
    expect(event.timestamp).toBe(1)
  })
})
