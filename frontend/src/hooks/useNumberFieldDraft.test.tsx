// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { useNumberFieldDraft } from './useNumberFieldDraft'

/**
 * The one rule both numeric channel fields share: an emptied box on its way to being retyped must
 * not commit, because `Number('')` is 0 and these fields write live to a channel.
 */
function Field({ display, onNumber }: { display: string; onNumber: (n: number) => void }) {
  const draft = useNumberFieldDraft(display, onNumber)
  return (
    <>
      <input
        aria-label="field"
        value={draft.value}
        onChange={(e) => draft.onChange(e.target.value)}
        onBlur={draft.onBlur}
      />
      <button type="button" onClick={draft.reset}>
        reset
      </button>
    </>
  )
}

describe('useNumberFieldDraft', () => {
  it('commits what parses, unclamped — the caller owns the range', () => {
    const onNumber = vi.fn()
    render(<Field display="0" onNumber={onNumber} />)
    const field = screen.getByLabelText('field')
    fireEvent.change(field, { target: { value: '200' } })
    fireEvent.change(field, { target: { value: '9000' } })
    fireEvent.change(field, { target: { value: '-4' } })
    expect(onNumber.mock.calls).toEqual([[200], [9000], [-4]])
  })

  it('commits nothing for an empty or unreadable field, and keeps the text on screen', () => {
    const onNumber = vi.fn()
    render(<Field display="200" onNumber={onNumber} />)
    const field = screen.getByLabelText('field') as HTMLInputElement
    fireEvent.change(field, { target: { value: '' } })
    fireEvent.change(field, { target: { value: '  ' } })
    fireEvent.change(field, { target: { value: 'x' } })
    expect(onNumber).not.toHaveBeenCalled()
    expect(field.value).toBe('x')
  })

  it('falls back to the display value on blur', () => {
    render(<Field display="200" onNumber={vi.fn()} />)
    const field = screen.getByLabelText('field') as HTMLInputElement
    fireEvent.change(field, { target: { value: '' } })
    fireEvent.blur(field)
    expect(field.value).toBe('200')
  })

  it('drops the draft on an explicit reset, for an editor that reopens on the same node', () => {
    render(<Field display="200" onNumber={vi.fn()} />)
    const field = screen.getByLabelText('field') as HTMLInputElement
    fireEvent.change(field, { target: { value: '7' } })
    expect(field.value).toBe('7')
    fireEvent.click(screen.getByText('reset'))
    expect(field.value).toBe('200')
  })
})
