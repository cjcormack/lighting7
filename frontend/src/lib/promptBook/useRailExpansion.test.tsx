// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useRailExpansion } from './useRailExpansion'

/**
 * The rail's two answers to the questions `useCueExpansion` leaves open: the operator's slot is a
 * set, and manual opens are forgotten when the playhead moves. Both are this surface's, so neither
 * is visible from the shared hook's own tests.
 */
function draw(live: number | null, next: number | null) {
  return renderHook(
    ({ activeCueId, nextCueId }: { activeCueId: number | null; nextCueId: number | null }) =>
      useRailExpansion(activeCueId, nextCueId),
    { initialProps: { activeCueId: live, nextCueId: next } },
  )
}

describe('useRailExpansion', () => {
  it('opens the live card and the one on deck', () => {
    const { result } = draw(5, 6)
    expect(result.current.isExpanded(5)).toBe(true)
    expect(result.current.isExpanded(6)).toBe(true)
    expect(result.current.isExpanded(9)).toBe(false)
  })

  it('holds several operator-opened cards at once', () => {
    const { result } = draw(5, 6)
    act(() => result.current.toggleExpanded(20))
    act(() => result.current.toggleExpanded(21))
    expect(result.current.isExpanded(20)).toBe(true)
    expect(result.current.isExpanded(21)).toBe(true)
  })

  it('forgets manual opens when the playhead moves, and re-derives the new cards', () => {
    const { result, rerender } = draw(5, 6)
    act(() => result.current.toggleExpanded(20))
    expect(result.current.isExpanded(20)).toBe(true)

    rerender({ activeCueId: 6, nextCueId: 7 })
    expect(result.current.isExpanded(20)).toBe(false)
    expect(result.current.isExpanded(6)).toBe(true)
    expect(result.current.isExpanded(7)).toBe(true)
    expect(result.current.isExpanded(5)).toBe(false)
  })

  it('keeps a dismissed live card shut until the playhead leaves it', () => {
    const { result, rerender } = draw(5, 6)
    act(() => result.current.toggleExpanded(5))
    expect(result.current.isExpanded(5)).toBe(false)

    // A new standby is not the playhead leaving cue 5.
    rerender({ activeCueId: 5, nextCueId: 8 })
    expect(result.current.isExpanded(5)).toBe(false)

    rerender({ activeCueId: 8, nextCueId: 9 })
    expect(result.current.isExpanded(5)).toBe(false)
    expect(result.current.isExpanded(8)).toBe(true)
  })
})
