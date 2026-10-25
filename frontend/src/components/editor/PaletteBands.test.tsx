// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PaletteSummary } from '@/api/palettesApi'
import type { CellResolution } from '@/components/fixtures-list/columns'
import type { CellBatch, WriteTarget } from '@/components/fixtures-list/rowModel'
import { chan, makeActiveFx, settingProp, sliderProp } from '@/test/fixtureFactories'

/**
 * The per-property popup (apply-model plan §4.3): the Palettes band round the level and position
 * editors. What is pinned is the offer per cell — a dimmer cell the Intensity palettes, a zoom cell
 * the Beam palettes with a zoom row, a gobo cell none — that a band press lands on the cell batch
 * under the cell's own family, the focused Look's teal line with no rings, *Save as palette…* with
 * the family, and that a cell with no press door (no `projectId`, no cue editor) draws its editor
 * bare — the cue editor's cells draw the bands through `PressCue`, pinned below. Then the
 * Effects band below the editor (session 6): a zoom cell offered Zoom Wave, a gobo cell its Effects
 * band and no Palettes band, the running effects lit and opened over the cell batch.
 */

let templates: PaletteSummary[] = []
const toggle = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve({ action: 'added' }) })))
const apply = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve({ written: 1, skipped: [], values: [] }) })))
vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({ data: templates }),
  useApplyPaletteMutation: () => [apply],
  useTogglePaletteMutation: () => [toggle],
}))
vi.mock('@/store/looks', () => ({
  useApplyLookMutation: () => [vi.fn()],
  useToggleLookMutation: () => [vi.fn()],
  useAbsorbLookFxMutation: () => [vi.fn()],
}))
/** The effect library and what runs — the Effects band's offer and its lit chips. */
const fx = vi.hoisted(() => ({ library: [] as unknown[], active: [] as unknown[] }))
vi.mock('@/store/fixtureFx', () => ({
  useRemoveFxMutation: () => [vi.fn()],
  useFxLibraryQuery: () => ({ data: fx.library }),
  useActiveFxQuery: () => ({ data: fx.active }),
}))
vi.mock('@/store/groups', () => ({ useRemoveGroupFxMutation: () => [vi.fn()] }))
/** The picker *+ Effect …* and *All* open, by the props they open it with — its own test is `ApplyPicker.test.tsx`. */
const picker = vi.hoisted(() => vi.fn())
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: { open: boolean; library: string }) => {
    picker(props)
    return props.open ? <div data-testid={`picker-${props.library}`} /> : null
  },
}))
/** The live editor a running chip opens — the split is `FxEditorPopover.test.tsx`'s. */
vi.mock('@/components/fx/FxEditorPopover', () => ({
  FxEditorPopover: ({ effect, selection }: { effect: { id: number } | null; selection?: unknown }) =>
    effect == null ? null : <div data-testid="fx-editor" data-effect={effect.id} data-selection={JSON.stringify(selection)} />,
}))
/** What the desk's applied state says is on. */
const applied = vi.hoisted(() => ({ current: [] as unknown[] }))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: applied.current }) }))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: false, canEdit: true }) }))
// The position popup's *Aim…* (venue-visit plan S13) asks whether the project is on the rig.
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 1 } }) }))
vi.mock('@/store/hand', () => ({ handPickUp: vi.fn() }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    // What an effect paints is read off the heads' properties, as the desk reads it.
    fixtures: ['hex-1', 'hex-2', 'hex-9'].map((key) => ({
      key,
      name: key,
      groups: [],
      elements: [],
      attributes: [
        { type: 'slider', name: 'dimmer' },
        { type: 'slider', name: 'zoom' },
      ],
    })),
    fixtureTypes: [],
    fixtureByKey: new Map([
      ['hex-1', { key: 'hex-1', name: 'Hex 1' }],
      ['hex-2', { key: 'hex-2', name: 'Hex 2' }],
    ]),
    typeByKey: new Map(),
  }),
}))
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => vi.fn(),
}))
// The scope: Local by default, a focused Look layer when a test says so.
const look = vi.hoisted(() => ({ focused: false }))
vi.mock('@/components/programmer/ProgrammerScope', () => ({
  useProgrammerScope: () => (look.focused ? { kind: 'layer', layerId: 'l1' } : null),
}))
vi.mock('@/components/programmer/LookRowStore', () => ({
  useLookRowStore: () =>
    look.focused ? { lookName: 'Warm wash', projectId: 1, lookId: 9, setValue: vi.fn() } : null,
}))
vi.mock('@/components/programmer/NewPaletteFromSelectionSheet', () => ({
  NewPaletteFromSelectionSheet: ({ open, targets, families }: { open: boolean; targets: unknown[]; families: string[] }) =>
    open ? <div data-testid="new-template" data-targets={JSON.stringify(targets)} data-families={families.join()} /> : null,
}))

