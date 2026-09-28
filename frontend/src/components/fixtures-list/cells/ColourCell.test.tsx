// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { chan, colourProp, settingProp } from '@/test/fixtureFactories'
import type { CellResolution } from '../columns'
import type { CellBatch } from '../rowModel'
import { resetEditorSurfaceMedia } from '../../editor/EditorSurface'

/**
 * The programmer's colour cell as a host of `ColourEditor` (editor-kit plan D10–D12): Recent only
 * in the bottom-sheet form; Save opening the new-template sheet with Colour answered over the
 * marquee's fixtures; Spread… handing the colour to row C's panel as *From*, RGB only, and closing
 * this editor (session 3); the label line in the popover; and
 * no *Applying to N targets* anywhere on it.
 */

vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
let templates: unknown[] = []
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: templates }),
  useApplyTemplateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
  useToggleTemplateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
}))
vi.mock('@/store/selection', () => ({ usePressFamilies: (local: unknown) => local }))
vi.mock('@/store/patches', () => ({ usePatchListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: [], fixtureTypes: [], fixtureByKey: new Map(), typeByKey: new Map() }),
}))
vi.mock('@/components/fixtures/fixtureAppearance', () => ({ FixtureAppearanceSource: () => null }))
vi.mock('@/components/programmer/NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: ({ open, targets, families }: { open: boolean; targets: unknown[]; families: string[] }) =>
    open ? <div data-testid="new-template" data-targets={JSON.stringify(targets)} data-families={families.join()} /> : null,
}))

import { ColourCell, colourTargetsOf, templateTargetsOf } from './ColourCell'

const rgb = colourProp('rgbColour', chan(1), chan(2), chan(3))
const rgbw = colourProp('rgbColour', chan(1), chan(2), chan(3), { whiteChannel: chan(4) })
const RGB: NonNullable<CellResolution> = { kind: 'colour', property: rgb }
const RGBW: NonNullable<CellResolution> = { kind: 'colour', property: rgbw }
/** Three heads: a par, an RGBW par, and one cell of a bar (named by its parent, as `rowWriteTargets` does). */
const batch: CellBatch = {
  count: 3,
  skipped: 0,
  resolutions: [RGB, RGBW, RGB],
  targets: [
    { key: 'par-1', properties: [rgb] },
    { key: 'par-2', properties: [rgbw] },
    { key: 'bar.c2', properties: [rgb], fixtureKey: 'bar', cellIndex: 1 },
  ],
}

function stubMatchMedia({ narrow = false, short = false } = {}) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('max-width') ? narrow : query.includes('500px') ? short : false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
}

function draw(props: Partial<React.ComponentProps<typeof ColourCell>> = {}) {
  const onCommit = vi.fn()
  render(
    <ColourCell
      value={{ kind: 'colour', isUniform: true, r: 10, g: 20, b: 30, combinedCss: 'rgb(10, 20, 30)' }}
      resolutions={[RGB]}
      label="Colour"
      batch={batch}
      scopeLabel="Local"
      projectId={1}
      autoOpen
      keyboardSeed=""
      onCommit={onCommit}
      onBeginEdit={() => {}}
      {...props}
    />,
  )
  return { onCommit }
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
  stubMatchMedia()
  templates = [
    {
      id: 2,
      uuid: 't2',
      name: 'Warm Amber',
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
    },
  ]
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  resetEditorSurfaceMedia()
})

