// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const { store } = await import('./index')
const { DESK_WINDOW_HEADER, setDeskWindowSource } = await import('./restApi')
const { historySlice } = await import('./history')

afterEach(() => {
  vi.unstubAllGlobals()
  setDeskWindowSource(() => null)
})

describe('the desk window header', () => {
  it('names this window on every REST call once it has a registry row, and is absent before', async () => {
    installRelativeUrlRequest()
    const seen: (string | null)[] = []
    vi.stubGlobal('fetch', async (request: Request) => {
      seen.push(request.headers.get(DESK_WINDOW_HEADER))
      return new Response(JSON.stringify({ projectId: 1, rows: [] }), { headers: { 'Content-Type': 'application/json' } })
    })
    await store.dispatch(historySlice.endpoints.historyPage.initiate({ projectId: 1 }, { forceRefetch: true }))
    setDeskWindowSource(() => 'w-9')
    await store.dispatch(historySlice.endpoints.historyPage.initiate({ projectId: 1 }, { forceRefetch: true }))
    expect(seen).toEqual([null, 'w-9'])
  })
})
