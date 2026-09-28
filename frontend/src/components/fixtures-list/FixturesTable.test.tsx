// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Everything store-connected is mocked away: the point of this suite is the GESTURE, not the data.
// `aggregateCellValue` is passed through to the real thing: the scoped-value path runs a staged
// value through the *same* aggregation the live read uses, and stubbing it would test nothing.
vi.mock('./useRowValues', async () => ({
  aggregateCellValue: (await vi.importActual<typeof import('./useRowValues')>('./useRowValues'))
    .aggregateCellValue,
  useRowValues: () => ({
    dimmer: { kind: 'slider', min: 0.5, max: 0.5, isUniform: true },
  }),
  buildRowCells: () => [
    {
      col: 'dimmer',
      // A real channel ref, because the scoped-value path stages a value onto a resolution's
      // channels before aggregating it — the same maths as the live read, by construction.
      resolutions: [
        {
          kind: 'slider',
          property: {
            name: 'dimmer',
            category: 'dimmer',
            min: 0,
            max: 255,
            channel: { universe: 0, channelNo: 1 },
          },
        },
      ],
      targetKeys: ['a'],
      keys: [],
    },
  ],
}))
const ownership = vi.hoisted(() => ({ current: {} as Record<string, unknown> }))
vi.mock('./useRowOwnership', () => ({ useRowOwnership: () => ownership.current }))

// The programmer's scope, driven directly rather than through its provider — that one needs a
// Redux store, and what is under test here is how a *cell* renders per scope.
const scopeState = vi.hoisted(() => ({
  current: null as null | { kind: 'output' | 'local' | 'layer'; layerId?: number },
}))
const scopeActions = vi.hoisted(() => ({
  setScope: vi.fn(),
  // Answers "is this layer in the programmer's stack?" — false for a cue's layer id, which is the
  // guard the jump overlay leans on.
  focusLayer: vi.fn((layerId: number) => layerId === 7),
}))
vi.mock('../programmer/ProgrammerScope', () => ({
  useProgrammerScope: () => scopeState.current,
  useProgrammerScopeActions: () => scopeActions,
}))
const lookStore = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('../programmer/LookRowStore', async () => ({
  useLookRowStore: () => lookStore.current,
  lookRowKey: (await vi.importActual<typeof import('../programmer/lookRowKey')>(
    '../programmer/lookRowKey',
  )).lookRowKey,
}))
// The *template* half of layer scope, driven the same way. Mutually exclusive with `lookStore` by
// construction: a layer applies one or the other.
const focusedTemplate = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('../programmer/FocusedTemplateLayer', () => ({
  useFocusedTemplateLayer: () => focusedTemplate.current,
}))

/** A focused template layer, in the shape `FocusedTemplateLayer` provides. */
function templateLayer(over: Record<string, unknown> = {}) {
  return {
    layerId: 7,
    templateId: 4,
    name: 'Amber Breathe',
    template: {
      kind: 'effect',
      effect: { beatDivision: 0.5, timingSource: 'BEAT' },
    },
    kind: 'effect',
    mask: [],
    targetedKeys: null,
    targets: [],
    ...over,
  }
}

/** A focused-layer store with no pending edits — the draft is exercised in its own suite. */
function layerStore(over: Record<string, unknown>) {
  return {
    lookId: 3,
    draft: { get: () => undefined, subscribe: () => () => {} },
    setValue: () => {},
    serverRows: new Map(),
    targetedKeys: null,
    propertyMask: null,
    deferredRows: [],
    elementRows: [],
    loaded: true,
    ...over,
  }
}
// jsdom lays nothing out, so the real virtualizer measures a zero-height scroller and renders no
// rows at all. Stubbed to render them all — this suite is about the pointer gesture, not windowing.
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({
    count,
    estimateSize,
  }: {
    count: number
    estimateSize: () => number
  }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({
        index,
        key: index,
        start: index * estimateSize(),
        size: estimateSize(),
      })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('../fixtures/LocateButton', () => ({ LocateButton: () => null }))
// The socket's readyState, driven directly for the same reason the scope is: the real hook is an
// RTK Query subscription and this suite deliberately mounts without a Provider.
const deskConnected = vi.hoisted(() => ({ current: true }))
vi.mock('../../store/status', () => ({
  useIsDeskConnected: () => deskConnected.current,
}))

import { FixturesTable } from './FixturesTable'
import { EditorContextProvider } from '../programmer/EditorContext'
import { useCellSelection } from '../sheet/useCellSelection'
import type { Row } from './rowModel'
import { lookRowKey } from '../programmer/lookRowKey'
import type { ColumnKey } from './columns'

const ROWS: Row[] = [
  {
    kind: 'fixture',
    id: 'fixture:a',
    depth: 0,
    fixture: { key: 'a', name: 'SL Wash 1' },
  },
  {
    kind: 'fixture',
    id: 'fixture:b',
    depth: 0,
    fixture: { key: 'b', name: 'SL Wash 2' },
  },
] as unknown as Row[]

const onBeginCellEdit = vi.fn()
const onMarqueeDragChange = vi.fn()
const onBackgroundClick = vi.fn()
const onRowMarquee = vi.fn()
/** Which rows the harness reports selected — the base a ⌘-drag from the name column accumulates onto. */
const selectedRows = vi.hoisted(() => ({ current: new Set<string>() }))

function Harness({
  rows = ROWS,
  keyboardOpen = null,
  closeEditorCell = null,
  visibleColumns = ['dimmer'] as ColumnKey[],
}: {
  rows?: Row[]
  keyboardOpen?: { rowId: string; col: ColumnKey; seed: string; atButton: boolean } | null
  /** The bar's Set pressed a second time — the close half of the same one-shot channel. */
  closeEditorCell?: { rowId: string; col: ColumnKey } | null
  /**
   * The mocked `useRowValues` answers for `dimmer` and nothing else, so any second column here is
   * a column these rows resolve nothing for — which is the blank cell the clear-on-click case
   * needs, and the one a dimmer-only par really has under Colour.
   */
  visibleColumns?: ColumnKey[]
}) {
  const cellSelection = useCellSelection<ColumnKey>(new Set(rows.map(r => r.id)))
  return (
    <>
      <FixturesTable
        rows={rows}
        visibleColumns={visibleColumns}
        isSelected={(id) => selectedRows.current.has(id)}
        onRowClick={() => {}}
        onToggleExpand={() => {}}
        onBeginCellEdit={onBeginCellEdit}
        onCellCommit={() => {}}
        batchFor={() => ({ count: 1, skipped: 0, resolutions: [] })}
        scopeLabel="Local"
        onShowInfo={() => {}}
        showOwnership
        cellSelection={cellSelection}
        onRowMarquee={onRowMarquee}
        keyboardOpen={keyboardOpen}
        closeEditorCell={closeEditorCell}
        onMarqueeDragChange={onMarqueeDragChange}
        onBackgroundClick={onBackgroundClick}
      />
      {/* jsdom cannot see the outline a selected cell draws, so the count is the proof. */}
      <span data-testid="cell-count">{cellSelection.count}</span>
    </>
  )
}

const cellCount = () => Number(screen.getByTestId('cell-count').textContent)

/**
 * Give the grid a layout, for the tests that need the marquee to actually resolve to cells or rows.
 *
 * jsdom reports every rect as zero, which is why the rest of this suite asserts the *gesture* and
 * leaves the geometry to `cellMarquee.test.ts`. Two rects are enough here: the scroller's origin
 * becomes (0, 0) so client coordinates pass through unchanged, the sticky header measures zero
 * high, the name column's header spans x = 0..200 and the single `dimmer` column's header
 * x = 200..1000 — so a press left of 200 is in the name column and selects rows, and a press right
 * of it selects cells (strictly inside, so the suite's x = 300 presses cover the column even before
 * they move). A drag anywhere inside the first two rows' 72px covers exactly those rows.
 *
 * `scrolledBy` moves the value column's header left, the way a sideways scroll does, while the
 * sticky name header stays where it is — the case the mode test has to survive.
 */
function stubFlatLayout({ scrolledBy = 0 }: { scrolledBy?: number } = {}) {
  const rect = (left: number, right: number) =>
    ({
      left,
      top: 0,
      right,
      bottom: 0,
      width: right - left,
      height: 0,
      x: left,
      y: 0,
      toJSON: () => ({}),
    }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 200)
    if (this.hasAttribute('data-column-header')) return rect(200 - scrolledBy, 1000 - scrolledBy)
    return rect(0, 1000)
  })
}

