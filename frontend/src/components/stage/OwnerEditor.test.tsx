// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'

/**
 * `OwnerEditor`'s cue arm: the fixture sheet's *on Q12* and the Stage view's *Moves with* open a cue
 * here (FxTray's and EditSceneElementForm's suites pin that they ask for it), and since
 * show-cue-editing plan session 4 that is the **cue editor** in a sheet, in place — Cue properties
 * retired (D15).
 */

vi.mock('@/components/cues/editor/CueEditor', () => ({
  CueEditor: (props: { cueId: number; host: string; locked: boolean; entryId?: number | null }) => (
    <div data-testid="cue-editor" data-cue-editor={props.cueId} data-host={props.host} data-locked={String(props.locked)} />
  ),
}))
vi.mock('@/components/cues/CueStackForm', () => ({ CueStackForm: () => null }))
vi.mock('@/components/looks/LookDetailSheet', () => ({ LookDetailSheet: () => null }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: [] }),
  useSaveProjectCueStackMutation: () => [vi.fn(), { isLoading: false }],
}))
vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({ data: [] }),
  useCopyLookMutation: () => [vi.fn()],
}))

import { OwnerEditor } from './OwnerEditor'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('OwnerEditor', () => {
  it('opens a cue in the cue editor, in a sheet over the view', () => {
    render(<OwnerEditor projectId={1} entry={{ kind: 'cue', id: 12 }} onClose={() => {}} />)
    const editor = screen.getByTestId('cue-editor')
    expect(editor).toHaveAttribute('data-cue-editor', '12')
    expect(editor).toHaveAttribute('data-host', 'sheet')
    // The show-editing lock is the Show view's; it does not reach a cue opened from outside it.
    expect(editor).toHaveAttribute('data-locked', 'false')
    expect(screen.getByRole('dialog')).toContainElement(editor)
  })

  it('opens nothing for no entry, or for another owner', () => {
    const view = render(<OwnerEditor projectId={1} entry={null} onClose={() => {}} />)
    expect(screen.queryByTestId('cue-editor')).toBeNull()
    view.rerender(<OwnerEditor projectId={1} entry={{ kind: 'look', id: 5 }} onClose={() => {}} />)
    expect(screen.queryByTestId('cue-editor')).toBeNull()
  })

  it('closes on Escape, returning to whatever it opened over', () => {
    const onClose = vi.fn()
    render(<OwnerEditor projectId={1} entry={{ kind: 'cue', id: 12 }} onClose={onClose} />)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})
