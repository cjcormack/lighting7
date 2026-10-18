// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { NOTHING_SAFE, type RigSafetyState } from '@/api/rigSafetyApi'

let rig: RigSafetyState = NOTHING_SAFE
let answer: Promise<unknown> = Promise.resolve({ rigWasSafe: true })
const quitDesk = vi.fn(() => ({ unwrap: () => answer }))
vi.mock('@/store/rigSafety', () => ({
  useRigSafety: () => rig,
  useQuitDeskMutation: () => [quitDesk, { isLoading: false }],
}))
const markDeskQuit = vi.fn()
vi.mock('./deskQuit', () => ({ markDeskQuit: (coolsAt: number | null) => markDeskQuit(coolsAt) }))

import { QuitDeskDialog } from './QuitDeskDialog'

afterEach(() => {
  cleanup()
  rig = NOTHING_SAFE
  answer = Promise.resolve({ rigWasSafe: true })
  vi.clearAllMocks()
})

describe('QuitDeskDialog', () => {
  it('quits a safe rig plainly', async () => {
    rig = { ...NOTHING_SAFE, ready: true, blackout: true }
    const onOpenChange = vi.fn()
    render(<QuitDeskDialog open onOpenChange={onOpenChange} />)
    expect(screen.queryByTestId('quit-not-safe')).toBeNull()
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Quit desk' })))
    expect(quitDesk).toHaveBeenCalledWith({ force: false })
    expect(markDeskQuit).toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('quits a cooling rig without force, and says how long to leave the power on', async () => {
    rig = { ...NOTHING_SAFE, blackout: true, coolingRemainingMs: 125_000, receivedAt: Date.now() }
    render(<QuitDeskDialog open onOpenChange={() => {}} />)
    expect(screen.queryByTestId('quit-not-safe')).toBeNull()
    expect(screen.getByTestId('quit-cooling').textContent).toContain('another 2:05')
    const before = Date.now()
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Quit desk' })))
    expect(quitDesk).toHaveBeenCalledWith({ force: false })
    const coolsAt = markDeskQuit.mock.calls[0][0] as number
    expect(coolsAt).toBeGreaterThanOrEqual(before + 120_000)
    expect(coolsAt).toBeLessThanOrEqual(Date.now() + 125_000)
  })

  it('warns when the rig is not marked safe, and quits anyway only on the press', async () => {
    render(<QuitDeskDialog open onOpenChange={() => {}} />)
    expect(screen.getByTestId('quit-not-safe').textContent).toContain('not marked safe')
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Quit anyway' })))
    expect(quitDesk).toHaveBeenCalledWith({ force: true })
  })

  it('shows a refusal in place and does not mark the desk quit', async () => {
    answer = Promise.reject({ status: 403, data: { error: 'The desk can only be quit from the desk itself', code: 'QUIT_LOCAL_ONLY' } })
    render(<QuitDeskDialog open onOpenChange={() => {}} />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Quit anyway' })))
    expect(markDeskQuit).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog').textContent).toContain('only be quit from the desk itself')
  })
})
