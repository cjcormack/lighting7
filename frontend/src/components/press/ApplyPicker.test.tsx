// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'
import type { PressPair } from '@/hooks/usePress'

/**
 * The one picker (apply-model plan §4.2): every library door's panel.
 *
 * What is pinned is what each door relies on — the sections and their membership, the offer filter
 * it shares with the strip and the bands (family, emitters, a Beam property), *Outside the mask*
 * dimmed with its reason and still pressable, that every press goes through `usePress` with the
 * door's own pair **and leaves the panel open**, that a Look can land on its own fixtures, and that
 * the three forms come from the cell editor's media queries. The press itself is `usePress`'s
 * (`usePress.test.tsx`).
 */
let library: TemplateSummary[] = []
let looks: LookSummary[] = []

const press = vi.fn(async () => 'on' as const)
const pairs: PressPair[] = []
/** A focused Look layer, where a test says so: `usePress` lands there, and the effects arm absorbs. */
const focus = vi.hoisted(() => ({ look: null as null | { projectId: number; lookId: number; lookName: string } }))
vi.mock('@/hooks/usePress', () => ({
  usePress: (_projectId: number, pair: PressPair) => {
    pairs.push(pair)
    return { press, into: focus.look != null ? { kind: 'look', lookName: focus.look.lookName } : { kind: 'programmer' } }
  },
  usePressLook: () => focus.look,
}))

vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: library }),
}))
const absorb = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve() })))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: looks }), useAbsorbLookEffectsMutation: () => [absorb] }))
const stopEffect = vi.hoisted(() => vi.fn())
vi.mock('@/components/fx/useEffectControls', () => ({ useStopEffect: () => stopEffect }))
const fxWire = vi.hoisted(() => ({ active: [] as unknown[] }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: fxWire.active }),
  useEffectLibraryQuery: () => ({ data: [] }),
}))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: [{ key: 'hex-1', name: 'Hex 1', groups: [], properties: [], elements: [] }], fixtureByKey: new Map() }),
}))
/** The effects arm's `FxPicker`, by the props it is handed; a press on it starts effect 77, as a tap would. */
const fxPicker = vi.hoisted(() => vi.fn())
vi.mock('@/components/fx/FxPicker', () => ({
  FxPicker: (props: { onCurrent: (c: unknown) => void }) => {
    fxPicker(props)
    return (
      <button type="button" data-fx-picker onClick={() => props.onCurrent({ effectId: 77, effectType: 'Pulse', propertyName: 'dimmer' })}>
        start
      </button>
    )
  },
}))
vi.mock('@/components/fx/FxEditor', () => ({
  FxEditor: ({ onStop }: { onStop?: () => void }) =>
    onStop == null ? null : (
      <button type="button" onClick={onStop}>
        stop it
      </button>
    ),
}))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: [] }) }))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: false, canEdit: true }) }))
vi.mock('@/store/hand', () => ({ handPickUp: vi.fn() }))

// The hold menu's *Edit palette…* navigates; nothing here follows it.
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => vi.fn(),
}))

// Counted, not stubbed: the real ordering still runs, but the picker must not call it while shut.
const recentTemplatesSpy = vi.hoisted(() => vi.fn())
vi.mock('@/lib/templateRecents', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/lib/templateRecents')>()
  return {
    ...real,
    recentTemplates: (...args: Parameters<typeof real.recentTemplates>) => {
      recentTemplatesSpy()
      return real.recentTemplates(...args)
    },
  }
})

// An effect pad's detail line names its live speed master, which is a subscription.
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterDisplay: () => undefined }))

const newSheetProps = vi.fn()
vi.mock('@/components/programmer/NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: (props: { open: boolean }) => {
    newSheetProps(props)
    return props.open ? <div data-testid="new-sheet" /> : null
  },
}))

/** The form the cell editor would choose. Swapped per test — the picker asks, it does not decide. */
const form = vi.hoisted(() => ({ current: 'popover' as 'popover' | 'bottom-sheet' | 'side-sheet' }))
vi.mock('@/components/editor/EditorSurface', () => ({
  useEditorForm: () => form.current,
}))

const { ApplyPicker } = await import('./ApplyPicker')

