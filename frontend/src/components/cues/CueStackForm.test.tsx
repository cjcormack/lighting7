// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueStack, StackMoveInDarkPlan } from '@/api/cueStacksApi'

/**
 * The stack sheet's *Move in dark* block (move-in-dark plan session 4, design §5.1): what Save sends,
 * what it refuses, and the plan's read-out. The sheet is never open while the show is locked — its
 * doors (the stack row's ⋮, *Stack settings…*) are unlocked gestures — so the block has no locked arm.
 */
const plan: { data: StackMoveInDarkPlan | undefined } = { data: undefined }
vi.mock('@/store/cueStacks', () => ({ useCueStackMoveInDarkQuery: () => plan }))
vi.mock('@/store/scenery', () => ({ useSetStackSceneryMutation: () => [vi.fn()] }))
vi.mock('@/components/scenery/SceneryEditor', () => ({ SceneryEditor: () => null }))

import { CueStackForm } from './CueStackForm'

const STACK: CueStack = {
  id: 7,
  name: 'Main',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
  moveInDark: { on: true, when: 'EARLY', delayMs: 1250, timeMs: 2000, families: ['POSITION', 'COLOUR', 'BEAM'] },
}

const onSave = vi.fn(() => Promise.resolve())
const onOpenChange = vi.fn()

function draw(stack: CueStack | null, extra: Partial<React.ComponentProps<typeof CueStackForm>> = {}) {
  return render(
    <CueStackForm open onOpenChange={onOpenChange} projectId={1} stack={stack} onSave={onSave} isSaving={false} {...extra} />,
  )
}

const save = () => screen.getByRole('button', { name: /^(Save|Create)$/ })

beforeEach(() => {
  plan.data = undefined
  onSave.mockClear()
  onOpenChange.mockClear()
})
afterEach(cleanup)

describe('CueStackForm · Move in dark', () => {
  it('a new stack starts on at the defaults, and Create sends them', async () => {
    draw(null)
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Act 2' } })
    expect(screen.getByRole('switch', { name: 'Move in dark' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(save())
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    expect(onSave.mock.calls[0]).toEqual([
      { name: 'Act 2', loop: false, moveInDark: { on: true, when: 'EARLY', delayMs: 1000, timeMs: 2000, families: ['POSITION', 'COLOUR', 'BEAM'] } },
    ])
  })

  it('a Save that leaves the block alone sends no setting, so a stored 1.25 s is not rounded', async () => {
    draw(STACK)
    expect(screen.getByLabelText('Delay')).toHaveValue('1.3s')
    fireEvent.click(save())
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    expect(onSave.mock.calls[0]).toEqual([{ name: 'Main', loop: false }])
  })

  it('writes When, the times and the families it was given', async () => {
    draw(STACK)
    fireEvent.click(screen.getByRole('radio', { name: 'Late' }))
    fireEvent.change(screen.getByLabelText('Delay'), { target: { value: '0.5' } })
    fireEvent.change(screen.getByLabelText('Move time'), { target: { value: '3s' } })
    fireEvent.click(screen.getByRole('button', { name: 'Colour' }))
    fireEvent.click(save())
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    expect(onSave.mock.calls[0]).toEqual([
      { name: 'Main', loop: false, moveInDark: { on: true, when: 'LATE', delayMs: 500, timeMs: 3000, families: ['POSITION', 'BEAM'] } },
    ])
  })

  it('refuses a time out of range or no family, saying which', () => {
    draw(STACK)
    fireEvent.change(screen.getByLabelText('Delay'), { target: { value: '45' } })
    expect(screen.getByText('A delay is 0 to 30 seconds')).toBeInTheDocument()
    expect(save()).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Delay'), { target: { value: '1' } })
    for (const name of ['Position', 'Colour', 'Beam']) fireEvent.click(screen.getByRole('button', { name }))
    expect(screen.getByText('Pick at least one of Position, Colour and Beam')).toBeInTheDocument()
    expect(save()).toBeDisabled()
  })

  it('turned off, it hides the rest and sends off, the untouched times as stored', async () => {
    draw(STACK)
    fireEvent.click(screen.getByRole('switch', { name: 'Move in dark' }))
    expect(screen.queryByLabelText('Delay')).toBeNull()
    fireEvent.click(save())
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    expect(onSave.mock.calls[0]).toEqual([
      { name: 'Main', loop: false, moveInDark: { on: false, when: 'EARLY', delayMs: 1250, timeMs: 2000, families: ['POSITION', 'COLOUR', 'BEAM'] } },
    ])
  })

  it('reads out the stored plan, and its press opens the cue table on those cues', () => {
    plan.data = { stackId: 7, rigLoaded: true, entries: [], summary: { heads: 38, cues: 14, live: 3, off: 2 } }
    const onShowDarkCues = vi.fn()
    draw(STACK, { onShowDarkCues })
    const readout = document.querySelector('[data-move-in-dark-readout]') as HTMLElement
    expect(readout).toHaveTextContent('38 heads in 14 cues')
    expect(readout).toHaveTextContent('3 move lit')
    expect(readout).toHaveTextContent('2 cues off')
    fireEvent.click(readout)
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(onShowDarkCues).toHaveBeenCalledWith(7)
  })

  it('says why there is no count off the current project', () => {
    plan.data = { stackId: 7, rigLoaded: false, entries: [], summary: { heads: 0, cues: 0, live: 0, off: 0 } }
    draw(STACK)
    expect(screen.getByText(/counted once its project is current/)).toBeInTheDocument()
  })

  it('switched off over a bad time, Save is free and writes only off', async () => {
    draw(STACK)
    fireEvent.change(screen.getByLabelText('Delay'), { target: { value: '45' } })
    fireEvent.click(screen.getByRole('switch', { name: 'Move in dark' }))
    expect(save()).toBeEnabled()
    fireEvent.click(save())
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    expect(onSave.mock.calls[0]).toEqual([{ name: 'Main', loop: false, moveInDark: { on: false } }])
  })

  it('keeps what is typed when the same stack is re-read, and starts afresh on another', () => {
    const { rerender } = draw(STACK)
    fireEvent.change(screen.getByLabelText('Delay'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Main 2' } })
    // A GO hands the host a new copy of the same stack.
    rerender(<CueStackForm open onOpenChange={onOpenChange} projectId={1} stack={{ ...STACK, activeEntryId: 5 }} onSave={onSave} isSaving={false} />)
    expect(screen.getByLabelText('Delay')).toHaveValue('3')
    expect(screen.getByLabelText('Name')).toHaveValue('Main 2')
    rerender(<CueStackForm open onOpenChange={onOpenChange} projectId={1} stack={{ ...STACK, id: 8, name: 'Act 2' }} onSave={onSave} isSaving={false} />)
    expect(screen.getByLabelText('Delay')).toHaveValue('1.3s')
    expect(screen.getByLabelText('Name')).toHaveValue('Act 2')
  })

  it('a separator has no block', () => {
    draw({ ...STACK, type: 'SEPARATOR' })
    expect(document.querySelector('[data-move-in-dark-block]')).toBeNull()
  })
})
