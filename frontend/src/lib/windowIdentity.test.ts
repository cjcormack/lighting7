// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  WINDOW_ID_KEY,
  WINDOW_NAME_KEY,
  renameWindow,
  resetWindowIdentity,
  subscribeWindowName,
  useWindowName,
  windowId,
  windowName,
} from './windowIdentity'

/**
 * This tab's identity (multi-screen plan D9, D10, and session 2.5): a `windowId` in
 * `sessionStorage`, a name from `?window=` read once and stripped, else `Window xxxx`; both survive
 * a reload, neither is shared with another tab; and a rename moves every reader.
 *
 * The session-2.5 pair, which is the whole point of the `windowId` block: a boot carrying
 * `?window=` **mints a fresh id** even over storage a cloned context inherited, and a boot with no
 * parameter — which is every reload, since the parameter is stripped at the first — **keeps** it.
 */

beforeEach(() => {
  window.history.replaceState(null, '', '/projects/1/programmer')
})

afterEach(() => {
  window.sessionStorage.clear()
  resetWindowIdentity()
})

describe('windowId', () => {
  it('mints a uuid once and keeps it for the tab', () => {
    const id = windowId()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    expect(windowId()).toBe(id)
    expect(window.sessionStorage.getItem(WINDOW_ID_KEY)).toBe(id)
  })

  it('reads the stored id after a reload rather than minting a new one', () => {
    window.sessionStorage.setItem(WINDOW_ID_KEY, 'aaaaaaaa-0000-4000-8000-000000000001')
    expect(windowId()).toBe('aaaaaaaa-0000-4000-8000-000000000001')
  })

  it('differs between two tabs — storage is per tab, so a fresh tab mints a fresh id', () => {
    const first = windowId()
    // A second tab: no storage, no cache.
    window.sessionStorage.clear()
    resetWindowIdentity()
    expect(windowId()).not.toBe(first)
  })

  it('mints a fresh id on a `?window=` boot, over an inherited one — the cloned-storage case', () => {
    // A `window.open`'d child clones its parent's sessionStorage, so this is the parent's id.
    window.sessionStorage.setItem(WINDOW_ID_KEY, 'aaaaaaaa-0000-4000-8000-000000000002')
    window.history.replaceState(null, '', '/?window=Screen%202')
    const minted = windowId()
    expect(minted).not.toBe('aaaaaaaa-0000-4000-8000-000000000002')
    expect(window.sessionStorage.getItem(WINDOW_ID_KEY)).toBe(minted)
    // The name is the launch parameter's business and is unaffected by the minting.
    expect(windowName()).toBe('Screen 2')
  })

  it('mints on the parameter whichever of the two is asked first — the id may be asked before the name', () => {
    window.sessionStorage.setItem(WINDOW_ID_KEY, 'aaaaaaaa-0000-4000-8000-000000000003')
    window.history.replaceState(null, '', '/?window=Screen%202')
    // `main.tsx` calls windowName() first; nothing guarantees a test or a future caller does.
    expect(windowId()).not.toBe('aaaaaaaa-0000-4000-8000-000000000003')
    // …and the name the id's read consumed is still the one the parameter carried.
    expect(windowName()).toBe('Screen 2')
    expect(window.location.search).toBe('')
  })

  it('mints on a blank `?window=` too — a shared identity is the worse failure', () => {
    window.sessionStorage.setItem(WINDOW_ID_KEY, 'aaaaaaaa-0000-4000-8000-000000000004')
    window.history.replaceState(null, '', '/?window=%20')
    expect(windowId()).not.toBe('aaaaaaaa-0000-4000-8000-000000000004')
  })

  it('is the true clone shape: an inherited id AND an inherited name, with `?window=` on the URL', () => {
    // What a cloned context actually wakes up holding — both keys, copied from its opener. Pinned
    // because the two halves deliberately answer differently and only prose said so: the id is the
    // misattribution vector and is re-minted, the name is cosmetic and the stored one still wins,
    // so this child lists as a second *Screen 1* rather than the *Screen 2* that was asked for.
    window.sessionStorage.setItem(WINDOW_ID_KEY, 'aaaaaaaa-0000-4000-8000-000000000005')
    window.sessionStorage.setItem(WINDOW_NAME_KEY, 'Screen 1')
    window.history.replaceState(null, '', '/?window=Screen%202')

    expect(windowId()).not.toBe('aaaaaaaa-0000-4000-8000-000000000005')
    expect(windowName()).toBe('Screen 1')
    expect(window.sessionStorage.getItem(WINDOW_ID_KEY)).toBe(windowId())
  })

  it('keeps the id minted at a `?window=` boot across the reload that follows it', () => {
    // Boot one: the parameter is present, so a fresh id is minted and the parameter stripped.
    window.history.replaceState(null, '', '/projects/1/busk?window=Screen%202')
    const minted = windowId()
    expect(window.location.search).toBe('')

    // Boot two is a reload of that stripped URL: no parameter, so the stored id is kept. Minting
    // here would churn a registry row on every refresh.
    resetWindowIdentity()
    expect(windowId()).toBe(minted)
  })
})

