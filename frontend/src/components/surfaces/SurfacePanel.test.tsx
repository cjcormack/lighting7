// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  ColourAxis,
  ControlState,
  ControlSurfaceBinding,
  ControlSurfaceType,
  EncoderBankSelection,
} from '@/store/surfaces'
import { buildBindingIndex } from '@/lib/surfaceResolve'
import { SurfacePanel } from './SurfacePanel'
import { EMPTY_RECORD_OPTIONS } from './recordOptions'

/**
 * Each state in `midi-surface-design/Legend.dc.html`, produced the only way the panel will ever
 * see one: as a `surfaceControls` frame. The three encoder states the plan singles out — uniform,
 * mixed and no-selection — are the ones worth a test each, because the desk draws mixed and
 * no-selection identically and a reading that flattened either into "0" would look plausible.
 */

afterEach(cleanup)

const KEY = 'xtc'

const profile: ControlSurfaceType = {
  typeKey: KEY,
  vendor: 'Behringer',
  product: 'X-Touch Compact',
  portPattern: null,
  className: 'X',
  banks: [{ id: 'layer-a', name: 'A' }],
  strips: [
    { id: 'strip-1', fader: 'fader-1', select: 'btn-25', encoder: 'enc-1', flash: 'btn-1' },
  ],
  layout: {
    regions: [
      {
        name: 'strips',
        columns: 1,
        cells: [
          { controlId: 'enc-1', col: 0, row: 0 },
          { controlId: 'btn-1', col: 0, row: 1 },
          { controlId: 'fader-1', col: 0, row: 2 },
          { controlId: 'btn-25', col: 0, row: 3 },
        ],
      },
      {
        name: 'right',
        columns: 1,
        cells: [{ controlId: 'enc-9', col: 0, row: 0 }],
      },
    ],
  },
  controls: [
    {
      type: 'fader',
      controlId: 'fader-1',
      label: 'Fader 1',
      cc: 1,
      channel: 1,
      hasMotor: true,
      motorCc: 1,
      touchNote: null,
      touchCc: 101,
      resolution: 'SEVEN_BIT',
    },
    {
      type: 'encoder',
      controlId: 'enc-1',
      label: 'Encoder 1',
      cc: 10,
      channel: 1,
      ringCc: 26,
      ringStyle: 'SINGLE_DOT',
      pushNote: null,
      pushLed: 'NONE',
    },
    {
      type: 'encoder',
      controlId: 'enc-9',
      label: 'Encoder 9',
      cc: 18,
      channel: 1,
      ringCc: 34,
      ringStyle: 'SINGLE_DOT',
      pushNote: null,
      pushLed: 'NONE',
    },
    { type: 'button', controlId: 'btn-1', label: 'Button 1', note: 1, channel: 1, ledFeedback: 'ON_OFF' },
    { type: 'button', controlId: 'btn-25', label: 'Button 25', note: 25, channel: 1, ledFeedback: 'ON_OFF' },
  ],
}

function binding(
  id: number,
  controlId: string,
  target: ControlSurfaceBinding['target'],
  health: ControlSurfaceBinding['health'] = { type: 'ok' },
): ControlSurfaceBinding {
  return {
    id,
    projectId: 1,
    deviceTypeKey: KEY,
    controlId,
    bank: null,
    target,
    targetType: target.type,
    takeoverPolicy: null,
    sortOrder: id,
    health,
  }
}

const state = (over: Partial<ControlState> = {}): ControlState => ({
  value: null,
  physical: null,
  touched: false,
  led: 'none',
  ring: 'none',
  ...over,
})

/** An encoder bank selection, `colourAxis` absent for hue. */
const bank = (propertyName: string, colourAxis?: ColourAxis): EncoderBankSelection =>
  colourAxis ? { propertyName, colourAxis } : { propertyName }

function renderPanel({
  bindings = [] as ControlSurfaceBinding[],
  controls = {} as Record<string, ControlState>,
  encoderBank = bank('colour'),
  pickups = {},
  selectedControlId = null as string | null,
} = {}) {
  const onSelect = vi.fn()
  render(
    <SurfacePanel
      profile={profile}
      controls={controls}
      index={buildBindingIndex(bindings, profile)}
      activeBank={null}
      encoderBank={encoderBank}
      pickups={pickups}
      selectedControlId={selectedControlId}
      onSelectControl={onSelect}
      editing={false}
      lifted={null}
      onRemoveBinding={() => {}}
      records={EMPTY_RECORD_OPTIONS}
    />,
  )
  return onSelect
}

/** The cell for one control, found by the label the panel prints under it. */
function cell(controlId: string): HTMLElement {
  const label = profile.controls.find((c) => c.controlId === controlId)!.label
  return screen.getAllByTitle(label)[0]!
}