/**
 * A press, a real drag and the release that ends it, between two y positions inside the rendered
 * rows. Rows are `ROW_HEIGHT` (36px) tall from a zero-height header, so 0-36 is row a and 36-72 is
 * row b. `mod` carries ⌘ for the accumulating-intent cases.
 */
function dragRows(
  cell: Element,
  fromY: number,
  toY: number,
  mod: { metaKey?: boolean } = {},
) {
  fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: fromY, ...mod })
  fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 380, clientY: toY, ...mod })
  fireEvent.pointerUp(cell, { button: 0, clientX: 380, clientY: toY, ...mod })
}

/** A drag down the whole of the one visible column, covering both rows. */
function dragWithinFirstColumn(cell: Element) {
  dragRows(cell, 10, 50)
}

/**
 * Which cell's editor is open. `data-state` is Radix's on a `PopoverTrigger` and restored by hand
 * on the anchor this grid uses instead (`EditorSurface`), precisely so this stays answerable
 * once the panel itself is anchored at the Set button rather than at the cell.
 */
function openCellRowId(): string | null {
  const trigger = document.querySelector('[data-cell] [data-state="open"]')
  return trigger?.closest('[data-row-id]')?.getAttribute('data-row-id') ?? null
}

beforeEach(() => {
  // The container puts what the marquee sends into the row selection, and the table dedupes its
  // next send against that — so the harness has to mirror it, or every move looks like a change.
  onRowMarquee.mockImplementation((ids: string[]) => {
    selectedRows.current = new Set(ids)
  })
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  // `clearAllMocks` resets call history and NOT a spy's installed implementation, so without this
  // `stubFlatLayout`'s `getBoundingClientRect` would keep answering with its fake flat rect for
  // every test that ran after it in this file — including Radix's own popover positioning.
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  scopeState.current = null
  lookStore.current = null
  focusedTemplate.current = null
  ownership.current = {}
  deskConnected.current = true
  selectedRows.current = new Set()
})

/** The value cell's popover trigger — the button the marquee must not fight with. */
const cellButton = () =>
  screen.getAllByRole('button').find(b => b.className.includes('h-full'))!

/**
 * jsdom lays nothing out, so the marquee's *geometry* is tested in `cellMarquee.test.ts` where it
 * is pure. What is testable here is the discriminator — press-and-release versus press-drag-release
 * — and that matters because it rests on an assumption about a third-party component: Radix
 * `Popover` opens on `click`, not `pointerdown`. If a shadcn bump ever changed that, drag-select
 * would start opening editors mid-drag and no geometry test would notice.
 */
