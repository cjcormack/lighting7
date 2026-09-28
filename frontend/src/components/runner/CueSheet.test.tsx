// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'

/**
 * The cue sheet's own rules on the kit (CLAUDE.md §Sheet kit), and above all **the lock as the
 * sheet's read-only scope**: locked, every value cell is inert in the four places the programmer's
 * Output scope is — the wrapper's `pointer-events-none`, the trigger's `disabled`, the keyboard
 * through the permission, and the bar's Set · Clear · Spread with the reason — while the marquee
 * still works and a click on the Cue column arms the cue as next. Unlocked, a cell writes one
 * PATCH per cue, and Spread on Fade spreads first→last.
 */
const patchCue = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/cues', () => ({ usePatchProjectCueMutation: () => [patchCue] }))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
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
    scrollToIndex: () => {},
  }),
}))

import { CueSheet } from './CueSheet'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

const cue = (id: number, over: Partial<CueStackCueEntry> = {}): CueStackCueEntry => ({
  id,
  name: `Cue ${id}`,
  sortOrder: id,
  layerCount: 0,
  adHocEffectCount: 0,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
  fadeDurationMs: 1000,
  fadeCurve: 'LINEAR',
  cueNumber: String(id),
  cueNumberAuto: false,
  notes: null,
  cueType: 'STANDARD',
  ...over,
})

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
  canEdit: true,
  canDelete: true,
}

function draw(over: Partial<React.ComponentProps<typeof CueSheet>> = {}) {
  return render(<CueSheet stack={STACK} projectId={1} activeCueId={null} onOpenCue={() => {}} {...over} />)
}

/** The first column spans 0..100; Name 100..300, Fade 300..388 — rows are 36px from a 0-high header. */
function stubFlatLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  const bands: Record<string, [number, number]> = {
    name: [100, 300],
    fade: [300, 388],
    curve: [388, 506],
    follow: [506, 602],
    book: [602, 678],
    layers: [678, 754],
    fx: [754, 810],
    notes: [810, 1010],
  }
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 100)
    const col = this.getAttribute('data-column-header')
    if (col && bands[col]) return rect(...bands[col])
    return rect(0, 1100)
  })
}

function row(cueId: number): HTMLElement {
  return document.querySelector(`[data-row-id="cue:${cueId}"]`) as HTMLElement
}
function fadeCell(cueId: number): HTMLElement {
  return within(row(cueId)).getAllByRole('button').find((b) => b.closest('[data-cell="fade"]'))!
}

