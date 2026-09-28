// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { EditorField } from './EditorField'

/**
 * The editor kit's one number field (editor-kit plan D9). Three things it promises, each pinned
 * with a literal: the draft rule, the unit glyph, and that the clamp is the caller's.
 */
describe('EditorField', () => {
  it('commits a typed number as typed — the clamp is the caller\'s, not the field\'s', () => {
    const onCommit = vi.fn()
    render(<EditorField label="Pan" value={0} onCommit={onCommit} min={0} max={255} />)
    const field = screen.getByRole('spinbutton', { name: 'Pan' })
    fireEvent.change(field, { target: { value: '900' } })
    fireEvent.change(field, { target: { value: '-4' } })
    fireEvent.change(field, { target: { value: '12.6' } })
    // Nothing rounded, nothing clamped: 900 goes out as 900 for the caller to range.
    expect(onCommit.mock.calls).toEqual([[900], [-4], [12.6]])
  })

  it('commits nothing while the box is empty mid-retype, and keeps the operator\'s text on screen', () => {
    const onCommit = vi.fn()
    render(<EditorField label="Level" value={200} onCommit={onCommit} />)
    const field = screen.getByRole('spinbutton', { name: 'Level' }) as HTMLInputElement
    fireEvent.change(field, { target: { value: '' } })
    expect(onCommit).not.toHaveBeenCalled()
    expect(field.value).toBe('')
    // A lone minus is what a browser reports as '' — nothing is committed and the field is not
    // snapped back to a number the operator did not type.
    fireEvent.change(field, { target: { value: '-' } })
    expect(onCommit).not.toHaveBeenCalled()
    fireEvent.change(field, { target: { value: '-5' } })
    expect(onCommit).toHaveBeenCalledWith(-5)
  })

  it('drops the typed text on blur, so the field shows what the desk holds', () => {
    render(<EditorField label="Level" value={200} onCommit={vi.fn()} />)
    const field = screen.getByRole('spinbutton', { name: 'Level' }) as HTMLInputElement
    fireEvent.change(field, { target: { value: '' } })
    fireEvent.blur(field)
    expect(field.value).toBe('200')
  })

  it('draws the unit inside the box as a muted glyph that is not part of the field\'s name', () => {
    const { container } = render(<EditorField label="Dimmer" unit="%" value={80} onCommit={vi.fn()} />)
    const glyph = container.querySelector('[data-editor-unit]')
    expect(glyph).not.toBeNull()
    expect(glyph).toHaveTextContent('%')
    expect(glyph).toHaveAttribute('aria-hidden', 'true')
    // The accessible name is the label alone — the glyph is decoration, and a screen reader
    // naming the field "Dimmer %" would be reading the box's chrome.
    expect(screen.getByRole('spinbutton', { name: 'Dimmer' })).toHaveValue(80)
    expect(screen.queryByRole('spinbutton', { name: 'Dimmer %' })).toBeNull()
  })

  it('is 28px tall — the busk tabs\' field, said once', () => {
    render(<EditorField label="Level" value={0} onCommit={vi.fn()} />)
    expect(screen.getByRole('spinbutton', { name: 'Level' })).toHaveClass('h-7')
  })

  it('lands a seed in the box as though it had been typed there, so it commits', () => {
    const onCommit = vi.fn()
    render(<EditorField label="Level" value={0} onCommit={onCommit} seed="5" />)
    expect(screen.getByRole('spinbutton', { name: 'Level' })).toHaveValue(5)
    expect(onCommit).toHaveBeenCalledWith(5)
  })

  it('reports the raw draft as it is typed, and null when the draft is dropped on blur', () => {
    const onDraft = vi.fn()
    render(<EditorField label="Start channel" value={41} onCommit={vi.fn()} onDraft={onDraft} />)
    const field = screen.getByRole('spinbutton', { name: 'Start channel' })
    fireEvent.change(field, { target: { value: '' } })
    expect(onDraft).toHaveBeenLastCalledWith('')
    fireEvent.change(field, { target: { value: '7' } })
    expect(onDraft).toHaveBeenLastCalledWith('7')
    fireEvent.blur(field)
    expect(onDraft).toHaveBeenLastCalledWith(null)
  })

  it('draws a prefix beside the box, and none where the row already names it', () => {
    const { rerender } = render(<EditorField label="R" prefix="R" value={0} onCommit={vi.fn()} />)
    expect(screen.getByText('R')).toBeInTheDocument()
    rerender(<EditorField label="W value" value={0} onCommit={vi.fn()} />)
    expect(screen.queryByText('W value')).toBeNull()
    expect(screen.getByRole('spinbutton', { name: 'W value' })).toBeInTheDocument()
  })
})
