// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  BUSK_FOCUS_KEY,
  BUSK_SHEET_KEY,
  BUSK_WINDOW_DECIDED_KEY,
  applyBuskArrival,
  applyBuskViewOptions,
  defaultBuskFocus,
  defaultBuskRigRows,
  defaultBuskSheet,
  getBuskFocus,
  getBuskSheet,
  isBuskWindowDecided,
  resetBuskWindowStores,
  setBuskFocus,
  setBuskRigHeight,
  resetBuskRigHeight,
  getBuskRigHeight,
  setBuskSheet,
  toggleBuskSheet,
  isLiveSheetTab,
  LIVE_SHEET_TABS,
  useBuskFocus,
  useBuskRigHeight,
  useBuskViewOptions,
  useBuskSheet,
  useBuskWindowDecided,
} from './buskWindow'
import {
  getLocalBuskPage,
  isFollowingBuskPage,
  reportShowingBuskPage,
  resetBuskPageFollowStores,
  unlinkBuskPage,
} from './buskPageFollow'

/**
 * The busk view's per-window facts (busk-further plan §3.4, D5–D7): defaults from the surface until
 * the window chooses; `?focus=` / `?sheet=` latched once per tab; `rigHeight` a wish the band clamps; the
 * sheet as one fact whose fold is `none`.
 */

/** Stub `matchMedia` so each of the three queries answers as the named surface would. */
function surface({ short = false, cramped = false, wide = true } = {}) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('500px') ? short : query.includes('750px') ? cramped : wide,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
}

beforeEach(() => surface())

afterEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetBuskWindowStores()
  resetBuskPageFollowStores()
  vi.unstubAllGlobals()
})

describe('the defaults ladder', () => {
  it('is Split · Speed · 3 rows on a desk screen', () => {
    const desk = { short: false, cramped: false, docks: true }
    expect(defaultBuskFocus(desk)).toBe('split')
    expect(defaultBuskSheet(desk)).toBe('speed')
    expect(defaultBuskRigRows(desk)).toBe(3)
  })

  it('folds the rail and shows 2 rows on an iPad in portrait, where docking would stack the page', () => {
    const portrait = { short: false, cramped: false, docks: false }
    expect(defaultBuskFocus(portrait)).toBe('split')
    expect(defaultBuskSheet(portrait)).toBe('none')
    expect(defaultBuskRigRows(portrait)).toBe(2)
  })

  it('keeps the rail open with 2 rows on a 1024×768 iPad in landscape, which is wide but cramped', () => {
    const landscape = { short: false, cramped: true, docks: true }
    expect(defaultBuskSheet(landscape)).toBe('speed')
    expect(defaultBuskRigRows(landscape)).toBe(2)
  })

  it('gives an 1180×820 iPad in landscape three rows — 820 clears the 750px cramped fold, which the Tablets ladder’s “2 rows” never anticipated', () => {
    const tall = { short: false, cramped: false, docks: true }
    expect(defaultBuskFocus(tall)).toBe('split')
    expect(defaultBuskSheet(tall)).toBe('speed')
    expect(defaultBuskRigRows(tall)).toBe(3)
  })

  it('is Pads with the sheet folded on a short viewport — a landscape phone', () => {
    const phone = { short: true, cramped: true, docks: false }
    expect(defaultBuskFocus(phone)).toBe('pads')
    expect(defaultBuskSheet(phone)).toBe('none')
  })

  it('reads the surface through matchMedia, and the tab fact wins once chosen', () => {
    surface({ short: true, cramped: true, wide: false })
    expect(renderHook(() => useBuskFocus()).result.current).toBe('pads')
    expect(renderHook(() => useBuskSheet()).result.current).toBe('none')
    expect(getBuskFocus()).toBe('pads')
    act(() => setBuskFocus('rig'))
    expect(renderHook(() => useBuskFocus()).result.current).toBe('rig')
    expect(getBuskFocus()).toBe('rig')
  })

  it('answers the desk default where matchMedia is missing, rather than throwing', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(renderHook(() => useBuskFocus()).result.current).toBe('split')
    // No `matchMedia` means no `docks` answer either, so the sheet rests folded.
    expect(renderHook(() => useBuskSheet()).result.current).toBe('none')
  })
})