/** A marquee down the Fade column over the drawn rows `from`..`to`. */
function dragFade(from: number, to: number) {
  const cell = fadeCell(STACK.cues[from].id)
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
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  patchCue.mockClear()
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

  it('locked: the cue number does not inflate the row — the display box is inline-block', () => {
    // Locked, the number is a `<span>` wrapping `TruncateStart`'s blocks. Left inline, its line
    // box measured 59px for a 20px number, and as the grid row's tallest min-content that became
    // the *track*: every cell in the row was laid out below the row.
    draw({ locked: true })
    const number = within(row(1)).getByTitle(/cue number/i)
    expect(number.className).toContain('inline-block')
    // And it is text, not a disabled editor: a disabled button swallows the click the column
    // needs to arm the cue.
    expect(number.closest('button')).toBeNull()
  })

  it('unlocked: a double click on the cue number opens its editor — the same popover as every value cell — and a single click selects the row', () => {
    draw()
    const number = within(row(1)).getByTitle(/edit the cue number/i)
    fireEvent.click(number)
    expect(row(1)).toHaveAttribute('data-state', 'selected')
    expect(screen.queryByLabelText('Cue number')).not.toBeInTheDocument()
    fireEvent.doubleClick(number)
    // Portalled, like every cell editor, so it is looked for on the screen and not in the row.
    const field = screen.getByLabelText('Cue number') as HTMLInputElement
    expect(field.value).toBe('1')
    fireEvent.change(field, { target: { value: '12A' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 1, cueNumber: '12A' })
  })

  it('leaves a cell it cannot select blank rather than drawing an em-dash', () => {
    // A snap cue has no curve to set and the read-outs are not cells at all; the em-dash belongs to
    // an empty cell you CAN set, which is what Follow and Notes still draw.
    draw({ stack: { ...STACK, cues: [cue(1, { fadeDurationMs: null }), cue(2)] } })
    // Columns after the sticky Cue head: name, fade, curve, follow, book, layers, fx, notes.
    const cell = (i: number) => row(1).children[i] as HTMLElement
    // A snap cue's Curve has nothing to set, so it is not a cell at all — and it draws nothing.
    expect(row(1).querySelector('[data-cell="curve"]')).toBeNull()
    expect(cell(3).textContent).toBe('')
    // Book, Layers and FX are read-outs with nothing to show, so they show nothing.
    expect(cell(5).textContent).toBe('')
    expect(cell(6).textContent).toBe('')
    expect(cell(7).textContent).toBe('')
    // Follow and Notes keep their em-dash: they are empty cells, not absent ones.
    expect(row(1).querySelector('[data-cell="follow"]')?.textContent).toContain('—')
    expect(row(1).querySelector('[data-cell="notes"]')?.textContent).toContain('—')
  })

  it('the Book read-out opens the Prompt Book, not the cue card', () => {
    const onOpenBook = vi.fn()
    const onOpenCue = vi.fn()
    draw({ locationByCue: new Map([[1, 'top of p. 8']]), onOpenBook, onOpenCue })
    fireEvent.click(within(row(1)).getByText('top of p. 8').closest('button')!)
    expect(onOpenBook).toHaveBeenCalledWith(1)
    expect(onOpenCue).not.toHaveBeenCalled()
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
      <CueSheet stack={STACK} projectId={1} activeCueId={null} onOpenCue={() => {}} onReorder={onReorder} />,
    )
    // Cues and separators alike — a marker is a row of the stack and moves with them.
    expect(screen.getAllByRole('button', { name: 'Reorder row' })).toHaveLength(STACK.cues.length)
    rerender(
      <CueSheet stack={STACK} projectId={1} activeCueId={null} onOpenCue={() => {}} onReorder={onReorder} locked />,
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
      <CueSheet stack={STACK} projectId={1} activeCueId={null} onOpenCue={() => {}} onReorder={vi.fn()} />,
    )
    const wrappers = [...document.querySelectorAll('[data-row-id]')].map((r) => r.parentElement!)
    expect(wrappers.map((w) => w.style.top)).toEqual(['0px', '36px', '72px', '108px', '144px'])
    for (const w of wrappers) expect(w.style.transform).not.toContain('translateY')
  })

  it('washes the live row green and the next row blue, as the cards do', () => {
    draw({ activeCueId: 2, standbyCueId: 4 })
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
    render(<CueSheet stack={stack} projectId={1} activeCueId={null} onOpenCue={() => {}} />)
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
    draw({ openedCueId: 4 })
    expect(row(4)).toHaveAttribute('data-state', 'selected')
    expect(row(1)).not.toHaveAttribute('data-state', 'selected')
    expect(screen.getByText('1 cue')).toBeInTheDocument()
  })

  it('consumes the deep link once — a refetched stack does not wipe a marquee made since', () => {
    // Every cell commit refetches the stack and every GO re-mints its cues; neither is an arrival.
    const view = render(
      <CueSheet stack={STACK} projectId={1} activeCueId={null} openedCueId={4} onOpenCue={() => {}} />,
    )
    expect(row(4)).toHaveAttribute('data-state', 'selected')
    dragFade(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    view.rerender(
      <CueSheet
        stack={{ ...STACK, cues: STACK.cues.map((c) => ({ ...c })), activeCueId: 2 }}
        projectId={1}
        activeCueId={2}
        openedCueId={4}
        onOpenCue={() => {}}
      />,
    )
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    expect(row(4)).not.toHaveAttribute('data-state', 'selected')
    // A new id is a new arrival.
    view.rerender(
      <CueSheet stack={STACK} projectId={1} activeCueId={null} openedCueId={5} onOpenCue={() => {}} />,
    )
    expect(row(5)).toHaveAttribute('data-state', 'selected')
    expect(screen.queryByText('2 cells')).toBeNull()
  })

  it('unlocked: Spread on Fade spreads first→last over the selection, in visible order', async () => {
    draw()
    // Rows 3 and 4 are cues 4 and 5 (row 2 is the marker, which a rectangle skips).
    dragFade(0, 4)
    expect(screen.getByText('4 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Spread' }))
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
    for (const name of ['Set', 'Clear cells', 'Spread']) {
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

  it('locked: a click on the Cue column arms the cue as next — on the number itself, not only the margins', () => {
    const onSetStandby = vi.fn()
    draw({ locked: true, activeCueId: 1, onSetStandby })
    // The cue number is the largest thing in the column; locked, it is inert text and the click
    // must bubble to the column.
    // `TruncateStart` says the text twice (a measure and a display), so take the first.
    fireEvent.click(within(row(2)).getAllByText('Q2')[0])
    expect(onSetStandby).toHaveBeenCalledWith(2)
    // The live cue cannot be armed against itself.
    fireEvent.click(row(1).firstElementChild as HTMLElement)
    expect(onSetStandby).toHaveBeenCalledTimes(1)
  })

  it('unlocked: a click on the Cue column selects the row rather than arming', () => {
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
