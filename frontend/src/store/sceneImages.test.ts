import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it. The module graph reaches it through
// `store/index`.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { sceneImagesApi } from './sceneImages'

const HASH = 'a'.repeat(64)

/** The scene-image routes' wire contract (scrim plan §3.4). */
describe('sceneImages endpoints', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch({
      'projects/3/scene-images': { hash: HASH, width: 2, height: 1, hasAlpha: false, mediaType: 'image/png' },
      'projects/3/stage-elements/7/display-detail': { id: 7, fullDetail: true },
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('uploads the raw bytes with the image’s own content type, which the desk requires', async () => {
    const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]).buffer
    const result = await store.dispatch(
      sceneImagesApi.endpoints.uploadSceneImage.initiate({ projectId: 3, bytes, mediaType: 'image/png' }),
    )
    expect(result.data?.hash).toBe(HASH)
    const request = fetchMock.mock.calls.at(-1)![0] as Request
    expect(request.url).toMatch(/\/api\/rest\/projects\/3\/scene-images$/)
    expect(request.method).toBe('POST')
    // fetchBaseQuery drops a header-set content type on a binary body; the Blob's type survives.
    expect(request.headers.get('content-type')).toBe('image/png')
    expect(new Uint8Array(await request.clone().arrayBuffer())).toEqual(new Uint8Array(bytes))
  })

  it('PUTs the Full detail switch to the element’s own route', async () => {
    await store.dispatch(
      sceneImagesApi.endpoints.setElementDisplayDetail.initiate({ projectId: 3, elementId: 7, full: true }),
    )
    const request = fetchMock.mock.calls.at(-1)![0] as Request
    expect(request.url).toMatch(/\/api\/rest\/projects\/3\/stage-elements\/7\/display-detail$/)
    expect(request.method).toBe('PUT')
    expect(JSON.parse(await request.clone().text())).toEqual({ full: true })
  })
})
