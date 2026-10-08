import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FxError } from '@/api/fxApi'

const errorSubscribers = vi.hoisted(() => [] as ((error: FxError) => void)[])

// A capturing stub for the one subscription under test; every other namespace answers inertly, so
// the slice (and the store it reaches) can load.
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const fx = {
    subscribe: () => ({ unsubscribe: () => {} }),
    subscribeToErrors: (fn: (error: FxError) => void) => {
      errorSubscribers.push(fn)
      return { unsubscribe: () => {} }
    },
  }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'fx' ? fx : target[prop]) }) }
})

vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))

import { toast } from 'sonner'
import { fxErrorToastId } from './fixtureFx'

/**
 * `fxError` — the live editor's refusals (fixture-fx-sheets plan W3) — reaches the operator as a
 * toast keyed **per effect**: a drag refused on every frame replaces one toast, and two effects
 * refused together each keep theirs.
 */
describe('the fxError bridge', () => {
  beforeEach(() => vi.mocked(toast.error).mockClear())

  it('is subscribed when the slice loads, and toasts the desk’s message keyed by the effect', () => {
    expect(errorSubscribers).toHaveLength(1)
    errorSubscribers[0]({ effectId: 21, code: 'FX_UPDATE_REFUSED', message: "Unknown blendMode 'SIDEWAYS'" })
    expect(toast.error).toHaveBeenCalledWith("Unknown blendMode 'SIDEWAYS'", { id: fxErrorToastId(21) })
  })

  it('collapses a burst on one effect and keeps two effects apart', () => {
    for (let i = 0; i < 10; i++) errorSubscribers[0]({ effectId: 21, code: 'FX_UPDATE_REFUSED', message: 'refused' })
    errorSubscribers[0]({ effectId: 22, code: 'FX_NOT_FOUND', message: 'Effect 22 is not running' })
    const ids = vi.mocked(toast.error).mock.calls.map((call) => (call[1] as { id: string }).id)
    expect(new Set(ids)).toEqual(new Set([fxErrorToastId(21), fxErrorToastId(22)]))
    expect(fxErrorToastId(21)).not.toBe(fxErrorToastId(22))
  })
})
