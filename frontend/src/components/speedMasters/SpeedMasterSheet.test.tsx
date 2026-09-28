// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SpeedMaster } from '../../api/speedMastersApi'

const saveMaster = vi.fn((_args: unknown) => Promise.resolve({}))
const deleteMaster = vi.fn((_args: unknown) => Promise.resolve(undefined))
const setBpm = vi.fn()
const tap = vi.fn()
const toastError = vi.fn()
const toastInfo = vi.fn()

vi.mock('sonner', () => ({ toast: { error: (...a: unknown[]) => toastError(...a), info: (...a: unknown[]) => toastInfo(...a) } }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@/components/BeatIndicator', () => ({ BeatIndicator: () => <span data-testid="beat" /> }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

const U = (n: number) => `aaaaaaaa-0000-0000-0000-00000000000${n}`
const LIVE = vi.hoisted(() => ({
  data: [
    { uuid: 'aaaaaaaa-0000-0000-0000-000000000001', index: 1, name: 'Global', bpm: 128, isRunning: true, source: 'MANUAL' },
    { uuid: 'aaaaaaaa-0000-0000-0000-000000000002', index: 2, name: 'Colour chase', bpm: 64, isRunning: true, source: 'MANUAL' },
    { uuid: 'aaaaaaaa-0000-0000-0000-000000000003', index: 3, name: 'Movement', bpm: 96, isRunning: true, source: 'MANUAL' },
    { uuid: 'aaaaaaaa-0000-0000-0000-000000000004', index: 4, name: 'Strobe hits', bpm: 128, isRunning: true, source: 'MANUAL' },
    { uuid: 'aaaaaaaa-0000-0000-0000-000000000005', index: 5, name: 'Slow wash', bpm: 42, isRunning: true, source: 'TAP' },
  ],
}))

vi.mock('../../store/speedMasters', () => ({
  useSaveSpeedMasterMutation: () => [(args: unknown) => ({ unwrap: () => saveMaster(args) })],
  useDeleteSpeedMasterMutation: () => [(args: unknown) => ({ unwrap: () => deleteMaster(args) })],
  useSpeedMasterLiveQuery: (_arg: unknown, opts?: { skip?: boolean }) => (opts?.skip ? { data: undefined } : LIVE),
  setSpeedMasterBpm: (uuid: string | null, bpm: number) => setBpm(uuid, bpm),
  tapSpeedMaster: (uuid: string | null) => tap(uuid),
}))

import { SpeedMasterSheet } from './SpeedMasterSheet'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

function m(n: number, over: Partial<SpeedMaster> = {}): SpeedMaster {
  const names = ['', 'Global', 'Colour chase', 'Movement', 'Strobe hits', 'Slow wash', 'Mover chase']
  return { id: n, uuid: U(n), masterIndex: n, name: names[n], bpm: 120, source: 'MANUAL', notes: null, referenceCount: 0, ...over }
}

/**
 * The board's bank: M1 the global tempo, M2 and M4 following it at ½ and 1×, M3 and M5 manual, and
 * M6 following M3 — so M6 is M3's descendant, the one a link from M3 to it would loop through.
 */
const BANK: SpeedMaster[] = [
  m(1, { referenceCount: 14, notes: 'The desk tempo' }),
  m(2, { followNum: 1, followDen: 2, usage: 'colour', referenceCount: 6 }),
  m(3, { bpm: 96, usage: 'position' }),
  m(4, { followNum: 1, followDen: 1, usage: 'dimmer' }),
  m(5, { bpm: 60 }),
  m(6, { followNum: 1, followDen: 2, followTargetUuid: U(3) }),
]

/** Stubbed column bands for the marquee, left to right in `SpeedMasterSheet`'s order — layout only as the marquee measures it. */
const BANDS: Record<string, [number, number]> = {
  bpm: [248, 352],
  start: [416, 496],
  follows: [496, 624],
  ratio: [624, 696],
  usage: [696, 800],
  notes: [872, 1032],
}
const CENTRE = (col: string) => (BANDS[col][0] + BANDS[col][1]) / 2

function stubLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 220)
    const col = this.getAttribute('data-column-header')
    if (col && BANDS[col]) return rect(...BANDS[col])
    return rect(0, 1100)
  })
}

function draw(over: Partial<React.ComponentProps<typeof SpeedMasterSheet>> = {}) {
  const onOpenMaster = vi.fn()
  render(
    <SpeedMasterSheet projectId={1} masters={BANK} bank={BANK} isCurrentProject onOpenMaster={onOpenMaster} {...over} />,
  )
  return { onOpenMaster }
}

