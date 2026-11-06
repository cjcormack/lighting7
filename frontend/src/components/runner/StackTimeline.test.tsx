// @vitest-environment jsdom
import { cueRow, markerRow } from '@/test/cueEntries'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack, StackMoveInDarkPlan } from '@/api/cueStacksApi'
import type { StackTimeline as StackTimelineData, TimelineEntry, TimelineLayer } from '@/api/stackTimelineApi'

/**
 * The Timeline's grid (stack-timeline plan §4.2): a column per record and target under its kind's
 * band, runs that pass a marker, the live row's fade bar, and one selected cell opening the pane on
 * its cue at the column's tab.
 */
let TIMELINE: StackTimelineData | undefined
const edit = vi.fn()
let DARK_PLAN: StackMoveInDarkPlan | undefined
vi.mock('@/store/cueStacks', () => ({
  useCueStackTimelineQuery: () => ({ data: TIMELINE }),
  useCueStackMoveInDarkQuery: () => ({ data: DARK_PLAN }),
  useEditCueStackTimelineMutation: () => [edit, { isLoading: false }],
}))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [] }) }))
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtures', () => ({
  useVisibleFixtureListQuery: () => ({ data: [{ key: 'hex-1', name: 'Hex 1' }, { key: 'bar', name: 'Bar', elements: [{ key: 'bar.1' }, { key: 'bar.2' }] }] }),
}))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [{ name: 'wash', memberKeys: ['hex-1', 'hex-2'] }] }) }))
vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({ data: [{ id: 7, isGeneric: true, rows: [{ attribute: 'rgbColour', value: '#8f7bff' }] }] }),
}))
vi.mock('@/store/stageElements', () => ({
  useStageElementListQuery: () => ({
    data: [
      { uuid: 'tabs', name: 'House tabs', kind: 'DRAPE' },
      { uuid: 'moon', name: 'Moon', kind: 'OBJECT' },
    ],
  }),
}))
let DESK_SELECTION: { type: 'fixture' | 'group'; key: string }[] = []
vi.mock('@/store/selection', () => ({ useDeskSelection: () => DESK_SELECTION }))
vi.mock('@/store/cueHolds', () => ({ useCueEdited: () => false }))
let FADE: number | null = null
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: FADE, fadeRemainMs: null }) }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@/components/cues/editor/CueEditor', () => ({
  CueEditor: (props: { cueId: number; tabRequest?: { tab: string; section?: string } | null }) => (
    <div data-cue-editor={props.cueId} data-tab={props.tabRequest?.tab ?? ''} data-section={props.tabRequest?.section ?? ''} />
  ),
}))
/** What the bar hands the cue action set — the actions themselves are `CueActions.test.tsx`'s. */
const cueActions: { last?: Record<string, unknown> } = {}
vi.mock('./CueActions', () => ({
  CueActions: (props: Record<string, unknown>) => {
    cueActions.last = props
    return <div data-testid="cue-actions" />
  },
}))
// The row drag's handlers, as the sheet hands them to dnd-kit, played into directly (`dragRow`).
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
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () => Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

import { StackTimeline } from './StackTimeline'
import { timelineFiltersStore } from '@/lib/stackTimeline/columnOrder'
import { cuePaneHeightStore, cuePaneOpenStore } from './CueEditorPane'

const STACK: CueStack = {
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cueRow(1, { name: 'Tabs out' }), cueRow(2, { name: 'Ballroom' }), markerRow(150, 'Scene 2'), cueRow(3, { name: 'Market' })],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
}

function lavender(over: Partial<TimelineLayer> = {}): TimelineLayer {
  return {
    id: 1,
    source: { kind: 'PALETTE', id: 7, uuid: 'p7', name: 'Lavender', families: ['COLOUR'] },
    targets: [{ type: 'fixture', key: 'hex-1' }],
    heads: ['hex-1'],
    sortOrder: 0,
    enabled: true,
    amount: 1,
    blendMode: 'OVERRIDE',
    attributeMask: null,
    stomp: false,
    timed: false,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }
}

