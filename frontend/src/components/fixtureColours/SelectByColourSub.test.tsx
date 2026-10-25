// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The heads chip's *Select by colour ▸*: a colour replaces the selection, and ⌥ adds to it — to the
 * desk's heads, keeping its mask, while following (⌘K's rule), and to the list's own unlinked.
 */
const alt = vi.hoisted(() => ({ held: false }))
vi.mock('@/components/stage3d/useShiftHeld', () => ({ useModifierHeld: () => ({ held: alt.held, ref: { current: alt.held } }) }))
vi.mock('@/hooks/useFixtureColourIndex', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useFixtureColourIndex')>()),
  useFixtureColourIndex: () => new Map([['red-1', 'red'], ['red-2', 'red']]),
}))
vi.mock('@/store/fixtures', () => ({
  useVisibleFixtureListQuery: () => ({ data: [{ key: 'red-1' }, { key: 'red-2' }, { key: 'par-9' }] }),
}))
vi.mock('@/store/selection', () => ({
  useDeskSelectionQuery: () => ({
    data: { targets: [{ type: 'fixture', key: 'par-9' }], families: ['COLOUR'], source: null },
  }),
  setDeskSelection: vi.fn(),
}))

import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { SelectByColourSub } from './SelectByColour'
import { resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'

function open(onSelect: (targets: unknown[], families: unknown) => void) {
  render(
    <DropdownMenu open>
      <DropdownMenuTrigger>menu</DropdownMenuTrigger>
      <DropdownMenuContent>
        <SelectByColourSub projectId={1} current={[{ type: 'fixture', key: 'own-1' }]} onSelect={onSelect} />
      </DropdownMenuContent>
    </DropdownMenu>,
  )
  fireEvent.click(screen.getByText('Select by colour'))
  fireEvent.click(document.querySelector('[data-select-colour="red"]')!)
}

afterEach(() => {
  cleanup()
  alt.held = false
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

describe('SelectByColourSub', () => {
  it('replaces the selection with the colour’s fixtures, unmasked', () => {
    const onSelect = vi.fn()
    open(onSelect)
    expect(onSelect).toHaveBeenCalledWith(
      [{ type: 'fixture', key: 'red-1' }, { type: 'fixture', key: 'red-2' }],
      null,
    )
  })

  it('adds to the desk’s heads and keeps its mask with ⌥ while following', () => {
    alt.held = true
    const onSelect = vi.fn()
    open(onSelect)
    expect(onSelect).toHaveBeenCalledWith(
      [{ type: 'fixture', key: 'par-9' }, { type: 'fixture', key: 'red-1' }, { type: 'fixture', key: 'red-2' }],
      ['COLOUR'],
    )
  })

  it('adds to the list’s own selection with ⌥ once unlinked', () => {
    unlinkFromDesk({ targets: [], families: null })
    alt.held = true
    const onSelect = vi.fn()
    open(onSelect)
    expect(onSelect).toHaveBeenCalledWith(
      [{ type: 'fixture', key: 'own-1' }, { type: 'fixture', key: 'red-1' }, { type: 'fixture', key: 'red-2' }],
      null,
    )
  })
})
