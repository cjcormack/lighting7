// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { BuskingTarget } from './buskingTypes'
import type { Fixture } from '@/store/fixtures'
import { resetLiveAppearance } from '@/lib/liveAppearance'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

/**
 * The Colour tab as the docked **host** of `ColourEditor` (busk-further plan D8, editor-kit plan
 * D10): what is the host's own. The selection → write-targets planning — literals to Local per
 * selected target, a group as a group write, a cell by its element key, through the live push —
 * the targets it hands the editor in rig order, the seed from the rig and its release, nothing
 * written under an empty selection, no mask refusal, and the footer's verbs reaching the editor:
 * Save over the selection as colour, Spread… handing the current colour or drawn inert. The
 * editor's own pieces — the emitter rows and counts, Pick, Recent, the knob — are pinned in
 * `ColourEditor.test.tsx`.
 */

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const toast = vi.hoisted(() => ({ error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() }))
vi.mock('sonner', () => ({ toast }))

vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: [] }),
  useApplyTemplateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
  useToggleTemplateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
}))
vi.mock('@/store/selection', () => ({ usePressFamilies: (local: unknown) => local }))
vi.mock('@/components/programmer/NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: ({ open, targets, families }: { open: boolean; targets: unknown[]; families: string[] }) =>
    open ? <div data-testid="new-template" data-targets={JSON.stringify(targets)} data-families={families.join()} /> : null,
}))
/** A built rig naming the bar first, so rig order and selection order differ. */
vi.mock('@/store/busk', () => ({
  useBuskRigQuery: () => ({ data: { rows: [{ id: 1, name: 'Row', tiles: [{ id: 1, kind: 'FIXTURE', patch: { key: 'bar' } }, { id: 2, kind: 'FIXTURE', patch: { key: 'par-1' } }] }] } }),
}))

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
const par1 = { key: 'par-1', name: 'PAR 1', typeKey: 'par', groups: ['Front wash', 'Reds'], properties: [colour()] } as unknown as Fixture
/** RGB only, like par-1: with it, 'Reds' is a group whose members agree on emitters. */
const par3 = { key: 'par-3', name: 'PAR 3', typeKey: 'par', groups: ['Reds'], properties: [colour()] } as unknown as Fixture
/** A dimmer-only head: nothing to write, nothing to read. */
const dim = {
  key: 'dim',
  name: 'Dimmer',
  typeKey: 'dimmer',
  groups: ['Front wash'],
  properties: [{ type: 'slider', name: 'dimmer', displayName: 'Dimmer', category: 'dimmer', channel: { universe: 1, channelNo: 20 }, min: 0, max: 255 }],
} as unknown as Fixture
const par2 = {
  key: 'par-2',
  name: 'PAR 2',
  typeKey: 'par',
  groups: ['Front wash'],
  properties: [colour({ whiteChannel: { universe: 1, channelNo: 4 } })],
} as unknown as Fixture
const bar = {
  key: 'bar',
  name: 'Bar L',
  typeKey: 'bar',
  groups: [],
  properties: [],
  elements: [
    { index: 0, key: 'bar.c1', displayName: 'Cell 1', properties: [colour({ amberChannel: { universe: 1, channelNo: 9 } })] },
    { index: 1, key: 'bar.c2', displayName: 'Cell 2', properties: [colour({ amberChannel: { universe: 1, channelNo: 10 } })] },
  ],
} as unknown as Fixture
const fixtures = [par1, par2, bar, par3, dim]
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures,
    fixtureTypes: [],
    fixtureByKey: new Map(fixtures.map((f) => [f.key, f])),
    typeByKey: new Map(),
  }),
}))
vi.mock('@/store/groups', () => ({
  useGroupListQuery: () => ({
    data: ['Front wash', 'Reds'].map((name) => ({ name, memberCount: 2, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] })),
  }),
}))
vi.mock('@/store/patches', () => ({
  usePatchListQuery: () => ({ data: fixtures.map((f, i) => ({ id: i + 1, key: f.key, displayName: f.name })) }),
}))
/** What each head is showing, keyed by patch key — what the appearance leaves report. */
const appearances: Record<string, { color: string; intensity: number; segments?: { css: string; intensity: number }[] }> = {}
vi.mock('@/components/fixtures/fixtureAppearance', () => ({
  FixtureAppearanceSource: ({ patch, children }: { patch: { key: string }; children: (a: unknown) => React.ReactNode }) => {
    const appearance = appearances[patch.key]
    return appearance == null ? null : children(appearance)
  },
}))

import { lightingApi } from '@/api/lightingApi'
import { ColourSheet, planColourWrites, writeTargetsOf } from './ColourSheet'

const groupOf = (name: string, memberCount: number): BuskingTarget => ({
  type: 'group',
  name,
  group: { name, memberCount, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] },
})
/** Mixed: par-1 (RGB), par-2 (RGBW) and a dimmer-only head. */
const group = groupOf('Front wash', 3)
/** Uniform: par-1 and par-3, both RGB. */
const reds = groupOf('Reds', 2)
const cell: BuskingTarget = { type: 'fixture', key: 'bar.c2', fixture: bar, element: bar.elements![1] }
const whole: BuskingTarget = { type: 'fixture', key: 'par-2', fixture: par2 }

