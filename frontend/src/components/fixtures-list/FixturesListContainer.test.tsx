// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { CellSelection } from '../sheet/useCellSelection'
import type { ColumnKey } from './columns'
import type { Row, RowId } from './rowModel'
import type { ProgrammerScope } from '../programmer/ProgrammerScope'
import { chan, colourProp, makeFixture, makePixelBar, sliderProp } from '../../test/fixtureFactories'
import type { CellOpenRequest } from '../sheet/useCellEditorRequests'

/**
 * What the three lists now share, asserted at the seam where they used to differ.
 *
 * `FixturesTable`'s own suite pins the *gestures* a grid with a cell selection has. This one pins
 * that the two plain list routes are given one at all — they were not until this session, which is
 * what made a click on a value cell mean "select this row and open its editor" there and "select
 * this cell" on the programmer — and that the effect sweep behind ⌫ is mounted with it.
 *
 * Everything store-connected is a fake: the subject is the container's wiring, not the data.
 */
const rowSelection = vi.hoisted(() => {
  const state = {
    ids: new Set<RowId>(),
    select: vi.fn(),
    clear: vi.fn(),
    // The row door, faked the way the slice behaves: it replaces the selection, so a later render
    // reads back what was set. The scope-switch test below is the one that needs that.
    setSelection: vi.fn((ids: readonly RowId[]) => {
      state.ids = new Set(ids)
    }),
  }
  return state
})
vi.mock('./useListSelection', async () => {
  const actual = await vi.importActual<typeof import('./useListSelection')>('./useListSelection')
  return {
    listSelectionIntentFor: actual.listSelectionIntentFor,
    usePublishSelectionTargets: () => {},
    usePublishRowTargets: () => {},
    useListSelection: () => ({
      selectedIds: rowSelection.ids,
      orderedSelected: [...rowSelection.ids],
      // The last id set, as the slice's range pivot is — what the tree keys open and close.
      anchor: [...rowSelection.ids].at(-1) ?? null,
      count: rowSelection.ids.size,
      isSelected: (id: RowId) => rowSelection.ids.has(id),
      select: rowSelection.select,
      selectAll: vi.fn(),
      clear: rowSelection.clear,
      setSelection: rowSelection.setSelection,
    }),
  }
})

/** The programmer's scope, flipped by the test rather than by a band. `null` on the plain lists. */
const programmerScope = vi.hoisted(() => ({ value: null as ProgrammerScope | null }))
vi.mock('../programmer/ProgrammerScope', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../programmer/ProgrammerScope')>()),
  useProgrammerScope: () => programmerScope.value,
}))

