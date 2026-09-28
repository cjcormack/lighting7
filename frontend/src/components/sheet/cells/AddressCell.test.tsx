// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AddressCell } from './AddressCell'
import { firstColumnCellProps } from '../sheetModel'

/**
 * The address editor writes on Apply, so an emptied start-channel box has to refuse rather than
 * apply the number the operator just deleted — the one place the kit's silent-on-empty field rule
 * needs the field's `onDraft` beside it.
 */
function draw() {
  const onCommit = vi.fn()
  render(
    <AddressCell
      {...firstColumnCellProps({ value: { universe: 1, channel: 41, footprint: 6 }, label: 'Address', noun: 'fixture', onCommit })}
      autoOpen
      landing={() => ({ lines: ['Par 1 → 1-041'], error: null })}
    />,
  )
  return { onCommit }
}

describe('AddressCell', () => {
  it('refuses Apply and Enter while the start channel box is empty, and recovers when a number is typed', async () => {
    const { onCommit } = draw()
    const field = await screen.findByRole('spinbutton', { name: 'Start channel' })
    const apply = screen.getByRole('button', { name: 'Apply' })
    expect(apply).toBeEnabled()
    fireEvent.change(field, { target: { value: '' } })
    expect(apply).toBeDisabled()
    expect(screen.getByText('Start channel cannot be empty')).toBeInTheDocument()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onCommit).not.toHaveBeenCalled()
    fireEvent.change(field, { target: { value: '7' } })
    expect(apply).toBeEnabled()
    fireEvent.click(apply)
    expect(onCommit).toHaveBeenCalledWith({ universe: 1, channel: 7, footprint: 6 })
  })

  it('names an out-of-range start and refuses it', async () => {
    const { onCommit } = draw()
    const field = await screen.findByRole('spinbutton', { name: 'Start channel' })
    fireEvent.change(field, { target: { value: '600' } })
    expect(screen.getByText('Start channel is 1–512')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onCommit).not.toHaveBeenCalled()
  })
})
