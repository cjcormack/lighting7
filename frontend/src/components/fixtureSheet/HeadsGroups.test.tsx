// @vitest-environment jsdom
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/** The desk's channels, the group's members, and the effects, as the sheet's reads see them. */
const wire = vi.hoisted(() => ({
  values: new Map<string, number>(),
  connected: true,
  effects: [] as unknown[],
  fixtures: [] as unknown[],
  members: [] as { fixtureKey: string; fixtureName: string }[],
}))

vi.mock('@/hooks/usePropertyValues', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/hooks/usePropertyValues')>()
  const at = (ref: { universe: number; channelNo: number } | null) => (ref ? (wire.values.get(`${ref.universe}:${ref.channelNo}`) ?? 0) : 0)
  return {
    ...real,
    getChannelValue: at,
    subscribeToChannels: () => () => {},
    useChannelValue: at,
    useSliderValue: (p: { channel: { universe: number; channelNo: number } }) => at(p.channel),
  }
})
// The Radix slider, as a range input a test can drive (`FxEditor.test.tsx`'s stand-in).
vi.mock('@/components/ui/slider', () => ({
  Slider: ({
    min,
    max,
    value,
    disabled,
    onValueChange,
    'aria-label': label,
  }: {
    min: number
    max: number
    value: number[]
    disabled?: boolean
    onValueChange?: (v: number[]) => void
    'aria-label'?: string
  }) => (
    <input
      type="range"
      aria-label={label}
      min={min}
      max={max}
      value={value[0]}
      disabled={disabled}
      onChange={(e) => onValueChange?.([Number(e.target.value)])}
    />
  ),
}))
vi.mock('@/hooks/usePropertyParkStatus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/usePropertyParkStatus')>()),
  usePropertyParkStatus: () => ({ isAnyParked: false }),
}))
vi.mock('@/hooks/useColourAppearance', () => ({
  useColourAppearance: () => ({ r: 0, g: 0, b: 0, combinedCss: '#000000', appearanceCss: '#000000' }),
}))
vi.mock('@/store/park', () => ({ useGetParkStateListQuery: () => ({ data: [] }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => wire.connected }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => false }))
vi.mock('@/store/programmer', () => ({ useProgrammerRevision: () => 0 }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: wire.effects }),
  usePauseFxMutation: () => [vi.fn()],
  useResumeFxMutation: () => [vi.fn()],
  useRemoveFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/groups', () => ({
  useGroupQuery: () => ({ data: { members: wire.members }, isLoading: false }),
  usePauseGroupFxMutation: () => [vi.fn()],
  useResumeGroupFxMutation: () => [vi.fn()],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: wire.fixtures, fixtureByKey: new Map(), typeByKey: new Map() }),
}))
vi.mock('../groups/useVisibleGroupMembers', () => ({ useVisibleGroupMembers: (m: unknown) => m }))
vi.mock('../groups/GroupMembersSection', () => ({ GroupMembersSection: () => <div data-testid="members" /> }))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: undefined }) }))
vi.mock('@/store/patches', () => ({ usePatchListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtures')>()),
  useFixtureTypeListQuery: () => ({ data: [] }),
}))
vi.mock('@/hooks/useGelIndex', () => ({ useGelIndex: () => new Map() }))
vi.mock('./effectLabels', () => ({
  useEffectDetail: () => () => '¼ · M1',
  useCueLabel: () => (id: number) => `Q${id}`,
}))
vi.mock('../surfaces/FixtureBoundControlsRow', () => ({ FixtureBoundControlsRow: () => null, GroupBoundControlsRow: () => null }))
vi.mock('../fixtures/LocateButton', () => ({ LocateTargetsButton: () => <button type="button">Locate</button> }))
vi.mock('../fixtures/FixtureParkButton', () => ({
  FixtureParkButton: () => <button type="button">Park</button>,
  ParkButton: () => <button type="button">Park</button>,
}))
vi.mock('../fx/LookTogglePicker', () => ({
  LookTogglePicker: (props: { targetType: string; targetKey: string }) => <span data-testid="look-picker" data-target={`${props.targetType}:${props.targetKey}`} />,
}))
const picker = vi.hoisted(() => ({ props: null as null | Record<string, unknown> }))
vi.mock('../fx/FxPicker', () => ({
  FxPicker: (props: Record<string, unknown>) => {
    picker.props = props
    return <div data-testid="fx-picker" />
  },
}))
vi.mock('../fx/FxEditor', () => ({ FxEditor: () => <div data-testid="fx-editor" /> }))

