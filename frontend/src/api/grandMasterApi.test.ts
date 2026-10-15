import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { AT_FULL, createGrandMasterWsApi, parseGrandMasterFrame } from './grandMasterApi'

describe('grandMasterApi', () => {
  it('reads a frame, a missing level as full (the desk Json drops defaults)', () => {
    expect(parseGrandMasterFrame({ type: 'grandMaster.state', projectId: 3, level: 42.5 })).toEqual({ projectId: 3, level: 42.5 })
    expect(parseGrandMasterFrame({ type: 'grandMaster.state', projectId: 3 })).toEqual({ projectId: 3, level: 100 })
    expect(parseGrandMasterFrame(null)).toEqual(AT_FULL)
  })

  it('streams the level to a late subscriber and sends a set as a gesture', () => {
    const { conn, sent, frame, setOpen } = fakeWsConnection()
    const api = createGrandMasterWsApi(conn)
    expect(api.get()).toEqual(AT_FULL)

    frame({ type: 'grandMaster.state', projectId: 1, level: 60 })
    const seen = vi.fn()
    api.subscribe(seen)
    expect(seen).toHaveBeenCalledWith({ projectId: 1, level: 60 })

    api.set(25)
    expect(sent).toEqual([{ type: 'grandMaster.set', level: 25 }])

    setOpen(false)
    api.set(10)
    expect(sent).toHaveLength(1)
  })
})
