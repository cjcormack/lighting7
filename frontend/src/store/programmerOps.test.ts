import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it. The module graph reaches it through
// `store/index`.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { programmerOpsApi } from './programmerOps'
import { SILENT_ENDPOINTS } from './errorToastMiddleware'

/**
 * Wiring for the programmer's REST ops — the URL/method contract with the backend. The Spread
 * tab's mutation is the one under test here: it is a project-scoped route (busk-further plan
 * §3.5), unlike the three `programmer/*` routes beside it, which carry the project in the body.
 */
describe('programmerOps endpoints', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch({
      'projects/6/programmer/spread': {
        written: [{ target: { type: 'fixture', key: 'par-1' }, propertyName: 'dimmer', value: 'pct:0' }],
        skipped: [],
        skippedFamilies: [],
      },
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('POSTs a spread to the project’s programmer route with the project out of the body', async () => {
    const result = await store.dispatch(
      programmerOpsApi.endpoints.spread.initiate({
        projectId: 6,
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['INTENSITY'],
        property: 'dimmer',
        from: 'pct:0',
        to: 'pct:100',
        curve: 'LINE',
        order: 'LINEAR',
        parts: 1,
        over: 'HEADS',
        fadeMs: 250,
        seed: 0,
      }),
    )
    const request = fetchMock.mock.calls.at(-1)![0] as Request
    expect(request.url).toMatch(/\/api\/rest\/projects\/6\/programmer\/spread$/)
    expect(request.method).toBe('POST')
    const body = JSON.parse(await request.clone().text())
    expect(body).toEqual({
      targets: [{ type: 'fixture', key: 'par-1' }],
      families: ['INTENSITY'],
      property: 'dimmer',
      from: 'pct:0',
      to: 'pct:100',
      curve: 'LINE',
      order: 'LINEAR',
      parts: 1,
      over: 'HEADS',
      fadeMs: 250,
      seed: 0,
    })
    expect('data' in result ? result.data?.written?.[0].value : null).toBe('pct:0')
  })

  it('is not silent: its 400s reach errorToastMiddleware once, under the endpoint’s id', () => {
    // `SPREAD_NEEDS_SELECTION` is pre-empted by the tab (it never sends under an empty selection);
    // `SPREAD_INVALID` is the middleware's to say, keyed so a Live burst replaces one toast.
    expect(SILENT_ENDPOINTS.has('spread')).toBe(false)
  })
})
