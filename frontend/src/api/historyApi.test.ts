import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createHistoryWsApi, EMPTY_HISTORY, parseHistoryFrame } from './historyApi'

describe('historyApi', () => {
  it('reads a frame, an absent top as nothing to take (the desk Json drops defaults)', () => {
    const parsed = parseHistoryFrame({
      type: 'history.state',
      projectId: 3,
      revision: 5,
      undo: { id: 'p-2', label: 'Clear', kind: 'PROGRAMMER' },
      programmer: {
        undo: [{ id: 'p-2', label: 'Clear', at: '2026-11-02 14:02:31.000Z', source: { kind: 'ACCOUNT', name: 'Chris', window: 'w-1' } }],
      },
    })
    expect(parsed.undo).toEqual({ id: 'p-2', label: 'Clear', kind: 'PROGRAMMER' })
    expect(parsed.redo).toBeNull()
    expect(parsed.programmer.undo[0].source).toEqual({ kind: 'ACCOUNT', name: 'Chris', window: 'w-1' })
    expect(parsed.programmer.redo).toEqual([])
    expect(parseHistoryFrame(null)).toEqual(EMPTY_HISTORY)
  })

  it('streams the history to a late subscriber', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createHistoryWsApi(conn)
    expect(api.get()).toEqual(EMPTY_HISTORY)
    frame({ type: 'history.state', projectId: 1, revision: 2, programmer: {} })
    const seen = vi.fn()
    api.subscribe(seen)
    expect(seen).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1, revision: 2 }))
  })
})
