// @vitest-environment jsdom
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LookSummary } from '@/api/looksApi'
import type { PaletteSummary, PaletteTarget } from '@/api/palettesApi'
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
let library: PaletteSummary[] = []
let looks: LookSummary[] = []
let applied: unknown[] = []

const press = vi.fn(async () => 'on' as const)
const pairs: PressPair[] = []
/** A focused Look layer, where a test says so: `usePress` lands there, and the effects arm absorbs. */
const focus = vi.hoisted(() => ({
  look: null as null | { projectId: number; lookId: number; lookName: string },
  /** The cue editor, where a test says so: presses write the cue (`PressCue`). */
  cue: null as null | { cueLabel: string; layered: ReadonlySet<string>; pickFx: (effect: unknown, replacing: unknown) => Promise<boolean> },
}))
vi.mock('@/hooks/usePress', () => ({
  usePress: (_projectId: number, pair: PressPair) => {
    pairs.push(pair)
    return {
      press,
      into:
        focus.cue != null
          ? { kind: 'cue', cueLabel: focus.cue.cueLabel }
          : focus.look != null
            ? { kind: 'look', lookName: focus.look.lookName }
            : { kind: 'programmer' },
    }
  },
  usePressLook: () => focus.look,
  usePressInCue: () => focus.cue,
}))

vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({ data: library }),
}))
const absorb = vi.hoisted(() => vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve() })))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: looks }), useAbsorbLookFxMutation: () => [absorb] }))
const stopFx = vi.hoisted(() => vi.fn())
vi.mock('@/components/fx/useFxControls', () => ({ useStopFx: () => stopFx }))
const fxWire = vi.hoisted(() => ({ active: [] as unknown[] }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveFxQuery: () => ({ data: fxWire.active }),
  useFxLibraryQuery: () => ({ data: [] }),
}))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [
      { key: 'hex-1', name: 'Hex 1', groups: [], attributes: [], elements: [] },
      { key: 'hex-2', name: 'Hex 2', groups: [], attributes: [], elements: [] },
    ],
    fixtureByKey: new Map(),
  }),
}))
/**
 * The effects arm's `FxPicker`, by the props it is handed; a press on it starts effect 77 on dimmer,
 * asking `stackOn` as a fresh start does.
 */
const fxPicker = vi.hoisted(() => vi.fn())
vi.mock('@/components/fx/FxPicker', () => ({
  FxPicker: (props: {
    onCurrent: (c: unknown) => void
    underFamilies?: React.ReactNode
    press?: { stackOn?: (attribute: string) => boolean }
  }) => {
    fxPicker(props)
    const start = () => {
      const stacked = props.press?.stackOn?.('dimmer') === true
      props.onCurrent({ fxId: 77, fxType: 'Pulse', attribute: 'dimmer', ...(stacked ? { stacked } : {}) })
    }
    return (
      <>
        {props.underFamilies}
        <button type="button" data-fx-picker onClick={start}>
          start
        </button>
      </>
    )
  },
}))
vi.mock('@/components/fx/FxEditor', () => ({
  FxEditor: ({ onStop, onDone }: { onStop?: () => void; onDone: () => void }) => (
    <>
      <button type="button" onClick={onDone}>
        editor done
      </button>
      {onStop != null && (
        <button type="button" onClick={onStop}>
          stop it
        </button>
      )}
    </>
  ),
}))
/**
 * What `useLocalFxHolder` answers: the attributes whose family already runs a Local FX on the
 * selection, and each `except` it was asked with — the session's own FX.
 */
const holders = vi.hoisted(() => ({ attributes: new Set<string>(), excepts: [] as (number | null | undefined)[] }))
vi.mock('@/components/fx/ReplaceStack', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/fx/ReplaceStack')>()),
  useLocalFxHolder: () => (attribute: string, except?: number | null) => {
    holders.excepts.push(except)
    return holders.attributes.has(attribute) ? `${attribute} FX on all 2 heads already` : null
  },
}))
/** The programmer page's scope, where a test says so: there the FX arm asks *Replace · Stack*. */
const programmerScope = vi.hoisted(() => ({ value: null as object | null }))
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => programmerScope.value }))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: applied }) }))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: false, canEdit: true }) }))
vi.mock('@/store/hand', () => ({ handPickUp: vi.fn() }))

