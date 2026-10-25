// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

// Radix's slider measures its thumb with `ResizeObserver`, which jsdom lacks.
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)

const state = vi.hoisted(() => ({
  sizes: {} as Record<number, number>,
  connected: true,
  effects: [] as { cueId: number | null }[],
  holds: [] as { cueId: number; topStackId: number | null }[],
}))
const setSize = vi.hoisted(() => vi.fn())

vi.mock('@/store/stackFxAmount', () => ({
  useStackFxAmount: (stackId: number) => state.sizes[stackId] ?? 1,
  setStackFxAmount: setSize,
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => state.connected }))
vi.mock('@/store/fixtureFx', () => ({ useActiveFxQuery: () => ({ data: state.effects }) }))
vi.mock('@/store/cueHolds', () => ({
  useCueHolds: (projectId: number) => ({ projectId, cues: state.holds }),
}))

import { MobileStackFxAmount, StackFxAmountControl } from './StackFxAmountControl'

afterEach(() => {
  cleanup()
  setSize.mockClear()
  state.sizes = {}
  state.connected = true
  state.effects = []
  state.holds = []
})

describe('StackFxAmountControl', () => {
  it("reads the desk's size as a percent, amber below full, and writes 0–1 for its stack", () => {
    state.sizes = { 4: 0.6 }
    render(<StackFxAmountControl stackId={4} stackName="Act 1" />)
    const slider = screen.getByRole('slider', { name: 'FX amount, Act 1' })
    expect(slider.getAttribute('aria-valuenow')).toBe('60')
    expect(screen.getByText('60%').className).toMatch(/amber/)

    fireEvent.keyDown(slider, { key: 'ArrowRight' })
    expect(setSize).toHaveBeenLastCalledWith(4, 0.61)
  })

  it("is a press of its own, never the stack row's under it", () => {
    const onRow = vi.fn()
    render(
      <div onClick={onRow} onPointerDown={onRow}>
        <StackFxAmountControl stackId={4} stackName="Act 1" />
      </div>,
    )
    // On the label, not the thumb: Radix's thumb asks for pointer capture, which jsdom lacks.
    fireEvent.pointerDown(screen.getByText('FX amount'))
    fireEvent.click(screen.getByText('FX amount'))
    expect(onRow).not.toHaveBeenCalled()
  })

  it('greys out while the desk is offline', () => {
    state.connected = false
    render(<StackFxAmountControl stackId={4} stackName="Act 1" />)
    expect(screen.getByRole('slider', { name: 'FX amount, Act 1' }).getAttribute('data-disabled')).not.toBeNull()
  })
})

describe('the phone runner row', () => {
  it('is hidden at 100 % for a stack running no effect', () => {
    state.holds = [{ cueId: 21, topStackId: 9 }]
    state.effects = [{ cueId: 21 }, { cueId: null }]
    render(<MobileStackFxAmount projectId={1} stackId={4} stackName="Act 1" />)
    expect(screen.queryByRole('slider')).toBeNull()
  })

  it('is drawn at 100 % while an effect of a cue it weighs runs', () => {
    state.holds = [{ cueId: 21, topStackId: 4 }]
    state.effects = [{ cueId: 21 }]
    render(<MobileStackFxAmount projectId={1} stackId={4} stackName="Act 1" />)
    expect(screen.getByRole('slider', { name: 'FX amount, Act 1' })).toBeTruthy()
  })

  it('is hidden for a cue it holds but another stack weighs, or a press holds loose', () => {
    state.holds = [
      { cueId: 21, topStackId: 9 },
      { cueId: 22, topStackId: null },
    ]
    state.effects = [{ cueId: 21 }, { cueId: 22 }]
    render(<MobileStackFxAmount projectId={1} stackId={4} stackName="Act 1" />)
    expect(screen.queryByRole('slider')).toBeNull()
  })

  it('is drawn below full whatever runs, so a size left down is never out of reach', () => {
    state.sizes = { 4: 0.3 }
    render(<MobileStackFxAmount projectId={1} stackId={4} stackName="Act 1" />)
    expect(screen.getByRole('slider', { name: 'FX amount, Act 1' }).getAttribute('aria-valuenow')).toBe('30')
  })
})
