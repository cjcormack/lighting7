// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeFixture } from '@/test/fixtureFactories'

/**
 * The heads chip's writes on the programmer (programmer-toolbar plan D4): one desk write while the
 * window follows, and — unlinked — the desk's rule run over **the list's own rows**, which is the
 * programmer's own selection, read back as rows.
 */
const FIXTURES = ['p1', 'p2', 'p3', 'p4'].map((key) => makeFixture(key, [], { name: key }))
vi.mock('@/store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtures')>()),
  useFixtureListQuery: () => ({ data: FIXTURES }),
}))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
// An empty rig: the show-all fallback, every fixture in list order.
vi.mock('@/store/busk', () => ({ useBuskRigQuery: () => ({ data: { rows: [] }, isError: false }) }))
const desk = vi.hoisted(() => ({
  subselect: vi.fn(),
  set: vi.fn(),
  snapshot: { targets: [] as { type: 'fixture' | 'group'; key: string }[], families: null as string[] | null, source: null },
}))
vi.mock('@/store/selection', () => ({
  subselectDeskSelection: desk.subselect,
  setDeskSelection: desk.set,
  useDeskSelectionSnapshot: () => desk.snapshot,
}))

import { useListSelectionWrites } from './useListSelectionWrites'
import { buildRows } from './rowModel'

const ROWS = buildRows({ fixtures: FIXTURES, groups: [], expandedGroups: new Set(), textFilter: '', groupByGroups: false })
const id = (key: string) => ROWS.find((row) => row.kind === 'fixture' && row.fixture.key === key)!.id

afterEach(() => {
  vi.clearAllMocks()
  desk.snapshot = { targets: [], families: null, source: null }
})

function writes(following: boolean, selected: string[], cells: { rowId: string; col: 'colour' }[] = []) {
  const setRows = vi.fn()
  const addRowsToMarquee = vi.fn()
  const { result } = renderHook(() =>
    useListSelectionWrites({
      enabled: true,
      following,
      projectId: 1,
      rows: ROWS,
      selectedRowIds: new Set(selected.map(id)),
      cells,
      setRows,
      addRowsToMarquee,
    }),
  )
  return { ...result.current, setRows, addRowsToMarquee }
}

describe('useListSelectionWrites', () => {
  it('sends the subset to the desk while following, and leaves the rows to the bridge', () => {
    const { subselect, setRows } = writes(true, ['p1', 'p2'])
    subselect('ODD')
    expect(desk.subselect).toHaveBeenCalledWith('ODD')
    expect(setRows).not.toHaveBeenCalled()
  })

  it('runs the desk’s rule over the list’s own rows once unlinked', () => {
    const { subselect, setRows } = writes(false, ['p1', 'p2', 'p3', 'p4'])
    subselect('ODD')
    expect(desk.subselect).not.toHaveBeenCalled()
    expect(setRows).toHaveBeenCalledWith([id('p1'), id('p3')])
    subselect('SECOND_HALF')
    expect(setRows).toHaveBeenLastCalledWith([id('p3'), id('p4')])
  })

  it('steps the list’s own selection along the rig', () => {
    const { subselect, setRows } = writes(false, ['p2'])
    subselect('NEXT')
    expect(setRows).toHaveBeenCalledWith([id('p3')])
  })

  it('lands Select by colour on the desk following, on the rows unlinked', () => {
    writes(true, []).pickColour(['p4'], false)
    expect(desk.set).toHaveBeenCalledWith([{ type: 'fixture', key: 'p4' }], null)
    const own = writes(false, [])
    own.pickColour(['p4'], false)
    expect(own.setRows).toHaveBeenCalledWith([id('p4')])
  })

  it('adds to the desk’s own selection and keeps its mask while following — heads the list cannot show included', () => {
    // An element of a collapsed fixture: the desk has it, the list has no row for it.
    desk.snapshot = { targets: [{ type: 'fixture', key: 'c.pixel-0' }], families: ['COLOUR'], source: null }
    writes(true, ['p1']).pickColour(['p4'], true)
    expect(desk.set).toHaveBeenCalledWith(
      [
        { type: 'fixture', key: 'c.pixel-0' },
        { type: 'fixture', key: 'p4' },
      ],
      ['COLOUR'],
    )
  })

  it('widens an unlinked marquee to the added rows in its own columns, keeping its mask', () => {
    const own = writes(false, ['p1'], [{ rowId: id('p1'), col: 'colour' }])
    own.pickColour(['p3'], true)
    expect(own.addRowsToMarquee).toHaveBeenCalledWith([id('p3')])
    expect(own.setRows).not.toHaveBeenCalled()
  })
})
