// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * Release on the programmer's row C is one split button (programmer-toolbar plan D7): the face
 * releases the cells when cells are selected, else the whole heads; the menu has both arms.
 */
const releaseHeads = vi.hoisted(() => vi.fn(() => Promise.resolve()))
vi.mock('./useRelease', () => ({ useReleaseTargets: () => releaseHeads }))

import { ReleaseTargetsButton, type ReleaseCellsArm } from './ReleaseTargetsButton'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

const TARGETS = [
  { type: 'fixture' as const, key: 'a', name: 'A' },
  { type: 'fixture' as const, key: 'b', name: 'B' },
]

function split(cells: Partial<ReleaseCellsArm>, disabledReason?: string) {
  const arm: ReleaseCellsArm = { count: 0, heads: 2, onRelease: vi.fn(), refused: null, kbdClass: 'kbd', ...cells }
  render(<ReleaseTargetsButton targets={TARGETS} wordClass="word" disabledReason={disabledReason} cells={arm} />)
  return arm
}

const openMenu = () =>
  fireEvent.pointerDown(screen.getByRole('button', { name: 'Release options' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })

describe('ReleaseTargetsButton as the programmer’s split button', () => {
  it('releases the cells from its face when cells are selected, with ⌫ on it', () => {
    const arm = split({ count: 3 })
    const face = screen.getByRole('button', { name: 'Release the 3 cells' })
    expect(face).toHaveTextContent('⌫')
    fireEvent.click(face)
    expect(arm.onRelease).toHaveBeenCalled()
    expect(releaseHeads).not.toHaveBeenCalled()
  })

  it('releases the whole heads from its face with rows alone', () => {
    split({ count: 0 })
    fireEvent.click(screen.getByRole('button', { name: 'Release the 2 heads' }))
    expect(releaseHeads).toHaveBeenCalled()
  })

  it('offers both arms in its menu, each doing what it says', () => {
    const arm = split({ count: 3 })
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: /These 3 cells/ }))
    expect(arm.onRelease).toHaveBeenCalled()
    openMenu()
    expect(screen.getByRole('menuitem', { name: /Every value and Local FX on them/ })).toHaveTextContent('The whole 2 heads')
    fireEvent.click(screen.getByRole('menuitem', { name: /The whole 2 heads/ }))
    expect(releaseHeads).toHaveBeenCalled()
  })

  it('says the cell arm needs cells with rows alone', () => {
    split({ count: 0 })
    openMenu()
    const cells = screen.getByRole('menuitem', { name: /These cells/ })
    expect(cells).toHaveAttribute('data-disabled')
    expect(cells).toHaveTextContent('Pick cells first')
  })

  it('words each arm by the scope: Output refuses the cells and releases the heads', () => {
    split({ count: 2, refused: 'Output is a read of the cook — switch to Local to set these cells' })
    const face = screen.getByRole('button', { name: 'Release the 2 cells' })
    expect(face).toBeDisabled()
    // ⌫ is refused there, so it is not advertised.
    expect(face).not.toHaveTextContent('⌫')
    openMenu()
    expect(screen.getByRole('menuitem', { name: /These 2 cells/ })).toHaveTextContent(/Output is a read/)
    expect(screen.getByRole('menuitem', { name: /The whole 2 heads/ })).not.toHaveAttribute('data-disabled')
  })

  it('refuses both in a focused layer, saying why', () => {
    split(
      { count: 2, refused: "A layer's rows cannot be cleared from here — switch to Local" },
      'Release clears Local — focus Local first; edits here go to Warm',
    )
    expect(screen.getByRole('button', { name: 'Release options' })).toBeDisabled()
  })

  it('counts the heads as the heads chip does — a group row is its members, not one target', () => {
    split({ count: 0, heads: 8 })
    expect(screen.getByRole('button', { name: 'Release the 8 heads' })).toBeInTheDocument()
  })

  it('draws the plain Release n wherever no cell arm is passed — the busk rig row', () => {
    render(<ReleaseTargetsButton targets={TARGETS} wordClass="word" />)
    expect(screen.getByRole('button', { name: 'Release 2' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Release options' })).not.toBeInTheDocument()
  })
})