// Through the shared factory, like every other suite in this directory: a hand-rolled partial
// would not carry the fields a real `Fixture` always has, so a render path that started reading
// one would find `undefined` here and a value on the rig.
// `a` resolves a Dimmer and a Colour cell, which is what the claimed-open tests below open; the
// rest of the suite only needs rows to exist.
const FIXTURES = [
  makeFixture('a', [sliderProp('dimmer', 'dimmer', chan(1)), colourProp('rgbColour', chan(2), chan(3), chan(4))], { name: 'SL Wash 1' }),
  makeFixture('b', [], { name: 'SL Wash 2' }),
  // A multi-head fixture, so a row can be opened by → — the tree keys the cell arrows must not take.
  makePixelBar('c', 2, [], { name: 'Pixel Bar' }),
  // Infrastructure: the raw list carries it and the visible one does not, so the list must read
  // the visible one to leave it out.
  makeFixture('hazer-power', [sliderProp('dimmer', 'dimmer', chan(40))], { name: 'Hazer power', infrastructure: true }),
]
vi.mock('../../store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../store/fixtures')>()),
  useFixtureListQuery: () => ({ data: FIXTURES, isLoading: false }),
  useVisibleFixtureListQuery: () => ({ data: FIXTURES.filter((f) => !f.infrastructure), isLoading: false }),
}))
vi.mock('../../store/groups', () => ({ useGroupListQuery: () => ({ data: [], isLoading: false }) }))
// Release stops a selection effect it released whole through the REST delete; nothing here runs one.
vi.mock('@/store/fixtureFx', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtureFx')>()),
  useRemoveFxMutation: () => [() => ({ unwrap: () => Promise.resolve() })],
}))
/** Every `programmer.clearTarget` *Release n* sent — the frame the desk's W2 answers. */
const releaseFrames = vi.hoisted(() => ({ calls: [] as unknown[][] }))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const programmer = new Proxy(base.programmer as Record<string, unknown>, {
    get: (target, prop: string) =>
      prop === 'clearTarget'
        ? (...args: unknown[]) => {
            releaseFrames.calls.push(args)
            return Promise.resolve({ targetType: args[0], targetKey: args[1], values: 1, fx: 0, partial: [] })
          }
        : target[prop],
  })
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'programmer' ? programmer : target[prop]) }) }
})
vi.mock('sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } }))
vi.mock('./useLitFixtureKeys', () => ({ useLitFixtureKeys: () => new Set<string>() }))
vi.mock('@/hooks/useFixtureColourIndex', () => ({ useFixtureColourIndex: () => new Map() }))
vi.mock('./useDeskSelectionBridge', () => ({ useDeskSelectionBridge: () => {} }))
// The plain lists' row C reads the press mask through the store; there is no Provider here, and
// on these routes the answer is the marquee's own families anyway (they never bridge).
vi.mock('@/store/selection', () => ({
  usePressFamilies: (local: unknown) => local,
  useDeskSelectionSnapshot: () => ({ targets: [], families: null, source: null }),
}))
// The plain lists draw the kit's selection bar as their row C (CLAUDE.md §List shell), and it asks
// the viewport's height; jsdom has no `matchMedia`, and a desk is tall.
vi.mock('../../hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
/** Include's one-shot request, which a test sets; none by default. */
const include = vi.hoisted(() => ({ request: { nonce: 0, fixtureKeys: [] as string[], groupKeys: [] as string[] } }))
vi.mock('../../store/includeSelection', () => ({
  useIncludeSelectionRequest: () => include.request,
}))
vi.mock('./useCellWriters', () => ({
  useCellWriters: () => ({}),
  applyPlannedWrite: () => {},
}))
/** The gate under test: `enabled`, which decides whether the FX subscription is mounted at all. */
const clearCellFx = vi.hoisted(() => ({ enabled: undefined as boolean | undefined }))
vi.mock('./useClearCellFx', () => ({
  useClearCellFx: (enabled: boolean) => {
    clearCellFx.enabled = enabled
    return () => {}
  },
}))
// The selection toolbar and the cell verbs are store-connected and have suites of their own;
// what matters here is only that selecting a cell is what summons them.
vi.mock('./SelectionToolbar', () => ({
  SelectionToolbar: ({ actions }: { actions: ReactNode }) => (
    <div data-testid="toolbar">{actions}</div>
  ),
}))
vi.mock('../sheet/CellSelectionActions', () => ({
  CellSelectionActions: () => <span data-testid="cell-verbs" />,
}))
vi.mock('./SpreadPopover', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./SpreadPopover')>()),
  SpreadPopover: () => <span data-testid="row-spread" />,
}))
// Row C's rig verbs and the heads chip's writes are store-connected, with suites of their own.
const rigRelease = vi.hoisted(() => vi.fn())
vi.mock('./RigVerbs', () => ({
  useRigVerbs: () => ({ highlight: { release: rigRelease } }),
  LocateControl: () => <span data-testid="locate" />,
  HighlightButton: () => <span data-testid="highlight" />,
}))
vi.mock('./useListSelectionWrites', () => ({ useListSelectionWrites: () => ({ subselect: vi.fn(), pickColour: vi.fn() }) }))
vi.mock('../groups/FixtureDetailModal', () => ({ FixtureDetailModal: () => null }))
vi.mock('../fixtures/GroupDetailModal', () => ({ GroupDetailModal: () => null }))
const router = vi.hoisted(() => ({ search: '', setSearchParams: vi.fn(), searchParamReads: 0 }))
vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
  useSearchParams: () => {
    router.searchParamReads++
    return [new URLSearchParams(router.search), router.setSearchParams]
  },
}))

/**
 * The table, stubbed down to the two things this suite asks about: whether it was handed a cell
 * selection, and what the container does with a click on one of its cells.
 */
