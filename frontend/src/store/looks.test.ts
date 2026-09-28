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
      'projects/1/looks/4/toggle': { action: 'applied', effectCount: 2 },
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

  it('POST toggleLook addresses the Look by int id', async () => {
    // A layer and a toggle both name a Look by its int PK; only a stored `ref:` value uses the
    // uuid, because int PKs are re-minted on sync import.
    await store.dispatch(
      looksApi.endpoints.toggleLook.initiate({
        projectId: 1,
        lookId: 4,
        targets: [{ type: 'fixture', key: 'mh-1' }],
      }),
    )
    const req = lastRequestTo('looks/4/toggle')
    expect(req).toBeDefined()
    expect(req!.method).toBe('POST')
    expect(JSON.parse(await req!.clone().text())).toEqual({
      targets: [{ type: 'fixture', key: 'mh-1' }],
    })
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
   * `compatibleLookIds` is computed server-side and rides on the **fixture and group summaries**,
   * not on the Look. So a library mutation that doesn't invalidate those two lists leaves a Look
   * that exists but is offered nowhere: `LookTogglePicker` omits it and `LayerPicker` disables every
   * head for it, until something unrelated happens to refetch. The retired `store/fxPresets.ts`
   * invalidated both for exactly this reason, and the tags were dropped in the port.
   *
   * Asserted behaviourally — a live subscriber refetching — rather than by reading `invalidatesTags`,
   * because a tag that no query provides would still look right in the source.
   */
  // The two `createLook` cases that stood here went with the endpoint in session 3: a Look is
  // recorded now, never hand-authored, so no client sends `POST /looks`. `deleteLook` below is the
  // surviving mutation that has to refresh those lists, and it makes the same claim.

  it('deleteLook refetches them too, so the dead id leaves every compatibility list', async () => {
    const sub = store.dispatch(fixturesApi.endpoints.fixtureList.initiate())
    await sub
    const before = countRequestsTo('fixtures')

    await store.dispatch(looksApi.endpoints.deleteLook.initiate({ projectId: 1, lookId: 4 }))
    await vi.waitFor(() => expect(countRequestsTo('fixtures')).toBeGreaterThan(before))

    sub.unsubscribe()
  })

  /**
   * The economy half of the same claim. `compatibleLookIds` is derived by `compatibleIdsFor` from a
   * Look's **effect categories** and nothing else, so rows and metadata cannot move it — and
   * `LookRowStore` writes rows-only bodies every 400 ms for the length of a layer-scope drag. A
   * `Fixture` invalidation there refetches the list 48 consumers read (and which costs
   * `loadLookCompatibilityInfos` + `detectCapabilities` per fixture server-side) and hands every one
   * of them a new array identity mid-drag.
   *
   * Both arms in one test on purpose: the effects arm is what proves the subscriber was live and
   * would have refetched, so the rows arm is a real negative rather than a timing accident.
   */
  it('saveLook refetches the compatibility lists only when the body writes effects', async () => {
    const sub = store.dispatch(fixturesApi.endpoints.fixtureList.initiate())
    await sub
    const before = countRequestsTo('fixtures')

    await store.dispatch(looksApi.endpoints.saveLook.initiate({ projectId: 1, lookId: 4, rows: [] }))
    expect(countRequestsTo('fixtures')).toBe(before)

    await store.dispatch(
      looksApi.endpoints.saveLook.initiate({ projectId: 1, lookId: 4, effects: [] }),
    )
    await vi.waitFor(() => expect(countRequestsTo('fixtures')).toBeGreaterThan(before))

    sub.unsubscribe()
  })

  /**
   * And a copy does too, **whichever project it lands in**. Gating this on
   * `targetProjectId === projectId` looks like the right economy and is not: `fixtures` and
   * `groups` are the *active* project's, which the mutation cannot see, so the case that check skips
   * — copying out of another project's library into the active one, which is what "Copy to Project"
   * is for — is exactly the case that needs the refresh. This copy is cross-project (source 1,
   * target 2) precisely so the mistake would show.
   */
  it('copyLook refetches the compatibility lists even when the target is another project', async () => {
    const sub = store.dispatch(fixturesApi.endpoints.fixtureList.initiate())
    await sub
    const before = countRequestsTo('fixtures')

    await store.dispatch(
      looksApi.endpoints.copyLook.initiate({ projectId: 1, lookId: 4, targetProjectId: 2 }),
    )
    await vi.waitFor(() => expect(countRequestsTo('fixtures')).toBeGreaterThan(before))

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
