// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
// The source as text, for the one assertion that is about what this file must *not* reach for.
import spreadSheetSrc from './SpreadSheet.tsx?raw'
import type { BuskingTarget } from './buskingTypes'
import type { Fixture } from '@/store/fixtures'
import type { SpreadRequest, SpreadResponse } from '@/store/programmerOps'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'
import { resetSpreadOverStore } from '@/lib/spreadOver'

/**
 * The Spread tab as the **docked host** of `SpreadPanel` (busk-further plan D9, D10; editor-kit
 * plan session 3): what this file owns is the host's — the selection → the request's targets and
 * the families the heads can take, the mask, the mutation with the fade, the template offer, the
 * *Over: Cells* count from the desk's own expansion, the Colour tab's seed, and *Save as Look…*
 * opening `RecordLookSheet` over the selection, never a template. The panel's own behaviour — the
 * curves and orders, Live, Send again, the desk's answer read for `skippedFamilies` and nothing
 * drawn from it — is `editor/SpreadPanel.test.tsx`'s.
 */

const toast = vi.hoisted(() => ({ error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() }))
vi.mock('sonner', () => ({ toast }))

/** Every request's settle handles, in send order — `answer(value, i)` resolves one, `refuse(i)` rejects one. */
let pending: { resolve: (value: SpreadResponse) => void; reject: (reason: unknown) => void }[] = []
const spread = vi.fn((_request: SpreadRequest) => ({
  unwrap: () =>
    new Promise<SpreadResponse>((resolve, reject) => {
      pending.push({ resolve, reject })
    }),
}))
vi.mock('@/store/programmerOps', () => ({ useSpreadMutation: () => [spread] }))
// lightingApi opens a real WebSocket at import; the store's middleware graph reaches it.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('@/components/programmer/RecordLookSheet', () => ({
  RecordLookSheet: ({ open, targets }: { open: boolean; targets: unknown[] }) =>
    open ? <div data-testid="record-look" data-targets={JSON.stringify(targets)} /> : null,
}))
let templates: unknown[] = []
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: templates }) }))

const colour = (extra: Record<string, unknown> = {}) => ({
  type: 'colour',
  name: 'rgbColour',
  displayName: 'Colour',
  category: 'colour',
  redChannel: { universe: 1, channelNo: 1 },
  greenChannel: { universe: 1, channelNo: 2 },
  blueChannel: { universe: 1, channelNo: 3 },
  ...extra,
})
const dimmer = { type: 'slider', name: 'dimmer', displayName: 'Dimmer', category: 'dimmer', channel: { universe: 1, channelNo: 20 }, min: 0, max: 255 }
const position = { type: 'position', name: 'position', displayName: 'Position', category: 'position', panChannel: { universe: 1, channelNo: 30 }, tiltChannel: { universe: 1, channelNo: 31 }, panMin: 0, panMax: 255, tiltMin: 0, tiltMax: 255 }
const par1 = { key: 'par-1', name: 'PAR 1', typeKey: 'par', groups: ['Front wash'], properties: [colour(), dimmer] } as unknown as Fixture
const par2 = { key: 'par-2', name: 'PAR 2', typeKey: 'par', groups: ['Front wash'], properties: [colour({ whiteChannel: { universe: 1, channelNo: 4 } }), dimmer] } as unknown as Fixture
const mover = { key: 'mh-1', name: 'Mover', typeKey: 'mh', groups: [], properties: [dimmer, position] } as unknown as Fixture
const bar = {
  key: 'bar',
  name: 'Bar L',
  typeKey: 'bar',
  groups: [],
  properties: [dimmer],
  elements: [
    { index: 0, key: 'bar.c1', displayName: 'Cell 1', properties: [colour()] },
    { index: 1, key: 'bar.c2', displayName: 'Cell 2', properties: [colour()] },
  ],
} as unknown as Fixture
const fixtures = [par1, par2, mover, bar]
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures, fixtureTypes: [], fixtureByKey: new Map(fixtures.map((f) => [f.key, f])), typeByKey: new Map() }),
}))

