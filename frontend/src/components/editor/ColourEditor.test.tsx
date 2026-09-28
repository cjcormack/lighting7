// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { chan, colourProp, sliderProp } from '@/test/fixtureFactories'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import { resetLiveAppearance } from '@/lib/liveAppearance'
import { resetEditorSurfaceMedia } from './EditorSurface'

/**
 * The colour editor — one body, one read-out, one footer (editor-kit plan D10, D12).
 *
 * The first block is the pieces-by-host table on `Colour.dc.html`, one case per row: which host
 * draws the fluid picker and R/G/B, the emitter rows with head counts, the read-out, the label
 * line, Recent, the footer and the keyboard. Then Pick — it reads the appearance store through the
 * editor's own hidden leaves, one per parent fixture, and says *mixed* — the guidance as the
 * picker's title, the keyboard sequence, and the channel byte's clamp, which was
 * `ChannelNumberInput`'s and is the editor's now that the field itself does not clamp.
 */

const toast = vi.hoisted(() => ({ error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() }))
vi.mock('sonner', () => ({ toast }))

const apply = vi.fn(() => ({ unwrap: () => Promise.resolve({ written: 1, skipped: [] }) }))
const toggle = vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))
let templates: unknown[] = []
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: templates }),
  useApplyTemplateMutation: () => [apply],
  useToggleTemplateMutation: () => [toggle],
}))
vi.mock('@/store/selection', () => ({ usePressFamilies: (local: unknown) => local }))
/** What each head is showing, keyed by patch key — what the appearance leaves report. */
const appearances: Record<string, { color: string; intensity: number; segments?: { css: string; intensity: number }[] }> = {}
const leafMounts = vi.fn()
vi.mock('@/components/fixtures/fixtureAppearance', () => ({
  FixtureAppearanceSource: ({ patch, children }: { patch: { key: string }; children: (a: unknown) => React.ReactNode }) => {
    leafMounts(patch.key)
    const appearance = appearances[patch.key]
    return appearance == null ? null : children(appearance)
  },
}))
const patchKeys = ['par-1', 'par-2', 'bar', 'dim']
vi.mock('@/store/patches', () => ({
  usePatchListQuery: () => ({ data: patchKeys.map((key, i) => ({ id: i + 1, key, displayName: key })) }),
}))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: [], fixtureTypes: [], fixtureByKey: new Map(), typeByKey: new Map() }),
}))

import { ColourEditor, emitterHeadCounts, type ColourEditorProps } from './ColourEditor'
import { ColourPickerPopover } from '@/components/fixtures/ColourPickerPopover'

const rgb = colourProp('rgbColour', chan(1), chan(2), chan(3))
const rgbw = colourProp('rgbColour', chan(1), chan(2), chan(3), { whiteChannel: chan(4) })
const rgba = colourProp('rgbColour', chan(1), chan(2), chan(3), { amberChannel: chan(5) })
const dimmer = sliderProp('dimmer', 'dimmer', chan(20))

const par1: WriteTarget = { key: 'par-1', properties: [rgb] }
const par2: WriteTarget = { key: 'par-2', properties: [rgbw] }
const dim: WriteTarget = { key: 'dim', properties: [dimmer] }
/** One cell of a bar, named the way `rowWriteTargets` and `writeTargetsOf` stamp it. */
const cell: WriteTarget = { key: 'bar.c2', properties: [rgba], fixtureKey: 'bar', cellIndex: 1 }
const FOUR = [par1, par2, dim, cell]

function stubMatchMedia({ narrow = false, short = false, cramped = false } = {}) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('max-width') ? narrow : query.includes('500px') ? short : query.includes('750px') ? cramped : false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
}

function draw(props: Partial<ColourEditorProps> = {}) {
  const onColourChange = vi.fn()
  const view = render(
    <ColourEditor
      r={10}
      g={20}
      b={30}
      combinedCss="rgb(10, 20, 30)"
      hasWhiteChannel={false}
      hasAmberChannel={false}
      hasUvChannel={false}
      onColourChange={onColourChange}
      channelFields
      open
      {...props}
    />,
  )
  return { onColourChange, ...view }
}