describe('per-tab storage', () => {
  it('lives in sessionStorage — one value per tab — and never in localStorage', () => {
    act(() => {
      setBuskFocus('pads')
      setBuskSheet('none')
    })
    expect(window.sessionStorage.getItem(BUSK_FOCUS_KEY)).toBe('"pads"')
    expect(window.sessionStorage.getItem(BUSK_SHEET_KEY)).toBe('"none"')
    expect(window.localStorage.getItem(BUSK_FOCUS_KEY)).toBeNull()
  })

  it('reads a reloaded tab back into the shape it had, and junk as the default', () => {
    window.sessionStorage.setItem(BUSK_FOCUS_KEY, '"rig"')
    window.sessionStorage.setItem(BUSK_SHEET_KEY, '"nope"')
    expect(renderHook(() => useBuskFocus()).result.current).toBe('rig')
    expect(renderHook(() => useBuskSheet()).result.current).toBe('speed')
  })

  it('moves every reader together', () => {
    const a = renderHook(() => useBuskSheet())
    const b = renderHook(() => useBuskSheet())
    act(() => setBuskSheet('none'))
    expect(a.result.current).toBe('none')
    expect(b.result.current).toBe('none')
  })
})

describe('rigHeight', () => {
  it('is the wish, unclamped — null until the window chooses, whole pixels once it has', () => {
    expect(renderHook(() => useBuskRigHeight()).result.current).toBeNull()
    act(() => setBuskRigHeight(233.4))
    expect(renderHook(() => useBuskRigHeight()).result.current).toBe(233)
    expect(getBuskRigHeight()).toBe(233)
    // The band clamps on read against the body it can measure; the store never does, so a window
    // that shrinks and grows back shows what it asked for.
    act(() => setBuskRigHeight(5000))
    expect(getBuskRigHeight()).toBe(5000)
  })

  it('never stores zero, and a reset is back to the default', () => {
    act(() => setBuskRigHeight(0))
    expect(getBuskRigHeight()).toBe(1)
    act(() => resetBuskRigHeight())
    expect(getBuskRigHeight()).toBeNull()
    expect(renderHook(() => useBuskRigHeight()).result.current).toBeNull()
  })

  it('is not announced: pixels on this screen say nothing about another, and no row control ever read the count', () => {
    act(() => setBuskRigHeight(300))
    const options = renderHook(() => useBuskViewOptions()).result.current
    expect(Object.keys(options).sort()).toEqual(['focus', 'pageFollows', 'sheet'])
    expect(options).not.toHaveProperty('rigRows')
    expect(options).not.toHaveProperty('rigHeight')
  })
})

