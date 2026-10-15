import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { NOTHING_SAFE, createRigSafetyWsApi, parseRigSafetyFrame } from './rigSafetyApi'

describe('rigSafetyApi', () => {
  it('reads a frame, an absent list or flag as empty or false, and stamps the receive time', () => {
    const state = parseRigSafetyFrame({ type: 'rigSafety.state', projectId: 4, ready: true, blackout: true }, 1000)
    expect(state).toEqual({ ...NOTHING_SAFE, projectId: 4, ready: true, blackout: true, receivedAt: 1000 })
    expect(parseRigSafetyFrame(null, 5)).toEqual({ ...NOTHING_SAFE, receivedAt: 5 })
    const run = parseRigSafetyFrame({ run: { id: 'r', role: 'LAMP_ON', purpose: 'UNDO', startedAt: 'x' } }, 0).run
    expect(run?.units).toEqual([])
    expect(run?.finished).toBe(false)
  })

  it('streams the frame to a late subscriber, and ignores other frames', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createRigSafetyWsApi(conn)
    expect(api.get()).toEqual(NOTHING_SAFE)
    frame({ type: 'grandMaster.state', projectId: 1, level: 50 })
    expect(api.get()).toEqual(NOTHING_SAFE)
    frame({ type: 'rigSafety.state', projectId: 1, ready: true, quitAllowed: true })
    const seen = vi.fn()
    api.subscribe(seen)
    expect(seen).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1, ready: true, quitAllowed: true }))
  })
})