function entry(entryId: number, over: Partial<TimelineEntry> = {}): TimelineEntry {
  return { entryId, kind: 'CUE', cueId: entryId - 100, layers: [], fx: [], scenery: [], trackedScenery: [], ...over }
}

beforeEach(() => {
  edit.mockReset()
  DARK_PLAN = undefined
  cueActions.last = undefined
  timelineFiltersStore.reset()
  sessionStorage.clear()
  cuePaneOpenStore.reset()
  cuePaneHeightStore.reset()
  DESK_SELECTION = []
  FADE = null
  TIMELINE = {
    stackId: 10,
    entries: [
      entry(101, { layers: [lavender()], scenery: [{ elementUuid: 'tabs', state: { open: 1 }, transitionMs: 4000 }] }),
      entry(102, { layers: [lavender({ id: 2, amount: 0.5 })], trackedScenery: [{ elementUuid: 'tabs', state: { open: 1 }, fromCueId: 1 }] }),
      { entryId: 150, kind: 'MARKER', label: 'Scene 2', layers: [], fx: [], scenery: [], trackedScenery: [] },
      entry(103, { layers: [lavender({ id: 3 })], trackedScenery: [{ elementUuid: 'tabs', state: { open: 1 }, fromCueId: 1 }] }),
    ],
  }
})

afterEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
})

function cell(entryId: number, col: string): HTMLElement {
  return document.querySelector(`[data-row-id="entry:${entryId}"] [data-cell="${col}"]`) as HTMLElement
}

/** The first column 0..260, Fade and Follow to 376, then Lavender 376..460 and the tabs 460..552; rows 36px from a 0-high header. */
function stubLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  const bands: Record<string, [number, number]> = { [PALETTE]: [376, 460], [SCENERY]: [460, 552] }
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 260)
    const col = this.getAttribute('data-column-header')
    if (col && bands[col]) return rect(...bands[col])
    return rect(0, 1100)
  })
}

/** A press on [target] at column x [x], in drawn row [row]. */
function pressCell(target: HTMLElement, x: number, row: number) {
  fireEvent.pointerDown(target, { button: 0, clientX: x, clientY: row * 36 + 10 })
  fireEvent.pointerUp(target, { button: 0, clientX: x, clientY: row * 36 + 10 })
  fireEvent.click(target)
}

const PALETTE = 'palette:7:fixture:hex-1'
const SCENERY = 'scenery:tabs'

