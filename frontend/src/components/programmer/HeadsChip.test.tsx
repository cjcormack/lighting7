// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The heads chip (programmer-toolbar plan D4, D5): the count, whose selection it is, and its menu —
 * the subsets over rig order, *Select by colour*, *Follow the desk selection*, Deselect, and on a
 * phone Locate, *Set locate from current* and Highlight.
 */
const desk = vi.hoisted(() => ({ targets: [{ type: 'fixture', key: 'par-1' }] }))
vi.mock('@/store/selection', async () => {
  const { unlinkFromDesk } = await import('@/lib/deskFollow')
  return { unlinkFromDeskNow: () => unlinkFromDesk({ targets: desk.targets as never, families: null }) }
})
vi.mock('@/components/fixtureColours/SelectByColour', () => ({ SelectByColourSub: () => <span data-testid="by-colour" /> }))
const located = vi.hoisted(() => ({ presses: 0 }))
vi.mock('@/components/fixtures-list/SelectionToolbar', () => ({
  useLocateSelection: () => ({ allLocated: false, locateSelection: () => located.presses++ }),
}))
const stored = vi.hoisted(() => ({ from: [] as unknown[] }))
vi.mock('@/hooks/useStoredLocateActions', () => ({
  useStoredLocateActions: () => ({ busy: false, projectId: 1, storeFromCurrent: (t: unknown[]) => stored.from.push(t) }),
}))
const highlight = vi.hoisted(() => ({ press: vi.fn(), release: vi.fn() }))
vi.mock('@/components/fixtures-list/useHighlight', () => ({
  useHighlight: () => ({ press: highlight.press, release: highlight.release, isActive: false }),
}))

import { HeadsChip, type HeadsChipProps } from './HeadsChip'
import { RowCBandContext, type RowCBand } from './rowCBand'
import { isFollowingDesk, resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'

const TWO = [
  { type: 'fixture' as const, key: 'a' },
  { type: 'fixture' as const, key: 'b' },
]

const props = {
  onSubselect: vi.fn(),
  onSelectTargets: vi.fn(),
  onDeselect: vi.fn(),
}

function chip(over: Partial<HeadsChipProps> = {}, band: RowCBand = 'desk') {
  return (
    <RowCBandContext.Provider value={band}>
      <HeadsChip
        projectId={1}
        heads={2}
        cells={0}
        targets={TWO}
        locateTargets={TWO}
        writeTargets={[]}
        {...props}
        {...over}
      />
    </RowCBandContext.Provider>
  )
}

/** Radix opens a menu on `pointerdown`, not `click`. */
function openMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: /^(Selection|Nothing selected)/ }), {
    button: 0,
    ctrlKey: false,
    pointerType: 'mouse',
  })
}

beforeEach(() => {
  located.presses = 0
  stored.from = []
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

describe('HeadsChip', () => {
  it('counts heads and cells, and says whose selection it is', () => {
    render(chip({ cells: 6, heads: 6 }))
    expect(screen.getByText('6 heads · 6 cells')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /following the desk/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Following the desk selection' })).toBeInTheDocument()
  })

  it('draws the dashed mark once unlinked, and a press on the count does not relink', () => {
    unlinkFromDesk({ targets: [], families: null })
    render(chip())
    expect(screen.getByRole('img', { name: 'This window’s own selection' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /this window’s own/ }))
    expect(isFollowingDesk()).toBe(false)
  })

  it('sends each subset’s mode, Previous and Next among them', () => {
    render(chip())
    openMenu()
    expect(screen.getByText('In rig order')).toBeInTheDocument()
    const modes = [...document.querySelectorAll('[data-subselect]')].map((item) => item.getAttribute('data-subselect'))
    expect(modes).toEqual(['ALL', 'ODD', 'EVEN', 'FIRST_HALF', 'SECOND_HALF', 'INVERT', 'MASTERS', 'PREV', 'NEXT'])
    fireEvent.click(document.querySelector('[data-subselect="ODD"]')!)
    expect(props.onSubselect).toHaveBeenCalledWith('ODD')
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Next along the rig' }))
    expect(props.onSubselect).toHaveBeenLastCalledWith('NEXT')
  })

  it('offers Select by colour', () => {
    render(chip())
    openMenu()
    expect(screen.getByTestId('by-colour')).toBeInTheDocument()
  })

  it('unlinks and relinks through Follow the desk selection', () => {
    render(chip())
    openMenu()
    const follow = screen.getByRole('menuitemcheckbox', { name: /Follow the desk selection/ })
    expect(follow).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(follow)
    expect(isFollowingDesk()).toBe(false)
    expect(screen.getByRole('menuitemcheckbox', { name: /Follow the desk selection/ })).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: /Follow the desk selection/ }))
    expect(isFollowingDesk()).toBe(true)
  })

  it('deselects from the × and from the menu', () => {
    render(chip())
    fireEvent.click(screen.getByRole('button', { name: 'Deselect' }))
    expect(props.onDeselect).toHaveBeenCalledTimes(1)
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Deselect' }))
    expect(props.onDeselect).toHaveBeenCalledTimes(2)
  })

  it('has no × and no Deselect with nothing selected', () => {
    render(chip({ heads: 0, targets: [], locateTargets: [] }))
    expect(screen.getByText('Nothing selected')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deselect' })).not.toBeInTheDocument()
    openMenu()
    expect(screen.queryByRole('menuitem', { name: 'Deselect' })).not.toBeInTheDocument()
  })

  it('offers × and Deselect for a selected group that shows no member', () => {
    render(chip({ heads: 0, targets: [{ type: 'group', key: 'Empty' }], locateTargets: [{ type: 'group', key: 'Empty' }] }))
    fireEvent.click(screen.getByRole('button', { name: 'Deselect' }))
    expect(props.onDeselect).toHaveBeenCalledTimes(1)
  })

  describe('on a phone', () => {
    it('counts the cells alone and drops the ×', () => {
      render(chip({ cells: 6, heads: 6 }, 'phone'))
      expect(screen.getByText('6 cells')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Deselect' })).not.toBeInTheDocument()
    })

    it('carries Locate, Set locate from current and a held Highlight in its menu', () => {
      render(chip({}, 'phone'))
      openMenu()
      fireEvent.click(screen.getByRole('menuitem', { name: 'Locate' }))
      expect(located.presses).toBe(1)
      openMenu()
      fireEvent.click(screen.getByRole('menuitem', { name: 'Set locate from current' }))
      expect(stored.from).toEqual([TWO])
      openMenu()
      const hold = screen.getByRole('menuitem', { name: 'Highlight' })
      fireEvent.pointerDown(hold)
      expect(highlight.press).toHaveBeenCalled()
      fireEvent.pointerUp(hold)
      expect(highlight.release).toHaveBeenCalled()
    })

    it('leaves them off the menu on a wider row, where they are buttons', () => {
      render(chip())
      openMenu()
      expect(screen.queryByRole('menuitem', { name: 'Set locate from current' })).not.toBeInTheDocument()
    })
  })
})