function selectionOf(...targets: BuskingTarget[]) {
  return new Map(targets.map((t) => [t.type === 'group' ? `group:${t.name}` : `fixture:${t.key}`, t]))
}

function draw(targets: BuskingTarget[], props: Partial<React.ComponentProps<typeof ColourSheet>> = {}) {
  return render(<ColourSheet projectId={1} selectedTargets={selectionOf(...targets)} families={null} {...props} />)
}

const field = (name: string) => screen.getByLabelText(name) as HTMLInputElement
const setColour = vi.spyOn(lightingApi.programmer, 'setColour')

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
  appearances['par-1'] = { color: '#ff0000', intensity: 1 }
  appearances['par-2'] = { color: 'rgb(0, 0, 255)', intensity: 1 }
  appearances['par-3'] = { color: '#ff0000', intensity: 1 }
  appearances['dim'] = { color: '#fff8d5', intensity: 1 }
  appearances['bar'] = { color: '#00ff00', intensity: 1, segments: [{ css: '#111111', intensity: 1 }, { css: '#222222', intensity: 1 }] }
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  resetLiveAppearance()
  resetEditorSurfaceMedia()
})

describe('targets', () => {
  it('hands the editor the selection expanded — a group to its members, a cell named by its parent and position', () => {
    expect(writeTargetsOf([group, cell], fixtures).map((t) => [t.key, t.fixtureKey ?? null, t.cellIndex ?? null])).toEqual([
      ['par-1', null, null],
      ['par-2', null, null],
      ['dim', null, null],
      ['bar.c2', 'bar', 1],
    ])
  })

  it('reads Pick in rig order — the bar before the par the rig names second, whatever the selection’s order', () => {
    draw([reds, cell])
    // The seed from the rig is a Pick: bar.c2's segment, not par-1's red, because the built rig
    // puts the bar first.
    expect(field('R').value).toBe('34')
    expect(document.querySelector('[data-colour-picked="mixed"]')).not.toBeNull()
    expect(setColour).not.toHaveBeenCalled()
  })
})