import { SpreadSheet } from './SpreadSheet'
import { selectedCells } from '@/lib/cellsSubSelection'
import { lookLayerTarget } from './buskingTypes'

const group: BuskingTarget = {
  type: 'group',
  name: 'Front wash',
  group: { name: 'Front wash', memberCount: 2, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] },
}
const moverTarget: BuskingTarget = { type: 'fixture', key: 'mh-1', fixture: mover }
const barTarget: BuskingTarget = { type: 'fixture', key: 'bar', fixture: bar }
const cell: BuskingTarget = { type: 'fixture', key: 'bar.c2', fixture: bar, element: bar.elements![1] }

function selectionOf(...targets: BuskingTarget[]) {
  return new Map(targets.map((t) => [t.type === 'group' ? `group:${t.name}` : `fixture:${t.key}`, t]))
}

function draw(targets: BuskingTarget[], props: Partial<React.ComponentProps<typeof SpreadSheet>> = {}) {
  return render(<SpreadSheet projectId={6} selectedTargets={selectionOf(...targets)} families={null} {...props} />)
}

const lastRequest = () => spread.mock.calls.at(-1)![0]
const apply = () => fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
/** A radio inside one of the tab's named groups — Family and Property both offer a *Colour*. */
const radio = (group: string, name: string | RegExp) => within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })

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
  templates = []
})

afterEach(() => {
  resetSpreadOverStore()
  window.sessionStorage.clear()
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  resetEditorSurfaceMedia()
})

