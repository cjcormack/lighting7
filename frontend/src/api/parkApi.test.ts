import { afterEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { PARK_ERROR_TOAST_ID, createParkApi } from './parkApi'

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

const errorToast = vi.mocked(toast.error)

afterEach(() => {
  vi.clearAllMocks()
})

describe('parkApi — venue parks', () => {
  it('keeps each park’s owner and the venue value under a re-park, and the released list', () => {
    const ws = fakeWsConnection()
    const park = createParkApi(ws.conn)
    const seen = vi.fn()
    park.subscribeEntries(seen)

    ws.frame({
      type: 'parkState',
      channels: [
        { universe: 0, channel: 5, value: 128, owner: 'VENUE' },
        { universe: 1, channel: 7, value: 200, owner: 'SHOW', venueValue: 255 },
        { universe: 0, channel: 9, value: 40, owner: 'SHOW' },
      ],
      released: [{ universe: 0, channel: 12, venueValue: 0 }, { universe: 2, channel: 1 }],
    })

    expect(park.getEntries().get('0:5')).toEqual({ value: 128, owner: 'VENUE' })
    expect(park.getEntries().get('1:7')).toEqual({ value: 200, owner: 'SHOW', venueValue: 255 })
    expect(park.getEntries().get('0:9')).toEqual({ value: 40, owner: 'SHOW' })
    expect(park.getAll().get('0:5')).toBe(128)
    expect(park.getReleased()).toEqual([{ universe: 0, channel: 12, venueValue: 0 }, { universe: 2, channel: 1 }])
    expect(seen).toHaveBeenCalledTimes(1)
  })

  it('reads a frame from a desk that predates venue parks as the project’s own, with nothing released', () => {
    const ws = fakeWsConnection()
    const park = createParkApi(ws.conn)

    ws.frame({ type: 'parkState', channels: [{ universe: 0, channel: 5, value: 128 }] })

    expect(park.getEntries().get('0:5')).toEqual({ value: 128, owner: 'SHOW' })
    expect(park.getReleased()).toEqual([])
  })

  it('sends a restore as its own frame', () => {
    const ws = fakeWsConnection()
    const park = createParkApi(ws.conn)

    expect(park.restore(0, 12)).toBe(true)
    expect(ws.sent).toEqual([{ type: 'restoreChannelPark', universe: 0, channel: 12 }])
  })

  it('toasts a refused park write under one id and leaves the list alone', () => {
    const ws = fakeWsConnection()
    const park = createParkApi(ws.conn)
    ws.frame({ type: 'parkState', channels: [{ universe: 0, channel: 5, value: 128, owner: 'VENUE' }], released: [] })

    ws.frame({ type: 'parkError', code: 'VENUE_ADMIN_ONLY', message: "Only an admin can change a venue's parks." })

    expect(errorToast).toHaveBeenCalledWith("Only an admin can change a venue's parks.", { id: PARK_ERROR_TOAST_ID })
    expect(park.getEntries().get('0:5')).toEqual({ value: 128, owner: 'VENUE' })
  })
})
