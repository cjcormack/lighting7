// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import libraryJson from '../../../../src/main/resources/lanterns/library.json'
import { indexLanterns, type Lantern, type LanternFocus } from '@/lib/lanterns'
import { FocusCard, type FocusUnit } from './FocusCard'

/**
 * The focus card (stage-view plan session 7): it offers what the lantern can take and nothing
 * else, a change answers the unit's whole focus, and a pair's lanterns are switched between —
 * each focused on its own.
 */
const lanterns = indexLanterns(libraryJson as Lantern[])
const s4 = lanterns.byId.get('s4-19')!
const zoom = lanterns.byId.get('s4-zoom-25-50')!
const cp62 = lanterns.byId.get('par64-cp62')!

function draw(units: FocusUnit[], active = 0) {
  const onChange = vi.fn<(index: number, next: LanternFocus) => void>()
  const onActiveChange = vi.fn<(index: number) => void>()
  render(<FocusCard units={units} active={active} onActiveChange={onActiveChange} onChange={onChange} />)
  return { onChange, onActiveChange }
}

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
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('FocusCard', () => {
  it('offers a profile its shutters, gate turn, iris and focus — and no lamp', () => {
    draw([{ key: 'f', label: 'This lantern', lantern: s4, focus: {} }])
    expect(screen.getByText('Source Four 19°')).toBeInTheDocument()
    for (const name of ['Top shutters depth', 'Right shutters angle', 'Gate rotation', 'Iris', 'Focus, sharp to soft']) {
      expect(screen.getByRole('slider', { name })).toBeInTheDocument()
    }
    expect(screen.queryByRole('slider', { name: 'Lamp rotation' })).toBeNull()
    expect(screen.queryByRole('slider', { name: 'Zoom' })).toBeNull()
  })

  it('moves one blade and answers all four, the others as they were', () => {
    const focus: LanternFocus = {
      shutters: [
        { depth: 0.2, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
        { depth: 0, angleDeg: 5 },
        { depth: 0, angleDeg: 0 },
      ],
    }
    const { onChange } = draw([{ key: 'f', label: 'This lantern', lantern: s4, focus }])
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Top shutters depth' }), { key: 'ArrowRight' })
    expect(onChange).toHaveBeenCalledTimes(1)
    const [index, next] = onChange.mock.calls[0]
    expect(index).toBe(0)
    expect(next.shutters).toEqual([
      { depth: 0.21, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 5 },
      { depth: 0, angleDeg: 0 },
    ])
  })

  it('takes every blade out at once, and says so only when one is in', () => {
    const { onChange } = draw([
      { key: 'f', label: 'This lantern', lantern: s4, focus: { shutters: [{ depth: 0.3, angleDeg: 0 }, ...Array(3).fill({ depth: 0, angleDeg: 0 })] } },
    ])
    fireEvent.click(screen.getByRole('button', { name: 'All out' }))
    expect(onChange.mock.calls[0][1].shutters).toBeNull()
    cleanup()
    draw([{ key: 'f', label: 'This lantern', lantern: s4, focus: {} }])
    expect(screen.getByRole('button', { name: 'All out' })).toBeDisabled()
  })

  it('offers a zoom profile its range, and a PAR its lamp turn and nothing to cut', () => {
    const zoomed = draw([{ key: 'f', label: 'This lantern', lantern: zoom, focus: {} }])
    const slider = screen.getByRole('slider', { name: 'Zoom' })
    expect(slider).toHaveAttribute('aria-valuemin', '25')
    expect(slider).toHaveAttribute('aria-valuemax', '50')
    fireEvent.keyDown(slider, { key: 'ArrowRight' })
    expect(zoomed.onChange.mock.calls[0][1].zoomDeg).toBe(zoom.fieldDeg + 1)
    cleanup()

    const par = draw([{ key: 'f', label: 'This lantern', lantern: cp62, focus: { lampRotationDeg: 30 } }])
    expect(screen.queryByRole('slider', { name: /shutters/ })).toBeNull()
    expect(screen.queryByRole('slider', { name: 'Iris' })).toBeNull()
    const lamp = screen.getByRole('slider', { name: 'Lamp rotation' })
    expect(lamp).toHaveAttribute('aria-valuenow', '30')
    fireEvent.keyDown(lamp, { key: 'ArrowLeft' })
    expect(par.onChange.mock.calls[0][1].lampRotationDeg).toBe(29)
  })

  it('switches between the lanterns of a pair, each with its own lantern and focus', () => {
    const { onActiveChange } = draw(
      [
        { key: 'f', label: 'Lantern 1', lantern: s4, focus: {} },
        { key: 'p', label: 'Lantern 2 · SR', lantern: cp62, focus: {} },
      ],
      1,
    )
    expect(screen.getByRole('tab', { name: 'Lantern 2 · SR' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Par 64 · CP62 MFL')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Lantern 1' }))
    expect(onActiveChange).toHaveBeenCalledWith(0)
  })

  it('says a lantern with nothing to focus has nothing to focus', () => {
    draw([{ key: 'f', label: 'This lantern', lantern: lanterns.byId.get('downlight')!, focus: {} }])
    expect(screen.getByText(/has nothing to focus/)).toBeInTheDocument()
  })
})
