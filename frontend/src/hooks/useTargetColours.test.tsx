// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// The three lists, served through each hook's own `selectFromResult`, as RTK Query would.
const lists = vi.hoisted(() => ({
  patches: [] as { key: string; colour?: string | null }[],
  fixtures: [] as { key: string; elements?: { key: string }[] }[],
  groups: [] as { name: string; colour?: string | null }[],
}))
type Select = (r: { data: unknown }) => unknown
vi.mock('@/store/patches', () => ({
  usePatchListQuery: (_id: number, opts: { selectFromResult: Select }) => opts.selectFromResult({ data: lists.patches }),
}))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: (_arg: unknown, opts: { selectFromResult: Select }) => opts.selectFromResult({ data: lists.fixtures }),
}))
vi.mock('@/store/groups', () => ({
  useGroupListQuery: (_arg: unknown, opts: { selectFromResult: Select }) => opts.selectFromResult({ data: lists.groups }),
}))

import { useTargetColours } from './useTargetColours'

beforeEach(() => {
  lists.patches = [{ key: 'bar', colour: 'red' }, { key: 'par', colour: null }, { key: 'odd', colour: 'magenta' }]
  lists.fixtures = [{ key: 'bar', elements: [{ key: 'bar.pixel-0' }, { key: 'bar.pixel-1' }] }, { key: 'par' }]
  lists.groups = [{ name: 'Front', colour: 'lime' }, { name: 'Back', colour: 'sky' }, { name: 'Side' }]
})

describe('useTargetColours', () => {
  it('gives a head its fixture’s colour and a group its own, and reads an unknown key as none', () => {
    const { result } = renderHook(() => useTargetColours(1))
    expect(result.current.fixtures.get('bar')).toBe('red')
    expect(result.current.fixtures.get('bar.pixel-1')).toBe('red')
    expect(result.current.fixtures.has('par')).toBe(false)
    // A key this desk does not know, and a reserved hue, are none (D7).
    expect(result.current.fixtures.has('odd')).toBe(false)
    expect([...result.current.groups]).toEqual([['Front', 'lime']])
  })

  it('keeps a group whose name holds a tab or a newline whole', () => {
    lists.groups = [{ name: 'Front\twash', colour: 'lime' }, { name: 'Back\nline', colour: 'pink' }]
    const { result } = renderHook(() => useTargetColours(1))
    expect([...result.current.groups]).toEqual([['Front\twash', 'lime'], ['Back\nline', 'pink']])
  })

  it('keeps its identity across a refetch that moves no colour, and changes it when one moves', () => {
    const { result, rerender } = renderHook(() => useTargetColours(1))
    const first = result.current
    lists.patches = lists.patches.map((p) => ({ ...p }))
    lists.fixtures = lists.fixtures.map((f) => ({ ...f }))
    lists.groups = lists.groups.map((g) => ({ ...g }))
    rerender()
    expect(result.current).toBe(first)
    lists.groups = [{ name: 'Front', colour: 'pink' }]
    rerender()
    expect(result.current).not.toBe(first)
    expect(result.current.groups.get('Front')).toBe('pink')
  })
})
