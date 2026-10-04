// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GelPickerField } from './GelPickerField'
import { indexGels, type Gel } from '@/lib/gels'
import gelsJson from '../../../../src/main/resources/gels.json'

// The served gel library (`GET /gels`), read here from the resource the desk serves it from.
vi.mock('@/hooks/useGelIndex', () => ({ useGelIndex: () => indexGels(gelsJson as Gel[]) }))

/**
 * The patch editor's gel field, now a host of the shared `GelPicker` (the patch list's Gel cell is
 * the other): the trigger names the current gel, the popover searches and picks, the cross clears,
 * and the search starts empty on every open — the picker's state lives in the popover's content,
 * which Radix unmounts on close.
 */
afterEach(cleanup)

function draw(value: string | null = null) {
  const onChange = vi.fn()
  render(<GelPickerField id="gel" value={value} onChange={onChange} />)
  return { onChange }
}

describe('GelPickerField', () => {
  it('searches the library and picks a gel', async () => {
    const { onChange } = draw()
    expect(screen.getByText('Open white — no gel')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Gel|Open white/ }))
    const search = await screen.findByRole('combobox', { name: 'Search gels' })
    fireEvent.change(search, { target: { value: 'full ct blue' } })
    fireEvent.click(screen.getByRole('option', { name: /L201/ }))
    expect(onChange).toHaveBeenCalledWith('L201')
  })

  it('takes the highlighted match on Enter, and filters by brand', async () => {
    const { onChange } = draw()
    fireEvent.click(screen.getByRole('button', { name: /Gel|Open white/ }))
    const search = await screen.findByRole('combobox', { name: 'Search gels' })
    fireEvent.click(screen.getByRole('button', { name: 'Rosco' }))
    expect(screen.queryByRole('option', { name: /L201/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'All' }))
    fireEvent.change(search, { target: { value: 'L106' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('L106')
  })

  it('opens each time with an empty search, even after one was dismissed half-typed', async () => {
    draw()
    const trigger = screen.getByRole('button', { name: /Gel|Open white/ })
    fireEvent.click(trigger)
    fireEvent.change(await screen.findByRole('combobox', { name: 'Search gels' }), { target: { value: 'amber' } })
    // Dismissed from the trigger — no pick, so nothing cleared the search but the unmount.
    fireEvent.click(trigger)
    expect(screen.queryByRole('combobox', { name: 'Search gels' })).toBeNull()
    fireEvent.click(trigger)
    expect(await screen.findByRole('combobox', { name: 'Search gels' })).toHaveValue('')
  })

  it('clears the current gel from the cross on the trigger', () => {
    const { onChange } = draw('L201')
    expect(screen.getByText('L201')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear gel' }))
    expect(onChange).toHaveBeenCalledWith(null)
  })
})
