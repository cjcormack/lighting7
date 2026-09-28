// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The DMX sheet's own rules on the kit (CLAUDE.md §Sheet kit): a 16-wide grid of every address,
 * a marquee across a row selects addresses, Set writes each through `channels.update`, Clear
 * writes 0, Park parks each at its current value — and an offline desk is the read-only scope, in
 * the four places the programmer's is.
 */
const updateChannel = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const parkChannel = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const unparkChannel = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/channels', () => ({ useUpdateChannelMutation: () => [updateChannel] }))
vi.mock('@/store/park', () => ({
  useParkChannelMutation: () => [parkChannel],
  useUnparkChannelMutation: () => [unparkChannel],
}))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
// Live values, driven directly: channel 7 sits at 100, everything else at 0.
vi.mock('@/hooks/usePropertyValues', () => ({
  useChannelValue: ({ channelNo }: { channelNo: number }) => (channelNo === 7 ? 100 : 0),
}))
// The programmer as the ownership tests set it: key states by `target|property`, and the
// channel sideband. Both are reset after every test.
const keyStates = new Map<string, unknown>()
let sidebandChannels: unknown[] = []
/** Live `subscribeToKey` callbacks by `target|property`, so a test can push a key the way the desk does. */
const keyListeners = new Map<string, Set<(state: unknown) => void>>()
function pushKey(key: string, state: unknown) {
  keyStates.set(key, state)
  keyListeners.get(key)?.forEach((fn) => fn(state))
}
vi.mock('@/api/lightingApi', () => ({
  lightingApi: {
    channels: { get: (_u: number, c: number) => (c === 7 ? 100 : 0) },
    programmer: {
      subscribe: () => ({ unsubscribe() {} }),
      subscribeToKey: (targetKey: string, propertyName: string, fn: (state: unknown) => void) => {
        const key = `${targetKey}|${propertyName}`
        const set = keyListeners.get(key) ?? new Set()
        set.add(fn)
        keyListeners.set(key, set)
        return { unsubscribe: () => set.delete(fn) }
      },
      getKeyState: (targetKey: string, propertyName: string) => keyStates.get(`${targetKey}|${propertyName}`) ?? {},
      getState: () => ({ channels: sidebandChannels }),
      isBlind: () => false,
    },
  },
}))
// jsdom lays nothing out, so the real virtualizer renders no rows. Stubbed to render the first
// two — 32 cells, each a Radix popover — which is what a short viewport would show; rendering all
// 512 is what a real desk never does and what times a jsdom test out under a loaded suite.
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: Math.min(count, 2) }, (_, index) => ({
        index,
        key: index,
        start: index * estimateSize(),
        size: estimateSize(),
      })),
    scrollToIndex: () => {},
  }),
}))

import { DmxSheet, DMX_ROW_HEIGHT, DMX_ROW_WIDTHS, resetOverflowObserver } from './DmxSheet'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

const MAPPINGS = {
  1: { fixtureKey: 'par-1', fixtureName: 'Front PAR 1', description: 'Dim', properties: [{ targetKey: 'par-1', propertyName: 'dimmer' }] },
  2: { fixtureKey: 'par-1', fixtureName: 'Front PAR 1', description: 'Red', properties: [{ targetKey: 'par-1', propertyName: 'rgbColour' }] },
  // A bundled amber, as the desk names it: its own slider *and* a component of the colour.
  3: {
    fixtureKey: 'par-1',
    fixtureName: 'Front PAR 1',
    description: 'amber',
    properties: [
      { targetKey: 'par-1', propertyName: 'rgbColour' },
      { targetKey: 'par-1', propertyName: 'amber' },
    ],
  },
  7: { fixtureKey: 'par-2', fixtureName: 'Front PAR 2', description: 'Dim', properties: [{ targetKey: 'par-2', propertyName: 'dimmer' }] },
  8: { fixtureKey: 'par-2', fixtureName: 'Front PAR 2', description: 'Red', properties: [{ targetKey: 'par-2', propertyName: 'rgbColour' }] },
  // A head of a pixel bar: the desk names the element's keys; nothing lifts a write onto them.
  17: { fixtureKey: 'bar', fixtureName: 'Bar', description: 'Head 1 White', properties: [{ targetKey: 'bar.pixel-0', propertyName: 'white' }] },
}

const ENTRY = { value: '50', owner: 'web', touched: true, owners: ['web'] }