const { SliderCell } = await import('@/components/fixtures-list/cells/SliderCell')
const { PositionCell } = await import('@/components/fixtures-list/cells/PositionCell')
const { SettingCell } = await import('@/components/fixtures-list/cells/SettingCell')
const { paletteCarries } = await import('./PaletteBands')
const { PressCueContext } = await import('@/components/press/pressCue')

function template(over: Partial<PaletteSummary>): PaletteSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Palette',
    notes: null,
    fadeDurationMs: null,
    family: 'INTENSITY',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    lastPressedAt: null,
    rows: [{ targetType: 'deferred', targetKey: '', attribute: 'dimmer', value: 'pct:100' }],
    fx: null,
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

const FULL = template({ id: 1, uuid: 'u1', name: 'Full' })
const HALF = template({ id: 2, uuid: 'u2', name: 'Half', lastPressedAt: '2026-10-16T20:00:00.000Z' })
const RED = template({
  id: 3,
  uuid: 'u3',
  name: 'Red',
  family: 'COLOUR',
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#ff0000' }],
})
const WIDE = template({
  id: 4,
  uuid: 'u4',
  name: 'Wide',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'zoom', value: 'pct:100' }],
})
const SOFT = template({
  id: 5,
  uuid: 'u5',
  name: 'Soft edge',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'frost', value: 'pct:60' }],
})
const HOME = template({
  id: 6,
  uuid: 'u6',
  name: 'Home',
  family: 'POSITION',
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'position', value: 'pos:0,0' }],
})
const PULSE = template({ id: 7, uuid: 'u7', name: 'Pulse', kind: 'fx', rows: [] })
const beamFx = (fxType: string, attribute: string | null = null) => ({
  fxType,
  family: 'beam',
  attribute,
  cycle: 4,
  blendMode: 'OVERRIDE',
  order: 'NORMAL',
  parameters: {},
})
const WAVE_ZOOM = template({ id: 8, uuid: 'u8', name: 'Slow zoom', family: 'BEAM', kind: 'fx', rows: [], fx: beamFx('ZoomWave') })
const PULSE_IRIS = template({ id: 9, uuid: 'u9', name: 'Iris throb', family: 'BEAM', kind: 'fx', rows: [], fx: beamFx('IrisPulse') })
const GOBO_STEPS = template({ id: 10, uuid: 'u10', name: 'Gobo walk', family: 'BEAM', kind: 'fx', rows: [], fx: beamFx('SlotSteps', 'gobo') })
const libraryEntry = (name: string, attribute: string) => ({
  name,
  family: 'beam',
  outputType: 'SLIDER',
  fxMode: 'STANDARD',
  compatibleAttributes: [attribute],
  parameters: [],
})
const ZOOM_WAVE = libraryEntry('ZoomWave', 'zoom')
const IRIS_PULSE = libraryEntry('IrisPulse', 'iris')

const dimmer = sliderProp('dimmer', 'dimmer', chan(1))
const zoom = sliderProp('zoom', 'zoom', chan(2))
const DIMMER: NonNullable<CellResolution> = { kind: 'slider', attribute: dimmer }
const ZOOM: NonNullable<CellResolution> = { kind: 'slider', attribute: zoom }
const HEADS: WriteTarget[] = [
  { key: 'hex-1', attributes: [dimmer, zoom] },
  { key: 'hex-2', attributes: [dimmer, zoom] },
]
const batchOf = (resolution: NonNullable<CellResolution>): CellBatch => ({
  count: 2,
  skipped: 0,
  resolutions: [resolution, resolution],
  targets: HEADS,
})

function slider(resolution: NonNullable<CellResolution>, over: Partial<React.ComponentProps<typeof SliderCell>> = {}) {
  return render(
    <SliderCell
      value={{ kind: 'slider', min: 128, max: 128, isUniform: true }}
      resolutions={[resolution]}
      label={resolution.kind === 'slider' ? resolution.attribute.name : 'Value'}
      batch={batchOf(resolution)}
      projectId={1}
      autoOpen
      keyboardSeed={null}
      onCommit={() => {}}
      onBeginEdit={() => {}}
      {...over}
    />,
  )
}

