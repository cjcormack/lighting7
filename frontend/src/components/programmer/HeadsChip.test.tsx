// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The heads chip's menu (programmer-toolbar plan D4, D5): the busk band's subsets, *Select by
 * colour*, the follow toggle, the rig verbs on a phone, and Deselect. Which selection a subset
 * rewrites is the caller's (`useListSelectionWrites`); this pins that each item sends its mode.
 */
const desk = vi.hoisted(() => ({ targets: [{ type: 'fixture' as const, key: 'a' }] }))
vi.mock('@/store/selection', async () => {
  const { unlinkFromDesk } = await import('@/lib/deskFollow')
  return { unlinkFromDeskNow: () => unlinkFromDesk({ targets: desk.targets, families: null }) }
})
vi.mock('@/hooks/useFixtureColourIndex', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useFixtureColourIndex')>()),
  useFixtureColourIndex: () => new Map([['a', 'red'], ['b', 'red'], ['c', 'blue']]),
}))

const { HeadsChip } = await import('./HeadsChip')
import { ToolbarPhoneContext } from '@/components/sheet/ToolbarMenu'
import { isFollowingDesk, resetDeskFollowStores } from '@/lib/deskFollow'
import type { RigVerbs } from '@/components/fixtures-list/RigVerbs'

afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

function chip(over: Partial<React.ComponentProps<typeof HeadsChip>> = {}, phone = false) {
  const props: React.ComponentProps<typeof HeadsChip> = {
    projectId: 1,
    selected: true,
    heads: 4,
    cells: 0,
    onSubselect: vi.fn(),
    onPickColour: vi.fn(),
    scope: null,
    colourKeys: new Set(['a', 'b', 'c']),
    onDeselect: vi.fn(),
    rigVerbs: null,
    ...over,
  }
  render(
    <ToolbarPhoneContext.Provider value={phone}>
      <HeadsChip {...props} />
    </ToolbarPhoneContext.Provider>,
  )
  return props
}

/** Radix opens a dropdown on a pointer press; a phone's sheet on a click. */
function open(phone = false) {
  const trigger = screen.getByRole('button', { name: /^Selection|^Nothing selected/ })
  if (phone) fireEvent.click(trigger)
  else fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false, pointerType: 'mouse' })
}

describe('HeadsChip', () => {
  it('sends each subset’s mode — the seven filters and the two steps, the busk band’s words', () => {
    const props = chip()
    const modes = [
      ['All', 'ALL'],
      ['Odd', 'ODD'],
      ['Even', 'EVEN'],
      ['1st half', 'FIRST_HALF'],
      ['2nd half', 'SECOND_HALF'],
      ['Invert', 'INVERT'],
      ['Masters only', 'MASTERS'],
      ['‹ Previous', 'PREV'],
      ['Next ›', 'NEXT'],
    ] as const
    for (const [label, mode] of modes) {
      open()
      fireEvent.click(screen.getByRole('menuitem', { name: label }))
      expect(props.onSubselect).toHaveBeenLastCalledWith(mode)
    }
    expect(props.onSubselect).toHaveBeenCalledTimes(modes.length)
  })

  it('heads the subsets In rig order', () => {
    chip()
    open()
    expect(screen.getByText('In rig order')).toBeInTheDocument()
  })

  it('offers no subset with nothing selected', () => {
    chip({ selected: false, heads: 0, cells: 0 })
    open()
    expect(screen.getByRole('menuitem', { name: 'Odd' })).toHaveAttribute('data-disabled')
    expect(screen.queryByRole('menuitem', { name: 'Deselect' })).not.toBeInTheDocument()
  })

  it('toggles following the desk from its menu item, both ways', () => {
    chip()
    open()
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: /Follow the desk selection/ }))
    expect(isFollowingDesk()).toBe(false)
    expect(screen.getByRole('img', { name: 'This window’s own selection' })).toBeInTheDocument()
    open()
    const item = screen.getByRole('menuitemcheckbox', { name: /Follow the desk selection/ })
    expect(item).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(item)
    expect(isFollowingDesk()).toBe(true)
  })

  it('deselects from its menu and from its ×', () => {
    const props = chip()
    open()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Deselect' }))
    fireEvent.click(screen.getByRole('button', { name: 'Deselect' }))
    expect(props.onDeselect).toHaveBeenCalledTimes(2)
  })

  it('passes a colour’s fixtures to the caller, which lands them on whichever selection is the window’s', async () => {
    const props = chip()
    open()
    fireEvent.click(screen.getByRole('menuitem', { name: /Select by colour/ }))
    fireEvent.keyDown(screen.getByRole('menuitem', { name: /Select by colour/ }), { key: 'ArrowRight' })
    const red = await screen.findByRole('menuitem', { name: /Red/ })
    fireEvent.click(red)
    expect(props.onPickColour).toHaveBeenCalledWith(['a', 'b'], false)
  })

  it('stays a selection — × and Deselect — for a row that stands for no head shown, an empty group', () => {
    const props = chip({ selected: true, heads: 0, cells: 0 })
    expect(screen.getByText('0 heads')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Deselect' }))
    expect(props.onDeselect).toHaveBeenCalled()
  })

  it('says the marquee’s columns on its hover', () => {
    chip({ cells: 4, scope: '4 × Colour' })
    expect(screen.getByRole('button', { name: /^Selection:/ })).toHaveAttribute(
      'title',
      '4 heads · 4 × Colour — edit once, applies to all',
    )
  })

  it('is a bottom sheet on a phone, with the rig verbs and no ×', () => {
    const locate = vi.fn()
    const highlight = { isActive: false, press: vi.fn(), release: vi.fn() }
    const rigVerbs = {
      locateTargets: [],
      allLocated: false,
      locate,
      storedLocate: { busy: false, storeFromCurrent: vi.fn(), clearLocate: vi.fn(), projectId: 1 },
      highlight,
      canStoreLocate: true,
    } as unknown as RigVerbs
    chip({ cells: 3, rigVerbs }, true)
    expect(screen.getByText('3 cells')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deselect' })).not.toBeInTheDocument()
    open(true)
    const sheet = screen.getByRole('menu', { name: 'The selection' })
    expect(within(sheet).getByText('On the rig')).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('menuitem', { name: 'Locate' }))
    expect(locate).toHaveBeenCalled()
    open(true)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Highlight' }))
    expect(highlight.press).toHaveBeenCalled()
  })
})
