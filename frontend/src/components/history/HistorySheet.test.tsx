// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { HistoryPage, HistoryRow } from '@/store/history'
import { parseHistoryPage } from '@/store/history'
import { filterHistoryRows, historyRecordHref } from './historySheetState'

const page = vi.hoisted(() => ({
  current: null as HistoryPage | null,
  revision: 1,
  showRevision: 1,
  refetch: vi.fn(),
  options: undefined as unknown,
}))

vi.mock('@/store/history', async () => {
  const real = await vi.importActual<typeof import('@/store/history')>('@/store/history')
  return {
    ...real,
    useHistoryPageQuery: (_arg: unknown, options: unknown) => {
      page.options = options
      return { data: page.current, isLoading: false, refetch: page.refetch }
    },
    useHistoryState: () => ({
      revision: page.revision,
      showRevision: page.showRevision,
      projectId: 1,
      undo: null,
      redo: null,
      programmer: { undo: [], redo: [] },
    }),
  }
})
const move = vi.hoisted(() => vi.fn())
const venue = vi.hoisted(() => ({ isVenue: false, canEdit: false }))
vi.mock('./HistoryMove', () => ({ useHistoryMove: () => ({ move, undo: vi.fn(), redo: vi.fn() }) }))
vi.mock('@/store/venues', () => ({ useVenueMode: () => venue }))
vi.mock('react-router', () => ({
  Link: ({ to, children }: { to: string; children: React.ReactNode }) => <a href={to}>{children}</a>,
}))
vi.mock('@/store/windows', () => ({
  useDeskWindows: () => [{ id: 'w-2', windowId: 'x', name: 'Desk left', view: 'busk' }],
}))

const { HistorySheetBody, rowActionLabel } = await import('./HistorySheet')

function row(overrides: Partial<HistoryRow>): HistoryRow {
  return {
    id: 'e-1',
    kind: 'SHOW',
    at: '2026-11-02 19:02:03.000Z',
    label: 'Look “Warm wash” added',
    mine: false,
    records: [],
    moreRecords: 0,
    requestIds: [],
    undone: false,
    redoable: false,
    moves: [],
    ...overrides,
  }
}

const ROWS: HistoryRow[] = [
  row({ id: 'e-3', label: 'Cue “Rose garden” deleted', mine: true, source: { kind: 'ACCOUNT', name: 'Chris', window: 'w-2' }, counts: { added: 0, removed: 3, changed: 0 }, records: [{ folder: 'cues', uuid: 'u', change: 'REMOVED', label: 'Cue “Rose garden” deleted' }] }),
  row({ id: 'p-1', kind: 'PROGRAMMER', label: 'Clear', source: { kind: 'DESK', name: 'Desk' } }),
  row({ id: 'e-2', label: 'Group “Front” changed', source: { kind: 'MCP', name: 'Claude · Claude Desktop' }, moves: ['changes output'] }),
  row({ id: 'c-1', kind: 'CLEAR', label: 'Pulled from the sync repo' }),
]

afterEach(() => {
  cleanup()
  move.mockClear()
  venue.isVenue = false
})

describe('filterHistoryRows', () => {
  it('keeps each filter to its rows, and every clear point in all of them', () => {
    const ids = (filter: Parameters<typeof filterHistoryRows>[1]) => filterHistoryRows(ROWS, filter).map((r) => r.id)
    expect(ids('all')).toEqual(['e-3', 'p-1', 'e-2', 'c-1'])
    expect(ids('mine')).toEqual(['e-3', 'c-1'])
    expect(ids('show')).toEqual(['e-3', 'e-2', 'c-1'])
    expect(ids('programmer')).toEqual(['p-1', 'c-1'])
    expect(ids('claude')).toEqual(['e-2', 'c-1'])
  })
})

describe('parseHistoryPage', () => {
  it('fills in what the desk Json drops', () => {
    const parsed = parseHistoryPage({ projectId: 3, rows: [{ id: 'e-1', kind: 'SHOW', at: 'x', label: 'L' }, { label: 'no id' }] })
    expect(parsed).toEqual({
      projectId: 3,
      more: false,
      live: false,
      revision: 0,
      rows: [{ id: 'e-1', kind: 'SHOW', at: 'x', label: 'L', mine: false, records: [], moreRecords: 0, requestIds: [], undone: false, redoable: false, moves: [] }],
    })
  })
})

