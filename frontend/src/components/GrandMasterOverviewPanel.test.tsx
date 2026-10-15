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

const state = vi.hoisted(() => ({ level: 100, connected: true }))
const setLevel = vi.hoisted(() => vi.fn())

vi.mock('@/store/grandMaster', () => ({
  useGrandMasterLevel: () => state.level,
  setGrandMasterLevel: setLevel,
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => state.connected }))

import { GrandMasterChip, GrandMasterControl, GrandMasterImmersiveChip, levelPercent } from './GrandMasterOverviewPanel'

afterEach(() => {
  cleanup()
  setLevel.mockClear()
  state.level = 100
  state.connected = true
})

describe('GrandMasterControl', () => {
  it('draws the fader at the level, and Out and Full set it', () => {
    state.level = 62.4
    render(<GrandMasterControl />)
    expect(screen.getByRole('slider', { name: 'Grand master' }).getAttribute('aria-valuenow')).toBe('62.4')
    expect(screen.getByText('62%').className).toMatch(/amber/)
    fireEvent.click(screen.getByRole('button', { name: 'Out' }))
    expect(setLevel).toHaveBeenLastCalledWith(0)
    // Held at what was pressed until the desk's frame agrees, rather than snapping back to 62.
    expect(screen.getByText('0%')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Full' }))
    expect(setLevel).toHaveBeenLastCalledWith(100)
  })

  it('writes nothing while the desk is offline', () => {
    state.connected = false
    render(<GrandMasterControl />)
    expect((screen.getByRole('button', { name: 'Out' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByRole('slider', { name: 'Grand master' }).getAttribute('data-disabled')).not.toBeNull()
  })
})

describe('the header chip', () => {
  it('names the level, plain at full, amber below, red at zero, and only toggles the panel', () => {
    const onToggle = vi.fn()
    const { rerender } = render(<GrandMasterChip isVisible={false} onToggle={onToggle} />)
    const chip = screen.getByRole('button', { name: 'Grand master, 100%' })
    expect(chip.className).not.toMatch(/amber|red/)
    expect(chip.getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(chip)
    expect(onToggle).toHaveBeenCalledTimes(1)
    expect(setLevel).not.toHaveBeenCalled()

    state.level = 40
    rerender(<GrandMasterChip isVisible onToggle={onToggle} />)
    const dim = screen.getByRole('button', { name: 'Grand master, 40%' })
    expect(dim.className).toMatch(/amber/)
    expect(dim.getAttribute('aria-pressed')).toBe('true')

    state.level = 0
    rerender(<GrandMasterChip isVisible onToggle={onToggle} />)
    expect(screen.getByRole('button', { name: 'Grand master, 0%' }).className).toMatch(/red/)
  })
})

describe('levelPercent', () => {
  it('never reads as an end the level is not at', () => {
    expect(levelPercent(100)).toBe(100)
    expect(levelPercent(99.6)).toBe(99)
    expect(levelPercent(49.5)).toBe(50)
    expect(levelPercent(0.4)).toBe(1)
    expect(levelPercent(0)).toBe(0)
  })

  it('styles from the raw level: 99.6 is amber, 0.4 is not red', () => {
    state.level = 99.6
    const { rerender } = render(<GrandMasterChip isVisible={false} onToggle={() => {}} />)
    expect(screen.getByRole('button', { name: 'Grand master, 99%' }).className).toMatch(/amber/)
    state.level = 0.4
    rerender(<GrandMasterChip isVisible={false} onToggle={() => {}} />)
    const low = screen.getByRole('button', { name: 'Grand master, 1%' })
    expect(low.className).toMatch(/amber/)
    expect(low.className).not.toMatch(/red/)
  })
})

describe('the immersive readout', () => {
  it('says nothing at full and the level below it', () => {
    const { rerender } = render(<GrandMasterImmersiveChip />)
    expect(screen.queryByTitle('Grand master below full')).toBeNull()
    state.level = 40
    rerender(<GrandMasterImmersiveChip />)
    expect(screen.getByTitle('Grand master below full').textContent).toBe('GM40%')
    state.level = 99.6
    rerender(<GrandMasterImmersiveChip />)
    expect(screen.getByTitle('Grand master below full').textContent).toBe('GM99%')
  })
})