const field = (name: string) => screen.getByRole('spinbutton', { name }) as HTMLInputElement
const footer = () => document.querySelector('[data-editor-footer]')
const readout = () => document.querySelector('[data-editor-readout]')
const recent = () => document.querySelector('[data-colour-editor-recent]')
const knob = () => document.querySelector('.react-colorful__saturation [aria-valuetext]')!.getAttribute('aria-valuetext')

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  stubMatchMedia()
  appearances['par-1'] = { color: '#ff0000', intensity: 1 }
  appearances['par-2'] = { color: 'rgb(0, 0, 255)', intensity: 1 }
  appearances['dim'] = { color: '#fff8d5', intensity: 1 }
  appearances['bar'] = { color: '#00ff00', intensity: 1, segments: [{ css: '#111111', intensity: 1 }, { css: '#222222', intensity: 1 }] }
  templates = []
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  resetLiveAppearance()
  resetEditorSurfaceMedia()
})

describe('the pieces, and which host draws which', () => {
  it('programmer popover: fluid picker + R/G/B, emitter rows, read-out with counts, label line, no Recent, the footer', () => {
    templates = [template(1, 'Warm Amber')]
    draw({
      targets: FOUR,
      projectId: 1,
      hasWhiteChannel: true,
      hasAmberChannel: true,
      labelLine: <div data-testid="label-line">4 heads · Local</div>,
      recent: { projectId: 1, targets: [{ type: 'fixture', key: 'par-1' }], localFamilies: ['COLOUR'], forms: ['bottom-sheet'] },
      onSave: () => {},
    })
    // The fluid row, never a pinned square.
    expect(document.querySelector('.colour-picker-fluid .react-colorful')).not.toBeNull()
    expect(field('R').value).toBe('10')
    expect(field('G').value).toBe('20')
    expect(field('B').value).toBe('30')
    // The union's rows — par-2 has white, the cell has amber, nothing has UV.
    expect(screen.getByLabelText('W value')).toBeInTheDocument()
    expect(screen.getByLabelText('A value')).toBeInTheDocument()
    expect(screen.queryByLabelText('UV value')).toBeNull()
    // The read-out: the count line, then the swatch and the hex.
    expect(document.querySelector('[data-colour-emitters]')).toHaveTextContent('Emitters on 2 of 4 heads · the rest take RGB only')
    expect(document.querySelector('[data-colour-editor-hex]')).toHaveTextContent('#0A141E')
    expect(screen.getByTestId('label-line')).toBeInTheDocument()
    // Recent asks for the bottom sheet alone, and this is a popover: row C's strip is one row up.
    expect(recent()).toBeNull()
    const verbs = [...footer()!.querySelectorAll('button')].map((b) => b.getAttribute('aria-label') ?? b.textContent?.trim())
    expect(verbs).toEqual(['Save as template…', 'Pick', 'Spread to a second colour…'])
    expect(screen.getByRole('button', { name: /Save as template/ })).toBeEnabled()
  })

  it('programmer bottom sheet: Recent is drawn there, and only there of the two sheets', () => {
    templates = [template(1, 'Warm Amber')]
    const source = { projectId: 1, targets: [{ type: 'fixture' as const, key: 'par-1' }], localFamilies: ['COLOUR' as const], forms: ['bottom-sheet' as const] }
    stubMatchMedia({ narrow: true })
    const { unmount } = draw({ targets: FOUR, projectId: 1, recent: source })
    expect(recent()).not.toBeNull()
    expect(document.querySelectorAll('[data-recent-template]')).toHaveLength(1)
    unmount()
    resetEditorSurfaceMedia()
    stubMatchMedia({ short: true })
    draw({ targets: FOUR, projectId: 1, recent: source })
    expect(recent()).toBeNull()
  })

  it('busk tab (docked): the body, read-out and Recent in one scroller, the footer static under it, Recent always', () => {
    draw({ targets: FOUR, projectId: 1, docked: true, recent: { projectId: 1, targets: [], localFamilies: null } })
    const scroller = document.querySelector('[data-colour-editor-scroller]')!
    expect(scroller.className).toContain('overflow-y-auto')
    expect(within(scroller as HTMLElement).queryByRole('spinbutton', { name: 'R' })).not.toBeNull()
    expect(scroller.querySelector('[data-editor-readout]')).not.toBeNull()
    expect(scroller.querySelector('[data-colour-editor-recent]')).not.toBeNull()
    expect(screen.getByText('Colour templates you press show up here')).toBeInTheDocument()
    // The footer is outside the scroller and does not scroll with it.
    expect(scroller.querySelector('[data-editor-footer]')).toBeNull()
    expect(footer()!.className).toContain('shrink-0')
  })

  it('Spread endpoint: the picker and R/G/B alone — no emitters, no read-out, no footer', () => {
    draw({ footer: false, counts: false })
    expect(field('R')).toBeInTheDocument()
    expect(screen.queryByLabelText('W value')).toBeNull()
    expect(readout()).toBeNull()
    expect(footer()).toBeNull()
    expect(recent()).toBeNull()
  })

  it('property visualisers: picker only, the R:G:B line and read-out sliders, nothing else', () => {
    draw({ channelFields: false, footer: false, counts: false, hasWhiteChannel: true, w: 40 })
    expect(screen.queryByRole('spinbutton', { name: 'R' })).toBeNull()
    expect(screen.getByText('R:10 G:20 B:30')).toBeInTheDocument()
    expect(screen.getByText('White = use white LED')).toBeInTheDocument()
    expect(screen.queryByLabelText('W value')).toBeNull()
    expect(readout()).toBeNull()
    expect(footer()).toBeNull()
  })
})

