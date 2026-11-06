// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'

afterEach(cleanup)

const renumber = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/cueStacks', () => ({ useRenumberCueStackEntriesMutation: () => [renumber, { isLoading: false }] }))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { RenumberCuesButton, renumberPreview, renumberProblem } from './RenumberCuesButton'

describe('renumberProblem', () => {
  it('takes whole numbers only, up to 9999', () => {
    expect(renumberProblem(3, '10', '1')).toBeNull()
    expect(renumberProblem(3, '0', '5')).toBeNull()
    expect(renumberProblem(3, '1.5', '1')).toMatch(/whole number/)
    expect(renumberProblem(3, '10', '0')).toMatch(/step/)
    expect(renumberProblem(3, '10', '0.5')).toMatch(/step/)
    expect(renumberProblem(3, '9998', '1')).toMatch(/Q10000/)
  })

  it('previews the numbers it writes', () => {
    expect(renumberPreview(3, 10, 10)).toBe('Q10, Q20, Q30')
    expect(renumberPreview(6, 1, 1)).toBe('Q1, Q2, Q3, …, Q6')
  })
})

describe('RenumberCuesButton', () => {
  it('starts from the first cue’s whole number and sends the entries with start and step', async () => {
    render(
      <RenumberCuesButton
        projectId={1}
        stackId={7}
        rows={[
          { entryId: 102, cueNumber: '4.5' },
          { entryId: 103, cueNumber: null },
        ]}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Renumber/ }))
    const first = screen.getByLabelText('First') as HTMLInputElement
    expect(first.value).toBe('4')
    // Focused and selected, so typing replaces the suggestion.
    expect(document.activeElement).toBe(first)
    expect([first.selectionStart, first.selectionEnd]).toEqual([0, 1])
    fireEvent.change(first, { target: { value: '20' } })
    fireEvent.change(screen.getByLabelText('Step'), { target: { value: '5' } })
    expect(screen.getByText('Q20, Q25')).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Renumber' }))
    })
    expect(renumber).toHaveBeenCalledWith({ projectId: 1, stackId: 7, entryIds: [102, 103], start: 20, step: 5 })
  })

  it('is disabled with the lock’s reason', () => {
    render(<RenumberCuesButton projectId={1} stackId={7} rows={[{ entryId: 1, cueNumber: '1' }]} disabledReason="Locked" />)
    expect(screen.getByRole('button', { name: /Renumber/ })).toBeDisabled()
  })
})