describe('FixturesTable cell gesture', () => {
  it('a press with no travel is still a click, and a click selects the cell — opening nothing', () => {
    // Both halves matter. The click has to *arrive* (the gesture discriminator), and it has to
    // arrive as a selection: on a grid that can select cells the trigger is an anchor rather than
    // a `PopoverTrigger`, so a click moves the marquee and the editor is Set's to open.
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.click(cell)
    expect(onBeginCellEdit).toHaveBeenCalled()
    expect(document.querySelector('[data-cell-editor-surface]')).toBeNull()
  })

  it('a click on a cell already in the marquee is still reported — it narrows to that one cell', () => {
    // The table must not suppress it. It used to be suppressed *above* it — a cell inside the
    // marquee was the whole marquee's editor, so moving the selection would have discarded the
    // rest of it silently. With the editor opened by Set instead, a click inside the marquee is
    // the operator narrowing a rectangle to one cell, which is the one thing a rectangle cannot
    // say; the container then replaces the selection with it.
    stubFlatLayout()
    render(<Harness />)
    const cell = cellButton()
    dragWithinFirstColumn(cell)
    expect(cellCount()).toBe(2)
    // A fresh press disposes the drag's click-swallow, which is what a second gesture really does.
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 10 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 300, clientY: 10 })
    fireEvent.click(cell)
    expect(onBeginCellEdit).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'fixture:a' }),
      'dimmer',
    )
    expect(document.querySelector('[data-cell-editor-surface]')).toBeNull()
  })

  it('a click on a column the row resolves nothing for clears the selection', () => {
    // A dimmer-only par's Colour cell. It is blank rather than an em-dash — there is nothing here
    // to set — and it is not background in the DOM either, since the row above it carries
    // `data-row-id` and the scroller's handler stops there. So it says so itself; without this a
    // click on it did nothing at all and left the old selection standing.
    stubFlatLayout()
    render(<Harness visibleColumns={['dimmer', 'colour'] as ColumnKey[]} />)
    const row = document.querySelector('[data-row-id="fixture:a"]')!
    // name, dimmer, colour — and the third is the blank one, since the mock resolves only dimmer.
    const blank = row.children[2]
    expect(blank.querySelector('button')).toBeNull()
    fireEvent.click(blank)
    expect(onBackgroundClick).toHaveBeenCalled()
  })

  it('a press that travels is a marquee, and the trailing click is swallowed', () => {
    // Without the capture-phase suppressor the cell under the release point opens its editor on
    // top of the selection just made.
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    // `buttons: 1` is what a real drag sends, and the handler relies on it: a `pointermove`
    // with no button held means the press was released somewhere this element never saw, and
    // the armed gesture is disarmed rather than turned into a marquee on a plain hover.
    fireEvent.pointerMove(cell, {
      button: 0,
      buttons: 1,
      clientX: 380,
      clientY: 160,
    })
    fireEvent.pointerUp(cell, { button: 0, clientX: 380, clientY: 160 })
    fireEvent.click(cell)
    expect(onBeginCellEdit).not.toHaveBeenCalled()
  })

  it('a marquee ends with focus on the document, not on the trigger the press began on', () => {
    // Chromium focuses a `<button>` on mousedown, and every cell trigger is one — so without this
    // a mouse-drawn marquee left that button focused and the container's window handler read the
    // operator's Enter as "from a focused control", opening one cell's popover instead of the
    // typed-value field. Safari does not focus buttons on mousedown, which hid it there.
    render(<Harness />)
    const cell = cellButton()
    cell.focus()
    expect(document.activeElement).toBe(cell)
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 380, clientY: 160 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 380, clientY: 160 })
    expect(document.activeElement).toBe(document.body)
    // The trailing click a real release generates; the drag's one-shot swallower is waiting for
    // it, and would otherwise eat the next test's first click.
    fireEvent.click(cell)
  })

  it('a press with no travel leaves focus where the browser put it — Tab-then-Enter still opens the editor', () => {
    render(<Harness />)
    const cell = cellButton()
    cell.focus()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 300, clientY: 100 })
    expect(document.activeElement).toBe(cell)
  })

  it('ignores a press inside an open cell editor, which React bubbles here through its portal', () => {
    // React bubbles a synthetic event up the *React* tree, portals included — and a cell editor is
    // rendered from inside its row. So a drag on the dimmer slider, the colour picker or the hue
    // bar arrived at the rows wrapper as a press on the grid, started a marquee from wherever the
    // editor happened to be over, and selected cells under the operator while they were setting a
    // value. Barely visible in a popover, which is small and sits beside its own cell; plainly
    // wrong in the phone's sheet, which covers the grid it was scribbling on.
    //
    // The scroller's background `onClick` already guards the same trap; this is the handler that
    // was missing it.
    stubFlatLayout()
    // Opened the only way it can be: the container's request. A click selects the cell now.
    render(<Harness keyboardOpen={{ rowId: 'fixture:a', col: 'dimmer' as ColumnKey, seed: '', atButton: true }} />)
    const editor = document.querySelector('[data-cell-editor-surface]')
    expect(editor, 'the editor did not open, so this asserts nothing').not.toBeNull()

    fireEvent.pointerDown(editor!, { button: 0, clientX: 300, clientY: 10 })
    fireEvent.pointerMove(editor!, { button: 0, buttons: 1, clientX: 380, clientY: 50 })

    expect(screen.queryByTestId('cell-marquee')).toBeNull()

    fireEvent.pointerUp(editor!, { button: 0, clientX: 380, clientY: 50 })
  })

  it('disarms a press whose release it never saw, rather than marqueeing on hover', () => {
    // Release over the sticky header, the scrollbar or outside the window: no pointer capture was
    // taken (that only happens past the threshold), so no `pointerup` reaches the rows wrapper. The
    // next plain hover must not resume a gesture from a start point set minutes ago.
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, { buttons: 0, clientX: 380, clientY: 160 })
    fireEvent.pointerMove(cell, { buttons: 0, clientX: 420, clientY: 200 })

    // THE assertion: no rubber band was ever drawn, i.e. no gesture ran. The *selection* cannot be
    // asserted here — jsdom reports every rect as zero, so `columnRange` matches nothing and the
    // count stays 0 whatever happens (the geometry is covered for real in `cellMarquee.test.ts`).
    // The band is set from the pointer alone, so it is the one part of the gesture jsdom can see.
    expect(screen.queryByTestId('cell-marquee')).toBeNull()

    fireEvent.click(cell)
    expect(onBeginCellEdit).toHaveBeenCalled()
  })

  it('reports the drag starting and ending, twice and no more', () => {
    // The programmer's selection bar holds its place for the duration of a drag
    // (`selectionBandState`), and it can only do that if the fact of one crosses out of here. It
    // must cost two renders a gesture, not one a pointer move — this is the assertion that says so.
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()

    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 380, clientY: 160 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 420, clientY: 200 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 460, clientY: 240 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true]])

    fireEvent.pointerUp(cell, { button: 0, clientX: 460, clientY: 240 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true], [false]])
    fireEvent.click(cell)
  })

  it('says nothing when a press never becomes a drag', () => {
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.click(cell)
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
  })

  it('ends the drag when a press is released where this element never saw it', () => {
    // The `buttons === 0` teardown. Without this arm the consumer believes a drag is still in
    // flight and the selection bar holds a place for a gesture that has gone.
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 380, clientY: 160 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true]])
    fireEvent.pointerMove(cell, { buttons: 0, clientX: 420, clientY: 200 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true], [false]])
  })

  it('a released drag opens nothing — the selection bar\'s Set and Enter do that', () => {
    // `PD-POPUP-AFTER-DRAG` used to open the first selected cell\'s editor behind the release of a
    // single-column drag. The drag says *what* to edit and the bar says *do it* now, so a
    // rectangle released over one column leaves the cells selected and no editor open.
    stubFlatLayout()
    render(<Harness />)
    dragWithinFirstColumn(cellButton())
    expect(cellCount()).toBe(2)
    expect(screen.queryByRole('slider')).toBeNull()
    expect(openCellRowId()).toBeNull()
    fireEvent.click(cellButton())
  })

  it('a double click on a cell opens that cell\'s editor', () => {
    // The pointer's own way of saying Set. It never reaches the container — `EditorSurface`
    // answers it — so the proof is that the cell the gesture landed on is the one marked open,
    // while the clicks that carried it still selected it.
    stubFlatLayout()
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.click(cell)
    fireEvent.click(cell)
    fireEvent.doubleClick(cell)

    expect(openCellRowId()).toBe('fixture:a')
    expect(onBeginCellEdit).toHaveBeenCalled()
  })

  it('opens the cell the container names, when the request comes through `keyboardOpen`', () => {
    // The one door left into an editor without a click — Enter over a selection, or the bar\'s
    // Set — and it is the container\'s to open, not the drag\'s.
    stubFlatLayout()
    render(<Harness keyboardOpen={{ rowId: 'fixture:b', col: 'dimmer' as ColumnKey, seed: '', atButton: true }} />)
    expect(openCellRowId()).toBe('fixture:b')
    // `onBeginEdit` is deliberately NOT part of a requested open: it exists to move the selection
    // to the cell a click landed on, and this cell is inside the selection by construction.
    expect(onBeginCellEdit).not.toHaveBeenCalled()
  })

  it('closes the cell the container names, and lets it be reopened afterwards', () => {
    // The second press of the bar's Set. It needs a channel of its own because a press on Set is
    // NOT the outside click that dismisses a popover — Set is that popover's own anchor — so
    // without this the second press left the panel open with focus stranded on the button, which
    // is what made the grid's Escape clear the selection out from under it.
    stubFlatLayout()
    const request = { rowId: 'fixture:b', col: 'dimmer' as ColumnKey, seed: '', atButton: true }
    const { rerender } = render(<Harness keyboardOpen={request} />)
    expect(openCellRowId()).toBe('fixture:b')

    rerender(<Harness closeEditorCell={{ rowId: 'fixture:b', col: 'dimmer' as ColumnKey }} />)
    expect(openCellRowId()).toBeNull()

    // And the close must not latch: Set pressed a third time opens it again.
    rerender(<Harness keyboardOpen={{ ...request }} />)
    expect(openCellRowId()).toBe('fixture:b')
  })

  it('leaves a cell alone when the close names a different one', () => {
    stubFlatLayout()
    render(
      <Harness
        keyboardOpen={{ rowId: 'fixture:b', col: 'dimmer' as ColumnKey, seed: '', atButton: true }}
        closeEditorCell={{ rowId: 'fixture:a', col: 'dimmer' as ColumnKey }}
      />,
    )
    expect(openCellRowId()).toBe('fixture:b')
  })

  it('a drag from the name column selects the rows it covers, not cells', () => {
    stubFlatLayout()
    render(<Harness />)
    const name = screen.getByText('SL Wash 1')
    fireEvent.pointerDown(name, { button: 0, clientX: 100, clientY: 10 })
    fireEvent.pointerMove(name, { button: 0, buttons: 1, clientX: 120, clientY: 50 })
    expect(onRowMarquee).toHaveBeenLastCalledWith(['fixture:a', 'fixture:b'])
    expect(cellCount()).toBe(0)
    // A move that changes no row sends nothing — the container dispatches on every call.
    fireEvent.pointerMove(name, { button: 0, buttons: 1, clientX: 140, clientY: 60 })
    expect(onRowMarquee).toHaveBeenCalledTimes(1)
    fireEvent.pointerUp(name, { button: 0, clientX: 140, clientY: 60 })
    // The trailing click is swallowed, or the name cell\'s own click would replace the selection
    // the drag just made with the one row under the release.
    fireEvent.click(name)
    expect(onMarqueeDragChange.mock.calls).toEqual([[true], [false]])
  })

  it('a ⌘-drag from the name column accumulates onto the selection it began over, and shrinking un-selects', () => {
    stubFlatLayout()
    selectedRows.current = new Set(['fixture:b'])
    render(<Harness />)
    const name = screen.getByText('SL Wash 1')
    fireEvent.pointerDown(name, { button: 0, clientX: 100, clientY: 10, metaKey: true })
    fireEvent.pointerMove(name, { button: 0, buttons: 1, clientX: 120, clientY: 50, metaKey: true })
    expect(onRowMarquee).toHaveBeenLastCalledWith(['fixture:a', 'fixture:b'])
    fireEvent.pointerMove(name, { button: 0, buttons: 1, clientX: 120, clientY: 20, metaKey: true })
    // Back to one covered row: the base row plus the row under the pointer, in display order.
    expect(onRowMarquee).toHaveBeenLastCalledWith(['fixture:a', 'fixture:b'])
    fireEvent.pointerUp(name, { button: 0, clientX: 120, clientY: 20, metaKey: true })
    fireEvent.click(name)
  })

  it('reads the sticky name column from its own rect, so a sideways-scrolled grid still selects rows', () => {
    // The name cell is `sticky left-0` while the value bands scroll under it: scrolled right, the
    // first band's left edge is at or left of zero, and a mode test against that edge would call a
    // press on the still-visible name cell a cell press — and rubber-band the cells hidden behind it.
    stubFlatLayout({ scrolledBy: 300 })
    render(<Harness />)
    const name = screen.getByText('SL Wash 1')
    fireEvent.pointerDown(name, { button: 0, clientX: 100, clientY: 10 })
    fireEvent.pointerMove(name, { button: 0, buttons: 1, clientX: 120, clientY: 50 })
    expect(onRowMarquee).toHaveBeenLastCalledWith(['fixture:a', 'fixture:b'])
    expect(cellCount()).toBe(0)
    fireEvent.pointerUp(name, { button: 0, clientX: 120, clientY: 50 })
    fireEvent.click(name)
  })

  it('counts the accumulated rows on the chip, not the rectangle', () => {
    stubFlatLayout()
    selectedRows.current = new Set(['fixture:b'])
    render(<Harness />)
    const name = screen.getByText('SL Wash 1')
    fireEvent.pointerDown(name, { button: 0, clientX: 100, clientY: 10, metaKey: true })
    fireEvent.pointerMove(name, { button: 0, buttons: 1, clientX: 120, clientY: 20, metaKey: true })
    expect(screen.getByText('2 rows')).toBeInTheDocument()
    fireEvent.pointerUp(name, { button: 0, clientX: 120, clientY: 20, metaKey: true })
    fireEvent.click(name)
  })

  it('a drag from a value column into the name column is still a cell marquee', () => {
    // Which of the two a press is for is decided at the press, the way a spreadsheet decides
    // between a row-header drag and a range drag.
    stubFlatLayout()
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 400, clientY: 10 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 100, clientY: 50 })
    expect(onRowMarquee).not.toHaveBeenCalled()
    expect(cellCount()).toBe(2)
    fireEvent.pointerUp(cell, { button: 0, clientX: 100, clientY: 50 })
    fireEvent.click(cell)
  })

  it('ignores a non-primary button', () => {
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 2, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, {
      button: 2,
      buttons: 2,
      clientX: 380,
      clientY: 160,
    })
    fireEvent.pointerUp(cell, { button: 2, clientX: 380, clientY: 160 })
    fireEvent.click(cell)
    expect(onBeginCellEdit).toHaveBeenCalled()
  })
})

