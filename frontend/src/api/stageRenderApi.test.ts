import { describe, expect, it } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createStageRenderWsApi, parseStageRenderRequest, type StageRenderRequest } from './stageRenderApi'

/**
 * `stageRender.request` as this side reads it (lighting7 `plugins/StageRenderSocket.kt`). The desk
 * sends it to one socket only, so a well-formed frame is this window's job — and anything else is
 * dropped rather than half-rendered.
 */

const SEATING = '00000000-0000-4000-8000-00000000000a'

const frame = (over: Record<string, unknown> = {}) => ({
  type: 'stageRender.request',
  requestId: 'r-1',
  token: 't-1',
  projectId: 15,
  viewpoint: `seat:${SEATING}:F6`,
  width: 1280,
  height: 720,
  source: 'output',
  timeoutMs: 30_000,
  ...over,
})

describe('parseStageRenderRequest', () => {
  it('reads the frame the desk sends', () => {
    expect(parseStageRenderRequest(frame())).toEqual<StageRenderRequest>({
      requestId: 'r-1',
      token: 't-1',
      projectId: 15,
      viewpoint: `seat:${SEATING}:F6`,
      width: 1280,
      height: 720,
      source: 'output',
      workLights: 'off',
      timeoutMs: 30_000,
    })
  })

  it('reads the capture’s work lights, a missing one as off (a desk that predates the field)', () => {
    expect(parseStageRenderRequest(frame({ workLights: true }))?.workLights).toBe('on')
    expect(parseStageRenderRequest(frame({ workLights: false }))?.workLights).toBe('off')
    expect(parseStageRenderRequest(frame())?.workLights).toBe('off')
  })

  it('takes every viewpoint in the vocabulary: a camera, a saved view uuid, a seat', () => {
    for (const viewpoint of ['plan', 'eye', SEATING, `seat:${SEATING}:A1`]) {
      expect(parseStageRenderRequest(frame({ viewpoint }))?.viewpoint).toBe(viewpoint)
    }
  })

  it('drops a frame it cannot render as asked', () => {
    expect(parseStageRenderRequest(frame({ viewpoint: 'Row F centre' }))).toBeNull()
    expect(parseStageRenderRequest(frame({ source: 'dmx' }))).toBeNull()
    expect(parseStageRenderRequest(frame({ width: 12.5 }))).toBeNull()
    expect(parseStageRenderRequest(frame({ height: 0 }))).toBeNull()
    expect(parseStageRenderRequest(frame({ token: undefined }))).toBeNull()
    expect(parseStageRenderRequest(frame({ type: 'windows.show' }))).toBeNull()
    // Work lights are a boolean on the wire; a word is not read as one.
    expect(parseStageRenderRequest(frame({ workLights: 'on' }))).toBeNull()
    expect(parseStageRenderRequest(null)).toBeNull()
  })
})

describe('createStageRenderWsApi', () => {
  it('hands each request to the subscriber, and nothing else the socket carries', () => {
    const ws = fakeWsConnection()
    const api = createStageRenderWsApi(ws.conn)
    const seen: StageRenderRequest[] = []
    api.subscribe((r) => seen.push(r))

    ws.frame({ type: 'windows.show', targetId: 'x', view: '/busk' })
    ws.frame(frame({ viewpoint: 'not-a-viewpoint' }))
    ws.frame(frame({ requestId: 'r-2', viewpoint: 'front' }))

    expect(seen.map((r) => [r.requestId, r.viewpoint])).toEqual([['r-2', 'front']])
    expect(ws.sent).toEqual([])
  })
})