describe('the request', () => {
  it('sends a colour spread as two colour intents over the selection pair, with the desk’s vocabulary for the rest', () => {
    draw([group, cell], { families: ['COLOUR'] })
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    apply()
    expect(spread).toHaveBeenCalledTimes(1)
    expect(lastRequest()).toEqual({
      projectId: 6,
      targets: [
        { type: 'group', key: 'Front wash' },
        { type: 'fixture', key: 'bar.c2' },
      ],
      families: ['COLOUR'],
      property: 'rgbColour',
      from: '#F5B342;policy=extract',
      to: '#2456FF;policy=extract',
      curve: 'LINE',
      order: 'LINEAR',
      parts: 1,
      over: 'HEADS',
      seed: 0,
      fadeMs: 0,
    })
  })

  it('sends a tmpl: reference for a colour endpoint chosen from the library, and only offers generic value colour templates', () => {
    templates = [
      { id: 1, uuid: 'u-amber', name: 'Amber', family: 'COLOUR', kind: 'value', isGeneric: true, rows: [{ propertyName: 'rgbColour', value: '#FFBF00;policy=extract' }], requiredEmitters: [] },
      { id: 2, uuid: 'u-fx', name: 'Pulse', family: 'COLOUR', kind: 'effect', isGeneric: true, rows: [], requiredEmitters: [] },
      { id: 3, uuid: 'u-per', name: 'Per head', family: 'COLOUR', kind: 'value', isGeneric: false, rows: [], requiredEmitters: [] },
      { id: 4, uuid: 'u-pos', name: 'Home', family: 'POSITION', kind: 'value', isGeneric: true, rows: [], requiredEmitters: [] },
      // A legal colour template with no hex: the desk's `spreadEndpoint` has nothing to interpolate
      // and answers 400, so it is not offered rather than refused after the press.
      { id: 5, uuid: 'u-uv', name: 'UV only', family: 'COLOUR', kind: 'value', isGeneric: true, rows: [{ propertyName: 'uv', value: 'dmx:200' }], requiredEmitters: ['uv'] },
    ]
    draw([group])
    fireEvent.click(radio('Family', 'Colour'))
    fireEvent.click(document.querySelector('[data-spread-endpoint="to"]')!)
    const picker = screen.getByRole('combobox', { name: 'To template' }) as HTMLSelectElement
    expect([...picker.options].map((o) => o.textContent)).toEqual(['— a colour —', 'Amber'])
    fireEvent.change(picker, { target: { value: 'u-amber' } })
    apply()
    expect(lastRequest()).toMatchObject({ property: 'rgbColour', from: '#F5B342;policy=extract', to: 'tmpl:u-amber' })
    // The desk interpolates with From's policy alone; while From is a template that policy is the
    // template row's, so the control goes inert and says why rather than pretending to edit it.
    const policies = () => within(screen.getByRole('radiogroup', { name: 'White policy' })).getAllByRole('radio')
    expect(policies().every((r) => !(r as HTMLButtonElement).disabled)).toBe(true)
    fireEvent.click(document.querySelector('[data-spread-endpoint="from"]')!)
    fireEvent.change(screen.getByRole('combobox', { name: 'From template' }), { target: { value: 'u-amber' } })
    expect(policies().every((r) => (r as HTMLButtonElement).disabled)).toBe(true)
    expect(screen.getByRole('radiogroup', { name: 'White policy' })).toHaveAttribute('title', expect.stringMatching(/From is a template/))
  })

  it('lands on the first family the mask names once the selection can take it — even when the selection arrives after mount', () => {
    // A cold `?sheet=spread` arrival: the fixture list has not answered, so nothing is available
    // yet and the initialiser falls back; the mask is re-read when the selection resolves.
    const { rerender } = draw([], { families: ['COLOUR'] })
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(group)} families={['COLOUR']} />)
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    // Settled once resolved against a real selection: a head added mid-edit does not move the
    // family (and so does not reset the endpoints), even where the default would now differ.
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(group, moverTarget)} families={null} />)
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    // The operator's own choice stands the same way while the selection can take it.
    fireEvent.click(radio('Family', 'Intensity'))
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(group, moverTarget, barTarget)} families={null} />)
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    // A family the selection can no longer take goes with it.
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(barTarget)} families={null} />)
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(cell)} families={null} />)
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
  })

  it('offers only the families the selection can take, and Swap exchanges the ends', () => {
    draw([moverTarget])
    const families = within(screen.getByRole('radiogroup', { name: 'Family' })).getAllByRole('radio')
    expect(families.map((r) => r.textContent)).toEqual(['Intensity', 'Position'])
    fireEvent.click(radio('Family', 'Position'))
    fireEvent.click(screen.getByRole('button', { name: 'Swap From and To' }))
    apply()
    expect(lastRequest()).toMatchObject({ from: 'deg:300,135', to: 'deg:240,135' })
  })

  it('sends nothing under an empty selection, and toasts as the strip does', () => {
    draw([])
    apply()
    expect(spread).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('Select the fixtures this should land on first', expect.objectContaining({ id: expect.any(String) }))
  })
})

