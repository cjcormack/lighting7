// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_VIS_SOURCE,
  VIS_SOURCES,
  isVisSource,
  resetVisSourceStore,
  setVisSource,
  useVisSource,
} from './useVisSource'
import { act, renderHook } from '@testing-library/react'

const STORAGE_KEY = 'stage.source'
const LEGACY_KEY = 'stageVisSource'

afterEach(() => {
  window.localStorage.clear()
  window.sessionStorage.clear()
  resetVisSourceStore()
})

describe('isVisSource', () => {
  it('accepts every declared source', () => {
    for (const source of VIS_SOURCES) expect(isVisSource(source)).toBe(true)
  })

  it('rejects anything else', () => {
    // The guard is what stops a value written by a later build (or junk) from reaching code
    // that has no case for it — `usePersistentState`'s unchecked cast is the trap being avoided.
    expect(isVisSource('cueOnly')).toBe(false)
    expect(isVisSource('')).toBe(false)
    expect(isVisSource(null)).toBe(false)
    expect(isVisSource(3)).toBe(false)
  })
})

describe('useVisSource', () => {
  it('defaults to output with nothing stored', () => {
    const { result } = renderHook(() => useVisSource())
    expect(result.current).toBe(DEFAULT_VIS_SOURCE)
    expect(result.current).toBe('output')
  })

  it('reads this window\'s stored source', () => {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify('programmer'))
    const { result } = renderHook(() => useVisSource())
    expect(result.current).toBe('programmer')
  })

  it('starts a window with nothing of its own from the profile-wide value it had before session 3', () => {
    window.localStorage.setItem(LEGACY_KEY, JSON.stringify('nextGo'))
    resetVisSourceStore()
    expect(renderHook(() => useVisSource()).result.current).toBe('nextGo')
    // The window's own value wins once it has one.
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify('programmer'))
    resetVisSourceStore()
    expect(renderHook(() => useVisSource()).result.current).toBe('programmer')
  })

  it('falls back to the default for an unrecognised stored value', () => {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify('cueOnly'))
    expect(renderHook(() => useVisSource()).result.current).toBe('output')

    resetVisSourceStore()
    window.sessionStorage.setItem(STORAGE_KEY, 'not json at all')
    expect(renderHook(() => useVisSource()).result.current).toBe('output')

    resetVisSourceStore()
    window.sessionStorage.clear()
    window.localStorage.setItem(LEGACY_KEY, JSON.stringify('cueOnly'))
    resetVisSourceStore()
    expect(renderHook(() => useVisSource()).result.current).toBe('output')
  })

  it('notifies every reader, so separate surfaces stay in step', () => {
    // The reason this is a module store and not two usePersistentState calls: the Stage view
    // menu and the globally-mounted overview panel have to agree.
    const one = renderHook(() => useVisSource())
    const two = renderHook(() => useVisSource())

    // Wrapped in act: the store writes outside React, so the resulting re-render has to be
    // flushed before the hook results are read.
    act(() => setVisSource('outputProgrammer'))

    expect(one.result.current).toBe('outputProgrammer')
    expect(two.result.current).toBe('outputProgrammer')
  })

  it('persists the choice per window: sessionStorage, never the profile-wide localStorage', () => {
    renderHook(() => useVisSource())
    act(() => setVisSource('programmer'))
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(JSON.stringify('programmer'))
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(window.localStorage.getItem(LEGACY_KEY)).toBeNull()
  })
})
