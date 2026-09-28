import { beforeEach, describe, expect, it, vi } from 'vitest'

const errorSubscribers: ((message: string) => void)[] = []

// A capturing stub rather than `backendMock`'s `noopSub`: the whole point of this suite is what the
// bridge does with a frame, so the subscription has to actually deliver one.
vi.mock('@/api/lightingApi', () => ({
  lightingApi: {
    programmer: {
      subscribeToErrors: (fn: (message: string) => void) => {
        errorSubscribers.push(fn)
        return { unsubscribe: () => {} }
      },
    },
  },
}))

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

import { toast } from 'sonner'
import { PROGRAMMER_ERROR_TOAST_ID, startProgrammerErrorBridge } from './programmerErrors'

/**
 * The programmer's WS write path is the one place a refused operator action used to be silent —
 * `subscribeToErrors` existed with no production subscriber. These pin that it has one, and that a
 * drag's burst collapses instead of stacking.
 */
describe('startProgrammerErrorBridge', () => {
  beforeEach(() => {
    errorSubscribers.length = 0
    vi.mocked(toast.error).mockClear()
  })

  it('toasts the server message verbatim', () => {
    startProgrammerErrorBridge()

    errorSubscribers.forEach((fn) => fn("Property 'uv' on 'hex-1' resolves to no DMX channels"))

    expect(toast.error).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledWith(
      "Property 'uv' on 'hex-1' resolves to no DMX channels",
      { id: PROGRAMMER_ERROR_TOAST_ID },
    )
  })

  it('reuses one toast id, so a drag burst collapses', () => {
    startProgrammerErrorBridge()

    for (let i = 0; i < 20; i++) {
      errorSubscribers.forEach((fn) => fn("Unknown fixture 'nope'"))
    }

    expect(toast.error).toHaveBeenCalledTimes(20)
    const ids = new Set(vi.mocked(toast.error).mock.calls.map((call) => call[1]?.id))
    expect(ids).toEqual(new Set([PROGRAMMER_ERROR_TOAST_ID]))
  })
})
