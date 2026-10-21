// @vitest-environment jsdom
import { cueRow, markerRow } from '@/test/cueEntries'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack } from '@/api/cueStacksApi'
import type { StackTimeline as StackTimelineData, TimelineEntry, TimelineLayer } from '@/api/stackTimelineApi'

/**
 * The Timeline's grid (stack-timeline plan §4.2): a column per record and target under its kind's
 * band, runs that pass a marker, the live row's fade bar, and one selected cell opening the pane on
 * its cue at the column's tab — while nothing a cell does writes.
 */
let TIMELINE: StackTimelineData | undefined
vi.mock('@/store/cueStacks', () => ({ useCueStackTimelineQuery: () => ({ data: TIMELINE }) }))
vi.mock('@/store/fixtures', () => ({
  useVisibleFixtureListQuery: () => ({ data: [{ key: 'hex-1', name: 'Hex 1' }, { key: 'bar', name: 'Bar', elements: [{ key: 'bar.1' }, { key: 'bar.2' }] }] }),
}))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [{ name: 'wash', memberKeys: ['hex-1', 'hex-2'] }] }) }))
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: [{ id: 7, isGeneric: true, rows: [{ propertyName: 'rgbColour', value: '#8f7bff' }] }] }),
}))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: [{ uuid: 'tabs', name: 'House tabs', kind: 'DRAPE' }] }) }))
let DESK_SELECTION: { type: 'fixture' | 'group'; key: string }[] = []
vi.mock('@/store/selection', () => ({ useDeskSelection: () => DESK_SELECTION }))
vi.mock('@/store/cueHolds', () => ({ useCueEdited: () => false }))
let FADE: number | null = null
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: FADE, fadeRemainMs: null }) }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@/components/cues/editor/CueEditor', () => ({
  CueEditor: (props: { cueId: number; tabRequest?: { tab: string } | null }) => (
    <div data-cue-editor={props.cueId} data-tab={props.tabRequest?.tab ?? ''} />
  ),
}))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () => Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

import { StackTimeline } from './StackTimeline'
import { timelineFiltersStore } from '@/lib/stackTimeline/columnOrder'

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
    source: { kind: 'TEMPLATE', id: 7, uuid: 'p7', name: 'Lavender', families: ['COLOUR'] },
    targets: [{ type: 'fixture', key: 'hex-1' }],
    heads: ['hex-1'],
    sortOrder: 0,
    enabled: true,
    amount: 1,
    blendMode: 'OVERRIDE',
    propertyMask: null,
    stomp: false,
    timed: false,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }
}

function entry(entryId: number, over: Partial<TimelineEntry> = {}): TimelineEntry {
  return { entryId, kind: 'CUE', cueId: entryId - 100, layers: [], effects: [], scenery: [], trackedScenery: [], ...over }
}

beforeEach(() => {
  timelineFiltersStore.reset()
  sessionStorage.clear()
  DESK_SELECTION = []
  FADE = null
  TIMELINE = {
    stackId: 10,
    entries: [
      entry(101, { layers: [lavender()], scenery: [{ elementUuid: 'tabs', state: { open: 1 }, transitionMs: 4000 }] }),
      entry(102, { layers: [lavender({ id: 2, amount: 0.5 })], trackedScenery: [{ elementUuid: 'tabs', state: { open: 1 }, fromCueId: 1 }] }),
      { entryId: 150, kind: 'MARKER', label: 'Scene 2', layers: [], effects: [], scenery: [], trackedScenery: [] },
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

  it('opens the pane on one selected cell’s cue at its column’s tab, and folds it over several cues', () => {
    stubLayout()
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    const editor = () => document.querySelector('[data-cue-editor]')
    pressCell(cell(102, PALETTE).firstElementChild as HTMLElement, 400, 1)
    expect(editor()?.getAttribute('data-cue-editor')).toBe('2')
    expect(editor()?.getAttribute('data-tab')).toBe('looks')
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(editor()?.getAttribute('data-tab')).toBe('scenery')
    // A range over two cues folds it.
    fireEvent.keyDown(window, { key: 'ArrowUp', shiftKey: true })
    expect(editor()).toBeNull()
  })

  it('never writes: ⏎ and ⌫ over a cell do nothing a cell could send', () => {
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    fireEvent.keyDown(window, { key: 'ArrowDown' })
    fireEvent.keyDown(window, { key: 'Enter' })
    fireEvent.keyDown(window, { key: 'Backspace' })
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(cell(101, PALETTE).querySelector('[data-rank]')?.textContent).toBe('1')
  })

  it('meets a selected cell with a layer on its parent, and a selected parent with a layer on its cell', () => {
    TIMELINE!.entries[0].layers.push(lavender({ id: 9, targets: [{ type: 'fixture', key: 'bar' }], heads: ['bar'] }))
    TIMELINE!.entries[1].layers.push(lavender({ id: 10, source: { kind: 'LOOK', id: 4, uuid: 'l4', name: 'Pixels', families: [] }, targets: [{ type: 'fixture', key: 'bar.2' }], heads: ['bar.2'] }))
    DESK_SELECTION = [{ type: 'fixture', key: 'bar.1' }]
    const { unmount } = render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    fireEvent.click(screen.getByRole('button', { name: /Selection only/ }))
    expect(cell(101, 'palette:7:fixture:bar')).not.toBeNull()
    expect(cell(101, PALETTE)).toBeNull()
    // bar.2 is not bar.1, and the cell selected is no parent of it.
    expect(cell(101, 'look:4:fixture:bar.2')).toBeNull()
    unmount()
    DESK_SELECTION = [{ type: 'fixture', key: 'bar' }]
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    expect(cell(101, 'look:4:fixture:bar.2')).not.toBeNull()
  })

  it('filters by kind and by the desk selection', () => {
    DESK_SELECTION = [{ type: 'fixture', key: 'hex-2' }]
    render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} />)
    fireEvent.click(screen.getByRole('button', { name: /Selection only/ }))
    // Lavender on Hex 1 reaches no selected head; scenery is no head's and stays.
    expect(cell(101, PALETTE)).toBeNull()
    expect(cell(101, SCENERY)).not.toBeNull()
    act(() => timelineFiltersStore.set({ kinds: ['palette'], family: null, selectionOnly: false }))
    expect(cell(101, PALETTE)).not.toBeNull()
    expect(cell(101, SCENERY)).toBeNull()
  })
})
