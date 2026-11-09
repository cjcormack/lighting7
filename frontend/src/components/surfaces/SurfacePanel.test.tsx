// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ControlState, PickupChange, ResolvedPart } from '@/store/surfaces'
import { SurfacePanel } from './SurfacePanel'
import { FIXTURE_PROFILE, MAP_FRAME } from './surfaceFixtures'

/**
 * The picture over the desk's served map (surface-setup plan D15): every label, colour and dead mark
 * is what the map says for the viewed layer, and every live state what `surfaceControls` says. The
 * parts come from the map fixture lighting7 pins, so a label here is the desk's own.
 */

afterEach(cleanup)

const profile = FIXTURE_PROFILE

const state = (over: Partial<ControlState> = {}): ControlState => ({
  value: null,
  physical: null,
  touched: false,
  led: 'none',
  ring: 'none',
  ...over,
})

function renderPanel({
  layer = 'layer-a',
  parts = MAP_FRAME.layers[layer] as Record<string, ResolvedPart>,
  controls = {} as Record<string, ControlState>,
  pickups = {} as Record<string, PickupChange>,
  dimmed = false,
} = {}) {
  const onSelect = vi.fn()
  const view = render(
    <SurfacePanel
      profile={profile}
      controls={controls}
      parts={parts}
      viewedLayer={layer}
      dimmed={dimmed}
      pickups={pickups}
      selectedControlId={null}
      onSelectControl={onSelect}
    />,
  )
  return { onSelect, ...view }
}

/** The cell for one control, found by its title. */
function cell(controlId: string): HTMLElement {
  const label = profile.controls.find((c) => c.controlId === controlId)!.label
  return screen.getAllByTitle(label)[0]!
}

describe('SurfacePanel — what a control says it is', () => {
  it('draws the desk\'s label for each bound part, and an em-dash for an unbound one', () => {
    renderPanel()
    expect(within(cell('btn-3')).getByText('GO · Act 1')).toBeInTheDocument()
    expect(within(cell('enc-9')).getByText('Pan · The selection')).toBeInTheDocument()
    expect(within(cell('btn-5')).getByText('—')).toBeInTheDocument()
  })

  it('labels a strip\'s controls from the strip, and a control\'s own row on the layer that holds it', () => {
    renderPanel()
    expect(within(cell('fader-1')).getByText('Dimmer · Front wash')).toBeInTheDocument()
    expect(within(cell('btn-25')).getByText('Select · Front wash')).toBeInTheDocument()
    cleanup()
    renderPanel({ layer: 'layer-b' })
    expect(within(cell('fader-1')).getByText('Dimmer · Hex 1')).toBeInTheDocument()
  })

  it('marks a dead binding without hiding what it was', () => {
    renderPanel()
    const label = within(cell('btn-4')).getByText('GO · A missing cue stack')
    expect(label.className).toContain('text-destructive')
  })

  it('bands the label in the colour the map names', () => {
    renderPanel()
    const band = cell('fader-1').querySelector<HTMLElement>('[data-target-band]')
    expect(band?.dataset.targetBand).toBe('blue')
    expect(cell('btn-3').querySelector('[data-target-band]')).toBeNull()
  })

  it('names a push-only encoder by its push', () => {
    const parts = { 'enc-9:MAIN': { from: 'NONE' }, 'enc-9:PUSH': { from: 'DIRECT', label: 'Locate · The selection' } } as Record<string, ResolvedPart>
    renderPanel({ parts })
    expect(within(cell('enc-9')).getByText('Push · Locate · The selection')).toBeInTheDocument()
  })

  it('lights the layer button of the layer being viewed', () => {
    const withLayerKey = {
      ...profile,
      controls: [...profile.controls, { type: 'bankButton' as const, controlId: 'layer-b-key', label: 'Layer B', note: 85, programChange: null, channel: 0, bankId: 'layer-b' }],
      layout: { regions: [{ name: 'Keys', columns: 1, cells: [{ controlId: 'layer-b-key', col: 0, row: 0 }] }] },
    }
    const { container } = render(
      <SurfacePanel profile={withLayerKey} controls={{}} parts={{}} viewedLayer="layer-b" pickups={{}} selectedControlId={null} onSelectControl={() => {}} />,
    )
    expect(container.querySelectorAll('.bg-surface-led')).toHaveLength(1)
  })
})

describe('SurfacePanel — live state', () => {
  it('shows a fader\'s position, preferring where the hand left it', () => {
    renderPanel({ controls: { 'fader-1': state({ value: 127, physical: 38 }) } })
    expect(within(cell('fader-1')).getByText('30%')).toBeInTheDocument()
  })

  it('draws the pickup target while a fader waits to be picked up', () => {
    renderPanel({
      controls: { 'fader-9': state({ physical: 38 }) },
      pickups: { 'fader-9': { displayKey: 'd', controlId: 'fader-9', state: 'AWAITING_PICKUP', target: 84 } },
    })
    expect(within(cell('fader-9')).getByText('66%')).toBeInTheDocument()
  })

  it('lights one ring dot for a value, and none for mixed or nothing selected', () => {
    const { container } = renderPanel({ controls: { 'enc-9': state({ value: 64, ring: 'on' }) } })
    expect(container.querySelectorAll('circle[fill="var(--surface-led)"]')).toHaveLength(1)
    cleanup()
    const mixed = renderPanel({ controls: { 'enc-9': state({ value: null, ring: 'off' }) } })
    expect(mixed.container.querySelectorAll('circle[fill="var(--surface-led)"]')).toHaveLength(0)
  })

  it('draws another layer\'s live state faint while it is not the desk\'s layer (D13)', () => {
    const { container } = renderPanel({ dimmed: true, controls: { 'btn-3': state({ led: 'on' }) } })
    expect(container.querySelectorAll('[data-live-dimmed]').length).toBeGreaterThan(0)
    expect(screen.getByTestId('surface-panel').dataset.dimmed).toBe('true')
  })
})
