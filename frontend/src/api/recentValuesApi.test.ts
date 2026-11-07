import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createRecentValuesWsApi, NO_RECENT_VALUES, parseRecentValuesFrame } from './recentValuesApi'

describe('recentValuesApi', () => {
  it('reads a frame, and anything malformed as no lists', () => {
    expect(parseRecentValuesFrame({ type: 'recentValues.state', projectId: 3, lists: { colour: ['#ff0000', 7], 'level:dimmer': ['pct:50'] } })).toEqual({
      projectId: 3,
      lists: { colour: ['#ff0000'], 'level:dimmer': ['pct:50'] },
    })
    expect(parseRecentValuesFrame({ type: 'recentValues.state', projectId: 3 })).toEqual({ projectId: 3, lists: {} })
    expect(parseRecentValuesFrame(null)).toEqual(NO_RECENT_VALUES)
  })

  it('streams the lists to a late subscriber and sends a record, silently when the socket is down', () => {
    const { conn, sent, frame, setOpen } = fakeWsConnection()
    const api = createRecentValuesWsApi(conn)
    expect(api.get()).toEqual(NO_RECENT_VALUES)

    frame({ type: 'recentValues.state', projectId: 1, lists: { colour: ['#102030;w0'] } })
    const seen = vi.fn()
    api.subscribe(seen)
    expect(seen).toHaveBeenCalledWith({ projectId: 1, lists: { colour: ['#102030;w0'] } })

    api.record('colour', '#ff8800')
    expect(sent).toEqual([{ type: 'recentValues.record', key: 'colour', value: '#ff8800' }])

    setOpen(false)
    api.record('colour', '#000000')
    expect(sent).toHaveLength(1)
  })
})
