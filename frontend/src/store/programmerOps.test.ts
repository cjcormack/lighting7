import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it. The module graph reaches it through
// `store/index`.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { programmerOpsApi } from './programmerOps'
import { cuesApi } from './cues'
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

/**
 * A Record or an Update that writes scenery rows moves what every later cue in the stack tracks, so
 * it refreshes every cue entry — `setCueScenery`'s rule — and not just the one it named
 * (scenery-programmer plan session 3).
 */
describe('scenery writes refresh every cue entry', () => {
  const recordBody = (sceneryWritten: number) => ({
    cue: { id: 5 }, created: true, assignmentsWritten: 0, assignmentsRemoved: 0, groupRowsEmitted: 0, fxWritten: 0,
    preserved: {}, republishedLive: false, skipped: [], warnings: [], sceneryWritten,
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  async function cueFetchesAfter(run: () => Promise<unknown>, routes: Record<string, unknown>) {
    installRelativeUrlRequest()
    const fetchMock = installRecordingFetch({ 'projects/6/cues/9': { id: 9, name: 'Q16' }, ...routes })
    const sub = store.dispatch(cuesApi.endpoints.projectCue.initiate({ projectId: 6, cueId: 9 }))
    await sub
    const cueCalls = () => fetchMock.mock.calls.filter(([r]) => String((r as Request).url).endsWith('/cues/9')).length
    const before = cueCalls()
    await run()
    await new Promise((resolve) => setTimeout(resolve, 20))
    const after = cueCalls()
    sub.unsubscribe()
    return after - before
  }

  it('a CREATE that wrote scenery refetches a later cue; one that wrote none does not', async () => {
    const create = () =>
      store.dispatch(programmerOpsApi.endpoints.recordProgrammer.initiate({ projectId: 6, mode: 'CREATE', cueStackId: 1 }))
    expect(await cueFetchesAfter(create, { 'programmer/record': recordBody(1) })).toBe(1)
    expect(await cueFetchesAfter(create, { 'programmer/record': recordBody(0) })).toBe(0)
  })

  it('an Update that wrote scenery back refetches every cue entry', async () => {
    const update = () => store.dispatch(programmerOpsApi.endpoints.updateProgrammer.initiate({ projectId: 6 }))
    const body = (sceneryWritten: number) => ({
      applied: true, mode: 'A', results: [{ cueId: 5, cueName: 'Q15', assignmentsWritten: 0, fxWritten: 0, republishedLive: false, sceneryWritten }],
      skipped: [], warnings: [],
    })
    expect(await cueFetchesAfter(update, { 'programmer/update': body(1) })).toBe(1)
    expect(await cueFetchesAfter(update, { 'programmer/update': body(0) })).toBe(0)
  })
})
