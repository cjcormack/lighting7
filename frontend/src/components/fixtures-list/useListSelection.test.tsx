// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import {
  applyListSelection,
  listSelectionIntentFor,
  setListSelection,
  useListSelection,
  type ListSelectionState,
} from './useListSelection'
import { publishTargets, selectionSlice, selectTargetKeys } from '../../store/selectionSlice'

const ORDER = ['g1', 'a', 'b', 'c', 'g2', 'd'] as const
const order = [...ORDER]

const empty: ListSelectionState = { ids: [], anchor: null }

const makeStore = () => configureStore({ reducer: { selection: selectionSlice.reducer } })

/**
 * Render the hook against a **fresh** store.
 *
 * The selection is store state now, so a shared store would leak one test's selection into the
 * next — pass a shared `store` explicitly when a test wants two scopes to see each other.
 */
function renderSelection(
  scope: 'fixtures' | 'groups' | 'programmer' = 'programmer',
  store = makeStore(),
) {
  const rendered = renderHook(() => useListSelection(order, scope), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <Provider store={store}>{children}</Provider>
    ),
  })
  return { ...rendered, store }
}

describe('listSelectionIntentFor', () => {
  it('maps modifiers to intents', () => {
    const base = { shiftKey: false, metaKey: false, ctrlKey: false }
    expect(listSelectionIntentFor(base)).toBe('replace')
    expect(listSelectionIntentFor({ ...base, shiftKey: true })).toBe('range')
    expect(listSelectionIntentFor({ ...base, metaKey: true })).toBe('toggle')
    expect(listSelectionIntentFor({ ...base, ctrlKey: true })).toBe('toggle')
    expect(listSelectionIntentFor({ ...base, shiftKey: true, metaKey: true })).toBe('range-add')
  })
})

describe('applyListSelection', () => {
  it('replaces by default and sets the anchor', () => {
    expect(applyListSelection(empty, { id: 'b', intent: 'replace' }, order)).toEqual({
      ids: ['b'],
      anchor: 'b',
    })
  })

  it('toggle adds (moving the anchor) and removes (anchor falls back)', () => {
    let state = applyListSelection(empty, { id: 'a', intent: 'toggle' }, order)
    state = applyListSelection(state, { id: 'c', intent: 'toggle' }, order)
    expect(state).toEqual({ ids: ['a', 'c'], anchor: 'c' })

    state = applyListSelection(state, { id: 'c', intent: 'toggle' }, order)
    expect(state).toEqual({ ids: ['a'], anchor: 'a' })
  })

  it('range selects the slice between anchor and click, in either direction', () => {
    const anchored = applyListSelection(empty, { id: 'b', intent: 'replace' }, order)
    expect(applyListSelection(anchored, { id: 'g2', intent: 'range' }, order).ids).toEqual([
      'b',
      'c',
      'g2',
    ])
    expect(applyListSelection(anchored, { id: 'g1', intent: 'range' }, order).ids).toEqual([
      'g1',
      'a',
      'b',
    ])
  })

  it('successive shift-clicks re-pivot from the same anchor', () => {
    let state = applyListSelection(empty, { id: 'b', intent: 'replace' }, order)
    state = applyListSelection(state, { id: 'd', intent: 'range' }, order)
    expect(state.ids).toEqual(['b', 'c', 'g2', 'd'])
    // Second shift-click shrinks back toward the anchor rather than extending.
    state = applyListSelection(state, { id: 'c', intent: 'range' }, order)
    expect(state).toEqual({ ids: ['b', 'c'], anchor: 'b' })
  })

  it('range-add unions the slice with the existing selection, keeping the anchor', () => {
    let state = applyListSelection(empty, { id: 'a', intent: 'replace' }, order)
    state = applyListSelection(state, { id: 'd', intent: 'toggle' }, order)
    expect(state).toEqual({ ids: ['a', 'd'], anchor: 'd' })
    // Anchor d, slice g2..d — unioned with the existing {a, d}.
    state = applyListSelection(state, { id: 'g2', intent: 'range-add' }, order)
    expect([...state.ids].sort()).toEqual(['a', 'd', 'g2'])
    expect(state.anchor).toBe('d')
  })

  it('degrades a range to replace when the anchor is filtered out of view', () => {
    const state: ListSelectionState = { ids: ['zz'], anchor: 'zz' }
    expect(applyListSelection(state, { id: 'c', intent: 'range' }, order)).toEqual({
      ids: ['c'],
      anchor: 'c',
    })
  })

  it('ignores a range click on an id not in the visible order', () => {
    const state = applyListSelection(empty, { id: 'b', intent: 'replace' }, order)
    expect(applyListSelection(state, { id: 'gone', intent: 'range' }, order)).toBe(state)
  })
})

