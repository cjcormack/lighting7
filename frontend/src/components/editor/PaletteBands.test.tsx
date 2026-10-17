// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'
import type { CellResolution } from '@/components/fixtures-list/columns'
import type { CellBatch, WriteTarget } from '@/components/fixtures-list/rowModel'
import { chan, settingProp, sliderProp } from '@/test/fixtureFactories'

/**
 * The per-property popup (apply-model plan §4.3): the Palettes band round the level and position
 * editors. What is pinned is the offer per cell — a dimmer cell the Intensity palettes, a zoom cell
 * the Beam palettes with a zoom row, a gobo cell none — that a band press lands on the cell batch
 * under the cell's own family, the focused Look's teal line with no rings, *Save as palette…* with
 * the family, and that a cell with no press door (no `projectId`) draws its editor bare.
 */

let templates: TemplateSummary[] = []
const toggle = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve({ action: 'added' }) })))
const apply = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve({ written: 1, skipped: [], values: [] }) })))
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: templates }),
  useApplyTemplateMutation: () => [apply],
  useToggleTemplateMutation: () => [toggle],
}))
vi.mock('@/store/looks', () => ({
  useApplyLookMutation: () => [vi.fn()],
  useToggleLookMutation: () => [vi.fn()],
  useAbsorbLookEffectsMutation: () => [vi.fn()],
}))
vi.mock('@/store/fixtureFx', () => ({ useRemoveFxMutation: () => [vi.fn()] }))
/** What the desk's applied state says is on. */
const applied = vi.hoisted(() => ({ current: [] as unknown[] }))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: applied.current }) }))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: false, canEdit: true }) }))
vi.mock('@/store/hand', () => ({ handPickUp: vi.fn() }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [],
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
    look.focused ? { lookName: 'Warm wash', projectId: 1, lookId: 9, targets: [], targetedKeys: null, setValue: vi.fn() } : null,
}))
vi.mock('@/components/programmer/NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: ({ open, targets, families }: { open: boolean; targets: unknown[]; families: string[] }) =>
    open ? <div data-testid="new-template" data-targets={JSON.stringify(targets)} data-families={families.join()} /> : null,
}))

const { SliderCell } = await import('@/components/fixtures-list/cells/SliderCell')
const { PositionCell } = await import('@/components/fixtures-list/cells/PositionCell')
const { SettingCell } = await import('@/components/fixtures-list/cells/SettingCell')
const { paletteCarries } = await import('./PaletteBands')

function template(over: Partial<TemplateSummary>): TemplateSummary {
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
    rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:100' }],
    effect: null,
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
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#ff0000' }],
})
const WIDE = template({
  id: 4,
  uuid: 'u4',
  name: 'Wide',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'zoom', value: 'pct:100' }],
})
const SOFT = template({
  id: 5,
  uuid: 'u5',
  name: 'Soft edge',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'frost', value: 'pct:60' }],
})
const HOME = template({
  id: 6,
  uuid: 'u6',
  name: 'Home',
  family: 'POSITION',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'position', value: 'pos:0,0' }],
})
const PULSE = template({ id: 7, uuid: 'u7', name: 'Pulse', kind: 'effect', rows: [] })

const dimmer = sliderProp('dimmer', 'dimmer', chan(1))
const zoom = sliderProp('zoom', 'zoom', chan(2))
const DIMMER: NonNullable<CellResolution> = { kind: 'slider', property: dimmer }
const ZOOM: NonNullable<CellResolution> = { kind: 'slider', property: zoom }
const HEADS: WriteTarget[] = [
  { key: 'hex-1', properties: [dimmer, zoom] },
  { key: 'hex-2', properties: [dimmer, zoom] },
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
      label={resolution.kind === 'slider' ? resolution.property.name : 'Value'}
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
  templates = [FULL, HALF, RED, WIDE, SOFT, HOME, PULSE]
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
      templateId: 1,
      targets: [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'hex-2' },
      ],
      propertyMask: 'INTENSITY',
      families: ['INTENSITY'],
    })
    await act(async () => {
      fireEvent.click(screen.getByText('Half'), { altKey: true })
    })
    expect(apply).toHaveBeenCalledWith(expect.objectContaining({ templateId: 2, families: ['INTENSITY'] }))
  })

  it('wears the pad’s ring from the desk’s applied state', () => {
    applied.current = [
      {
        source: { kind: 'TEMPLATE', id: 1, uuid: 'u1', name: 'Full' },
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
        source: { kind: 'TEMPLATE', id: 1, uuid: 'u1', name: 'Full' },
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
        batch={{ count: 1, skipped: 0, resolutions: [], targets: [{ key: 'hex-1', properties: [] }] }}
        projectId={1}
        autoOpen
        keyboardSeed={null}
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(document.querySelector('[data-palette-band="POSITION"]')).not.toBeNull()
    expect(chips()).toEqual(['Home'])
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

  it('draws the editor bare where there is no press door — the plain lists, the cue grid', () => {
    slider(DIMMER, { projectId: undefined })
    expect(document.querySelector('[data-palette-band]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Save as palette…' })).toBeNull()
    expect(screen.getByRole('slider')).toBeInTheDocument()
  })

  it('gives a gobo cell no Palettes band: a value palette cannot carry a slotted role', () => {
    expect(paletteCarries('gobo')).toBe(false)
    expect(paletteCarries('zoom')).toBe(true)
    const gobo = settingProp('gobo', 'gobo', chan(5), [{ name: 'open', level: 0, displayName: 'Open' }])
    render(
      <SettingCell
        value={{ kind: 'setting', isUniform: true, level: 0 }}
        resolutions={[{ kind: 'setting', property: gobo }]}
        label="Gobo"
        autoOpen
        keyboardSeed=""
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(screen.getByRole('option', { name: 'Open' })).toBeInTheDocument()
    expect(document.querySelector('[data-palette-band]')).toBeNull()
  })
})