/** The ownership classes on an address's wrapper — the ring or the baseline dim. */
function ownershipClass(channelNo: number): string {
  let el: HTMLElement | null = cell(channelNo)
  while (el && !el.hasAttribute('data-cell')) {
    if (/opacity-55|ring-inset/.test(el.className)) return el.className
    el = el.parentElement
  }
  return ''
}

function draw(over: Partial<React.ComponentProps<typeof DmxSheet>> = {}) {
  return render(
    <DmxSheet universe={1} connected mappings={MAPPINGS} parkValueMap={new Map()} {...over} />,
  )
}

/**
 * The column bands, 64px each after a 48px row head; rows are 56px from a 0-high header.
 *
 * `width` is what every other element measures, and the sheet reads its own to decide how many
 * addresses a row holds — 1100 is a desk, and the two narrow arms need less than 1072 and 560.
 */
function stubFlatLayout(width = 1100) {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    const col = this.getAttribute('data-column-header')
    if (col) {
      const i = Number(col.slice(1))
      return rect(48 + i * 64, 48 + (i + 1) * 64)
    }
    return rect(0, width)
  })
}

/** The row-head labels the virtualiser drew — `001`, `017`, … at sixteen wide. */
function rowBases(): string[] {
  return [...document.querySelectorAll('[data-row-id] > span, [data-row-id] > div > span')]
    .map((el) => el.textContent ?? '')
    .filter((t) => /^\d{3}$/.test(t))
}

/** A cell face's three lines — address and value, fixture, use — as text. */
function faceLines(channelNo: number): string[] {
  const face = cell(channelNo).firstElementChild!
  return [...face.children].map((line) => line.textContent ?? '')
}

/** The address cell's trigger, found by its zero-padded number on the first line. */
function cell(channelNo: number): HTMLElement {
  // The row head says `001` too, so take the one inside a cell trigger.
  const match = screen
    .getAllByText(String(channelNo).padStart(3, '0'))
    .map((el) => el.closest('button'))
    .find((button): button is HTMLButtonElement => button != null)
  if (!match) throw new Error(`no cell for ${channelNo}`)
  return match
}

/** A marquee along row 0 from column `from` to column `to`. */
function dragRow0(from: number, to: number) {
  const el = cell(from + 1)
  fireEvent.pointerDown(el, { button: 0, clientX: 48 + from * 64 + 10, clientY: 10 })
  fireEvent.pointerMove(el, { button: 0, buttons: 1, clientX: 48 + to * 64 + 30, clientY: 20 })
  fireEvent.pointerUp(el, { button: 0, clientX: 48 + to * 64 + 30, clientY: 20 })
  fireEvent.click(el)
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
  // The overflow observer is one per page; without this every test would reuse the first one's stub.
  resetOverflowObserver()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  updateChannel.mockClear()
  parkChannel.mockClear()
  unparkChannel.mockClear()
  keyStates.clear()
  keyListeners.clear()
  sidebandChannels = []
})