describe('setListSelection', () => {
  it('selects exactly the requested rows, in visible order', () => {
    // Include returns fixture keys in its own order; the sheet must show them top-to-bottom.
    expect(setListSelection(['d', 'a'], order)).toEqual({ ids: ['a', 'd'], anchor: 'd' })
  })

  it('drops rows that are not currently visible', () => {
    // A filtered-out row, or a group row while the sheet is in flat mode. Selecting the
    // survivors beats either throwing or selecting nothing.
    expect(setListSelection(['a', 'not-a-row'], order)).toEqual({ ids: ['a'], anchor: 'a' })
  })

  it('an empty request clears the selection', () => {
    expect(setListSelection([], order)).toEqual({ ids: [], anchor: null })
  })
})

describe('useListSelection', () => {
  it('setSelection replaces the whole selection in one go', () => {
    // "Select Heads on Include" — not a click, so it goes through its own action rather
    // than a loop of toggles.
    const { result } = renderSelection()
    act(() => result.current.select('g1'))
    act(() => result.current.setSelection(['b', 'd']))
    expect(result.current.orderedSelected).toEqual(['b', 'd'])
  })

  it('setSelection is referentially stable across renders', () => {
    // Keyboard-shortcut effects depend on these callbacks not re-binding per render.
    const { result, rerender } = renderSelection()
    const before = result.current.setSelection
    rerender()
    expect(result.current.setSelection).toBe(before)
  })


  it('orders selection by visible row order regardless of click order', () => {
    const { result } = renderSelection()
    act(() => result.current.select('d', 'toggle'))
    act(() => result.current.select('a', 'toggle'))
    expect(result.current.orderedSelected).toEqual(['a', 'd'])
    expect(result.current.count).toBe(2)
    expect(result.current.isSelected('a')).toBe(true)
    expect(result.current.isSelected('b')).toBe(false)
  })

  it('selectAll selects the whole visible order; clear empties', () => {
    const { result } = renderSelection()
    act(() => result.current.selectAll())
    expect(result.current.orderedSelected).toEqual(order)
    act(() => result.current.clear())
    expect(result.current.count).toBe(0)
    expect(result.current.anchor).toBeNull()
  })

  it('keeps a stable object identity while the selection is unchanged', () => {
    const { result, rerender } = renderSelection()
    act(() => result.current.select('a'))
    const before = result.current
    rerender()
    expect(result.current).toBe(before)
    act(() => result.current.select('b', 'toggle'))
    expect(result.current).not.toBe(before)
  })

  it('keeps each list’s selection to itself', () => {
    // The three lists share one container component but not one selection: `group:front-wash`
    // is a row in two of them and does not mean the same rows in each.
    const store = makeStore()
    const programmer = renderSelection('programmer', store)
    const groups = renderSelection('groups', store)

    act(() => programmer.result.current.select('a'))
    expect(programmer.result.current.orderedSelected).toEqual(['a'])
    expect(groups.result.current.orderedSelected).toEqual([])
  })

  it('drops the scope when the list unmounts', () => {
    // Behaviour-neutrality with the old local state: a selection that outlived its list would
    // let a scoped Record narrow to heads the operator can no longer see.
    const store = makeStore()
    const { result, unmount } = renderSelection('programmer', store)
    act(() => result.current.selectAll())
    act(() => {
      store.dispatch(publishTargets({ scope: 'programmer', targetKeys: ['hex-1'] }))
    })
    expect(selectTargetKeys(store.getState(), 'programmer')).toEqual(['hex-1'])

    unmount()
    expect(selectTargetKeys(store.getState(), 'programmer')).toEqual([])
    expect(store.getState().selection.programmer.ids).toEqual([])
  })
})

describe('selectionSlice.publishTargets', () => {
  it('is idempotent on value, so an unchanged selection does not churn the state', () => {
    // The container republishes whenever `rows` rebuilds — a filter keystroke, a background
    // refetch — and most of those produce the same keys. A new array each time would re-render
    // every subscriber for a selection that never moved.
    const store = makeStore()
    store.dispatch(publishTargets({ scope: 'programmer', targetKeys: ['hex-1', 'hex-2'] }))
    const first = selectTargetKeys(store.getState(), 'programmer')

    store.dispatch(publishTargets({ scope: 'programmer', targetKeys: ['hex-1', 'hex-2'] }))
    expect(selectTargetKeys(store.getState(), 'programmer')).toBe(first)

    store.dispatch(publishTargets({ scope: 'programmer', targetKeys: ['hex-1'] }))
    expect(selectTargetKeys(store.getState(), 'programmer')).toEqual(['hex-1'])
  })

  it('order matters — reordering the same keys is a real change', () => {
    // The expansion is in visible row order, which is the order a raw spread and batch writes run in.
    const store = makeStore()
    store.dispatch(publishTargets({ scope: 'programmer', targetKeys: ['hex-1', 'hex-2'] }))
    store.dispatch(publishTargets({ scope: 'programmer', targetKeys: ['hex-2', 'hex-1'] }))
    expect(selectTargetKeys(store.getState(), 'programmer')).toEqual(['hex-2', 'hex-1'])
  })
})
