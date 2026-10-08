// @vitest-environment jsdom
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/** The desk's channels and parks, as the sheet's reads see them. */
const wire = vi.hoisted(() => ({
  values: new Map<string, number>(),
  parked: new Set<string>(),
  connected: true,
  effects: [] as unknown[],
}))

vi.mock('@/hooks/usePropertyValues', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/hooks/usePropertyValues')>()
  const at = (ref: { universe: number; channelNo: number } | null) => (ref ? (wire.values.get(`${ref.universe}:${ref.channelNo}`) ?? 0) : 0)
  return {
    ...real,
    useChannelValue: at,
    useSliderValue: (p: { channel: { universe: number; channelNo: number } }) => at(p.channel),
    useSettingValue: (p: { channel: { universe: number; channelNo: number }; options: { level: number }[] }) => {
      const level = at(p.channel)
      return { level, option: real.resolveSettingOption(p.options as never, level) }
    },
  }
})
vi.mock('@/hooks/usePropertyParkStatus', () => ({
  usePropertyParkStatus: (p: { name: string }) => ({ isAnyParked: wire.parked.has(p.name) }),
}))
vi.mock('@/hooks/useColourAppearance', () => ({
  useColourAppearance: () => ({ r: 255, g: 138, b: 61, combinedCss: '#ff8a3d', appearanceCss: '#ff8a3d' }),
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => wire.connected }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => false }))
vi.mock('@/store/programmer', () => ({ useProgrammerRevision: () => 0 }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: wire.effects }),
  usePauseFxMutation: () => [vi.fn()],
  useResumeFxMutation: () => [vi.fn()],
  useRemoveFxMutation: () => [vi.fn()],
}))
const removeGroupFx = vi.hoisted(() => vi.fn(() => ({ unwrap: () => Promise.resolve() })))
vi.mock('@/store/groups', () => ({
  usePauseGroupFxMutation: () => [vi.fn()],
  useResumeGroupFxMutation: () => [vi.fn()],
  useRemoveGroupFxMutation: () => [removeGroupFx],
}))
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
vi.mock('../fixtures/LocateButton', () => ({
  LocateButton: () => <button type="button">Locate</button>,
  LocateTargetsButton: () => <button type="button">Locate</button>,
}))
vi.mock('../fixtures/FixtureParkButton', () => ({
  FixtureParkButton: () => <button type="button">Park</button>,
  ParkButton: () => <button type="button">Park</button>,
}))
vi.mock('../fx/LookTogglePicker', () => ({ LookTogglePicker: () => null }))
const picker = vi.hoisted(() => ({ props: null as null | Record<string, unknown> }))
vi.mock('../fx/FxPicker', () => ({
  FxPicker: (props: Record<string, unknown>) => {
    picker.props = props
    return <div data-testid="fx-picker" />
  },
}))
vi.mock('../fx/FxEditor', () => ({ FxEditor: () => <div data-testid="fx-editor" /> }))

import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'
import { programmerWs } from '@/test/backendMock'
import { chan, makeActiveEffect, makeFixture, positionProp, settingProp, sliderProp } from '@/test/fixtureFactories'
import type { ProgrammerEntry, ProvenanceEntry } from '@/api/programmerWsApi'
import type { Fixture } from '@/store/fixtures'
import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import { FixtureSheet } from './FixtureSheet'
import { PhoneSheet, SAFE_BOTTOM_CLASS } from './PhoneSheet'
import { SheetTitle } from '@/components/ui/sheet'

/**
 * The fixture sheet as a host draws it (fixture-fx-sheets plan §5, session 2's tests). Every write
 * is asserted as the frame the desk would receive.
 */

const SPOT: Fixture = makeFixture(
  'spot-3',
  [
    sliderProp('dimmer', 'dimmer', chan(1)),
    sliderProp('pan', 'pan', chan(2), { axis: 'PAN', degMin: 0, degMax: 540 }),
    sliderProp('tilt', 'tilt', chan(3), { axis: 'TILT', degMin: 0, degMax: 270 }),
    settingProp('gobo', 'gobo', chan(4), [
      { name: 'open', level: 0, displayName: 'Open' },
      { name: 'breakup', level: 20, displayName: 'Breakup' },
    ]),
    sliderProp('zoom', 'zoom', chan(5)),
  ],
  { name: 'Spot 3', universe: 0, firstChannel: 1, channelCount: 5, manufacturer: 'Robe', model: 'ColorSpot' },
)

