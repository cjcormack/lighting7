// @vitest-environment jsdom
import { cueRow, markerRow } from '@/test/cueEntries'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack } from '@/api/cueStacksApi'
import type {
  StackTimeline as StackTimelineData,
  TimelineEditOp,
  TimelineEditResponse,
  TimelineEntry,
  TimelineLayer,
} from '@/api/stackTimelineApi'

/**
 * Editing the Timeline (stack-timeline plan §4.4): every gesture — a second click, ⏎, ⌫, a drag of
 * a run's end, the bar's On · Off · Fill ↓, Replace and Settings, a scenery cell's popover, Undo —
 * is **exactly one** request to `timeline/edit`; locked, each asks to unlock and sends nothing.
 */
let TIMELINE: StackTimelineData | undefined
let ANSWER: TimelineEditResponse
const requests: { ops: TimelineEditOp[] }[] = []
const edit = vi.fn((arg: { ops: TimelineEditOp[] }) => {
  requests.push(arg)
  return { unwrap: () => Promise.resolve(ANSWER) }
})
vi.mock('@/store/cueStacks', () => ({
  useCueStackTimelineQuery: () => ({ data: TIMELINE }),
  useEditCueStackTimelineMutation: () => [edit, { isLoading: false }],
}))
vi.mock('@/store/fixtures', () => ({ useVisibleFixtureListQuery: () => ({ data: [{ key: 'hex-1', name: 'Hex 1' }] }) }))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({
    data: [
      { id: 7, name: 'Lavender', isGeneric: true, family: 'COLOUR', rows: [] },
      { id: 8, name: 'Amber', isGeneric: true, family: 'COLOUR', rows: [] },
    ],
  }),
}))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [{ id: 4, name: 'Warm', families: ['INTENSITY'] }] }) }))
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterListQuery: () => ({ data: [] }) }))
vi.mock('@/store/stageElements', () => ({
  useStageElementListQuery: () => ({
    data: [{ uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', positionZ: 0, params: { operation: 'DRAW', states: { open: 1 } } }],
  }),
}))
vi.mock('@/store/selection', () => ({ useDeskSelection: () => [] }))
let EDITED = false
vi.mock('@/store/cueHolds', () => ({ useCueEdited: () => EDITED }))
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }) }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@/components/cues/editor/CueEditor', () => ({ CueEditor: () => <div data-cue-editor /> }))
vi.mock('@/components/scenery/SceneryControl', () => ({
  SceneryControl: (props: { onWrite: (patch: Record<string, unknown>) => void }) => (
    <button type="button" onClick={() => props.onWrite({ open: 0.5 })}>
      Half
    </button>
  ),
}))
const toast = vi.fn()
vi.mock('sonner', () => ({ toast: (...args: unknown[]) => toast(...args) }))
const navigate = vi.fn()
vi.mock('react-router', async (original) => ({ ...(await original<typeof import('react-router')>()), useNavigate: () => navigate }))
const openUpdateStage = vi.fn()
vi.mock('./cueActionDialogsContext', () => ({ useSharedCueActionDialogs: () => ({ openUpdateStage }) }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () => Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

import { StackTimeline } from './StackTimeline'
import { resetTimelineUndo } from './TimelineEditing'
import { timelineFiltersStore } from '@/lib/stackTimeline/columnOrder'

const STACK: CueStack = {
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cueRow(1, { name: 'One' }), cueRow(2, { name: 'Two' }), markerRow(150, 'Scene 2'), cueRow(3, { name: 'Three' }), cueRow(4, { name: 'Four' })],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
}

function lavender(id: number, over: Partial<TimelineLayer> = {}): TimelineLayer {
  return {
    id,
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

const PALETTE = 'palette:7:fixture:hex-1'
const SCENERY = 'scenery:tabs'

function cell(entryId: number, col: string): HTMLElement {
  return document.querySelector(`[data-row-id="entry:${entryId}"] [data-cell="${col}"]`)!.firstElementChild as HTMLElement
}

/**
 * One press sent and its answer settled. ⌘Z reaches only an Undo step the answer has recorded, and
 * the press's toast is called once it has, so waiting on `edit` alone lets ⌘Z land too early.
 */
async function pressAnswered() {
  await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
  await waitFor(() => expect(toast).toHaveBeenCalledTimes(1))
}

beforeEach(() => {
  requests.length = 0
  edit.mockClear()
  toast.mockClear()
  openUpdateStage.mockClear()
  resetTimelineUndo()
  timelineFiltersStore.reset()
  sessionStorage.clear()
  EDITED = false
  ANSWER = { changed: [2], released: [], before: [{ cueId: 2, layers: [], adHocFx: [], scenery: [] }], edited: [] }
  TIMELINE = {
    stackId: 10,
    entries: [
      entry(101, { layers: [lavender(11, { amount: 0.6 })], scenery: [{ elementUuid: 'tabs', state: { open: 0 }, transitionMs: 4000 }] }),
      entry(102),
      { entryId: 150, kind: 'MARKER', label: 'Scene 2', layers: [], fx: [], scenery: [], trackedScenery: [] },
      entry(103, { layers: [lavender(13)] }),
      entry(104),
    ],
  }
})

afterEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
})

function mount(props: Partial<React.ComponentProps<typeof StackTimeline>> = {}) {
  return render(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} {...props} />)
}

