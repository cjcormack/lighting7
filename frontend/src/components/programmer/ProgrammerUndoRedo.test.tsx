// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EMPTY_HISTORY, type HistoryState, type HistoryStep } from '@/api/historyApi'

let history: HistoryState = EMPTY_HISTORY
const undoMutation = vi.fn()
const redoMutation = vi.fn()
vi.mock('@/store/history', () => ({
  useHistoryState: () => history,
  useHistoryUndoMutation: () => [undoMutation],
  useHistoryRedoMutation: () => [redoMutation],
}))

import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import { ProgrammerUndoRedo } from './ProgrammerUndoRedo'

const step = (id: string, label: string): HistoryStep => ({
  id,
  label,
  at: '2026-11-02 14:02:31.000Z',
  source: { kind: 'DESK', name: 'Desk' },
})

function withSteps(undo: HistoryStep[], redo: HistoryStep[] = [], projectId = 1) {
  history = {
    projectId,
    revision: 3,
    showRevision: 0,
    undo: undo[0] ? { id: undo[0].id, label: undo[0].label, kind: 'PROGRAMMER' } : null,
    redo: redo[0] ? { id: redo[0].id, label: redo[0].label, kind: 'PROGRAMMER' } : null,
    programmer: { undo, redo },
  }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  history = EMPTY_HISTORY
  window.localStorage.clear()
  resetProgrammerFadeStore()
  document.body.innerHTML = ''
})

describe('ProgrammerUndoRedo', () => {
  it('is disabled with nothing to take, and names the step it takes', () => {
    render(<ProgrammerUndoRedo projectId={1} />)
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Redo' })).toBeDisabled()
    cleanup()

    withSteps([step('p-2', 'Clear'), step('p-1', 'hex-1 dimmer')], [step('p-3', 'Fan')])
    render(<ProgrammerUndoRedo projectId={1} />)
    expect(screen.getByRole('button', { name: 'Undo Clear' })).not.toBeDisabled()
    expect(screen.getByRole('button', { name: 'Redo Fan' })).not.toBeDisabled()
  })

  it('undoes and redoes at the programmer fade', () => {
    withSteps([step('p-2', 'Clear')], [step('p-3', 'Fan')])
    setProgrammerFade('1000')
    render(<ProgrammerUndoRedo projectId={1} />)
    fireEvent.click(screen.getByRole('button', { name: 'Undo Clear' }))
    expect(undoMutation).toHaveBeenCalledWith({ projectId: 1, entryId: undefined, fadeMs: 1000 })
    fireEvent.click(screen.getByRole('button', { name: 'Redo Fan' }))
    expect(redoMutation).toHaveBeenCalledWith({ projectId: 1, entryId: undefined, fadeMs: 1000 })
  })

  it("reads another project's history as empty", () => {
    withSteps([step('p-2', 'Clear')], [], 7)
    render(<ProgrammerUndoRedo projectId={1} />)
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
  })

  it('lists the last steps on a right-click, and picking one goes back to before it', () => {
    withSteps([step('p-3', 'Fan'), step('p-2', 'Clear'), step('p-1', 'hex-1 dimmer')])
    render(<ProgrammerUndoRedo projectId={1} />)
    fireEvent.contextMenu(screen.getByRole('button', { name: 'Undo Fan' }))
    expect(screen.getByText('Go back to before')).toBeTruthy()
    fireEvent.click(screen.getByRole('menuitem', { name: /hex-1 dimmer/ }))
    expect(undoMutation).toHaveBeenCalledWith({ projectId: 1, entryId: 'p-1', fadeMs: 0 })
  })
})
