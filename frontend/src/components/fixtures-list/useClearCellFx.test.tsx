// @vitest-environment jsdom
import { cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveFx } from '../../store/fixtureFx'

/**
 * The wiring half of the marquee's effect clear. `cellFx.test.ts` pins the rule; this pins
 * that the rule reaches the right mutation — a group target goes through the group route, and a
 * fixture target through the fixture one, which is the mistake a single "remove" call would make
 * invisible — and that the query is skipped where the gesture cannot happen.
 */
const mocks = vi.hoisted(() => ({
  effects: [] as ActiveFx[],
  activeFxOptions: null as unknown,
  removeFx: vi.fn(() => ({ unwrap: () => Promise.resolve() })),
  removeGroupFx: vi.fn(() => ({ unwrap: () => Promise.resolve() })),
  warn: vi.fn(),
}))

vi.mock('../../store/fixtureFx', () => ({
  useActiveFxQuery: (_arg: unknown, options: unknown) => {
    mocks.activeFxOptions = options
    return { data: mocks.effects }
  },
  useRemoveFxMutation: () => [mocks.removeFx],
}))
vi.mock('../../store/groups', () => ({ useRemoveGroupFxMutation: () => [mocks.removeGroupFx] }))
vi.mock('../../store/fixtures', () => ({
  useFixtureListQuery: () => ({
    data: [
      { key: 'hex-1', groups: ['wash'] },
      { key: 'hex-2', groups: ['wash'] },
    ],
  }),
}))
vi.mock('sonner', () => ({ toast: { warning: (...args: unknown[]) => mocks.warn(...args) } }))

import { cellFxKey } from './cellFx'
import { makeActiveFx as effect } from '@/test/fixtureFactories'
import { useClearCellFx } from './useClearCellFx'


const cells = (...pairs: [string, string][]) =>
  new Set(pairs.map(([key, prop]) => cellFxKey(key, prop)))

beforeEach(() => {
  mocks.effects = []
  mocks.activeFxOptions = null
  mocks.removeFx.mockClear()
  mocks.removeGroupFx.mockClear()
  mocks.warn.mockClear()
})
afterEach(cleanup)

describe('useClearCellFx', () => {
  it('stops a fixture-targeted effect through the fixture route', () => {
    mocks.effects = [effect({ id: 7, targetKey: 'hex-1', attribute: 'dimmer' })]
    const { result } = renderHook(() => useClearCellFx(true))
    result.current(cells(['hex-1', 'dimmer']))
    expect(mocks.removeFx).toHaveBeenCalledWith({ id: 7, fixtureKey: 'hex-1' })
    expect(mocks.removeGroupFx).not.toHaveBeenCalled()
  })

  it('stops a group-targeted effect through the group route, once every member is cleared', () => {
    mocks.effects = [effect({ id: 8, targetKey: 'wash', isGroupTarget: true })]
    const { result } = renderHook(() => useClearCellFx(true))
    result.current(cells(['hex-1', 'dimmer'], ['hex-2', 'dimmer']))
    expect(mocks.removeGroupFx).toHaveBeenCalledWith({ id: 8, groupName: 'wash' })
    expect(mocks.removeFx).not.toHaveBeenCalled()
  })

  it('says so rather than half-stopping one that reaches outside the selection', () => {
    mocks.effects = [effect({ id: 8, targetKey: 'wash', isGroupTarget: true })]
    const { result } = renderHook(() => useClearCellFx(true))
    result.current(cells(['hex-1', 'dimmer']))
    expect(mocks.removeGroupFx).not.toHaveBeenCalled()
    expect(mocks.warn).toHaveBeenCalledWith(expect.stringContaining('1 FX left running'))
  })

  /**
   * The identity of this callback reaches the grid's window `keydown` listener through
   * `clearSelectedCells`, and an `activeFx` refetch lands on every FX frame — so a callback
   * that changed with the data would re-bind a document-level listener at frame rate. It must stay
   * stable *and* still act on the newest effects.
   */
  it('keeps one identity across a refetch, and still sweeps the newest effects', () => {
    mocks.effects = []
    const { result, rerender } = renderHook(() => useClearCellFx(true))
    const first = result.current

    mocks.effects = [effect({ id: 7, targetKey: 'hex-1', attribute: 'dimmer' })]
    rerender()
    expect(result.current).toBe(first)

    first(cells(['hex-1', 'dimmer']))
    expect(mocks.removeFx).toHaveBeenCalledWith({ id: 7, fixtureKey: 'hex-1' })
  })

  it('skips the query and stops nothing where the gesture is refused', () => {
    mocks.effects = [effect({ id: 7 })]
    const { result } = renderHook(() => useClearCellFx(false))
    expect(mocks.activeFxOptions).toEqual({ skip: true })
    result.current(cells(['hex-1', 'dimmer']))
    expect(mocks.removeFx).not.toHaveBeenCalled()
  })
})
