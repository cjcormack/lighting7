// @vitest-environment jsdom
import { cueRow, markerRow } from '@/test/cueEntries'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack, CueStackCueEntry, CueStackCueRow, CueStackMarkerRow, StackMoveInDarkPlan } from '@/api/cueStacksApi'
import type { MoveInDarkHead } from '@/api/moveInDarkApi'

/**
 * The cue sheet's own rules on the kit (CLAUDE.md §Sheet kit), and above all **the lock as the
 * sheet's read-only scope**: locked, every value cell is inert in the four places the programmer's
 * Output scope is — the wrapper's `pointer-events-none`, the trigger's `disabled`, the keyboard
 * through the permission, and the bar's Set · Clear · Spread with the reason — while the marquee
 * still works and a click on the row head arms the cue as next. Unlocked, a cell writes one
 * PATCH per cue, and Spread on Fade spreads first→last.
 */
const patchCue = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/cueHolds', () => ({ useCueEdited: () => false }))
vi.mock('@/store/cues', () => ({ usePatchProjectCueMutation: () => [patchCue] }))
const patchEntry = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const renumberEntries = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
// The stack's Move in dark plan and what it is moving now — the *Dark* column's two sources.
const dark: { plan: StackMoveInDarkPlan | undefined; heads: MoveInDarkHead[] } = { plan: undefined, heads: [] }
vi.mock('@/store/cueStacks', () => ({
  usePatchCueStackEntryMutation: () => [patchEntry],
  useRenumberCueStackEntriesMutation: () => [renumberEntries, { isLoading: false }],
  useCueStackMoveInDarkQuery: () => ({ data: dark.plan }),
}))
vi.mock('@/store/moveInDark', () => ({ useMoveInDarkHeads: () => dark.heads }))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
/** What the bar hands the cue action set — the actions themselves are `CueActions.test.tsx`'s. */
const cueActions: { last?: Record<string, unknown> } = {}
vi.mock('./CueActions', () => ({
  CueActions: (props: Record<string, unknown>) => {
    cueActions.last = props
    return <div data-testid="cue-actions" />
  },
}))
// The scene the Scenery column names its elements from, and its editor — the editor's own rows are
// `SceneryEditor.test.tsx`'s; here it says only which cue it was opened on.
const SCENE = [
  { uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', layer: 'VENUE', positionZ: 0, params: { operation: 'DRAW' } },
  { uuid: 'moon', name: 'Moon', kind: 'OBJECT', layer: 'SET', positionZ: 3, params: { flies: true, states: { trimM: 5 } } },
]
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: SCENE }) }))
vi.mock('@/components/scenery/CueSceneryEditor', () => ({
  CueSceneryEditor: ({ cue }: { cue: { id: number } | null }) =>
    cue == null ? null : <div data-testid="scenery-editor">editing {cue.id}</div>,
}))
// The pane's editor — `CueEditor.test.tsx`'s; here it says which cue and entry it was opened on.
vi.mock('@/components/cues/editor/CueEditor', () => ({
  CueEditor: (props: { cueId: number; entryId: number; host: string; locked: boolean; tabRequest?: { tab: string; section?: string } | null }) => (
    <div
      data-cue-editor={props.cueId}
      data-cue-editor-host={props.host}
      data-entry={props.entryId}
      data-locked={String(props.locked)}
      data-tab={props.tabRequest?.tab ?? ''}
      data-section={props.tabRequest?.section ?? ''}
    >
      <button type="button" data-testid="pane-control">a control in the editor</button>
    </div>
  ),
}))
// The row drag's handlers, as the sheet hands them to dnd-kit — a jsdom row has no geometry for a
// sensor to drag across, so a test plays the drag's events into the sheet's own handlers.
const dnd: { last?: Record<string, (event: unknown) => void> } = {}
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: (props: React.ComponentProps<typeof actual.DndContext>) => {
      dnd.last = props as unknown as Record<string, (event: unknown) => void>
      return <actual.DndContext {...props} />
    },
  }
})
const virtual = vi.hoisted(() => ({ scrollToIndex: vi.fn() }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({
        index,
        key: index,
        start: index * estimateSize(),
        size: estimateSize(),
      })),
    scrollToIndex: virtual.scrollToIndex,
  }),
}))

import { CueSheet } from './CueSheet'
import { cuePaneHeightStore, cuePaneOpenStore } from './CueEditorPane'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

/** A cue row of the stack, or — with `cueType: 'MARKER'` — a marker entry, its entry id `id + 100`. */
function cue(id: number, over?: Partial<CueStackCueRow>): CueStackCueRow
function cue(id: number, over: { cueType: 'MARKER'; name?: string }): CueStackMarkerRow
function cue(
  id: number,
  over: Partial<CueStackCueRow> | { cueType: 'MARKER'; name?: string } = {},
): CueStackCueEntry {
  if (over.cueType === 'MARKER') return markerRow(id + 100, over.name ?? `Cue ${id}`, { sortOrder: id })
  return cueRow(id, { name: `Cue ${id}`, sortOrder: id, fadeDurationMs: 1000, cueNumber: String(id), ...(over as Partial<CueStackCueRow>) })
}

/** The reason a locked, unliftable sheet gives on its disabled verbs. */
const LOCKED_TITLE = 'Locked — cells are read-only · L to edit'

const STACK: CueStack = {
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cue(1), cue(2), cue(3, { cueType: 'MARKER', name: 'Interval' }), cue(4), cue(5)],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
}

function draw(over: Partial<React.ComponentProps<typeof CueSheet>> = {}) {
  return render(<CueSheet stack={STACK} projectId={1} activeEntryId={null} {...over} />)
}

/** The row head spans 0..64, Cue # 64..140, Name 140..300, Fade 300..388 — rows are 36px from a 0-high header. */
function stubFlatLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  const bands: Record<string, [number, number]> = {
    cueNumber: [64, 140],
    name: [140, 300],
    fade: [300, 388],
    curve: [388, 506],
    follow: [506, 602],
    book: [602, 678],
    layers: [678, 754],
    fx: [754, 810],
    notes: [810, 1010],
    dark: [1010, 1122],
    scenery: [1122, 1318],
  }
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 64)
    const col = this.getAttribute('data-column-header')
    if (col && bands[col]) return rect(...bands[col])
    return rect(0, 1100)
  })
}

/** The row of cue (or marker) `cueId` — keyed by its entry, `cueId + 100` in these fixtures. */
function row(cueId: number): HTMLElement {
  return document.querySelector(`[data-row-id="entry:${cueId + 100}"]`) as HTMLElement
}
function fadeCell(cueId: number): HTMLElement {
  return within(row(cueId)).getAllByRole('button').find((b) => b.closest('[data-cell="fade"]'))!
}
function numberCell(cueId: number): HTMLElement {
  return row(cueId).querySelector('[data-cell="cueNumber"] button') as HTMLElement
}

/**
 * A marquee down the Cue # column over the drawn rows `from`..`to`. The cell is asked for again after
 * the move: over several cells it is the renumber editor's trigger, a new node, and jsdom has no
 * pointer capture to carry the release to the rows wrapper as a browser does.
 */
function dragNumbers(from: number, to: number) {
  const cueId = STACK.cues[from].entryId - 100
  fireEvent.pointerDown(numberCell(cueId), { button: 0, clientX: 100, clientY: from * 36 + 10 })
  fireEvent.pointerMove(numberCell(cueId), { button: 0, buttons: 1, clientX: 110, clientY: to * 36 + 26 })
  fireEvent.pointerUp(numberCell(cueId), { button: 0, clientX: 110, clientY: to * 36 + 26 })
  fireEvent.click(numberCell(cueId))
}