describe('the desk’s answer', () => {
  it('is never anticipated here — this file reaches nothing that interpolates, and draws no preview of its own', () => {
    // The rule in one line: this file reaches nothing that interpolates.
    const imports = [...spreadSheetSrc.matchAll(/from '([^']+)'/g)].map((m) => m[1])
    expect(imports.some((name) => /fanMath|colourMath\b.*fan|fanValues/.test(name))).toBe(false)
    expect(spreadSheetSrc).not.toMatch(/fanValues|fanColours|from '\.\.\/sheet\/fanMath'|sheet\/fanMath'/)

    draw([group, barTarget])
    apply()
    // The preview strip went on 2026-09-21: the rig is the preview. Nothing here draws `written[]`.
    expect(document.querySelector('[data-spread-preview]')).toBeNull()
    expect(screen.queryByText('Preview')).toBeNull()
  })

  it('draws no heading and no mask pill — the tab strip names the tab and the band says what is selected', () => {
    draw([group], { families: ['POSITION'] })
    expect(document.querySelector('[data-spread-heading]')).toBeNull()
    expect(screen.queryByText(/writes to Local/)).toBeNull()
    // The footer is static under the tab's one scroller, the save first — the Colour tab's shape.
    const footer = document.querySelector('[data-spread-sheet-footer]')!
    expect(footer.className).toContain('shrink-0')
    expect(document.querySelector('[data-spread-sheet-body]')!.className).toContain('overflow-y-auto')
    // Live sits beside Apply; while it is on, Apply reads as the resend it is and stays pressable —
    // the one un-deduped send after a refused Live write.
    expect([...footer.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual(['Save as Look…', 'Live', 'Apply'])
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled()
    fireEvent.click(screen.getByRole('switch', { name: 'Live — apply as I adjust' }))
    expect(spread).not.toHaveBeenCalled()
    const resend = screen.getByRole('button', { name: 'Send again' })
    expect(resend).toBeEnabled()
    fireEvent.click(resend)
    expect(spread).toHaveBeenCalledTimes(1)
  })
})

describe('Over', () => {
  it('offers Cells with the count where a selected fixture has cells, and keeps it chosen where none does', () => {
    const { rerender } = draw([group, barTarget])
    const cells = radio('Over', /^Cells/)
    expect(cells).toBeEnabled()
    expect(cells).toHaveTextContent('Cells2')
    fireEvent.click(cells)
    // A selection with no cells keeps the choice (the desk spreads it as Heads), and it is still
    // there when the bar comes back.
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(group)} families={null} />)
    expect(radio('Over', /^Cells/)).toBeEnabled()
    expect(radio('Over', /^Cells/)).toHaveAttribute('aria-checked', 'true')
    rerender(<SpreadSheet projectId={6} selectedTargets={selectionOf(group, barTarget)} families={null} />)
    expect(radio('Over', /^Cells/)).toHaveAttribute('aria-checked', 'true')
    // The count is the Cells chip's own expansion (`lib/cellsSubSelection.ts`), so Over: Cells and
    // the chip count cells one way; this is the same three answers the tab's own counter gave.
    const count = (...targets: BuskingTarget[]) =>
      selectedCells(targets.map(lookLayerTarget), { rows: [], groups: [], fixtures }).length
    expect(count(group, barTarget)).toBe(2)
    // A cell already selected on its own is one head; the parent selected too counts its cells once.
    expect(count(cell)).toBe(1)
    expect(count(cell, barTarget)).toBe(2)
  })
})

describe('Save as Look…', () => {
  it('opens RecordLookSheet over the selection — record-look, never a template (D10)', () => {
    draw([group, cell])
    fireEvent.click(screen.getByRole('button', { name: 'Save as Look…' }))
    expect(screen.getByTestId('record-look')).toHaveAttribute(
      'data-targets',
      JSON.stringify([
        { type: 'group', key: 'Front wash' },
        { type: 'fixture', key: 'bar.c2' },
      ]),
    )
    expect(spreadSheetSrc).not.toMatch(/from-programmer|NewTemplateFromSelectionSheet/)
  })
})

describe('the seed', () => {
  it('takes the Colour tab’s colour as From, on Colour, once, and asks the host to drop it', async () => {
    const onSeedConsumed = vi.fn()
    draw([group, moverTarget], { seed: { from: { r: 255, g: 0, b: 7 }, key: 1 }, onSeedConsumed })
    await waitFor(() => expect(onSeedConsumed).toHaveBeenCalledTimes(1))
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    apply()
    expect(lastRequest()).toMatchObject({ property: 'rgbColour', from: '#FF0007;policy=extract', to: '#2456FF;policy=extract' })
  })

  it('spends the seed without moving the family when the selection cannot take colour', async () => {
    const onSeedConsumed = vi.fn()
    draw([moverTarget], { seed: { from: { r: 255, g: 0, b: 7 }, key: 1 }, onSeedConsumed })
    await waitFor(() => expect(onSeedConsumed).toHaveBeenCalledTimes(1))
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    expect(within(screen.getByRole('radiogroup', { name: 'Family' })).queryByRole('radio', { name: 'Colour' })).toBeNull()
    apply()
    expect(lastRequest()).toMatchObject({ property: 'dimmer' })
  })
})