function entry(propertyName: string, over: Partial<ProgrammerEntry> = {}): ProgrammerEntry {
  return { targetKey: 'spot-3', propertyName, value: '200', owner: 'web', touched: true, owners: ['web'], ...over }
}
function prov(propertyName: string, over: Partial<ProvenanceEntry>): ProvenanceEntry {
  return { targetKey: 'spot-3', propertyName, source: 'PROGRAMMER', ...over }
}

function hold(entries: ProgrammerEntry[], provenance: ProvenanceEntry[]) {
  programmerWs.push({
    entries: new Map(entries.map((e) => [`${e.targetKey}|${e.propertyName}`, e])),
    provenance: new Map(provenance.map((p) => [`${p.targetKey}|${p.propertyName}`, p])),
  })
}

const row = (id: string) => document.querySelector(`[data-row="${id}"]`) as HTMLElement

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  wire.values = new Map([
    ['0:1', 204],
    ['0:2', 128],
    ['0:3', 64],
  ])
  wire.parked = new Set()
  wire.connected = true
  wire.effects = []
  picker.props = null
  programmerWs.reset()
  resetProgrammerFadeStore()
  localStorage.clear()
  vi.clearAllMocks()
})
afterEach(() => vi.unstubAllGlobals())

describe('FixtureSheet — rows', () => {
  it('groups the rows by family: Intensity, Position, Beam', () => {
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    const families = [...document.querySelectorAll('[data-family]')].map((el) => el.getAttribute('data-family'))
    expect(families).toEqual(['INTENSITY', 'POSITION', 'BEAM'])
    expect(within(document.querySelector('[data-family="BEAM"]') as HTMLElement).getByText('gobo')).toBeTruthy()
  })

  it('draws each source kind as a chip and an edge', () => {
    const pulse = makeActiveEffect({ id: 7, effectType: 'Pulse', targetKey: 'spot-3', propertyName: 'zoom', programmerOwned: false })
    wire.effects = [pulse]
    hold(
      [entry('dimmer')],
      [
        prov('dimmer', {}),
        prov('zoom', { source: 'EFFECT', effectId: 7 }),
        prov('gobo', { source: 'CUE', cueId: 12, layerSource: { kind: 'LOOK', id: 3, name: 'Night' } as never }),
        prov('position', { source: 'PARKED' }),
      ],
    )
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    const chip = (id: string) => row(id).querySelector('button[data-source]') as HTMLElement
    expect(row('dimmer').dataset.source).toBe('programmer')
    expect(chip('dimmer').textContent).toBe('Programmer')
    expect(row('dimmer').className).toContain('before:bg-primary')
    expect(chip('zoom').textContent).toBe('Pulse · ¼ · M1')
    expect(row('zoom').className).toContain('before:bg-violet-500')
    expect(chip('gobo').textContent).toBe('Q12 · Night')
    expect(row('gobo').className).toContain('before:bg-sky-500')
    expect(chip('position').textContent).toBe('Parked')
    expect(row('position').className).toContain('before:bg-amber-500')
  })

  it('draws Base for a row nothing asserts', () => {
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    expect((row('zoom').querySelector('button[data-source]') as HTMLElement).textContent).toBe('Base')
    expect(row('zoom').dataset.source).toBe('base')
  })

  it('marks a row whose value holds a cue effect back with the amber dot', () => {
    wire.effects = [makeActiveEffect({ id: 9, targetKey: 'spot-3', propertyName: 'dimmer', programmerOwned: false, cueId: 12 })]
    hold([entry('dimmer')], [prov('dimmer', {})])
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    expect(within(row('dimmer')).getByTestId('held-back-dot')).toBeTruthy()
    expect(row('zoom').querySelector('[data-testid="held-back-dot"]')).toBeNull()
  })
})