describe('StackTimeline', () => {
  it('draws a column per record and target under its kind’s band, with a cell per cue', () => {
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect([...document.querySelectorAll('[data-header-band]')].map((b) => b.textContent)).toEqual(['Palettes · 1', 'Scenery · 1'])
    expect(screen.getByText('Lavender')).toBeTruthy()
    expect(screen.getByText('Hex 1')).toBeTruthy()
    expect(cell(101, PALETTE).querySelector('[data-rank]')?.textContent).toBe('1')
    // A palette cell, and its header, carry the palette's colour.
    expect((cell(101, PALETTE).querySelector('[data-swatch]') as HTMLElement).style.background).toBe('rgb(143, 123, 255)')
    expect(document.querySelector('[data-grid-header] [data-swatch]')).not.toBeNull()
    expect(cell(102, PALETTE).textContent).toContain('50%')
    expect(cell(101, SCENERY).textContent).toContain('drawn · 4 s')
    expect(cell(102, SCENERY).querySelector('[data-timeline-cell]')?.getAttribute('data-timeline-cell')).toBe('tracked')
  })

  it('passes a run and a tracked line through a marker', () => {
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    const marker = document.querySelector('[data-row-id="entry:150"]')!
    expect(marker.textContent).toContain('Scene 2')
    expect(marker.querySelector(`[data-divider-cell="${PALETTE}"] [data-timeline-through]`)).not.toBeNull()
    expect(marker.querySelector(`[data-divider-cell="${SCENERY}"] [data-timeline-through]`)).not.toBeNull()
  })

  it('frames the live row with its fade and the next row with its edge', () => {
    FADE = 0.4
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={102} standbyEntryId={103} fadeStackId={10} />)
    const live = document.querySelector('[data-row-id="entry:102"]') as HTMLElement
    expect(live.className).toContain('shadow-[inset_3px_0_0_rgb(34,197,94)]')
    expect((live.querySelector('[data-testid="timeline-fade"]') as HTMLElement).style.width).toBe('40%')
    expect((document.querySelector('[data-row-id="entry:103"]') as HTMLElement).className).toContain('rgb(59,130,246)')
  })

  it('shows one selected cell’s cue at its column’s tab, and a blank state over several cues', () => {
    stubLayout()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    const editor = () => document.querySelector('[data-cue-editor]')
    const pane = () => document.querySelector<HTMLElement>('[data-cue-pane]')
    expect(pane()).toHaveAttribute('data-cue-pane-state', 'none')
    pressCell(cell(102, PALETTE).firstElementChild as HTMLElement, 400, 1)
    expect(editor()?.getAttribute('data-cue-editor')).toBe('2')
    expect(editor()?.getAttribute('data-tab')).toBe('looks')
    const height = pane()!.style.height
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(editor()?.getAttribute('data-tab')).toBe('scenery')
    // A range over two cues: the pane stays at its height and says why it is blank.
    fireEvent.keyDown(window, { key: 'ArrowUp', shiftKey: true })
    expect(editor()).toBeNull()
    expect(pane()).toHaveAttribute('data-cue-pane-state', 'several')
    expect(pane()).toHaveTextContent('2 cues selected')
    expect(pane()!.style.height).toBe(height)
  })

  it('draws no Dark column while the stack moves nothing in the dark', () => {
    DARK_PLAN = { stackId: 10, rigLoaded: true, entries: [{ entryId: 101, cueId: 1, heads: [], live: [] }], summary: { heads: 0, cues: 0, live: 0, off: 0 } }
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect(document.querySelector('[data-timeline-dark]')).toBeNull()
    expect([...document.querySelectorAll('[data-header-band]')].map((b) => b.textContent)).toEqual(['Palettes · 1', 'Scenery · 1'])
  })

  it('draws each move in the Dark column, from the cue it happens in to the cue it is for, across a marker', () => {
    // Early: two heads move in Q1 for Q3, through Q2 and the marker. Late: one moves in Q2 for Q3.
    DARK_PLAN = {
      stackId: 10,
      rigLoaded: true,
      entries: [
        { entryId: 101, cueId: 1, heads: [], live: [] },
        { entryId: 102, cueId: 2, heads: [], live: [] },
        {
          entryId: 103,
          cueId: 3,
          heads: [
            { target: 'spot-1', fromEntryId: 101, families: ['POSITION'] },
            { target: 'spot-2', fromEntryId: 101, families: ['POSITION'] },
            { target: 'spot-3', fromEntryId: 102, families: ['POSITION'] },
          ],
          live: [{ target: 'spot-4', reason: 'KEPT_LIVE' }],
        },
      ],
      summary: { heads: 3, cues: 1, live: 1, off: 0 },
    }
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect([...document.querySelectorAll('[data-header-band]')].map((b) => b.textContent)).toEqual(['Palettes · 1', 'Scenery · 1', 'Move in dark'])
    const spans = (entryId: number) =>
      [...document.querySelectorAll(`[data-row-id="entry:${entryId}"] [data-dark-span]`)].map(
        (e) => `${e.getAttribute('data-dark-span')}${e.hasAttribute('data-dark-start') ? ' start' : ''}${e.hasAttribute('data-dark-end') ? ' end' : ''}`,
      )
    expect(spans(101)).toEqual(['101-103 start'])
    expect(spans(102)).toEqual(['101-103', '102-103 start'])
    expect(spans(150)).toEqual(['101-103', '102-103'])
    expect(spans(103)).toEqual(['101-103 end', '102-103 end'])
    // Where a move starts it says so; where moves land, the cue's own read-out.
    expect(document.querySelector('[data-timeline-dark="101"]')?.textContent).toBe('☾ 2 → Q3')
    expect(document.querySelector('[data-timeline-dark="103"]')?.textContent).toContain('3')
    expect(document.querySelector('[data-timeline-dark="103"]')?.textContent).toContain('kept live')

    // Read-only: a tap opens the cue the move is for, at its Move in dark.
    fireEvent.click(document.querySelector('[data-timeline-dark="101"] button')!)
    const editor = document.querySelector('[data-cue-editor]')
    expect(editor?.getAttribute('data-cue-editor')).toBe('3')
    expect(editor?.getAttribute('data-tab')).toBe('cue')
    expect(editor?.getAttribute('data-section')).toBe('moveInDark')
    expect(edit).not.toHaveBeenCalled()
    // The request is for that row: once the pane leaves it, coming back opens no tab of its own.
    fireEvent.click(document.querySelector('[data-row-id="entry:101"] [data-first-column]')!)
    fireEvent.click(document.querySelector('[data-row-id="entry:103"] [data-first-column]')!)
    expect(document.querySelector('[data-cue-editor]')?.getAttribute('data-cue-editor')).toBe('3')
    expect(document.querySelector('[data-cue-editor]')?.getAttribute('data-tab')).toBe('')
    // The spans cross the row rules: their read-out does not clip.
    expect(document.querySelector('[data-timeline-dark="102"]')?.parentElement?.className).not.toContain('overflow-hidden')
  })

  it('names the place a move is for, where its cue sits twice in the stack', () => {
    const twice: CueStack = { ...STACK, cues: [...STACK.cues, cueRow(3, { entryId: 109, cueNumber: '9', name: 'Market again' })] }
    DARK_PLAN = {
      stackId: 10,
      rigLoaded: true,
      entries: [{ entryId: 109, cueId: 3, heads: [{ target: 'spot-1', fromEntryId: 101, families: ['POSITION'] }], live: [] }],
      summary: { heads: 1, cues: 1, live: 0, off: 0 },
    }
    render(<StackTimeline stack={twice} projectId={1} activeEntryId={null} />)
    expect(document.querySelector('[data-timeline-dark="101"]')?.textContent).toBe('☾ 1 → Q9')
    expect(document.querySelector('[data-timeline-dark="101"] button')?.getAttribute('title')).toContain('for Q9')
  })

  it('writes nothing for ⏎ and ⌫ over a row selection', () => {
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    fireEvent.keyDown(window, { key: 'ArrowDown' })
    fireEvent.keyDown(window, { key: 'Enter' })
    fireEvent.keyDown(window, { key: 'Backspace' })
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(edit).not.toHaveBeenCalled()
  })

  it('meets a selected cell with a layer on its parent, and a selected parent with a layer on its cell', () => {
    TIMELINE!.entries[0].layers.push(lavender({ id: 9, targets: [{ type: 'fixture', key: 'bar' }], heads: ['bar'] }))
    TIMELINE!.entries[1].layers.push(lavender({ id: 10, source: { kind: 'PALETTE', id: 4, uuid: 'p4', name: 'Pixels', families: [] }, targets: [{ type: 'fixture', key: 'bar.2' }], heads: ['bar.2'] }))
    DESK_SELECTION = [{ type: 'fixture', key: 'bar.1' }]
    const { unmount } = render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    fireEvent.click(screen.getByRole('button', { name: /Selection only/ }))
    expect(cell(101, 'palette:7:fixture:bar')).not.toBeNull()
    expect(cell(101, PALETTE)).toBeNull()
    // bar.2 is not bar.1, and the cell selected is no parent of it.
    expect(cell(101, 'palette:4:fixture:bar.2')).toBeNull()
    unmount()
    DESK_SELECTION = [{ type: 'fixture', key: 'bar' }]
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect(cell(101, 'palette:4:fixture:bar.2')).not.toBeNull()
  })

  it('filters by kind and by the desk selection', () => {
    DESK_SELECTION = [{ type: 'fixture', key: 'hex-2' }]
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    fireEvent.click(screen.getByRole('button', { name: /Selection only/ }))
    // Lavender on Hex 1 reaches no selected head; scenery is no head's and stays.
    expect(cell(101, PALETTE)).toBeNull()
    expect(cell(101, SCENERY)).not.toBeNull()
    act(() => timelineFiltersStore.set({ kinds: ['palette'], family: null, selectionOnly: false, hiddenScenery: [] }))
    expect(cell(101, PALETTE)).not.toBeNull()
    expect(cell(101, SCENERY)).toBeNull()
  })

  it('hands the bar’s cue action set the selected cues, as the Table does', () => {
    stubLayout()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} locked onRequestUnlock={() => {}} />)
    expect(screen.queryByTestId('cue-actions')).toBeNull()
    pressCell(cell(102, PALETTE).firstElementChild as HTMLElement, 400, 1)
    expect(screen.getByTestId('cue-actions')).toBeTruthy()
    expect(cueActions.last).toMatchObject({
      projectId: 1,
      host: 'timeline',
      rows: [{ cueId: 2, name: 'Ballroom', entryId: 102, stackId: 10 }],
      locked: true,
      fromShowView: true,
    })
    // A range over two cues hands both, in row order; a marker is no cue.
    fireEvent.keyDown(window, { key: 'ArrowDown', shiftKey: true })
    fireEvent.keyDown(window, { key: 'ArrowDown', shiftKey: true })
    expect((cueActions.last!.rows as { cueId: number }[]).map((r) => r.cueId)).toEqual([2, 3])
  })

  it('shows the scenery the operator picks, one element at a time', () => {
    TIMELINE!.entries[1].scenery.push({ elementUuid: 'moon', state: { visible: true }, transitionMs: null })
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    // Read off the DOM: the open menu hides the rest of the page from the accessibility tree.
    const chip = () => document.querySelector('[data-timeline-scenery-filter] button[aria-pressed]') as HTMLElement
    const open = () => fireEvent.pointerDown(screen.getByRole('button', { name: 'Choose the scenery shown' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    expect(cell(101, 'scenery:moon')).not.toBeNull()
    open()
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: /House tabs/ }))
    expect(cell(101, SCENERY)).toBeNull()
    expect(cell(101, 'scenery:moon')).not.toBeNull()
    expect(chip().textContent).toContain('1/2')
    expect(timelineFiltersStore.getSnapshot().hiddenScenery).toEqual(['tabs'])
    // The menu stays open, so a second element is one more click; Show all brings both back.
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: /Moon/ }))
    expect(cell(101, 'scenery:moon')).toBeNull()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Show all' }))
    expect(cell(101, SCENERY)).not.toBeNull()
    expect(cell(101, 'scenery:moon')).not.toBeNull()
    expect(chip().textContent).not.toContain('/')
  })

  it('lets go of a selected cell its column is hidden from', () => {
    stubLayout()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    pressCell(cell(101, SCENERY).firstElementChild as HTMLElement, 500, 0)
    expect(screen.getByTestId('cue-actions')).toBeTruthy()
    act(() => timelineFiltersStore.set({ ...timelineFiltersStore.getSnapshot(), hiddenScenery: ['tabs'] }))
    // Nothing selected is left, so the bar holds no cue and ⏎ writes nothing.
    expect(screen.queryByTestId('cue-actions')).toBeNull()
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(edit).not.toHaveBeenCalled()
  })

  it('keeps another stack’s hidden scenery when this one shows all', () => {
    timelineFiltersStore.set({ ...timelineFiltersStore.getSnapshot(), hiddenScenery: ['elsewhere', 'tabs'] })
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect(cell(101, SCENERY)).toBeNull()
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Choose the scenery shown' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    fireEvent.click(screen.getByRole('menuitem', { name: 'Show all' }))
    expect(timelineFiltersStore.getSnapshot().hiddenScenery).toEqual(['elsewhere'])
  })
})