describe('Pick', () => {
  it('mounts one hidden leaf per parent fixture of the targets — a cell through its parent, a dimmer not at all', () => {
    draw({ targets: FOUR, projectId: 1 })
    const leaves = document.querySelector('[data-colour-editor-leaves]')!
    expect(leaves).toHaveAttribute('hidden')
    // Distinct keys: the mock counts renders, and the leaves render again when their list lands.
    expect([...new Set(leafMounts.mock.calls.map((c) => c[0]))].sort()).toEqual(['bar', 'par-1', 'par-2'])
  })

  it('mounts no leaf without a project, and says nothing is on screen', () => {
    draw({ targets: FOUR })
    expect(document.querySelector('[data-colour-editor-leaves]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Pick' }))
    expect(toast.info).toHaveBeenCalledWith('No selected head is on screen to read')
    expect(field('R').value).toBe('10')
  })

  it('reads the first head into the fields and the knob without writing, and says mixed when the heads disagree', () => {
    const { onColourChange } = draw({ targets: FOUR, projectId: 1, hasWhiteChannel: true, hasAmberChannel: true })
    fireEvent.click(screen.getByRole('button', { name: 'Pick' }))
    expect(field('R').value).toBe('255')
    expect(field('G').value).toBe('0')
    expect(field('B').value).toBe('0')
    // The emitters start at 0: the store folds them into one colour.
    expect(field('W value').value).toBe('0')
    expect(document.querySelector('[data-colour-picked="mixed"]')).not.toBeNull()
    expect(knob()).toBe('Saturation 100%, Brightness 100%')
    expect(onColourChange).not.toHaveBeenCalled()
    // The next single-channel edit builds on what was picked, not on the props — and the emitters
    // this host never held stay unstated, so the heads that have them keep them.
    fireEvent.change(field('G'), { target: { value: '20' } })
    expect(onColourChange).toHaveBeenCalledWith(255, 20, 0, undefined, undefined, undefined)
    // …and the write clears the marker.
    expect(document.querySelector('[data-colour-picked="mixed"]')).toBeNull()
  })

  it('reads in the host’s head order, and a cell off its parent’s segment', () => {
    draw({ targets: [par1, cell], projectId: 1, headOrder: ['bar', 'par-1'] })
    fireEvent.click(screen.getByRole('button', { name: 'Pick' }))
    expect(field('R').value).toBe('34')
    expect(document.querySelector('[data-colour-picked="mixed"]')).not.toBeNull()
  })

  it('ignores a head with no colour descriptor: a dimmer’s tungsten is neither read nor a reason to say mixed', () => {
    draw({ targets: [par1, dim], projectId: 1 })
    fireEvent.click(screen.getByRole('button', { name: 'Pick' }))
    expect(field('R').value).toBe('255')
    expect(document.querySelector('[data-colour-picked="mixed"]')).toBeNull()
  })

  it('leaves an emitter the host does not hold unstated — through a typed byte and a picker move — and states it from its own field', () => {
    const { onColourChange } = draw({ hasWhiteChannel: true, hasAmberChannel: true, a: 100 })
    fireEvent.change(field('G'), { target: { value: '21' } })
    // Amber is held (100) and travels; white is offered by the union but unheld, and stays undefined.
    expect(onColourChange).toHaveBeenLastCalledWith(10, 21, 30, undefined, 100, undefined)
    // A picker move is the replacing gesture: it zeroes what is held and still states nothing about white.
    // react-colorful reads the numeric code, not `key`.
    fireEvent.keyDown(document.querySelector('.react-colorful__saturation [aria-valuetext]')!, { key: 'ArrowRight', keyCode: 39, which: 39 })
    const [, , , w, a] = onColourChange.mock.calls.at(-1)!
    expect(w).toBeUndefined()
    expect(a).toBe(0)
    fireEvent.change(field('W value'), { target: { value: '40' } })
    expect(onColourChange.mock.calls.at(-1)![3]).toBe(40)
  })

  it('does not re-seed when only the head order moves', () => {
    const onPick = vi.fn()
    const { rerender, onColourChange } = draw({ targets: [par1, par2], projectId: 1, pickOnTargets: true, headOrder: ['par-1', 'par-2'], onPick })
    expect(onPick).toHaveBeenCalledTimes(1)
    fireEvent.change(field('G'), { target: { value: '9' } })
    rerender(
      <ColourEditor r={10} g={20} b={30} combinedCss="rgb(10, 20, 30)" hasWhiteChannel={false} hasAmberChannel={false} hasUvChannel={false} onColourChange={onColourChange} channelFields open targets={[par1, par2]} projectId={1} pickOnTargets headOrder={['par-2', 'par-1']} onPick={onPick} />,
    )
    // The rig re-ordered under a colour the operator has set: the same heads, no second seed.
    expect(onPick).toHaveBeenCalledTimes(1)
    expect(field('G').value).toBe('9')
  })

  it('seeds from the targets on mount when asked, once per set of heads, and tells the host', () => {
    const onPick = vi.fn()
    const { rerender, onColourChange } = draw({ targets: [par2], projectId: 1, pickOnTargets: true, onPick })
    expect(field('B').value).toBe('255')
    expect(knob()).toBe('Saturation 100%, Brightness 100%')
    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick).toHaveBeenCalledWith({ r: 0, g: 0, b: 255, w: 0, a: 0, uv: 0 }, { hex: '#0000ff', mixed: false, read: 1 })
    expect(onColourChange).not.toHaveBeenCalled()
    // The same heads again: no second seed. New heads: a fresh one.
    rerender(
      <ColourEditor r={10} g={20} b={30} combinedCss="rgb(10, 20, 30)" hasWhiteChannel={false} hasAmberChannel={false} hasUvChannel={false} onColourChange={onColourChange} channelFields open targets={[par2]} projectId={1} pickOnTargets onPick={onPick} />,
    )
    expect(onPick).toHaveBeenCalledTimes(1)
    rerender(
      <ColourEditor r={10} g={20} b={30} combinedCss="rgb(10, 20, 30)" hasWhiteChannel={false} hasAmberChannel={false} hasUvChannel={false} onColourChange={onColourChange} channelFields open targets={[par1]} projectId={1} pickOnTargets onPick={onPick} />,
    )
    expect(onPick).toHaveBeenCalledTimes(2)
    expect(onPick).toHaveBeenLastCalledWith({ r: 255, g: 0, b: 0, w: 0, a: 0, uv: 0 }, { hex: '#ff0000', mixed: false, read: 1 })
  })
})

