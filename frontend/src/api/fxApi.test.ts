import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createFxApi } from './fxApi'

describe('createFxApi — updateFx and fxError (fixture-fx-sheets plan W3)', () => {
  it('sends updateFx with the request fields beside the effect id', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createFxApi(conn)

    expect(api.updateFx(12, { beatDivision: 2, blendMode: 'ADDITIVE', parameters: { min: '40' } })).toBe(true)
    expect(sent).toEqual([
      { type: 'updateFx', effectId: 12, beatDivision: 2, blendMode: 'ADDITIVE', parameters: { min: '40' } },
    ])
  })

  it('is a gesture: a dead socket sends nothing and answers false', () => {
    const { conn, sent, setOpen } = fakeWsConnection()
    const api = createFxApi(conn)
    setOpen(false)
    expect(api.updateFx(12, { beatDivision: 2 })).toBe(false)
    expect(sent).toEqual([])
  })

  it('hands every fxError to its subscribers, and stops when unsubscribed', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createFxApi(conn)
    const listener = vi.fn()
    const subscription = api.subscribeToErrors(listener)

    frame({ type: 'fxError', effectId: 12, code: 'FX_UPDATE_REFUSED', message: "Unknown blendMode 'SIDEWAYS'" })
    expect(listener).toHaveBeenCalledWith({
      effectId: 12,
      code: 'FX_UPDATE_REFUSED',
      message: "Unknown blendMode 'SIDEWAYS'",
    })

    subscription.unsubscribe()
    frame({ type: 'fxError', effectId: 12, code: 'FX_NOT_FOUND', message: 'gone' })
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('an fxError neither touches the effect list nor asks for one', () => {
    const { conn, sent, frame } = fakeWsConnection()
    const api = createFxApi(conn)
    const state = vi.fn()
    api.subscribe(state)
    frame({ type: 'fxError', effectId: 12, code: 'FX_NOT_FOUND', message: 'gone' })
    expect(state).not.toHaveBeenCalled()
    expect(sent).toEqual([])
  })

  it('an fxChanged still asks for the list, as before', () => {
    const { conn, sent, frame } = fakeWsConnection()
    createFxApi(conn)
    frame({ type: 'fxChanged', changeType: 'updated', effectId: 12 })
    expect(sent).toEqual([{ type: 'fxState' }])
  })
})