const chips = () => [...document.querySelectorAll('[data-band-palette]')].map((chip) => chip.textContent)

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  templates = [FULL, HALF, RED, WIDE, SOFT, HOME, PULSE, WAVE_ZOOM, PULSE_IRIS, GOBO_STEPS]
  fx.library = [ZOOM_WAVE, IRIS_PULSE]
  fx.active = []
  picker.mockClear()
  applied.current = []
  look.focused = false
  toggle.mockClear()
  apply.mockClear()
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the Palettes band', () => {
  it('offers a dimmer cell the Intensity value palettes, recent first then by name', () => {
    slider(DIMMER)
    expect(document.querySelector('[data-palette-band="INTENSITY"]')).not.toBeNull()
    // Not Red (Colour) nor Pulse (an effect palette — the Effects band's, session 6).
    expect(chips()).toEqual(['Half', 'Full'])
  })

  it('offers a zoom cell the Beam palettes with a zoom row, not every Beam palette', () => {
    slider(ZOOM)
    expect(document.querySelector('[data-palette-band="BEAM"]')).not.toBeNull()
    expect(chips()).toEqual(['Wide'])
  })

  it('names the heads the press lands on and the cell’s family as the mask', () => {
    slider(DIMMER)
    expect(document.querySelector('[data-palette-band-heads]')).toHaveTextContent('on Hex 1, Hex 2')
    expect(document.querySelector('[data-palette-band] [data-slot="badge"]')).toHaveTextContent('Intensity')
  })

  it('lands a press on the cell batch under the cell’s family', async () => {
    slider(DIMMER)
    await act(async () => {
      fireEvent.click(screen.getByText('Full'))
    })
    expect(toggle).toHaveBeenCalledWith({
      projectId: 1,
      paletteId: 1,
      targets: [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'hex-2' },
      ],
      attributeMask: 'INTENSITY',
      families: ['INTENSITY'],
    })
    await act(async () => {
      fireEvent.click(screen.getByText('Half'), { altKey: true })
    })
    expect(apply).toHaveBeenCalledWith(expect.objectContaining({ paletteId: 2, families: ['INTENSITY'] }))
  })

  it('wears the pad’s ring from the desk’s applied state', () => {
    applied.current = [
      {
        source: { kind: 'PALETTE', id: 1, uuid: 'u1', name: 'Full' },
        targets: [
          { type: 'fixture', key: 'hex-1', state: 'all' },
          { type: 'fixture', key: 'hex-2', state: 'all' },
        ],
      },
    ]
    slider(DIMMER)
    expect(document.querySelector('[data-band-palette="1"]')).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-band-palette="2"]')).toHaveAttribute('aria-pressed', 'false')
  })

  it('under a focused Look says where a press goes, and draws no ring', () => {
    look.focused = true
    applied.current = [
      {
        source: { kind: 'PALETTE', id: 1, uuid: 'u1', name: 'Full' },
        targets: [
          { type: 'fixture', key: 'hex-1', state: 'all' },
          { type: 'fixture', key: 'hex-2', state: 'all' },
        ],
      },
    ]
    slider(DIMMER)
    expect(document.querySelector('[data-palette-band-into]')).toHaveTextContent('Into Warm wash')
    expect(document.querySelector('[data-band-palette="1"]')).toHaveAttribute('aria-pressed', 'false')
  })

  it('wraps the position editor too, with the Position palettes', () => {
    render(
      <PositionCell
        value={{ kind: 'position', isUniform: true, pan: 128, tilt: 128, panNormalized: 0.5, tiltNormalized: 0.5 }}
        resolutions={[{ kind: 'position', pan: chan(3), tilt: chan(4), panMin: 0, panMax: 255, tiltMin: 0, tiltMax: 255 }]}
        label="Position"
        batch={{ count: 1, skipped: 0, resolutions: [], targets: [{ key: 'hex-1', attributes: [] }] }}
        projectId={1}
        autoOpen
        keyboardSeed={null}
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(document.querySelector('[data-palette-band="POSITION"]')).not.toBeNull()
    expect(chips()).toEqual(['Home'])
    // The footer's tools slot holds *Aim…* (venue-visit plan S13), after the save.
    const footer = document.querySelector('[data-editor-footer]')!
    expect([...footer.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Save as palette…', 'Aim…'])
  })

  it('opens Save as palette… with the cell’s family over the batch', () => {
    slider(ZOOM)
    fireEvent.click(screen.getByRole('button', { name: 'Save as palette…' }))
    const sheet = screen.getByTestId('new-template')
    expect(sheet).toHaveAttribute('data-families', 'BEAM')
    expect(JSON.parse(sheet.getAttribute('data-targets')!)).toEqual([
      { type: 'fixture', key: 'hex-1' },
      { type: 'fixture', key: 'hex-2' },
    ])
  })

  it('draws the editor bare where there is no press door — the plain lists', () => {
    slider(DIMMER, { projectId: undefined })
    expect(document.querySelector('[data-palette-band]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Save as palette…' })).toBeNull()
    expect(screen.getByRole('slider')).toBeInTheDocument()
  })

  it('gives a gobo cell no Palettes band: a value palette cannot carry a slotted role', () => {
    expect(paletteCarries('gobo')).toBe(false)
    expect(paletteCarries('zoom')).toBe(true)
    gobo()
    expect(screen.getByRole('option', { name: 'Open' })).toBeInTheDocument()
    expect(document.querySelector('[data-palette-band]')).toBeNull()
  })
})