describe('HistorySheetBody', () => {
  it('lists rows with their source, window, counts and what moves, and a clear point as a rule', () => {
    page.current = { projectId: 1, rows: ROWS, more: false, live: true, revision: 1 }
    render(<HistorySheetBody projectId={1} />)
    expect(screen.getByText('Cue “Rose garden” deleted')).toBeTruthy()
    expect(screen.getByText('Chris · Desk left')).toBeTruthy()
    expect(screen.getByText('−3')).toBeTruthy()
    expect(screen.getByText('changes output')).toBeTruthy()
    expect(screen.getByText(/Pulled from the sync repo/).closest('li')?.dataset.kind).toBe('CLEAR')
  })

  it('expands a row to its records, and filters to Claude', () => {
    page.current = { projectId: 1, rows: ROWS, more: false, live: true, revision: 1 }
    render(<HistorySheetBody projectId={1} />)
    fireEvent.click(screen.getByText('Cue “Rose garden” deleted'))
    expect(screen.getAllByText('Cue “Rose garden” deleted')).toHaveLength(2)

    fireEvent.click(screen.getByRole('radio', { name: 'Claude' }))
    expect(screen.queryByText('Clear')).toBeNull()
    expect(screen.getByText('Group “Front” changed')).toBeTruthy()
  })

  it('reads the page afresh on open, and again when either history moves', () => {
    page.current = { projectId: 1, rows: [], more: false, live: true, revision: 1 }
    page.refetch.mockClear()
    const { rerender } = render(<HistorySheetBody projectId={1} />)
    expect(screen.getByText('Nothing yet.')).toBeTruthy()
    expect(page.options).toEqual({ refetchOnMountOrArgChange: true })
    expect(page.refetch).not.toHaveBeenCalled()
    page.showRevision = 2
    rerender(<HistorySheetBody projectId={1} />)
    expect(page.refetch).toHaveBeenCalledTimes(1)
    page.revision = 5
    rerender(<HistorySheetBody projectId={1} />)
    expect(page.refetch).toHaveBeenCalledTimes(2)
  })
})

describe("the sheet's undo actions (undo plan §4.2)", () => {
  it('offers back-to-here on your own row and a step, the source on anyone else’s, and redo on an undone row', () => {
    expect(rowActionLabel(ROWS[0], false)).toBe('Undo back to here')
    expect(rowActionLabel(ROWS[1], false)).toBe('Undo back to here')
    expect(rowActionLabel(ROWS[2], false)).toBe("Undo Claude · Claude Desktop's change")
    expect(rowActionLabel(ROWS[3], false)).toBeNull()
    expect(rowActionLabel(row({ undone: true, redoable: true }), false)).toBe('Redo')
    expect(rowActionLabel(row({ undone: true, redoable: false }), false)).toBeNull()
    expect(rowActionLabel(ROWS[0], true)).toBeNull()
  })

  it('undoes your own row, anyone else’s, a step programmer-only, and redoes an undone one', () => {
    page.current = { projectId: 1, rows: [...ROWS, row({ id: 'e-0', label: 'Old', undone: true, redoable: true })], more: false, live: true, revision: 1 }
    render(<HistorySheetBody projectId={1} />)
    const buttons = screen.getAllByRole('button', { name: /Undo back to here|change$|^Redo$/ })
    for (const button of buttons) fireEvent.click(button)
    // Never back to here up front: the desk names what collides first (D3), and the question says
    // whether back to here would take your own later changes too.
    expect(move.mock.calls).toEqual([
      ['undo', { projectId: 1, entryId: 'e-3', programmerOnly: false, mine: true }],
      ['undo', { projectId: 1, entryId: 'p-1', programmerOnly: true, mine: false }],
      ['undo', { projectId: 1, entryId: 'e-2', programmerOnly: false, mine: false }],
      ['redo', { projectId: 1, entryId: 'e-0', programmerOnly: false, mine: false }],
    ])
  })

  it("draws an operator's show rows on a venue read-only, and its programmer steps still undoable", () => {
    venue.isVenue = true
    page.current = { projectId: 1, rows: ROWS, more: false, live: true, revision: 1 }
    render(<HistorySheetBody projectId={1} />)
    expect(screen.getAllByRole('button', { name: 'Undo back to here' })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: /'s change$/ })).toBeNull()
  })

  it('links a record to its page by row id, and a removed one nowhere', () => {
    expect(historyRecordHref(4, { folder: 'cueStacks', uuid: 'u', change: 'CHANGED', label: 'Act 1', rowId: 7 })).toBe('/projects/4/show/stacks/7')
    expect(historyRecordHref(4, { folder: 'cueStacks', uuid: 'u', change: 'CHANGED', label: 'Act 1' })).toBe('/projects/4/show')
    expect(historyRecordHref(4, { folder: 'looks', uuid: 'u', change: 'ADDED', label: 'Warm', rowId: 2 })).toBe('/projects/4/looks')
    expect(historyRecordHref(4, { folder: 'cues', uuid: 'u', change: 'REMOVED', label: 'Q12', rowId: 3 })).toBeNull()
    expect(historyRecordHref(4, { folder: 'project', uuid: 'u', change: 'CHANGED', label: 'Show' })).toBeNull()
  })
})
