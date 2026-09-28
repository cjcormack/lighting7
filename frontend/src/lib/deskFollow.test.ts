// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  DESK_FOLLOW_KEY,
  LOCAL_SELECTION_KEY,
  getLocalSelection,
  isFollowingDesk,
  relinkToDesk,
  resetDeskFollowStores,
  setLocalSelection,
  unlinkFromDesk,
  useDeskFollow,
  useLocalSelection,
} from './deskFollow'

/**
 * Follow / local (multi-screen plan D8): a per-tab fact, default on; unlinking snapshots the
 * desk's selection and re-linking drops the copy.
 */

afterEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetDeskFollowStores()
})

describe('useDeskFollow', () => {
  it('follows the desk by default', () => {
    expect(renderHook(() => useDeskFollow()).result.current).toBe(true)
    expect(isFollowingDesk()).toBe(true)
  })

  it('lives in sessionStorage — one value per tab — and never in localStorage', () => {
    // `localStorage` is one value per origin per profile, and the two desk screens are two windows
    // of one profile: a flag kept there would be one flag for both.
    const { result } = renderHook(() => useDeskFollow())
    act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'] }))
    expect(result.current).toBe(false)
    expect(window.sessionStorage.getItem(DESK_FOLLOW_KEY)).toBe('false')
    expect(window.sessionStorage.getItem(LOCAL_SELECTION_KEY)).toContain('par-1')
    expect(window.localStorage.getItem(DESK_FOLLOW_KEY)).toBeNull()
    expect(window.localStorage.getItem(LOCAL_SELECTION_KEY)).toBeNull()
  })

  it('reads a tab that was unlinked before a reload as still unlinked, with its selection', () => {
    window.sessionStorage.setItem(DESK_FOLLOW_KEY, 'false')
    window.sessionStorage.setItem(
      LOCAL_SELECTION_KEY,
      JSON.stringify({ targets: [{ type: 'group', key: 'Front wash' }], families: ['position'] }),
    )
    expect(renderHook(() => useDeskFollow()).result.current).toBe(false)
    expect(renderHook(() => useLocalSelection()).result.current).toEqual({
      targets: [{ type: 'group', key: 'Front wash' }],
      families: ['POSITION'],
    })
  })

  it('reads junk in storage as following, with nothing local', () => {
    window.sessionStorage.setItem(DESK_FOLLOW_KEY, '"maybe"')
    window.sessionStorage.setItem(LOCAL_SELECTION_KEY, '{"targets":[{"type":"robot"}],"families":"COLOUR"}')
    expect(renderHook(() => useDeskFollow()).result.current).toBe(true)
    expect(getLocalSelection()).toEqual({ targets: [], families: null })
  })

  it('moves every reader together', () => {
    const a = renderHook(() => useDeskFollow())
    const b = renderHook(() => useDeskFollow())
    act(() => unlinkFromDesk({ targets: [], families: null }))
    expect(a.result.current).toBe(false)
    expect(b.result.current).toBe(false)
  })
})

describe('unlink and re-link', () => {
  it('snapshots the desk’s targets and families on unlink, folded to the one spelling', () => {
    unlinkFromDesk({
      targets: [{ type: 'fixture', key: 'par-1' }],
      families: ['COLOUR', 'INTENSITY', 'POSITION', 'BEAM'],
    })
    expect(getLocalSelection()).toEqual({ targets: [{ type: 'fixture', key: 'par-1' }], families: null })
    setLocalSelection({ targets: [], families: ['COLOUR', 'INTENSITY'] })
    expect(getLocalSelection().families).toEqual(['INTENSITY', 'COLOUR'])
  })

  it('drops the copy on re-link, so the desk’s selection is adopted rather than overwritten', () => {
    const { result } = renderHook(() => useLocalSelection())
    act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'] }))
    expect(result.current.targets).toHaveLength(1)
    act(() => relinkToDesk())
    expect(isFollowingDesk()).toBe(true)
    expect(result.current).toEqual({ targets: [], families: null })
  })
})