/** A marquee down the Fade column over the drawn rows `from`..`to`. */
function dragFade(from: number, to: number) {
  const cell = fadeCell(STACK.cues[from].entryId - 100)
  fireEvent.pointerDown(cell, { button: 0, clientX: 340, clientY: from * 36 + 10 })
  fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 350, clientY: to * 36 + 26 })
  fireEvent.pointerUp(cell, { button: 0, clientX: 350, clientY: to * 36 + 26 })
  fireEvent.click(cell)
}

beforeEach(() => {
  stubFlatLayout()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  resetEditorSurfaceMedia()
  sessionStorage.clear()
  cuePaneOpenStore.reset()
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  patchCue.mockClear()
  virtual.scrollToIndex.mockClear()
  patchEntry.mockClear()
  renumberEntries.mockClear()
})

describe('CueSheet', () => {
  it('draws one row per cue and a marker as a divider', () => {
    draw()
    expect(row(1)).toBeInTheDocument()
    expect(row(3)).toHaveTextContent('Interval')
    expect(within(row(3)).queryByRole('button')).toBeNull()
    expect(screen.getByText('4 cues · 1 marker')).toBeInTheDocument()
  })

  it('keeps a read-out on one line, so a long book position cannot grow the row', () => {
    // `bottom of p. 12` is 85px of text in a 108px track. Wrapped, the cell was 60px tall inside a
    // 36px row and painted over the row below it — the defect the show table was reported for.
    draw({ locationByCue: new Map([[1, 'bottom of p. 12']]) })
    const book = within(row(1)).getByText('bottom of p. 12')
    expect(book.className).toContain('truncate')
    expect(book.closest('button')!.className).toContain('whitespace-nowrap')
    expect(book.closest('[data-cell], div')!.className).toContain('overflow-hidden')
    // The whole label is still reachable, on the read-out's own hover.
    expect(book.closest('button')).toHaveAttribute('title', expect.stringContaining('bottom of p. 12'))
  })

  it('the cue number does not inflate the row — the display box is inline-block', () => {
    // Left inline, `TruncateStart`'s blocks made the line box 59px tall for a 20px number, and as the
    // grid row's tallest min-content that became the *track*: every cell in the row sat below it.
    draw({ locked: true })
    const number = within(row(1)).getByTitle('Cue number')
    expect(number.className).toContain('inline-block')
  })

  it('marks a cue another stack shares, on its name', () => {
    const places = new Map([
      [
        2,
        [
          { stackId: 10, stackName: 'Act 1', entryId: 102, cueNumber: '2' },
          { stackId: 11, stackName: 'Act 2', entryId: 207, cueNumber: '7' },
        ],
      ],
    ])
    draw({ placesByCue: places })
    const chip = within(row(2)).getByRole('img', { name: /in 2 stacks/ })
    expect(chip).toHaveAttribute('title', 'Also Act 2 · Q7')
    expect(within(row(1)).queryByRole('img', { name: /stacks/ })).toBeNull()
  })

  it('leaves a cell it cannot select blank rather than drawing an em-dash', () => {
    // A snap cue has no curve to set and the read-outs are not cells at all; the em-dash belongs to
    // an empty cell you CAN set, which is what Follow and Notes still draw.
    draw({ stack: { ...STACK, cues: [cue(1, { fadeDurationMs: null }), cue(2)] } })
    // Columns after the sticky row head: cue #, name, fade, curve, follow, dark, book, layers, fx, …
    const cell = (i: number) => row(1).children[i] as HTMLElement
    // A snap cue's Curve has nothing to set, so it is not a cell at all — and it draws nothing.
    expect(row(1).querySelector('[data-cell="curve"]')).toBeNull()
    expect(cell(4).textContent).toBe('')
    // Book, Layers and FX are read-outs with nothing to show, so they show nothing.
    expect(cell(6).textContent).toBe('')
    expect(cell(7).textContent).toBe('')
    expect(cell(8).textContent).toBe('')
    // Follow and Notes keep their em-dash: they are empty cells, not absent ones.
    expect(row(1).querySelector('[data-cell="follow"]')?.textContent).toContain('—')
    expect(row(1).querySelector('[data-cell="notes"]')?.textContent).toContain('—')
  })

  it('the Book read-out opens the Prompt Book, not the cue', () => {
    const onOpenBook = vi.fn()
    const { container } = draw({ locationByCue: new Map([[1, 'top of p. 8']]), onOpenBook })
    fireEvent.click(within(row(1)).getByText('top of p. 8').closest('button')!)
    expect(onOpenBook).toHaveBeenCalledWith(1)
    expect(container.querySelector('[data-cue-pane]')).toHaveAttribute('data-cue-pane-state', 'none')
  })

  it('locked: a refused edit offers to unlock instead of doing nothing', async () => {
    const onRequestUnlock = vi.fn()
    draw({ locked: true, onRequestUnlock })
    dragFade(0, 1)
    // The verbs stay live and say why rather than sitting there greyed out.
    const set = screen.getByRole('button', { name: 'Set' })
    expect(set).not.toBeDisabled()
    fireEvent.click(set)
    expect(await screen.findByText('Unlock the show to edit?')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Unlock' }))
    expect(onRequestUnlock).toHaveBeenCalled()
    // …and no cell editor was opened behind it.
    expect(screen.queryByLabelText('Fade')).not.toBeInTheDocument()
  })

  it('locked: Enter offers the unlock, and the transport’s own keys are left alone', () => {
    const onRequestUnlock = vi.fn()
    draw({ locked: true, onRequestUnlock })
    dragFade(0, 1)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.getByText('Unlock the show to edit?')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Stay locked' }))

    // Backspace is BACK on a locked show and `l` is the lock toggle — the keyboard's own way back
    // to editing. Both read `defaultPrevented`, so the sheet must neither claim them nor answer
    // them with a dialog that would sit over whatever they did.
    for (const key of ['Backspace', 'l']) {
      const e = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true })
      window.dispatchEvent(e)
      expect(e.defaultPrevented).toBe(false)
      expect(screen.queryByText('Unlock the show to edit?')).not.toBeInTheDocument()
    }
  })

  it('without a way out, the refused verbs stay disabled and say why', () => {
    draw({ locked: true })
    dragFade(0, 1)
    expect(screen.getByRole('button', { name: 'Set' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Set' })).toHaveAttribute('title', LOCKED_TITLE)
  })

  it('unlocked: every row carries a reorder grip; locked, none does', () => {
    const onReorder = vi.fn()
    const { rerender } = render(
      <CueSheet stack={STACK} projectId={1} activeEntryId={null} onReorder={onReorder} />,
    )
    // Cues and separators alike — a marker is a row of the stack and moves with them.
    expect(screen.getAllByRole('button', { name: 'Reorder row' })).toHaveLength(STACK.cues.length)
    rerender(
      <CueSheet stack={STACK} projectId={1} activeEntryId={null} onReorder={onReorder} locked />,
    )
    expect(screen.queryAllByRole('button', { name: 'Reorder row' })).toHaveLength(0)
  })

  it('positions a draggable row with `top`, so dnd-kit can measure where it is', () => {
    // The virtualiser's usual `transform: translateY()` is what a sortable row cannot use: dnd-kit
    // measures droppables with transforms discounted, so rows positioned only by a transform all
    // measure at the container's origin. Every centre-distance then ties, `closestCenter`'s stable
    // sort hands back the rows in DOM order on every frame, and the drop lands on the row the drag
    // started from. It is not a dead drag either way, which is what made it easy to miss: dragging
    // *up* still worked, because for an upward drag DOM order and distance order agree.
    render(
      <CueSheet stack={STACK} projectId={1} activeEntryId={null} onReorder={vi.fn()} />,
    )
    const wrappers = [...document.querySelectorAll('[data-row-id]')].map((r) => r.parentElement!)
    expect(wrappers.map((w) => w.style.top)).toEqual(['0px', '36px', '72px', '108px', '144px'])
    for (const w of wrappers) expect(w.style.transform).not.toContain('translateY')
  })

  it('washes the live row green and the next row blue, as the cards do', () => {
    draw({ activeEntryId: 102, standbyEntryId: 104 })
    expect(row(2).className).toContain('green')
    expect(row(4).className).toContain('blue')
    expect(row(1).className).not.toContain('green')
  })

  it('unlocked: a cell writes one PATCH per selected cue', async () => {
    draw()
    // Rows 0 and 1 are cues 1 and 2.
    dragFade(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Fade')
    fireEvent.change(field, { target: { value: '2.5s' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 1, fadeDurationMs: 2500 })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 2, fadeDurationMs: 2500 })
    expect(patchCue).toHaveBeenCalledTimes(2)
  })

  it('unlocked: a commit reaches only the columns of its own kind — a fade never becomes a follow or a note', async () => {
    // Name, Fade, Curve, Follow and Notes all take a string, so the kit's shape test cannot tell
    // them apart. A marquee from Fade across to Notes (rows 0 and 1), then `3s` typed into the
    // Fade editor: three fades move, no cue starts auto-advancing, no note is overwritten.
    draw()
    const cell = fadeCell(1)
    fireEvent.pointerDown(cell, { button: 0, clientX: 340, clientY: 10 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 900, clientY: 62 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 900, clientY: 62 })
    fireEvent.click(cell)
    // Fade, Curve, Follow and Notes for two cues; Book, Layers and FX are read-outs and are never selected.
    expect(screen.getByText('8 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Fade')
    fireEvent.change(field, { target: { value: '3s' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 1, fadeDurationMs: 3000 })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 2, fadeDurationMs: 3000 })
    expect(patchCue).toHaveBeenCalledTimes(2)
    for (const call of patchCue.mock.calls as unknown as Record<string, unknown>[][]) {
      expect(call[0]).not.toHaveProperty('autoAdvance')
      expect(call[0]).not.toHaveProperty('notes')
      expect(call[0]).not.toHaveProperty('fadeCurve')
    }
  })

  it("skips a snap cue's Curve: the marquee covers it, the write never sees it, and the editor says so", async () => {
    // A snap cue has no curve to set (`value: undefined`), but a marquee is geometric and sweeps
    // its Curve cell up anyway. The kit drops it before `write` (library-sheets plan D12) and the
    // editor's read-out names it — before this, the column's `write` received it and patched a
    // curve onto a cue with no fade.
    const stack = { ...STACK, cues: STACK.cues.map((c) => (c.id === 2 ? { ...c, fadeDurationMs: null } : c)) }
    render(<CueSheet stack={stack} projectId={1} activeEntryId={null} />)
    const cell = within(row(1)).getAllByRole('button').find((b) => b.closest('[data-cell="curve"]'))!
    fireEvent.pointerDown(cell, { button: 0, clientX: 440, clientY: 10 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 450, clientY: 62 })
    fireEvent.pointerUp(cell, { button: 0, clientX: 450, clientY: 62 })
    fireEvent.click(cell)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(await screen.findByText('Q2 snaps · skipped')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('option', { name: 'Ease In' }))
    expect(patchCue).toHaveBeenCalledTimes(1)
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 1, fadeCurve: 'EASE_IN' })
  })

  it('selects and scrolls to the cue the URL names — the ?cue= contract holds on the sheet', () => {
    draw({ openedEntryId: 104 })
    expect(row(4)).toHaveAttribute('data-state', 'selected')
    expect(row(1)).not.toHaveAttribute('data-state', 'selected')
    expect(screen.getByText('1 cue')).toBeInTheDocument()
  })

  it('consumes the deep link once — a refetched stack does not wipe a marquee made since', () => {
    // Every cell commit refetches the stack and every GO re-mints its cues; neither is an arrival.
    const view = render(
      <CueSheet stack={STACK} projectId={1} activeEntryId={null} openedEntryId={104} />,
    )
    expect(row(4)).toHaveAttribute('data-state', 'selected')
    dragFade(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    view.rerender(
      <CueSheet
        stack={{ ...STACK, cues: STACK.cues.map((c) => ({ ...c })), activeCueId: 2 }}
        projectId={1}
        activeEntryId={102}
        openedEntryId={104}
      />,
    )
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    expect(row(4)).not.toHaveAttribute('data-state', 'selected')
    // A new id is a new arrival.
    view.rerender(
      <CueSheet stack={STACK} projectId={1} activeEntryId={null} openedEntryId={105} />,
    )
    expect(row(5)).toHaveAttribute('data-state', 'selected')
    expect(screen.queryByText('2 cells')).toBeNull()
  })

  it('unlocked: Spread on Fade spreads first→last over the selection, in visible order', async () => {
    draw()
    // Rows 3 and 4 are cues 4 and 5 (row 2 is the marker, which a rectangle skips).
    dragFade(0, 4)
    expect(screen.getByText('4 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Fan' }))
    const from = await screen.findByLabelText('From')
    fireEvent.change(from, { target: { value: '1s' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '4s' } })
    expect(screen.getByText(/Q1 1.0s · Q2 2.0s · Q4 3.0s · Q5 4.0s/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    // Q1 is already at 1s, so three of the four move.
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 2, fadeDurationMs: 2000 })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 4, fadeDurationMs: 3000 })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 5, fadeDurationMs: 4000 })
    expect(patchCue).toHaveBeenCalledTimes(3)
  })

  it('locked: every value cell is inert in all four places, and the marquee still works', async () => {
    draw({ locked: true, onSetStandby: () => {} })
    // 1. The pointer: the wrapper takes no events. 2. The keyboard's own door: the trigger is
    // disabled, so Tab-then-Enter opens nothing.
    const cell = fadeCell(1)
    expect(cell).toBeDisabled()
    expect(cell.closest('[data-cell]')!.className).toContain('pointer-events-none')
    // The marquee arms in every scope — its press sits on the rows wrapper.
    dragFade(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    expect(screen.getByText(/Locked — cells are read-only/)).toBeInTheDocument()
    // 3. The bar's verbs, with the reason. 4. The window keyboard, refused through the permission.
    for (const name of ['Set', 'Clear cells', 'Fan']) {
      const button = screen.getByRole('button', { name })
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('title', expect.stringContaining('Locked'))
    }
    await act(async () => {
      fireEvent.keyDown(window, { key: 'Enter' })
      fireEvent.keyDown(window, { key: '2' })
      fireEvent.keyDown(window, { key: 'Backspace' })
    })
    expect(patchCue).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Fade')).toBeNull()
  })

  it('locked: a click on the row head arms the cue as next', () => {
    const onSetStandby = vi.fn()
    draw({ locked: true, activeEntryId: 101, onSetStandby })
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    expect(onSetStandby).toHaveBeenCalledWith(2, 102)
    // The live cue cannot be armed against itself.
    fireEvent.click(row(1).firstElementChild as HTMLElement)
    expect(onSetStandby).toHaveBeenCalledTimes(1)
  })

  it('unlocked: a click on the row head selects the row rather than arming', () => {
    const onSetStandby = vi.fn()
    draw({ onSetStandby })
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    expect(onSetStandby).not.toHaveBeenCalled()
    expect(screen.getByText('1 cue')).toBeInTheDocument()
  })

  it('leaves the transport keys alone: Space is not a sheet key, and a locked Backspace is not claimed', () => {
    draw({ locked: true, onSetStandby: () => {} })
    dragFade(0, 1)
    const backspace = new KeyboardEvent('keydown', { key: 'Backspace', code: 'Backspace', bubbles: true, cancelable: true })
    window.dispatchEvent(backspace)
    expect(backspace.defaultPrevented).toBe(false)
    const space = new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true, cancelable: true })
    window.dispatchEvent(space)
    expect(space.defaultPrevented).toBe(false)
  })
})

