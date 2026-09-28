// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueTarget } from '@/api/cuesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import {
  getLocalSelection,
  relinkToDesk,
  resetDeskFollowStores,
  unlinkFromDesk,
} from '@/lib/deskFollow'

/**
 * The busk view over the **desk's** selection rather than its own (plan D2) — and over its own
 * when the tab has unlinked (multi-screen plan D8).
 *
 * Three things are worth pinning and all are quiet failures: the rehydration, which is the only
 * place a target is dropped; the writes, which must send the *layer* target shape — a group by
 * name — because a pad's ring and a select LED have to agree about which group they mean; and the
 * local arm, where the same three writes must reach the tab's copy and never the desk.
 */

let deskTargets: CueTarget[] = []
let deskFamilies: AttributeFamily[] | null = null
const setSelection = vi.fn()
const toggleSelection = vi.fn()
const clearSelection = vi.fn()
const subselectSelection = vi.fn()

const movers = { name: 'Movers', memberCount: 4, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] }
const par = { key: 'par-1', name: 'PAR 1', typeKey: 'par', groups: [] }
const bar = {
  key: 'bar-1',
  name: 'Bar L',
  typeKey: 'bar',
  groups: [],
  elements: [0, 1].map((i) => ({ index: i, key: `bar-1.pixel-${i}`, displayName: `Cell ${i + 1}`, properties: [] })),
}

// The store's readers over a real `lib/deskFollow.ts`, so the local arm is the real one.
vi.mock('@/store/selection', async () => {
  const { useDeskFollow, useLocalSelection } = await import('@/lib/deskFollow')
  return {
    useSelectionPair: () => {
      const following = useDeskFollow()
      const local = useLocalSelection()
      return following ? { targets: deskTargets, families: deskFamilies } : local
    },
    setDeskSelection: (t: CueTarget[], f?: AttributeFamily[] | null) => setSelection(t, f ?? null),
    toggleDeskSelection: (t: CueTarget) => toggleSelection(t),
    clearDeskSelection: () => clearSelection(),
    subselectDeskSelection: (mode: string) => subselectSelection(mode),
  }
})
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [movers] }) }))
vi.mock('@/store/fixtures', () => ({ useFixtureListQuery: () => ({ data: [par, bar] }) }))
// An empty rig: the mirror walks `effectiveRig`'s fallback, every group then every fixture. `rigQuery`
// is swapped to an in-flight read by the guard test.
let rigQuery: { data: { rows: [] } | undefined; isError: boolean } = { data: { rows: [] }, isError: false }
vi.mock('@/store/busk', () => ({ useBuskRigQuery: () => rigQuery }))

import { useBuskingSelection } from './useBuskingSelection'

beforeEach(() => {
  deskTargets = []
  deskFamilies = null
  setSelection.mockClear()
  toggleSelection.mockClear()
  clearSelection.mockClear()
  subselectSelection.mockClear()
  rigQuery = { data: { rows: [] }, isError: false }
})

