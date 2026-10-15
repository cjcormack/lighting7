// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { NOTHING_SAFE, type RigSafetyState } from '@/api/rigSafetyApi'

let rig: RigSafetyState = NOTHING_SAFE
const cancel = vi.fn()
const release = vi.fn()
vi.mock('@/store/rigSafety', () => ({
  useRigSafety: () => rig,
  useCancelBulkCommandMutation: () => [cancel],
  useReleaseRigSafeMutation: () => [release],
}))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 1 } }) }))
vi.mock('./QuitDeskDialog', () => ({ QuitDeskDialog: () => <div data-testid="quit-dialog" /> }))
vi.mock('./UndoRigSafeDialog', () => ({ UndoRigSafeDialog: () => <div data-testid="undo-dialog" /> }))

import { RigSafetyBanner, RigSafetyImmersiveChip } from './RigSafetyBanner'

const at = (over: Partial<RigSafetyState>) => {
  rig = { ...NOTHING_SAFE, projectId: 1, receivedAt: Date.now(), ...over }
}

afterEach(() => {
  cleanup()
  rig = NOTHING_SAFE
  vi.clearAllMocks()
})

describe('RigSafetyBanner', () => {
  it('draws nothing when there is nothing to say, or for another project', () => {
    at({})
    const { container } = render(<RigSafetyBanner />)
    expect(container.innerHTML).toBe('')
    cleanup()
    at({ projectId: 2, ready: true, blackout: true })
    expect(render(<RigSafetyBanner />).container.innerHTML).toBe('')
  })

  it('says ready to switch off, asks before Undo, and offers Quit only where this window may quit', () => {
    at({ ready: true, blackout: true, undo: [{ fixture: 'mac1', fixtureName: 'MAC 1' }, { fixture: 'mac2', fixtureName: 'MAC 2' }] })
    render(<RigSafetyBanner />)
    const banner = screen.getByRole('status', { name: 'Rig safety' })
    expect(banner.getAttribute('data-rig-safety')).toBe('ready')
    expect(banner.textContent).toContain('Ready to switch off')
    expect(banner.textContent).toContain('2 lamps off')
    expect(screen.queryByRole('button', { name: /Quit desk/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    expect(screen.getByTestId('undo-dialog')).toBeTruthy()
    cleanup()

    at({ ready: true, blackout: true, quitAllowed: true })
    render(<RigSafetyBanner />)
    fireEvent.click(screen.getByRole('button', { name: /Quit desk/ }))
    expect(screen.getByTestId('quit-dialog')).toBeTruthy()
  })

  it('says why a blackout is not ready, and releases it', () => {
    at({ blackout: true, notOff: ['MAC 2'] })
    render(<RigSafetyBanner />)
    expect(screen.getByRole('status').textContent).toContain('a lamp is not known to be off: MAC 2')
    fireEvent.click(screen.getByRole('button', { name: 'Release blackout' }))
    expect(release).toHaveBeenCalledWith({ projectId: 1 })
  })

  it('follows a run, lists its units, and cancels it', () => {
    at({
      run: {
        id: 'r', role: 'LAMP_ON', purpose: 'UNDO', startedAt: 'x', finished: false,
        units: [
          { fixture: 'mac1', fixtureName: 'MAC 1', command: 'lampOn', status: 'DONE' },
          { fixture: 'mac2', fixtureName: 'MAC 2', command: 'lampOn', status: 'WAITING', remainingMs: 252_000 },
        ],
      },
    })
    render(<RigSafetyBanner />)
    expect(screen.getByRole('status').textContent).toContain('Undoing Make rig safe — 1 of 2 done · MAC 2 in 4:12 — restrike')
    fireEvent.click(screen.getByRole('button', { name: 'Details' }))
    expect(screen.getByTestId('rig-run-units').textContent).toContain('Restrike in 4:12')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(cancel).toHaveBeenCalledWith({ projectId: 1 })
  })

  it('keeps a run that ended short until this window dismisses it', () => {
    at({
      run: {
        id: 'r', role: 'LAMP_OFF', purpose: 'BULK', startedAt: 'x', finished: true, withoutCommand: 3,
        units: [{ fixture: 'mac1', fixtureName: 'MAC 1', command: 'lampOff', status: 'SKIPPED', code: 'COMMAND_BUSY' }],
      },
    })
    render(<RigSafetyBanner />)
    expect(screen.getByRole('status').textContent).toContain('0 done · 1 skipped · 3 selected had no such command')
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByRole('status')).toBeNull()
  })
})

describe('RigSafetyImmersiveChip', () => {
  it('reads ready, blacked out or a run, and nothing otherwise', () => {
    at({})
    expect(render(<RigSafetyImmersiveChip />).container.innerHTML).toBe('')
    cleanup()
    at({ ready: true, blackout: true })
    render(<RigSafetyImmersiveChip />)
    expect(screen.getByText('Ready to switch off')).toBeTruthy()
    cleanup()
    at({ blackout: true })
    render(<RigSafetyImmersiveChip />)
    expect(screen.getByText('Blacked out')).toBeTruthy()
  })
})
