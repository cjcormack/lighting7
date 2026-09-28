// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CellResolution } from '../columns'
import { chan, sliderProp } from '@/test/fixtureFactories'
import { PositionCell } from './PositionCell'

/**
 * The position editor types degrees where the head annotates, bytes where it does not, and its
 * XY pad writes both axes (editor-kit plan D14). Every expectation is a literal byte or degree:
 * an expectation built from `dmxToDegrees` would pass with the mapping wrong.
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
  it('types degrees on an annotated head: byte 128 reads 271° of 540, and 270° typed is the degree itself', async () => {
    const { onCommit } = draw([MOVER])
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    // 128 / 255 × 540 = 271.06, shown whole.
    expect(pan).toHaveValue(271)
    expect(screen.getByRole('spinbutton', { name: 'Tilt' })).toHaveValue(136)
    expect(pan).toHaveAttribute('max', '540')
    fireEvent.change(pan, { target: { value: '270' } })
    // The commit carries the degree; each head's byte is resolved where its descriptor is in hand.
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', panDeg: 270 })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Tilt' }), { target: { value: '300' } })
    // Clamped to the annotated range on the way out.
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', tiltDeg: 270 })
    expect(screen.getByText('128 · 128 of 255 · 0–540° · 0–270°')).toBeInTheDocument()
  })

  it('keeps bytes on a head with no annotation', async () => {
    const { onCommit } = draw([SILENT])
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(pan).toHaveValue(128)
    expect(pan).toHaveAttribute('max', '255')
    fireEvent.change(pan, { target: { value: '200' } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', pan: 200 })
    expect(screen.getByText('128 · 128 · 0–255 · 0–255')).toBeInTheDocument()
  })

  it('keeps bytes for every head when the batch mixes annotated and silent ones', async () => {
    const { onCommit } = draw([MOVER], { batch: { count: 2, skipped: 0, resolutions: [MOVER, SILENT] } })
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    // The row's own head annotates; the batch does not, so no degree is offered.
    expect(pan).toHaveValue(128)
    fireEvent.change(pan, { target: { value: '64' } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', pan: 64 })
  })

  it('writes both axes from the pad, in bytes on a silent head', async () => {
    const { onCommit } = draw([SILENT])
    await screen.findByRole('spinbutton', { name: 'Pan' })
    const pad = placePad(document.body)
    // Halfway across, at the top: pan 50%, tilt 100%.
    fireEvent.pointerDown(pad, { clientX: 60, clientY: 0, pointerId: 1 })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', pan: 128, tilt: 255 })
    // A move while held writes again; a move after release does not.
    fireEvent.pointerMove(pad, { clientX: 0, clientY: 120, pointerId: 1 })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', pan: 0, tilt: 0 })
    fireEvent.pointerUp(pad, { pointerId: 1 })
    onCommit.mockClear()
    fireEvent.pointerMove(pad, { clientX: 60, clientY: 60, pointerId: 1 })
    expect(onCommit).not.toHaveBeenCalled()
  })

  it('writes both axes from the pad in degrees on an annotated head', async () => {
    const { onCommit } = draw([MOVER])
    await screen.findByRole('spinbutton', { name: 'Pan' })
    const pad = placePad(document.body)
    fireEvent.pointerDown(pad, { clientX: 60, clientY: 0, pointerId: 1 })
    // Halfway across 540° is 270°; the top of 270° is 270°.
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'position', panDeg: 270, tiltDeg: 270 })
  })

  it('draws the pad thumb on the degree scale of an inverted axis, where the drag writes', async () => {
    const inverted: NonNullable<CellResolution> = {
      ...MOVER,
      panProperty: sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540, inverted: true }),
    }
    // Byte 0 on an inverted pan is 540°: the thumb sits at the right edge, not the left.
    draw([inverted], { value: { kind: 'position', isUniform: true, pan: 0, tilt: 128, panNormalized: 0, tiltNormalized: 128 / 255 } })
    await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(screen.getByRole('spinbutton', { name: 'Pan' })).toHaveValue(540)
    const thumb = document.querySelector<HTMLElement>('[data-editor-xy-pad] > span')
    expect(thumb?.style.left).toBe('100%')
  })

  it('draws the label line and no count line', async () => {
    draw([MOVER], { batch: { count: 6, skipped: 1, resolutions: [MOVER] }, scopeLabel: 'Local' })
    await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(screen.getByText('6 heads · Local')).toBeInTheDocument()
    expect(screen.getByText('1 head has no position · skipped')).toBeInTheDocument()
    expect(screen.queryByText(/Applying to/)).toBeNull()
  })
})