describe('windowName', () => {
  it('mints `Window` plus a short suffix for a tab opened by hand, once', () => {
    const name = windowName()
    expect(name).toMatch(/^Window [0-9a-z]{4}$/)
    expect(windowName()).toBe(name)
    expect(window.sessionStorage.getItem(WINDOW_NAME_KEY)).toBe(name)
  })

  it('takes the name from `?window=`, strips it from the URL, and keeps the rest of the query', () => {
    window.history.replaceState(null, '', '/projects/1/busk?page=4&window=Screen%202')
    expect(windowName()).toBe('Screen 2')
    expect(window.location.search).toBe('?page=4')
    expect(window.sessionStorage.getItem(WINDOW_NAME_KEY)).toBe('Screen 2')
  })

  it('reads the stored name after a reload rather than minting a new one', () => {
    window.sessionStorage.setItem(WINDOW_NAME_KEY, 'Screen 1')
    expect(windowName()).toBe('Screen 1')
  })

  it('prefers the stored name to a stale `?window=` on a reload of the stripped URL', () => {
    // The parameter was consumed at first boot; a bookmark of the launch URL re-opened in the same
    // tab must not rename it.
    window.sessionStorage.setItem(WINDOW_NAME_KEY, 'Screen 1')
    window.history.replaceState(null, '', '/?window=Screen%202')
    expect(windowName()).toBe('Screen 1')
    // …and the parameter is still consumed, or the URL would carry it for the life of the tab.
    expect(window.location.search).toBe('')
  })

  it('ignores a blank `?window=`', () => {
    window.history.replaceState(null, '', '/?window=%20')
    expect(windowName()).toMatch(/^Window /)
    expect(window.location.search).toBe('')
  })
})

describe('renameWindow', () => {
  it('stores the new name, tells every subscriber, and reports whether anything changed', () => {
    windowName()
    const listener = vi.fn()
    subscribeWindowName(listener)

    expect(renameWindow('  Screen 2  ')).toBe(true)
    expect(windowName()).toBe('Screen 2')
    expect(window.sessionStorage.getItem(WINDOW_NAME_KEY)).toBe('Screen 2')
    expect(listener).toHaveBeenCalledTimes(1)

    // A no-op rename and a blank one change nothing and wake nobody.
    expect(renameWindow('Screen 2')).toBe(false)
    expect(renameWindow('   ')).toBe(false)
    expect(windowName()).toBe('Screen 2')
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('survives a reload — the renamed name is what storage holds', () => {
    windowName()
    renameWindow('Chris’s iPad')
    resetWindowIdentity()
    expect(windowName()).toBe('Chris’s iPad')
  })

  it('re-renders a React reader', () => {
    const { result } = renderHook(() => useWindowName())
    const before = result.current
    act(() => {
      renameWindow('Screen 3')
    })
    expect(before).not.toBe('Screen 3')
    expect(result.current).toBe('Screen 3')
  })
})
