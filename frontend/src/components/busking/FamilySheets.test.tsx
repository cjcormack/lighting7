// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { chan, element, groupSummary, makeFixture, positionProp, sliderProp } from '@/test/fixtureFactories'
import type { BuskingTarget } from './buskingTypes'

/**
 * The busk side sheet's Level, Position and Beam tabs (apply-model plan §4.5, D10): the popup's body
 * over the busk selection pair. What is pinned is that each tab's band presses the busk pair — its
 * targets as the busk view addresses them (a group as one, a cell by its key) and its mask — into
 * Local whatever the grid is pointed at, that the editors write Local (the Position tab's XY pad
 * one `setPosition` per head), and that the Beam tab acts on the Beam attribute the operator picks.
 */

let templates: TemplateSummary[] = []
const toggle = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve({ action: 'added' }) })))
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: templates }),
  useApplyTemplateMutation: () => [vi.fn()],
  useToggleTemplateMutation: () => [toggle],
}))
vi.mock('@/store/looks', () => ({
  useApplyLookMutation: () => [vi.fn()],
  useToggleLookMutation: () => [vi.fn()],
  useAbsorbLookEffectsMutation: () => [vi.fn()],
}))
vi.mock('@/store/fixtureFx', () => ({
  useRemoveFxMutation: () => [vi.fn()],
  useEffectLibraryQuery: () => ({ data: [] }),
  useActiveEffectsQuery: () => ({ data: [] }),
}))
vi.mock('@/store/groups', () => ({ useRemoveGroupFxMutation: () => [vi.fn()] }))
const picker = vi.hoisted(() => vi.fn())
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: { open: boolean; library: string }) => {
    picker(props)
    return props.open ? <div data-testid={`picker-${props.library}`} /> : null
  },
}))
vi.mock('@/components/fx/FxEditorPopover', () => ({ FxEditorPopover: () => null }))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: [] }) }))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: false, canEdit: true }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/store/hand', () => ({ handPickUp: vi.fn() }))
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => vi.fn(),
}))
// The programmer grid is pointed at a focused Look layer: the busk tabs must write Local anyway.
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => ({ kind: 'layer', layerId: 'l1' }) }))
vi.mock('@/components/programmer/LookRowStore', () => ({
  useLookRowStore: () => ({ lookName: 'Warm wash', projectId: 1, lookId: 9, targets: [], targetedKeys: null, setValue: vi.fn() }),
}))
vi.mock('@/components/programmer/NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: ({ open, targets, families }: { open: boolean; targets: unknown[]; families: string[] }) =>
    open ? <div data-testid="new-template" data-targets={JSON.stringify(targets)} data-families={families.join()} /> : null,
}))
vi.mock('@/components/fixtureSheet/useChannelValues', () => ({
  useChannelValues: (refs: readonly unknown[]) => refs.map(() => 128),
}))
const programmer = vi.hoisted(() => ({ set: vi.fn(), setPosition: vi.fn(), getKeyState: () => ({ entry: null }) }))
// The two families of the socket the tabs write through; every other call answers a subscription
// and nothing else, for the stores that subscribe when they are imported.
vi.mock('@/api/lightingApi', () => {
  const known: Record<string, unknown> = { programmer, channels: { get: () => 128 } }
  const quiet = new Proxy({}, { get: () => () => ({ unsubscribe: () => {} }) })
  return { lightingApi: new Proxy(known, { get: (target, key: string) => target[key] ?? quiet }) }
})

const SPOT_1 = makeFixture(
  'spot-1',
  [sliderProp('dimmer', 'dimmer', chan(1)), sliderProp('zoom', 'zoom', chan(2)), sliderProp('iris', 'iris', chan(3)), positionProp('position', chan(4), chan(5))],
  { name: 'Spot 1', groups: ['Spots'] },
)
const SPOT_2 = makeFixture(
  'spot-2',
  [sliderProp('dimmer', 'dimmer', chan(11)), sliderProp('zoom', 'zoom', chan(12)), positionProp('position', chan(14), chan(15))],
  { name: 'Spot 2', groups: ['Spots'] },
)
/** A bar whose dimmer lives on its cells, not on the fixture. */
const BAR = makeFixture('bar-1', [], {
  name: 'Bar 1',
  elements: [element(0, 'bar-1.head-1', [sliderProp('dimmer', 'dimmer', chan(21))]), element(1, 'bar-1.head-2', [sliderProp('dimmer', 'dimmer', chan(22))])],
})
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [SPOT_1, SPOT_2, BAR],
    fixtureTypes: [],
    fixtureByKey: new Map([SPOT_1, SPOT_2, BAR].map((f) => [f.key, f])),
    typeByKey: new Map(),
  }),
}))

