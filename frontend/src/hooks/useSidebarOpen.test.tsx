// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { useSidebarOpen } from './useSidebarOpen'

/**
 * The sidebar default per route group, and — the part that is silent when it breaks — **which of
 * the two preferences the toggle writes**.
 *
 * `lib/liveViews.test.ts` pins which paths count as live views. This pins the wiring around that
 * answer, where a regression shows up as nothing at all: the sidebar would still open and close, it
 * would just forget on the next navigation, or forget on the wrong half of the app.
 *
 * The hook rather than `Layout` itself. `Layout` mounts the whole desk — four overview panels, the
 * DnD provider, the command palette, the AI panel's lazy boundary — so a render test of it would
 * exercise forty unrelated things to assert one boolean.
 */
function draw(path: string) {
  return renderHook(() => useSidebarOpen(), {
    wrapper: ({ children }) => <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>,
  })
}

afterEach(() => window.localStorage.clear())

describe('the sidebar default per route group', () => {
  it('starts collapsed on a live view and open everywhere else', () => {
    expect(draw('/projects/6/programmer').result.current.open).toBe(false)
    expect(draw('/projects/6/busk').result.current.open).toBe(false)
    expect(draw('/projects/6/fixtures').result.current.open).toBe(true)
    expect(draw('/install/diagnostics').result.current.open).toBe(true)
  })

  it('writes the live preference from a live view, leaving the other alone', () => {
    const live = draw('/projects/6/programmer')
    act(() => live.result.current.toggle())
    expect(live.result.current.open).toBe(true)

    // The whole point of two keys: opening it on the Programmer must not close it on the lists.
    expect(draw('/projects/6/fixtures').result.current.open).toBe(true)
    // …and it must reach the other three live views, which share the one preference.
    expect(draw('/projects/6/show').result.current.open).toBe(true)
  })

  it('writes the other preference from a non-live view, leaving the live one alone', () => {
    const other = draw('/projects/6/fixtures')
    act(() => other.result.current.toggle())
    expect(other.result.current.open).toBe(false)

    expect(draw('/projects/6/programmer').result.current.open).toBe(false)
    expect(draw('/projects/6/templates').result.current.open).toBe(false)
  })

  it('remembers each answer across a remount', () => {
    // The preference is what survives a reload; a per-mount default would silently undo every
    // toggle the operator made.
    const first = draw('/projects/6/show')
    act(() => first.result.current.toggle())
    first.unmount()
    expect(draw('/projects/6/show').result.current.open).toBe(true)
  })
})
