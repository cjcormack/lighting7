// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useCueExpansion } from './useCueExpansion'

/**
 * The hook is shared by the merged Show view (one `?cue=` slot, live card only) and the Prompt
 * Book rail (a set of opened cards, live *and* next). Both arms are pinned here, because the
 * interesting cases are the ones where the two reasons a card can be open overlap — and those are
 * invisible from either caller's own tests.
 */

/** A caller whose operator slot is a set, as the Prompt Book rail's is. */
function drawMulti(live: number | null, next: number | null = null) {
  const open = new Set<number>()
  const hook = renderHook(
    (p: { liveCueId: number | null; nextCueId: number | null; resetKey: number | null }) =>
      useCueExpansion({
        openCueIds: open,
        onOpen: (id) => void open.add(id),
        onClose: (id) => void open.delete(id),
        ...p,
      }),
    { initialProps: { liveCueId: live, nextCueId: next, resetKey: live } },
  )
  return { hook, open }
}

describe('useCueExpansion', () => {
  it('opens the live cue and the next cue without either being addressed', () => {
    const { hook } = drawMulti(5, 6)
    expect(hook.result.current.isExpanded(5)).toBe(true)
    expect(hook.result.current.isExpanded(6)).toBe(true)
    expect(hook.result.current.isExpanded(7)).toBe(false)
  })

  it('leaves the next cue closed for a surface that does not ask for it', () => {
    const { hook } = drawMulti(5)
    expect(hook.result.current.isExpanded(5)).toBe(true)
    expect(hook.result.current.isExpanded(6)).toBe(false)
  })

  it('shuts a card open for both reasons in one press', () => {
    const { hook, open } = drawMulti(5)
    act(() => hook.result.current.toggleExpanded(5)) // dismiss the live card
    hook.rerender({ liveCueId: 5, nextCueId: null, resetKey: 5 })
    expect(hook.result.current.isExpanded(5)).toBe(false)

    act(() => hook.result.current.toggleExpanded(5)) // re-open it by hand
    hook.rerender({ liveCueId: 5, nextCueId: null, resetKey: 5 })
    expect(open.has(5)).toBe(true)
    expect(hook.result.current.isExpanded(5)).toBe(true)

    // Open for both reasons — one press must still shut it.
    act(() => hook.result.current.toggleExpanded(5))
    hook.rerender({ liveCueId: 5, nextCueId: null, resetKey: 5 })
    expect(hook.result.current.isExpanded(5)).toBe(false)
  })

  it('re-opens a dismissed card once the playhead has moved past it', () => {
    const { hook } = drawMulti(5, 6)
    act(() => hook.result.current.toggleExpanded(5))
    hook.rerender({ liveCueId: 5, nextCueId: 6, resetKey: 5 })
    expect(hook.result.current.isExpanded(5)).toBe(false)

    // GO: 6 is live, 7 on deck. 5 is nobody's card any more, and 6 was never dismissed.
    hook.rerender({ liveCueId: 6, nextCueId: 7, resetKey: 6 })
    expect(hook.result.current.isExpanded(5)).toBe(false)
    expect(hook.result.current.isExpanded(6)).toBe(true)
  })

  it('re-opens a dismissed next card when the GO that makes it live keeps its id', () => {
    // The one case self-clearing cannot catch: the id survives the transition, so only `resetKey`
    // moving clears the dismissal.
    const { hook } = drawMulti(5, 6)
    act(() => hook.result.current.toggleExpanded(6)) // dismiss the card on deck
    hook.rerender({ liveCueId: 5, nextCueId: 6, resetKey: 5 })
    expect(hook.result.current.isExpanded(6)).toBe(false)

    hook.rerender({ liveCueId: 6, nextCueId: 7, resetKey: 6 })
    expect(hook.result.current.isExpanded(6)).toBe(true)
  })
})
