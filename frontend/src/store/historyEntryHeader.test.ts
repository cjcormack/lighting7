// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const { store } = await import('./index')
const { HISTORY_ENTRY_HEADER, trackHistoryWrites } = await import('./restApi')
const { entriesForRequests, historySlice, parseHistoryPage } = await import('./history')

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("a toast's entry, from the X-History-Entry header", () => {
  it("collects the request ids its endpoints' writes answered while tracking, and nothing after", async () => {
    installRelativeUrlRequest()
    let next = 1
    vi.stubGlobal('fetch', async (request: Request) => {
      const write = request.method !== 'GET'
      return new Response(JSON.stringify(write ? { undone: [] } : { projectId: 1, rows: [] }), {
        headers: { 'Content-Type': 'application/json', ...(write ? { [HISTORY_ENTRY_HEADER]: `h-${next++}` } : {}) },
      })
    })
    const writes = trackHistoryWrites(['historyMove'])
    const other = trackHistoryWrites(['deleteCues'])
    await store.dispatch(historySlice.endpoints.historyMove.initiate({ projectId: 1, direction: 'undo' }))
    await store.dispatch(historySlice.endpoints.historyPage.initiate({ projectId: 1 }, { forceRefetch: true }))
    await store.dispatch(historySlice.endpoints.historyMove.initiate({ projectId: 1, direction: 'redo' }))
    expect(writes.stop()).toEqual(['h-1', 'h-2'])
    // Another gesture's tracker, naming another endpoint, takes none of them.
    expect(other.stop()).toEqual([])
    await store.dispatch(historySlice.endpoints.historyMove.initiate({ projectId: 1, direction: 'undo' }))
    expect(writes.stop()).toEqual(['h-1', 'h-2'])
  })

  it('finds the entries holding those requests, newest first, and only show entries', () => {
    const page = parseHistoryPage({
      projectId: 1,
      rows: [
        { id: 'e-9', kind: 'SHOW', at: '', label: 'Later, by someone else', requestIds: ['h-20'] },
        { id: 'p-4', kind: 'PROGRAMMER', at: '', label: 'Clear' },
        { id: 'e-8', kind: 'SHOW', at: '', label: 'Delete Warm wash', requestIds: ['h-3', 'h-4'] },
        { id: 'e-7', kind: 'SHOW', at: '', label: 'Delete Cold wash', requestIds: ['h-2'] },
        { id: 'c-1', kind: 'CLEAR', at: '', label: 'Pulled from GitHub' },
      ],
    })
    expect(entriesForRequests(page, ['h-2', 'h-4', 'h-99']).map((row) => row.id)).toEqual(['e-8', 'e-7'])
  })
})