/** The wrapper the scope classes land on — the cell button's parent. */
const cellWrapper = () => cellButton().parentElement!

const jumpButton = () =>
  screen.queryAllByRole('button').find((b) => b.className.includes('cursor-zoom-in'))

describe('FixturesTable scopes', () => {
  it('leaves a list with no scope exactly as it was', () => {
    // `/fixtures` and `/groups` mount this table with no programmer scope anywhere above them.
    // They must not inherit Output's read-only-ness, or the two plain list routes silently stop
    // being editable — the one regression this seam could cause that nobody would attribute to it.
    render(<Harness />)
    expect(cellWrapper().className).not.toContain('pointer-events-none')
    expect(screen.queryByText('—')).toBeNull()
    expect(screen.getAllByText('0%').length).toBe(ROWS.length)
  })

  it('makes Output a read, not an editor', () => {
    scopeState.current = { kind: 'output' }
    render(<Harness />)
    // The value is still the cook's — Output shows everything — but the cell does not take a click.
    expect(screen.getAllByText('0%').length).toBe(ROWS.length)
    expect(cellWrapper().className).toContain('pointer-events-none')
  })

  it('shows an em-dash, not a zero, where the focused layer holds nothing', () => {
    scopeState.current = { kind: 'layer', layerId: 7 }
    lookStore.current = layerStore({})
    render(<Harness />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    // A zero here would be a lie about a Look that says nothing about this head, and the live
    // reading would be the rig's answer to a question about the Look.
    expect(screen.queryAllByText('0%')).toHaveLength(0)
  })

  it('rings a cell the focused layer does set', () => {
    scopeState.current = { kind: 'layer', layerId: 7 }
    lookStore.current = layerStore({ serverRows: new Map([[lookRowKey('a', 'dimmer'), { kind: 'level', value: 128 }]]) })
    render(<Harness />)
    expect(screen.getAllByText('50%').length).toBe(ROWS.length)
    expect(cellWrapper().className).toContain('ring-primary/70')
  })

  it('greys a column outside the layer mask, and says so rather than hiding it', () => {
    scopeState.current = { kind: 'layer', layerId: 7 }
    // Dimmer is INTENSITY, so a COLOUR-only layer asserts nothing here.
    lookStore.current = layerStore({ propertyMask: 'COLOUR' })
    render(<Harness />)
    expect(cellWrapper().className).toContain('opacity-40')
    expect(cellWrapper().className).toContain('pointer-events-none')
  })

  it('shows the live value, ringed and with the wave, on an effect template layer', () => {
    scopeState.current = { kind: 'layer', layerId: 7 }
    focusedTemplate.current = templateLayer()
    const { container } = render(<Harness />)
    // The **live** read, not an em-dash: an effect is one rule for every head, and what is worth
    // watching is what it is producing right now. Same value Output shows, which is the point.
    expect(screen.getAllByText('0%').length).toBe(ROWS.length)
    expect(cellWrapper().className).toContain('ring-primary/70')
    expect(container.querySelector('svg.lucide-audio-waveform')).not.toBeNull()
    // 0.5 beats is an eighth note — the label the whole desk uses for that division.
    expect(screen.getAllByText('1/8').length).toBeGreaterThan(0)
  })

  it('shows nothing per fixture on a value template layer', () => {
    scopeState.current = { kind: 'layer', layerId: 7 }
    focusedTemplate.current = templateLayer({ kind: 'value', template: { kind: 'value', effect: null } })
    const { container } = render(<Harness />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    expect(container.querySelector('svg.lucide-audio-waveform')).toBeNull()
  })

  it('refuses the edit through the keyboard too, not only the pointer', () => {
    // The pointer guard is `pointer-events-none` on the wrapper; the trigger stays tabbable, so
    // Tab-then-Enter would otherwise open an editor whose commit falls through `useCellWriters`
    // to a **live** write — literals in Local, on a grid drawing itself as a read.
    scopeState.current = { kind: 'layer', layerId: 7 }
    focusedTemplate.current = templateLayer()
    render(<Harness />)
    expect(cellWrapper().className).toContain('pointer-events-none')
    expect(cellButton()).toBeDisabled()
  })

  it('disables an Output cell for the keyboard as well, for the same reason', () => {
    scopeState.current = { kind: 'output' }
    render(<Harness />)
    expect(cellButton()).toBeDisabled()
  })

  it('badges an effect-driven cell with the FX wave, and nothing else', () => {
    // The violet ring alone was too quiet beside the blue ones; the badge is what says "this value
    // is moving and Record will not take it" without a hover.
    ownership.current = {
      dimmer: { source: 'effect', touched: false, isUniform: true, owners: [] },
      colour: { source: 'programmer', touched: true, isUniform: true, owners: [] },
    }
    render(<Harness />)
    // One per effect-owned cell (the harness renders more than one row), and never on the
    // programmer-owned colour cells beside them.
    const badges = screen.getAllByTestId('effect-badge')
    expect(badges.length).toBeGreaterThan(0)
    for (const badge of badges) {
      expect(badge.closest('[data-cell]')).toHaveAttribute('data-cell', 'dimmer')
    }
  })

  it('makes an Output tint a destination — clicking jumps to the layer that won it', () => {
    // What makes the ownership colours worth learning: they are navigational, not decorative.
    scopeState.current = { kind: 'output' }
    ownership.current = {
      dimmer: {
        source: 'cue',
        touched: false,
        isUniform: true,
        owners: [],
        layer: { layerId: 7, lookId: 3, name: 'Warm Wash', mixed: false },
      },
    }
    render(<Harness />)
    fireEvent.click(jumpButton()!)
    expect(scopeActions.focusLayer).toHaveBeenCalledWith(7)
  })

  it('does not offer a jump for a cell more than one layer contributed to', () => {
    // A `mixed` cell has no single owner to name, so a cursor promising a jump would be lying.
    scopeState.current = { kind: 'output' }
    ownership.current = {
      dimmer: {
        source: 'cue',
        touched: false,
        isUniform: false,
        owners: [],
        layer: { mixed: true },
      },
    }
    render(<Harness />)
    expect(jumpButton()).toBeUndefined()
  })

  it("goes nowhere when the layer belongs to a cue rather than the programmer's stack", () => {
    // `ProvenanceEntry.layerId` is present for a cue's layers too. Landing the grid on a layer
    // this stack does not hold is the trap; `focusLayer` reports it and the click falls through.
    scopeState.current = { kind: 'output' }
    ownership.current = {
      dimmer: {
        source: 'cue',
        touched: false,
        isUniform: true,
        owners: [],
        layer: { layerId: 99, lookId: 3, name: 'A cue layer', mixed: false },
      },
    }
    render(<Harness />)
    fireEvent.click(jumpButton()!)
    expect(scopeActions.focusLayer).toHaveBeenCalledWith(99)
    expect(scopeActions.setScope).not.toHaveBeenCalled()
  })

  it('jumps a programmer-owned cell to Local, so every tint has a destination', () => {
    scopeState.current = { kind: 'output' }
    ownership.current = {
      dimmer: { source: 'programmer', touched: true, isUniform: true, owners: ['web'] },
    }
    render(<Harness />)
    fireEvent.click(jumpButton()!)
    expect(scopeActions.setScope).toHaveBeenCalledWith({ kind: 'local' })
  })

  it('offers no jump in Local scope — there is nowhere left to go', () => {
    scopeState.current = { kind: 'local' }
    ownership.current = {
      dimmer: {
        source: 'cue',
        touched: false,
        isUniform: true,
        owners: [],
        layer: { layerId: 7, lookId: 3, name: 'Warm Wash', mixed: false },
      },
    }
    render(<Harness />)
    expect(jumpButton()).toBeUndefined()
  })

  it('dashes a fixture outside the layer targets, and refuses the edit rather than widening them', () => {
    scopeState.current = { kind: 'layer', layerId: 7 }
    // The Look has a value for `a`; this layer's targets exclude it.
    lookStore.current = layerStore({
      serverRows: new Map([[lookRowKey('a', 'dimmer'), { kind: 'level', value: 128 }]]),
      targetedKeys: new Set(['somewhere-else']),
    })
    render(<Harness />)
    // The value still shows — "this Look has a value for it, this layer filters it out" is the
    // useful reading, and hiding it would make the layer look empty.
    expect(screen.getAllByText('50%').length).toBe(ROWS.length)
    expect(cellWrapper().className).toContain('border-dashed')
    // A marquee dragged across the grid must never quietly widen a layer to the whole rig.
    expect(cellWrapper().className).toContain('pointer-events-none')
  })
})

describe('FixturesTable with the desk unreachable', () => {
  it('makes the cells inert rather than taking edits that go nowhere', () => {
    // A cell edit is a `programmer.*` WS write, and the grid reads its values back from the
    // server — so with the socket down the drag does nothing and the cell snaps back with no
    // explanation. Inert says so up front.
    deskConnected.current = false
    render(<Harness />)
    expect(cellWrapper().className).toContain('pointer-events-none')
  })

  it('leaves a focused Look layer editable — that edit is a local draft, not a wire write', () => {
    deskConnected.current = false
    scopeState.current = { kind: 'layer', layerId: 7 }
    lookStore.current = layerStore({
      serverRows: new Map([[lookRowKey('a', 'dimmer'), { kind: 'level', value: 128 }]]),
    })
    render(
      <EditorContextProvider value={{ kind: 'lookLayer', layerId: 7, lookId: 3 }}>
        <Harness />
      </EditorContextProvider>,
    )
    expect(cellWrapper().className).not.toContain('pointer-events-none')
  })
})

/**
 * **Selection is neutral, ownership is colour** — space plan D4.
 *
 * These pin a *colour*, which normally is not worth a test. This one is: the whole session exists
 * because a selected row and a row whose values you own were both `--primary`, and the two facts
 * the grid most needs to keep apart looked alike. A well-meant "make the selection stand out more"
 * that reaches for the accent again would undo it silently — nothing else on this page would
 * break, and the failure only shows at a desk in a blacked-out room.
 *
 * They live here rather than in a programmer suite because `FixturesTable` is shared: D4 reaches
 * `/fixtures/list` and `/groups/list` too, which is intended — a selected row should look the same
 * everywhere.
 */
describe('FixturesTable neutral selection', () => {
  /** The row wrapper — the grid div carrying the wash. */
  const rowOf = (name: string) => screen.getByText(name).closest('.group\\/row')!

  function SelectedHarness() {
    const cellSelection = useCellSelection<ColumnKey>(new Set(ROWS.map((r) => r.id)))
    return (
      <FixturesTable
        rows={ROWS}
        visibleColumns={['dimmer'] as ColumnKey[]}
        isSelected={(id) => id === 'fixture:a'}
        onRowClick={() => {}}
        onToggleExpand={() => {}}
        onBeginCellEdit={onBeginCellEdit}
        onCellCommit={() => {}}
        batchFor={() => ({ count: 1, skipped: 0, resolutions: [] })}
        scopeLabel="Local"
        onShowInfo={() => {}}
        showOwnership
        cellSelection={cellSelection}
      />
    )
  }

  it('washes a selected row in foreground, never in the ownership accent', () => {
    render(<SelectedHarness />)
    const row = rowOf('SL Wash 1')
    expect(row.className).toContain('bg-foreground/[0.06]')
    expect(row.className).not.toContain('primary')
    // And an unselected row is untouched by any of it.
    expect(rowOf('SL Wash 2').className).not.toContain('bg-foreground')
  })

  it('draws the 3px edge on the sticky cell’s overlay, where the opaque background cannot hide it', () => {
    // On the row itself the inset shadow paints under the name cell's `bg-background` and is
    // invisible at every width — the same reason that overlay exists for the wash.
    render(<SelectedHarness />)
    const overlay = rowOf('SL Wash 1').querySelector('.pointer-events-none.absolute.inset-0')!
    expect(overlay.className).toContain('shadow-[inset_3px_0_0_var(--foreground)]')
  })

  it('bolds the name, and draws no checkbox to say it twice', () => {
    // The checkbox went when rows and cells became one selection: a drag from the name column
    // selects rows now, and a box beside a name that already says "selected" was a second
    // vocabulary for one fact.
    render(<SelectedHarness />)
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.getByText('SL Wash 1').className).toContain('font-semibold')
    expect(screen.getByText('SL Wash 2').className).not.toContain('font-semibold')
  })

  it('draws the marquee band in foreground, with no primary anywhere on it', () => {
    // The band is set from the pointer alone, which is the one part of the gesture jsdom can see
    // (its geometry is covered for real in `cellMarquee.test.ts`).
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, { buttons: 1, clientX: 380, clientY: 160 })

    const band = screen.getByTestId('cell-marquee')
    expect(band.className).toContain('border-foreground')
    expect(band.className).toContain('bg-foreground/5')
    expect(band.className).not.toContain('border-primary')
    expect(band.className).not.toContain('primary')
    // Both corner handles are foreground too — they were `bg-primary`.
    for (const handle of band.querySelectorAll('span')) {
      expect(handle.className).toContain('bg-foreground')
    }

    fireEvent.pointerUp(cell, { button: 0, clientX: 380, clientY: 160 })
  })

  it('reserves a marks gutter wide enough for the widest mark', () => {
    // D5: the effect badge and the layer glyph float over the value's last characters otherwise.
    // On the wrapper, so the ownership ring and the selection outline still trace the whole cell.
    //
    // 18px and not the plan's 16: the effect badge is `size-3.5` at `right-1`, i.e. 18px from the
    // edge, so a 16px gutter still let it overlap the value by 2px — the exact defect D5 removes.
    render(<Harness />)
    expect(cellWrapper().className).toContain('pr-[18px]')
  })
})

