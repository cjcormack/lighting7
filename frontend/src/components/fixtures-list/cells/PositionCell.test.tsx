// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CellResolution } from '../columns'
import { chan, sliderProp } from '@/test/fixtureFactories'
import { PositionCell } from './PositionCell'

/**
 * The position editor types centre-relative DMX on every head — `-128..127`, 0 at the centre of
 * travel (venue-visit plan D3) — and its XY pad writes both axes. Every expectation is a literal
 * value: an expectation built from `dmxToCentre` would pass with the mapping wrong.
 */

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})
afterEach(() => vi.unstubAllGlobals())

/** A mover: pan 0–540°, tilt 0–270°, paired from two annotated sliders. */
const MOVER: NonNullable<CellResolution> = {
  kind: 'position',
  pan: chan(1),
  tilt: chan(2),
  panMin: 0,
  panMax: 255,
  tiltMin: 0,
  tiltMax: 255,
  panProperty: sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
  tiltProperty: sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 }),
}

/** A head with pan/tilt sliders and no annotation at all. */
const SILENT: NonNullable<CellResolution> = {
  kind: 'position',
  pan: chan(3),
  tilt: chan(4),
  panMin: 0,
  panMax: 255,
  tiltMin: 0,
  tiltMax: 255,
  panProperty: sliderProp('pan', 'pan', chan(3), { axis: 'PAN' }),
  tiltProperty: sliderProp('tilt', 'tilt', chan(4), { axis: 'TILT' }),
}

function draw(
  resolutions: NonNullable<CellResolution>[],
  over: Partial<React.ComponentProps<typeof PositionCell>> = {},
): { onCommit: ReturnType<typeof vi.fn> } {
  const onCommit = vi.fn()
  render(
    <PositionCell
      value={{ kind: 'position', isUniform: true, pan: 128, tilt: 128, panNormalized: 128 / 255, tiltNormalized: 128 / 255 }}
      resolutions={resolutions}
      label="Position"
      autoOpen
      keyboardSeed={null}
      onCommit={onCommit}
      onBeginEdit={() => {}}
      {...over}
    />,
  )
  return { onCommit }
}

/** Point the pad's box somewhere jsdom can be asked about: 120px square at the origin. */
function placePad(container: HTMLElement): HTMLElement {
  const pad = container.querySelector<HTMLElement>('[data-editor-xy-pad]')
  if (!pad) throw new Error('no pad')
  pad.getBoundingClientRect = () =>
    ({ left: 0, top: 0, width: 120, height: 120, right: 120, bottom: 120, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect
  return pad
}

describe('PositionCell', () => {
  it('types centre-relative on an annotated head: byte 128 reads 0, and a typed value is the commit', async () => {
    const { onCommit } = draw([MOVER])
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(pan).toHaveValue(0)
    expect(screen.getByRole('spinbutton', { name: 'Tilt' })).toHaveValue(0)
    expect(pan).toHaveAttribute('min', '-128')
    expect(pan).toHaveAttribute('max', '127')
    fireEvent.change(pan, { target: { value: '-20' } })
    // The commit carries the centre value; each head's byte is resolved where its descriptor is.
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', panCentre: -20 })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Tilt' }), { target: { value: '300' } })
    // Clamped to the scale on the way out.
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', tiltCentre: 127 })
    // The DMX underneath stays on the read-out.
    expect(screen.getByText('DMX 128 · 128')).toBeInTheDocument()
  })

  it('takes the same scale on a head with no annotation, and in a batch mixing the two', async () => {
    const { onCommit } = draw([SILENT], { batch: { count: 2, skipped: 0, resolutions: [MOVER, SILENT] } })
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(pan).toHaveValue(0)
    fireEvent.change(pan, { target: { value: '64' } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', panCentre: 64 })
  })

  it('shows the cell itself on the scale', () => {
    draw([MOVER], {
      autoOpen: false,
      value: { kind: 'position', isUniform: true, pan: 108, tilt: 168, panNormalized: 108 / 255, tiltNormalized: 168 / 255 },
    })
    expect(screen.getByText('-20,+40')).toBeInTheDocument()
  })

  it('writes both axes from the pad, centre-relative', async () => {
    const { onCommit } = draw([SILENT])
    await screen.findByRole('spinbutton', { name: 'Pan' })
    const pad = placePad(document.body)
    // Halfway across, at the top: the centre of pan, the top of tilt.
    fireEvent.pointerDown(pad, { clientX: 60, clientY: 0, pointerId: 1 })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', panCentre: 0, tiltCentre: 127 })
    // A move while held writes again; a move after release does not.
    fireEvent.pointerMove(pad, { clientX: 0, clientY: 120, pointerId: 1 })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', panCentre: -128, tiltCentre: -128 })
    fireEvent.pointerUp(pad, { pointerId: 1 })
    onCommit.mockClear()
    fireEvent.pointerMove(pad, { clientX: 60, clientY: 60, pointerId: 1 })
    expect(onCommit).not.toHaveBeenCalled()
  })

  it('reads an inverted axis as raw DMX — the scale is DMX, not degrees', async () => {
    const inverted: NonNullable<CellResolution> = {
      ...MOVER,
      panProperty: sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540, inverted: true }),
    }
    draw([inverted], { value: { kind: 'position', isUniform: true, pan: 0, tilt: 128, panNormalized: 0, tiltNormalized: 128 / 255 } })
    await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(screen.getByRole('spinbutton', { name: 'Pan' })).toHaveValue(-128)
    const thumb = document.querySelector<HTMLElement>('[data-editor-xy-pad] > span')
    expect(thumb?.style.left).toBe('0%')
  })

  it('draws the label line and no count line', async () => {
    draw([MOVER], { batch: { count: 6, skipped: 1, resolutions: [MOVER] }, scopeLabel: 'Local' })
    await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(screen.getByText('6 heads · Local')).toBeInTheDocument()
    expect(screen.getByText('1 head has no position · skipped')).toBeInTheDocument()
    expect(screen.queryByText(/Applying to/)).toBeNull()
  })
})