describe('one cell', () => {
  it('a click selects, and a second click toggles: one request', async () => {
    mount()
    fireEvent.click(cell(102, PALETTE))
    expect(edit).not.toHaveBeenCalled()
    fireEvent.click(cell(102, PALETTE))
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops).toEqual([
      { op: 'layerOn', cueId: 2, source: { kind: 'PALETTE', id: 7 }, target: { type: 'fixture', key: 'hex-1' }, settingsFrom: 11 },
    ])
  })

  it('⏎ toggles the selected cell, ⌫ turns it off: one request each', async () => {
    mount()
    fireEvent.click(cell(103, PALETTE))
    fireEvent.keyDown(window, { key: 'Enter' })
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops.map((op) => op.op)).toEqual(['layerOff'])
    fireEvent.keyDown(window, { key: 'Backspace' })
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(2))
    expect(requests[1].ops.map((op) => op.op)).toEqual(['layerOff'])
  })

  it('names what a press released, and its toast’s Undo sends the before back as one restore', async () => {
    ANSWER = {
      changed: [3],
      released: [{ cueId: 3, source: { kind: 'PALETTE', id: 7, uuid: 'p7', name: 'Lavender' }, targets: [{ type: 'fixture', key: 'hex-1' }] }],
      before: [{ cueId: 3, layers: [{ paletteId: 7 }], adHocFx: [], scenery: [] }],
      edited: [],
    }
    TIMELINE!.entries.push(entry(105, { layers: [lavender(15, { source: { kind: 'PALETTE', id: 8, uuid: 'p8', name: 'Amber', families: ['COLOUR'] } })] }))
    mount({ stack: { ...STACK, cues: [...STACK.cues, cueRow(5, { name: 'Five' })] } })
    const amber = 'palette:8:fixture:hex-1'
    fireEvent.click(cell(103, amber))
    fireEvent.click(cell(103, amber))
    await waitFor(() => expect(toast).toHaveBeenCalled())
    const [message, options] = toast.mock.calls[0] as [string, { action: { onClick: () => void } }]
    expect(message).toBe('Amber on Hex 1 in Q3. Lavender released there.')
    act(() => options.action.onClick())
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(2))
    expect(requests[1].ops).toEqual([{ op: 'restore', cueId: 3, layers: [{ paletteId: 7 }], adHocFx: [], scenery: [] }])
  })

  it('⌘Z undoes the last gesture as one request', async () => {
    mount()
    fireEvent.click(cell(102, PALETTE))
    fireEvent.click(cell(102, PALETTE))
    await pressAnswered()
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(2))
    expect(requests[1].ops.map((op) => op.op)).toEqual(['restore'])
  })

  it('⌘Z’s toast drops the Undo it replaced, and an Undo the desk refuses is dropped', async () => {
    mount()
    fireEvent.click(cell(102, PALETTE))
    fireEvent.click(cell(102, PALETTE))
    await pressAnswered()
    edit.mockImplementationOnce((arg: { ops: TimelineEditOp[] }) => {
      requests.push(arg)
      return { unwrap: () => Promise.reject({ status: 400 }) }
    })
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(2))
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await act(async () => {})
    expect(edit).toHaveBeenCalledTimes(2)
  })

  it('a put-back toast carries no Undo', async () => {
    mount()
    fireEvent.click(cell(102, PALETTE))
    fireEvent.click(cell(102, PALETTE))
    await pressAnswered()
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await waitFor(() => expect(toast).toHaveBeenCalledWith('Put back', expect.objectContaining({ action: undefined })))
    const [, options] = toast.mock.calls.at(-1) as [string, object]
    expect('action' in options).toBe(true)
  })

  it('locked, the toast’s Undo asks to unlock and sends nothing', async () => {
    const onRequestUnlock = vi.fn()
    const { rerender } = mount({ onRequestUnlock })
    fireEvent.click(cell(102, PALETTE))
    fireEvent.click(cell(102, PALETTE))
    await waitFor(() => expect(toast).toHaveBeenCalled())
    const [, options] = toast.mock.calls[0] as [string, { action: { onClick: () => void } }]
    rerender(<StackTimeline stack={STACK} projectId={1} activeEntryId={null} locked onRequestUnlock={onRequestUnlock} />)
    act(() => options.action.onClick())
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    expect(edit).toHaveBeenCalledTimes(1)
  })
})