describe('FixturesTable sideways scroll fade', () => {
  const scroller = () => document.querySelector('.overflow-auto') as HTMLElement
  const fade = () => screen.queryByTestId('column-scroll-fade')

  /** jsdom lays nothing out, so the numbers the fade is derived from are stubbed. */
  function layOut(el: HTMLElement, scrollWidth: number, clientWidth: number) {
    Object.defineProperty(el, 'scrollWidth', { value: scrollWidth, configurable: true })
    Object.defineProperty(el, 'clientWidth', { value: clientWidth, configurable: true })
  }

  it('says there are more columns only while there are, and only to the right', () => {
    // Space plan D8: on a phone the value columns run off the right of the screen, and the fade
    // is the only thing that says so.
    render(<Harness />)
    const el = scroller()
    // Everything fits: no fade at all. A permanent gradient at a desk width would read as a
    // rendering fault rather than as a promise of more.
    expect(fade()).toBeNull()

    layOut(el, 900, 400)
    el.scrollLeft = 120
    fireEvent.scroll(el)
    expect(fade()).toBeTruthy()
    expect(fade()!.className).toContain('w-6')
    // Never eats a press on the last column.
    expect(fade()!.className).toContain('pointer-events-none')
    // On the scroller's WRAPPER, not inside it — which is the whole reason the wrapper exists.
    // Drawn within the scroller it would slide away with the columns it is describing.
    expect(fade()!.parentElement).toBe(el.parentElement)
    expect(el.contains(fade())).toBe(false)

    // Scrolled to the end, there is nothing left to promise.
    el.scrollLeft = 500
    fireEvent.scroll(el)
    expect(fade()).toBeNull()
  })

  it('reads nothing on a scroll that did not move the columns', () => {
    // `horizontalOnly` in `useScrollEdges`: this scroller is the virtualizer's as well, so most
    // scroll events on it are a fixture list moving vertically and say nothing about the columns.
    // Measuring on each of those is work on the one path this codebase treats as
    // performance-critical.
    render(<Harness />)
    const el = scroller()
    layOut(el, 900, 400)
    el.scrollLeft = 120
    fireEvent.scroll(el)
    expect(fade()).toBeTruthy()

    // A vertical tick. `scrollWidth` is re-defined to count reads *and* to report a width that
    // would hide the fade — so if the guard ever stops working, this fails twice over.
    let reads = 0
    Object.defineProperty(el, 'scrollWidth', {
      get() {
        reads += 1
        return 400
      },
      configurable: true,
    })
    fireEvent.scroll(el)
    expect(reads).toBe(0)
    expect(fade()).toBeTruthy()
  })
})