describe('StackTimeline — the pip arms (show-view-fixes plan D13)', () => {
  it.each([false, true])('locked %s: a grey pip arms its cue and selects nothing; the live and next pips are read-outs', (locked) => {
    const onSetStandby = vi.fn()
    render(
      <StackTimeline
        stack={STACK}
        projectId={1}
        activeEntryId={101}
        standbyEntryId={102}
        onSetStandby={onSetStandby}
        locked={locked}
        onRequestUnlock={() => {}}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Arm Q3 as the next GO' }))
    expect(onSetStandby).toHaveBeenCalledTimes(1)
    expect(onSetStandby).toHaveBeenCalledWith(3, 103)
    expect(document.querySelector('[data-row-id="entry:103"]')).not.toHaveAttribute('data-state', 'selected')
    expect(screen.getByTitle('On stage').tagName).toBe('SPAN')
    expect(screen.getByTitle('Next GO').tagName).toBe('SPAN')
    expect(screen.queryByRole('button', { name: /Arm Q[12] as/ })).toBeNull()
  })
})

describe('StackTimeline — dragging a selection (show-view-fixes plan D22)', () => {
  function dragRow(from: number, over: number) {
    const active = { id: `entry:${from}` }
    act(() => dnd.last!.onDragStart({ active }))
    act(() => dnd.last!.onDragEnd({ active, over: { id: `entry:${over}` } }))
  }

  it('a drag from a row with selected cells moves every row the cells are in; another row moves alone', () => {
    stubLayout()
    const onReorder = vi.fn()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} onReorder={onReorder} />)
    const target = cell(101, PALETTE).firstElementChild as HTMLElement
    fireEvent.pointerDown(target, { button: 0, clientX: 400, clientY: 10 })
    fireEvent.pointerMove(target, { button: 0, buttons: 1, clientX: 410, clientY: 36 + 26 })
    fireEvent.pointerUp(target, { button: 0, clientX: 410, clientY: 36 + 26 })
    fireEvent.click(target)

    dragRow(102, 103)
    expect(onReorder).toHaveBeenLastCalledWith([150, 103, 101, 102])
    dragRow(103, 101)
    expect(onReorder).toHaveBeenLastCalledWith([103, 101, 102, 150])
  })
})