import { lightingApi } from '@/api/lightingApi'
import { programmerWs } from '@/test/backendMock'
import { chan, colourProp, groupSummary, makeFixture, makePixelBar, sliderProp } from '@/test/fixtureFactories'
import type { Fixture } from '@/store/fixtures'
import type { ProgrammerEntry, ProvenanceEntry } from '@/api/programmerWsApi'
import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import { FixtureSheet } from './FixtureSheet'

/**
 * Heads and groups (fixture-fx-sheets plan §5, session 4's tests): the head strip's pick, and where
 * the rows over it write. Every write is asserted as the frame the desk would receive.
 */

const BAR_A: Fixture = makePixelBar('bar-a', 12, [], { name: 'Bar A' })
const BAR_B: Fixture = makePixelBar('bar-b', 12, [], { name: 'Bar B' })
const BAR_C: Fixture = makePixelBar('bar-c', 3, [], { name: 'Bar C' })

const wash = (key: string, first: number) =>
  makeFixture(
    key,
    [
      sliderProp('dimmer', 'dimmer', chan(first)),
      colourProp('rgbColour', chan(first + 1), chan(first + 2), chan(first + 3), { whiteChannel: chan(first + 4) }),
    ],
    { name: key.replace('wash-', 'Wash '), groups: ['front'], channelCount: 5, firstChannel: first },
  )
const WASH_1 = wash('wash-1', 101)
const WASH_2 = wash('wash-2', 111)
const WASH_3 = wash('wash-3', 121)
const FRONT = { ...groupSummary('front', 3), capabilities: ['dimmer', 'colour'] }

const pip = (name: string) => screen.getByRole('button', { name })
const pips = (root: ParentNode = document) => [...root.querySelectorAll<HTMLElement>('[data-head-pip]')]
const all = () => screen.getByRole('button', { name: /^All/ })
const row = (id: string) => document.querySelector(`[data-row="${id}"]`) as HTMLElement
const mouse = { button: 0, pointerId: 1, pointerType: 'mouse' }
/** A pip added to the pick — a click with ⌘ held; a plain click picks that head alone. */
const addPip = (name: string) => fireEvent.click(pip(name), { metaKey: true })
const checked = () => pips().filter((p) => p.getAttribute('aria-pressed') === 'true').map((p) => p.getAttribute('aria-label'))

/** Rows over a pick of heads or members are a `PickPropertyRow`; one picked head is its own `PropertyRow`. */
function typeInto(field: HTMLElement, value: string) {
  fireEvent.change(field, { target: { value } })
  fireEvent.keyDown(field, { key: 'Enter' })
}

function hold(entries: ProgrammerEntry[], provenance: ProvenanceEntry[]) {
  programmerWs.push({
    entries: new Map(entries.map((e) => [`${e.targetKey}|${e.propertyName}`, e])),
    provenance: new Map(provenance.map((p) => [`${p.targetKey}|${p.propertyName}`, p])),
  })
}

let channelUpdate: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  wire.values = new Map()
  wire.connected = true
  wire.effects = []
  wire.fixtures = [BAR_A, BAR_B, BAR_C, WASH_1, WASH_2, WASH_3]
  wire.members = [WASH_1, WASH_2, WASH_3].map((f) => ({ fixtureKey: f.key, fixtureName: f.name }))
  picker.props = null
  programmerWs.reset()
  resetProgrammerFadeStore()
  localStorage.clear()
  vi.clearAllMocks()
  // The raw channel write a group's sliders used to send, one per member: it must never go out.
  channelUpdate = vi.fn()
  ;(lightingApi as unknown as { channels: Record<string, unknown> }).channels = {
    update: channelUpdate,
    get: (universe: number, channelNo: number) => wire.values.get(`${universe}:${channelNo}`) ?? 0,
  }
})
afterEach(() => vi.unstubAllGlobals())

