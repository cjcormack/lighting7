// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render } from '@testing-library/react'
import type { OscState } from '@/api/oscWsApi'

const mocks = vi.hoisted(() => ({
  current: null as OscState | null,
  listeners: [] as ((state: OscState) => void)[],
  warning: vi.fn(),
}))

vi.mock('@/api/lightingApi', () => ({
  lightingApi: {
    osc: {
      current: () => mocks.current,
      subscribe: (fn: (state: OscState) => void) => {
        mocks.listeners.push(fn)
        return { unsubscribe: () => {} }
      },
    },
  },
}))
vi.mock('sonner', () => ({ toast: { warning: mocks.warning } }))

const { OscAddressAnnouncer } = await import('./OscAddressAnnouncer')

const frame = (ids: number[]): OscState => ({
  enabled: true,
  listening: true,
  port: 8415,
  received: 0,
  dropped: 0,
  refused: 0,
  refusedSenders: [],
  recent: [],
  warnings: ids.map((id) => ({ id, at: '', changes: [{ address: `/lighting7/a${id}`, was: 'GO Act 1', now: null }] })),
})

const emit = (state: OscState) => act(() => mocks.listeners.forEach((fn) => fn(state)))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.listeners = []
  mocks.current = null
})

describe('OscAddressAnnouncer', () => {
  it('toasts each new warning once, and none the connect snapshot carried', () => {
    render(<OscAddressAnnouncer />)
    emit(frame([1]))
    expect(mocks.warning).not.toHaveBeenCalled()
    emit(frame([2, 1]))
    expect(mocks.warning).toHaveBeenCalledTimes(1)
    expect(mocks.warning).toHaveBeenCalledWith('An OSC address moved', expect.objectContaining({ id: 'osc-address-warning-2', duration: 20_000 }))
    const description = render(<>{mocks.warning.mock.calls[0][1].description}</>)
    expect(description.getAllByRole('listitem').map((li) => li.textContent)).toEqual(['/lighting7/a2 no longer works (was GO Act 1)'])
    emit(frame([2, 1]))
    expect(mocks.warning).toHaveBeenCalledTimes(1)
  })

  it('takes a frame already held when it mounts as seen', () => {
    mocks.current = frame([4])
    render(<OscAddressAnnouncer />)
    emit(frame([5, 4]))
    expect(mocks.warning).toHaveBeenCalledTimes(1)
    expect(mocks.warning.mock.calls[0][1]).toMatchObject({ id: 'osc-address-warning-5' })
  })
})