describe('DmxSheet', () => {
  it('draws the widest arm the container fits, halving to eight and then four', () => {
    // Driven off the exported arms rather than three hand-written numbers, so adding a fourth arm
    // is covered by this test rather than silently outside it. Each needs `48 + 64 × n` of
    // container; one pixel under that floor is the next arm down.
    expect(DMX_ROW_WIDTHS).toEqual([16, 8, 4])
    for (const columns of DMX_ROW_WIDTHS) {
      cleanup()
      vi.restoreAllMocks()
      stubFlatLayout(48 + 64 * columns)
      draw()
      expect(screen.getAllByText(`+${columns - 1}`)).toHaveLength(1)
      expect(screen.queryByText(`+${columns}`)).not.toBeInTheDocument()
      // The row bases stay round addresses whichever arm is showing: 001, then 1 + columns.
      expect(rowBases()).toEqual(['001', String(1 + columns).padStart(3, '0')])
    }
  })

  it('draws sixteen addresses to a row at 56px, naming the fixture and the use on every patched cell', () => {
    draw()
    expect(screen.getAllByText('+15')).toHaveLength(1)
    expect(DMX_ROW_HEIGHT).toBe(56)
    // The address beside its value, then the fixture, then the use — on the first cell of a run
    // and on every other alike.
    for (const [channelNo, use, fixture] of [
      [1, 'Dim', 'Front PAR 1'],
      [2, 'Red', 'Front PAR 1'],
      [8, 'Red', 'Front PAR 2'],
    ] as const) {
      const lines = faceLines(channelNo)
      expect(lines).toEqual([`${String(channelNo).padStart(3, '0')}0`, fixture, use])
    }
    expect(faceLines(7)[0]).toBe('007100')
    // An unpatched address keeps its text lines, empty, so its value lines up with its neighbours'.
    expect(faceLines(5)).toEqual(['0050', '', ''])
    // No cell of a run is drawn louder than another: the first-cell emphasis went with the name
    // moving onto every cell, so the first cell's lines are styled exactly as the second's.
    const styles = (channelNo: number) => [...cell(channelNo).firstElementChild!.querySelectorAll('*')].map((el) => el.className)
    expect(styles(1)).toEqual(styles(2))
  })

  it('cuts a long fixture name or use at its beginning, fading in only where it overflows', () => {
    // jsdom lays nothing out, so the widths are stubbed: the bar's use is wider than its box and
    // everything else fits.
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.textContent === 'Head 1 White' ? 90 : 20
    })
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(50)
    draw()
    // An RTL box overflows off its left edge, aligned left so a short line still reads from the
    // left, around an LTR isolate so a trailing `)` stays where it was typed.
    const lines = [...cell(17).querySelectorAll('[data-leading-cut]')]
    expect(lines.map((line) => line.textContent)).toEqual(['Bar', 'Head 1 White'])
    for (const line of lines) {
      expect(line.className).toMatch(/\[direction:rtl\]/)
      expect(line.className).toMatch(/\btext-left\b/)
      expect(line.className).toMatch(/\boverflow-hidden\b/)
      // No ellipsis: a cut landing on a space drew `… Lightbar`. The fade has no first character.
      expect(line.className).not.toMatch(/\btruncate\b|text-ellipsis/)
      expect(line.firstElementChild?.tagName).toBe('BDI')
      expect(line.firstElementChild).toHaveAttribute('dir', 'ltr')
    }
    // The fade is keyed on the line overflowing, so a name that fits keeps its first letter whole.
    expect(lines[0]).not.toHaveAttribute('data-overflow')
    expect(lines[1]).toHaveAttribute('data-overflow', 'true')
    expect(lines[1].className).toContain('data-[overflow=true]:[mask-image:')
    // The address is never cut, so it is not laid out right-to-left.
    expect(faceLines(17)[0]).toBe('0170')
    expect(cell(17).firstElementChild!.firstElementChild!.hasAttribute('data-leading-cut')).toBe(false)
  })

  it('re-marks a line when the shared observer reports it, and watches no empty line', () => {
    // A stub that keeps what it is asked to watch, so the test can report sizes the way the
    // browser would.
    // The sheet's width band builds an observer of its own, so each callback is kept by what its
    // observer watches rather than as "the" callback.
    const watched = new Map<Element, (entries: { target: Element }[]) => void>()
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private cb: (entries: { target: Element }[]) => void) {}
        observe(el: Element) {
          watched.set(el, this.cb)
        }
        unobserve(el: Element) {
          watched.delete(el)
        }
        disconnect() {}
      },
    )
    const report = (target: Element) => watched.get(target)!([{ target }])
    let wide = false
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return wide && this.textContent === 'Head 1 White' ? 90 : 20
    })
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(50)
    const { rerender } = draw()
    const use = cell(17).querySelectorAll('[data-leading-cut]')[1]
    const text = use.querySelector('bdi')!
    // Both the line's box and its text are watched; an unpatched address's empty lines are not.
    expect(watched.has(use)).toBe(true)
    expect(watched.has(text)).toBe(true)
    for (const empty of cell(5).querySelectorAll('[data-leading-cut]')) expect(watched.has(empty)).toBe(false)
    expect(use).not.toHaveAttribute('data-overflow')
    // The text grows past its box — a font or zoom change — and the observer reports the text.
    wide = true
    act(() => report(text))
    expect(use).toHaveAttribute('data-overflow', 'true')
    // The box widens instead, and the observer reports the line itself.
    wide = false
    act(() => report(use))
    expect(use).not.toHaveAttribute('data-overflow')
    // A line that loses its text stops being watched and drops a fade it had.
    wide = true
    act(() => report(text))
    expect(use).toHaveAttribute('data-overflow', 'true')
    const unpatched = { ...MAPPINGS } as Partial<typeof MAPPINGS>
    delete unpatched[17]
    rerender(<DmxSheet universe={1} connected mappings={unpatched as typeof MAPPINGS} parkValueMap={new Map()} />)
    const emptied = cell(17).querySelectorAll('[data-leading-cut]')[1]
    expect(emptied.textContent).toBe('')
    expect(emptied).not.toHaveAttribute('data-overflow')
    expect(watched.has(emptied)).toBe(false)
  })

  it('says the whole of a cell on hover, whatever the ellipsis cut off', () => {
    keyStates.set('par-1|rgbColour', { entry: { targetKey: 'par-1', propertyName: 'rgbColour', ...ENTRY } })
    draw()
    expect(cell(2).closest('[title]')).toHaveAttribute('title', '002 · Front PAR 1 · Red — Programmer')
    expect(cell(1).closest('[title]')).toHaveAttribute('title', '001 · Front PAR 1 · Dim')
    expect(cell(5).closest('[title]')).toHaveAttribute('title', '005 · Unpatched')
  })

  it("marks where each fixture's footprint begins, outside the ownership ring", () => {
    draw()
    const edged = [...document.querySelectorAll('[data-run-edge]')].map(
      (edge) => edge.parentElement!.querySelector('button')!.textContent!.slice(0, 3),
    )
    // par-1 at 001–003, par-2 at 007–008, the bar's head at 017; nothing else starts a run.
    expect(edged).toEqual(['001', '007', '017'])
    // A sibling of the ownership wrapper, so the baseline dim does not fade a footprint.
    const edge = cell(1).closest('[data-cell]')!.querySelector('[data-run-edge]')!
    expect(edge.closest('[data-ownership]')).toBeNull()
  })

  it('paints nothing above the ownership ring, so it reads the same on every run', () => {
    // The ring is an inset box-shadow on the ownership wrapper, which paints beneath the wrapper's
    // children: any background on the face covers it. The alternating footprint tint did, so an
    // owned address on a tinted run read fainter than the same owner beside it.
    keyStates.set('par-1|rgbColour', { entry: { targetKey: 'par-1', propertyName: 'rgbColour', ...ENTRY } })
    keyStates.set('par-2|rgbColour', { entry: { targetKey: 'par-2', propertyName: 'rgbColour', ...ENTRY } })
    draw({ parkValueMap: new Map([[3, 40]]) })
    for (const channelNo of [2, 3, 8]) {
      const wrapper = cell(channelNo).closest('[data-ownership]')!
      expect(wrapper.className).toMatch(/ring-inset/)
      // A hover highlight is the trigger's own and transient; nothing else may carry a fill.
      const painted = [wrapper, ...wrapper.querySelectorAll('*')]
        .slice(1)
        .filter((el) => el.getAttribute('class')?.split(/\s+/).some((c) => c.startsWith('bg-')))
      expect(painted).toEqual([])
    }
    // Two runs, one owner, one ring.
    expect(ownershipClass(2)).toBe(ownershipClass(8))
  })

  it("rings an address through whichever of the desk's keys holds the entry", () => {
    // The write lifted to the amber slider — the second key. Reading only the colour (what the
    // sheet's own descriptor map did) left a value set here drawn as idle.
    keyStates.set('par-1|amber', { entry: { targetKey: 'par-1', propertyName: 'amber', ...ENTRY } })
    draw()
    expect(ownershipClass(3)).toContain('ring-primary')
    expect(ownershipClass(3)).not.toContain('border-dashed')
    expect(ownershipClass(2)).toContain('opacity-55')
  })

  it('draws one owner per address when its keys disagree — the strongest, never dashed', () => {
    keyStates.set('par-1|rgbColour', { provenance: { targetKey: 'par-1', propertyName: 'rgbColour', source: 'CUE' } })
    keyStates.set('par-1|amber', { entry: { targetKey: 'par-1', propertyName: 'amber', ...ENTRY } })
    draw()
    expect(ownershipClass(3)).toContain('ring-primary')
    expect(ownershipClass(3)).not.toContain('border-dashed')
    expect(ownershipClass(2)).toContain('ring-sky-500')
  })

  it('moves a ring when the desk pushes a key, without a remount', () => {
    draw()
    expect(ownershipClass(3)).toContain('opacity-55')
    act(() => pushKey('par-1|amber', { entry: { targetKey: 'par-1', propertyName: 'amber', ...ENTRY } }))
    expect(ownershipClass(3)).toContain('ring-primary')
    // The other key of the same address pushing afterwards must not lose the first one's state.
    act(() => pushKey('par-1|rgbColour', { provenance: { targetKey: 'par-1', propertyName: 'rgbColour', source: 'CUE' } }))
    expect(ownershipClass(3)).toContain('ring-primary')
    act(() => pushKey('par-1|amber', {}))
    expect(ownershipClass(3)).toContain('ring-sky-500')
  })

  it('lets a sideband slot beat a cue on its address, but never an effect', () => {
    keyStates.set('par-1|rgbColour', { provenance: { targetKey: 'par-1', propertyName: 'rgbColour', source: 'CUE' } })
    keyStates.set('par-2|rgbColour', { provenance: { targetKey: 'par-2', propertyName: 'rgbColour', source: 'EFFECT' } })
    sidebandChannels = [
      { universe: 1, channel: 2, value: 80, owner: 'web', touched: true },
      { universe: 1, channel: 8, value: 80, owner: 'web', touched: true },
    ]
    draw()
    // Programmer output composes over the cue layers on the wire.
    expect(ownershipClass(2)).toContain('ring-primary')
    // A programmer-band effect outranks the programmer, and the desk's verdict already weighed the slot.
    expect(ownershipClass(8)).toContain('ring-violet-500')
  })

  it('dims an address the desk names no property for, like any idle one', () => {
    draw()
    // 005 is unpatched; 001 is patched and idle. Both read baseline.
    expect(ownershipClass(5)).toContain('opacity-55')
    expect(ownershipClass(1)).toContain('opacity-55')
  })

  it("rings a sideband write on this universe as the programmer's, patched or not", () => {
    sidebandChannels = [
      { universe: 1, channel: 5, value: 80, owner: 'web', touched: true },
      { universe: 1, channel: 17, value: 50, owner: 'web', touched: true },
      // Another universe's slot at the same address rings nothing here.
      { universe: 2, channel: 6, value: 50, owner: 'web', touched: true },
    ]
    draw()
    expect(ownershipClass(5)).toContain('ring-primary')
    expect(ownershipClass(17)).toContain('ring-primary')
    expect(ownershipClass(6)).toContain('opacity-55')
  })

  it('leaves a parked address parked whatever the sideband holds', () => {
    sidebandChannels = [{ universe: 1, channel: 8, value: 50, owner: 'web', touched: true }]
    draw({ parkValueMap: new Map([[8, 60]]) })
    expect(ownershipClass(8)).toContain('ring-amber-500')
  })

  it('selects a run of addresses with a marquee and sets them with one editor', async () => {
    draw()
    // 007–010: columns 6..9 of row 0.
    dragRow0(6, 9)
    expect(screen.getByText('4 channels')).toBeInTheDocument()
    expect(screen.getByText('Value')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Value')
    fireEvent.change(field, { target: { value: '178' } })
    for (const channelNo of [7, 8, 9, 10]) {
      expect(updateChannel).toHaveBeenCalledWith({ universe: 1, channelNo, value: 178 })
    }
  })

  it('clears the selected addresses to 0 and parks them at their current values', () => {
    draw()
    dragRow0(6, 7)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    expect(updateChannel).toHaveBeenCalledWith({ universe: 1, channelNo: 7, value: 0 })
    expect(updateChannel).toHaveBeenCalledWith({ universe: 1, channelNo: 8, value: 0 })
    fireEvent.click(screen.getByRole('button', { name: /Park$/ }))
    expect(parkChannel).toHaveBeenCalledWith({ universe: 1, channelNo: 7, value: 100 })
    expect(parkChannel).toHaveBeenCalledWith({ universe: 1, channelNo: 8, value: 0 })
  })

  it('unparks only the parked addresses in the selection', () => {
    draw({ parkValueMap: new Map([[8, 60]]) })
    dragRow0(6, 7)
    expect(cell(8)).toHaveTextContent('60')
    fireEvent.click(screen.getByRole('button', { name: /Unpark/ }))
    expect(unparkChannel).toHaveBeenCalledTimes(1)
    expect(unparkChannel).toHaveBeenCalledWith({ universe: 1, channelNo: 8 })
  })

  it('makes every cell inert with the desk offline — pointer, keyboard, verbs and Spread', async () => {
    draw({ connected: false })
    // The trigger is disabled and its wrapper takes no pointer, so Tab-then-Enter goes nowhere.
    expect(cell(7)).toBeDisabled()
    expect(cell(7).closest('[data-cell]')!.className).toContain('pointer-events-none')
    // The marquee still arms — its press sits on the rows wrapper — and the verbs then say why.
    dragRow0(6, 7)
    expect(screen.getByText('2 channels')).toBeInTheDocument()
    for (const name of ['Set', 'Clear cells', 'Spread']) {
      const button = screen.getByRole('button', { name })
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('title', expect.stringMatching(/desk|connect/i))
    }
    await act(async () => {
      fireEvent.keyDown(window, { key: 'Enter' })
      fireEvent.keyDown(window, { key: 'Backspace' })
    })
    expect(updateChannel).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Value')).toBeNull()
  })
})