describe('FixtureSheet — clearing', () => {
  it('draws × only while the programmer holds the property, and clears it at the programmer fade', () => {
    setProgrammerFade('2000')
    const clearEntry = vi.spyOn(lightingApi.programmer, 'clearEntry')
    hold([entry('dimmer')], [prov('dimmer', {})])
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    expect(row('zoom').querySelector('[data-row-clear]')).toBeNull()
    const x = within(row('dimmer')).getByRole('button', { name: 'Clear your dimmer' })
    expect(x.getAttribute('title')).toBe('Clear your dimmer — it falls to what is underneath')
    fireEvent.click(x)
    expect(clearEntry).toHaveBeenCalledWith('fixture', 'spot-3', 'dimmer', 2000)
  })

  it('clears the position entry from the Position row', () => {
    const clearEntry = vi.spyOn(lightingApi.programmer, 'clearEntry')
    hold([entry('position', { value: '128,64' })], [prov('position', {})])
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    fireEvent.click(within(row('position')).getByRole('button', { name: 'Clear your position' }))
    expect(clearEntry).toHaveBeenCalledWith('fixture', 'spot-3', 'position', 0)
  })

  it('sends Release as one clearTarget and toasts the reply, naming effects left running', async () => {
    setProgrammerFade('1500')
    const clearTarget = vi.spyOn(lightingApi.programmer, 'clearTarget').mockResolvedValue({
      targetType: 'fixture',
      targetKey: 'spot-3',
      values: 2,
      effects: 1,
      partial: [{ effectId: 4, effectType: 'Pulse', targetKey: 'Front wash', isGroupTarget: true, propertyName: 'dimmer' }],
    })
    hold([entry('dimmer'), entry('zoom')], [prov('dimmer', {}), prov('zoom', {})])
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    const release = screen.getByRole('button', { name: 'Release 2' })
    await act(async () => {
      fireEvent.click(release)
    })
    expect(clearTarget).toHaveBeenCalledWith('fixture', 'spot-3', 1500)
    expect(toast.success).toHaveBeenCalledWith('Released 2 values and 1 effect on Spot 3')
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('1 effect left running — it drives heads outside this fixture'))
  })
})

describe('FixtureSheet — writes', () => {
  it('commits a typed value on Enter, in the row unit, at the programmer fade', () => {
    setProgrammerFade('2000')
    const set = vi.spyOn(lightingApi.programmer, 'set')
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    const field = within(row('dimmer')).getByLabelText('dimmer percent') as HTMLInputElement
    expect(field.value).toBe('80')
    expect(row('dimmer').querySelector('[data-editor-unit]')?.textContent).toBe('%')
    fireEvent.change(field, { target: { value: '5' } })
    fireEvent.change(field, { target: { value: '50' } })
    // Nothing goes out per keystroke: `5` on the way to `50` would be a fade of its own.
    expect(set).not.toHaveBeenCalled()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(set).toHaveBeenCalledTimes(1)
    expect(set).toHaveBeenCalledWith('fixture', 'spot-3', 'dimmer', '128', 2000, undefined)
  })

  it('writes Position as one setPosition in degrees — never channels.update', () => {
    const setPosition = vi.spyOn(lightingApi.programmer, 'setPosition')
    const update = vi.fn()
    ;(lightingApi as unknown as { channels: Record<string, unknown> }).channels = {
      update,
      get: (universe: number, channelNo: number) => wire.values.get(`${universe}:${channelNo}`) ?? 0,
    }
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    fireEvent.click(within(row('position')).getByRole('button', { name: 'Open the position editor' }))
    const pan = within(row('position')).getByLabelText('Pan degrees') as HTMLInputElement
    expect(pan.value).toBe('271')
    fireEvent.change(pan, { target: { value: '270' } })
    fireEvent.keyDown(pan, { key: 'Enter' })
    // 270° of a 540° pan is byte 128; tilt is kept from the wire.
    expect(setPosition).toHaveBeenCalledWith('fixture', 'spot-3', 128, 64, 0, undefined)
    expect(update).not.toHaveBeenCalled()
  })

  it('types degrees on a head whose position descriptor sits over annotated axes', () => {
    // The Robe ColorSpot 575's shape: a `position` descriptor beside pan 0–530° and tilt 0–280°.
    const robe = makeFixture(
      'robe-1',
      [
        sliderProp('dimmer', 'dimmer', chan(10)),
        positionProp('position', chan(11), chan(13)),
        sliderProp('pan', 'pan', chan(11), { axis: 'PAN', degMin: 0, degMax: 530 }),
        sliderProp('panFine', 'pan_fine', chan(12)),
        sliderProp('tilt', 'tilt', chan(13), { axis: 'TILT', degMin: 0, degMax: 280 }),
        sliderProp('tiltFine', 'tilt_fine', chan(14)),
      ],
      { name: 'Robe 1', universe: 0, firstChannel: 10, channelCount: 5, manufacturer: 'Robe', model: 'ColorSpot 575' },
    )
    const setPosition = vi.spyOn(lightingApi.programmer, 'setPosition')
    const update = vi.fn()
    ;(lightingApi as unknown as { channels: Record<string, unknown> }).channels = {
      update,
      get: (universe: number, channelNo: number) => wire.values.get(`${universe}:${channelNo}`) ?? 0,
    }
    render(<FixtureSheet fixture={robe} host="popup" />)
    fireEvent.click(within(row('position')).getByRole('button', { name: 'Open the position editor' }))
    const pan = within(row('position')).getByLabelText('Pan degrees') as HTMLInputElement
    fireEvent.change(pan, { target: { value: '265' } })
    fireEvent.keyDown(pan, { key: 'Enter' })
    // 265° of a 530° pan is byte 128, not 255 — the descriptor's entry, never the axes.
    expect(setPosition).toHaveBeenCalledWith('fixture', 'robe-1', 128, 0, 0, undefined)
    expect(update).not.toHaveBeenCalled()
  })
})