const table = vi.hoisted(() => ({
  cellSelection: undefined as CellSelection<ColumnKey> | undefined,
  rows: [] as readonly Row[],
  closeEditorCell: null as { rowId: string; col: ColumnKey } | null,
  /** Draw the DOM shape of an open cell editor, which is how the container finds one to close. */
  editorOpen: false,
  /** Every open request the container handed the table — what would have opened a popover. */
  keyboardOpens: [] as CellOpenRequest<ColumnKey>[],
  /** The last cell the container asked the table to bring into view. */
  revealCell: null as { rowId: string; col: ColumnKey } | null,
}))
vi.mock('./FixturesTable', () => ({
  FixturesTable: (props: {
    rows: readonly Row[]
    cellSelection: CellSelection<ColumnKey>
    onBeginCellEdit: (row: Row, col: ColumnKey) => void
    onBackgroundClick?: () => void
    closeEditorCell?: { rowId: string; col: ColumnKey } | null
    keyboardOpen?: CellOpenRequest<ColumnKey> | null
    revealCell?: { rowId: string; col: ColumnKey } | null
  }) => {
    table.cellSelection = props.cellSelection
    if (props.revealCell) table.revealCell = props.revealCell
    table.rows = props.rows
    if (props.closeEditorCell) table.closeEditorCell = props.closeEditorCell
    if (props.keyboardOpen) table.keyboardOpens.push(props.keyboardOpen)
    return (
      <>
        <button
          data-testid="cell"
          onClick={() => props.onBeginCellEdit(props.rows[0], 'dimmer')}
        >
          {props.cellSelection.count} cells
        </button>
        <button data-testid="colour-cell" onClick={() => props.onBeginCellEdit(props.rows[0], 'colour')} />
        {/* What the real table renders for a column a row resolves nothing for, and for the
            empty space under the last row: both call `onBackgroundClick`. */}
        <button data-testid="blank-cell" onClick={() => props.onBackgroundClick?.()} />
        {/* The real grid's addressing contract, which `openCellEditorTarget` reads to find the
            open editor: a `data-state="open"` anchor inside a `[data-cell]` inside a
            `[data-row-id]`. Rendered only when this stub is told an editor is open. */}
        {table.editorOpen && (
          <div data-row-id="fixture:a">
            <div data-cell="dimmer">
              <span data-state="open" />
            </div>
          </div>
        )}
      </>
    )
  },
}))

const { FixturesListContainer } = await import('./FixturesListContainer')
import { createMarqueeStore, MarqueeStoreContext, type MarqueeStore } from './marqueeContext'

/** The programmer's row C, drawn bare: its two right-hand clusters, in order. */
const programmerToolbar = (parts: { actions: ReactNode; selection: ReactNode }) => (
  <>
    {parts.actions}
    {parts.selection}
  </>
)

beforeEach(() => {
  router.search = ''
  router.setSearchParams.mockClear()
  router.searchParamReads = 0
  releaseFrames.calls = []
  rowSelection.ids = new Set()
  rowSelection.select.mockClear()
  rowSelection.clear.mockClear()
  rowSelection.setSelection.mockClear()
  programmerScope.value = null
  clearCellFx.enabled = undefined
  table.cellSelection = undefined
  table.closeEditorCell = null
  table.editorOpen = false
  table.keyboardOpens = []
  table.revealCell = null
  include.request = { nonce: 0, fixtureKeys: [], groupKeys: [] }
})
afterEach(cleanup)

describe('the ?select= deep link', () => {
  it('selects the fixture a plain list is linked to, and consumes the param', () => {
    router.search = '?select=fixture:a'
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(rowSelection.select).toHaveBeenCalledWith('fixture:a', 'replace')
    expect(router.setSearchParams).toHaveBeenCalled()
  })

  it('holds no router subscription where the list takes no deep links — the programmer’s grid', () => {
    router.search = '?select=fixture:a'
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" enableDeepLinkSelect={false} />)
    expect(router.searchParamReads).toBe(0)
    expect(rowSelection.select).not.toHaveBeenCalled()
  })
})

describe('Include’s request', () => {
  it('is applied again after StrictMode’s simulated remount, whose unmount clears the selection', () => {
    include.request = { nonce: 4, fixtureKeys: ['a'], groupKeys: [] }
    render(
      <StrictMode>
        <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" respondToIncludeSelection enableDeepLinkSelect={false} />
      </StrictMode>,
    )
    const applied = rowSelection.setSelection.mock.calls.filter(([ids]) => [...ids].includes('fixture:a'))
    expect(applied).toHaveLength(2)
  })
})

