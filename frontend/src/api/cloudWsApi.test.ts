// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { InternalEventType } from './internalApi'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createCloudWsApi } from './cloudWsApi'

const session = { sid: 'sid-1', email: 'a@example.com', displayName: 'A', expiresAt: '2026-11-06T12:01:00.000Z' }

describe('createCloudWsApi', () => {
  it('keeps the latest remoteSession.pending and tells its subscribers', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createCloudWsApi(conn)
    const spy = vi.fn()
    api.subscribePending(spy)
    expect(api.pending()).toEqual([])

    frame({ type: 'remoteSession.pending', sessions: [session] })
    expect(spy).toHaveBeenLastCalledWith([session])
    expect(api.pending()).toEqual([session])

    frame({ type: 'remoteSession.pending', sessions: [] })
    expect(api.pending()).toEqual([])
  })

  it('forgets what was waiting when the socket closes, since nothing can be allowed until it is back', () => {
    const { conn, frame, fire } = fakeWsConnection()
    const api = createCloudWsApi(conn)
    const spy = vi.fn()
    api.subscribePending(spy)
    frame({ type: 'remoteSession.pending', sessions: [session] })
    fire(InternalEventType.close, new CloseEvent('close', { code: 1006 }))
    expect(api.pending()).toEqual([])
    expect(spy).toHaveBeenLastCalledWith([])
  })

  it('keeps cloud.state apart from the pending list', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createCloudWsApi(conn)
    const state = vi.fn()
    const pending = vi.fn()
    api.subscribe(state)
    api.subscribePending(pending)
    frame({ type: 'cloud.state', state: { configured: true, enabled: true } })
    expect(state).toHaveBeenCalledWith({ configured: true, enabled: true })
    expect(pending).not.toHaveBeenCalled()
  })
})
