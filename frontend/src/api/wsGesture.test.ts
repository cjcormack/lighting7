import { afterEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createParkApi } from './parkApi'
import { createProgrammerApi } from './programmerWsApi'
import { createSurfacesWsApi } from './surfacesApi'
import { WS_GESTURE_DROPPED_MESSAGE, WS_GESTURE_DROPPED_TOAST_ID, sendGesture } from './wsGesture'

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

const errorToast = vi.mocked(toast.error)

afterEach(() => {
  vi.clearAllMocks()
})

describe('sendGesture', () => {
  it('writes the frame and says nothing while the socket is up', () => {
    const ws = fakeWsConnection()

    expect(sendGesture(ws.conn, { type: 'programmer.clearAll' })).toBe(true)
    expect(ws.sent).toEqual([{ type: 'programmer.clearAll' }])
    expect(errorToast).not.toHaveBeenCalled()
  })

  it('toasts under one id, so a drag against a dead socket raises one toast', () => {
    const ws = fakeWsConnection()
    ws.setOpen(false)

    for (let value = 0; value < 40; value++) {
      expect(sendGesture(ws.conn, { type: 'programmer.set', value })).toBe(false)
    }

    expect(ws.sent).toEqual([])
    expect(errorToast).toHaveBeenCalledTimes(40)
    for (const call of errorToast.mock.calls) {
      expect(call).toEqual([WS_GESTURE_DROPPED_MESSAGE, { id: WS_GESTURE_DROPPED_TOAST_ID }])
    }
  })
})

describe('operator gestures over a socket that is down', () => {
  it('announces a dropped programmer write', () => {
    const ws = fakeWsConnection()
    const programmer = createProgrammerApi(ws.conn)
    ws.setOpen(false)

    programmer.setBlind(true, 0)

    expect(errorToast).toHaveBeenCalledWith(WS_GESTURE_DROPPED_MESSAGE, {
      id: WS_GESTURE_DROPPED_TOAST_ID,
    })
  })

  it('announces a dropped blackout', () => {
    const ws = fakeWsConnection()
    const surfaces = createSurfacesWsApi(ws.conn)
    ws.setOpen(false)

    surfaces.setBlackout(true)

    expect(errorToast).toHaveBeenCalledWith(WS_GESTURE_DROPPED_MESSAGE, {
      id: WS_GESTURE_DROPPED_TOAST_ID,
    })
  })

  it('announces a dropped park, and reports it to its caller', () => {
    const ws = fakeWsConnection()
    const park = createParkApi(ws.conn)

    // The boolean is what `store/park.ts` turns into a real RTK error, so `.unwrap()` and
    // `isError` stop reporting success for a frame that never left the browser.
    expect(park.park(1, 4, 255)).toBe(true)

    ws.setOpen(false)
    expect(park.park(1, 4, 255)).toBe(false)
    expect(park.unpark(1, 4)).toBe(false)
    expect(errorToast).toHaveBeenCalledWith(WS_GESTURE_DROPPED_MESSAGE, {
      id: WS_GESTURE_DROPPED_TOAST_ID,
    })
  })

  it('stays silent for an idempotent state re-request', () => {
    const ws = fakeWsConnection()
    const programmer = createProgrammerApi(ws.conn)
    ws.setOpen(false)

    // Every bridge re-asks for its state on open; blaming the operator for the reconnect
    // machinery finding the socket still down would toast on every flap.
    programmer.requestState()

    expect(errorToast).not.toHaveBeenCalled()
  })
})
