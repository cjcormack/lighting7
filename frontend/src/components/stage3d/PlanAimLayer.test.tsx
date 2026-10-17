// @vitest-environment jsdom
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSectionViewStore, type SectionControls } from './edit/sectionView'
import { PlanAimLayer } from './PlanAimLayer'

/**
 * Aiming on the Plan (venue-visit plan S13): the layer over the canvas turns a click into stage
 * metres through the Plan's own section view, pans on a drag and zooms on the wheel — so the click
 * never reaches the fixture or scenery picking underneath.
 */

// An 800 × 600 canvas at the window's origin, looking at x 0, y 4 (v −4) at 20 px a metre.
const RECT = { left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600, x: 0, y: 0, toJSON: () => ({}) }

let controls: SectionControls
beforeEach(() => {
  controls = { panBy: vi.fn(), zoomAt: vi.fn(), fit: vi.fn() }
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(RECT as DOMRect)
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function mount(marker: { x: number; y: number } | null = null) {
  const store = createSectionViewStore()
  store.set({ h: 0, v: -4, zoom: 20, width: 800, height: 600 })
  const onPoint = vi.fn()
  const view = render(<PlanAimLayer viewStore={store} controls={() => controls} onPoint={onPoint} marker={marker} />)
  const layer = view.container.querySelector('[data-plan-aim]') as HTMLElement
  return { layer, onPoint, store }
}

function click(el: HTMLElement, x: number, y: number) {
  fireEvent.pointerDown(el, { pointerId: 1, button: 0, clientX: x, clientY: y })
  fireEvent.pointerUp(el, { pointerId: 1, button: 0, clientX: x, clientY: y })
}

describe('PlanAimLayer', () => {
  it('turns a click into a point on the stage, in metres', () => {
    const { layer, onPoint } = mount()
    // 100 px right of centre and 40 px above it: 5 m audience-right, 2 m further upstage.
    click(layer, 500, 260)
    expect(onPoint).toHaveBeenCalledTimes(1)
    expect(onPoint.mock.calls[0][0].x).toBeCloseTo(5, 9)
    expect(onPoint.mock.calls[0][0].y).toBeCloseTo(6, 9)
  })

  it('reaches a point far off the rig — the house, a wall', () => {
    const { layer, onPoint } = mount()
    // The bottom-left corner of the canvas: 20 m stage left of centre and 11 m into the house.
    click(layer, 0, 600)
    expect(onPoint.mock.calls[0][0].x).toBeCloseTo(-20, 9)
    expect(onPoint.mock.calls[0][0].y).toBeCloseTo(-11, 9)
  })

  it('pans on a drag and aims nothing', () => {
    const { layer, onPoint } = mount()
    fireEvent.pointerDown(layer, { pointerId: 1, button: 0, clientX: 400, clientY: 300 })
    fireEvent.pointerMove(layer, { pointerId: 1, button: 0, clientX: 430, clientY: 290 })
    fireEvent.pointerUp(layer, { pointerId: 1, button: 0, clientX: 430, clientY: 290 })
    expect(controls.panBy).toHaveBeenCalledWith(30, -10)
    expect(onPoint).not.toHaveBeenCalled()
  })

  it('aims on a press that barely moves, and not on a cancelled one', () => {
    const { layer, onPoint } = mount()
    fireEvent.pointerDown(layer, { pointerId: 1, button: 0, clientX: 400, clientY: 300 })
    fireEvent.pointerMove(layer, { pointerId: 1, button: 0, clientX: 402, clientY: 301 })
    fireEvent.pointerUp(layer, { pointerId: 1, button: 0, clientX: 402, clientY: 301 })
    expect(onPoint).toHaveBeenCalledTimes(1)
    expect(controls.panBy).not.toHaveBeenCalled()
    fireEvent.pointerDown(layer, { pointerId: 2, button: 0, clientX: 400, clientY: 300 })
    fireEvent.pointerCancel(layer, { pointerId: 2 })
    fireEvent.pointerUp(layer, { pointerId: 2, button: 0, clientX: 400, clientY: 300 })
    expect(onPoint).toHaveBeenCalledTimes(1)
  })

  it('aims nothing on a second finger — a pinch or a two-finger tap', () => {
    const { layer, onPoint } = mount()
    fireEvent.pointerDown(layer, { pointerId: 1, button: 0, clientX: 400, clientY: 300 })
    fireEvent.pointerDown(layer, { pointerId: 2, button: 0, clientX: 500, clientY: 300 })
    fireEvent.pointerUp(layer, { pointerId: 2, button: 0, clientX: 500, clientY: 300 })
    fireEvent.pointerUp(layer, { pointerId: 1, button: 0, clientX: 400, clientY: 300 })
    expect(onPoint).not.toHaveBeenCalled()
    // The next single press aims again.
    click(layer, 400, 300)
    expect(onPoint).toHaveBeenCalledTimes(1)
  })

  it('zooms about the pointer on the wheel', () => {
    const { layer } = mount()
    act(() => {
      layer.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, clientX: 200, clientY: 100, bubbles: true, cancelable: true }))
    })
    expect(controls.zoomAt).toHaveBeenCalledWith(expect.any(Number), 200, 100)
    expect((controls.zoomAt as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBeGreaterThan(1)
  })

  it('rings where the last aim went', () => {
    const { layer } = mount({ x: 5, y: 6 })
    const ring = layer.querySelector('[data-plan-aim-marker]') as HTMLElement
    expect(ring.style.left).toBe('calc(50% + 100px)')
    expect(ring.style.top).toBe('calc(50% - 40px)')
  })

  it('drops a click until the Plan has said where it looks', () => {
    const store = createSectionViewStore()
    const onPoint = vi.fn()
    const view = render(<PlanAimLayer viewStore={store} controls={() => controls} onPoint={onPoint} marker={null} />)
    click(view.container.querySelector('[data-plan-aim]') as HTMLElement, 400, 300)
    expect(onPoint).not.toHaveBeenCalled()
  })
})
