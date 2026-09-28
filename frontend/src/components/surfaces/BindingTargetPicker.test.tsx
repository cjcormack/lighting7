// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BindingTarget } from '@/store/surfaces'

// The picker is store-connected on five axes; stub them all so this stays a unit test of
// which kinds are offered for which control type. The fixture list is behind
// `useRigProperties`, which is where the target-less kinds (selection property, encoder bank)
// get their vocabulary.
vi.mock('@/store/groups', () => ({
  useGroupListQuery: () => ({ data: [{ name: 'front-wash', memberCount: 2 }] }),
  // The axis field looks a group's property up to see whether it is a colour.
  useGroupPropertiesQuery: () => ({
    data: [
      { type: 'slider', name: 'dimmer', displayName: 'dimmer', category: 'dimmer', min: 0, max: 255, memberChannels: [] },
      { type: 'colour', name: 'rgbColour', displayName: 'colour', category: 'colour', memberColourChannels: [] },
    ],
  }),
}))
// One patched head, so the rig vocabulary has a colour for the axis field to show for.
const rigFixtures = vi.hoisted(() => [
  {
    key: 'hex-1',
    name: 'Hex 1',
    properties: [
      { type: 'slider', name: 'dimmer', displayName: 'dimmer', category: 'dimmer', channel: { universe: 0, channelNo: 1 }, min: 0, max: 255 },
      { type: 'colour', name: 'rgbColour', displayName: 'colour', category: 'colour', redChannel: { universe: 0, channelNo: 2 }, greenChannel: { universe: 0, channelNo: 3 }, blueChannel: { universe: 0, channelNo: 4 } },
    ],
  },
])
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: rigFixtures }),
  // The rig vocabulary reads the visible list; no fixture here is infrastructure.
  useVisibleFixtureListQuery: () => ({ data: rigFixtures }),
}))
// Empty for every suite but the infrastructure one, which fills it.
let patches: { key: string; displayName: string; infrastructure?: boolean }[] = []
vi.mock('@/store/patches', () => ({
  usePatchListQuery: () => ({ data: patches }),
  useVisiblePatchListQuery: () => ({ data: patches.filter((p) => !p.infrastructure) }),
}))
vi.mock('@/store/windows', () => ({
  useDeskWindows: () => [
    { id: 'w1', windowId: 'a', name: 'Screen 1', view: '/busk', fullscreen: false, follows: true, user: null, viewOptions: null },
    { id: 'w2', windowId: 'b', name: 'Screen 2', view: '/busk', fullscreen: false, follows: true, user: null, viewOptions: null },
  ],
}))
vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: [] }) }))
// The three record libraries, behind `useRecordBindingOptions`.
vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({
    data: [
      { id: 1, uuid: 'look-warm', name: 'Warm wash', hasDeferredEffects: false },
      { id: 2, uuid: 'look-pulse', name: 'Pulse', hasDeferredEffects: true },
    ],
  }),
}))
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: [{ id: 5, uuid: 'tmpl-red', name: 'Red', family: 'COLOUR' }] }),
}))
vi.mock('@/store/busk', () => ({
  useBuskPagesQuery: () => ({
    data: [
      {
        id: 7, uuid: 'page-verse', name: 'Verse', sortOrder: 0,
        rows: [{ columns: [{ id: 1, uuid: 'c', width: 12, banks: [{ id: 2, uuid: 'b', name: 'keys', solo: true, flow: 'WRAP', pads: [] }] }] }],
      },
    ],
  }),
}))
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({
    data: [
      { uuid: 'm1', index: 1, name: 'Master 1', bpm: 120, isRunning: true, source: 'MANUAL' },
      { uuid: 'm2', index: 2, name: 'Master 2', bpm: 60, isRunning: true, source: 'MANUAL' },
    ],
  }),
}))

import { BindingTargetPicker } from './BindingTargetPicker'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  patches = []
})

function renderPicker(continuous: boolean, value: BindingTarget, onChange = vi.fn()) {
  render(
    <BindingTargetPicker
      projectId={1}
      continuous={continuous}
      value={value}
      onChange={onChange}
      policy={null}
      onPolicyChange={() => {}}
    />,
  )
  return onChange
}

