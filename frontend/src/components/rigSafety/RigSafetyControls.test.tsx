// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { NOTHING_SAFE, type RigSafetyState } from '@/api/rigSafetyApi'
import type { CueTarget } from '@/api/cuesApi'

let rig: RigSafetyState = NOTHING_SAFE
let targets: CueTarget[] = []
let connected = true
const makeSafe = vi.fn()
const runBulk = vi.fn()
vi.mock('@/store/rigSafety', () => ({
  useRigSafety: () => rig,
  useMakeRigSafeMutation: () => [makeSafe],
  useRunBulkCommandMutation: () => [runBulk],
}))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 3 } }) }))
vi.mock('@/store/selection', () => ({ useSelectionPair: () => ({ targets, families: null }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => connected }))
vi.mock('./UndoRigSafeDialog', () => ({ UndoRigSafeDialog: () => <div data-testid="undo-dialog" /> }))

import { RigSafetyControls } from './RigSafetyControls'

afterEach(() => {
  cleanup()
  rig = NOTHING_SAFE
  targets = []
  connected = true
  vi.clearAllMocks()
})

const press = (name: RegExp | string) => fireEvent.click(screen.getByRole('button', { name }))

describe('RigSafetyControls', () => {
  it('makes the whole rig safe after the confirm, and only then', () => {
    render(<RigSafetyControls />)
    press(/Make rig safe/)
    expect(makeSafe).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog').textContent).toContain('Lamps off every unit with a lamp')
    expect(screen.queryByRole('radio', { name: /selection/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Make rig safe' }))
    expect(makeSafe).toHaveBeenCalledWith({ projectId: 3, targets: null })
  })

  it('can make only the selection safe', () => {
    targets = [{ type: 'group', key: 'movers' }]
    render(<RigSafetyControls />)
    press(/Make rig safe/)
    fireEvent.click(screen.getByRole('radio', { name: /selection/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Make rig safe' }))
    expect(makeSafe).toHaveBeenCalledWith({ projectId: 3, targets })
  })

  it('runs a bulk command over the selection or the rig from the Lamps menu, asked first', () => {
    targets = [{ type: 'fixture', key: 'mac1' }]
    render(<RigSafetyControls />)
    fireEvent.keyDown(screen.getByRole('button', { name: /Lamps/ }), { key: 'Enter' })
    fireEvent.click(screen.getAllByRole('menuitem', { name: /Selection \(1 selected target\)/ })[0])
    expect(screen.getByRole('alertdialog').textContent).toContain('one at a time')
    fireEvent.click(screen.getByRole('button', { name: 'Lamp on' }))
    expect(runBulk).toHaveBeenCalledWith({ projectId: 3, role: 'LAMP_ON', targets })
  })

  it('offers Undo only with something to undo, and is inert while a run goes or offline', () => {
    render(<RigSafetyControls />)
    expect(screen.queryByRole('button', { name: /Undo/ })).toBeNull()
    cleanup()
    rig = { ...NOTHING_SAFE, blackout: true, run: { id: 'r', role: 'LAMP_OFF', purpose: 'BULK', startedAt: 'x', units: [], finished: false } }
    render(<RigSafetyControls />)
    expect((screen.getByRole('button', { name: /Make rig safe/ }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: /Undo/ }) as HTMLButtonElement).disabled).toBe(true)
    cleanup()
    rig = { ...NOTHING_SAFE, blackout: true }
    connected = false
    render(<RigSafetyControls />)
    expect((screen.getByRole('button', { name: /Lamps/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})
