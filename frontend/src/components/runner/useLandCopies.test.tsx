// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { LAND_TIMEOUT_MS, useLandCopies } from './useLandCopies'

const land = vi.fn()

function setup(stackId: number, entries: number[]) {
  return renderHook(({ stackId, entries }) => useLandCopies(stackId, entries, land), { initialProps: { stackId, entries } })
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  land.mockReset()
})

describe('useLandCopies', () => {
  it('lands the copies once the stack lists every one, and only once', () => {
    const hook = setup(1, [101, 102])
    act(() => hook.result.current([103, 104]))
    hook.rerender({ stackId: 1, entries: [101, 102, 103] })
    expect(land).not.toHaveBeenCalled()
    hook.rerender({ stackId: 1, entries: [101, 102, 103, 104] })
    expect(land).toHaveBeenCalledExactlyOnceWith([103, 104])
    hook.rerender({ stackId: 1, entries: [101, 102, 103, 104, 105] })
    expect(land).toHaveBeenCalledOnce()
  })

  it('forgets copies the stack never lists in time', () => {
    const hook = setup(1, [101])
    act(() => hook.result.current([102]))
    act(() => vi.advanceTimersByTime(LAND_TIMEOUT_MS))
    hook.rerender({ stackId: 1, entries: [101, 102] })
    expect(land).not.toHaveBeenCalled()
  })

  it('forgets them on leaving the stack', () => {
    const hook = setup(1, [101])
    act(() => hook.result.current([102]))
    hook.rerender({ stackId: 2, entries: [201] })
    hook.rerender({ stackId: 1, entries: [101, 102] })
    expect(land).not.toHaveBeenCalled()
  })
})
