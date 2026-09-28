// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LIVE_PUSH_MS, useLivePush } from './useLivePush'

/**
 * The tempo fader's write discipline, restated over any value: dedupe on the value, a floor
 * between sends, a deferred value sent when the floor lifts, and a release that bypasses both.
 * `BuskSpeedRail.test.tsx` pins the same behaviour through the card; this pins the hook.
 */

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

function harness<T>(equals?: (a: T, b: T) => boolean) {
  const send = vi.fn<(value: T) => void>()
  const hook = renderHook(() => useLivePush<T>(send, { equals }))
  return { send, ...hook }
}

describe('useLivePush', () => {
  it('sends the first value at once, and dedupes a repeat of it', () => {
    const { send, result } = harness<number>()
    act(() => result.current.push(120))
    expect(send).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS + 5))
    act(() => result.current.push(120))
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('floors a second value at the interval and sends the latest one when it lifts', () => {
    const { send, result } = harness<number>()
    act(() => result.current.push(120))
    act(() => result.current.push(125))
    act(() => result.current.push(130))
    expect(send).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS + 5))
    expect(send).toHaveBeenCalledTimes(2)
    expect(send).toHaveBeenLastCalledWith(130)
  })

  it('records the deferred value before the dedupe, so a move back onto the last value does not arm its predecessor', () => {
    const { send, result } = harness<number>()
    act(() => result.current.push(120))
    act(() => result.current.push(125))
    act(() => result.current.push(121))
    act(() => result.current.push(120))
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS + 5))
    // Nothing new to say: the finger is back where the last send left the rig.
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('flushes the release at once, past the floor and the armed timer, and dedupes it', () => {
    const { send, result } = harness<number>()
    act(() => result.current.push(120))
    act(() => result.current.push(140))
    act(() => result.current.flush(150))
    expect(send).toHaveBeenCalledTimes(2)
    expect(send).toHaveBeenLastCalledWith(150)
    // The armed timer was cleared with it: nothing lands later.
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS * 3))
    expect(send).toHaveBeenCalledTimes(2)
    act(() => result.current.flush(150))
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('forgets the last value on reset, so a fresh gesture arming on it still sends', () => {
    const { send, result } = harness<number>()
    act(() => result.current.push(120))
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS + 5))
    act(() => result.current.reset())
    act(() => result.current.push(120))
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('takes an equality for a value that is an object, and reads the latest send closure', () => {
    const send = vi.fn<(value: { r: number; g: number }) => void>()
    let target = 'par-1'
    const { result } = renderHook(() =>
      useLivePush<{ r: number; g: number }>((value) => send({ ...value, target } as never), {
        equals: (a, b) => a.r === b.r && a.g === b.g,
      }),
    )
    act(() => result.current.push({ r: 1, g: 2 }))
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS + 5))
    // A fresh object with the same bytes is the same write.
    act(() => result.current.push({ r: 1, g: 2 }))
    expect(send).toHaveBeenCalledTimes(1)
    target = 'par-2'
    act(() => result.current.flush({ r: 3, g: 2 }))
    expect(send).toHaveBeenLastCalledWith({ r: 3, g: 2, target: 'par-2' })
  })

  it('clears an armed timer on unmount', () => {
    const { send, result, unmount } = harness<number>()
    act(() => result.current.push(120))
    act(() => result.current.push(130))
    unmount()
    act(() => vi.advanceTimersByTime(LIVE_PUSH_MS * 3))
    expect(send).toHaveBeenCalledTimes(1)
  })
})
