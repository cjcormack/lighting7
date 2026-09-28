// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import {
  selectionIntentFor,
  selectionKey,
  useStageSelection,
  type SelectionRef,
} from './useStageSelection'

const A: SelectionRef = { kind: 'patch', patchKey: 'a' }
const B: SelectionRef = { kind: 'patch', patchKey: 'b' }
const C: SelectionRef = { kind: 'region', uuid: 'r1' }

describe('selectionKey', () => {
  it('namespaces by kind, so a patch and a region can share an identifier', () => {
    expect(selectionKey({ kind: 'patch', patchKey: 'x' })).toBe('patch:x')
    expect(selectionKey({ kind: 'region', uuid: 'x' })).toBe('region:x')
    expect(selectionKey({ kind: 'rigging', uuid: 'x' })).toBe('rigging:x')
  })
})

describe('selectionIntentFor', () => {
  it('maps modifiers to intents', () => {
    const base = { shiftKey: false, metaKey: false, ctrlKey: false }
    expect(selectionIntentFor(base)).toBe('replace')
    expect(selectionIntentFor({ ...base, shiftKey: true })).toBe('add')
    expect(selectionIntentFor({ ...base, metaKey: true })).toBe('toggle')
    expect(selectionIntentFor({ ...base, ctrlKey: true })).toBe('toggle')
  })

  it('prefers toggle when both are held', () => {
    expect(selectionIntentFor({ shiftKey: true, metaKey: true, ctrlKey: false })).toBe('toggle')
  })
})

describe('useStageSelection', () => {
  it('starts empty', () => {
    const { result } = renderHook(() => useStageSelection())
    expect(result.current.count).toBe(0)
    expect(result.current.primary).toBeNull()
  })

  it('replaces by default', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.select(B))
    expect(result.current.refs).toEqual([B])
    expect(result.current.primary).toEqual(B)
  })

  it('adds with the add intent, keeping the newest as the anchor', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.select(B, 'add'))
    expect(result.current.count).toBe(2)
    expect(result.current.primary).toEqual(B)
    expect(result.current.isSelected(A)).toBe(true)
  })

  it('does not duplicate an already-selected ref', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.select(A, 'add'))
    expect(result.current.count).toBe(1)
  })

  it('re-adding with add moves the ref to the anchor position', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.select(B, 'add'))
    act(() => result.current.select(A, 'add'))
    expect(result.current.primary).toEqual(A)
    expect(result.current.count).toBe(2)
  })

  it('toggles a selected ref off, leaving the others', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, B, C]))
    act(() => result.current.select(B, 'toggle'))
    expect(result.current.count).toBe(2)
    expect(result.current.isSelected(B)).toBe(false)
    expect(result.current.primary).toEqual(C)
  })

  it('toggles an unselected ref on', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.select(B, 'toggle'))
    expect(result.current.count).toBe(2)
  })

  it('toggling off the only selection clears it', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.select(A, 'toggle'))
    expect(result.current.count).toBe(0)
    expect(result.current.primary).toBeNull()
  })

  it('select(null) clears', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, B]))
    act(() => result.current.select(null))
    expect(result.current.count).toBe(0)
  })

  it('selectMany replaces and de-duplicates', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, B, A]))
    expect(result.current.count).toBe(2)
  })

  it('selectMany with add merges without duplicating', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.select(A))
    act(() => result.current.selectMany([A, B, C], 'add'))
    expect(result.current.count).toBe(3)
  })

  it('mixes kinds in one selection', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, C]))
    expect(result.current.selectedKeys).toEqual(new Set(['patch:a', 'region:r1']))
  })

  it('reconcile drops refs whose object is gone but keeps the rest', () => {
    // The case this exists for: another operator deletes one object, the WS
    // refetch lands, and a multi-selection must survive minus that one.
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, B, C]))
    act(() => result.current.reconcile((r) => selectionKey(r) !== 'patch:b'))
    expect(result.current.count).toBe(2)
    expect(result.current.isSelected(B)).toBe(false)
    expect(result.current.isSelected(A)).toBe(true)
  })

  it('reconcile keeps the same array when nothing changed, so effects do not re-run', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, B]))
    const before = result.current.refs
    act(() => result.current.reconcile(() => true))
    expect(result.current.refs).toBe(before)
  })

  it('reconcile can empty the selection', () => {
    const { result } = renderHook(() => useStageSelection())
    act(() => result.current.selectMany([A, B]))
    act(() => result.current.reconcile(() => false))
    expect(result.current.count).toBe(0)
    expect(result.current.primary).toBeNull()
  })

  it('keeps callback identities stable across selection changes', () => {
    // These feed keyboard-shortcut effects; re-binding listeners on every
    // selection change would mean add/remove per click.
    const { result } = renderHook(() => useStageSelection())
    const first = {
      select: result.current.select,
      selectMany: result.current.selectMany,
      clear: result.current.clear,
      reconcile: result.current.reconcile,
    }
    act(() => result.current.select(A))
    act(() => result.current.select(B, 'add'))
    expect(result.current.select).toBe(first.select)
    expect(result.current.selectMany).toBe(first.selectMany)
    expect(result.current.clear).toBe(first.clear)
    expect(result.current.reconcile).toBe(first.reconcile)
  })
})