/**
 * The touch arm (`PD-MARQUEE-TOUCH`): a finger pans, and only a held one marquees. jsdom has no
 * touch behaviour of its own — no pan, no `pointercancel` from a scroller — so what is pinned here
 * is the arming rule: distance never arms a touch press, the hold does, and the browser's own
 * `touchmove` is refused only while a marquee is live.
 */
describe('FixturesTable touch', () => {
  const TOUCH = { pointerType: 'touch', button: 0, buttons: 1 }

  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    // A release schedules the click-swallow's own teardown on a timer. Under fake timers that
    // teardown would be discarded with the clock, leaving a capture-phase window listener to eat
    // the first click of whichever test runs next — so the pending timers run out first.
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('a finger that moves is a scroll — distance never arms a touch marquee', () => {
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { ...TOUCH, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, { ...TOUCH, clientX: 380, clientY: 160 })
    fireEvent.pointerMove(cell, { ...TOUCH, clientX: 460, clientY: 240 })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
    // The move also dropped the hold, so nothing arms later either.
    act(() => {
      vi.advanceTimersByTime(600)
    })
    fireEvent.pointerUp(cell, { ...TOUCH, buttons: 0, clientX: 460, clientY: 240 })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
  })

  it('a held finger arms the marquee, and selects the cell under it before it moves', () => {
    stubFlatLayout()
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { ...TOUCH, clientX: 300, clientY: 10 })
    act(() => {
      vi.advanceTimersByTime(499)
    })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
    act(() => {
      vi.advanceTimersByTime(2)
    })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true]])
    // Released without moving. The hold alone covered one cell — the proof that the zero-size
    // rectangle selected the cell under the finger.
    expect(cellCount()).toBe(1)
    fireEvent.pointerUp(cell, { ...TOUCH, buttons: 0, clientX: 300, clientY: 10 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true], [false]])
  })

  it('a pan reclaiming the touch before the hold fires disarms it', () => {
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { ...TOUCH, clientX: 300, clientY: 100 })
    fireEvent.pointerCancel(cell, { ...TOUCH, buttons: 0 })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
  })

  it('a finger that leaves the rows before the hold fires has held nothing', () => {
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { ...TOUCH, clientX: 300, clientY: 100 })
    fireEvent.pointerLeave(cell, { ...TOUCH })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
  })

  it('refuses the browser’s scroll only while a marquee is live', () => {
    stubFlatLayout()
    render(<Harness />)
    const cell = cellButton()
    const scroller = cell.closest('.overflow-auto')!
    // Before the hold the touch is the browser's: a touchmove scrolls.
    fireEvent.pointerDown(cell, { ...TOUCH, clientX: 300, clientY: 10 })
    expect(fireEvent.touchMove(scroller)).toBe(true)
    act(() => {
      vi.advanceTimersByTime(501)
    })
    // Live: the pan is refused, so the finger draws the rectangle rather than scrolling it away.
    expect(fireEvent.touchMove(scroller)).toBe(false)
    fireEvent.pointerUp(cell, { ...TOUCH, buttons: 0, clientX: 300, clientY: 10 })
    // Released: the browser has its scroll back.
    expect(fireEvent.touchMove(scroller)).toBe(true)
  })
})

