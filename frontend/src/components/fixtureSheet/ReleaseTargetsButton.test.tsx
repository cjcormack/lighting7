// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * Release as the programmer's split button (programmer-toolbar plan D7): the face releases the
 * cells when there are any, else the whole heads, and the menu has both arms.
 */
const released = vi.hoisted(() => ({ heads: 0 }))
vi.mock('./useRelease', () => ({
  useReleaseTargets: () => async () => {
    released.heads++
  },
}))

import { ReleaseTargetsButton, type ReleaseSplit } from './ReleaseTargetsButton'

const TARGETS = [
  { type: 'fixture' as const, key: 'a', name: 'A' },
  { type: 'fixture' as const, key: 'b', name: 'B' },
]

function button(split: Partial<ReleaseSplit> = {}, disabledReason?: string) {
  const onReleaseCells = vi.fn()
  render(
    <ReleaseTargetsButton
      targets={TARGETS}
      wordClass="inline"
      disabledReason={disabledReason}
      split={{
        heads: 2,
        cells: 0,
        onReleaseCells,
        cellsTitle: 'Take the 2 selected cells out of Local',
        showKeys: true,
        sheet: false,
        ...split,
      }}
    />,
  )
  return { onReleaseCells }
}

function openMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: 'Release options' }), {
    button: 0,
    ctrlKey: false,
    pointerType: 'mouse',
  })
}

afterEach(() => {
  cleanup()
  released.heads = 0
})

describe('ReleaseTargetsButton as a split', () => {
  it('releases the whole heads on the face with rows alone, and offers no cells arm', () => {
    const { onReleaseCells } = button()
    fireEvent.click(screen.getByRole('button', { name: 'Release 2 heads' }))
    expect(released.heads).toBe(1)
    expect(onReleaseCells).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Release 2 heads' })).not.toHaveTextContent('⌫')
    openMenu()
    expect(screen.getByRole('menuitem', { name: /The selected cells/ })).toHaveAttribute('data-disabled')
    expect(screen.getByText('Pick cells first')).toBeInTheDocument()
  })

  it('releases the cells on the face when there are any, with ⌫ beside it', () => {
    const { onReleaseCells } = button({ cells: 2 })
    const face = screen.getByRole('button', { name: 'Release 2 cells' })
    expect(face).toHaveTextContent('⌫')
    fireEvent.click(face)
    expect(onReleaseCells).toHaveBeenCalledTimes(1)
    expect(released.heads).toBe(0)
  })

  it('offers both arms in the menu, each doing what it says', () => {
    const { onReleaseCells } = button({ cells: 2 })
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: /These 2 cells/ }))
    expect(onReleaseCells).toHaveBeenCalledTimes(1)
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: /The whole 2 heads/ }))
    expect(released.heads).toBe(1)
  })

  it('refuses the cells arm in a scope that cannot clear them, saying why, and keeps the heads arm', () => {
    button({ cells: 2, cellsRefusal: 'Output is a read of the cook — switch to Local to set these cells' })
    expect(screen.getByRole('button', { name: 'Release 2 cells' })).toBeDisabled()
    openMenu()
    expect(screen.getByRole('menuitem', { name: /These 2 cells/ })).toHaveAttribute('data-disabled')
    expect(screen.getByRole('menuitem', { name: /The whole 2 heads/ })).not.toHaveAttribute('data-disabled')
  })

  it('refuses the heads arm in a focused layer, with the host’s reason', () => {
    button({}, 'Release clears Local — focus Local first')
    const face = screen.getByRole('button', { name: 'Release 2 heads' })
    expect(face).toBeDisabled()
    expect(face).toHaveAttribute('title', 'Release clears Local — focus Local first')
  })

  it('draws no ⌫ below the widest rung', () => {
    button({ cells: 2, showKeys: false })
    expect(screen.getByRole('button', { name: 'Release 2 cells' })).not.toHaveTextContent('⌫')
  })

  it('opens its arms as a bottom sheet on a phone', () => {
    const { onReleaseCells } = button({ cells: 2, sheet: true })
    fireEvent.click(screen.getByRole('button', { name: 'Release options' }))
    const arm = document.querySelector('[data-release-arm="cells"]') as HTMLElement
    expect(arm).not.toBeNull()
    fireEvent.click(arm)
    expect(onReleaseCells).toHaveBeenCalledTimes(1)
  })
})

describe('ReleaseTargetsButton without a split — the busk band’s', () => {
  it('is one button counting its targets', () => {
    render(<ReleaseTargetsButton targets={TARGETS} wordClass="inline" />)
    fireEvent.click(screen.getByRole('button', { name: 'Release 2' }))
    expect(released.heads).toBe(1)
  })
})