describe('a range', () => {
  function selectColumn() {
    fireEvent.click(cell(101, PALETTE))
    fireEvent.keyDown(window, { key: 'ArrowDown', shiftKey: true })
    fireEvent.keyDown(window, { key: 'ArrowDown', shiftKey: true })
    fireEvent.keyDown(window, { key: 'ArrowDown', shiftKey: true })
  }

  it('On fills every empty cell in one request', async () => {
    mount()
    selectColumn()
    fireEvent.click(document.querySelector('[data-timeline-verb="On"]')!)
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops.map((op) => ('cueId' in op ? `${op.op}:${op.cueId}` : op.op))).toEqual(['layerOn:2', 'layerOn:4'])
  })

  it('Off empties every filled cell in one request, and ⌘D fills down in one', async () => {
    mount()
    selectColumn()
    fireEvent.click(document.querySelector('[data-timeline-verb="Off"]')!)
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops.map((op) => op.op)).toEqual(['layerOff', 'layerOff'])
    fireEvent.keyDown(window, { key: 'd', metaKey: true })
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(2))
    expect(requests[1].ops.map((op) => ('cueId' in op ? `${op.op}:${op.cueId}` : op.op))).toEqual(['layerOn:2', 'layerOn:4'])
  })

  it('a shift-click extends the selection to a range, writing nothing', async () => {
    mount()
    fireEvent.click(cell(101, PALETTE))
    fireEvent.click(cell(104, PALETTE), { shiftKey: true })
    expect(edit).not.toHaveBeenCalled()
    fireEvent.click(document.querySelector('[data-timeline-verb="On"]')!)
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops.map((op) => ('cueId' in op ? `${op.op}:${op.cueId}` : op.op))).toEqual(['layerOn:2', 'layerOn:4'])
  })

  it('disables a verb that would change nothing', () => {
    mount()
    fireEvent.click(cell(101, PALETTE))
    expect(document.querySelector<HTMLButtonElement>('[data-timeline-verb="On"]')!.disabled).toBe(true)
    expect(document.querySelector<HTMLButtonElement>('[data-timeline-verb="Off"]')!.disabled).toBe(false)
  })

  it('Replace with… sends one replace op over the selected cues', async () => {
    mount()
    selectColumn()
    fireEvent.click(document.querySelector('[data-timeline-verb="Replace"]')!)
    fireEvent.change(screen.getByLabelText('Replacement'), { target: { value: 'PALETTE:8' } })
    expect(document.querySelector('[data-replace-readout]')?.textContent).toContain('Q1 and Q3 swap Lavender for Amber')
    fireEvent.click(screen.getByRole('button', { name: /^Replace 2/ }))
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops).toEqual([
      {
        op: 'replace',
        cueIds: [1, 3],
        from: { source: { kind: 'PALETTE', id: 7 }, target: { type: 'fixture', key: 'hex-1' } },
        to: { source: { kind: 'PALETTE', id: 8 }, target: { type: 'fixture', key: 'hex-1' } },
        keepSettings: true,
      },
    ])
  })

  it('Settings… writes every selected layer in one request, reading mixed where they disagree', async () => {
    mount()
    selectColumn()
    fireEvent.click(document.querySelector('[data-timeline-verb="Settings"]')!)
    const amount = screen.getByLabelText('Amount') as HTMLInputElement
    expect(amount.placeholder).toBe('mixed')
    fireEvent.change(amount, { target: { value: '40' } })
    fireEvent.keyDown(amount, { key: 'Enter' })
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops).toEqual([
      { op: 'layerSettings', cueId: 1, layerId: 11, fields: { amount: 0.4 } },
      { op: 'layerSettings', cueId: 3, layerId: 13, fields: { amount: 0.4 } },
    ])
  })

  it('Settings… over differing masks says so, and the first mask chosen is written to every layer', async () => {
    TIMELINE!.entries[3] = entry(103, { layers: [lavender(13, { attributeMask: 'COLOUR' })] })
    mount()
    selectColumn()
    fireEvent.click(document.querySelector('[data-timeline-verb="Settings"]')!)
    expect(screen.getByText(/masks differ/)).toBeTruthy()
    fireEvent.click(screen.getByRole('switch', { name: 'All attributes' }))
    expect(edit).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Beam' }))
    await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
    expect(requests[0].ops).toEqual([
      { op: 'layerSettings', cueId: 1, layerId: 11, fields: { attributeMask: 'INTENSITY,POSITION,COLOUR' } },
      { op: 'layerSettings', cueId: 3, layerId: 13, fields: { attributeMask: 'INTENSITY,POSITION,COLOUR' } },
    ])
  })
})