describe('the picker', () => {
  it('carries the white-LED guidance as its title, and only where a white row is offered', () => {
    const { unmount } = draw({ hasWhiteChannel: true })
    expect(document.querySelector('.colour-picker-fluid')).toHaveAttribute(
      'title',
      'Pure white in the picker drives the white LED; the boxes set one channel each.',
    )
    // No paragraph under the picker any more.
    expect(screen.queryByText('Pure white in the picker drives the white LED; the boxes set one channel each.')).toBeNull()
    unmount()
    draw({ hasWhiteChannel: false })
    expect(document.querySelector('.colour-picker-fluid')).not.toHaveAttribute('title')
  })

  it('takes its compact heights from index.css’s class on the row — never a Tailwind utility, which the library’s unlayered rule beats', () => {
    draw({ compact: true })
    const row = document.querySelector('.colour-picker-fluid')!
    expect(row.className).toContain('colour-picker-compact')
    expect(row.className).not.toMatch(/react-colorful/)
    expect(document.querySelector('[data-colour-editor-body="compact"]')).not.toBeNull()
  })

  it('clamps a typed channel byte to 0–255 and rounds it — the caller’s clamp, not the field’s', () => {
    const { onColourChange } = draw()
    fireEvent.change(field('R'), { target: { value: '900' } })
    fireEvent.change(field('R'), { target: { value: '-4' } })
    fireEvent.change(field('R'), { target: { value: '12.6' } })
    expect(onColourChange.mock.calls.map((c) => c[0])).toEqual([255, 0, 13])
  })

  it('folds every colour descriptor of a target into the emitter counts, and counts a head once', () => {
    expect(emitterHeadCounts([{ key: 'bar', properties: [], elements: [{ index: 1, key: 'bar.c2', displayName: 'Cell 2', properties: [rgba] }] }])).toEqual({
      white: 0,
      amber: 1,
      uv: 0,
      any: 1,
    })
    const both = colourProp('rgbColour', chan(1), chan(2), chan(3), { whiteChannel: chan(4), amberChannel: chan(5) })
    expect(emitterHeadCounts([{ key: 'x', properties: [both] }])).toEqual({ white: 1, amber: 1, uv: 0, any: 1 })
  })
})

