// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  BUSK_LOCAL_PAGE_KEY,
  BUSK_PAGE_FOLLOW_KEY,
  getLocalBuskPage,
  isBuskPageDecided,
  isFollowingBuskPage,
  keepFollowingBuskPage,
  relinkBuskPage,
  resetBuskPageFollowStores,
  setLocalBuskPage,
  unlinkBuskPage,
  useBuskPageDecided,
  useBuskPageFollow,
  useLocalBuskPage,
} from './buskPageFollow'
import { DESK_FOLLOW_KEY, resetDeskFollowStores, unlinkFromDesk, isFollowingDesk } from './deskFollow'

/**
 * The showing busk page's follow/local flag: `lib/deskFollow.ts`'s shape, a second and independent
 * fact, tri-state so that `?page=` can mean *this window's page* exactly once.
 */

afterEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetBuskPageFollowStores()
  resetDeskFollowStores()
})

describe('useBuskPageFollow', () => {
  it('follows the desk by default, and says so is not yet decided', () => {
    expect(renderHook(() => useBuskPageFollow()).result.current).toBe(true)
    expect(isFollowingBuskPage()).toBe(true)
    expect(isBuskPageDecided()).toBe(false)
  })

  it('lives in sessionStorage — one value per tab — and never in localStorage', () => {
    // `localStorage` is one value per origin per profile, and the two desk screens are two windows
    // of one profile: a flag kept there would be one flag for both.
    const { result } = renderHook(() => useBuskPageFollow())
    act(() => unlinkBuskPage(7))
    expect(result.current).toBe(false)
    expect(window.sessionStorage.getItem(BUSK_PAGE_FOLLOW_KEY)).toBe('false')
    expect(window.sessionStorage.getItem(BUSK_LOCAL_PAGE_KEY)).toBe('7')
    expect(window.localStorage.getItem(BUSK_PAGE_FOLLOW_KEY)).toBeNull()
    expect(window.localStorage.getItem(BUSK_LOCAL_PAGE_KEY)).toBeNull()
  })

  it('reads a tab that was unlinked before a reload as still unlinked, on its own page', () => {
    window.sessionStorage.setItem(BUSK_PAGE_FOLLOW_KEY, 'false')
    window.sessionStorage.setItem(BUSK_LOCAL_PAGE_KEY, '9')
    expect(renderHook(() => useBuskPageFollow()).result.current).toBe(false)
    expect(renderHook(() => useLocalBuskPage()).result.current).toBe(9)
    expect(isBuskPageDecided()).toBe(true)
  })

  it('reads junk in storage as undecided, with no page', () => {
    window.sessionStorage.setItem(BUSK_PAGE_FOLLOW_KEY, '"maybe"')
    window.sessionStorage.setItem(BUSK_LOCAL_PAGE_KEY, '"3"')
    expect(renderHook(() => useBuskPageFollow()).result.current).toBe(true)
    expect(isBuskPageDecided()).toBe(false)
    expect(getLocalBuskPage()).toBeNull()
  })

  it('moves every reader together', () => {
    const a = renderHook(() => useBuskPageFollow())
    const b = renderHook(() => useBuskPageFollow())
    act(() => unlinkBuskPage(4))
    expect(a.result.current).toBe(false)
    expect(b.result.current).toBe(false)
  })
})

describe('the store caches its reads', () => {
  it('reads storage once even while the value is null — the first nullable-fallback store', () => {
    // `createSyncStore` used `current ??= readStored()`, which treats a legitimate `null` as "not
    // read yet". Both stores here rest at `null` — the follow flag until the window decides, the
    // local page for the whole life of a window that never unlinks — so an uncached read would be
    // a `sessionStorage.getItem` + `JSON.parse` on every render of the busk view, forever.
    const getItem = vi.spyOn(window.sessionStorage, 'getItem')
    expect(isFollowingBuskPage()).toBe(true)
    expect(getLocalBuskPage()).toBeNull()
    const afterFirst = getItem.mock.calls.length
    for (let i = 0; i < 5; i += 1) {
      isFollowingBuskPage()
      getLocalBuskPage()
    }
    expect(getItem.mock.calls.length).toBe(afterFirst)
    getItem.mockRestore()
  })
})

describe('unlink and re-link', () => {
  it('snapshots the page this window is showing on unlink, and leaves nothing for the desk', () => {
    unlinkBuskPage(5)
    expect(getLocalBuskPage()).toBe(5)
    expect(isFollowingBuskPage()).toBe(false)
  })

  it('unlinks with no page when the window is showing none, rather than writing null over one', () => {
    setLocalBuskPage(5)
    unlinkBuskPage(null)
    expect(getLocalBuskPage()).toBe(5)
    expect(isFollowingBuskPage()).toBe(false)
  })

  it('drops the copy on re-link, so the desk’s page is adopted rather than overwritten', () => {
    const { result } = renderHook(() => useLocalBuskPage())
    act(() => unlinkBuskPage(5))
    expect(result.current).toBe(5)
    act(() => relinkBuskPage())
    expect(isFollowingBuskPage()).toBe(true)
    expect(result.current).toBeNull()
    // Re-linking is a decision too — a reload must not read its own mirrored `?page=` back as one.
    expect(isBuskPageDecided()).toBe(true)
  })

  it('records a follow without changing what is shown', () => {
    setLocalBuskPage(5)
    keepFollowingBuskPage()
    expect(isFollowingBuskPage()).toBe(true)
    expect(isBuskPageDecided()).toBe(true)
    expect(getLocalBuskPage()).toBe(5)
  })
})

/**
 * The regression this whole feature turns on: two facts, two flags. Folding them into one would
 * make the flow it exists for — a colour page on one screen, a position page on the other, pressed
 * onto one selection — unreachable.
 */
/**
 * The rendered tri-state, which the `?page=` mirror effect gates on. It has to move on the
 * keep-following arm too, where the *collapsed* boolean does not change at all.
 */
describe('useBuskPageDecided', () => {
  it('is false while undecided and true once either arm has run', () => {
    const decided = renderHook(() => useBuskPageDecided())
    const following = renderHook(() => useBuskPageFollow())
    expect(decided.result.current).toBe(false)
    expect(following.result.current).toBe(true)
    act(() => keepFollowingBuskPage())
    // The collapsed boolean is `true` on both sides of this, which is exactly why the mirror
    // effect cannot be gated on it.
    expect(following.result.current).toBe(true)
    expect(decided.result.current).toBe(true)
  })

  it('is true after an unlink as well', () => {
    const decided = renderHook(() => useBuskPageDecided())
    act(() => unlinkBuskPage(3))
    expect(decided.result.current).toBe(true)
  })
})

describe('the two flags are independent', () => {
  it('unlinking the page leaves the selection following', () => {
    unlinkBuskPage(5)
    expect(isFollowingBuskPage()).toBe(false)
    expect(isFollowingDesk()).toBe(true)
    expect(window.sessionStorage.getItem(DESK_FOLLOW_KEY)).toBeNull()
  })

  it('unlinking the selection leaves the page following', () => {
    unlinkFromDesk({ targets: [{ type: 'fixture', key: 'par-1' }], families: null })
    expect(isFollowingDesk()).toBe(false)
    expect(isFollowingBuskPage()).toBe(true)
    expect(window.sessionStorage.getItem(BUSK_PAGE_FOLLOW_KEY)).toBeNull()
  })
})