describe('writes', () => {
  it('seeds the buffer from the rig, so a typed byte leaves the other five where the rig has them — a cell by its element key, a mixed group per member', () => {
    // The bar is not in this selection, so rig order puts par-1 first: red.
    draw([group, cell].slice(0, 1))
    expect(field('R').value).toBe('255')
    expect(field('G').value).toBe('0')
    expect(setColour).not.toHaveBeenCalled()
    cleanup()
    draw([group, cell])
    fireEvent.change(field('G'), { target: { value: '20' } })
    // Front wash is mixed (RGB + RGBW + a dimmer), so it fans per member carrying the group's name;
    // the dimmer takes nothing; the cell has amber and no white or UV, so those bytes are not sent.
    expect(setColour).toHaveBeenCalledTimes(3)
    expect(setColour).toHaveBeenCalledWith('fixture', 'par-1', 'rgbColour', { r: 34, g: 20, b: 34, w: undefined, a: undefined, uv: undefined }, 0, 'Front wash')
    expect(setColour).toHaveBeenCalledWith('fixture', 'par-2', 'rgbColour', { r: 34, g: 20, b: 34, w: 0, a: undefined, uv: undefined }, 0, 'Front wash')
    expect(setColour).toHaveBeenCalledWith('fixture', 'bar.c2', 'rgbColour', { r: 34, g: 20, b: 34, w: undefined, a: 0, uv: undefined }, 0, undefined)
  })

  it('writes a group whose members agree on emitters as one group write', () => {
    draw([reds])
    fireEvent.change(field('B'), { target: { value: '9' } })
    expect(setColour).toHaveBeenCalledTimes(1)
    expect(setColour).toHaveBeenCalledWith('group', 'Reds', 'rgbColour', { r: 255, g: 0, b: 9, w: undefined, a: undefined, uv: undefined }, 0, undefined)
  })

  it('plans a whole fixture as one write, sending only the emitters it has, and folds an undeliverable white into RGB — per member for a mixed group', () => {
    const white = { r: 0, g: 0, b: 0, w: 255, a: 0, uv: 0 }
    expect(planColourWrites([whole], white, fixtures)).toEqual([
      { targetType: 'fixture', targetKey: 'par-2', propertyName: 'rgbColour', colour: { r: 0, g: 0, b: 0, w: 255, a: undefined, uv: undefined }, sourceGroup: undefined },
    ])
    const par1Only: BuskingTarget = { type: 'fixture', key: 'par-1', fixture: par1 }
    expect(planColourWrites([par1Only], white, fixtures)[0].colour).toEqual({ r: 255, g: 255, b: 255, w: undefined, a: undefined, uv: undefined })
    // A pixel bar whose colour lives on its cells is one write per cell.
    const barWhole: BuskingTarget = { type: 'fixture', key: 'bar', fixture: bar }
    expect(planColourWrites([barWhole], white, fixtures).map((w) => w.targetKey)).toEqual(['bar.c1', 'bar.c2'])
    // Pure white over the mixed group: the RGB member gets RGB white, the RGBW member its white LED,
    // and the dimmer nothing — a single group write would have sent 0,0,0 to par-1.
    const mixed = planColourWrites([group], white, fixtures)
    expect(mixed.map((w) => [w.targetKey, w.colour.r, w.colour.w, w.sourceGroup])).toEqual([
      ['par-1', 255, undefined, 'Front wash'],
      ['par-2', 0, 255, 'Front wash'],
    ])
    // Agreeing members: one group write, folded for what they share.
    expect(planColourWrites([reds], white, fixtures)).toEqual([
      { targetType: 'group', targetKey: 'Reds', propertyName: 'rgbColour', colour: { r: 255, g: 255, b: 255, w: undefined, a: undefined, uv: undefined } },
    ])
  })

  it('writes nothing under an empty selection, and toasts as the strip does', () => {
    draw([])
    fireEvent.change(field('R'), { target: { value: '10' } })
    expect(setColour).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('Select the fixtures this should land on first', expect.objectContaining({ id: expect.any(String) }))
  })

  it('ends the gesture on a release anywhere in the window, and never flushes a stale colour onto a new selection', () => {
    const { rerender } = draw([reds])
    fireEvent.change(field('R'), { target: { value: '10' } })
    expect(setColour).toHaveBeenCalledTimes(1)
    // The picker binds its release to the document; a release outside the sheet still ends it.
    fireEvent.pointerUp(window)
    expect(setColour).toHaveBeenCalledTimes(1)
    // A new selection: the gesture is over and the buffer re-seeded, so a later release writes nothing.
    rerender(<ColourSheet projectId={1} selectedTargets={selectionOf(cell)} families={null} />)
    fireEvent.pointerUp(window)
    fireEvent.pointerUp(document.querySelector('[data-colour-sheet]')!)
    expect(setColour).toHaveBeenCalledTimes(1)
    // The field keeps its typed draft until it blurs; the value underneath is the cell's.
    fireEvent.blur(field('R'))
    expect(field('R').value).toBe('34')
  })

  it('does not consult the mask: a drag under a Position mask still lands — and the tab draws no heading, only the hex read-out', () => {
    draw([group, cell], { families: ['POSITION'] })
    expect(document.querySelector('[data-colour-sheet-heading]')).toBeNull()
    expect(screen.queryByText('Position')).toBeNull()
    expect(document.querySelector('[data-colour-editor-hex]')).toHaveTextContent(/^#[0-9a-f]{6}$/i)
    fireEvent.change(field('G'), { target: { value: '5' } })
    expect(setColour).toHaveBeenCalledTimes(3)
  })

  it('is a read and not a write after Pick: a pointer lifted over the sheet afterwards sends nothing', () => {
    draw([group])
    fireEvent.click(screen.getByRole('button', { name: 'Pick' }))
    fireEvent.pointerUp(document.querySelector('[data-colour-sheet]')!)
    expect(setColour).not.toHaveBeenCalled()
  })
})

describe('the footer’s verbs reach the editor', () => {
  it('docks the editor: the footer static under the scroller, the save first', () => {
    draw([group, cell])
    const scroller = document.querySelector('[data-colour-editor-scroller]')!
    expect(scroller.className).toContain('overflow-y-auto')
    const footer = document.querySelector('[data-editor-footer]')!
    expect(footer.className).toContain('shrink-0')
    expect(scroller.contains(footer)).toBe(false)
    const names = [...footer.querySelectorAll('button')].map((b) => b.getAttribute('aria-label') ?? b.textContent)
    expect(names[0]).toMatch(/Save as template/)
    expect(names).toContain('Spread to a second colour…')
  })

  it('opens the new-template sheet over the selection as colour', () => {
    draw([group, cell])
    fireEvent.click(screen.getByRole('button', { name: /Save as template/ }))
    const sheet = screen.getByTestId('new-template')
    expect(sheet).toHaveAttribute('data-families', 'COLOUR')
    expect(JSON.parse(sheet.getAttribute('data-targets')!)).toEqual([
      { type: 'group', key: 'Front wash' },
      { type: 'fixture', key: 'bar.c2' },
    ])
  })

  it('hands Spread to a second colour… the current colour once wired, and draws it inert on a host with no Spread tab', () => {
    const { unmount } = draw([reds])
    expect(screen.queryByRole('switch')).toBeNull()
    expect(screen.getByRole('button', { name: /Spread to a second colour/ })).toBeDisabled()
    unmount()
    const onSpread = vi.fn()
    draw([reds], { onSpread })
    expect(screen.getByRole('button', { name: /Spread to a second colour/ })).toBeEnabled()
    fireEvent.change(field('B'), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: /Spread to a second colour/ }))
    expect(onSpread).toHaveBeenCalledWith({ r: 255, g: 0, b: 7, w: 0, a: 0, uv: 0 })
  })
})