/**
 * `PD-CLEAR-SELECTION-TOUCH`: a click on the grid's own empty background is reported, so the
 * container can run its Escape ladder on a device that has no Escape.
 */
describe('FixturesTable background click', () => {
  it('a click on the empty grid reports it; a click on a row does not', () => {
    render(<Harness />)
    const cell = cellButton()
    const scroller = cell.closest('.overflow-auto')!
    fireEvent.click(cell)
    expect(onBackgroundClick).not.toHaveBeenCalled()
    fireEvent.click(scroller)
    expect(onBackgroundClick).toHaveBeenCalledTimes(1)
  })

  it('the click that ends a marquee is not a background click', () => {
    // A drag released over empty space must not clear the selection it just made.
    render(<Harness />)
    const cell = cellButton()
    const scroller = cell.closest('.overflow-auto')!
    fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: 100 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 380, clientY: 160 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 380, clientY: 160 })
    fireEvent.click(scroller)
    expect(onBackgroundClick).not.toHaveBeenCalled()
  })
})

describe('FixturesTable background click, the two false positives', () => {
  it('ignores a click inside a portalled cell editor — React bubbles it here through the portal', () => {
    // Every cell editor is a Radix popover portalled to `body`. The synthetic click still reaches
    // the scroller's handler up the *React* tree, and read as background it would drop the
    // marquee the editor is committing to.
    render(<Harness keyboardOpen={{ rowId: 'fixture:a', col: 'dimmer' as ColumnKey, seed: '', atButton: true }} />)
    const editor = document.querySelector('[data-radix-popper-content-wrapper]')
    expect(editor).not.toBeNull()
    fireEvent.click(editor!.firstElementChild ?? editor!)
    expect(onBackgroundClick).not.toHaveBeenCalled()
  })

  it('ignores a click on a divider row — "Ungrouped" is a row, not empty space', () => {
    render(
      <Harness
        rows={[...ROWS, { kind: 'divider', id: 'divider:ungrouped', label: 'Ungrouped' } as unknown as Row]}
      />,
    )
    fireEvent.click(screen.getByText('Ungrouped'))
    expect(onBackgroundClick).not.toHaveBeenCalled()
  })
})