describe('ColourCell', () => {
  it('draws the label line in the popover — the batch and the scope, the column on the right — and no “Applying to” anywhere', () => {
    draw()
    const line = document.querySelector('[data-editor-label-line]')!
    expect(line).toHaveTextContent('3 heads · Local')
    expect(line).toHaveTextContent('Colour')
    expect(document.body.textContent).not.toContain('Applying to')
    expect(screen.getByRole('button', { name: /10,20,30/ })).not.toHaveAttribute('title')
  })

  it('offers the emitter rows as the batch’s union, not the row’s own', () => {
    draw()
    // The row itself is RGB; the batch has an RGBW head, so the W row is offered.
    expect(screen.getByLabelText('W value')).toBeInTheDocument()
    expect(document.querySelector('[data-colour-emitters]')).toHaveTextContent('Emitters on 1 of 3 heads · the rest take RGB only')
  })

  it('draws Recent in the bottom sheet alone: not in the popover, not in the side sheet', () => {
    draw()
    expect(document.querySelector('[data-colour-editor-recent]')).toBeNull()
    cleanup()
    resetEditorSurfaceMedia()
    stubMatchMedia({ narrow: true })
    draw()
    expect(document.querySelector('[data-colour-editor-recent]')).not.toBeNull()
    expect(document.querySelectorAll('[data-recent-template]')).toHaveLength(1)
    cleanup()
    resetEditorSurfaceMedia()
    stubMatchMedia({ short: true })
    draw()
    expect(document.querySelector('[data-colour-editor-recent]')).toBeNull()
  })

  it('Save as template… opens the sheet with Colour answered over the marquee’s fixtures — a cell folded onto its parent', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: /Save as template/ }))
    const sheet = screen.getByTestId('new-template')
    expect(sheet).toHaveAttribute('data-families', 'COLOUR')
    expect(JSON.parse(sheet.getAttribute('data-targets')!)).toEqual([
      { type: 'fixture', key: 'par-1' },
      { type: 'fixture', key: 'par-2' },
      { type: 'fixture', key: 'bar' },
    ])
    expect(templateTargetsOf([{ key: 'bar.c1', properties: [], fixtureKey: 'bar' }, { key: 'bar.c2', properties: [], fixtureKey: 'bar' }])).toEqual([{ type: 'fixture', key: 'bar' }])
  })

  it('Spread… hands the RGB to the host as From — no emitter travels — and closes this editor; inert with no host to open', () => {
    const onSpread = vi.fn()
    const onOpenChange = vi.fn()
    draw({ onSpread, resolutions: [RGBW], value: { kind: 'colour', isUniform: true, r: 10, g: 20, b: 30, w: 200, combinedCss: 'rgb(10, 20, 30)' } })
    expect(document.querySelector('[data-cell-editor-surface]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Spread to a second colour/ }))
    expect(onSpread).toHaveBeenCalledWith({ r: 10, g: 20, b: 30 })
    // The seed is RGB alone: the busk `SpreadSeed` shape, since a colour intent has no emitter component.
    expect(Object.keys(onSpread.mock.calls[0][0]).sort()).toEqual(['b', 'g', 'r'])
    expect(document.querySelector('[data-cell-editor-surface]')).toBeNull()
    expect(onOpenChange).not.toHaveBeenCalled()
    cleanup()
    draw()
    expect(screen.getByRole('button', { name: /Spread to a second colour/ })).toBeDisabled()
    cleanup()
    draw({ projectId: undefined })
    expect(screen.getByRole('button', { name: /Save as template/ })).toBeDisabled()
    expect(document.querySelector('[data-colour-editor-leaves]')).toBeNull()
  })

  it('commits a typed byte beside the other two, and leaves an emitter this row does not hold unstated', () => {
    // The W row is offered because the batch's union has an RGBW head, but this row has no white
    // byte to state. A defined 0 would be written to the RGBW head (`writeColour` samples the wire
    // only for undefined), so the white it holds is left alone until the operator states it.
    const { onCommit } = draw()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'G' }), { target: { value: '99' } })
    expect(onCommit).toHaveBeenCalledWith({ kind: 'colour', r: 10, g: 99, b: 30, w: undefined, a: undefined, uv: undefined })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'W value' }), { target: { value: '40' } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'colour', r: 10, g: 99, b: 30, w: 40, a: undefined, uv: undefined })
  })

  it('hands the editor the batch’s targets as a colour commit lands on them, and both lines count that list', () => {
    const rgba = colourProp('rgbColour', chan(1), chan(2), chan(3), { amberChannel: chan(5) })
    const bar = {
      key: 'bar',
      properties: [],
      elements: [
        { index: 0, key: 'bar.c1', displayName: 'Cell 1', properties: [rgb] },
        { index: 1, key: 'bar.c2', displayName: 'Cell 2', properties: [rgba] },
      ],
    }
    const dim = { key: 'dim', properties: [] }
    expect(colourTargetsOf([{ key: 'par-1', properties: [rgb] }, bar, dim])).toEqual([
      { key: 'par-1', properties: [rgb] },
      { key: 'bar.c1', properties: [rgb], fixtureKey: 'bar', cellIndex: 0 },
      { key: 'bar.c2', properties: [rgba], fixtureKey: 'bar', cellIndex: 1 },
    ])
    // One collapsed bar and a par: the column resolves three cells, and both lines say three.
    draw({ batch: { count: 3, skipped: 1, resolutions: [RGB, RGB, { kind: 'colour', property: rgba }], targets: [{ key: 'par-1', properties: [rgb] }, bar, dim] } })
    expect(document.querySelector('[data-editor-label-line]')).toHaveTextContent('3 heads · Local')
    expect(document.querySelector('[data-colour-emitters]')).toHaveTextContent('Emitters on 1 of 3 heads · the rest take RGB only')
  })

  it('counts a colour-wheel head in neither line: the column resolves it, a colour commit refuses it', () => {
    // A MAC-250-shaped head: a setting in the colour category and no RGB. `batchForTargets` counts
    // its `colour-setting` cell (it is not skipped — it resolved one cell), but the commit lands on
    // the par alone, and the label line must not promise two heads over a read-out of one.
    const wheel = settingProp('colourWheel', 'colour', chan(7), [{ name: 'open', level: 0, displayName: 'Open' }])
    const mac = { key: 'mac', properties: [wheel] }
    expect(colourTargetsOf([{ key: 'par-2', properties: [rgbw] }, mac])).toEqual([{ key: 'par-2', properties: [rgbw] }])
    cleanup()
    draw({ batch: { count: 2, skipped: 0, resolutions: [RGBW, { kind: 'colour-setting', property: wheel }], targets: [{ key: 'par-2', properties: [rgbw] }, mac] } })
    expect(document.querySelector('[data-editor-label-line]')).toHaveTextContent('1 head · Local')
    expect(document.querySelector('[data-colour-emitters]')).toHaveTextContent('Emitters on 1 of 1 head')
    expect(document.querySelector('[data-colour-emitters]')).not.toHaveTextContent('the rest')
  })

  it('draws no footer on a cell mounted with no batch — the cue grid’s read-only colour', () => {
    draw({ batch: undefined })
    expect(document.querySelector('[data-editor-footer]')).toBeNull()
  })
})
