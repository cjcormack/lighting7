// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CellSelectionActions } from './CellSelectionActions'
import { cellActionCopy } from '../fixtures-list/cellEntry'

afterEach(cleanup)

/**
 * The bar's cell verbs are the marquee's two keys with a button on them. What is pinned is that
 * they take the surface's gate and words rather than deciding anything themselves — one
 * `permission` object for the button and the key, so the two cannot disagree — and that Spread is
 * a slot the surface fills rather than a panel drawn here.
 */
describe('CellSelectionActions', () => {
  it('runs Set and Clear from the surface, and carries its words', () => {
    const onSet = vi.fn()
    const onClear = vi.fn()
    render(
      <CellSelectionActions
        copy={cellActionCopy({ kind: 'local' }, false, 2)}
        permission={{ entry: true, clear: true }}
        onSet={onSet}
        onClear={onClear}
        spread={<button type="button">Spread</button>}
      />,
    )
    const set = screen.getByRole('button', { name: 'Set' })
    expect(set).toHaveAttribute('title', expect.stringContaining('in Local'))
    fireEvent.click(set)
    expect(onSet).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    expect(onClear).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Spread' })).toBeInTheDocument()
  })

  it('is disabled where the gate refuses, with the reason on the button', () => {
    render(
      <CellSelectionActions
        copy={cellActionCopy({ kind: 'output' }, false, 2)}
        permission={{ entry: false, clear: false }}
        onSet={() => {}}
        onClear={() => {}}
      />,
    )
    const set = screen.getByRole('button', { name: 'Set' })
    expect(set).toBeDisabled()
    expect(set).toHaveAttribute('title', expect.stringContaining('read of the cook'))
    expect(screen.getByRole('button', { name: 'Clear cells' })).toBeDisabled()
    // No spread handed in, none drawn: a surface with nothing that spreads draws no dead button.
    expect(screen.queryByRole('button', { name: 'Spread' })).toBeNull()
  })

  it('refuses one verb without the other — Clear alone, on a column that cannot be empty', () => {
    // The patch list's Address column: Set lands an address, and an address cannot be cleared.
    render(
      <CellSelectionActions
        copy={{ setTitle: 'Set the address', clearTitle: 'An address cannot be empty' }}
        permission={{ entry: true, clear: false }}
        onSet={() => {}}
        onClear={() => {}}
      />,
    )
    expect(screen.getByRole('button', { name: 'Set' })).not.toBeDisabled()
    const clear = screen.getByRole('button', { name: 'Clear cells' })
    expect(clear).toBeDisabled()
    expect(clear).toHaveAttribute('title', 'An address cannot be empty')
  })

  it('hands the Set button up as a ref — it is where the editor it opens is anchored', () => {
    // The panel opens at this button rather than at the cell, so the ref is not decoration: without
    // it `EditorSurface` falls back to the cell anchor and the editor lands wherever in the grid
    // the first selected cell happens to be.
    const ref = { current: null as HTMLButtonElement | null }
    render(
      <CellSelectionActions
        copy={cellActionCopy({ kind: 'local' }, false, 1)}
        permission={{ entry: true, clear: true }}
        setRef={ref}
        onSet={() => {}}
        onClear={() => {}}
      />,
    )
    expect(ref.current).toBe(screen.getByRole('button', { name: 'Set' }))
  })
})