describe('StackTimeline — the editor pane toggle', () => {
  const pane = () => document.querySelector<HTMLElement>('[data-cue-pane]')

  it('holds the pane’s cue while a marquee crosses cues, and follows the selection on release', () => {
    stubLayout()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    pressCell(cell(101, PALETTE).firstElementChild as HTMLElement, 400, 0)
    expect(pane()).toHaveAttribute('data-cue-pane', '101')
    const target = cell(101, PALETTE).firstElementChild as HTMLElement
    fireEvent.pointerDown(target, { button: 0, clientX: 400, clientY: 10 })
    fireEvent.pointerMove(target, { button: 0, buttons: 1, clientX: 410, clientY: 36 + 26 })
    expect(pane()).toHaveAttribute('data-cue-pane', '101')
    fireEvent.pointerUp(target, { button: 0, clientX: 410, clientY: 36 + 26 })
    fireEvent.click(target)
    expect(pane()).toHaveAttribute('data-cue-pane-state', 'several')
  })

  it('off, draws no pane over a selected cell', () => {
    cuePaneOpenStore.set(false)
    stubLayout()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    pressCell(cell(102, PALETTE).firstElementChild as HTMLElement, 400, 1)
    expect(pane()).toBeNull()
  })

  it('the URL’s cue turns it on', () => {
    cuePaneOpenStore.set(false)
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} openedEntryId={103} />)
    expect(cuePaneOpenStore.getSnapshot()).toBe(true)
    expect(pane()).toHaveAttribute('data-cue-pane', '103')
  })

  it('a Dark tap turns it on and opens the cue at its Move in dark', () => {
    cuePaneOpenStore.set(false)
    DARK_PLAN = {
      stackId: 10,
      rigLoaded: true,
      entries: [
        { entryId: 101, cueId: 1, heads: [], live: [] },
        { entryId: 103, cueId: 3, heads: [{ target: 'spot-1', fromEntryId: 101, families: ['POSITION'] }], live: [] },
      ],
      summary: { heads: 1, cues: 1, live: 0, off: 0 },
    }
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect(pane()).toBeNull()
    fireEvent.click(document.querySelector('[data-timeline-dark="101"] button')!)
    expect(cuePaneOpenStore.getSnapshot()).toBe(true)
    expect(pane()?.querySelector('[data-cue-editor]')?.getAttribute('data-section')).toBe('moveInDark')
  })
})