function row(n: number): HTMLElement {
  return document.querySelector(`[data-row-id="sm:${n}"]`) as HTMLElement
}

/** A marquee down one column over rows `from`..`to` (1-based master numbers, 36px rows). */
function drag(col: string, from: number, to: number) {
  const start = row(from)
  fireEvent.pointerDown(start, { button: 0, clientX: CENTRE(col), clientY: (from - 1) * 36 + 10 })
  fireEvent.pointerMove(start, { button: 0, buttons: 1, clientX: CENTRE(col) + 4, clientY: (to - 1) * 36 + 26 })
  fireEvent.pointerUp(start, { button: 0, clientX: CENTRE(col) + 4, clientY: (to - 1) * 36 + 26 })
  const cell = within(start).queryAllByRole('button').find((b) => b.closest(`[data-cell="${col}"]`))
  fireEvent.click(cell ?? start)
}

beforeEach(() => {
  stubLayout()
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
  saveMaster.mockClear()
  deleteMaster.mockClear()
  setBpm.mockClear()
  tap.mockClear()
  toastError.mockClear()
  toastInfo.mockClear()
})

describe('SpeedMasterSheet', () => {
  it('Set over five BPMs writes the three manual running clocks now, skips the followers by name, and leaves Start alone', async () => {
    draw()
    drag('bpm', 1, 5)
    expect(screen.getByText('5 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    // The read-out names the followers the kit dropped (D12) — the board's own sentence.
    expect(await screen.findByText('M2 and M4 follow M1 · skipped')).toBeInTheDocument()
    const field = screen.getByLabelText('BPM')
    fireEvent.change(field, { target: { value: '90' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(setBpm.mock.calls).toEqual([
      [null, 90], // master 1 by its null uuid
      [U(3), 90],
      [U(5), 90],
    ])
    // BPM is the live tempo (the socket); Start is the stored row and nothing PUTs it.
    expect(saveMaster).not.toHaveBeenCalled()
  })

  it('shows a follower’s tempo as a read-out with its ratio, and its TAP inert', () => {
    draw()
    expect(row(2).querySelector('[data-cell="bpm"]')).toBeNull()
    expect(row(2)).toHaveTextContent('64.0½ of M1')
    expect(within(row(2)).getByRole('button', { name: 'Tap tempo for master 2' })).toBeDisabled()
    fireEvent.click(within(row(3)).getByRole('button', { name: 'Tap tempo for master 3' }))
    expect(tap).toHaveBeenCalledWith(U(3))
    fireEvent.click(within(row(1)).getByRole('button', { name: 'Tap tempo for master 1' }))
    expect(tap).toHaveBeenLastCalledWith(null)
  })

  it('draws a cell with nothing to set blank, as the programmer does — no dot, no dash', () => {
    draw()
    const cells = (n: number) => [...row(n).children].slice(1) as HTMLElement[]
    // M1: Follows and Ratio (columns 5 and 6 after the beat · BPM · Tap · Start tracks).
    const m1 = cells(1)
    expect(m1[4].textContent).toBe('')
    expect(m1[5].textContent).toBe('')
    // M2 follows: its Start is blank.
    expect(cells(2)[3].textContent).toBe('')
    expect(row(1)).not.toHaveTextContent('·')
  })

  it('off the current project BPM and TAP are read-only, and the live bank is not read (D12, §1’s bug)', () => {
    draw({ isCurrentProject: false })
    for (const n of [1, 3, 5]) {
      expect(row(n).querySelector('[data-cell="bpm"]')).toBeNull()
      expect(within(row(n)).getByRole('button', { name: `Tap tempo for master ${n}` })).toBeDisabled()
    }
    // The stored tempo, not the running show's 128.
    expect(row(1)).toHaveTextContent('120.0')
    expect(screen.queryAllByTestId('beat')).toHaveLength(0)
    // Start stays editable: the REST route is `withProject`.
    expect(row(3).querySelector('[data-cell="start"] button')).not.toBeNull()
  })

  it('Start writes the stored boot tempo per manual master, and skips followers', async () => {
    draw()
    drag('start', 2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(await screen.findByText('M2 follows M1 · skipped')).toBeInTheDocument()
    const field = screen.getByLabelText('Start')
    fireEvent.change(field, { target: { value: '100' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(saveMaster.mock.calls).toEqual([[{ projectId: 1, masterId: 3, bpm: 100 }]])
    expect(setBpm).not.toHaveBeenCalled()
  })

  it('Follows carries the origin’s leader to every row, and skips a row that may not follow it', async () => {
    draw()
    drag('follows', 2, 5)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'M3 · Movement' }))
    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(3))
    expect(saveMaster.mock.calls.map(([a]) => a)).toEqual([
      // M2 re-pointed, keeping its ratio; both halves with the target, never a bpm.
      { projectId: 1, masterId: 2, followTargetUuid: U(3), followNum: 1, followDen: 2 },
      { projectId: 1, masterId: 4, followTargetUuid: U(3), followNum: 1, followDen: 1 },
      // A fresh link starts at the default ratio.
      { projectId: 1, masterId: 5, followTargetUuid: U(3), followNum: 1, followDen: 2 },
    ])
    // M3 cannot follow itself — dropped before the desk's FOLLOW_CYCLE, and said.
    expect(toastInfo).toHaveBeenCalledWith(expect.stringMatching(/^M3 skipped — it cannot follow M3/), expect.anything())
  })

  it('does not offer a master its own descendants, and names master 1’s two spellings as one', async () => {
    draw()
    drag('follows', 3, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
    // M3 is itself and M6 follows it: a link to either would loop.
    expect(options).toEqual(['Manual', 'M1 · Global', 'M2 · Colour chase', 'M4 · Strobe hits', 'M5 · Slow wash'])
    fireEvent.keyDown(screen.getAllByRole('option')[0], { key: 'Escape' })
    cleanup()
    // M2 follows M1 by the null spelling; choosing M1 by its uuid is no change, and sends nothing.
    draw()
    drag('follows', 2, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'M1 · Global' }))
    expect(saveMaster).not.toHaveBeenCalled()
  })

  it('Clear on Follows unlinks, sending all three nulls, and skips master 1', async () => {
    draw()
    // Dragged upward from M3: master 1 has no Follows cell to press on, only the blank it is.
    drag('follows', 3, 1)
    expect(screen.getByText('3 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster).toHaveBeenCalledWith({ projectId: 1, masterId: 2, followNum: null, followDen: null, followTargetUuid: null })
  })

  it('Ratio sends both halves with no target and no bpm, and skips a manual master', async () => {
    draw()
    drag('ratio', 2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(await screen.findByText('M3 runs manually · skipped')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('option', { name: '⅓' }))
    expect(saveMaster.mock.calls).toEqual([[{ projectId: 1, masterId: 2, followNum: 1, followDen: 3 }]])
  })

  it('Usage over two rows is refused before anything is sent; over one, the desk’s refusal is toasted by code', async () => {
    draw()
    drag('usage', 3, 5)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'Colour' }))
    expect(saveMaster).not.toHaveBeenCalled()
    expect(toastError).toHaveBeenCalledWith(expect.stringMatching(/^A usage belongs to one master/), {
      id: 'sheet-write:speed-masters:usage',
    })
    cleanup()

    saveMaster.mockImplementationOnce(() =>
      Promise.reject({ status: 409, data: { error: 'Colour is already M2’s', code: 'SPEED_MASTER_USAGE_TAKEN' } }),
    )
    draw()
    drag('usage', 5, 5)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'Colour' }))
    expect(saveMaster).toHaveBeenCalledWith({ projectId: 1, masterId: 5, usage: 'colour' })
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Colour is already M2’s', { id: 'sheet-write:speed-masters:usage' }),
    )
  })

  it('⏎ over one row opens its detail sheet; the pencil does too', () => {
    const { onOpenMaster } = draw()
    fireEvent.click(row(3).querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onOpenMaster).toHaveBeenCalledWith(BANK[2])
    fireEvent.click(screen.getByRole('button', { name: 'Edit M3 · Movement' }))
    expect(onOpenMaster).toHaveBeenCalledTimes(2)
  })

  it('Delete skips master 1 by name and deletes the rest', async () => {
    draw()
    fireEvent.click(row(1).querySelector('[data-first-column]')!)
    fireEvent.click(row(3).querySelector('[data-first-column]')!, { metaKey: true })
    fireEvent.click(screen.getByRole('button', { name: /Delete/ }))
    await waitFor(() => expect(deleteMaster).toHaveBeenCalledTimes(1))
    expect(deleteMaster).toHaveBeenCalledWith({ projectId: 1, masterId: 3, force: false })
    expect(toastInfo).toHaveBeenCalledWith(expect.stringMatching(/^M1 · Global skipped/), expect.anything())
  })
})
