// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  DEFAULT_VIEW_FLAGS,
  LEGACY_STAGE_VIEW_FLAGS_KEY,
  STAGE_VIEW_FLAGS_KEY,
  parseStageViewFlags,
  resetStageViewStore,
  useStageView,
} from './useStageView'

afterEach(() => {
  window.localStorage.clear()
  window.sessionStorage.clear()
  resetStageViewStore()
})

describe('useStageView', () => {
  it('defaults with nothing stored anywhere', () => {
    expect(renderHook(() => useStageView()).result.current.flags).toEqual(DEFAULT_VIEW_FLAGS)
  })

  it("keeps this window's flags in sessionStorage", () => {
    const { result } = renderHook(() => useStageView())
    act(() => result.current.setFlag('riggings', false))
    act(() => result.current.setLabelMode('all'))
    expect(JSON.parse(window.sessionStorage.getItem(STAGE_VIEW_FLAGS_KEY)!)).toEqual({
      ...DEFAULT_VIEW_FLAGS,
      riggings: false,
      labels: 'all',
    })
    expect(window.localStorage.getItem(LEGACY_STAGE_VIEW_FLAGS_KEY)).toBeNull()
  })

  it('seeds a window with nothing of its own from the per-browser flags, once, and never writes them', () => {
    const legacy = JSON.stringify({ regions: false, riggings: true, fixtures: true, labels: false, beamCones: false })
    window.localStorage.setItem(LEGACY_STAGE_VIEW_FLAGS_KEY, legacy)
    resetStageViewStore()

    const { result } = renderHook(() => useStageView())
    // `labels: false` is the boolean the label layer replaced: None.
    expect(result.current.flags).toEqual({ regions: false, riggings: true, fixtures: true, labels: 'none', beamCones: false })

    act(() => result.current.setFlag('regions', true))
    expect(result.current.flags.regions).toBe(true)
    // The window's own value is stored, and the old key is exactly as it was.
    expect(JSON.parse(window.sessionStorage.getItem(STAGE_VIEW_FLAGS_KEY)!).regions).toBe(true)
    expect(window.localStorage.getItem(LEGACY_STAGE_VIEW_FLAGS_KEY)).toBe(legacy)

    // Once the window has its own, a change to the old key moves nothing.
    window.localStorage.setItem(LEGACY_STAGE_VIEW_FLAGS_KEY, JSON.stringify({ regions: false, fixtures: false }))
    resetStageViewStore()
    const again = renderHook(() => useStageView())
    expect(again.result.current.flags.regions).toBe(true)
    expect(again.result.current.flags.fixtures).toBe(true)
  })

  it('notifies every reader, so two surfaces of one window agree', () => {
    const one = renderHook(() => useStageView())
    const two = renderHook(() => useStageView())
    act(() => one.result.current.setFlag('beamCones', false))
    expect(two.result.current.flags.beamCones).toBe(false)
  })
})

describe('parseStageViewFlags', () => {
  it('reads field by field over the defaults', () => {
    // A value an older build wrote must not switch a layer off by omission, nor a junk value on.
    expect(parseStageViewFlags({ fixtures: false })).toEqual({ ...DEFAULT_VIEW_FLAGS, fixtures: false })
    expect(parseStageViewFlags({ regions: 'no', labels: 'sideways', extra: 1 })).toEqual(DEFAULT_VIEW_FLAGS)
    expect(parseStageViewFlags({ labels: true })).toEqual(DEFAULT_VIEW_FLAGS)
    expect(parseStageViewFlags(null)).toEqual(DEFAULT_VIEW_FLAGS)
    expect(parseStageViewFlags('junk')).toEqual(DEFAULT_VIEW_FLAGS)
  })

  it('falls back to the defaults for junk in either key', () => {
    window.sessionStorage.setItem(STAGE_VIEW_FLAGS_KEY, 'not json')
    expect(renderHook(() => useStageView()).result.current.flags).toEqual(DEFAULT_VIEW_FLAGS)

    window.sessionStorage.clear()
    window.localStorage.setItem(LEGACY_STAGE_VIEW_FLAGS_KEY, '{oops')
    resetStageViewStore()
    expect(renderHook(() => useStageView()).result.current.flags).toEqual(DEFAULT_VIEW_FLAGS)
  })
})