const goboProp = settingProp('gobo', 'gobo', chan(5), [{ name: 'open', level: 0, displayName: 'Open' }])
function gobo(over: Partial<React.ComponentProps<typeof SettingCell>> = {}) {
  return render(
    <SettingCell
      value={{ kind: 'setting', isUniform: true, level: 0 }}
      resolutions={[{ kind: 'setting', attribute: goboProp }]}
      label="Gobo"
      batch={{ count: 2, skipped: 0, resolutions: [], targets: HEADS }}
      projectId={1}
      autoOpen
      keyboardSeed=""
      onCommit={() => {}}
      onBeginEdit={() => {}}
      {...over}
    />,
  )
}

const fxChips = () => [...document.querySelectorAll('[data-band-effect-palette]')].map((chip) => chip.textContent)

describe('the Effects band', () => {
  it('sits below a dimmer cell’s editor, under its family', () => {
    slider(DIMMER)
    const band = document.querySelector('[data-effects-band="INTENSITY"]')
    expect(band).not.toBeNull()
    // Below the editor: after the slider in document order.
    expect(screen.getByRole('slider').compareDocumentPosition(band!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('offers a zoom cell Zoom Wave — the Beam effect palettes that drive zoom — and opens the picker on zoom', () => {
    slider(ZOOM)
    expect(fxChips()).toEqual(['Slow zoom'])
    fireEvent.click(screen.getByRole('button', { name: 'FX…' }))
    expect(screen.getByTestId('picker-fx')).toBeInTheDocument()
    expect(picker).toHaveBeenLastCalledWith(
      expect.objectContaining({
        library: 'fx',
        family: 'BEAM',
        attribute: 'zoom',
        pair: { targets: [{ type: 'fixture', key: 'hex-1' }, { type: 'fixture', key: 'hex-2' }], families: ['BEAM'] },
      }),
    )
  })

  it('gives a gobo cell its Effects band and no Palettes band: Slot Steps of its gobo', () => {
    gobo()
    expect(document.querySelector('[data-palette-band]')).toBeNull()
    expect(document.querySelector('[data-effects-band="BEAM"]')).not.toBeNull()
    expect(fxChips()).toEqual(['Gobo walk'])
    expect(screen.queryByRole('button', { name: 'Save as palette…' })).toBeNull()
  })

  it('draws a setting cell bare where there is no press door', () => {
    gobo({ projectId: undefined })
    expect(document.querySelector('[data-effects-band]')).toBeNull()
  })

  it('presses an effect palette onto the cell batch under the cell’s family', async () => {
    slider(ZOOM)
    await act(async () => {
      fireEvent.click(screen.getByText('Slow zoom'))
    })
    expect(toggle).toHaveBeenCalledWith(
      expect.objectContaining({ paletteId: 8, families: ['BEAM'], targets: [{ type: 'fixture', key: 'hex-1' }, { type: 'fixture', key: 'hex-2' }] }),
    )
  })

  it('lights the effects running on these heads and opens the editor over the cell batch', () => {
    fx.active = [
      makeActiveFx({ id: 41, fxType: 'Pulse', targetKey: 'hex-2', attribute: 'dimmer' }),
      makeActiveFx({ id: 42, fxType: 'Pulse', targetKey: 'hex-9', attribute: 'dimmer' }),
      makeActiveFx({ id: 43, fxType: 'ZoomWave', targetKey: 'hex-1', attribute: 'zoom' }),
    ]
    slider(DIMMER)
    const lit = [...document.querySelectorAll('[data-band-effect]')].map((chip) => chip.getAttribute('data-band-effect'))
    expect(lit).toEqual(['41'])
    fireEvent.click(document.querySelector('[data-band-effect="41"]')!)
    const editor = screen.getByTestId('fx-editor')
    expect(editor).toHaveAttribute('data-effect', '41')
    expect(JSON.parse(editor.getAttribute('data-selection')!)).toEqual([
      { type: 'fixture', key: 'hex-1' },
      { type: 'fixture', key: 'hex-2' },
    ])
  })

  it('reads a cue’s effect as its cue’s: lit, but not edited here', () => {
    fx.active = [makeActiveFx({ id: 44, fxType: 'Pulse', targetKey: 'hex-1', programmerOwned: false, cueId: 12 })]
    slider(DIMMER)
    expect(document.querySelector('[data-band-effect="44"]')).toBeDisabled()
  })
})

describe('in the cue editor (apply-model plan §3.6)', () => {
  const storedPulse = (targetKey: string, attribute = 'dimmer') => ({
    targetType: 'fixture' as const,
    targetKey,
    fxType: attribute === 'zoom' ? 'ZoomWave' : 'Pulse',
    family: 'intensity',
    attribute,
    cycle: 4,
    blendMode: 'OVERRIDE',
    order: 'NORMAL',
    phaseOffset: 0,
    elementMode: null,
    elementFilter: null,
    timing: null,
    parameters: {},
  })
  function inCue(layered: string[] = [], { readOnly = false, effects = [storedPulse('hex-2'), storedPulse('hex-9'), storedPulse('hex-1', 'zoom')] } = {}) {
    const cue = {
      projectId: 1,
      cueId: 3,
      cueLabel: 'Q2',
      readOnly,
      layered: new Set(layered),
      effects,
      pressLayer: vi.fn(() => Promise.resolve({ action: 'applied' as const, cue: {} as never })),
      landValues: vi.fn(() => 0),
      pickFx: vi.fn(() => Promise.resolve(true)),
    }
    render(
      <PressCueContext.Provider value={cue}>
        <SliderCell
          value={{ kind: 'slider', min: 128, max: 128, isUniform: true }}
          resolutions={[DIMMER]}
          label="dimmer"
          batch={batchOf(DIMMER)}
          autoOpen
          keyboardSeed={null}
          onCommit={() => {}}
          onBeginEdit={() => {}}
        />
      </PressCueContext.Provider>,
    )
    return cue
  }

  it('draws the bands with the grid passing no project, says where a press goes, and no Save as palette…', () => {
    inCue()
    expect(chips()).toEqual(['Half', 'Full'])
    expect(document.querySelector('[data-palette-band-into]')?.textContent).toBe('Into Q2 · as a layer')
    expect(screen.queryByRole('button', { name: 'Save as palette…' })).toBeNull()
  })

  it('a chip press is the cue’s layer press on the cell batch under the cell’s family, never the programmer’s', async () => {
    const cue = inCue()
    await act(async () => {
      fireEvent.click(screen.getByText('Full'))
    })
    expect(cue.pressLayer).toHaveBeenCalledWith({
      paletteId: 1,
      targets: [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'hex-2' },
      ],
      families: ['INTENSITY'],
    })
    expect(toggle).not.toHaveBeenCalled()
  })

  it('rings a chip the cue layers, partly', () => {
    inCue(['PALETTE:2'])
    expect(screen.getByText('Half').closest('button')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Full').closest('button')).toHaveAttribute('aria-pressed', 'false')
  })

  it('lists the cue’s stored effects on this property that reach these heads, and nothing running', () => {
    fx.active = [makeActiveFx({ id: 41, fxType: 'Pulse', targetKey: 'hex-2', attribute: 'dimmer' })]
    inCue()
    expect(document.querySelector('[data-band-effect]')).toBeNull()
    const stored = [...document.querySelectorAll('[data-band-stored-effect]')]
    expect(stored.map((chip) => chip.textContent)).toEqual(['Pulse'])
    expect(stored[0].getAttribute('title')).toMatch(/stored in Q2; edit it in the FX tab/)
  })

  it('lists a stored effect with no property of its own under its family', () => {
    inCue([], { effects: [{ ...storedPulse('hex-1'), attribute: null as unknown as string }] })
    expect([...document.querySelectorAll('[data-band-stored-effect]')].map((chip) => chip.textContent)).toEqual(['Pulse'])
  })

  it('draws no bands in a cue that is read-only', () => {
    inCue([], { readOnly: true })
    expect(document.querySelector('[data-palette-band]')).toBeNull()
    expect(document.querySelector('[data-effects-band]')).toBeNull()
  })
})