describe('FixtureSheet — the shape', () => {
  it('pins the FX tray outside the properties scroller', () => {
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    const body = document.querySelector('[data-sheet-body]') as HTMLElement
    const tray = document.querySelector('[data-fx-tray]') as HTMLElement
    expect(body.className).toContain('overflow-y-auto')
    expect(body.contains(tray)).toBe(false)
    expect(tray.parentElement).toBe(body.parentElement)
  })

  it("asks before stopping a group's effect from a fixture's tray, which stops it on every member", () => {
    wire.effects = [
      makeActiveEffect({ id: 4, effectType: 'Pulse', targetKey: 'Front wash', isGroupTarget: true, propertyName: 'dimmer' }),
    ]
    const fixture = { ...SPOT, groups: ['Front wash'] }
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    render(<FixtureSheet fixture={fixture} host="popup" />)
    fireEvent.click(screen.getByRole('button', { name: 'Open the effects' }))
    expect(screen.getByText(/via Front wash/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Stop Pulse' }))
    expect(confirm).toHaveBeenCalledWith('Stop Pulse on every fixture in Front wash?')
    expect(removeGroupFx).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: 'Stop Pulse' }))
    expect(removeGroupFx).toHaveBeenCalledWith({ id: 4, groupName: 'Front wash' })
  })

  it('opens + Effect as the picker in the tray, on the family of the row open on the sheet', () => {
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    fireEvent.click(screen.getByRole('button', { name: 'Open the position editor' }))
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    const tray = document.querySelector('[data-fx-tray]') as HTMLElement
    expect(within(tray).getByTestId('fx-picker')).toBeTruthy()
    expect(picker.props).toMatchObject({ target: { type: 'fixture', fixture: SPOT }, initialFamily: 'POSITION', preferredProperty: 'position' })
  })

  it("opens a row's effect in the live editor inline, under its row", () => {
    wire.effects = [makeActiveEffect({ id: 5, effectType: 'Circle', targetKey: 'spot-3', propertyName: 'position' })]
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    fireEvent.click(screen.getByRole('button', { name: /^Circle/ }))
    const tray = document.querySelector('[data-fx-tray]') as HTMLElement
    expect(tray.getAttribute('data-open')).toBe('true')
    expect(within(tray).getByTestId('fx-editor')).toBeTruthy()
  })

  it("shows a cue's effect read-only in the tray — its cue named, no editor, pause or stop (session 5)", () => {
    wire.effects = [makeActiveEffect({ id: 9, effectType: 'Pulse', targetKey: 'spot-3', propertyName: 'dimmer', programmerOwned: false, cueId: 12 })]
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    fireEvent.click(screen.getByRole('button', { name: 'Open the effects' }))
    const row = document.querySelector('[data-effect-row="9"]') as HTMLElement
    expect(within(row).getByText(/on Q12/)).toBeTruthy()
    expect(within(row).queryByRole('button', { name: /^Pulse/ })).toBeNull()
    expect(within(row).queryByRole('button', { name: 'Pause Pulse' })).toBeNull()
    expect(within(row).queryByRole('button', { name: 'Stop Pulse' })).toBeNull()
    expect(screen.queryByTestId('fx-editor')).toBeNull()
  })

  it('has no Edit toggle', () => {
    render(<FixtureSheet fixture={SPOT} host="stage" focus={<div>focus</div>} />)
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
    expect(screen.getByRole('tab', { name: 'Focus' })).toBeTruthy()
  })

  it('draws the card host unscrolled, without the header, with its tray and no Edit toggle', () => {
    render(<FixtureSheet fixture={SPOT} host="card" />)
    expect(document.querySelector('[data-fixture-sheet]')?.getAttribute('data-host')).toBe('card')
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(document.querySelector('[data-scope-line]')).toBeNull()
    expect((document.querySelector('[data-sheet-body]') as HTMLElement).className).not.toContain('overflow-y-auto')
    expect(document.querySelector('[data-fx-tray]')).not.toBeNull()
    expect(row('dimmer')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('is read-only while the desk is offline', () => {
    wire.connected = false
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    expect((within(row('dimmer')).getByLabelText('dimmer percent') as HTMLInputElement).disabled).toBe(true)
    expect(row('dimmer').dataset.readOnly).toBe('true')
    expect(screen.getByText('Offline — read-only until the desk is back')).toBeTruthy()
  })

  it('is read-only on a parked row, and live on the others', () => {
    wire.parked = new Set(['zoom'])
    render(<FixtureSheet fixture={SPOT} host="popup" />)
    expect((within(row('zoom')).getByLabelText('zoom percent') as HTMLInputElement).disabled).toBe(true)
    expect((within(row('dimmer')).getByLabelText('dimmer percent') as HTMLInputElement).disabled).toBe(false)
  })
})

/** Session 6: the phone host (D14, §4) — finger sizes, in the bottom sheet that holds it. */
describe('FixtureSheet — the phone host', () => {
  it('takes finger sizes: a 36px field, a 32px ×, 32px tray chips — and the desk hosts keep theirs', () => {
    hold([entry('dimmer')], [prov('dimmer', {})])
    wire.effects = [makeActiveEffect({ id: 5, effectType: 'Circle', targetKey: 'spot-3', propertyName: 'position' })]
    const { unmount } = render(<FixtureSheet fixture={SPOT} host="phone" />)
    const field = within(row('dimmer')).getByLabelText('dimmer percent')
    expect(field.className).toContain('h-9')
    // 16px text: below it iOS zooms the page on a field's focus.
    expect(field.className).toContain('text-base')
    expect(within(row('dimmer')).getByRole('button', { name: 'Clear your dimmer' }).className).toContain('size-8')
    expect(screen.getByRole('button', { name: /^Circle/ }).className).toContain('h-8')
    expect(screen.getByRole('button', { name: 'Effect' }).className).toContain('h-8')
    unmount()

    render(<FixtureSheet fixture={SPOT} host="stage" />)
    expect(within(row('dimmer')).getByLabelText('dimmer percent').className).toContain('h-7')
    expect(within(row('dimmer')).getByRole('button', { name: 'Clear your dimmer' }).className).not.toContain('size-8')
    expect(screen.getByRole('button', { name: /^Circle/ }).className).toContain('h-6')
  })

  it('keeps the tray outside the scroller, at the foot of the bottom sheet, above the home indicator', () => {
    render(
      <PhoneSheet open onClose={() => {}} form="bottom-sheet" modal={false} description="Spot 3">
        <FixtureSheet fixture={SPOT} host="phone" titleComponent={SheetTitle} />
      </PhoneSheet>,
    )
    const content = document.querySelector('[data-phone-sheet]') as HTMLElement
    const body = content.querySelector('[data-sheet-body]') as HTMLElement
    const tray = content.querySelector('[data-fx-tray]') as HTMLElement
    expect(body.className).toContain('overflow-y-auto')
    expect(body.contains(tray)).toBe(false)
    // The tray is the column's last child, and the column the sheet's foot: nothing lies between
    // the tray and the sheet's bottom edge but the safe-area padding.
    expect(tray.parentElement?.lastElementChild).toBe(tray)
    expect(content.className).toContain(SAFE_BOTTOM_CLASS)
    expect(screen.getByRole('dialog', { name: 'Spot 3' })).toBeTruthy()
    // The grabber sits above the sheet, the peek folding its rows away.
    const grabber = content.querySelector('[data-sheet-grabber]') as HTMLElement
    fireEvent.click(grabber)
    fireEvent.click(grabber)
    expect(content.dataset.height).toBe('peek')
    expect(content.contains(tray)).toBe(true)
  })
})