describe('SurfacePanel — what a control says it is', () => {
  it('draws an em-dash for a control with no binding', () => {
    renderPanel()
    expect(within(cell('fader-1')).getByText('—')).toBeInTheDocument()
  })

  it('labels all four of a strip’s controls from the one row', () => {
    renderPanel({
      bindings: [binding(9, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } })],
    })
    expect(within(cell('fader-1')).getByText('Movers.dimmer')).toBeInTheDocument()
    // The encoder follows the bank, which is the whole reason it is not just another dimmer.
    expect(within(cell('enc-1')).getByText('Movers.colour')).toBeInTheDocument()
    expect(within(cell('btn-25')).getByText('Select Movers')).toBeInTheDocument()
    expect(within(cell('btn-1')).getByText('Flash Movers.dimmer')).toBeInTheDocument()
  })

  it('lets a control’s own binding override the strip it sits on', () => {
    renderPanel({
      bindings: [
        binding(9, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } }),
        binding(10, 'fader-1', { type: 'blackout' }),
      ],
    })
    expect(within(cell('fader-1')).getByText('Blackout')).toBeInTheDocument()
    expect(within(cell('enc-1')).getByText('Movers.colour')).toBeInTheDocument()
  })

  it('marks a dead binding without hiding what it was', () => {
    renderPanel({
      bindings: [
        binding(9, 'fader-1', { type: 'groupProperty', groupName: 'Gone', propertyName: 'dimmer' }, {
          type: 'missingGroup',
          groupName: 'Gone',
        }),
      ],
    })
    const label = within(cell('fader-1')).getByText('Gone.dimmer')
    expect(label.className).toContain('text-destructive')
  })
})

describe('SurfacePanel — the fader states', () => {
  it('shows the fed-back value as a percentage', () => {
    renderPanel({ controls: { 'fader-1': state({ value: 92 }) } })
    expect(within(cell('fader-1')).getByText('72%')).toBeInTheDocument()
  })

  it('prefers the physical position, which is all a non-motor fader has', () => {
    // The desk never tells a non-motor fader where to be, so `value` is what it was *asked* for
    // and `physical` is where the operator's hand left it. Drawing the cap at `value` would show
    // a fader that is not there.
    renderPanel({ controls: { 'fader-1': state({ value: 127, physical: 38 }) } })
    expect(within(cell('fader-1')).getByText('30%')).toBeInTheDocument()
  })

  it('draws the pickup target while a fader is waiting to be picked up', () => {
    renderPanel({
      controls: { 'fader-1': state({ physical: 38 }) },
      pickups: {
        'fader-1': { displayKey: 'd', controlId: 'fader-1', state: 'AWAITING_PICKUP', target: 84 },
      },
    })
    const face = within(cell('fader-1'))
    expect(face.getByText('30%')).toBeInTheDocument()
    expect(face.getByText('66%')).toBeInTheDocument()
  })
})

describe('SurfacePanel — the three encoder states', () => {
  it('lights one dot when every selected head agrees', () => {
    const { container } = renderWithContainer({ 'enc-9': state({ value: 64, ring: 'on' }) })
    expect(litDots(container)).toBe(1)
  })

  // Mixed and no-selection both arrive as `ring: "off"`, and the hardware draws them the same
  // way, so the picture must too. What must *not* happen is either being drawn as a value: a
  // dark ring says "the desk has nothing to show you", and a dot at zero would say "they are all
  // at zero", which is a different and wrong claim.
  it('darkens the whole ring when the selection is mixed', () => {
    const { container } = renderWithContainer({ 'enc-9': state({ value: null, ring: 'off' }) })
    expect(litDots(container)).toBe(0)
  })

  it('darkens the whole ring when nothing is selected', () => {
    const { container } = renderWithContainer({ 'enc-9': state({ ring: 'none' }) })
    expect(litDots(container)).toBe(0)
  })
})

describe('SurfacePanel — the button LED', () => {
  it('lights the LED bar from the stream, not from the binding', () => {
    // The point of D7: a select button is lit because the desk lit it, so the screen and the
    // hardware cannot disagree about what is selected.
    const { container } = render(
      <SurfacePanel
        profile={profile}
        controls={{ 'btn-25': { ...state(), led: 'on' } }}
        index={buildBindingIndex(
          [binding(9, 'strip-1', { type: 'strip', target: { type: 'group', key: 'Movers' } })],
          profile,
        )}
        activeBank={null}
        encoderBank={bank('colour')}
        pickups={{}}
        selectedControlId={null}
        onSelectControl={() => {}}
        editing={false}
        lifted={null}
        onRemoveBinding={() => {}}
        records={EMPTY_RECORD_OPTIONS}
      />,
    )
    const lit = container.querySelectorAll('.bg-surface-led')
    expect(lit.length).toBe(1)
  })
})

function renderWithContainer(controls: Record<string, ControlState>) {
  return render(
    <SurfacePanel
      profile={profile}
      controls={controls}
      index={buildBindingIndex([], profile)}
      activeBank={null}
      encoderBank={bank('colour')}
      pickups={{}}
      selectedControlId={null}
      onSelectControl={() => {}}
      editing={false}
      lifted={null}
      onRemoveBinding={() => {}}
      records={EMPTY_RECORD_OPTIONS}
    />,
  )
}

/** A lit ring dot is the only circle carrying the LED colour. */
function litDots(container: HTMLElement): number {
  return container.querySelectorAll('circle[fill="var(--surface-led)"]').length
}