describe('FixturesTable two fingers', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('a second finger drops a pending hold rather than arming early against it', () => {
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { pointerType: 'touch', pointerId: 1, button: 0, buttons: 1, clientX: 300, clientY: 20 })
    act(() => {
      vi.advanceTimersByTime(300)
    })
    fireEvent.pointerDown(cell, { pointerType: 'touch', pointerId: 2, button: 0, buttons: 1, clientX: 380, clientY: 50 })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
    fireEvent.pointerUp(cell, { pointerType: 'touch', pointerId: 2, button: 0, buttons: 0 })
    fireEvent.pointerUp(cell, { pointerType: 'touch', pointerId: 1, button: 0, buttons: 0 })
    expect(onMarqueeDragChange).not.toHaveBeenCalled()
  })

  it('lifting a second finger does not end the marquee the first one owns', () => {
    stubFlatLayout()
    render(<Harness />)
    const cell = cellButton()
    fireEvent.pointerDown(cell, { pointerType: 'touch', pointerId: 1, button: 0, buttons: 1, clientX: 300, clientY: 10 })
    act(() => {
      vi.advanceTimersByTime(501)
    })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true]])
    fireEvent.pointerDown(cell, { pointerType: 'touch', pointerId: 2, button: 0, buttons: 1, clientX: 380, clientY: 50 })
    fireEvent.pointerUp(cell, { pointerType: 'touch', pointerId: 2, button: 0, buttons: 0, clientX: 380, clientY: 50 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true]])
    fireEvent.pointerUp(cell, { pointerType: 'touch', pointerId: 1, button: 0, buttons: 0, clientX: 300, clientY: 10 })
    expect(onMarqueeDragChange.mock.calls).toEqual([[true], [false]])
  })
})
