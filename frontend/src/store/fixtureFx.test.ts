import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it (also stubs the fx subscriptions the
// store registers at module load).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { fixtureFxApi } from './fixtureFx'

/** The reset mutation's contract with the desk: `POST /fx/{id}/reset` (fixture-fx-sheets plan W5). */
describe('fixtureFx reset to template', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch({ 'fx/12/reset': { id: 12, effectType: 'Pulse' } })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('POSTs /fx/{id}/reset and answers the reset effect', async () => {
    const result = await store.dispatch(fixtureFxApi.endpoints.resetFxToTemplate.initiate({ id: 12 }))
    expect(result.data).toMatchObject({ id: 12 })
    const request = fetchMock.mock.calls.map((call) => call[0] as Request).find((r) => r.url.includes('fx/12/reset'))
    expect(request?.method).toBe('POST')
  })
})