describe('CueSheet · following the show (venue-rig-fixes plan D10, D12)', () => {
  /** Over the flat layout: a 100px scroller, Q1 and Q2 inside it and every later row below it. */
  function stubScroller() {
    const spy = vi.mocked(Element.prototype.getBoundingClientRect)
    const base = spy.getMockImplementation()!
    spy.mockImplementation(function (this: Element) {
      const box = (top: number, height: number) => ({ left: 0, top, right: 1100, bottom: top + height, width: 1100, height, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
      if (this.hasAttribute('data-sheet-scroller')) return box(0, 100)
      const id = this.getAttribute('data-row-id')
      if (id != null) return Number(id.slice('entry:'.length)) <= 102 ? box(30, 36) : box(400, 36)
      return base.call(this)
    })
    vi.spyOn(Element.prototype, 'clientHeight', 'get').mockImplementation(function (this: Element) {
      return this.hasAttribute('data-sheet-scroller') ? 100 : 0
    })
  }
  beforeEach(stubScroller)

  it('centres the live row on entering a running stack', () => {
    draw({ activeEntryId: 104 })
    expect(virtual.scrollToIndex).toHaveBeenCalledExactlyOnceWith(3, { align: 'center' })
  })

  it('a GO to a row outside the sheet centres it, and one to a row in view does not', () => {
    const view = draw({ activeEntryId: 101 })
    virtual.scrollToIndex.mockClear()
    view.rerender(<CueSheet stack={STACK} projectId={1} activeEntryId={102} />)
    expect(virtual.scrollToIndex).not.toHaveBeenCalled()
    view.rerender(<CueSheet stack={STACK} projectId={1} activeEntryId={102} standbyEntryId={105} />)
    expect(virtual.scrollToIndex).toHaveBeenCalledExactlyOnceWith(4, { align: 'center' })
  })
})

describe('CueSheet · Duplicate stays put (venue-rig-fixes plan D12)', () => {
  it('selects Duplicate’s copies once the refetch draws them, scrolls to the first, and leaves the pane off', () => {
    cuePaneOpenStore.set(false)
    const view = draw()
    const onDuplicated = cueActionsOf().onDuplicated as (ids: number[]) => void
    act(() => onDuplicated([106, 107]))
    expect(virtual.scrollToIndex).not.toHaveBeenCalled()
    view.rerender(<CueSheet stack={{ ...STACK, cues: [...STACK.cues, cue(6), cue(7)] }} projectId={1} activeEntryId={null} />)
    expect(row(6)).toHaveAttribute('data-state', 'selected')
    expect(row(7)).toHaveAttribute('data-state', 'selected')
    expect(virtual.scrollToIndex).toHaveBeenCalledExactlyOnceWith(5, { align: 'center' })
    expect(cuePaneOpenStore.getSnapshot()).toBe(false)
  })

  /** The props the bar hands the action set, once Q1 and Q2's fades are selected to draw it. */
  function cueActionsOf(): Record<string, unknown> {
    cueActions.last = undefined
    dragFade(0, 1)
    return cueActions.last!
  }
})

describe('CueSheet · Cue # (show-view-fixes plan D7–D12)', () => {
  it('is a cell column after the row head, which keeps the grip and the pip', () => {
    draw({ onReorder: vi.fn() })
    expect(row(1).firstElementChild).toHaveAttribute('data-first-column')
    expect(within(row(1).firstElementChild as HTMLElement).getByLabelText('Reorder row')).toBeInTheDocument()
    expect(row(1).querySelector('[data-first-column] [data-entry-row="101"]')).not.toBeNull()
    expect(row(1).children[1]).toHaveAttribute('data-cell', 'cueNumber')
    expect(within(row(1).firstElementChild as HTMLElement).queryByText('Q1')).toBeNull()
  })

  it('one cell edits the number: a click selects the cell, a double click opens the field, Enter asks where it goes', () => {
    draw()
    fireEvent.click(numberCell(1))
    expect(screen.getByText('1 cell')).toBeInTheDocument()
    expect(screen.queryByLabelText('Cue #')).not.toBeInTheDocument()
    fireEvent.doubleClick(numberCell(1))
    const field = screen.getByLabelText('Cue #') as HTMLInputElement
    expect(field.value).toBe('1')
    fireEvent.change(field, { target: { value: '12.5' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(patchEntry).toHaveBeenCalledWith({ projectId: 1, stackId: 10, entryId: 101, cueId: 1, cueNumber: '12.5', placement: 'ASK' })
    expect(patchCue).not.toHaveBeenCalled()
    expect(renumberEntries).not.toHaveBeenCalled()
  })

  it('an auto number opens empty: there is no typed number to edit', () => {
    const stack = { ...STACK, cues: STACK.cues.map((c) => (c.id === 2 ? { ...c, cueNumberAuto: true } : c)) }
    render(<CueSheet stack={stack} projectId={1} activeEntryId={null} />)
    fireEvent.doubleClick(numberCell(2))
    expect((screen.getByLabelText('Cue #') as HTMLInputElement).value).toBe('')
  })

  it('four cells and a typed 20 send one renumber of those cues in stack order', async () => {
    draw()
    // Rows 0..4 are Q1, Q2, the marker, Q4 and Q5; a rectangle skips the marker.
    dragNumbers(0, 4)
    expect(screen.getByText('4 cells')).toBeInTheDocument()
    await act(async () => {
      fireEvent.keyDown(window, { key: '2' })
    })
    expect(screen.getByText('Renumber 4 cues')).toBeInTheDocument()
    const first = screen.getByLabelText('First') as HTMLInputElement
    expect(first.value).toBe('2')
    fireEvent.change(first, { target: { value: '20' } })
    expect(screen.getByText('Q20, Q21, Q22, Q23')).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Renumber' }))
    })
    expect(renumberEntries).toHaveBeenCalledTimes(1)
    expect(renumberEntries).toHaveBeenCalledWith({ projectId: 1, stackId: 10, entryIds: [101, 102, 104, 105], start: 20, step: 1 })
    expect(patchEntry).not.toHaveBeenCalled()
    expect(screen.queryByText('Renumber 4 cues')).toBeNull()
  })

  it('the renumber form is the kit’s: First focused and selected, a comma moves to Step, Enter renumbers', async () => {
    draw()
    dragNumbers(0, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const first = (await screen.findByLabelText('First')) as HTMLInputElement
    expect(document.activeElement).toBe(first)
    expect([first.selectionStart, first.selectionEnd]).toEqual([0, first.value.length])
    fireEvent.change(first, { target: { value: '30' } })
    fireEvent.keyDown(first, { key: ',' })
    const step = screen.getByLabelText('Step') as HTMLInputElement
    expect(document.activeElement).toBe(step)
    fireEvent.change(step, { target: { value: '10' } })
    await act(async () => {
      fireEvent.keyDown(step, { key: 'Enter' })
    })
    expect(renumberEntries).toHaveBeenCalledTimes(1)
    expect(renumberEntries).toHaveBeenCalledWith({ projectId: 1, stackId: 10, entryIds: [101, 102], start: 30, step: 10 })
  })

  it('a letter typed at the grid does not seed First', async () => {
    draw()
    dragNumbers(0, 1)
    await act(async () => {
      fireEvent.keyDown(window, { key: 'q' })
    })
    expect((screen.getByLabelText('First') as HTMLInputElement).value).toBe('1')
  })

  it('Set over several starts from the first cue’s whole number', async () => {
    const stack = { ...STACK, cues: STACK.cues.map((c) => (c.id === 1 ? { ...c, cueNumber: '7.5' } : c)) }
    render(<CueSheet stack={stack} projectId={1} activeEntryId={null} />)
    dragNumbers(0, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(((await screen.findByLabelText('First')) as HTMLInputElement).value).toBe('7')
  })

  it('refuses a decimal over several, with the form’s reason', async () => {
    draw()
    dragNumbers(0, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.change(await screen.findByLabelText('First'), { target: { value: '2.5' } })
    expect(screen.getByText('The first number is a whole number, 0 or more')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Renumber' })).toBeDisabled()
    fireEvent.submit(screen.getByLabelText('First').closest('form')!)
    expect(renumberEntries).not.toHaveBeenCalled()
  })

  it('Clear returns each typed number to auto, one PATCH per entry, and leaves an auto number alone', () => {
    const stack = { ...STACK, cues: STACK.cues.map((c) => (c.id === 2 ? { ...c, cueNumberAuto: true } : c)) }
    render(<CueSheet stack={stack} projectId={1} activeEntryId={null} />)
    dragNumbers(0, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    expect(patchEntry).toHaveBeenCalledWith({ projectId: 1, stackId: 10, entryId: 101, cueId: 1, cueNumber: null })
    expect(patchEntry).toHaveBeenCalledWith({ projectId: 1, stackId: 10, entryId: 104, cueId: 4, cueNumber: null })
    expect(patchEntry).toHaveBeenCalledTimes(2)
  })

  it('offers no Spread on the column', () => {
    draw()
    dragNumbers(0, 1)
    expect(screen.getByRole('button', { name: 'Fan' })).toBeDisabled()
  })

  it('a Fade commit over a Cue # → Fade marquee never reaches the number', async () => {
    draw()
    fireEvent.pointerDown(numberCell(1), { button: 0, clientX: 100, clientY: 10 })
    fireEvent.pointerMove(numberCell(1), { button: 0, buttons: 1, clientX: 340, clientY: 62 })
    fireEvent.pointerUp(numberCell(1), { button: 0, clientX: 340, clientY: 62 })
    fireEvent.click(numberCell(1))
    // Cue #, Name and Fade for two cues.
    expect(screen.getByText('6 cells')).toBeInTheDocument()
    fireEvent.doubleClick(fadeCell(1))
    const field = await screen.findByLabelText('Fade')
    fireEvent.change(field, { target: { value: '3s' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 1, fadeDurationMs: 3000 })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 2, fadeDurationMs: 3000 })
    expect(patchEntry).not.toHaveBeenCalled()
    expect(renumberEntries).not.toHaveBeenCalled()
  })

  it('locked, a Cue # cell is inert like every value cell', () => {
    draw({ locked: true, onSetStandby: () => {} })
    expect(numberCell(1)).toBeDisabled()
    expect(numberCell(1).closest('[data-cell]')!.className).toContain('pointer-events-none')
  })
})

describe('CueSheet · dragging a selection (show-view-fixes plan D22–D24)', () => {
  /** Plays a row drag from the row of `from` onto the row of `over` (cue ids), through dnd-kit's events. */
  function dragRow(from: number, over: number, end = true) {
    const active = { id: `entry:${from + 100}` }
    act(() => dnd.last!.onDragStart({ active }))
    act(() => dnd.last!.onDragOver({ active, over: { id: `entry:${over + 100}` } }))
    if (end) act(() => dnd.last!.onDragEnd({ active, over: { id: `entry:${over + 100}` } }))
  }
  const head = (cueId: number) => row(cueId).firstElementChild as HTMLElement

  it('a selected row’s drag moves every selected row in one reorder, and the selection stays on them', () => {
    const onReorder = vi.fn()
    const { rerender } = draw({ onReorder })
    fireEvent.click(head(1))
    fireEvent.click(head(2), { metaKey: true })
    fireEvent.click(head(4), { metaKey: true })
    expect(screen.getByText('3 cues')).toBeInTheDocument()

    dragRow(2, 5)
    // Cue 1, 2 and 4 land together, in their order, after cue 5; the separator stays.
    expect(onReorder).toHaveBeenCalledTimes(1)
    expect(onReorder).toHaveBeenCalledWith([103, 105, 101, 102, 104])

    const byEntry = new Map(STACK.cues.map((c) => [c.entryId, c]))
    rerender(
      <CueSheet
        stack={{ ...STACK, cues: [103, 105, 101, 102, 104].map((id) => byEntry.get(id)!) }}
        projectId={1}
        activeEntryId={null}
        onReorder={onReorder}
      />,
    )
    expect(screen.getByText('3 cues')).toBeInTheDocument()
    expect([1, 2, 4].map((id) => row(id).getAttribute('data-state'))).toEqual(['selected', 'selected', 'selected'])
    expect(row(5)).not.toHaveAttribute('data-state')
  })

  it('an unselected row’s drag moves it alone', () => {
    const onReorder = vi.fn()
    draw({ onReorder })
    fireEvent.click(head(1))
    fireEvent.click(head(2), { metaKey: true })
    dragRow(5, 1)
    expect(onReorder).toHaveBeenCalledWith([105, 101, 102, 103, 104])
  })

  it('while dragging, the travelling rows dim, the dragged row carries +n and a line marks the landing', () => {
    draw({ onReorder: vi.fn() })
    fireEvent.click(head(1))
    fireEvent.click(head(2), { metaKey: true })
    fireEvent.click(head(4), { metaKey: true })
    dragRow(2, 5, false)
    const wrapper = (cueId: number) => row(cueId).parentElement!
    expect([1, 2, 4, 5].map((id) => wrapper(id).hasAttribute('data-drag-dimmed'))).toEqual([true, false, true, false])
    expect(within(row(2)).getByText('+2')).toBeInTheDocument()
    // Moving down: the line is under the row dropped on.
    expect(wrapper(5).querySelector('[data-drop-line]')).toHaveAttribute('data-drop-line', 'bottom')
    expect(document.querySelectorAll('[data-drop-line]')).toHaveLength(1)

    act(() => dnd.last!.onDragCancel({}))
    expect(document.querySelectorAll('[data-drag-dimmed], [data-drop-line], [data-drag-carrying]')).toHaveLength(0)
  })

  it('a single row’s drag draws no chip and no line', () => {
    draw({ onReorder: vi.fn() })
    dragRow(2, 5, false)
    expect(document.querySelectorAll('[data-drag-dimmed], [data-drop-line], [data-drag-carrying]')).toHaveLength(0)
  })
})

describe('CueSheet · the pip arms (show-view-fixes plan D13)', () => {
  it.each([false, true])('locked %s: a grey pip arms its cue and selects nothing', (locked) => {
    const onSetStandby = vi.fn()
    draw({ locked, activeEntryId: 101, standbyEntryId: 102, onSetStandby })
    const pip = screen.getByRole('button', { name: 'Arm Q4 as the next GO' })
    expect(pip).toHaveAttribute('title', 'Arm Q4 as the next GO')
    fireEvent.pointerDown(pip, { button: 0 })
    fireEvent.click(pip)
    expect(onSetStandby).toHaveBeenCalledTimes(1)
    expect(onSetStandby).toHaveBeenCalledWith(4, 104)
    expect(row(4)).not.toHaveAttribute('data-state', 'selected')
    expect(screen.queryByText('1 cue')).toBeNull()
  })

  it('the live and next pips are read-outs, and no pip is a button without a transport', () => {
    draw({ activeEntryId: 101, standbyEntryId: 102, onSetStandby: vi.fn() })
    expect(within(row(1)).getByTitle('On stage').tagName).toBe('SPAN')
    expect(within(row(2)).getByTitle('Next GO').tagName).toBe('SPAN')
    expect(screen.queryByRole('button', { name: /Arm Q[12] as/ })).toBeNull()
    cleanup()
    draw()
    expect(screen.queryByRole('button', { name: /as the next GO/ })).toBeNull()
  })

  it('unlocked, the bar offers Arm as next over one cue', () => {
    const onSetStandby = vi.fn()
    draw({ activeEntryId: 101, onSetStandby })
    fireEvent.click(row(4).firstElementChild as HTMLElement)
    fireEvent.click(screen.getByRole('button', { name: /Arm as next/ }))
    expect(onSetStandby).toHaveBeenCalledWith(4, 104)
  })
})

describe('CueSheet · Scenery (scenery-programmer plan D13)', () => {
  const change = (uuid: string, elementName: string, state: Record<string, unknown>, transitionMs: number | null, sortOrder = 0) => ({
    uuid: `c-${uuid}-${sortOrder}`,
    elementUuid: uuid,
    elementName,
    elementKind: 'DRAPE',
    state,
    transitionMs,
    sortOrder,
  })
  const SCENERY_STACK: CueStack = {
    ...STACK,
    cues: [
      cue(1),
      cue(2, { scenery: [change('tabs', 'Tabs', { open: 0 }, 4000), change('moon', 'Moon', { trimM: 3 }, null, 1)] }),
      cue(3, { cueType: 'MARKER', name: 'Interval' }),
      cue(4),
      // A change whose element the scene no longer has (a delete swept it; the list is stale).
      cue(5, { scenery: [change('gone', 'Cloth', { visible: false }, 0)] }),
    ],
  }
  const scenery = (cueId: number) => document.querySelector(`[data-scenery-cue="${cueId}"]`) as HTMLButtonElement | null

  it("reads a cue's own changes, named from the scene, each on its clock", () => {
    draw({ stack: SCENERY_STACK })
    // The element's name is the scene's (`House tabs`), not the one stored with the change.
    expect(scenery(2)).toHaveTextContent('House tabs → closed · 4 s · Moon → in')
  })

  it('a cue that changes nothing reads tracked where something is tracked into it, else the em-dash', () => {
    draw({ stack: SCENERY_STACK })
    expect(scenery(1)).toHaveTextContent('—')
    expect(scenery(1)).not.toHaveTextContent('tracked')
    // Q4 tracks Q2's changes: hatched, not empty.
    expect(scenery(4)).toHaveTextContent('tracked')
    // Q5's only change names an element the scene does not have, so it reads as changing nothing.
    expect(scenery(5)).toHaveTextContent('tracked')
  })

  it("a stack's set is tracked into its first cue", () => {
    draw({ stack: { ...SCENERY_STACK, scenery: [change('tabs', 'Tabs', { open: 1 }, null)] } })
    expect(scenery(1)).toHaveTextContent('tracked')
  })

  it('is blank on a MARKER', () => {
    draw({ stack: SCENERY_STACK })
    expect(scenery(3)).toBeNull()
  })

  it('unlocked: a press opens the editor on that cue', () => {
    draw({ stack: SCENERY_STACK })
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
    fireEvent.click(scenery(2)!)
    expect(screen.getByTestId('scenery-editor')).toHaveTextContent('editing 2')
  })

  it('locked: a press asks to unlock and opens nothing', async () => {
    const onRequestUnlock = vi.fn()
    draw({ stack: SCENERY_STACK, locked: true, onRequestUnlock })
    expect(scenery(2)).not.toBeDisabled()
    fireEvent.click(scenery(2)!)
    expect(await screen.findByText('Unlock the show to edit?')).toBeInTheDocument()
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Unlock' }))
    expect(onRequestUnlock).toHaveBeenCalled()
  })

  it('locked with no way out: inert, and says why', () => {
    draw({ stack: SCENERY_STACK, locked: true })
    expect(scenery(2)).toBeDisabled()
    expect(scenery(2)!.title).toContain(LOCKED_TITLE)
  })

  it('an editor open when the show locks closes', () => {
    const { rerender } = draw({ stack: SCENERY_STACK })
    fireEvent.click(scenery(2)!)
    expect(screen.getByTestId('scenery-editor')).toBeInTheDocument()
    rerender(<CueSheet stack={SCENERY_STACK} projectId={1} activeEntryId={null} locked onRequestUnlock={() => {}} />)
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
    // …and does not come back with the unlock.
    rerender(<CueSheet stack={SCENERY_STACK} projectId={1} activeEntryId={null} />)
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
  })

  it('carries the cue action set on the bar, over the selected cues in row order, markers left out', () => {
    stubFlatLayout()
    const unlock = vi.fn()
    draw({ locked: true, onRequestUnlock: unlock })
    expect(screen.queryByTestId('cue-actions')).toBeNull()
    // A marquee down the Fade column from cue 2 over the Interval marker to cue 4.
    dragFade(1, 3)
    expect(screen.getByTestId('cue-actions')).toBeTruthy()
    expect(cueActions.last).toMatchObject({ host: 'bar', locked: true, onRequestUnlock: unlock })
    expect((cueActions.last!.rows as { cueId: number; entryId: number; stackId: number }[]).map((r) => [r.cueId, r.entryId, r.stackId])).toEqual([
      [2, 102, 10],
      [4, 104, 10],
    ])
    // After Set · Clear · Spread, before Deselect.
    const bar = screen.getByTestId('cue-actions').parentElement!
    const order = [...bar.children].map((el) => el.getAttribute('data-testid') ?? el.textContent ?? '')
    const at = order.indexOf('cue-actions')
    const setAt = order.findIndex((t) => t.includes('Set'))
    const deselectAt = order.findIndex((t) => t.includes('Deselect'))
    expect(setAt).toBeGreaterThanOrEqual(0)
    expect(setAt).toBeLessThan(at)
    expect(at).toBeLessThan(deselectAt)
  })
})

describe('CueSheet — the bottom pane', () => {
  /** A column 800 tall; every other child of it is flat, so the sheet and the pane share 784. */
  function stubColumn() {
    // Over the flat layout the outer `beforeEach` stubbed: the same spy, its implementation kept.
    const spy = vi.mocked(Element.prototype.getBoundingClientRect)
    const base = spy.getMockImplementation()!
    spy.mockImplementation(function (this: Element) {
      const box = (height: number) => ({ left: 0, top: 0, right: 1100, bottom: height, width: 1100, height, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect
      if (this.hasAttribute('data-cue-sheet-column')) return box(800)
      if (this.hasAttribute('data-cue-pane')) return box(parseFloat((this as HTMLElement).style.height) || 0)
      return base.call(this)
    })
  }
  const pane = () => document.querySelector<HTMLElement>('[data-cue-pane]')

  beforeEach(() => {
    sessionStorage.clear()
    cuePaneHeightStore.reset()
    stubColumn()
  })

  const blank = () => pane()!.querySelector('[data-cue-pane-blank]')

  it('is drawn with nothing selected, shows the one selected cue, and a blank state for several', () => {
    draw()
    expect(pane()).toHaveAttribute('data-cue-pane-state', 'none')
    expect(blank()).toHaveTextContent('No cue selected')
    expect(blank()).toHaveTextContent('Select a cue to edit it here.')
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    expect(pane()).toHaveAttribute('data-cue-pane', '102')
    const editor = pane()!.querySelector('[data-cue-editor]')!
    expect(editor).toHaveAttribute('data-cue-editor', '2')
    expect(editor).toHaveAttribute('data-cue-editor-host', 'pane')
    expect(editor).toHaveAttribute('data-entry', '102')
    // Two rows: the pane and its grip stay, saying why there is no editor.
    dragFade(0, 1)
    expect(pane()).toHaveAttribute('data-cue-pane-state', 'several')
    expect(pane()!.querySelector('[data-cue-editor]')).toBeNull()
    expect(blank()).toHaveTextContent('2 cues selected')
    expect(blank()).toHaveTextContent('The editor shows one cue at a time. The bar above acts on both.')
    expect(document.querySelector('[data-cue-pane-handle]')).not.toBeNull()
  })

  it('a marquee from one row to three keeps the pane’s height, holding the cue until release', () => {
    draw()
    fireEvent.click(row(1).firstElementChild as HTMLElement)
    expect(pane()!.style.height).toBe('400px')
    // Down the Fade column from cue 1 over the marker to cue 4: three cues.
    const cell = fadeCell(1)
    fireEvent.pointerDown(cell, { button: 0, clientX: 340, clientY: 10 })
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 350, clientY: 36 + 26 })
    // Mid-marquee the pane keeps what it had — no editor mounted per row crossed.
    expect(pane()).toHaveAttribute('data-cue-pane', '101')
    fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 350, clientY: 3 * 36 + 26 })
    expect(pane()).toHaveAttribute('data-cue-pane', '101')
    expect(pane()!.style.height).toBe('400px')
    fireEvent.pointerUp(cell, { button: 0, clientX: 350, clientY: 3 * 36 + 26 })
    fireEvent.click(cell)
    expect(pane()).toHaveAttribute('data-cue-pane-state', 'several')
    expect(blank()).toHaveTextContent('3 cues selected')
    expect(blank()).toHaveTextContent('The bar above acts on all three.')
    expect(pane()!.style.height).toBe('400px')
  })

  it('off, it is never drawn, whatever is selected', () => {
    cuePaneOpenStore.set(false)
    draw()
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    expect(pane()).toBeNull()
    expect(document.querySelector('[data-cue-pane-handle]')).toBeNull()
  })

  it('a Layers read-out turns it on, and a plain click does not', () => {
    cuePaneOpenStore.set(false)
    draw({ stack: { ...STACK, cues: [cue(1), cue(2, { layerCount: 3 }), cue(4)] } })
    fireEvent.click(row(4).firstElementChild as HTMLElement)
    expect(pane()).toBeNull()
    fireEvent.click(within(row(2)).getByTitle(/Looks in the editor below/))
    expect(cuePaneOpenStore.getSnapshot()).toBe(true)
    expect(pane()).toHaveAttribute('data-cue-pane', '102')
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', 'looks')
  })

  it('a read-out’s tab is spent when the pane is switched off', () => {
    draw({ stack: { ...STACK, cues: [cue(1), cue(2, { layerCount: 3 }), cue(4)] } })
    fireEvent.click(within(row(2)).getByTitle(/Looks in the editor below/))
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', 'looks')
    act(() => cuePaneOpenStore.set(false))
    act(() => cuePaneOpenStore.set(true))
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', '')
  })

  it('the URL’s cue turns it on — Edit cue opens the editor', () => {
    cuePaneOpenStore.set(false)
    draw({ openedEntryId: 104 })
    expect(cuePaneOpenStore.getSnapshot()).toBe(true)
    expect(pane()).toHaveAttribute('data-cue-pane', '104')
  })

  it('a press on a blank state leaves the keys with the sheet', () => {
    draw()
    dragFade(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    fireEvent.pointerDown(blank()!)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByText('2 cells')).toBeNull()
  })

  it('re-measures when the sheet gives way to another child of the column', () => {
    const observed: Element[] = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(el: Element) {
          observed.push(el)
        }
        disconnect() {}
        unobserve() {}
      },
    )
    draw()
    expect(observed).toContain(document.querySelector('[data-cue-sheet-column]'))
    expect(observed).toContain(document.querySelector('[data-cue-sheet-body]'))
  })

  it('the window’s choice survives a remount and a reload', () => {
    cuePaneOpenStore.set(false)
    expect(sessionStorage.getItem('cueSheet.paneOpen')).toBe('false')
    // A reload: the cached value goes, sessionStorage stays.
    cuePaneOpenStore.reset()
    draw()
    expect(pane()).toBeNull()
  })

  it('opens at half the column, and the grip sets the window’s height, magnetic at half', () => {
    draw()
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    // Half of the 800 column.
    expect(pane()!.style.height).toBe('400px')
    const grip = document.querySelector<HTMLElement>('[data-cue-pane-handle]')!
    fireEvent.pointerDown(grip, { button: 0, pointerId: 1, clientY: 500 })
    // Up 100: taller by 100.
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 400 })
    expect(pane()!.style.height).toBe('500px')
    // Back to within 10px of half: it lands on half.
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 494 })
    expect(pane()!.style.height).toBe('400px')
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 430 })
    fireEvent.pointerUp(window, { pointerId: 1, clientY: 430 })
    expect(cuePaneHeightStore.getSnapshot()).toBe(470)
    expect(sessionStorage.getItem('cueSheet.paneHeight')).toBe('470')
  })

  it('never lets the sheet keep less than its header and three rows, nor the pane fall under 160', () => {
    draw()
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    const grip = document.querySelector<HTMLElement>('[data-cue-pane-handle]')!
    fireEvent.pointerDown(grip, { button: 0, pointerId: 1, clientY: 500 })
    fireEvent.pointerMove(window, { pointerId: 1, clientY: -1000 })
    // 800 less the grip's 16 and the sheet's header and three rows, 30 + 3 × 36.
    expect(pane()!.style.height).toBe('646px')
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 2000 })
    expect(pane()!.style.height).toBe('160px')
    fireEvent.pointerUp(window, { pointerId: 1, clientY: 2000 })
  })

  it('keeps its height across a reload in the window', () => {
    cuePaneHeightStore.set(260)
    // A reload: the cached value goes, sessionStorage stays.
    cuePaneHeightStore.reset()
    draw()
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    expect(pane()!.style.height).toBe('260px')
  })

  it('steps with the arrow keys, and a double press restores the default', () => {
    draw()
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    const grip = document.querySelector<HTMLElement>('[data-cue-pane-handle]')!
    fireEvent.keyDown(grip, { key: 'ArrowDown' })
    expect(pane()!.style.height).toBe('160px')
    fireEvent.keyDown(grip, { key: 'ArrowUp' })
    expect(pane()!.style.height).toBe('400px')
    fireEvent.keyDown(grip, { key: 'ArrowUp' })
    expect(pane()!.style.height).toBe('646px')
    for (let i = 0; i < 2; i++) {
      fireEvent.pointerDown(grip, { button: 0, pointerId: 1, clientY: 300 })
      fireEvent.pointerUp(window, { pointerId: 1, clientY: 300 })
    }
    expect(cuePaneHeightStore.getSnapshot()).toBeNull()
    expect(pane()!.style.height).toBe('400px')
  })

  it("a row's Layers and FX read-outs select it and open the pane on that tab", () => {
    draw({ stack: { ...STACK, cues: [cue(1), cue(2, { layerCount: 3, adHocFxCount: 1 }), cue(4)] } })
    fireEvent.click(within(row(2)).getByTitle(/Looks in the editor below/))
    expect(row(2)).toHaveAttribute('data-state', 'selected')
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', 'looks')
    fireEvent.click(within(row(2)).getByTitle(/FX in the editor below/))
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', 'fx')
    // A plain click on another row is that window's own tab, not the read-out's.
    fireEvent.click(row(4).firstElementChild as HTMLElement)
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', '')
  })

  it('a read-out’s tab is spent once the pane leaves its row', () => {
    draw({ stack: { ...STACK, cues: [cue(1), cue(2, { adHocFxCount: 1 }), cue(4)] } })
    fireEvent.click(within(row(2)).getByTitle(/FX in the editor below/))
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', 'fx')
    fireEvent.click(row(4).firstElementChild as HTMLElement)
    fireEvent.click(row(2).firstElementChild as HTMLElement)
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-tab', '')
  })

  it('a key pressed inside the pane is not the sheet’s: Escape keeps the row, Backspace clears no cell', () => {
    draw()
    // One cell in row 2: one row, so the pane opens over a selection Backspace would clear.
    dragFade(1, 1)
    expect(pane()).not.toBeNull()
    const control = screen.getByTestId('pane-control')
    control.focus()
    fireEvent.keyDown(control, { key: 'Backspace' })
    expect(patchCue).not.toHaveBeenCalled()
    fireEvent.keyDown(control, { key: 'Escape' })
    expect(pane()).not.toBeNull()
    const grip = document.querySelector<HTMLElement>('[data-cue-pane-handle]')!
    fireEvent.keyDown(grip, { key: 'Delete' })
    expect(patchCue).not.toHaveBeenCalled()
    expect(screen.getByText('1 cell')).toBeInTheDocument()
  })

  it('locked, the pane edits nothing', () => {
    draw({ locked: true, onRequestUnlock: () => {} })
    // Locked, the marquee still selects; one cell is one row.
    dragFade(1, 1)
    expect(pane()!.querySelector('[data-cue-editor]')).toHaveAttribute('data-locked', 'true')
  })
})

