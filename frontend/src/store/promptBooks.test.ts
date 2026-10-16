import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { promptBooksApi } from './promptBooks'

const region = (y: number) => [{ page: 0, x: 0.1, y, w: 0.5, h: 0.02 }]

/**
 * Anchors are an entry's (lighting7 cue model plan D4): a cue shared by two stacks is two places in
 * the show, each anchored on its own. The routes and the optimistic patches name the entry.
 */
describe('prompt-book anchors by entry', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch({
      'projects/1/prompt-book/anchors/': { cueId: 5, entryId: 205, region: region(0.6), label: 'Q9' },
      'projects/1/prompt-book': {
        scriptHash: 'h',
        scriptFileName: null,
        pageCount: 1,
        coverPages: 0,
        // Cue 5 sits in two stacks, anchored at two places.
        anchors: [
          { cueId: 5, entryId: 105, region: region(0.2), label: 'Q1' },
          { cueId: 5, entryId: 205, region: region(0.4), label: 'Q9' },
        ],
        annotations: [],
        canEdit: true,
      },
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  const anchorsInCache = () => promptBooksApi.endpoints.projectPromptBook.select(1)(store.getState()).data?.anchors

  it('moves one entry’s anchor and leaves the same cue’s other anchor where it is', async () => {
    await store.dispatch(promptBooksApi.endpoints.projectPromptBook.initiate(1))
    await vi.waitFor(() => expect(anchorsInCache()).toHaveLength(2))

    const promise = store.dispatch(
      promptBooksApi.endpoints.upsertAnchor.initiate({ projectId: 1, entryId: 205, cueId: 5, region: region(0.6), label: 'Q9' }),
    )
    expect(anchorsInCache()).toEqual([
      { cueId: 5, entryId: 105, region: region(0.2), label: 'Q1' },
      { cueId: 5, entryId: 205, region: region(0.6), label: 'Q9' },
    ])
    await promise
    const req = fetchMock.mock.calls.map((c) => c[0] as Request).find((r) => r.method === 'PUT')!
    expect(req.url).toMatch(/\/prompt-book\/anchors\/205$/)
    expect(JSON.parse(await req.clone().text())).toEqual({ region: region(0.6), label: 'Q9' })
  })

  it('anchors a second place of a cue as a row of its own', async () => {
    await store.dispatch(promptBooksApi.endpoints.projectPromptBook.initiate(1))
    await vi.waitFor(() => expect(anchorsInCache()).toHaveLength(2))
    void store.dispatch(
      promptBooksApi.endpoints.upsertAnchor.initiate({ projectId: 1, entryId: 305, cueId: 5, region: region(0.8) }),
    )
    expect(anchorsInCache()?.map((a) => a.entryId)).toEqual([105, 205, 305])
  })

  it('removes one entry’s anchor by its entry', async () => {
    await store.dispatch(promptBooksApi.endpoints.projectPromptBook.initiate(1))
    await vi.waitFor(() => expect(anchorsInCache()).toHaveLength(2))
    const promise = store.dispatch(promptBooksApi.endpoints.deleteAnchor.initiate({ projectId: 1, entryId: 105 }))
    expect(anchorsInCache()?.map((a) => a.entryId)).toEqual([205])
    await promise
    const req = fetchMock.mock.calls.map((c) => c[0] as Request).find((r) => r.method === 'DELETE')!
    expect(req.url).toMatch(/\/prompt-book\/anchors\/105$/)
  })
})