// The hold menu's *Edit palette…* navigates; nothing here follows it.
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => vi.fn(),
}))

// Counted, not stubbed: the real ordering still runs, but the picker must not call it while shut.
const recentPalettesSpy = vi.hoisted(() => vi.fn())
vi.mock('@/lib/paletteRecents', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/lib/paletteRecents')>()
  return {
    ...real,
    recentPalettes: (...args: Parameters<typeof real.recentPalettes>) => {
      recentPalettesSpy()
      return real.recentPalettes(...args)
    },
  }
})

// An effect pad's detail line names its live speed master, which is a subscription.
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterDisplay: () => undefined }))

const newSheetProps = vi.fn()
vi.mock('@/components/programmer/NewPaletteFromSelectionSheet', () => ({
  NewPaletteFromSelectionSheet: (props: { open: boolean }) => {
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

const HEX_1: PaletteTarget[] = [{ type: 'fixture', key: 'hex-1' }]

function template(over: Partial<PaletteSummary> = {}): PaletteSummary {
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
    rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#FF9D4A;policy=extract' }],
    fx: null,
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
    fxCount: 0,
    targetCount: 4,
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
  rows: [{ targetType: 'fixture', targetKey: 'hex-1', attribute: 'position', value: 'deg:12,-8' }],
})
const EFFECT = template({
  id: 4,
  uuid: 'u4',
  name: 'Amber Breathe',
  kind: 'fx',
  rows: [],
  fx: {
    fxType: 'ColourWave',
    family: 'colour',
    cycle: 0.5,
    blendMode: 'OVERRIDE',
    order: 'NORMAL',
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
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'dimmer', value: 'pct:100' }],
})
const WIDE = template({
  id: 7,
  uuid: 'u7',
  name: 'Wide',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'zoom', value: 'pct:100' }],
})
const SOFT = template({
  id: 8,
  uuid: 'u8',
  name: 'Soft edge',
  family: 'BEAM',
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'frost', value: 'pct:60' }],
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
  recentPalettesSpy.mockClear()
  library = [PRESSED, GENERIC, PER_FIXTURE, EFFECT, WHITE_KEY, FULL, WIDE, SOFT]
  looks = []
  applied = []
  focus.look = null
  focus.cue = null
  fxPicker.mockClear()
  absorb.mockClear()
  stopFx.mockClear()
  fxWire.active = []
  holders.attributes = new Set()
  holders.excepts = []
  programmerScope.value = null
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
    expect(sectionPads('fx')).toEqual(['Amber Breathe'])
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
    render(picker({ family: 'BEAM', fit: { families: null, emitters: [], attribute: 'zoom' } }))
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
    expect(press).toHaveBeenCalledWith({ kind: 'palette', palette: GENERIC }, 'press')
  })

  it('filters every section from the one search field', () => {
    render(picker())
    fireEvent.change(within(panel()).getByLabelText('Search palettes'), { target: { value: 'amber' } })
    expect(sectionPads('recent')).toEqual(['Amber Key'])
    expect(sectionPads('all')).toEqual(['Amber Key'])
    expect(sectionPads('fx')).toEqual(['Amber Breathe'])
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
    expect(press).toHaveBeenCalledWith({ kind: 'palette', palette: GENERIC }, 'press')
    expect(pairs.at(-1)).toBe(pair)
    expect(panel()).toBeInTheDocument()
  })

  it('⌥click asks for the palette’s values instead', async () => {
    render(picker())
    await act(async () => {
      fireEvent.click(panel().querySelector('[data-picker-section="all"] [data-picker-palette="2"]')!, { altKey: true })
    })
    expect(press).toHaveBeenCalledWith({ kind: 'palette', palette: GENERIC }, 'asValues')
  })

  it('offers the family segments where the heads take more than one', () => {
    render(picker())
    fireEvent.click(within(panel()).getByText('Position'))
    expect(sectionPads('per-fixture')).toEqual(['Front Focus'])
    expect(sectionPads('fx')).toEqual([])
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

    // A door with no anchor, as the Stack table's *+ Look or palette* is.
    render(picker({ anchorRef: undefined }))
    expect(panel()).toHaveAttribute('data-slot', 'sheet-content')
  })

  it('takes the focus on open, and Escape gives it back to the door it hangs from (plan §4)', async () => {
    const door = document.createElement('button')
    document.body.appendChild(door)
    door.focus()
    function Hosted({ library }: { library: 'palettes' | 'fx' }) {
      const [open, setOpen] = useState(true)
      return picker({ library, open, onOpenChange: setOpen, anchorRef: { current: door }, pair: { targets: HEX_1, families: ['COLOUR'] } })
    }
    render(<Hosted library="palettes" />)
    expect(document.activeElement).toBe(within(panel()).getByRole('textbox'))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(door)
    cleanup()
    document.body.appendChild(door)
    door.focus()
    render(<Hosted library="fx" />)
    // The FX arm has no search: it takes nothing the stub picker does not draw, and still hands back.
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(door)
    door.remove()
  })

  it('leaves the focus where the operator put it when a press elsewhere closes it', async () => {
    const door = document.createElement('button')
    const elsewhere = document.createElement('input')
    document.body.append(door, elsewhere)
    door.focus()
    function Hosted() {
      const [open, setOpen] = useState(true)
      return picker({ open, onOpenChange: setOpen, anchorRef: { current: door }, pair: { targets: HEX_1, families: ['COLOUR'] } })
    }
    render(<Hosted />)
    fireEvent.pointerDown(elsewhere)
    elsewhere.focus()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(document.activeElement).toBe(elsewhere)
    door.remove()
    elsewhere.remove()
  })

  it('computes nothing while it is shut, however often its inputs change identity', () => {
    const { rerender } = render(picker({ open: false }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    for (let frame = 0; frame < 5; frame++) {
      rerender(picker({ open: false, pair: { targets: [...HEX_1], families: null } }))
    }
    expect(recentPalettesSpy).not.toHaveBeenCalled()

    rerender(picker({ open: true }))
    expect(recentPalettesSpy).toHaveBeenCalled()
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
  const CHASE = look({ id: 22, uuid: 'l22', name: 'Chase', families: ['INTENSITY'], fxCount: 1 })
  const MOVES = look({ id: 23, uuid: 'l23', name: 'Sweep', families: ['POSITION'] })

  beforeEach(() => {
    looks = [OWN, CHASE, MOVES]
  })

  it('presses a Look with its gestures, whatever is selected', async () => {
    const pair = { targets: HEX_1, families: null }
    render(picker({ library: 'looks', pair }))
    expect(sectionPads('all')).toEqual(['Warm wash', 'Chase', 'Sweep'])
    await act(async () => {
      fireEvent.click(panel().querySelector('[data-picker-look="21"]')!)
    })
    expect(press).toHaveBeenCalledWith({ kind: 'look', look: OWN }, 'press')
  })

  it('draws every Look in one list, whatever the mask, with no Own fixtures switch', () => {
    // A Look lands whole (absolute-looks plan D1, D3): the selection's mask says nothing about it.
    render(picker({ library: 'looks', pair: { targets: HEX_1, families: ['COLOUR'] } }))
    expect(sectionPads('all')).toEqual(['Warm wash', 'Chase', 'Sweep'])
    expect(panel().querySelector('[data-picker-section="outside"]')).toBeNull()
    expect(within(panel()).queryByText('On its own fixtures')).not.toBeInTheDocument()
  })

  it('rings a Look while a layer of it is on, whatever is selected', () => {
    // The desk's entry for a Look names no targets (P4): its presence is the ring.
    applied = [{ source: { kind: 'LOOK', id: 23, uuid: 'l23', name: 'Sweep' }, targets: [] }]
    render(picker({ library: 'looks', pair: { targets: [], families: null } }))
    expect(panel().querySelector('[data-picker-look="23"]')!.className).toContain('ring-1')
    expect(panel().querySelector('[data-picker-look="21"]')!.className).not.toContain('ring-1')
  })
})

describe('ApplyPicker — a lead', () => {
  it('draws the door’s lead above the Looks — the phone’s *Include a cue or Look…*', () => {
    looks = [look()]
    render(picker({ library: 'looks', lead: <button type="button">Include a cue or Look…</button> }))
    const lead = within(panel()).getByRole('button', { name: 'Include a cue or Look…' })
    const search = within(panel()).getByRole('textbox', { name: 'Search Looks' })
    expect(lead.compareDocumentPosition(search) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

/**
 * The effects arm (apply-model plan §8 session 6): what ⌘K *Apply FX*, the FX table's *+ Effect* and a
 * band's *+ Effect …* open — `FxPicker` on the bare effect press over the door's pair, above the
 * effect palettes; under a focused Look the effect starts on `/fx/add` and is absorbed on close.
 */
describe('ApplyPicker — effects', () => {
  const MASKED: PressPair = { targets: HEX_1, families: ['COLOUR'] }

  it('hands FxPicker the bare effect press with the door’s mask, on the cell’s property', () => {
    render(picker({ library: 'fx', pair: MASKED, family: 'COLOUR', attribute: 'uv' }))
    expect(fxPicker).toHaveBeenLastCalledWith(
      expect.objectContaining({
        press: { projectId: 1, families: ['COLOUR'] },
        initialFamily: 'COLOUR',
        preferredAttribute: 'uv',
        target: expect.objectContaining({ type: 'fixture' }),
      }),
    )
    expect(sectionPads('effect-palettes')).toEqual(['Amber Breathe'])
  })

  it('sizes the effects to their list, where the palettes hold their height', () => {
    render(picker({ library: 'fx', pair: MASKED }))
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
    const { rerender } = render(picker({ library: 'fx', pair: MASKED }))
    expect(fxPicker).toHaveBeenLastCalledWith(expect.objectContaining({ press: undefined }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    rerender(picker({ library: 'fx', pair: MASKED, open: false }))
    expect(absorb).toHaveBeenCalledTimes(1)
    expect(absorb).toHaveBeenCalledWith({ projectId: 1, lookId: 9, fxIds: [77] })
    cleanup()
    expect(absorb).toHaveBeenCalledTimes(1)
  })

  it('stops the auditioned effect from its editor’s ×, and then absorbs nothing', () => {
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    fxWire.active = [{ id: 77, fxType: 'Pulse', attribute: 'dimmer' }]
    const { rerender } = render(picker({ library: 'fx', pair: MASKED }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    fxPicker.mock.lastCall![0].onEdit()
    rerender(picker({ library: 'fx', pair: MASKED }))
    fireEvent.click(within(panel()).getByRole('button', { name: 'stop it' }))
    expect(stopFx).toHaveBeenCalledWith(expect.objectContaining({ id: 77 }))
    rerender(picker({ library: 'fx', pair: MASKED, open: false }))
    expect(absorb).not.toHaveBeenCalled()
  })

  it('in the cue editor stores the bare effect in draft — no press, no Edit — and a second tap swaps it', () => {
    const pickFx = vi.fn((_effect: unknown, _replacing: unknown) => Promise.resolve(true))
    focus.cue = { cueLabel: 'Q4', layered: new Set(), pickFx }
    const { rerender } = render(picker({ library: 'fx', pair: MASKED }))
    expect(panel().textContent).toContain('stored in Q4')
    const first = fxPicker.mock.lastCall![0]
    expect(first.press).toBeUndefined()
    expect(first.onEdit).toBeUndefined()
    const pick = { fxType: 'Pulse', family: 'intensity', attribute: 'dimmer', cycle: 4, blendMode: 'OVERRIDE', parameters: {} }
    act(() => first.draft.onPick(pick))
    expect(pickFx).toHaveBeenLastCalledWith(expect.objectContaining({ targetType: 'fixture', targetKey: 'hex-1', fxType: 'Pulse' }), null)
    const second = fxPicker.mock.lastCall![0]
    expect(second.draft.current).toEqual({ fxType: 'Pulse', attribute: 'dimmer' })
    act(() => second.draft.onPick({ ...pick, fxType: 'SineWave' }))
    expect(pickFx).toHaveBeenLastCalledWith(
      expect.objectContaining({ fxType: 'SineWave' }),
      expect.objectContaining({ fxType: 'Pulse', targetKey: 'hex-1' }),
    )
    expect(absorb).not.toHaveBeenCalled()

    // A tap over another selection adds afresh rather than swapping the effect on the first.
    rerender(picker({ library: 'fx', pair: { ...MASKED, targets: [{ type: 'fixture', key: 'hex-2' }] } }))
    expect(fxPicker.mock.lastCall![0].draft.current).toBeNull()
    act(() => fxPicker.mock.lastCall![0].draft.onPick(pick))
    expect(pickFx).toHaveBeenLastCalledWith(expect.objectContaining({ targetKey: 'hex-2' }), null)
  })

  it('in the cue editor, an append that did not land is not swapped by the next tap', async () => {
    const pickFx = vi.fn((_effect: unknown, _replacing: unknown) => Promise.resolve(false))
    focus.cue = { cueLabel: 'Q4', layered: new Set(), pickFx }
    render(picker({ library: 'fx', pair: MASKED }))
    const pick = { fxType: 'Pulse', family: 'intensity', attribute: 'dimmer', cycle: 4, blendMode: 'OVERRIDE', parameters: {} }
    await act(async () => fxPicker.mock.lastCall![0].draft.onPick(pick))
    expect(fxPicker.mock.lastCall![0].draft.current).toBeNull()
    await act(async () => fxPicker.mock.lastCall![0].draft.onPick({ ...pick, fxType: 'SineWave' }))
    expect(pickFx).toHaveBeenLastCalledWith(expect.objectContaining({ fxType: 'SineWave' }), null)
  })

  it('asks Replace · Stack on the programmer page where a tap would land on a family already running a Local FX', () => {
    programmerScope.value = { kind: 'local' }
    holders.attributes = new Set(['uv'])
    render(picker({ library: 'fx', pair: MASKED }))
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
    act(() => fxPicker.mock.lastCall![0].onLanding(['dimmer']))
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
    // An Intensity FX landing on uv is the Colour Local FX's business, which the desk would replace.
    act(() => fxPicker.mock.lastCall![0].onLanding(['dimmer', 'uv']))
    expect(within(panel()).getByText('uv FX on all 2 heads already')).toBeTruthy()
    expect(within(panel()).getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    const stackOn = () => fxPicker.mock.lastCall![0].press.stackOn as (attribute: string) => boolean
    expect(stackOn()('uv')).toBe(false)
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Stack' }))
    // Stack where the landing family holds one, never where it does not.
    expect(stackOn()('uv')).toBe(true)
    expect(stackOn()('dimmer')).toBe(false)
  })

  // `FxPicker` reports only where a tap would start afresh, and keeps a swap as its FX started; the
  // arm asks each fresh start of its own landing, the session's FX (77, on dimmer) left out.
  const stackOn = (attribute: string) => (fxPicker.mock.lastCall![0].press.stackOn as (a: string) => boolean)(attribute)
  const landing = (attributes: string[]) => act(() => fxPicker.mock.lastCall![0].onLanding(attributes))
  const start = () => fireEvent.click(panel().querySelector('[data-fx-picker]')!)

  it('asks a fresh start of its own landing after a stacked start, and nothing where it holds nothing', () => {
    programmerScope.value = { kind: 'local' }
    holders.attributes = new Set(['dimmer', 'uv'])
    render(picker({ library: 'fx', pair: MASKED }))
    landing(['dimmer'])
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Stack' }))
    start()
    expect(fxPicker.mock.lastCall![0].current).toMatchObject({ fxId: 77, stacked: true })
    // Another family whose Local FX the desk would replace: asked afresh, on Replace.
    landing(['uv'])
    expect(within(panel()).getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(stackOn('uv')).toBe(false)
    // Where no Local FX runs: no question, and no stack.
    landing(['rgbColour'])
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
    expect(stackOn('rgbColour')).toBe(false)
  })

  it('asks a fresh start onto a held landing, on Replace, after a replacing start', () => {
    programmerScope.value = { kind: 'local' }
    holders.attributes = new Set(['uv'])
    render(picker({ library: 'fx', pair: MASKED }))
    landing(['dimmer'])
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
    start()
    landing(['uv'])
    expect(within(panel()).getByText('uv FX on all 2 heads already')).toBeTruthy()
    expect(within(panel()).getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(stackOn('uv')).toBe(false)
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Stack' }))
    expect(stackOn('uv')).toBe(true)
  })

  it('asks again, on Replace and without the session’s FX, once that FX has stopped elsewhere', () => {
    programmerScope.value = { kind: 'local' }
    holders.attributes = new Set(['dimmer'])
    render(picker({ library: 'fx', pair: MASKED }))
    landing(['dimmer'])
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Stack' }))
    start()
    // While 77 runs a dimmer tap swaps it, so nothing lands afresh.
    landing([])
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
    // Released elsewhere: the next tap starts afresh on dimmer, asked of the FX that is still there.
    holders.excepts = []
    landing(['dimmer'])
    expect(within(panel()).getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(stackOn('dimmer')).toBe(false)
    expect(new Set(holders.excepts)).toEqual(new Set([77]))
  })

  it('keeps the FX it started for the selection it started over: elsewhere a tap starts afresh, asked on Replace', () => {
    programmerScope.value = { kind: 'local' }
    holders.attributes = new Set(['uv'])
    const { rerender } = render(picker({ library: 'fx', pair: MASKED }))
    act(() => fxPicker.mock.lastCall![0].onLanding(['uv']))
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Stack' }))
    act(() => fxPicker.mock.lastCall![0].onCurrent({ fxId: 77, fxType: 'Pulse', attribute: 'uv', stacked: true }))
    expect(fxPicker.mock.lastCall![0].current).toEqual(expect.objectContaining({ fxId: 77 }))

    // Another selection with the picker open: nothing to swap there, and the question back on Replace.
    const HEX_2: PressPair = { ...MASKED, targets: [{ type: 'fixture', key: 'hex-2' }] }
    rerender(picker({ library: 'fx', pair: HEX_2 }))
    expect(fxPicker.mock.lastCall![0].current).toBeNull()
    expect(within(panel()).getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(fxPicker.mock.lastCall![0].press.stackOn('uv')).toBe(false)
    act(() => fxPicker.mock.lastCall![0].onCurrent({ fxId: 78, fxType: 'Pulse', attribute: 'uv' }))
    expect(fxPicker.mock.lastCall![0].current).toEqual(expect.objectContaining({ fxId: 78 }))

    // Back on the first selection, its own FX is the one a tap swaps again.
    rerender(picker({ library: 'fx', pair: MASKED }))
    expect(fxPicker.mock.lastCall![0].current).toEqual(expect.objectContaining({ fxId: 77 }))
  })

  it('in a focused Look absorbs every FX the session started, on every selection, and a failed start forgets none', () => {
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    const HEX_2: PressPair = { ...MASKED, targets: [{ type: 'fixture', key: 'hex-2' }] }
    const { rerender } = render(picker({ library: 'fx', pair: MASKED }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    rerender(picker({ library: 'fx', pair: HEX_2 }))
    act(() => fxPicker.mock.lastCall![0].onCurrent(null))
    act(() => fxPicker.mock.lastCall![0].onCurrent({ fxId: 78, fxType: 'Pulse', attribute: 'dimmer' }))
    rerender(picker({ library: 'fx', pair: MASKED }))
    expect(fxPicker.mock.lastCall![0].current).toEqual(expect.objectContaining({ fxId: 77 }))
    rerender(picker({ library: 'fx', pair: MASKED, open: false }))
    expect(absorb).toHaveBeenCalledTimes(1)
    expect(absorb).toHaveBeenCalledWith({ projectId: 1, lookId: 9, fxIds: [77, 78] })
  })

  it('asks from Set’s *+ FX …* band too, a door that passes nothing: the page decides', async () => {
    const { FxBand } = await import('@/components/editor/PaletteBands')
    programmerScope.value = { kind: 'local' }
    holders.attributes = new Set(['uv'])
    render(<FxBand projectId={1} family="COLOUR" attribute="uv" targets={HEX_1} families={['COLOUR']} />)
    fireEvent.click(screen.getByRole('button', { name: 'FX…' }))
    act(() => fxPicker.mock.lastCall![0].onLanding(['uv']))
    expect(within(panel()).getByRole('radio', { name: 'Stack' })).toBeTruthy()
  })

  it('asks nothing off the programmer page, in a focused Look, or in the cue editor', () => {
    holders.attributes = new Set(['uv'])
    render(picker({ library: 'fx', pair: MASKED }))
    act(() => fxPicker.mock.lastCall![0].onLanding(['uv']))
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
    expect(fxPicker.mock.lastCall![0].press).toEqual({ projectId: 1, families: ['COLOUR'] })
    cleanup()
    programmerScope.value = { kind: 'layer' }
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    render(picker({ library: 'fx', pair: MASKED }))
    act(() => fxPicker.mock.lastCall![0].onLanding(['uv']))
    expect(within(panel()).queryByRole('radio', { name: 'Stack' })).toBeNull()
  })

  it('turns into the live editor once a tap starts the FX, with Swap, Open in FX editor and Done', () => {
    fxWire.active = [{ id: 77, fxType: 'Pulse', attribute: 'dimmer' }]
    const onOpenChange = vi.fn()
    const onOpenInEditor = vi.fn()
    render(picker({ library: 'fx', pair: MASKED, editOnStart: true, onOpenInEditor, onOpenChange }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    // The list stays mounted under the editor, so Swap comes back to the family it was on.
    expect(panel().querySelector('[data-fx-picker]')!.closest('[hidden]')).not.toBeNull()
    const mounts = fxPicker.mock.calls.length
    fireEvent.click(within(panel()).getByRole('button', { name: 'Swap' }))
    expect(panel().querySelector('[data-fx-picker]')!.closest('[hidden]')).toBeNull()
    expect(fxPicker.mock.calls.length).toBeGreaterThan(mounts)
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    fireEvent.click(within(panel()).getByRole('button', { name: 'Open in FX editor' }))
    expect(onOpenInEditor).toHaveBeenCalledWith(77)
    fireEvent.click(within(panel()).getByRole('button', { name: 'editor done' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('keeps Edit and its way back to the list where the door did not ask for the editor, and no ⤢ in a Look', () => {
    fxWire.active = [{ id: 77, fxType: 'Pulse', attribute: 'dimmer' }]
    const onOpenChange = vi.fn()
    const { rerender } = render(picker({ library: 'fx', pair: MASKED, onOpenChange }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    expect(panel().querySelector('[data-fx-picker]')!.closest('[hidden]')).toBeNull()
    act(() => fxPicker.mock.lastCall![0].onEdit())
    rerender(picker({ library: 'fx', pair: MASKED, onOpenChange }))
    fireEvent.click(within(panel()).getByRole('button', { name: 'editor done' }))
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(panel().querySelector('[data-fx-picker]')!.closest('[hidden]')).toBeNull()
    cleanup()
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    render(picker({ library: 'fx', pair: MASKED, editOnStart: true, onOpenInEditor: vi.fn() }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    expect(within(panel()).queryByRole('button', { name: 'Open in FX editor' })).toBeNull()
  })

  it('absorbs nothing on the programmer, or when nothing was started', () => {
    const { rerender, unmount } = render(picker({ library: 'fx', pair: MASKED }))
    fireEvent.click(panel().querySelector('[data-fx-picker]')!)
    rerender(picker({ library: 'fx', pair: MASKED, open: false }))
    unmount()
    focus.look = { projectId: 1, lookId: 9, lookName: 'Warm wash' }
    render(picker({ library: 'fx', pair: MASKED })).unmount()
    expect(absorb).not.toHaveBeenCalled()
  })
})
