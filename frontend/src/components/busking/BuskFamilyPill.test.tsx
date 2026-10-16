// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BuskFamilyPill } from './BuskFamilyPill'

/**
 * The busk selection's family pill (apply-model plan D3, §4.4): the mask's picker, drawn over any
 * selection — `All` when unmasked, since the rig has no marquee to set a first mask with — and
 * over a mask with nothing selected.
 */
describe('BuskFamilyPill', () => {
  it('is a picker reading All over a selection with no mask', () => {
    render(<BuskFamilyPill families={null} onChange={() => {}} selected marker="data-pad-family" />)
    const pill = screen.getByRole('button', { name: 'All' })
    expect(pill).toHaveAttribute('data-pad-family')
  })

  it('is drawn for a mask with nothing selected, and not at all with neither', () => {
    const masked = render(<BuskFamilyPill families={['COLOUR']} onChange={() => {}} selected={false} />)
    expect(screen.getByRole('button', { name: 'Colour' })).toBeInTheDocument()
    masked.unmount()
    const { container } = render(<BuskFamilyPill families={null} onChange={() => {}} selected={false} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('writes the narrowed mask, and every attribute again from the switch', () => {
    const onChange = vi.fn()
    const { rerender } = render(<BuskFamilyPill families={null} onChange={onChange} selected />)
    fireEvent.click(screen.getByRole('button', { name: 'All' }))
    fireEvent.click(screen.getByRole('switch', { name: 'All attributes' }))
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(screen.getByLabelText('Intensity'))
    fireEvent.click(screen.getByLabelText('Beam'))
    expect(onChange.mock.calls).toEqual([[['POSITION', 'COLOUR', 'BEAM']], [['POSITION', 'COLOUR']]])
    // The pill's face is the stored mask, not the draft.
    expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument()
    rerender(<BuskFamilyPill families={['POSITION', 'COLOUR']} onChange={onChange} selected />)
    expect(screen.getByRole('button', { name: 'Position · Colour' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('switch', { name: 'All attributes' }))
    expect(onChange).toHaveBeenLastCalledWith(null)
  })
})
