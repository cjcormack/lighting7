// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SpreadRequest, SpreadResponse } from '@/store/programmerOps'

/**
 * The programmer's Spread (editor-kit plan D3–D6, D15): the marquee's heads as the request's
 * targets — a group row to its **visible** members, an element row as a cell — with the press's
 * families; the family answered from the column and the Property row from what the heads can take;
 * Over defaulting to Heads and offering Cells with the count; Local sending **no** `write` key; a
 * focused Look layer sending `write: false` and landing the answered literals in the layer's draft
 * — and refusing an intent-shaped answer from an older desk; Output and a focused template layer
 * refusing with the words Set and Clear use; Speed as a raw client walk; Gobo and Prism as no plan.
 */

vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
import { toast } from 'sonner'

// The raw arm writes through `useCellWriters`, which reaches `lightingApi` — and the socket opens at import.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
const writers = vi.hoisted(() => ({ writeSlider: vi.fn(), writeColour: vi.fn(), writePosition: vi.fn(), writeSetting: vi.fn(), clearValue: vi.fn() }))
vi.mock('./useCellWriters', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./useCellWriters')>()),
  useCellWriters: () => writers,
}))

const focusedTemplate = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('../programmer/FocusedTemplateLayer', () => ({ useFocusedTemplateLayer: () => focusedTemplate.current }))
const scopeState = vi.hoisted(() => ({ current: null as null | { kind: 'output' | 'local' } | { kind: 'layer'; layerId: number } }))
vi.mock('../programmer/ProgrammerScope', () => ({ useProgrammerScope: () => scopeState.current }))
const lookStore = vi.hoisted(() => ({ current: null as null | { setValue: (targetKey: string, propertyName: string, value: string) => void; lookName?: string } }))
vi.mock('../programmer/LookRowStore', () => ({ useLookRowStore: () => lookStore.current, lookRowKey: (t: string, p: string) => `${t}|${p}` }))
// The desk's mask, distinguishable from the marquee's so the test can see which one a request carries.
const deskMask = vi.hoisted(() => ({ current: ['POSITION'] as string[] | null }))
vi.mock('../../store/selection', () => ({ usePressFamilies: () => deskMask.current }))
vi.mock('../../store/templates', () => ({ useTemplateListQuery: () => ({ data: [] }) }))
// Store-bound; the endpoint picker is not under test here.
vi.mock('../editor/ColourEditor', () => ({ ColourEditor: () => <div data-testid="colour-endpoint" /> }))

let pending: { resolve: (value: SpreadResponse) => void }[] = []
const spread = vi.fn((_request: SpreadRequest) => ({
  unwrap: () =>
    new Promise<SpreadResponse>((resolve) => {
      pending.push({ resolve })
    }),
}))
vi.mock('../../store/programmerOps', () => ({ useSpreadMutation: () => [spread] }))
vi.mock('../../lib/programmerFade', () => ({ getProgrammerFadeMs: () => 0 }))

import { SpreadPopover, isIntentString, spreadColumnsForTargets, type SpreadColumn } from './SpreadPopover'
import { buildRows, expandSelectionToTargets, spreadTargetsFor, type Row } from './rowModel'
import { chan, colourProp, makeFixture, makePixelBar, sliderProp } from '@/test/fixtureFactories'
import { resetSpreadOverStore } from '@/lib/spreadOver'

const rgb = (n: number) => colourProp('rgbColour', chan(n), chan(n + 1), chan(n + 2))
const rgbw = (n: number) => colourProp('rgbColour', chan(n), chan(n + 1), chan(n + 2), { whiteChannel: chan(n + 3) })
/** Two pars in a group (one RGBW), a lone mover with zoom and speed, and a two-cell bar. */
const FIXTURES = [
  makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(10)), rgb(11)], { groups: ['Front wash'] }),
  makeFixture('par-2', [sliderProp('dimmer', 'dimmer', chan(20)), rgbw(21), sliderProp('strobe', 'strobe', chan(25))], { groups: ['Front wash'] }),
  makeFixture('mover', [sliderProp('dimmer', 'dimmer', chan(30)), sliderProp('zoom', 'zoom', chan(31)), sliderProp('speed', 'speed', chan(32)), sliderProp('gobo', 'gobo', chan(33))]),
  makePixelBar('bar', 2, [sliderProp('dimmer', 'dimmer', chan(40)), sliderProp('speed', 'speed', chan(41))]),
]