describe('the footer', () => {
  it('disables Save without a handler or with no heads, and hands Spread… the current channels or draws it inert', () => {
    const onSpread = vi.fn()
    const { unmount } = draw({ targets: FOUR, onSpread })
    expect(screen.getByRole('button', { name: /Save as template/ })).toBeDisabled()
    expect(screen.queryByRole('switch')).toBeNull()
    fireEvent.change(field('B'), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: /Spread to a second colour/ }))
    expect(onSpread).toHaveBeenCalledWith({ r: 10, g: 20, b: 7, w: 0, a: 0, uv: 0 })
    unmount()
    const onSave = vi.fn()
    draw({ targets: [], onSave })
    expect(screen.getByRole('button', { name: /Save as template/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Spread to a second colour/ })).toBeDisabled()
  })

  it('Recent draws the colour recents most recent first, the selection’s emitters filtering, and a tap is a template apply', () => {
    templates = [
      template(1, 'Deep Blue', { lastPressedAt: '2026-09-18T09:00:00Z' }),
      template(2, 'Warm Amber'),
      template(3, 'Home', { family: 'POSITION' }),
      template(4, 'UV wash', { requiredEmitters: ['uv'] }),
      template(5, 'Never', { lastPressedAt: null }),
      template(6, 'Per head', { isGeneric: false }),
    ]
    draw({ targets: FOUR, projectId: 1, recent: { projectId: 1, targets: [{ type: 'fixture', key: 'par-1' }], localFamilies: ['COLOUR'] } })
    const chips = document.querySelectorAll('[data-recent-template]')
    expect([...chips].map((c) => c.textContent)).toEqual(['Warm Amber', 'Deep Blue'])
    fireEvent.click(within(chips[0] as HTMLElement).getByText('Warm Amber'))
    expect(apply).toHaveBeenCalledWith({ projectId: 1, templateId: 2, targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'] })
    expect(toggle).not.toHaveBeenCalled()
  })
})