describe('the head strip', () => {
  it('draws All and a pip per head, starting on All', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    const strip = screen.getByRole('group', { name: 'Heads' })
    expect(pips(strip)).toHaveLength(12)
    expect(all().getAttribute('aria-pressed')).toBe('true')
    // All holds every head, so every pip is lit.
    expect(pips(strip).every((p) => p.getAttribute('aria-pressed') === 'true')).toBe(true)
  })

  it('resets the pick when the sheet moves to another target', () => {
    const { rerender } = render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 3'))
    expect(pip('Head 3').getAttribute('aria-pressed')).toBe('true')
    expect(all().getAttribute('aria-pressed')).toBe('false')
    // The Stage panel keeps one sheet mounted and hands it the next fixture.
    rerender(<FixtureSheet fixture={BAR_B} host="stage" />)
    expect(all().getAttribute('aria-pressed')).toBe('true')
    expect(checked()).toHaveLength(12)
  })

  it('picks a run with a mouse drag — every pip crossed, once', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.pointerDown(pip('Head 1'), mouse)
    fireEvent.pointerMove(pip('Head 2'), mouse)
    fireEvent.pointerMove(pip('Head 3'), mouse)
    fireEvent.pointerMove(pip('Head 4'), mouse)
    fireEvent.pointerMove(pip('Head 4'), mouse)
    fireEvent.pointerUp(pip('Head 4'), mouse)
    const picked = pips().filter((p) => p.getAttribute('aria-pressed') === 'true')
    expect(picked.map((p) => p.getAttribute('aria-label'))).toEqual(['Head 1', 'Head 2', 'Head 3', 'Head 4'])
    expect(screen.getByText('4 of 12')).toBeTruthy()
  })

  it('picks one head on a tap, from All or from any other pick', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 2'))
    expect(checked()).toEqual(['Head 2'])
    addPip('Head 3')
    fireEvent.click(pip('Head 5'))
    expect(checked()).toEqual(['Head 5'])
    fireEvent.click(all())
    expect(all().getAttribute('aria-pressed')).toBe('true')
  })

  it('adds and removes a head with ⌘, and a pick of every head reads as All with every pip still lit', () => {
    render(<FixtureSheet fixture={BAR_C} host="stage" />)
    fireEvent.click(pip('Head 1'))
    addPip('Head 2')
    expect(checked()).toEqual(['Head 1', 'Head 2'])
    addPip('Head 3')
    expect(all().getAttribute('aria-pressed')).toBe('true')
    expect(checked()).toEqual(['Head 1', 'Head 2', 'Head 3'])
    addPip('Head 2')
    expect(checked()).toEqual(['Head 1', 'Head 3'])
  })

  it('picks by pattern from the Cells menu, steps with Prev and Next, and a tap clears the pattern', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    const cells = () => screen.getByRole('button', { name: /^Cells:/ })
    // Radix opens on Enter at the trigger, with no pointer needed.
    const choose = (name: string) => {
      fireEvent.keyDown(cells(), { key: 'Enter' })
      fireEvent.click(screen.getByRole('menuitem', { name }))
    }
    expect(cells().getAttribute('aria-label')).toBe('Cells: All')
    choose('Odd')
    expect(checked()).toEqual(['Head 1', 'Head 3', 'Head 5', 'Head 7', 'Head 9', 'Head 11'])
    expect(cells().getAttribute('aria-label')).toBe('Cells: Odd')
    choose('Invert')
    expect(checked()).toEqual(['Head 2', 'Head 4', 'Head 6', 'Head 8', 'Head 10', 'Head 12'])
    choose('2nd half')
    expect(checked()).toEqual(['Head 7', 'Head 8', 'Head 9', 'Head 10', 'Head 11', 'Head 12'])
    fireEvent.click(screen.getByRole('button', { name: 'Next head' }))
    expect(checked()).toEqual(['Head 1', 'Head 8', 'Head 9', 'Head 10', 'Head 11', 'Head 12'])
    expect(cells().getAttribute('aria-label')).toBe('Cells: a pick of your own')
    fireEvent.click(pip('Head 4'))
    expect(checked()).toEqual(['Head 4'])
    fireEvent.click(screen.getByRole('button', { name: 'Previous head' }))
    expect(checked()).toEqual(['Head 3'])
  })

  it('steps from All to the first head with Next and the last with Prev', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(screen.getByRole('button', { name: 'Next head' }))
    expect(checked()).toEqual(['Head 1'])
    fireEvent.click(all())
    fireEvent.click(screen.getByRole('button', { name: 'Previous head' }))
    expect(checked()).toEqual(['Head 12'])
  })

  it('never touches the desk selection — the pick is the sheet’s own (call 4)', () => {
    const selection = lightingApi.selection as unknown as Record<string, unknown>
    const set = vi.fn()
    selection.set = set
    selection.toggle = set
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 1'))
    addPip('Head 2')
    expect(set).not.toHaveBeenCalled()
  })
})