describe('CueSheet · Dark (move-in-dark plan session 4)', () => {
  const STACK_D: CueStack = { ...STACK, cues: [cue(1), cue(2), cue(3, { cueType: 'MARKER', name: 'Interval' }), cue(4), cue(5)] }
  const plan = (entries: StackMoveInDarkPlan['entries']): StackMoveInDarkPlan => ({
    stackId: 10,
    rigLoaded: true,
    entries,
    summary: { heads: 0, cues: 0, live: 0, off: 0 },
  })
  const darkCell = (cueId: number) => row(cueId).querySelector('[data-cell="dark"]') ?? row(cueId).querySelector('[data-dark-entry]')
  const darkButton = (cueId: number) => row(cueId).querySelector<HTMLElement>(`[data-dark-entry="${cueId + 100}"]`)

  beforeEach(() => {
    stubFlatLayout()
    dark.heads = []
    dark.plan = plan([
      { entryId: 101, cueId: 1, heads: [], live: [] },
      {
        entryId: 102,
        cueId: 2,
        heads: [
          { target: 'spot-1', fromEntryId: 101, families: ['POSITION'] },
          { target: 'spot-2', fromEntryId: 101, families: ['POSITION', 'COLOUR'] },
        ],
        live: [{ target: 'spot-3', reason: 'LIT_BEFORE' }],
      },
      { entryId: 104, cueId: 4, heads: [{ target: 'spot-1', fromEntryId: 102, families: ['POSITION'] }], live: [{ target: 'spot-2', reason: 'TOO_QUICK' }, { target: 'spot-3', reason: 'TOO_QUICK' }] },
      { entryId: 105, cueId: 5, heads: [], live: [{ target: 'spot-1', reason: 'CUE_OFF' }, { target: 'spot-2', reason: 'CUE_OFF' }] },
    ])
  })
  afterEach(() => {
    dark.plan = undefined
    dark.heads = []
  })

  it('counts the heads set in the dark for a cue, and those that will move lit, with the reason when there is one', () => {
    draw({ stack: STACK_D })
    expect(darkButton(1)).toBeNull()
    expect(darkButton(2)).toHaveAttribute('aria-label', expect.stringContaining('2 heads set in the dark for this cue'))
    expect(darkButton(2)).toHaveTextContent('2')
    expect(darkButton(2)).toHaveTextContent('1')
    expect(darkButton(2)).toHaveTextContent('lit before')
    expect(darkButton(4)).toHaveTextContent('too quick')
    expect(darkButton(4)).toHaveAttribute('aria-label', expect.stringContaining('2 heads move while lit (too quick)'))
  })

  it('reads a cue set Off as Off with the heads it moves lit', () => {
    draw({ stack: STACK_D })
    expect(darkButton(5)).toHaveTextContent('Off · 2 live')
  })

  it('on the live row says what is moving now', () => {
    dark.heads = [
      { target: 'spot-1', entryId: 104, cueId: 4, label: 'Q4', state: 'MOVING', endsAtMs: Date.now() + 1000, families: ['POSITION'], position: null },
      { target: 'spot-2', entryId: 104, cueId: 4, label: 'Q4', state: 'WAITING', endsAtMs: Date.now() + 500, families: ['POSITION'], position: null },
      { target: 'spot-3', entryId: 104, cueId: 4, label: 'Q4', state: 'LIT_EARLY', endsAtMs: null, families: ['POSITION'], position: null },
    ]
    draw({ stack: STACK_D, activeEntryId: 101 })
    expect(darkButton(1)).toHaveTextContent('2 moving → Q4')
    expect(darkButton(1)).toHaveAttribute('aria-label', expect.stringContaining('1 lit early'))
    // The rest still read the plan.
    expect(darkButton(2)).toHaveTextContent('lit before')
  })

  it('a tap opens the cue editor on the Cue tab, at Move in dark', () => {
    draw({ stack: STACK_D })
    fireEvent.click(darkButton(2)!)
    expect(row(2)).toHaveAttribute('data-state', 'selected')
    const editor = document.querySelector('[data-cue-editor]')!
    expect(editor).toHaveAttribute('data-tab', 'cue')
    expect(editor).toHaveAttribute('data-section', 'moveInDark')
  })

  it('darkOnly shows only the cues it has something to say about, and Show all lifts it', () => {
    const onShowAll = vi.fn()
    draw({ stack: STACK_D, darkOnly: true, onShowAll, onReorder: vi.fn() })
    // A drag would name only the rows shown, and the desk would put every other entry after them.
    expect(screen.queryAllByRole('button', { name: 'Reorder row' })).toHaveLength(0)
    expect(row(1)).toBeNull()
    expect(row(3)).toBeNull()
    expect(row(2)).not.toBeNull()
    expect(row(4)).not.toBeNull()
    expect(row(5)).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Show all' }))
    expect(onShowAll).toHaveBeenCalled()
  })

  it('without a plan draws nothing in the column, and darkOnly shows every cue and says why', () => {
    dark.plan = undefined
    draw({ stack: STACK_D, darkOnly: true })
    expect(darkCell(2)).toBeNull()
    expect(row(1)).not.toBeNull()
    expect(screen.getByText(/every cue is shown until then/)).toBeInTheDocument()
  })

  it('off the current project darkOnly filters nothing and says so', () => {
    dark.plan = { ...plan([]), rigLoaded: false }
    draw({ stack: STACK_D, darkOnly: true })
    expect(row(1)).not.toBeNull()
    expect(screen.getByText(/counted once this project is current/)).toBeInTheDocument()
  })

  it('a cue set Off reads Off though it lights no head that would move, and passes the filter', () => {
    dark.plan = plan([{ entryId: 101, cueId: 1, mode: 'OFF', heads: [], live: [] }, { entryId: 102, cueId: 2, heads: [], live: [] }])
    draw({ stack: STACK_D, darkOnly: true })
    expect(darkButton(1)).toHaveTextContent(/^Off$/)
    expect(row(2)).toBeNull()
  })

  it('the legend shows only while some cue has something to say', () => {
    dark.plan = plan([{ entryId: 101, cueId: 1, heads: [], live: [] }])
    const { unmount } = draw({ stack: STACK_D })
    expect(screen.getByText('set in dark for this cue').closest('span')).toHaveClass('hidden')
    unmount()
    dark.plan = plan([{ entryId: 102, cueId: 2, heads: [{ target: 'a', fromEntryId: 101, families: ['POSITION'] }], live: [] }])
    draw({ stack: STACK_D })
    expect(screen.getByText('set in dark for this cue').closest('span')).toHaveClass('inline-flex')
  })
})