describe('BindingTargetPicker speed-master targets', () => {
  it('offers BPM on a continuous control and tap on a button', () => {
    // The split matters: a button press carries no position, so BPM on a button could only
    // ever jump the tempo to one fixed value.
    const { unmount } = render(
      <BindingTargetPicker
        projectId={1}
        continuous
        value={{ type: 'speedMasterBpm', masterUuid: null, minBpm: 60, maxBpm: 180 }}
        onChange={() => {}}
        policy={null}
        onPolicyChange={() => {}}
      />,
    )
    expect(screen.getByText('Speed master — BPM')).toBeInTheDocument()
    unmount()

    renderPicker(false, { type: 'speedMasterTap', masterUuid: null })
    expect(screen.getByText('Speed master — Tap')).toBeInTheDocument()
  })

  it('renders the BPM range inputs with the configured window', () => {
    renderPicker(true, { type: 'speedMasterBpm', masterUuid: 'm2', minBpm: 90, maxBpm: 150 })

    expect(screen.getByDisplayValue('90')).toBeInTheDocument()
    expect(screen.getByDisplayValue('150')).toBeInTheDocument()
  })

  it('keeps a cleared or garbage BPM range inside the clock range', () => {
    // `Number('')` is 0 and `Number('abc')` is NaN (which serialises to null); both fail the
    // backend's SpeedMasterBpm init requires during deserialisation — before the route's
    // try/catch, so they surface as a 500 rather than a validation message.
    const onChange = renderPicker(true, {
      type: 'speedMasterBpm',
      masterUuid: null,
      minBpm: 60,
      maxBpm: 180,
    })

    fireEvent.change(screen.getByDisplayValue('60'), { target: { value: '' } })
    expect(onChange.mock.calls[0][0]).toMatchObject({ minBpm: 20 })

    onChange.mockClear()
    fireEvent.change(screen.getByDisplayValue('180'), { target: { value: 'abc' } })
    expect(onChange.mock.calls[0][0]).toMatchObject({ maxBpm: 180 })
  })

  it('renders only a master picker for a tap binding', () => {
    renderPicker(false, { type: 'speedMasterTap', masterUuid: 'm2' })

    expect(screen.getByText('Speed Master')).toBeInTheDocument()
    expect(screen.queryByText('Min BPM')).not.toBeInTheDocument()
  })
})

describe('BindingTargetPicker record targets', () => {
  // 3a widened the union without widening this list, so *Change target* on a selection binding
  // opened an empty body under a kind Select reading "Fixture property". Session 4 mints four more
  // variants by the handful, so the same hole would reopen four times.
  //
  // Rendering *with* each value catches both halves at once: `kind` falls back to `options[0]` when
  // the value's type is not offered, and the body only renders while `value.type === kind` — so an
  // unlisted variant shows "Fixture property" and nothing else.
  it.each([
    ['Look — apply', 'Look', { type: 'applyLook', lookUuid: 'look-warm' } as BindingTarget],
    ['Template — press', 'Template', { type: 'pressTemplate', templateUuid: 'tmpl-red' } as BindingTarget],
    ['Busk pad — press', 'Busk pad', { type: 'pressPad', padUuid: 'pad-1' } as BindingTarget],
    ['Busk page — show', 'Page', { type: 'buskPageSet', pageUuid: 'page-verse' } as BindingTarget],
  ])('offers %s and renders its body', (kindLabel, fieldLabel, value) => {
    renderPicker(false, value)
    expect(screen.getByText(kindLabel)).toBeInTheDocument()
    expect(screen.getByText(fieldLabel)).toBeInTheDocument()
  })

  it.each([
    ['Busk window — focus', 'Window', { type: 'buskFocusSet', windowName: 'Screen 2', focus: 'pads' } as BindingTarget],
    ['Busk window — sheet', 'Window', { type: 'buskSheetToggle', windowName: 'Screen 2' } as BindingTarget],
    ['Selection — cells', 'Mode', { type: 'selectionCells', mode: 'ODD' } as BindingTarget],
  ])('offers %s and renders its body (busk-further plan D14)', (kindLabel, fieldLabel, value) => {
    renderPicker(false, value)
    expect(screen.getByText(kindLabel)).toBeInTheDocument()
    expect(screen.getByText(fieldLabel)).toBeInTheDocument()
  })

  it('names the window and the mode a binding carries, and offers a window that is not signed in by its own name', () => {
    renderPicker(false, { type: 'buskFocusSet', windowName: 'iPad', focus: 'rig' })
    expect(screen.getByText('iPad')).toBeInTheDocument()
    expect(screen.getByText('Rig')).toBeInTheDocument()
    cleanup()
    renderPicker(false, { type: 'selectionCells', mode: 'SECOND_HALF' })
    expect(screen.getByText('2nd half')).toBeInTheDocument()
  })

  it('renders the selection-step kinds, which have no body to fill in', () => {
    renderPicker(false, { type: 'selectionNext' })
    expect(screen.getByText('Selection — next')).toBeInTheDocument()
    expect(screen.getByText(/one place along rig order/)).toBeInTheDocument()
  })

  it('renders the page-step kinds, which have no body to fill in', () => {
    renderPicker(false, { type: 'buskPageNext' })
    expect(screen.getByText('Busk page — next')).toBeInTheDocument()
    expect(screen.getByText(/wrapping at the ends/)).toBeInTheDocument()
  })

  it('does not offer a record kind on a fader', () => {
    // Every one is a press, and a fader has no press — `refuseWrongKind` would refuse the row.
    renderPicker(true, { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'dimmer' })
    expect(screen.queryByText('Look — apply')).toBeNull()
  })

  it('names the record rather than its uuid', () => {
    renderPicker(false, { type: 'applyLook', lookUuid: 'look-warm' })
    expect(screen.getByText('Warm wash')).toBeInTheDocument()
  })

  it('offers a Look that needs a selection, but not as a choosable option', () => {
    // `SurfaceLibrary`'s drag chip already excludes one of these outright, to avoid the write
    // boundary's `BINDING_LOOK_NEEDS_SELECTION`. The picker's manual "Change target" door has to
    // enforce the same rule itself rather than let the operator pick it and hit the 400 on save.
    renderPicker(false, { type: 'applyLook', lookUuid: 'look-warm' })
    // Two comboboxes are on screen: "Target type" and this body's own "Look" field — the second
    // is the one that lists the library.
    fireEvent.click(screen.getAllByRole('combobox')[1]!)
    const pulse = screen.getByRole('option', { name: /Pulse/ })
    expect(pulse.getAttribute('aria-disabled')).toBe('true')
  })
})

