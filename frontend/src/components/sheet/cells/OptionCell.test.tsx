// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OptionCell, type SheetOption } from './OptionCell'
import { firstColumnCellProps } from '../sheetModel'
import { resetEditorSurfaceMedia } from '../../editor/EditorSurface'

/**
 * The option cell's filter is its keyboard — where a character typed at the grid lands and what
 * Enter takes the top match of — so it is drawn for every list with a choice in it, two options
 * included: the patch list's Stage and Role, a small bank's Follows or Master. A one-option list
 * has nothing to choose between and draws none.
 */
afterEach(() => {
  cleanup()
  resetEditorSurfaceMedia()
})

function draw(options: SheetOption[], value = options[0].value) {
  const onCommit = vi.fn()
  render(
    <OptionCell
      {...firstColumnCellProps({ value, label: 'Follows', noun: 'master', onCommit })}
      autoOpen
      options={options}
    />,
  )
  return { onCommit }
}

describe('OptionCell', () => {
  it('draws the filter over two options, narrows as it is typed, and takes the top match on Enter', async () => {
    const { onCommit } = draw([
      { value: 'manual', label: 'Manual' },
      { value: 'm1', label: 'M1 · Master' },
    ])
    const filter = await screen.findByRole('combobox', { name: 'Filter Follows options' })
    fireEvent.change(filter, { target: { value: 'm1' } })
    expect(screen.queryByRole('option', { name: 'Manual' })).toBeNull()
    fireEvent.keyDown(filter, { key: 'Enter' })
    expect(onCommit).toHaveBeenCalledWith('m1')
  })

  it('draws no filter over a single option', async () => {
    draw([{ value: 'manual', label: 'Manual' }])
    expect(await screen.findByRole('option', { name: 'Manual' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).toBeNull()
  })
})