describe('the sheet is one fact', () => {
  it('has no open flag beside it: none is the fold, and setting a tab opens it', () => {
    act(() => setBuskSheet('none'))
    expect(getBuskSheet()).toBe('none')
    act(() => setBuskSheet('speed'))
    expect(getBuskSheet()).toBe('speed')
    expect(Object.keys(window.sessionStorage).filter((k) => /open/i.test(k))).toEqual([])
  })

  it('toggles between the fold and the tab that was last open', () => {
    act(() => setBuskSheet('speed'))
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('none')
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('speed')
  })

  it('remembers Spread once it has been open, so the toggle unfolds back onto it', () => {
    // Session 6 lit the third tab; a `?sheet=spread` arrival is an ordinary open now, and the
    // MIDI BuskSheetToggle folds and unfolds it like the other two.
    applyBuskArrival({ focus: null, sheet: 'spread' })
    expect(getBuskSheet()).toBe('spread')
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('none')
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('spread')
  })

  it('still gates the memory on the live list, so a fifth tab could land hidden the way the third and fourth did', () => {
    expect(isLiveSheetTab('show')).toBe(true)
    expect(isLiveSheetTab('cheese')).toBe(false)
    expect(LIVE_SHEET_TABS).toEqual(['speed', 'colour', 'spread', 'show'])
  })

  it('remembers Show once it has been open, and ?sheet=show arrives as an ordinary open (busk-chrome session A)', () => {
    applyBuskArrival({ focus: null, sheet: 'show' })
    expect(getBuskSheet()).toBe('show')
    toggleBuskSheet()
    expect(getBuskSheet()).toBe('none')
    toggleBuskSheet()
    expect(getBuskSheet()).toBe('show')
    // The overlay's memory of Show is Show — only a memory of Speed is redirected there.
    resetBuskWindowStores()
    surface({ short: true })
    setBuskSheet('show')
    setBuskSheet('none')
    toggleBuskSheet()
    expect(getBuskSheet()).toBe('show')
  })

  it('remembers Colour once it has been open, so the toggle unfolds back onto it', () => {
    act(() => setBuskSheet('colour'))
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('none')
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('colour')
  })

  it('unfolds onto Colour where the sheet is an overlay, since no overlay form offers Speed', () => {
    // A MIDI BuskSheetToggle on the short board: the overlay offers no Speed tab (the tempo chip is
    // the Show tab's strip's there), so a memory of Speed would open a sheet with no tab — fold to fold.
    surface({ short: true })
    expect(getBuskSheet()).toBe('none')
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('colour')
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('none')
    // Below md likewise.
    surface({ wide: false })
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('colour')
  })

  it('unfolds onto Speed when nothing has been open yet, where the rail docks', () => {
    // A desk board folded by hand: the memory is empty and Speed is the docked sheet's first tab.
    act(() => setBuskSheet('none'))
    act(() => toggleBuskSheet())
    expect(getBuskSheet()).toBe('speed')
  })
})

describe('arrival', () => {
  it('takes ?focus= and ?sheet= once, and marks the tab decided', () => {
    applyBuskArrival({ focus: 'pads', sheet: 'none' })
    expect(getBuskFocus()).toBe('pads')
    expect(getBuskSheet()).toBe('none')
    expect(isBuskWindowDecided()).toBe(true)
    expect(window.sessionStorage.getItem(BUSK_WINDOW_DECIDED_KEY)).toBe('true')
  })

  it('is a no-op the second time — a reload is never an arrival', () => {
    applyBuskArrival({ focus: 'pads', sheet: null })
    act(() => setBuskFocus('rig'))
    // The mirror has written `?focus=rig` by now; a reload finds it and must not read it as new.
    applyBuskArrival({ focus: 'split', sheet: 'speed' })
    expect(getBuskFocus()).toBe('rig')
    expect(getBuskSheet()).toBe('speed')
  })

  it('records that it decided even when it arrived with neither parameter', () => {
    applyBuskArrival({ focus: null, sheet: null })
    expect(isBuskWindowDecided()).toBe(true)
    expect(getBuskFocus()).toBe('split')
  })

  it('ignores a value outside the vocabulary and still decides', () => {
    applyBuskArrival({ focus: 'sideways', sheet: 'cheese' })
    expect(getBuskFocus()).toBe('split')
    expect(getBuskSheet()).toBe('speed')
    expect(isBuskWindowDecided()).toBe(true)
  })

  it('renders the decision one commit behind the live read', () => {
    const { result } = renderHook(() => useBuskWindowDecided())
    expect(result.current).toBe(false)
    act(() => applyBuskArrival({ focus: null, sheet: null }))
    expect(result.current).toBe(true)
  })
})