describe('FixturesListContainer on the plain list routes', () => {
  it('lists no infrastructure fixture', () => {
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    const keys = (table.rows ?? []).flatMap((row) => ('fixture' in row ? [row.fixture.key] : []))
    expect(keys).toContain('a')
    expect(keys).not.toContain('hazer-power')
  })

  it('hands the table a cell selection, the way the programmer sheet always was', () => {
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(table.cellSelection).toBeDefined()
    expect(table.cellSelection!.count).toBe(0)
  })

  it('answers a click on a value cell by selecting that cell, not its row', () => {
    // The row rule this replaced: a click there used to select the clicked *row*, because there
    // was no cell selection for the toolbar, `commitNow` or `batchCountFor` to read.
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    fireEvent.click(screen.getByTestId('cell'))
    expect(screen.getByTestId('cell')).toHaveTextContent('1 cells')
    expect(rowSelection.select).not.toHaveBeenCalled()
  })

  it('swaps the row Spread for the cell verbs once a cell is selected', () => {
    rowSelection.ids = new Set(['fixture:a'])
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(screen.getByTestId('row-spread')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('cell'))
    expect(screen.getByTestId('cell-verbs')).toBeInTheDocument()
  })

  it('offers the whole-selection row Fan over a rows-only selection, on the programmer beside Release', () => {
    // A row selection is made by dragging the name column, by ⌘A or from another surface, and
    // fanning across eight whole heads without first drawing a rectangle over one of their columns
    // is worth keeping.
    rowSelection.ids = new Set(['fixture:a', 'fixture:b'])
    const { rerender } = render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(screen.getByTestId('row-spread')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Release/ })).toBeNull()
    rerender(
      <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />,
    )
    expect(screen.getByTestId('row-spread')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Release the 2 heads' })).toBeInTheDocument()
  })

  it('clears a rows-only selection when a blank cell is clicked — the programmer\'s answer, now theirs', () => {
    // The one part of the unification that *removes* something. `onEmptyCellClick` was withheld on
    // these two routes while their ladder had only a row rung, so a click on a dimmer-only par's
    // Colour cell did nothing; it has both rungs now, and a blank cell is grid background wearing a
    // cell's position. Pinned because the refusal was explicit, so its reversal should be too.
    rowSelection.ids = new Set(['fixture:a', 'fixture:b'])
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    fireEvent.click(screen.getByTestId('blank-cell'))
    expect(rowSelection.clear).toHaveBeenCalled()
  })

  it('drops the cells first, leaving the rows, when a marquee is what is selected', () => {
    // The other rung: with cells selected the same click narrows rather than clearing the rows,
    // which is what makes `clearByLadder` a ladder and not a blanket deselect.
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    fireEvent.click(screen.getByTestId('cell'))
    expect(screen.getByTestId('cell')).toHaveTextContent('1 cells')
    fireEvent.click(screen.getByTestId('blank-cell'))
    expect(screen.getByTestId('cell')).toHaveTextContent('0 cells')
    expect(rowSelection.clear).not.toHaveBeenCalled()
  })

  it('leaves the plain lists alone: no scope, so nothing to switch', () => {
    // `useProgrammerScope` is null on these two routes, so the scope effect fires once on mount and
    // finds no marquee. Pinned because the conversion below writes through the row door, and a
    // mount-time write there would be a `set([])` published to the desk by the programmer's bridge.
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(rowSelection.setSelection).not.toHaveBeenCalled()
  })

  it("mounts the cell clear's effect sweep, so ⌫ means one thing on every list", () => {
    // Gated on `showOwnership` until this session, which would have left ⌫ here clearing the
    // values while the effect driving them kept running — indistinguishable, on the rig, from the
    // key having done nothing.
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(clearCellFx.enabled).toBe(true)
  })
})

/**
 * The arrows over a cell marquee (CLAUDE.md §Sheet kit): the programmer's own listener, stepping by
 * the kit's `cellArrowStep` over the visible rows and columns. ↑ / ↓ over cells used to drop them
 * and step the rows, and ← / → looked for a tree row to open; over a marquee both now move it.
 */