function rowsOf(textFilter = '', expandedFixtures = new Set<string>()): Row[] {
  return buildRows({
    fixtures: FIXTURES,
    groups: [{ name: 'Front wash', memberCount: 2, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] }],
    expandedGroups: new Set(['Front wash']),
    expandedFixtures,
    textFilter,
  })
}

function column(col: SpreadColumn['col'], rowIds: string[], rows = rowsOf()): SpreadColumn {
  return { col, targets: expandSelectionToTargets(rows, new Set(rowIds)) }
}

const draw = (columns: SpreadColumn[], props: Partial<React.ComponentProps<typeof SpreadPopover>> = {}) =>
  render(<SpreadPopover columns={columns} projectId={6} desk {...props} />)
const open = () => fireEvent.click(screen.getByRole('button', { name: 'Spread' }))
const apply = () => fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
const lastRequest = () => spread.mock.calls.at(-1)![0]
const radio = (group: string, name: string | RegExp) => within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
async function answer(value: SpreadResponse) {
  await act(async () => {
    pending.at(-1)?.resolve(value)
    await Promise.resolve()
  })
}

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
  pending = []
})

afterEach(() => {
  resetSpreadOverStore()
  window.sessionStorage.clear()
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  focusedTemplate.current = null
  scopeState.current = null
  lookStore.current = null
})

describe('the targets (D4)', () => {
  it('a group row lands on its visible members, and an element row lands as a cell — never folded onto its fixture', () => {
    // Filter to par-2: the group row survives with one visible member, and that is all it spreads to.
    const filtered = rowsOf('par-2')
    expect(spreadTargetsFor(filtered, new Set(['group:Front wash']))).toEqual([{ type: 'fixture', key: 'par-2' }])
    const rows = rowsOf('', new Set(['bar']))
    expect(spreadTargetsFor(rows, new Set(['group:Front wash', 'element:fixture:bar:bar.pixel-1']))).toEqual([
      { type: 'fixture', key: 'par-1' },
      { type: 'fixture', key: 'par-2' },
      { type: 'fixture', key: 'bar.pixel-1' },
    ])
    // A cell under its selected parent is the parent's, once.
    expect(spreadTargetsFor(rows, new Set(['fixture:bar', 'element:fixture:bar:bar.pixel-1']))).toEqual([{ type: 'fixture', key: 'bar' }])
  })

  it('sends the marquee’s heads and the press’s families, with the family answered from the column and no `write` key for Local', () => {
    draw([column('dimmer', ['group:Front wash', 'fixture:mover'])])
    open()
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    // The heads can take Colour too, but the marquee is Dimmer: offered, not pressable.
    expect(radio('Family', 'Colour')).toBeDisabled()
    apply()
    expect(spread).toHaveBeenCalledTimes(1)
    expect(lastRequest()).toEqual({
      projectId: 6,
      targets: [
        { type: 'fixture', key: 'par-1' },
        { type: 'fixture', key: 'par-2' },
        { type: 'fixture', key: 'mover' },
      ],
      // The press pair's mask — the desk's while following — not the marquee's.
      families: ['POSITION'],
      property: 'dimmer',
      from: 'pct:0',
      to: 'pct:100',
      curve: 'LINE',
      order: 'LINEAR',
      parts: 1,
      over: 'HEADS',
      seed: 0,
      fadeMs: 0,
    })
    expect('write' in lastRequest()).toBe(false)
  })

  it('sends the marquee’s own families on a surface that does not bridge — the two plain lists', () => {
    draw([column('dimmer', ['group:Front wash'])], { desk: false })
    open()
    apply()
    expect(lastRequest()).toMatchObject({ families: ['INTENSITY'] })
  })

  it('offers the Property row from what the heads can take: Level · Strobe over a Dimmer marquee whose heads include a strobe', () => {
    draw([column('dimmer', ['group:Front wash'])])
    open()
    const properties = within(screen.getByRole('radiogroup', { name: 'Property' })).getAllByRole('radio')
    expect(properties.map((r) => r.textContent)).toEqual(['Level', 'Strobe'])
    fireEvent.click(radio('Property', 'Strobe'))
    apply()
    expect(lastRequest()).toMatchObject({ property: 'strobe' })
  })

  it('offers both families live when the marquee spans two, with the first column’s answered', () => {
    draw([column('dimmer', ['group:Front wash']), column('colour', ['group:Front wash'])])
    open()
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    expect(radio('Family', 'Colour')).toBeEnabled()
    fireEvent.click(radio('Family', 'Colour'))
    // The Colour family over an RGB and an RGBW head: Colour · White.
    expect(within(screen.getByRole('radiogroup', { name: 'Property' })).getAllByRole('radio').map((r) => r.textContent)).toEqual(['Colour', 'White'])
    apply()
    expect(lastRequest()).toMatchObject({ property: 'rgbColour', families: ['POSITION'] })
  })

  it('Over defaults to Heads, and offers Cells with the count where a selected fixture has cells', () => {
    const { unmount } = draw([column('dimmer', ['group:Front wash'])])
    open()
    expect(radio('Over', 'Heads')).toHaveAttribute('aria-checked', 'true')
    // Pressable with nothing to split: the desk spreads it as Heads.
    expect(radio('Over', /^Cells/)).toBeEnabled()
    expect(radio('Over', /^Cells/)).toHaveTextContent(/^Cells$/)
    unmount()
    draw([column('dimmer', ['group:Front wash', 'fixture:bar'])])
    open()
    const cells = radio('Over', /^Cells/)
    expect(cells).toBeEnabled()
    // The bar's two cells, and each par as one.
    expect(cells).toHaveTextContent('Cells4')
    fireEvent.click(cells)
    apply()
    expect(lastRequest()).toMatchObject({ over: 'CELLS' })
  })
})

