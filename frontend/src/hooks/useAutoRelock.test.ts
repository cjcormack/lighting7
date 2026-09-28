// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAutoRelock } from './useAutoRelock'

/**
 * The mid-show safety net for "unlocked to fix one thing and forgot". It had no test at all until
 * session 2b lifted it out of the Prompt Book to be shared with Show — and it is exactly the kind
 * of thing that must not silently stop working, because nothing on screen reports its absence.
 */

const IDLE_MS = 120_000
const COUNTDOWN_MS = 10_000

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

function draw(locked = false) {
  const onRelock = vi.fn()
  const view = renderHook(({ l }: { l: boolean }) => useAutoRelock({ locked: l, onRelock }), {
    initialProps: { l: locked },
  })
  return { ...view, onRelock }
}

describe('useAutoRelock', () => {
  it('shows no countdown until the idle limit is near', () => {
    const { result } = draw()
    expect(result.current.countdownSecondsLeft).toBeNull()

    act(() => vi.advanceTimersByTime(IDLE_MS - COUNTDOWN_MS - 1000))
    expect(result.current.countdownSecondsLeft).toBeNull()
  })

  it('counts down visibly before re-locking', () => {
    // The countdown is the whole point: re-locking silently mid-edit would look like the desk
    // eating the operator's keystrokes.
    const { result, onRelock } = draw()

    act(() => vi.advanceTimersByTime(IDLE_MS - COUNTDOWN_MS))
    expect(result.current.countdownSecondsLeft).toBe(COUNTDOWN_MS / 1000)
    expect(onRelock).not.toHaveBeenCalled()

    act(() => vi.advanceTimersByTime(COUNTDOWN_MS))
    expect(onRelock).toHaveBeenCalledTimes(1)
    expect(result.current.countdownSecondsLeft).toBeNull()
  })

  it('an edit puts the full idle window back', () => {
    const { result, onRelock } = draw()

    act(() => vi.advanceTimersByTime(IDLE_MS - COUNTDOWN_MS - 1000))
    act(() => result.current.noteEdit())
    act(() => vi.advanceTimersByTime(IDLE_MS - COUNTDOWN_MS - 1000))

    expect(result.current.countdownSecondsLeft).toBeNull()
    expect(onRelock).not.toHaveBeenCalled()
  })

  it('"stay unlocked" cancels a running countdown', () => {
    // Deliberately the same operation as an edit — exposed under both names so call sites read
    // naturally.
    const { result, onRelock } = draw()

    act(() => vi.advanceTimersByTime(IDLE_MS - COUNTDOWN_MS + 2000))
    expect(result.current.countdownSecondsLeft).not.toBeNull()

    act(() => result.current.stayUnlocked())
    expect(result.current.countdownSecondsLeft).toBeNull()

    act(() => vi.advanceTimersByTime(COUNTDOWN_MS))
    expect(onRelock).not.toHaveBeenCalled()
  })

  it('GO re-locks at once, without waiting out the idle window', () => {
    // Running the show again is the natural end of a fix-it edit.
    const { result, onRelock } = draw()
    act(() => result.current.noteGo())
    expect(onRelock).toHaveBeenCalledTimes(1)
  })

  it('does nothing at all while already locked', () => {
    const { result, onRelock } = draw(true)

    act(() => vi.advanceTimersByTime(IDLE_MS * 2))
    expect(onRelock).not.toHaveBeenCalled()
    expect(result.current.countdownSecondsLeft).toBeNull()

    act(() => result.current.noteGo())
    expect(onRelock).not.toHaveBeenCalled()
  })

  it('disarms when it becomes locked mid-countdown', () => {
    const { result, rerender, onRelock } = draw()
    act(() => vi.advanceTimersByTime(IDLE_MS - COUNTDOWN_MS + 2000))
    expect(result.current.countdownSecondsLeft).not.toBeNull()

    rerender({ l: true })
    expect(result.current.countdownSecondsLeft).toBeNull()

    act(() => vi.advanceTimersByTime(IDLE_MS * 2))
    expect(onRelock).not.toHaveBeenCalled()
  })

  it('stops its timers on unmount', () => {
    // A surviving interval would fire `onRelock` into a torn-down tree.
    const { unmount, onRelock } = draw()
    unmount()
    act(() => vi.advanceTimersByTime(IDLE_MS * 2))
    expect(onRelock).not.toHaveBeenCalled()
  })
})