describe('FixturesListContainer cell arrows', () => {
  const cells = () =>
    table.cellSelection!.cells.map((c) => `${c.rowId}·${c.col}`).sort()
  const press = (key: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, ...init })

  it('moves a selected cell with every arrow and brings it into view, without touching the rows', () => {
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
    fireEvent.click(screen.getByTestId('cell'))
    expect(cells()).toEqual(['fixture:a·dimmer'])
    press('ArrowRight')
    expect(cells()).toEqual(['fixture:a·colour'])
    // `b` has no properties, so every cell on its row is blank: ↓ steps past it to the pixel bar.
    press('ArrowDown')
    expect(cells()).toEqual(['fixture:c·colour'])
    expect(table.revealCell).toEqual({ rowId: 'fixture:c', col: 'colour' })
    press('ArrowUp')
    press('ArrowLeft')
    expect(cells()).toEqual(['fixture:a·dimmer'])
    expect(rowSelection.select).not.toHaveBeenCalled()
  })

  it('hears no key while the grid sits inside an inert ancestor — the programmer’s other tables', () => {
    render(
      <div inert>
        <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />
      </div>,
    )
    fireEvent.click(screen.getByTestId('cell'))
    expect(cells()).toEqual(['fixture:a·dimmer'])
    press('ArrowRight')
    press('Enter')
    press('Backspace')
    press('Escape')
    expect(cells()).toEqual(['fixture:a·dimmer'])
    expect(table.keyboardOpens).toEqual([])
  })

  it('extends a rectangle with Shift and shrinks it back towards the anchor', () => {
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
    fireEvent.click(screen.getByTestId('cell'))
    press('ArrowRight', { shiftKey: true })
    // The head steps past `b`'s blank row, but the rectangle still covers it, as a marquee's does.
    press('ArrowDown', { shiftKey: true })
    expect(cells()).toEqual([
      'fixture:a·colour',
      'fixture:a·dimmer',
      'fixture:b·colour',
      'fixture:b·dimmer',
      'fixture:c·colour',
      'fixture:c·dimmer',
    ])
    press('ArrowUp', { shiftKey: true })
    expect(cells()).toEqual(['fixture:a·colour', 'fixture:a·dimmer'])
    press('ArrowLeft', { shiftKey: true })
    expect(cells()).toEqual(['fixture:a·dimmer'])
  })

  it('leaves → and ← over a row selection to the tree: they open and close a multi-head fixture', () => {
    rowSelection.ids = new Set(['fixture:c'])
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
    const closed = table.rows.length
    press('ArrowRight')
    expect(table.rows.length).toBe(closed + 2)
    expect(table.cellSelection!.count).toBe(0)
    press('ArrowLeft')
    expect(table.rows.length).toBe(closed)
  })

  it('leaves ⌥ / ⌘ / Ctrl arrows alone, and an arrow with no cells selected to the rows', () => {
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
    press('ArrowRight')
    expect(table.cellSelection!.count).toBe(0)
    press('ArrowDown')
    expect(rowSelection.select).toHaveBeenCalledWith('fixture:a', 'replace')
    fireEvent.click(screen.getByTestId('cell'))
    press('ArrowRight', { altKey: true })
    press('ArrowRight', { metaKey: true })
    press('ArrowRight', { ctrlKey: true })
    expect(cells()).toEqual(['fixture:a·dimmer'])
  })
})

/**
 * A control a click left focused (`focusedByPointer`): a toolbar button pressed with the mouse
 * keeps the focus, and the Enter after it is meant for the selection. A menu trigger toggles its
 * menu from its own keydown handler, so the grid has to take the key before it — the capture arm.
 */