const HEX_1: TemplateTarget[] = [{ type: 'fixture', key: 'hex-1' }]

function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Amber Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    lastPressedAt: null,
    rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' }],
    effect: null,
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

function look(over: Partial<LookSummary> = {}): LookSummary {
  return {
    id: 21,
    uuid: 'l21',
    name: 'Warm wash',
    notes: null,
    families: ['COLOUR', 'INTENSITY'],
    rowCount: 4,
    effectCount: 0,
    targetCount: 4,
    hasDeferredEffects: false,
    preview: [],
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

const PRESSED = template({ id: 1, uuid: 'u1', name: 'Amber Key', lastPressedAt: '2026-09-14T10:00:00.000Z' })
const GENERIC = template({ id: 2, uuid: 'u2', name: 'Blue Wash' })
const PER_FIXTURE = template({
  id: 3,
  uuid: 'u3',
  name: 'Front Focus',
  family: 'POSITION',
  isGeneric: false,
  rows: [{ targetType: 'fixture', targetKey: 'hex-1', propertyName: 'position', value: 'deg:12,-8' }],
})
const EFFECT = template({
  id: 4,
  uuid: 'u4',
  name: 'Amber Breathe',
  kind: 'effect',
  rows: [],
  effect: {
    effectType: 'ColourPulse',
    category: 'colour',
    beatDivision: 0.5,
    blendMode: 'OVERRIDE',
    distribution: 'LINEAR',
    parameters: {},
    timingSource: 'BEAT',
  },
})
const WHITE_KEY = template({ id: 5, uuid: 'u5', name: 'White Key', requiredEmitters: ['white'] })
const FULL = template({
  id: 6,
  uuid: 'u6',
  name: 'Full',
  family: 'INTENSITY',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:100' }],
})
const WIDE = template({
  id: 7,
  uuid: 'u7',
  name: 'Wide',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'zoom', value: 'pct:100' }],
})
const SOFT = template({
  id: 8,
  uuid: 'u8',
  name: 'Soft edge',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'frost', value: 'pct:60' }],
})

function picker(over: Partial<React.ComponentProps<typeof ApplyPicker>> = {}) {
  const anchor = { current: document.createElement('button') }
  return (
    <ApplyPicker
      projectId={1}
      open
      onOpenChange={() => {}}
      anchorRef={anchor}
      library="palettes"
      pair={{ targets: HEX_1, families: null }}
      fit={{ families: ['COLOUR', 'POSITION'], emitters: [] }}
      {...over}
    />
  )
}

/** The panel, whichever of the three shapes it is in — all three are Radix dialogs. */
const panel = () => screen.getByRole('dialog')

/** A section's pads' names, by the section's key. */
function sectionPads(key: string): string[] {
  const section = panel().querySelector(`[data-picker-section="${key}"]`)
  return [...(section?.querySelectorAll('button:not([data-picker-bare-effects])') ?? [])].map(
    (pad) => pad.querySelector('span span:last-child')?.textContent ?? '',
  )
}

beforeEach(() => {
  press.mockClear()
  pairs.length = 0
  newSheetProps.mockClear()
  form.current = 'popover'
  recentTemplatesSpy.mockClear()
  library = [PRESSED, GENERIC, PER_FIXTURE, EFFECT, WHITE_KEY, FULL, WIDE, SOFT]
  looks = []
  focus.look = null
  fxPicker.mockClear()
  absorb.mockClear()
  stopEffect.mockClear()
  fxWire.active = []
})
afterEach(cleanup)

