// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useSidebarOpen } from './useSidebarOpen'

/**
 * The sidebar's one preference (venue-visit plan D4), and how a desk's pre-D4 answers carry over.
 *
 * `Layout.test.tsx` drives the gesture — the toggle button, then a navigation between a live view
 * and the lists, then immersive on and off — against the real `Layout`. This pins the storage
 * half, where a regression is silent: the sidebar would still open and close, it would just come
 * up in the wrong shape after an upgrade.
 */
function draw() {
  return renderHook(() => useSidebarOpen())
}

afterEach(() => window.localStorage.clear())

describe('the sidebar preference', () => {
  it('starts open on a desk with nothing stored', () => {
    expect(draw().result.current.open).toBe(true)
  })

  it('remembers the toggle across a remount, under the one key', () => {
    const first = draw()
    act(() => first.result.current.toggle())
    expect(first.result.current.open).toBe(false)
    first.unmount()

    expect(window.localStorage.getItem('layout.sidebarOpen')).toBe('false')
    expect(draw().result.current.open).toBe(false)
  })
})

describe('a desk upgraded from the two-preference sidebar', () => {
  it('keeps a collapsed sidebar collapsed, from the non-live answer', () => {
    window.localStorage.setItem('layout.sidebarOpen.other', 'false')
    window.localStorage.setItem('layout.sidebarOpen.live', 'false')
    expect(draw().result.current.open).toBe(false)
  })

  it('keeps an open sidebar open, whatever the live views had — their default was the rail', () => {
    // Both keys were always written, chosen or not, so `live: false` is no evidence of a choice.
    window.localStorage.setItem('layout.sidebarOpen.other', 'true')
    window.localStorage.setItem('layout.sidebarOpen.live', 'false')
    expect(draw().result.current.open).toBe(true)
  })

  it('reads the old key once: the new key wins from then on', () => {
    window.localStorage.setItem('layout.sidebarOpen.other', 'false')
    const first = draw()
    expect(first.result.current.open).toBe(false)
    act(() => first.result.current.toggle())
    first.unmount()

    // The old key is untouched — a rolled-back build finds it — and no longer consulted.
    expect(window.localStorage.getItem('layout.sidebarOpen.other')).toBe('false')
    expect(draw().result.current.open).toBe(true)
  })

  it('falls back to open on an old value that is not a boolean', () => {
    window.localStorage.setItem('layout.sidebarOpen.other', '"wide"')
    expect(draw().result.current.open).toBe(true)
    window.localStorage.clear()
    window.localStorage.setItem('layout.sidebarOpen.other', 'not json')
    expect(draw().result.current.open).toBe(true)
  })
})