describe('FixturesListContainer with a toolbar control focused', () => {
  function Trigger({ onToggle }: { onToggle: () => void }) {
    // A Radix menu trigger's shape: Enter toggles and ↓ opens from the trigger's keydown, unless
    // already claimed.
    return (
      <button
        data-testid="trigger"
        onKeyDown={(e) => {
          if (e.defaultPrevented || (e.key !== 'Enter' && e.key !== 'ArrowDown')) return
          e.preventDefault()
          onToggle()
        }}
      />
    )
  }

  function setUp() {
    const onToggle = vi.fn()
    // The row the request names must be on screen, or the container scrolls rather than opens.
    table.editorOpen = true
    render(
      <>
        <Trigger onToggle={onToggle} />
        <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />
      </>,
    )
    fireEvent.click(screen.getByTestId('cell'))
    return { onToggle, trigger: screen.getByTestId('trigger') }
  }

  it('opens the cell editor on Enter when a click left the focus on the control', () => {
    const { onToggle, trigger } = setUp()
    fireEvent.pointerDown(trigger)
    act(() => trigger.focus())
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onToggle).not.toHaveBeenCalled()
    expect(table.keyboardOpens.map((open) => open.col)).toEqual(['dimmer'])
  })

  it('leaves Enter to a control the keyboard reached', () => {
    const { onToggle, trigger } = setUp()
    fireEvent.pointerDown(trigger)
    fireEvent.keyDown(document.body, { key: 'Tab' })
    act(() => trigger.focus())
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onToggle).toHaveBeenCalledTimes(1)
    expect(table.keyboardOpens).toEqual([])
  })

  it('steps the cell marquee on ↓ rather than opening the menu', () => {
    const { onToggle, trigger } = setUp()
    fireEvent.click(screen.getByTestId('colour-cell'))
    fireEvent.pointerDown(trigger)
    act(() => trigger.focus())
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    expect(onToggle).not.toHaveBeenCalled()
    // `b` has no properties, so ↓ steps past its blank row to the pixel bar.
    expect(table.cellSelection!.cells.map((c) => `${c.rowId}·${c.col}`)).toEqual(['fixture:c·colour'])
  })

  it('opens the tree on → from a clicked toolbar control', () => {
    rowSelection.ids = new Set(['fixture:c'])
    const trigger = document.createElement('button')
    document.body.append(trigger)
    try {
      render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
      const closed = table.rows.length
      fireEvent.pointerDown(trigger)
      act(() => trigger.focus())
      fireEvent.keyDown(trigger, { key: 'ArrowRight' })
      expect(table.rows.length).toBe(closed + 2)
    } finally {
      trigger.remove()
    }
  })

  it('opens the tree on → from a control inside the anchor row a click left focused', () => {
    rowSelection.ids = new Set(['fixture:c'])
    // The anchor row's own rename button, which a click on the name leaves focused.
    const row = document.createElement('div')
    row.setAttribute('data-row-id', 'fixture:c')
    const name = document.createElement('button')
    row.appendChild(name)
    document.body.append(row)
    try {
      render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
      const closed = table.rows.length
      fireEvent.keyDown(document.body, { key: 'Tab' })
      act(() => name.focus())
      fireEvent.keyDown(name, { key: 'ArrowRight' })
      expect(table.rows.length).toBe(closed)
      act(() => name.blur())
      fireEvent.pointerDown(name)
      act(() => name.focus())
      fireEvent.keyDown(name, { key: 'ArrowRight' })
      expect(table.rows.length).toBe(closed + 2)
    } finally {
      row.remove()
    }
  })

  it('leaves → to a clicked control in another row — its chevron toggles that row, not the anchor', () => {
    rowSelection.ids = new Set(['fixture:c'])
    const row = document.createElement('div')
    row.setAttribute('data-row-id', 'fixture:a')
    const chevron = document.createElement('button')
    row.appendChild(chevron)
    document.body.append(row)
    try {
      render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
      const closed = table.rows.length
      fireEvent.pointerDown(chevron)
      act(() => chevron.focus())
      fireEvent.keyDown(chevron, { key: 'ArrowRight' })
      expect(table.rows.length).toBe(closed)
    } finally {
      row.remove()
    }
  })

  it('leaves the arrows to a clicked radio, whose arrows move it within its group', () => {
    const { trigger } = setUp()
    trigger.setAttribute('role', 'radio')
    fireEvent.pointerDown(trigger)
    act(() => trigger.focus())
    fireEvent.keyDown(trigger, { key: 'ArrowRight' })
    expect(table.cellSelection!.cells.map((c) => `${c.rowId}·${c.col}`)).toEqual(['fixture:a·dimmer'])
  })

  it('lets ⇧F reach the full-screen toggle first, and seeds the editor with any other ⇧ letter', () => {
    // `useWindowsBridge`'s ⇧F handler: `document`, capture, claiming the key.
    const fullScreen = vi.fn((e: KeyboardEvent) => {
      if (e.key === 'F' && e.shiftKey && !e.defaultPrevented) e.preventDefault()
    })
    document.addEventListener('keydown', fullScreen, true)
    try {
      const { trigger } = setUp()
      fireEvent.pointerDown(trigger)
      act(() => trigger.focus())
      fireEvent.keyDown(trigger, { key: 'F', shiftKey: true })
      expect(fullScreen).toHaveBeenCalled()
      expect(table.keyboardOpens).toEqual([])
      fireEvent.keyDown(trigger, { key: 'G', shiftKey: true })
      expect(table.keyboardOpens.map((open) => open.seed)).toEqual(['G'])
    } finally {
      document.removeEventListener('keydown', fullScreen, true)
    }
  })
})

/**
 * A scope switch under a marquee (multi-screen session 1 follow-up A).
 *
 * The cells are scope-local and must go — eight cells in Local are eight of *your* values, and in a
 * layer they are eight of a Look's rows. The *heads* are not, and since the two selections became
 * one, clearing the cells outright emptied `selectedRowIds`: the bridge published `set([])` and
 * every other screen's target band and family pill went with it. So the switch converts the
 * marquee to its rows through the row door, and only the mask is dropped.
 */