describe('applyBuskViewOptions', () => {
  it('sets focus and sheet from a frame, and reports what changed', () => {
    expect(applyBuskViewOptions({ focus: 'rig', sheet: 'none' })).toEqual({ focus: 'rig', sheet: 'none' })
    expect(getBuskFocus()).toBe('rig')
    expect(getBuskSheet()).toBe('none')
  })

  it('flips the fold and the last open tab on sheet: toggle', () => {
    act(() => setBuskSheet('speed'))
    expect(applyBuskViewOptions({ sheet: 'toggle' })).toEqual({ sheet: 'none' })
    expect(applyBuskViewOptions({ sheet: 'toggle' })).toEqual({ sheet: 'speed' })
  })

  it('unlinks this window onto a page, exactly as arriving with ?page= does (D13)', () => {
    expect(applyBuskViewOptions({ page: '3' })).toEqual({ page: 3, pageFollows: false })
    expect(isFollowingBuskPage()).toBe(false)
    expect(getLocalBuskPage()).toBe(3)
  })

  describe('pageFollows (desk-follow plan D6)', () => {
    it("pages the window with the desk again on 'true' — the chip's own press", () => {
      unlinkBuskPage(3)
      expect(applyBuskViewOptions({ pageFollows: 'true' })).toEqual({ pageFollows: true })
      expect(isFollowingBuskPage()).toBe(true)
      expect(getLocalBuskPage()).toBeNull()
    })

    it("keeps the page the window is showing as its own on 'false'", () => {
      reportShowingBuskPage(5)
      expect(applyBuskViewOptions({ pageFollows: 'false' })).toEqual({ pageFollows: false })
      expect(isFollowingBuskPage()).toBe(false)
      expect(getLocalBuskPage()).toBe(5)
    })

    it("still unlinks on 'false' before the view has resolved a page, keeping nothing rather than inventing one", () => {
      const unlinkPage = vi.fn()
      expect(applyBuskViewOptions({ pageFollows: 'false' }, { unlinkPage, showingPage: () => null })).toEqual({ pageFollows: false })
      expect(unlinkPage).toHaveBeenCalledWith(null)
    })

    it("resolves a frame carrying both keys on pageFollows: 'false' with a page unlinks onto that page, once", () => {
      reportShowingBuskPage(5)
      const unlinkPage = vi.fn()
      expect(applyBuskViewOptions({ pageFollows: 'false', page: '3' }, { unlinkPage })).toEqual({ page: 3, pageFollows: false })
      expect(unlinkPage).toHaveBeenCalledTimes(1)
      expect(unlinkPage).toHaveBeenCalledWith(3)
    })

    it("and 'true' with a page relinks and ignores the page — a paged-with window's page is the desk's", () => {
      const unlinkPage = vi.fn()
      const relinkPage = vi.fn()
      expect(applyBuskViewOptions({ pageFollows: 'true', page: '3' }, { unlinkPage, relinkPage })).toEqual({ pageFollows: true })
      expect(relinkPage).toHaveBeenCalledTimes(1)
      expect(unlinkPage).not.toHaveBeenCalled()
    })

    it('ignores a value outside the vocabulary, and a page that is not a positive integer', () => {
      const unlinkPage = vi.fn()
      const relinkPage = vi.fn()
      expect(applyBuskViewOptions({ pageFollows: 'yes', page: '0' }, { unlinkPage, relinkPage })).toEqual({})
      expect(applyBuskViewOptions({ page: '-2' }, { unlinkPage, relinkPage })).toEqual({})
      expect(unlinkPage).not.toHaveBeenCalled()
      expect(relinkPage).not.toHaveBeenCalled()
    })
  })

  it('ignores keys it does not contribute, and values outside the vocabulary', () => {
    expect(applyBuskViewOptions({ focus: 'sideways', sheet: 'cheese', page: '-1', rigRows: '2', zoom: '3' })).toEqual({})
    expect(isFollowingBuskPage()).toBe(true)
  })
})
