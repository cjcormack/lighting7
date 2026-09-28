// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  IMMERSIVE_KEY,
  applyImmersiveLaunch,
  applyImmersiveViewOption,
  immersiveValue,
  isImmersive,
  resetImmersiveStore,
  setImmersive,
  toggleImmersive,
  useImmersive,
} from './immersive'
import { WINDOW_ID_KEY, resetWindowIdentity, windowId, windowName } from './windowIdentity'

/**
 * `desk.immersive` (busk-chrome plan D7–D9): a per-tab fact in `sessionStorage`, off by default,
 * set once at boot from `?immersive=` through the same consumed read as `?window=` and stripped
 * with it, and kept across the reload that follows — which is what the strip buys.
 */

/** A reload: every memoised value forgotten, storage and the URL as they stand. */
function reload() {
  resetImmersiveStore()
  resetWindowIdentity()
}

beforeEach(() => {
  window.history.replaceState(null, '', '/projects/1/busk')
})

afterEach(() => {
  window.sessionStorage.clear()
  reload()
})

describe('the fact', () => {
  it('defaults to off, on every surface', () => {
    expect(isImmersive()).toBe(false)
    expect(immersiveValue()).toBe('off')
    expect(window.sessionStorage.getItem(IMMERSIVE_KEY)).toBeNull()
  })

  it('is set and toggled, written to sessionStorage and never localStorage', () => {
    setImmersive(true)
    expect(isImmersive()).toBe(true)
    expect(immersiveValue()).toBe('on')
    expect(JSON.parse(window.sessionStorage.getItem(IMMERSIVE_KEY)!)).toBe('on')
    expect(window.localStorage.getItem(IMMERSIVE_KEY)).toBeNull()
    toggleImmersive()
    expect(isImmersive()).toBe(false)
    expect(JSON.parse(window.sessionStorage.getItem(IMMERSIVE_KEY)!)).toBe('off')
  })

  it('re-renders a hook reader when it flips', () => {
    const { result } = renderHook(() => useImmersive())
    expect(result.current).toBe(false)
    act(() => setImmersive(true))
    expect(result.current).toBe(true)
  })

  it('reads junk in storage as off rather than throwing', () => {
    window.sessionStorage.setItem(IMMERSIVE_KEY, '"sideways"')
    expect(isImmersive()).toBe(false)
    window.sessionStorage.setItem(IMMERSIVE_KEY, 'not json')
    reload()
    expect(isImmersive()).toBe(false)
  })
})

describe('the boot read', () => {
  it('takes `?immersive=on`, strips it from the URL, and keeps the rest of the query', () => {
    window.history.replaceState(null, '', '/projects/1/busk?page=4&immersive=on')
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(true)
    expect(window.location.search).toBe('?page=4')
  })

  it('takes `off` too, and ignores any other value rather than reading it as off', () => {
    setImmersive(true)
    window.history.replaceState(null, '', '/projects/1/busk?immersive=sideways')
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(true)
    expect(window.location.search).toBe('')

    reload()
    window.history.replaceState(null, '', '/projects/1/busk?immersive=off')
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(false)
  })

  it('rides the same consumed read as `?window=`: both are stripped in one pass, whichever asks first', () => {
    window.history.replaceState(null, '', '/projects/1/busk?window=Screen%202&immersive=on')
    // `main.tsx` calls windowName() first, then applyImmersiveLaunch(); a reversed order must
    // answer the same, since either read rewrites the URL for both.
    expect(windowName()).toBe('Screen 2')
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(true)
    expect(window.location.search).toBe('')

    window.sessionStorage.clear()
    reload()
    window.history.replaceState(null, '', '/projects/1/busk?window=Screen%203&immersive=on')
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(true)
    expect(windowName()).toBe('Screen 3')
  })

  it('is not a `?window=` boot: a plain `?immersive=on` arrival keeps the stored windowId', () => {
    window.sessionStorage.setItem(WINDOW_ID_KEY, 'aaaaaaaa-0000-4000-8000-000000000001')
    window.history.replaceState(null, '', '/projects/1/busk?immersive=on')
    applyImmersiveLaunch()
    expect(windowId()).toBe('aaaaaaaa-0000-4000-8000-000000000001')
  })

  it('keeps the fact across the reload that follows — the strip is what makes it one-time', () => {
    window.history.replaceState(null, '', '/projects/1/busk?immersive=on')
    applyImmersiveLaunch()
    expect(window.location.search).toBe('')

    // A reload of the stripped URL: no parameter, storage still says on.
    reload()
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(true)

    // And the operator turning it off is not undone by a later reload either.
    setImmersive(false)
    reload()
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(false)
  })

  it('does nothing with no parameter, leaving whatever the tab holds', () => {
    setImmersive(true)
    applyImmersiveLaunch()
    expect(isImmersive()).toBe(true)
  })
})

describe('a windows.viewOptions frame', () => {
  it('applies `immersive` on and off, ignores anything else, and reports what it changed', () => {
    expect(applyImmersiveViewOption({ immersive: 'on' })).toBe('on')
    expect(isImmersive()).toBe(true)
    expect(applyImmersiveViewOption({ focus: 'pads' })).toBeUndefined()
    expect(isImmersive()).toBe(true)
    expect(applyImmersiveViewOption({ immersive: 'toggle' })).toBeUndefined()
    expect(isImmersive()).toBe(true)
    expect(applyImmersiveViewOption({ immersive: 'off' })).toBe('off')
    expect(isImmersive()).toBe(false)
  })
})