describe('ApplyPicker — palettes', () => {
  it('sorts what fits into Recent, All, Per fixture and Effects', () => {
    render(picker())
    expect(sectionPads('recent')).toEqual(['Amber Key'])
    // `All` is the generic **value** palettes: a per-fixture one holds a value per head and an
    // effect palette holds no rows at all, so each gets a section that says what it is.
    expect(sectionPads('all')).toEqual(['Amber Key', 'Blue Wash'])
    expect(sectionPads('per-fixture')).toEqual(['Front Focus'])
    expect(sectionPads('effects')).toEqual(['Amber Breathe'])
  })

  it('offers only what the heads can take: the family and every emitter a palette names', () => {
    render(picker())
    // No head has white, so White Key is not offered; Full and the Beam palettes are another family.
    const names = [...panel().querySelectorAll('[data-picker-palette]')].map((pad) => pad.getAttribute('data-picker-palette'))
    expect(names).not.toContain(String(WHITE_KEY.id))
    expect(names).not.toContain(String(FULL.id))
    cleanup()
    render(picker({ fit: { families: ['COLOUR'], emitters: ['white'] } }))
    expect(sectionPads('all')).toContain('White Key')
  })

  it('opened on one family — a band’s All … — offers that family, and a Beam property’s palettes alone', () => {
    render(picker({ family: 'BEAM', fit: { families: null, emitters: [], property: 'zoom' } }))
    // A zoom cell is offered the Beam palettes with a zoom row, not every Beam palette.
    expect(sectionPads('all')).toEqual(['Wide'])
    expect(panel().querySelector('[data-slot="badge"]')).toHaveTextContent('Beam')
  })

  it('shows what the mask leaves out, dimmed, with the reason, and still presses it', async () => {
    render(picker({ pair: { targets: HEX_1, families: ['POSITION'] } }))
    expect(sectionPads('all')).toEqual([])
    expect(sectionPads('per-fixture')).toEqual(['Front Focus'])
    expect(sectionPads('outside')).toEqual(['Amber Key', 'Blue Wash', 'Amber Breathe'])
    const outside = panel().querySelector('[data-picker-section="outside"] [data-picker-palette="2"]')!
    expect(outside.className).toContain('opacity-55')
    expect(outside).toHaveTextContent('Colour — the selection is Position')
    // The desk tests the mask on the on arm only, so a lit one outside it still comes off.
    await act(async () => {
      fireEvent.click(outside)
    })
    expect(press).toHaveBeenCalledWith({ kind: 'template', template: GENERIC }, 'press')
  })

  it('filters every section from the one search field', () => {
    render(picker())
    fireEvent.change(within(panel()).getByLabelText('Search palettes'), { target: { value: 'amber' } })
    expect(sectionPads('recent')).toEqual(['Amber Key'])
    expect(sectionPads('all')).toEqual(['Amber Key'])
    expect(sectionPads('effects')).toEqual(['Amber Breathe'])
    expect(sectionPads('per-fixture')).toEqual([])
  })

  it('says how much of the library fits the selection', () => {
    render(picker())
    expect(within(panel()).getByText('4 of 8 fit the selection')).toBeInTheDocument()
  })

  it('presses the door’s pair on a click and stays open, so three colours can be auditioned in a row', async () => {
    const pair = { targets: HEX_1, families: ['COLOUR' as const] }
    render(picker({ pair }))
    await act(async () => {
      fireEvent.click(panel().querySelector('[data-picker-section="all"] [data-picker-palette="2"]')!)
    })
    expect(press).toHaveBeenCalledWith({ kind: 'template', template: GENERIC }, 'press')
    expect(pairs.at(-1)).toBe(pair)
    expect(panel()).toBeInTheDocument()
  })

  it('⌥click asks for the palette’s values instead', async () => {
    render(picker())
    await act(async () => {
      fireEvent.click(panel().querySelector('[data-picker-section="all"] [data-picker-palette="2"]')!, { altKey: true })
    })
    expect(press).toHaveBeenCalledWith({ kind: 'template', template: GENERIC }, 'asValues')
  })

  it('offers the family segments where the heads take more than one', () => {
    render(picker())
    fireEvent.click(within(panel()).getByText('Position'))
    expect(sectionPads('per-fixture')).toEqual(['Front Focus'])
    expect(sectionPads('effects')).toEqual([])
  })

  it('is a sheet on a phone, a popover at a desk, and a side sheet at a desk with no anchor', () => {
    form.current = 'bottom-sheet'
    const { unmount } = render(picker())
    expect(panel()).toHaveAttribute('data-slot', 'sheet-content')
    // The gesture hint is the popover's alone: ⌥ is a key a phone has not got.
    expect(within(panel()).queryByText(/⌥click/)).not.toBeInTheDocument()
    unmount()

    form.current = 'popover'
    const second = render(picker())
    expect(panel()).toHaveAttribute('data-slot', 'popover-content')
    expect(within(panel()).getByText(/⌥click/)).toBeInTheDocument()
    second.unmount()

    // The rail's doors: a footer, a strip and a handle, none of which a popover could hang from.
    render(picker({ anchorRef: undefined }))
    expect(panel()).toHaveAttribute('data-slot', 'sheet-content')
  })

  it('computes nothing while it is shut, however often its inputs change identity', () => {
    const { rerender } = render(picker({ open: false }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    for (let frame = 0; frame < 5; frame++) {
      rerender(picker({ open: false, pair: { targets: [...HEX_1], families: null } }))
    }
    expect(recentTemplatesSpy).not.toHaveBeenCalled()

    rerender(picker({ open: true }))
    expect(recentTemplatesSpy).toHaveBeenCalled()
    expect(sectionPads('recent')).toEqual(['Amber Key'])
  })

  it('opens the menu on a hold, on any pointer, and never presses', () => {
    vi.useFakeTimers()
    try {
      render(picker())
      const pad = panel().querySelector('[data-picker-section="recent"] [data-picker-palette="1"]')!
      fireEvent.pointerDown(pad, { pointerType: 'mouse' })
      act(() => {
        vi.advanceTimersByTime(600)
      })
      fireEvent.pointerUp(pad)
      fireEvent.click(pad)
      expect(press).not.toHaveBeenCalled()
      expect(screen.getByRole('menu', { name: 'Amber Key' })).toBeInTheDocument()
      expect(screen.getByRole('menuitem', { name: 'Put on' })).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('drops a family chip once the offer narrows under it, rather than filtering by a chip nobody can see', () => {
    const { rerender } = render(picker())
    fireEvent.click(within(panel()).getByText('Position'))
    expect(sectionPads('all')).toEqual([])
    // The selection moves to colour-only heads with the panel open: the chips go, and so does their filter.
    rerender(picker({ fit: { families: ['COLOUR'], emitters: [] } }))
    expect(sectionPads('all')).toEqual(['Amber Key', 'Blue Wash'])
  })

  it('draws no New from selection for a band’s picker, whose editor would close the sheet with it', () => {
    render(picker({ family: 'COLOUR', offerNew: false }))
    expect(within(panel()).queryByText('New from selection')).not.toBeInTheDocument()
  })

  it('hands New from selection the same targets a press would use', () => {
    render(picker({ family: 'COLOUR' }))
    fireEvent.click(within(panel()).getByText('New from selection'))
    expect(newSheetProps).toHaveBeenLastCalledWith(expect.objectContaining({ open: true, targets: HEX_1, families: ['COLOUR'] }))
  })
})

describe('ApplyPicker — Looks', () => {
  const OWN = look({ id: 21, name: 'Warm wash' })
  const DEFERRED = look({ id: 22, uuid: 'l22', name: 'Chase', families: ['INTENSITY'], effectCount: 1, hasDeferredEffects: true })
  const MOVES = look({ id: 23, uuid: 'l23', name: 'Sweep', families: ['POSITION'] })

  beforeEach(() => {
    looks = [OWN, DEFERRED, MOVES]
  })

  it('presses a Look onto the selection, with its gestures', async () => {
    const pair = { targets: HEX_1, families: null }
    render(picker({ library: 'looks', pair }))
    expect(sectionPads('all')).toEqual(['Warm wash', 'Chase', 'Sweep'])
    await act(async () => {
      fireEvent.click(panel().querySelector('[data-picker-look="21"]')!)
    })
    expect(press).toHaveBeenCalledWith({ kind: 'look', look: OWN }, 'press')
    expect(pairs.at(-1)).toBe(pair)
  })

  it('puts a Look outside the mask only when none of its families is inside it', () => {
    render(picker({ library: 'looks', pair: { targets: HEX_1, families: ['COLOUR'] } }))
    // Warm wash lands masked to its colour rows; Chase and Sweep have nothing in Colour.
    expect(sectionPads('all')).toEqual(['Warm wash'])
    expect(sectionPads('outside')).toEqual(['Chase', 'Sweep'])
  })

  it('lands on its own fixtures with nothing selected, and a Look whose effects need targets is out', async () => {
    render(picker({ library: 'looks', pair: { targets: [], families: null } }))
    expect(sectionPads('all')).toEqual(['Warm wash', 'Sweep'])
    expect(sectionPads('outside')).toEqual(['Chase'])
    await act(async () => {
      fireEvent.click(panel().querySelector('[data-picker-look="23"]')!)
    })
    expect(pairs.at(-1)).toEqual({ targets: [], families: null })
  })

  it('offers its own fixtures beside the selection, which presses with no targets', () => {
    render(picker({ library: 'looks', pair: { targets: HEX_1, families: null } }))
    fireEvent.click(within(panel()).getByText('On its own fixtures'))
    expect(pairs.at(-1)).toEqual({ targets: [], families: null })
  })
})

/**
 * The effects arm (apply-model plan §8 session 6): what ⌘K *Apply FX*, the rail's *+ Effect* and a
 * band's *+ Effect …* open — `FxPicker` on the bare effect press over the door's pair, above the
 * effect palettes; under a focused Look the effect starts on `/fx/add` and is absorbed on close.
 */
describe('ApplyPicker — effects', () => {
  const MASKED: PressPair = { targets: HEX_1, families: ['COLOUR'] }

  it('hands FxPicker the bare effect press with the door’s mask, on the cell’s property', () => {
    render(picker({ library: 'effects', pair: MASKED, family: 'COLOUR', property: 'uv' }))
    expect(fxPicker).toHaveBeenLastCalledWith(
      expect.objectContaining({
        press: { projectId: 1, families: ['COLOUR'] },
        initialFamily: 'COLOUR',
        preferredProperty: 'uv',
        target: expect.objectContaining({ type: 'fixture' }),
      }),
    )
    expect(sectionPads('effect-palettes')).toEqual(['Amber Breathe'])
  })

  it('sizes the effects to their list, where the palettes hold their height', () => {
    render(picker({ library: 'effects', pair: MASKED }))
    expect(panel().className).not.toContain('h-[540px]')
    cleanup()
    render(picker())
    expect(panel().className).toContain('h-[540px]')
  })

  it('reaches the bare effects from the palettes’ Effects section, and back', () => {
    render(picker())
    fireEvent.click(panel().querySelector('[data-picker-bare-effects]')!)
    expect(panel().querySelector('[data-fx-picker]')).not.toBeNull()
    fireEvent.click(within(panel()).getByRole('button', { name: 'Palettes' }))
    expect(panel().querySelector('[data-picker-section="all"]')).not.toBeNull()
  })

  it('in a focused Look starts on /fx/add and absorbs the effect it ends on, once, when it closes', () => {
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    const { rerender } = render(picker({ library: 'effects', pair: MASKED }))
    expect(fxPicker).toHaveBeenLastCalledWith(expect.objectContaining({ press: undefined }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    rerender(picker({ library: 'effects', pair: MASKED, open: false }))
    expect(absorb).toHaveBeenCalledTimes(1)
    expect(absorb).toHaveBeenCalledWith({ projectId: 1, lookId: 9, effectIds: [77] })
    cleanup()
    expect(absorb).toHaveBeenCalledTimes(1)
  })

  it('stops the auditioned effect from its editor’s ×, and then absorbs nothing', () => {
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    fxWire.active = [{ id: 77, effectType: 'Pulse', propertyName: 'dimmer' }]
    const { rerender } = render(picker({ library: 'effects', pair: MASKED }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    fxPicker.mock.lastCall![0].onEdit()
    rerender(picker({ library: 'effects', pair: MASKED }))
    fireEvent.click(within(panel()).getByRole('button', { name: 'stop it' }))
    expect(stopEffect).toHaveBeenCalledWith(expect.objectContaining({ id: 77 }))
    rerender(picker({ library: 'effects', pair: MASKED, open: false }))
    expect(absorb).not.toHaveBeenCalled()
  })

  it('absorbs nothing on the programmer, or when nothing was started', () => {
    const { rerender, unmount } = render(picker({ library: 'effects', pair: MASKED }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    rerender(picker({ library: 'effects', pair: MASKED, open: false }))
    unmount()
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    render(picker({ library: 'effects', pair: MASKED })).unmount()
    expect(absorb).not.toHaveBeenCalled()
  })
})