describe('FixturesListContainer across a programmer scope switch', () => {
  it('drops a marquee to its rows rather than to nothing', () => {
    programmerScope.value = { kind: 'local' }
    const { rerender } = render(
      <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />,
    )
    fireEvent.click(screen.getByTestId('cell'))
    expect(screen.getByTestId('cell')).toHaveTextContent('1 cells')
    expect(rowSelection.setSelection).not.toHaveBeenCalled()

    programmerScope.value = { kind: 'output' }
    rerender(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)

    expect(rowSelection.setSelection).toHaveBeenCalledWith(['fixture:a'])
    expect(rowSelection.clear).not.toHaveBeenCalled()
    expect(screen.getByTestId('cell')).toHaveTextContent('0 cells')
    expect([...rowSelection.ids]).toEqual(['fixture:a'])
  })

  it('does not re-mint the cells on the way back', () => {
    // The reverse direction: with the marquee already converted there is nothing left to convert,
    // so switching back to Local leaves the rows exactly as they are and writes nothing.
    // A fresh element each time: React bails out of re-rendering a reference-equal one, and the
    // scope here is read through a mocked hook rather than through context, so a bail-out would
    // mean the switch never reached the component at all and the test would pass vacuously.
    const view = () => (
      <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />
    )
    const { rerender } = render(view())
    fireEvent.click(screen.getByTestId('cell'))
    programmerScope.value = { kind: 'output' }
    rerender(view())
    expect(screen.getByTestId('cell')).toHaveTextContent('0 cells')
    rowSelection.setSelection.mockClear()

    programmerScope.value = { kind: 'local' }
    rerender(view())

    expect(rowSelection.setSelection).not.toHaveBeenCalled()
    expect(screen.getByTestId('cell')).toHaveTextContent('0 cells')
    expect([...rowSelection.ids]).toEqual(['fixture:a'])
  })
})

/**
 * The other half of a scope switch: an editor that was already open when it happened.
 *
 * This used to be closed by accident. Under a marquee the row selection is empty, so the old
 * `clearCells()` took `selectionEmpty` (`selection.count === 0 && cellCount === 0`) across its
 * false→true edge and `useEditorOpen` shut the panel. Converting the marquee to rows keeps
 * that flag false, so the edge never comes — and an open panel is *not* inert in a read-only
 * scope: `disabled` reaches the cell's trigger, never the fields inside an open popover, and
 * `useCellWriters` has no Output or template arm, so a commit falls through to a live write into
 * Local. The close has to be said rather than fall out of the selection going away.
 */
describe('FixturesListContainer closes an open cell editor on a scope switch', () => {
  it('asks the table to close whichever editor is open', () => {
    programmerScope.value = { kind: 'local' }
    table.editorOpen = true
    const view = () => (
      <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />
    )
    const { rerender } = render(view())
    fireEvent.click(screen.getByTestId('cell'))
    expect(table.closeEditorCell).toBeNull()

    programmerScope.value = { kind: 'output' }
    rerender(view())

    expect(table.closeEditorCell).toEqual({ rowId: 'fixture:a', col: 'dimmer' })
  })

  it('asks for no close when the switch happens with no editor open', () => {
    // The request is a one-shot the table drops on the next commit, and a standing one re-opens
    // the editor it names — so a scope switch must not mint one for a cell nobody was editing.
    programmerScope.value = { kind: 'local' }
    table.editorOpen = false
    const view = () => (
      <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />
    )
    const { rerender } = render(view())
    fireEvent.click(screen.getByTestId('cell'))

    programmerScope.value = { kind: 'output' }
    rerender(view())

    expect(table.closeEditorCell).toBeNull()
  })
})

describe('FixturesListContainer — a Colour cell on the programmer', () => {
  it('opens its own popover on Enter', () => {
    programmerScope.value = { kind: 'local' }
    // The row the request names must be on screen, or the container scrolls rather than opens.
    table.editorOpen = true
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership />)
    fireEvent.click(screen.getByTestId('colour-cell'))
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(table.keyboardOpens.map((open) => open.col)).toEqual(['colour'])
  })
})

describe('Release over a row selection on the programmer (fixture-fx-sheets plan D18)', () => {
  it('sends one programmer.clearTarget per selected row, at the programmer fade', async () => {
    rowSelection.ids = new Set(['fixture:a', 'fixture:b'])
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />)
    // A rows-only selection on the programmer: Release's face releases the whole heads.
    fireEvent.click(screen.getByRole('button', { name: 'Release the 2 heads' }))
    await vi.waitFor(() => expect(releaseFrames.calls).toHaveLength(2))
    expect(releaseFrames.calls.map(([type, key]) => `${type}:${key}`)).toEqual(['fixture:a', 'fixture:b'])
  })

  it('is refused, with its reason, while a Look or palette layer is focused — live in Output (Chris’s call)', () => {
    rowSelection.ids = new Set(['fixture:a'])
    programmerScope.value = { kind: 'layer', layerId: 3 } as ProgrammerScope
    const { unmount } = render(
      <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />,
    )
    const release = screen.getByRole('button', { name: 'Release the 1 head' })
    expect(release).toBeDisabled()
    expect(release.getAttribute('title')).toMatch(/Release clears Local/)
    unmount()
    programmerScope.value = { kind: 'output' } as ProgrammerScope
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />)
    expect(screen.getByRole('button', { name: 'Release the 1 head' })).toBeEnabled()
  })

  it('is not on the plain lists — the programmer is what it releases', () => {
    rowSelection.ids = new Set(['fixture:a'])
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="fixtures" />)
    expect(screen.queryByRole('button', { name: /^Release/ })).toBeNull()
  })
})