describe('the scope arm (D6)', () => {
  it('a focused Look layer sends write: false and lands the answered literals through setValue, in the layer’s grammar', async () => {
    const setValue = vi.fn()
    scopeState.current = { kind: 'layer', layerId: 3 }
    lookStore.current = { setValue, lookName: 'Warm Wash' }
    draw([column('dimmer', ['group:Front wash'])], { scopeLabel: 'Warm Wash' })
    open()
    expect(screen.getByText(/into Warm Wash · 2 heads/)).toBeInTheDocument()
    expect(screen.getByText(/written to the layer’s draft/)).toBeInTheDocument()
    apply()
    expect(lastRequest()).toMatchObject({ write: false })
    await answer({
      written: [
        { target: { type: 'fixture', key: 'par-1' }, propertyName: 'dimmer', value: '0' },
        { target: { type: 'fixture', key: 'par-2' }, propertyName: 'dimmer', value: '255' },
      ],
    })
    expect(setValue).toHaveBeenCalledTimes(2)
    expect(setValue).toHaveBeenNthCalledWith(1, 'par-1', 'dimmer', '0')
    expect(setValue).toHaveBeenNthCalledWith(2, 'par-2', 'dimmer', '255')
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('refuses an intent-shaped answer from an older desk rather than landing it in a Look row', async () => {
    const setValue = vi.fn()
    scopeState.current = { kind: 'layer', layerId: 3 }
    lookStore.current = { setValue, lookName: 'Warm Wash' }
    draw([column('dimmer', ['group:Front wash'])])
    open()
    apply()
    await answer({
      written: [
        { target: { type: 'fixture', key: 'par-1' }, propertyName: 'dimmer', value: '0' },
        { target: { type: 'fixture', key: 'par-2' }, propertyName: 'dimmer', value: 'pct:100' },
      ],
    })
    expect(setValue).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/pct:100.*update lighting7/), expect.objectContaining({ id: expect.any(String) }))
    // A colour intent parses as a colour through the literal parser (it splits on `;` and tests
    // the head), so the guard is by shape: the exact string an older desk answered is refused.
    vi.clearAllMocks()
    open()
    apply()
    await answer({ written: [{ target: { type: 'fixture', key: 'par-1' }, propertyName: 'rgbColour', value: '#FF0000;policy=extract' }] })
    expect(setValue).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/#FF0000;policy=extract/), expect.anything())
  })

  it('tells an intent string from a literal by shape', () => {
    for (const intent of ['pct:50', 'deg:240,135', 'dmx:40', 'tmpl:0b2a4c6e-1111-2222-3333-444455556666', '#FF0000;policy=extract', '#ff0000;policy=rgbonly']) {
      expect(isIntentString(intent)).toBe(true)
    }
    for (const literal of ['0', '128', '255', '#ff0000', '#bb4400;w68', '#0044bb;w68;a10;uv200', '128,64']) {
      expect(isIntentString(literal)).toBe(false)
    }
  })

  it('refuses in Output scope — a read of the cook — with the reason Set and Clear give', () => {
    scopeState.current = { kind: 'output' }
    draw([column('dimmer', ['group:Front wash'])])
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('read of the cook'))
  })

  it('refuses onto a focused template layer, and says why', () => {
    focusedTemplate.current = { layerId: 7, templateId: 4, kind: 'effect' }
    draw([column('dimmer', ['group:Front wash'])])
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('switch to Local'))
  })
})