const { LevelSheet, PositionSheet, BeamSheet } = await import('./FamilySheets')

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

const spots: BuskingTarget = { type: 'group', name: 'Spots', group: groupSummary('Spots', 2) }
const bar: BuskingTarget = { type: 'fixture', key: 'bar-1', fixture: BAR }
const selection = new Map<string, BuskingTarget>([
  ['group:Spots', spots],
  ['fixture:bar-1', bar],
])
const BUSK_PAIR_TARGETS = [
  { type: 'group', key: 'Spots' },
  { type: 'fixture', key: 'bar-1' },
]

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  templates = [
    template({ id: 1, name: 'Full' }),
    template({ id: 2, name: 'Centre', family: 'POSITION', rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'position', value: 'pos:0,0' }] }),
    template({ id: 3, name: 'Wide', family: 'BEAM', rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'zoom', value: 'pct:100' }] }),
    template({ id: 4, name: 'Open iris', family: 'BEAM', rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'iris', value: 'pct:100' }] }),
  ]
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const chips = () => [...document.querySelectorAll('[data-band-palette]')].map((chip) => chip.textContent)

describe('each family tab presses over the busk pair, into Local', () => {
  const CASES = [
    { name: 'Level', Tab: LevelSheet, family: 'INTENSITY', chip: 'Full', templateId: 1 },
    { name: 'Position', Tab: PositionSheet, family: 'POSITION', chip: 'Centre', templateId: 2 },
    { name: 'Beam', Tab: BeamSheet, family: 'BEAM', chip: 'Wide', templateId: 3 },
  ] as const

  for (const { name, Tab, family, chip, templateId } of CASES) {
    it(`${name}: the family’s Palettes band lands on the busk targets under the busk mask, a focused Look notwithstanding`, async () => {
      const families: AttributeFamily[] = [family, 'COLOUR']
      render(<Tab projectId={1} selectedTargets={selection} families={families} />)
      expect(document.querySelector(`[data-palette-band="${family}"]`)).not.toBeNull()
      expect(document.querySelector(`[data-effects-band="${family}"]`)).not.toBeNull()
      // No *Into Warm wash* line: the tab writes Local whatever the grid is pointed at.
      expect(document.querySelector('[data-palette-band-into]')).toBeNull()
      await act(async () => {
        fireEvent.click(screen.getByText(chip))
      })
      expect(toggle).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: 1, templateId, targets: BUSK_PAIR_TARGETS, families }),
      )
    })

    it(`${name}: + FX… opens the effects arm on the busk pair, writing Local`, () => {
      render(<Tab projectId={1} selectedTargets={selection} families={null} />)
      fireEvent.click(screen.getByRole('button', { name: 'FX…' }))
      expect(picker).toHaveBeenLastCalledWith(
        expect.objectContaining({ library: 'effects', family, scope: 'programmer', pair: { targets: BUSK_PAIR_TARGETS, families: null } }),
      )
    })

    it(`${name}: Save as palette… records the busk targets under the family`, () => {
      render(<Tab projectId={1} selectedTargets={selection} families={null} />)
      fireEvent.click(screen.getByRole('button', { name: 'Save as palette…' }))
      const sheet = screen.getByTestId('new-template')
      expect(sheet).toHaveAttribute('data-families', family)
      expect(sheet).toHaveAttribute('data-targets', JSON.stringify(BUSK_PAIR_TARGETS))
    })
  }
})