afterEach(() => {
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

describe('useBuskingSelection', () => {
  it('rehydrates the desk’s `{type,key}` list into whole groups and fixtures', () => {
    // The band draws a member count and a name; the cache holds neither, so this is the step that
    // makes the desk's list usable here at all.
    deskTargets = [
      { type: 'group', key: 'Movers' },
      { type: 'fixture', key: 'par-1' },
    ]
    const { result } = renderHook(() => useBuskingSelection(1))
    expect([...result.current.selectedTargets.keys()]).toEqual(['group:Movers', 'fixture:par-1'])
    const group = result.current.selectedTargets.get('group:Movers')
    expect(group?.type === 'group' && group.group.memberCount).toBe(4)
  })

  it('hands the desk’s mask through beside the targets', () => {
    deskTargets = [{ type: 'fixture', key: 'par-1' }]
    deskFamilies = ['COLOUR']
    const { result } = renderHook(() => useBuskingSelection(1))
    expect(result.current.families).toEqual(['COLOUR'])
  })

  it('shows nothing for a target the lists have not caught up with, and never filters it away', () => {
    // A target that has genuinely stopped resolving has already left `DeskSelection` server-side.
    // One missing here means the fixture list is still arriving, so it returns on the next frame —
    // this must not become a second, client-side drop rule.
    deskTargets = [{ type: 'fixture', key: 'not-yet' }]
    const { result } = renderHook(() => useBuskingSelection(1))
    expect(result.current.selectedTargets.size).toBe(0)
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('hands a toggle to the desk rather than deciding it here — by {type, key}, a group by name', () => {
    // The desk narrows a partly covered group head by head (D2). Doing it here would need the
    // group's members, which `GroupSummary` does not carry — and a second answer would drift.
    const { result } = renderHook(() => useBuskingSelection(1))
    result.current.toggleTarget({ type: 'fixture', key: 'par-1' })
    expect(toggleSelection).toHaveBeenCalledWith({ type: 'fixture', key: 'par-1' })
    result.current.toggleTarget({ type: 'group', key: 'Movers' })
    expect(toggleSelection).toHaveBeenLastCalledWith({ type: 'group', key: 'Movers' })
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('clears through the desk', () => {
    const { result } = renderHook(() => useBuskingSelection(1))
    result.current.clearSelection()
    expect(clearSelection).toHaveBeenCalled()
  })

  describe('unlinked from the desk (D8)', () => {
    it('shows the snapshot taken at unlink, and stops following the desk', () => {
      deskTargets = [{ type: 'fixture', key: 'par-1' }]
      deskFamilies = ['COLOUR']
      const { result } = renderHook(() => useBuskingSelection(1))
      act(() => unlinkFromDesk({ targets: deskTargets, families: deskFamilies }))
      expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:par-1'])
      expect(result.current.families).toEqual(['COLOUR'])

      // The desk moves on; this tab does not.
      deskTargets = [{ type: 'group', key: 'Movers' }]
      deskFamilies = null
      act(() => {})
      expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:par-1'])
      expect(result.current.families).toEqual(['COLOUR'])
    })

    it('writes to the tab’s own copy, keeps the mask on a toggle, and never touches the desk', () => {
      const { result } = renderHook(() => useBuskingSelection(1))
      act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'] }))

      act(() => result.current.toggleTarget({ type: 'group', key: 'Movers' }))
      expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:par-1', 'group:Movers'])
      expect(result.current.families).toEqual(['COLOUR'])

      act(() => result.current.toggleTarget({ type: 'fixture', key: 'par-1' }))
      expect([...result.current.selectedTargets.keys()]).toEqual(['group:Movers'])

      act(() => result.current.clearSelection())
      expect(result.current.selectedTargets.size).toBe(0)

      expect(setSelection).not.toHaveBeenCalled()
      expect(toggleSelection).not.toHaveBeenCalled()
      expect(clearSelection).not.toHaveBeenCalled()
    })

    it('adopts the desk’s selection on re-link and drops the copy', () => {
      deskTargets = [{ type: 'group', key: 'Movers' }]
      const { result } = renderHook(() => useBuskingSelection(1))
      act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'par-1' }], families: null }))
      expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:par-1'])
      act(() => relinkToDesk())
      expect([...result.current.selectedTargets.keys()]).toEqual(['group:Movers'])
      expect(getLocalSelection().targets).toEqual([])
      // Nothing was published: re-linking adopts, it does not clear what another screen has.
      expect(setSelection).not.toHaveBeenCalled()
    })
  })
})

describe('a cell in the selection', () => {
  it('rehydrates against the parent’s own element list, never by parsing the key', () => {
    deskTargets = [{ type: 'fixture', key: 'bar-1.pixel-1' }]
    const { result } = renderHook(() => useBuskingSelection(1))
    const cell = result.current.selectedTargets.get('fixture:bar-1.pixel-1')
    expect(cell).toMatchObject({ type: 'fixture', key: 'bar-1.pixel-1', fixture: { key: 'bar-1' }, element: { displayName: 'Cell 2' } })
  })

  it('toggles a cell by its element key, exactly as a rig tile hands it over', () => {
    const { result } = renderHook(() => useBuskingSelection(1))
    act(() => result.current.toggleTarget({ type: 'fixture', key: 'bar-1.pixel-0' }))
    expect(toggleSelection).toHaveBeenCalledWith({ type: 'fixture', key: 'bar-1.pixel-0' })
  })
})

describe('the sub-selection (D12)', () => {
  it('is one desk op while following — the mode and nothing else, the desk answering with the frame', () => {
    const { result } = renderHook(() => useBuskingSelection(1))
    act(() => result.current.subselect('ODD'))
    expect(subselectSelection).toHaveBeenCalledWith('ODD')
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('mirrors the rule over the tab’s own copy when unlinked, keeps the mask, and never reaches the desk', () => {
    const { result } = renderHook(() => useBuskingSelection(1))
    act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'bar-1' }], families: ['COLOUR'] }))

    act(() => result.current.subselect('ODD'))
    expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:bar-1.pixel-0'])
    expect(result.current.families).toEqual(['COLOUR'])

    // Next walks the empty rig's fallback order — every group, then every fixture — at cell
    // granularity, since every selected target is a cell.
    act(() => result.current.subselect('NEXT'))
    expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:bar-1.pixel-1'])

    act(() => result.current.subselect('ALL'))
    expect([...result.current.selectedTargets.keys()]).toEqual(['fixture:bar-1'])

    expect(subselectSelection).not.toHaveBeenCalled()
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('does nothing on the local arm until the rig has answered — the fallback is not a built rig’s order', () => {
    rigQuery = { data: undefined, isError: false }
    const { result } = renderHook(() => useBuskingSelection(1))
    act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'bar-1' }], families: null }))
    const before = getLocalSelection()
    act(() => result.current.subselect('ODD'))
    expect(getLocalSelection()).toBe(before)
    // A failed read falls back as the band does: every group then every fixture.
    rigQuery = { data: undefined, isError: true }
    const failed = renderHook(() => useBuskingSelection(1))
    act(() => failed.result.current.subselect('ODD'))
    expect(getLocalSelection().targets).toEqual([{ type: 'fixture', key: 'bar-1.pixel-0' }])
  })

  it('writes nothing on the local arm for a rewrite that changes nothing, as the desk emits no frame', () => {
    const { result } = renderHook(() => useBuskingSelection(1))
    act(() => unlinkFromDesk({ targets: [{ type: 'fixture', key: 'par-1' }], families: null }))
    const before = getLocalSelection()
    act(() => result.current.subselect('MASTERS'))
    expect(getLocalSelection()).toBe(before)
  })
})