describe('BindingTargetPicker colour axes', () => {
  // The axis field appears only where the named property *is* a colour: on anything else the
  // write boundary refuses the axis by name (`BINDING_AXIS_NEEDS_COLOUR`), and a field that led
  // there would be a way to learn the rule from a 400.
  it.each([
    ['a fixture', { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour' } as BindingTarget, { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'dimmer' } as BindingTarget],
    ['a group', { type: 'groupProperty', groupName: 'front-wash', propertyName: 'rgbColour' } as BindingTarget, { type: 'groupProperty', groupName: 'front-wash', propertyName: 'dimmer' } as BindingTarget],
    ['the selection', { type: 'selectionProperty', propertyName: 'rgbColour' } as BindingTarget, { type: 'selectionProperty', propertyName: 'dimmer' } as BindingTarget],
    ['the encoder bank', { type: 'encoderBankSet', propertyName: 'rgbColour' } as BindingTarget, { type: 'encoderBankSet', propertyName: 'dimmer' } as BindingTarget],
  ])('offers a colour axis on %s only for a colour property', (_what, onColour, onSlider) => {
    const continuous = onColour.type !== 'encoderBankSet'
    const { unmount } = render(
      <BindingTargetPicker projectId={1} continuous={continuous} value={onColour} onChange={vi.fn()} policy={null} onPolicyChange={() => {}} />,
    )
    expect(screen.getByText('Colour axis')).toBeInTheDocument()
    unmount()
    render(
      <BindingTargetPicker projectId={1} continuous={continuous} value={onSlider} onChange={vi.fn()} policy={null} onPolicyChange={() => {}} />,
    )
    expect(screen.queryByText('Colour axis')).toBeNull()
  })

  it('shows the axis a binding carries, and hue when it carries none', () => {
    renderPicker(true, { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour', colourAxis: 'saturation' })
    expect(screen.getByText('Saturation')).toBeInTheDocument()
    cleanup()
    renderPicker(true, { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour' })
    expect(screen.getByText('Hue')).toBeInTheDocument()
  })

  it('drops the axis when the property changes', () => {
    // A colour → dimmer edit that kept `saturation` would save a 400.
    const onChange = renderPicker(true, {
      type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour', colourAxis: 'saturation',
    })
    fireEvent.change(screen.getByPlaceholderText('dimmer'), { target: { value: 'dimmer' } })
    expect(onChange).toHaveBeenCalledWith({ type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'dimmer' })
    expect('colourAxis' in onChange.mock.calls[0]![0]).toBe(false)
  })
})

describe('BindingTargetPicker infrastructure fixtures', () => {
  it('keeps an infrastructure fixture a binding already names, and offers no other', () => {
    // A binding made before its fixture was marked infrastructure must still read — as what it is
    // — rather than as a blank field; but no infrastructure fixture is ever offered as a new pick.
    patches = [
      { key: 'hex-1', displayName: 'Hex 1' },
      { key: 'hazer-power', displayName: 'Hazer power', infrastructure: true },
      { key: 'relay-1', displayName: 'Relay 1', infrastructure: true },
    ]
    renderPicker(true, { type: 'fixtureProperty', fixtureKey: 'hazer-power', propertyName: 'dimmer' })
    expect(screen.getByText('Hazer power (infrastructure)')).toBeInTheDocument()
    // The second combobox is the Fixture field (the first is the target kind).
    fireEvent.click(screen.getAllByRole('combobox')[1]!)
    const offered = screen.getAllByRole('option').map((o) => o.textContent)
    expect(offered).toContain('Hex 1')
    expect(offered).toContain('Hazer power (infrastructure)')
    expect(offered).not.toContain('Relay 1')
  })
})