describe('the plan kinds', () => {
  it('Speed builds a raw plan walked here, applied in visible order through the cell writers (D15)', () => {
    draw([column('speed', ['fixture:mover', 'fixture:bar'])])
    open()
    expect(screen.queryByRole('radiogroup', { name: 'Family' })).toBeNull()
    expect(screen.getByRole('spinbutton', { name: 'From, 0–255' })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, 0–255' }), { target: { value: '100' } })
    apply()
    expect(spread).not.toHaveBeenCalled()
    expect(writers.writeSlider).toHaveBeenCalledTimes(2)
    expect(writers.writeSlider).toHaveBeenNthCalledWith(1, 'mover', 'speed', { universe: 0, channelNo: 32 }, 0)
    expect(writers.writeSlider).toHaveBeenNthCalledWith(2, 'bar', 'speed', { universe: 0, channelNo: 41 }, 100)
  })

  it('Gobo and Prism build no plan, and the button says which columns can', () => {
    draw([column('gobo', ['fixture:mover'])])
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('in a column it can drive'))
    expect(button).not.toHaveAttribute('title', expect.stringContaining('Select cells'))
  })

  it('is disabled with nothing selected, and says so', () => {
    draw([])
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('Select cells'))
  })

  it('the plain lists’ row arm offers every spreadable column over the whole selection', () => {
    const targets = expandSelectionToTargets(rowsOf(), new Set(['group:Front wash']))
    expect(spreadColumnsForTargets(targets).map((c) => c.col)).toEqual(['dimmer', 'colour', 'position', 'zoom', 'focus', 'iris', 'strobe', 'speed'])
  })

  it('a Dimmer + Speed marquee draws a chooser between the desk-resolved plan and the raw one', () => {
    draw([column('dimmer', ['fixture:mover']), column('speed', ['fixture:mover', 'fixture:bar'])])
    open()
    expect(screen.getByRole('combobox', { name: 'Column to spread' })).toBeInTheDocument()
  })
})

describe('the seed', () => {
  it('opens on Colour with From set to the colour editor’s RGB, and asks the host to drop it', () => {
    const onSeedConsumed = vi.fn()
    draw([column('colour', ['group:Front wash'])], { seed: { from: { r: 255, g: 0, b: 7 }, key: 1 }, onSeedConsumed })
    expect(onSeedConsumed).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[data-spread-panel]')).not.toBeNull()
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    apply()
    expect(lastRequest()).toMatchObject({ property: 'rgbColour', from: '#FF0007;policy=extract', to: '#2456FF;policy=extract' })
  })
})
