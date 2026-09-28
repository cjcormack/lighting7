// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CellResolution } from '../columns'
import { chan, sliderProp } from '@/test/fixtureFactories'
import { SliderCell, fromPct, toPct } from './SliderCell'

/**
 * The level editor's field is in the cell's unit — a percent — with the byte on the read-out
 * (editor-kit plan D13). The conversions are pinned with literals at both ends and the middle,
 * because a rounding that drifted by one would round-trip 128 → 50% → 128 just as happily as a
 * correct one and only the literal 128 says which is which.
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

const DIMMER: NonNullable<CellResolution> = { kind: 'slider', property: sliderProp('dimmer', 'dimmer', chan(1)) }
const NARROW: NonNullable<CellResolution> = {
  kind: 'slider',
  property: sliderProp('strobe', 'strobe', chan(2), { min: 10, max: 250 }),
}

function draw(
  byte: number,
  over: Partial<React.ComponentProps<typeof SliderCell>> = {},
): { onCommit: ReturnType<typeof vi.fn> } {
  const onCommit = vi.fn()
  render(
    <SliderCell
      value={{ kind: 'slider', min: byte, max: byte, isUniform: true }}
      resolutions={[DIMMER]}
      label="Dimmer"
      autoOpen
      keyboardSeed={null}
      onCommit={onCommit}
      onBeginEdit={() => {}}
      {...over}
    />,
  )
  return { onCommit }
}

describe('the percent ↔ byte conversions', () => {
  it('map each end and the middle to literal values', () => {
    expect(toPct(0)).toBe(0)
    expect(toPct(128)).toBe(50)
    expect(toPct(255)).toBe(100)
    expect(fromPct(0)).toBe(0)
    expect(fromPct(50)).toBe(128)
    expect(fromPct(100)).toBe(255)
  })
})

describe('SliderCell', () => {
  it('shows the byte as a percent in the field and the byte on the read-out', async () => {
    draw(128)
    expect(await screen.findByRole('spinbutton', { name: 'Dimmer' })).toHaveValue(50)
    expect(screen.getByText('128 of 255 · 0–255')).toBeInTheDocument()
  })

  it.each([
    { typed: '0', byte: 0 },
    { typed: '50', byte: 128 },
    { typed: '100', byte: 255 },
  ])('commits a typed $typed percent as byte $byte', async ({ typed, byte }) => {
    // Opened at 13 (5%), so none of the three typed values is the one already in the box — a
    // controlled input fires no change for the value it already holds.
    const { onCommit } = draw(13)
    fireEvent.change(await screen.findByRole('spinbutton', { name: 'Dimmer' }), { target: { value: typed } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'slider', value: byte })
  })

  it('clamps the byte to the resolution\'s own range, not to 0–255', async () => {
    const { onCommit } = draw(128, { resolutions: [NARROW], label: 'Strobe' })
    fireEvent.change(await screen.findByRole('spinbutton', { name: 'Strobe' }), { target: { value: '100' } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'slider', value: 250 })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Strobe' }), { target: { value: '0' } })
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'slider', value: 10 })
  })

  it('draws the label line from the batch and the scope, and says what was skipped', async () => {
    draw(204, {
      batch: { count: 4, skipped: 2, resolutions: [DIMMER, DIMMER, DIMMER, DIMMER] },
      scopeLabel: 'Warm Wash',
    })
    await screen.findByRole('spinbutton', { name: 'Dimmer' })
    expect(screen.getByText('4 heads · Warm Wash')).toBeInTheDocument()
    expect(screen.getByText('204 of 255 · 0–255 on every head')).toBeInTheDocument()
    expect(screen.getByText('2 heads have no dimmer · skipped')).toBeInTheDocument()
    expect(screen.queryByText(/Applying to/)).toBeNull()
  })

  it('says the ranges differ where the batch\'s heads do not agree', async () => {
    draw(204, { batch: { count: 2, skipped: 0, resolutions: [DIMMER, NARROW] } })
    await screen.findByRole('spinbutton', { name: 'Dimmer' })
    expect(screen.getByText('204 of 255 · 0–255 on the first head · ranges differ')).toBeInTheDocument()
  })
})