describe('the editors write Local', () => {
  it('Level: a typed level is one group entry for a group whose members carry a dimmer, and each cell’s for a bar', () => {
    render(<LevelSheet projectId={1} selectedTargets={selection} families={null} />)
    const field = screen.getByLabelText('Level percent')
    fireEvent.change(field, { target: { value: '100' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    const fade = expect.any(Number)
    expect(programmer.set).toHaveBeenCalledWith('group', 'Spots', 'dimmer', '255', fade)
    expect(programmer.set).toHaveBeenCalledWith('fixture', 'bar-1.head-1', 'dimmer', '255', fade, undefined)
    expect(programmer.set).toHaveBeenCalledWith('fixture', 'bar-1.head-2', 'dimmer', '255', fade, undefined)
    expect(programmer.set).toHaveBeenCalledTimes(3)
  })

  it('Position: the XY pad writes one setPosition into Local, a group whose heads land alike as one group entry', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      width: 100,
      height: 100,
      right: 100,
      bottom: 100,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    })
    const spotOnly = new Map<string, BuskingTarget>([['group:Spots', spots]])
    render(<PositionSheet projectId={1} selectedTargets={spotOnly} families={null} />)
    const pad = screen.getByTestId('position-pad')
    fireEvent.pointerDown(pad, { clientX: 50, clientY: 25, pointerId: 1 })
    // Drags write at once: no fade.
    expect(programmer.setPosition).toHaveBeenCalledWith('group', 'Spots', 128, 64, undefined)
    expect(programmer.set).not.toHaveBeenCalled()
  })

  it('Level: a member reached before its group is written once, the group falling back to member entries', () => {
    const order = new Map<string, BuskingTarget>([
      ['fixture:spot-1', { type: 'fixture', key: 'spot-1', fixture: SPOT_1 }],
      ['group:Spots', spots],
    ])
    render(<LevelSheet projectId={1} selectedTargets={order} families={null} />)
    const field = screen.getByLabelText('Level percent')
    fireEvent.change(field, { target: { value: '100' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    const fade = expect.any(Number)
    expect(programmer.set).toHaveBeenCalledWith('fixture', 'spot-1', 'dimmer', '255', fade, undefined)
    expect(programmer.set).toHaveBeenCalledWith('fixture', 'spot-2', 'dimmer', '255', fade, 'Spots')
    expect(programmer.set).toHaveBeenCalledTimes(2)
  })

  it('says how many selected heads it leaves out for having no such property', () => {
    const DARK = makeFixture('par-9', [], { name: 'Par 9' })
    const mixed = new Map<string, BuskingTarget>([
      ['group:Spots', spots],
      ['fixture:par-9', { type: 'fixture', key: 'par-9', fixture: DARK }],
    ])
    render(<LevelSheet projectId={1} selectedTargets={mixed} families={null} />)
    expect(document.querySelector('[data-family-sheet-left-out]')).toHaveTextContent('1 selected head has no dimmer and is left out.')
    cleanup()
    render(<LevelSheet projectId={1} selectedTargets={new Map([['group:Spots', spots]])} families={null} />)
    expect(document.querySelector('[data-family-sheet-left-out]')).toBeNull()
  })

  it('Position: says so when nothing selected can move', () => {
    render(<PositionSheet projectId={1} selectedTargets={new Map([['fixture:bar-1', bar]])} families={null} />)
    expect(screen.queryByTestId('position-pad')).toBeNull()
    expect(document.querySelector('[data-family-sheet-empty]')).toHaveTextContent('No selected head has a position.')
  })
})

describe('the Beam tab acts on one Beam attribute', () => {
  it('offers the Beam properties the selection carries, and its band and level follow the one picked', () => {
    const both = new Map<string, BuskingTarget>([
      ['group:Spots', spots],
      ['fixture:spot-1', { type: 'fixture', key: 'spot-1', fixture: SPOT_1 }],
    ])
    render(<BeamSheet projectId={1} selectedTargets={both} families={null} />)
    const row = screen.getByRole('radiogroup', { name: 'Beam attribute' })
    expect(within(row).getAllByRole('radio').map((r) => r.textContent)).toEqual(['Zoom', 'Iris'])
    expect(within(row).getByRole('radio', { name: 'Zoom' })).toHaveAttribute('aria-checked', 'true')
    expect(chips()).toEqual(['Wide'])
    fireEvent.click(within(row).getByRole('radio', { name: 'Iris' }))
    expect(chips()).toEqual(['Open iris'])
    // Iris lives on Spot 1 alone: the group's other member has none, so it is Spot 1's own entry,
    // carrying the group — and Spot 1, selected beside it, is not written a second time.
    const field = screen.getByLabelText('Iris percent')
    fireEvent.change(field, { target: { value: '0' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(programmer.set).toHaveBeenCalledWith('fixture', 'spot-1', 'iris', '0', expect.any(Number), 'Spots')
    expect(programmer.set).toHaveBeenCalledTimes(1)
  })

  it('draws no property row over a selection with one Beam level', () => {
    render(<BeamSheet projectId={1} selectedTargets={new Map([['fixture:spot-2', { type: 'fixture', key: 'spot-2', fixture: SPOT_2 }]])} families={null} />)
    expect(screen.queryByRole('radiogroup', { name: 'Beam attribute' })).toBeNull()
    expect(screen.getByLabelText('Zoom percent')).toBeInTheDocument()
  })
})
