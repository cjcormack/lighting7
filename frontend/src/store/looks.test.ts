import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { failWith, installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it. Note this slice's WS bridge is *not*
// started on import (see `startLooksBridge`), so nothing here needs the subscription stub — but the
// module graph still reaches lightingApi through `store/index`.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { looksApi } from './looks'
import { fixturesApi } from './fixtures'

/**
 * Wiring tests for the Look library's endpoints: the URL/method contract with the backend, and the
 * invalidation guards. The guards are the part worth pinning — every mutation here deliberately
 * invalidates *nothing* on a failure, because the failures are ordinary flow steps (a duplicate
 * name, a LOOK_IN_USE delete) and refetching the library to learn nothing changed is pure churn
 * behind an open sheet.
 */
describe('looks endpoints', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    // Keys are matched by substring, most-specific first.
    fetchMock = installRecordingFetch({
      'projects/1/looks/4/toggle': { action: 'applied', fxCount: 2 },
      'projects/1/looks/4/copy': {
        lookId: 9,
        lookName: 'Warm (Copy)',
        targetProjectId: 2,
        targetProjectName: 'Other',
        message: 'ok',
      },
      'projects/1/looks/4': { id: 4, uuid: 'u4', name: 'Warm', rows: [], effects: [] },
      'projects/1/looks?family=COLOUR': [],
      'projects/1/looks': [],
      'fixtures': [],
      groups: [],
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  function countRequestsTo(fragment: string): number {
    return fetchMock.mock.calls.filter((call) =>
      (call[0] as Request).url.includes(fragment),
    ).length
  }

  function lastRequestTo(fragment: string): Request | undefined {
    for (let i = fetchMock.mock.calls.length - 1; i >= 0; i--) {
      const input = fetchMock.mock.calls[i][0] as Request
      if (input.url.includes(fragment)) return input
    }
    return undefined
  }

  it('GET lookList hits the unfiltered collection', async () => {
    await store.dispatch(looksApi.endpoints.lookList.initiate({ projectId: 1 }))
    const req = lastRequestTo('projects/1/looks')
    expect(req).toBeDefined()
    expect(req!.method).toBe('GET')
    expect(new URL(req!.url).search).toBe('')
  })

  it('GET lookList passes a family through as a query param', async () => {
    // The backend derives families from a Look's rows, so it filters after building the summaries
    // — there is no column to query. An unknown value is a 400, which is why this is a typed union
    // client-side rather than a free string.
    await store.dispatch(looksApi.endpoints.lookList.initiate({ projectId: 1, family: 'COLOUR' }))
    const req = lastRequestTo('family=COLOUR')
    expect(req).toBeDefined()
    expect(req!.method).toBe('GET')
  })

  it('PUT saveLook sends only the keys it was given', async () => {
    // Load-bearing: the backend receives a raw JSON object so absent and empty are different.
    // Omitting `rows` leaves the contents alone; sending `[]` would clear them, out from under
    // every cue resolving through this Look. A metadata edit must therefore not mention them.
    await store.dispatch(
      looksApi.endpoints.saveLook.initiate({ projectId: 1, lookId: 4, name: 'Warmer' }),
    )
    const req = lastRequestTo('projects/1/looks/4')
    expect(req).toBeDefined()
    expect(req!.method).toBe('PUT')
    expect(JSON.parse(await req!.clone().text())).toEqual({ name: 'Warmer' })
  })

  it('DELETE deleteLook appends force only when asked', async () => {
    await store.dispatch(looksApi.endpoints.deleteLook.initiate({ projectId: 1, lookId: 4 }))
    expect(new URL(lastRequestTo('projects/1/looks/4')!.url).search).toBe('')

    await store.dispatch(
      looksApi.endpoints.deleteLook.initiate({ projectId: 1, lookId: 4, force: true }),
    )
    expect(new URL(lastRequestTo('force=true')!.url).search).toBe('?force=true')
  })

  it('POST toggleLook addresses the Look by int id and names no targets', async () => {
    // A layer and a toggle both name a Look by its int PK. A Look lands whole (absolute-looks plan
    // D1), so the press carries no targets and no mask.
    await store.dispatch(looksApi.endpoints.toggleLook.initiate({ projectId: 1, lookId: 4 }))
    const req = lastRequestTo('looks/4/toggle')
    expect(req).toBeDefined()
    expect(req!.method).toBe('POST')
    expect(JSON.parse(await req!.clone().text())).toEqual({})
  })

  it('copyLook invalidates the *target* project’s library, not this one', async () => {
    // The copy lands in whichever project was named, which may not be the one on screen. Keying
    // the tag on `projectId` would leave the target project's library stale.
    await store.dispatch(
      looksApi.endpoints.copyLook.initiate({ projectId: 1, lookId: 4, targetProjectId: 2 }),
    )
    const req = lastRequestTo('looks/4/copy')
    expect(req).toBeDefined()
    expect(JSON.parse(await req!.clone().text())).toEqual({ targetProjectId: 2 })
  })

  /**
   * Nothing on a fixture or group summary depends on a Look (absolute-looks plan P7 took
   * `compatibleLookIds` off them), so no Look mutation refetches the fixture list — which matters
   * most for `saveLook`, which `LookRowStore` sends every 400 ms through a layer-scope drag.
   */
  it('no Look mutation refetches the fixture list', async () => {
    const sub = store.dispatch(fixturesApi.endpoints.fixtureList.initiate())
    await sub
    const before = countRequestsTo('fixtures')

    await store.dispatch(looksApi.endpoints.saveLook.initiate({ projectId: 1, lookId: 4, rows: [], fx: [] }))
    await store.dispatch(looksApi.endpoints.deleteLook.initiate({ projectId: 1, lookId: 4 }))
    await store.dispatch(looksApi.endpoints.copyLook.initiate({ projectId: 1, lookId: 4, targetProjectId: 2 }))
    expect(countRequestsTo('fixtures')).toBe(before)

    sub.unsubscribe()
  })
})

/** The guard half: a failed mutation must not invalidate, so nothing refetches to learn nothing. */
describe('looks invalidation guards', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/looks/4': failWith(409, {
        error: 'This look is still used by 2 cue layer(s)',
        code: 'LOOK_IN_USE',
        layerCount: 2,
        refRowCount: 0,
        cueIds: [1, 2],
        cueNames: ['Act 1', 'Act 2'],
      }),
      'projects/1/looks': failWith(409, { error: 'A look named Warm already exists' }),
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('surfaces the LOOK_IN_USE body so the sheet can offer "delete anyway"', async () => {
    const result = await store.dispatch(
      looksApi.endpoints.deleteLook.initiate({ projectId: 1, lookId: 4 }),
    )
    const error = 'error' in result ? result.error : undefined
    expect((error as { status?: number })?.status).toBe(409)
    expect((error as { data?: { code?: string } })?.data?.code).toBe('LOOK_IN_USE')
  })

})
