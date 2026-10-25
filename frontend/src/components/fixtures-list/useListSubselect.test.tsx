// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CueTarget } from '../../api/cuesApi'
import type { SubselectMode } from '../../api/selectionApi'
import type { LocateTarget } from '../../store/locate'
import { makeFixture, makePixelBar } from '../../test/fixtureFactories'

/**
 * The heads chip's subsets over the list's own selection: the desk's frame while following, the
 * `cellsSubSelection` mirror landed as rows unlinked, and on both arms the answer revealed first.
 */
const desk = vi.hoisted(() => ({ subselects: [] as string[] }))
vi.mock('../../store/selection', () => ({ subselectDeskSelection: (mode: string) => desk.subselects.push(mode) }))
// An empty rig: the desk's own order, every group then every fixture.
vi.mock('../../store/busk', () => ({ useBuskRigQuery: () => ({ data: { rows: [] }, isError: false }) }))

const { useListSubselect } = await import('./useListSubselect')

const FIXTURES = [makeFixture('a', []), makeFixture('b', []), makeFixture('c', []), makePixelBar('bar', 4)]

function setUp(targets: LocateTarget[], following: boolean) {
  const reveal = vi.fn<(targets: readonly CueTarget[]) => void>()
  const land = vi.fn<(targets: readonly CueTarget[]) => void>()
  const { result } = renderHook(() =>
    useListSubselect({ enabled: true, projectId: 1, following, fixtures: FIXTURES, groups: [], targets, reveal, land }),
  )
  return { press: (mode: SubselectMode) => act(() => result.current!(mode)), reveal, land }
}

afterEach(() => {
  cleanup()
  desk.subselects = []
})

describe('useListSubselect', () => {
  it('sends the mode to the desk while following, revealing what the desk will answer', () => {
    const { press, reveal, land } = setUp([{ type: 'fixture', key: 'bar' }], true)
    press('ODD')
    expect(desk.subselects).toEqual(['ODD'])
    expect(land).not.toHaveBeenCalled()
    // Odd over a multi-head fixture answers with its cells.
    const revealed = reveal.mock.calls[0][0]
    expect(revealed).toHaveLength(2)
    expect(revealed.every((t) => t.key.startsWith('bar'))).toBe(true)
  })

  it('lands the mirror’s answer over the list’s own selection once unlinked', () => {
    const { press, land } = setUp(
      [{ type: 'fixture', key: 'a' }, { type: 'fixture', key: 'b' }, { type: 'fixture', key: 'c' }],
      false,
    )
    press('ODD')
    expect(desk.subselects).toEqual([])
    expect(land).toHaveBeenCalledWith([{ type: 'fixture', key: 'a' }, { type: 'fixture', key: 'c' }])
  })

  it('lands nothing when the answer is the selection as it stands', () => {
    const { press, land } = setUp([{ type: 'fixture', key: 'a' }], false)
    press('ALL')
    expect(land).not.toHaveBeenCalled()
  })

  it('is absent where the list offers none', () => {
    const { result } = renderHook(() =>
      useListSubselect({
        enabled: false,
        projectId: undefined,
        following: true,
        fixtures: [],
        groups: [],
        targets: [],
        reveal: () => {},
        land: () => {},
      }),
    )
    expect(result.current).toBeNull()
  })
})