describe('read-only — the programmer rail’s tab in Output or on a template layer', () => {
  it('takes no input on the picker, the fields or the emitters, and leaves Pick and the label line live', () => {
    // A scope that takes no value must not be written by any control of the editor, and Pick is a
    // read: it moves the fields and writes nothing, so it stays live (editor-kit plan session 4).
    draw({ targets: FOUR, docked: true, readOnly: true, hasWhiteChannel: true, onSpread: vi.fn(), labelLine: <span>Output · read-only</span> })
    const inertRegion = document.querySelector('[data-colour-editor-readonly]')
    expect(inertRegion).not.toBeNull()
    expect(inertRegion!.hasAttribute('inert')).toBe(true)
    expect(inertRegion!.contains(field('R'))).toBe(true)
    expect(inertRegion!.contains(document.querySelector('.react-colorful'))).toBe(true)
    const pick = screen.getByRole('button', { name: 'Pick' })
    expect(inertRegion!.contains(pick)).toBe(false)
    expect(pick).toBeEnabled()
    // Spread… hands a value on to be written, so read-only disables it here, not in each host.
    expect(screen.getByRole('button', { name: /Spread to a second colour/ })).toBeDisabled()
    // The docked frame draws the host's label line, above the controls.
    expect(screen.getByText('Output · read-only')).toBeInTheDocument()
  })

  it('draws the controls live by default', () => {
    draw({ targets: FOUR, docked: true })
    expect(document.querySelector('[data-colour-editor-readonly]')).toBeNull()
  })
})

describe('the keyboard, through the popover host', () => {
  it('focuses R on open, seeds it, and comma steps R → G → B → W → A → UV and round; Enter closes', () => {
    const onOpenChange = vi.fn()
    render(
      <ColourPickerPopover
        open
        onOpenChange={onOpenChange}
        r={10}
        g={20}
        b={30}
        w={1}
        a={2}
        uv={3}
        combinedCss="rgb(10, 20, 30)"
        hasWhiteChannel
        hasAmberChannel
        hasUvChannel
        onColourChange={() => {}}
        channelFields
        keyboardOpen="7"
        targets={FOUR}
      >
        <button type="button">swatch</button>
      </ColourPickerPopover>,
    )
    expect(field('R')).toHaveFocus()
    expect(field('R')).toHaveValue(7)
    for (const next of ['G', 'B', 'W value', 'A value', 'UV value', 'R']) {
      fireEvent.keyDown(document.activeElement!, { key: ',' })
      expect(field(next)).toHaveFocus()
    }
    fireEvent.keyDown(field('R'), { key: 'Enter' })
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

function template(id: number, name: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    uuid: `t${id}`,
    name,
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'value',
    rows: [{ propertyName: 'rgbColour', value: '#ffaa00', targetType: 'generic', targetKey: null, sortOrder: 0 }],
    effect: null,
    requiredEmitters: [],
    lastPressedAt: '2026-09-18T10:00:00Z',
    layerCount: 0,
    ...extra,
  }
}