it('dragging a run’s end down fills the cells it crosses in one request', async () => {
  mount()
  const handle = cell(103, PALETTE).querySelector('[data-timeline-handle]')!
  const target = document.querySelector('[data-row-id="entry:104"]')!
  document.elementFromPoint = vi.fn(() => target)
  fireEvent.pointerDown(handle, { clientX: 400, clientY: 120 })
  act(() => {
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 400, clientY: 150 }))
  })
  expect(document.querySelector('[data-timeline-drag-scope]')?.textContent).toBe('Lavender · Hex 1 on in Q4')
  expect(document.querySelector('[data-row-id="entry:104"] [data-timeline-ghost="fill"]')).toBeTruthy()
  act(() => {
    window.dispatchEvent(new MouseEvent('pointerup', { clientX: 400, clientY: 150 }))
  })
  await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
  expect(requests[0].ops).toEqual([
    { op: 'layerOn', cueId: 4, source: { kind: 'PALETTE', id: 7 }, target: { type: 'fixture', key: 'hex-1' }, settingsFrom: 13 },
  ])
})

it('a scenery cell’s second click opens its popover, whose Clear change is one request', async () => {
  mount()
  fireEvent.click(cell(101, SCENERY))
  fireEvent.click(cell(101, SCENERY))
  expect(document.querySelector('[data-timeline-scenery]')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Half' }))
  await waitFor(() => expect(edit).toHaveBeenCalledTimes(1))
  expect(requests[0].ops).toEqual([{ op: 'scenerySet', cueId: 1, elementUuid: 'tabs', state: { open: 0.5 }, transitionMs: 4000 }])
  fireEvent.click(screen.getByRole('button', { name: /Clear change/ }))
  await waitFor(() => expect(edit).toHaveBeenCalledTimes(2))
  expect(requests[1].ops).toEqual([{ op: 'sceneryClear', cueId: 1, elementUuid: 'tabs' }])
})

it('+ Column adds an empty column for this window', () => {
  mount()
  fireEvent.click(document.querySelector('[data-timeline-add-column]')!)
  fireEvent.click(screen.getByText('Warm'))
  expect(document.querySelector('[data-row-id="entry:101"] [data-cell="look:4:*"]')).toBeTruthy()
  expect(edit).not.toHaveBeenCalled()
})

it('+ Column will not add a generic palette on no target', () => {
  mount()
  fireEvent.click(document.querySelector('[data-timeline-add-column]')!)
  expect(screen.getByText('Amber').closest('button')!.disabled).toBe(true)
})

describe('locked', () => {
  it('a second click, ⏎ and every verb ask to unlock and send nothing', () => {
    const onRequestUnlock = vi.fn()
    mount({ locked: true, onRequestUnlock })
    fireEvent.click(cell(102, PALETTE))
    fireEvent.click(cell(102, PALETTE))
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Stay locked' }))
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Stay locked' }))
    fireEvent.click(document.querySelector('[data-timeline-verb="On"]')!)
    expect(screen.getByRole('alertdialog')).toBeTruthy()
    expect(cell(103, PALETTE).querySelector('[data-timeline-handle]')).toBeNull()
    expect(edit).not.toHaveBeenCalled()
  })
})

it('offers Update stage… with the live row selected while it is edited since GO', () => {
  EDITED = true
  mount({ activeEntryId: 101 })
  fireEvent.click(cell(101, PALETTE))
  fireEvent.click(screen.getByRole('button', { name: 'Update stage…' }))
  expect(openUpdateStage).toHaveBeenCalledWith({ projectId: 1, cueId: 1, name: 'One' })
})
