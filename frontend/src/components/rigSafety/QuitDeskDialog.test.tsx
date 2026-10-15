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
vi.mock('./deskQuit', () => ({ markDeskQuit: () => markDeskQuit() }))

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