describe('row C on the programmer (programmer-toolbar plan D3, D6, D7)', () => {
  const openMenu = (name: string) =>
    fireEvent.pointerDown(screen.getByRole('button', { name }), { button: 0, ctrlKey: false, pointerType: 'mouse' })

  it('draws Set, Fan and Release over a marquee, and no cell Clear — Release’s cell arm is ⌫', () => {
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />)
    fireEvent.click(screen.getByTestId('cell'))
    expect(screen.getByRole('button', { name: 'Set the 1 cell' })).toBeInTheDocument()
    expect(screen.getByTestId('row-spread')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Release the 1 cell' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clear cells' })).toBeNull()
    expect(screen.queryByTestId('cell-verbs')).toBeNull()
  })

  it('opens Set on whole rows by asking the family, then selects that family’s cells and opens the popup', () => {
    rowSelection.ids = new Set(['fixture:a', 'fixture:b'])
    render(
      <>
        {/* The grid's addressing contract: the row is on screen, so the request opens rather than scrolls. */}
        <div data-row-id="fixture:a" />
        <FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />
      </>,
    )
    openMenu('Set an attribute on the 2 heads')
    // `b` has no Colour and `a` does: Colour is offered, Position — no head has one — is not.
    expect(screen.getByRole('menuitem', { name: 'Position' })).toHaveAttribute('data-disabled')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Colour' }))
    expect(table.cellSelection!.cells).toEqual([{ rowId: 'fixture:a', col: 'colour' }])
    expect(table.keyboardOpens.at(-1)).toMatchObject({ rowId: 'fixture:a', col: 'colour', atButton: true })
  })

  it('lets go of a held Highlight when the selection empties — its button is gone before the release', () => {
    rowSelection.ids = new Set(['fixture:a'])
    const props = { projectId: 1, grouped: false, selectionScope: 'programmer' as const, showOwnership: true, renderToolbar: programmerToolbar }
    const { rerender } = render(<FixturesListContainer {...props} />)
    rigRelease.mockClear()
    rowSelection.ids = new Set()
    rerender(<FixturesListContainer {...props} />)
    expect(rigRelease).toHaveBeenCalled()
  })

  it('refuses Set with the scope’s words in Output, whatever the selection’s shape', () => {
    rowSelection.ids = new Set(['fixture:a'])
    programmerScope.value = { kind: 'output' } as ProgrammerScope
    render(<FixturesListContainer projectId={1} grouped={false} selectionScope="programmer" showOwnership renderToolbar={programmerToolbar} />)
    const set = screen.getByRole('button', { name: 'Set an attribute on the 1 head' })
    expect(set).toBeDisabled()
    expect(set.getAttribute('title')).toMatch(/Output is a read/)
  })
})

describe('FixturesListContainer — what a palette press lands on (venue-visit plan S1)', () => {
  const press = (key: string) => fireEvent.keyDown(window, { key })
  type ToolbarParts = { paletteTargets: readonly { type: string; key: string }[] }

  function renderWithToolbar(): { parts: () => ToolbarParts; marquee: MarqueeStore } {
    let last: ToolbarParts | null = null
    const renderToolbar = (parts: ToolbarParts) => {
      last = parts
      return null
    }
    const marquee = createMarqueeStore()
    const props = { projectId: 1, grouped: false, selectionScope: 'programmer' as const, showOwnership: true, renderToolbar }
    const wrap = (ui: React.ReactElement) => <MarqueeStoreContext.Provider value={marquee}>{ui}</MarqueeStoreContext.Provider>
    const { rerender } = render(wrap(<FixturesListContainer {...props} />))
    // Open the pixel bar so its cells have rows, then select one cell alone.
    rowSelection.ids = new Set(['fixture:c'])
    rerender(wrap(<FixturesListContainer {...props} />))
    press('ArrowRight')
    rowSelection.ids = new Set(['element:fixture:c:c.pixel-0'])
    rerender(wrap(<FixturesListContainer {...props} />))
    return { parts: () => last!, marquee }
  }

  it('sends a cell by its element key and offers the families the cell can take', () => {
    const { parts, marquee } = renderWithToolbar()
    expect(parts().paletteTargets).toEqual([{ type: 'fixture', key: 'c.pixel-0' }])
    // The cell's own colour: keyed by fixture alone, the map dropped the cell and offered nothing.
    expect(marquee.get()?.press.targetFamilies).toEqual(['COLOUR'])
  })
})
