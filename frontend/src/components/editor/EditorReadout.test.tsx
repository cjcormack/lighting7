// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EditorReadout } from './EditorReadout'

/**
 * The 10px read-out and its multi-line arm — the arm is what `LandingLines` was, and these are
 * that component's cases: one row to a line, each line titled with itself, the error under them.
 */
describe('EditorReadout', () => {
  it('draws the one-line read-out', () => {
    const { container } = render(
      <EditorReadout>
        <span>204 of 255 · 0–255 on every head</span>
      </EditorReadout>,
    )
    expect(container.querySelector('[data-editor-readout]')).toHaveTextContent('204 of 255 · 0–255 on every head')
  })

  it('renders nothing at all with nothing to say', () => {
    const { container } = render(<EditorReadout lines={[]} error={null} />)
    expect(container.querySelector('[data-editor-readout]')).toBeNull()
  })

  it('draws the landing one head to a line, each line titled with itself', () => {
    render(<EditorReadout lines={['Par 1 → 1-041', 'Par 2 → 1-047', 'Par 3 → 1-053']} />)
    const items = screen.getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual(['Par 1 → 1-041', 'Par 2 → 1-047', 'Par 3 → 1-053'])
    // The title carries the whole line, since a long one is truncated on screen.
    expect(items[2]).toHaveAttribute('title', 'Par 3 → 1-053')
  })

  it('keeps two heads with one name as two lines', () => {
    render(<EditorReadout lines={['Par → 1-001', 'Par → 1-007']} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  it('names the problem under the lines, in the destructive tone', () => {
    render(<EditorReadout lines={['Par 3 → 1-053']} error="Par 3 collides with Mover 1" />)
    const error = screen.getByText('Par 3 collides with Mover 1')
    expect(error.tagName).toBe('P')
    expect(error).toHaveClass('text-destructive')
    // Under, not above: the list is read first and the verdict last.
    const list = screen.getByRole('list')
    expect(list.compareDocumentPosition(error) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('draws an error alone where there are no lines', () => {
    render(<EditorReadout error="Start channel is 1–512" />)
    expect(screen.getByText('Start channel is 1–512')).toHaveClass('text-destructive')
    expect(screen.queryByRole('list')).toBeNull()
  })

  it('scrolls a long landing rather than growing without limit', () => {
    render(<EditorReadout lines={Array.from({ length: 40 }, (_, i) => `Head ${i + 1} → 1-${String(i + 1).padStart(3, '0')}`)} />)
    expect(screen.getByRole('list')).toHaveClass('max-h-40', 'overflow-y-auto')
  })
})
