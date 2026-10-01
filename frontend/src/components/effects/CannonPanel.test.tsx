// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { EffectsArmState } from '@/api/effectsApi'
import type { TriggerPropertyDescriptor } from '@/store/fixtures'

let arm: EffectsArmState = { armed: false, armedUntilMs: null, rehearsal: false, spent: [], projectId: 1 }
let visSource = 'output'
const fire = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const setArm = vi.fn(() => ({ unwrap: () => Promise.resolve() }))

vi.mock('@/store/effects', () => ({
  useEffectsArm: () => arm,
  useArmEffectsMutation: () => [setArm, { isLoading: false }],
  useFireTriggerMutation: () => [fire],
  useReloadTriggerMutation: () => [vi.fn(), { isLoading: false }],
}))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 1 } }) }))
vi.mock('@/store/patches', () => ({ useVisiblePatchListQuery: () => ({ data: [{ id: 7, key: 'cannon' }] }) }))
vi.mock('@/hooks/useVisSource', () => ({ useVisSource: () => visSource }))

import { CannonPanel, HOLD_TO_FIRE_MS } from './CannonPanel'

const trigger = (name: string, label: string): TriggerPropertyDescriptor => ({
  type: 'trigger', name, displayName: `Tube ${label}`, category: 'trigger', label,
  channel: { universe: 0, channelNo: 1 }, armChannel: { universe: 0, channelNo: 3 }, armName: 'master',
})
const TUBES = [trigger('output1', 'A'), trigger('output2', 'B')]

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance', 'setInterval', 'clearInterval', 'Date'] })
  fire.mockClear()
  setArm.mockClear()
  arm = { armed: false, armedUntilMs: null, rehearsal: false, spent: [], projectId: 1 }
  visSource = 'output'
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

function hold(button: HTMLElement, ms: number) {
  fireEvent.pointerDown(button, { pointerId: 1 })
  act(() => {
    vi.advanceTimersByTime(ms)
  })
  fireEvent.pointerUp(button, { pointerId: 1 })
}

describe('CannonPanel', () => {
  it('waits for the arm: disarmed, the tubes say so and nothing fires', () => {
    render(<CannonPanel fixtureKey="cannon" triggers={TUBES} canFire />)
    const a = screen.getByRole('button', { name: 'Arm to fire A' })
    expect((a as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: /Arm · 60 s/ }))
    expect(setArm).toHaveBeenCalledWith({ projectId: 1, on: true })
  })

  it('fires only on a hold, never a brush, and a spent tube is inert', () => {
    arm = { armed: true, armedUntilMs: performance.now() + 42_000, rehearsal: false, spent: [{ fixture: 'cannon', trigger: 'output2', spentAt: '2026-10-01 21:42:00.000Z' }], projectId: 1 }
    render(<CannonPanel fixtureKey="cannon" triggers={TUBES} canFire />)
    expect(screen.getByText(/Disarm · 42 s/)).toBeTruthy()
    const a = screen.getByRole('button', { name: 'Hold to fire A' })
    hold(a, 200)
    expect(fire).not.toHaveBeenCalled()
    hold(a, HOLD_TO_FIRE_MS + 50)
    expect(fire).toHaveBeenCalledTimes(1)
    expect(fire).toHaveBeenCalledWith({ projectId: 1, patchId: 7, trigger: 'output1', rehearse: false })
    const b = screen.getByRole('button', { name: 'B spent' })
    expect((b as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText(/spent · /)).toBeTruthy()
  })

  it('rehearses from a window whose vis source is the programmer, with no arm', () => {
    visSource = 'programmer'
    render(<CannonPanel fixtureKey="cannon" triggers={TUBES} canFire />)
    hold(screen.getByRole('button', { name: 'Hold to rehearse A' }), HOLD_TO_FIRE_MS + 50)
    expect(fire).toHaveBeenCalledWith({ projectId: 1, patchId: 7, trigger: 'output1', rehearse: true })
  })
})
