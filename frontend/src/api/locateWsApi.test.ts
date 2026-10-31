import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createLocateWsApi, parseLocateFrame } from './locateWsApi'

describe('locateWsApi', () => {
  it('reads a frame and drops a malformed target', () => {
    expect(
      parseLocateFrame({
        type: 'locate.state',
        projectId: 2,
        targets: [{ type: 'fixture', key: 'hex-1' }, { type: 'universe', key: '0' }, { type: 'group' }],
      }),
    ).toEqual([{ type: 'fixture', key: 'hex-1' }])
    expect(parseLocateFrame({ type: 'locate.state', projectId: 2 })).toEqual([])
    expect(parseLocateFrame(null)).toEqual([])
  })

  it('keeps the last frame and replays it to a late subscriber', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createLocateWsApi(conn)
    expect(api.get()).toEqual([])

    const early = vi.fn()
    api.subscribe(early)
    expect(early).not.toHaveBeenCalled()

    frame({ type: 'locate.state', projectId: 1, targets: [{ type: 'group', key: 'wash' }] })
    expect(early).toHaveBeenLastCalledWith([{ type: 'group', key: 'wash' }])
    expect(api.get()).toEqual([{ type: 'group', key: 'wash' }])

    const late = vi.fn()
    api.subscribe(late)
    expect(late).toHaveBeenCalledWith([{ type: 'group', key: 'wash' }])

    frame({ type: 'locate.state', projectId: 1, targets: [] })
    expect(late).toHaveBeenLastCalledWith([])
    expect(api.frames()).toBe(2)
  })
})