describe('rows over a pick of heads', () => {
  it('writes only the picked heads: heads 1–4 of a 12-pixel bar', () => {
    setProgrammerFade('1000')
    const setColour = vi.spyOn(lightingApi.programmer, 'setColour')
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.pointerDown(pip('Head 1'), mouse)
    for (const n of [2, 3, 4]) fireEvent.pointerMove(pip(`Head ${n}`), mouse)
    fireEvent.pointerUp(pip('Head 4'), mouse)
    fireEvent.click(within(row('rgbColour')).getByRole('button', { name: /^Open the .* editor$/ }))
    fireEvent.change(within(row('rgbColour')).getByLabelText('R'), { target: { value: '255' } })
    expect(setColour.mock.calls.map((c) => c[1])).toEqual(['bar-a.pixel-0', 'bar-a.pixel-1', 'bar-a.pixel-2', 'bar-a.pixel-3'])
    expect(setColour.mock.calls.every((c) => c[0] === 'fixture' && c[5] === undefined)).toBe(true)
  })

  it('draws no Dimmer row for a head — a head has no dimmer channel, and its colour row sets its level', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    expect(row('virtual-dimmer:rgbColour')).toBeNull()
    fireEvent.click(pip('Head 1'))
    expect(row('virtual-dimmer:rgbColour')).toBeNull()
    expect(row('rgbColour')).toBeTruthy()
  })

  it('draws mixed levels as a range and a swatch strip, and one head as its own row', () => {
    // Head 1 at red, head 2 at half red.
    const [h1, h2] = BAR_A.elements!
    const red = (e: typeof h1, v: number) => {
      const c = e.properties[0] as { redChannel: { universe: number; channelNo: number } }
      wire.values.set(`${c.redChannel.universe}:${c.redChannel.channelNo}`, v)
    }
    red(h1, 255)
    red(h2, 128)
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 1'))
    addPip('Head 2')
    expect(within(row('rgbColour')).getByTestId('colour-strip').children).toHaveLength(2)
    expect(row('rgbColour').textContent).toContain('2 colours')
    // One head picked: that head's own row, with its own value.
    fireEvent.click(pip('Head 2'))
    expect(row('rgbColour').getAttribute('data-heads')).toBeNull()
  })

  it('counts the heads a source holds on the chip and dashes the edge where they differ', () => {
    const [h1, h2] = BAR_A.elements!
    hold(
      [{ targetKey: h1.key, propertyName: 'rgbColour', value: '#ff0000', owner: 'web', touched: true, owners: ['web'] }],
      [
        { targetKey: h1.key, propertyName: 'rgbColour', source: 'PROGRAMMER' },
        { targetKey: h2.key, propertyName: 'rgbColour', source: 'CUE', cueId: 8 },
      ],
    )
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 1'))
    addPip('Head 2')
    expect(row('rgbColour').getAttribute('data-mixed-source')).toBe('true')
    const chip = row('rgbColour').querySelector('button[data-source]') as HTMLElement
    expect(chip.textContent).toBe('Programmer · 1 of 2')
  })

  it('lists what reaches the pick in the tray and starts + Effect on the picked head', () => {
    wire.effects = [
      { id: 7, effectType: 'Pulse', targetKey: 'bar-a.pixel-2', propertyName: 'rgbColour', isGroupTarget: false, isRunning: true, programmerOwned: true },
      { id: 8, effectType: 'Rainbow', targetKey: 'bar-a', propertyName: 'rgbColour', isGroupTarget: false, isRunning: true, programmerOwned: true },
    ]
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 1'))
    const tray = document.querySelector('[data-fx-tray]') as HTMLElement
    // Head 1 sees the fixture's Rainbow (painted onto every head) and not head 3's Pulse.
    expect(within(tray).getByText('Rainbow')).toBeTruthy()
    expect(within(tray).queryByText('Pulse')).toBeNull()
    fireEvent.click(within(tray).getByRole('button', { name: 'Effect' }))
    expect((picker.props?.target as { fixture: Fixture }).fixture.key).toBe('bar-a.pixel-0')
  })

  it('starts on the fixture with a filter for a half of the heads, and refuses a pick no filter names', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    fireEvent.click(pip('Head 1'))
    for (const n of [2, 3, 4, 5, 6]) addPip(`Head ${n}`)
    const tray = document.querySelector('[data-fx-tray]') as HTMLElement
    fireEvent.click(within(tray).getByRole('button', { name: 'Effect' }))
    expect((picker.props?.target as { fixture: Fixture }).fixture.key).toBe('bar-a')
    expect(picker.props?.elementFilter).toBe('FIRST_HALF')
    // Five heads: no filter names them, so the picker says why rather than starting on the wrong ones.
    addPip('Head 6')
    expect(within(tray).queryByTestId('fx-picker')).toBeNull()
    expect(within(tray).getByText(/pick one of those/)).toBeTruthy()
    fireEvent.click(within(tray).getByRole('button', { name: 'Back to the effects' }))
    expect((within(tray).getByRole('button', { name: 'Effect' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('the tray’s Look picker follows the pick', () => {
  const look = () => document.querySelector('[data-testid="look-picker"]')?.getAttribute('data-target') ?? null

  it('presses onto the fixture on All, one head when one is picked, and is absent for any other pick', () => {
    render(<FixtureSheet fixture={BAR_A} host="stage" />)
    expect(look()).toBe('fixture:bar-a')
    fireEvent.click(pip('Head 1'))
    expect(look()).toBe('fixture:bar-a.pixel-0')
    addPip('Head 2')
    expect(look()).toBeNull()
  })

  it('presses onto the group on All and one member when one is picked', () => {
    render(<FixtureSheet group={FRONT} host="popup" />)
    expect(look()).toBe('group:front')
    fireEvent.click(pip('Wash 3'))
    expect(look()).toBe('fixture:wash-3')
  })
})

describe('the group sheet', () => {
  it('writes one group entry on All', () => {
    setProgrammerFade('2000')
    const set = vi.spyOn(lightingApi.programmer, 'set')
    render(<FixtureSheet group={FRONT} host="popup" />)
    expect(all().textContent).toBe('All 3')
    typeInto(within(row('dimmer')).getByLabelText('dimmer percent'), '50')
    expect(set).toHaveBeenCalledTimes(1)
    expect(set).toHaveBeenCalledWith('group', 'front', 'dimmer', '128', 2000)
    expect(screen.getByText(/the group/)).toBeTruthy()
  })

  it('writes each picked member, carrying the group, on a subset', () => {
    setProgrammerFade('0')
    const set = vi.spyOn(lightingApi.programmer, 'set')
    render(<FixtureSheet group={FRONT} host="popup" />)
    fireEvent.click(pip('Wash 1'))
    addPip('Wash 3')
    typeInto(within(row('dimmer')).getByLabelText('dimmer percent'), '50')
    expect(set.mock.calls).toEqual([
      ['fixture', 'wash-1', 'dimmer', '128', 0, 'front'],
      ['fixture', 'wash-3', 'dimmer', '128', 0, 'front'],
    ])
  })

  it('scales each member’s colour from a dimmerless group’s Dimmer row, carrying the group', () => {
    const par = (key: string, first: number) =>
      makeFixture(key, [colourProp('rgbColour', chan(first), chan(first + 1), chan(first + 2))], {
        name: key,
        groups: ['pars'],
        channelCount: 3,
        firstChannel: first,
      })
    const parA = par('par-a', 201)
    const parB = par('par-b', 211)
    wire.fixtures = [parA, parB]
    wire.members = [parA, parB].map((f) => ({ fixtureKey: f.key, fixtureName: f.name }))
    const red = (f: Fixture) => (f.properties[0] as { redChannel: { universe: number; channelNo: number } }).redChannel
    for (const f of [parA, parB]) wire.values.set(`${red(f).universe}:${red(f).channelNo}`, 200)
    setProgrammerFade('0')
    const setColour = vi.spyOn(lightingApi.programmer, 'setColour')
    render(<FixtureSheet group={{ ...groupSummary('pars', 2), capabilities: ['colour'] }} host="popup" />)
    typeInto(within(row('virtual-dimmer:rgbColour')).getByLabelText('Dimmer percent'), '50')
    expect(setColour.mock.calls.map((c) => [c[0], c[1], c[3].r, c[5]])).toEqual([
      ['fixture', 'par-a', 128, 'pars'],
      ['fixture', 'par-b', 128, 'pars'],
    ])
  })

  it('writes one picked member as its own row, still carrying the group', () => {
    const set = vi.spyOn(lightingApi.programmer, 'set')
    render(<FixtureSheet group={FRONT} host="popup" />)
    fireEvent.click(pip('Wash 2'))
    typeInto(within(row('dimmer')).getByLabelText('dimmer percent'), '100')
    expect(set).toHaveBeenCalledWith('fixture', 'wash-2', 'dimmer', '255', 0, 'front')
  })

  it('never sends a raw channel for a group — a drag is the group entry, at no fade', () => {
    const set = vi.spyOn(lightingApi.programmer, 'set')
    const setColour = vi.spyOn(lightingApi.programmer, 'setColour')
    render(<FixtureSheet group={FRONT} host="popup" />)
    fireEvent.change(within(row('dimmer')).getByRole('slider', { name: 'dimmer' }), { target: { value: '200' } })
    expect(set).toHaveBeenCalledWith('group', 'front', 'dimmer', '200', undefined)
    fireEvent.click(within(row('rgbColour')).getByRole('button', { name: 'Open the rgbcolour editor' }))
    fireEvent.change(within(row('rgbColour')).getByLabelText('R'), { target: { value: '255' } })
    expect(setColour.mock.calls.every((c) => c[0] === 'group' && c[1] === 'front')).toBe(true)
    expect(setColour).toHaveBeenCalled()
    expect(channelUpdate).not.toHaveBeenCalled()
  })

  it('clears the group entry with one frame from All', () => {
    const clear = vi.spyOn(lightingApi.programmer, 'clearEntry')
    hold(
      [{ targetKey: 'wash-2', propertyName: 'dimmer', value: '128', owner: 'web', touched: true, owners: ['web'], sourceGroup: 'front' }],
      [{ targetKey: 'wash-2', propertyName: 'dimmer', source: 'PROGRAMMER' }],
    )
    render(<FixtureSheet group={FRONT} host="popup" />)
    fireEvent.click(within(row('dimmer')).getByRole('button', { name: 'Clear your dimmer' }))
    expect(clear).toHaveBeenCalledTimes(1)
    expect(clear).toHaveBeenCalledWith('group', 'front', 'dimmer', expect.any(Number))
  })

  it('has Values and Members, and starts + Effect on the group with All and on one member', () => {
    render(<FixtureSheet group={FRONT} host="popup" onOpenMember={() => {}} />)
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Values', 'Members'])
    const tray = () => document.querySelector('[data-fx-tray]') as HTMLElement
    fireEvent.click(within(tray()).getByRole('button', { name: 'Effect' }))
    expect(picker.props?.target).toMatchObject({ type: 'group', group: { name: 'front' } })
    // The picker follows the pick while it is open.
    fireEvent.click(pip('Wash 2'))
    expect(picker.props?.target).toMatchObject({ type: 'fixture', fixture: { key: 'wash-2' } })
    act(() => fireEvent.click(screen.getByRole('tab', { name: 'Members' })))
    expect(screen.getByTestId('members')).toBeTruthy()
  })
})
