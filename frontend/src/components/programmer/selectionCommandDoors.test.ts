// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, renderHook } from '@testing-library/react'
import type { RigVerbs } from '@/components/fixtures-list/RigVerbs'

/**
 * ⌘K's *Selection* group as the programmer's list registers it (programmer-toolbar plan D13): each
 * command runs row C's handler, says what it acts on, and cannot act where row C's control would be
 * refused — and the plain lists, which share the container, register nothing.
 */

const relink = vi.hoisted(() => vi.fn())
const unlink = vi.hoisted(() => vi.fn())
vi.mock('@/lib/deskFollow', () => ({ relinkToDesk: relink }))
vi.mock('@/store/selection', () => ({ unlinkFromDeskNow: unlink }))

const { useSelectionCommandDoors } = await import('./selectionCommandDoors')
const { readProgrammerCommands } = await import('./commandDoors')
type Input = Parameters<typeof useSelectionCommandDoors>[0]

afterEach(cleanup)

function rigVerbs(over: Partial<RigVerbs> = {}): RigVerbs {
  return {
    locate: vi.fn(),
    allLocated: false,
    canStoreLocate: true,
    storedLocate: { busy: false, storeFromCurrent: vi.fn() },
    locateTargets: [],
    highlight: { isActive: false, press: vi.fn(), release: vi.fn() },
    ...over,
  } as unknown as RigVerbs
}

function input(over: Partial<NonNullable<Input>> = {}): NonNullable<Input> {
  return {
    heads: 6,
    cells: 6,
    families: ['COLOUR'],
    canSet: true,
    onSet: vi.fn(),
    canFan: true,
    onFan: vi.fn(),
    canReleaseCells: true,
    onReleaseCells: vi.fn(),
    releaseHeads: 6,
    canReleaseHeads: true,
    onReleaseHeads: vi.fn(),
    onSubselect: vi.fn(),
    onRemask: vi.fn(),
    rigVerbs: rigVerbs(),
    following: true,
    onDeselect: vi.fn(),
    ...over,
  }
}

const offered = () => readProgrammerCommands().filter((c) => c.group === 'selection')
const command = (id: string) => offered().find((c) => c.id === id)

describe('the selection group', () => {
  it('lists D13’s order, each with what it acts on', () => {
    renderHook(() => useSelectionCommandDoors(input()))
    expect(offered().map((c) => c.label)).toEqual([
      'Set the selected cells',
      'Fan across the selection…',
      'Release the selected cells',
      'Release the whole heads',
      'All',
      'Odd',
      'Even',
      '1st half',
      '2nd half',
      'Invert',
      'Masters only',
      'Previous',
      'Next',
      'Mask: All',
      'Mask: Intensity',
      'Mask: Colour',
      'Mask: Position',
      'Mask: Beam',
      'Locate the selection',
      'Set locate from current',
      'Highlight the selection',
      'This window’s own selection',
      'Deselect',
    ])
    expect(command('selection.set')).toMatchObject({ hint: '6 cells', kbd: '⏎' })
    expect(command('selection.fan')?.hint).toBe('6 heads · Colour')
    expect(command('selection.releaseCells')).toMatchObject({ hint: '6 cells', kbd: '⌫' })
    expect(command('selection.releaseHeads')?.hint).toBe('6 heads · values and Local FX')
    expect(command('selection.subset.ODD')?.hint).toBe('In rig order · 6 heads')
    expect(command('selection.mask.ALL')?.hint).toBe('now Colour')
  })

  it('runs row C’s handler for each', () => {
    const i = input()
    renderHook(() => useSelectionCommandDoors(i))
    command('selection.set')!.run!()
    command('selection.fan')!.run!()
    command('selection.releaseCells')!.run!()
    command('selection.releaseHeads')!.run!()
    command('selection.subset.EVEN')!.run!()
    command('selection.subset.NEXT')!.run!()
    command('selection.mask.INTENSITY')!.run!()
    command('selection.mask.ALL')!.run!()
    command('selection.locate')!.run!()
    command('selection.storeLocate')!.run!()
    command('selection.highlight')!.run!()
    command('selection.deselect')!.run!()
    expect(i.onSet).toHaveBeenCalledTimes(1)
    expect(i.onFan).toHaveBeenCalledTimes(1)
    expect(i.onReleaseCells).toHaveBeenCalledTimes(1)
    expect(i.onReleaseHeads).toHaveBeenCalledTimes(1)
    expect(i.onSubselect).toHaveBeenNthCalledWith(1, 'EVEN')
    expect(i.onSubselect).toHaveBeenNthCalledWith(2, 'NEXT')
    expect(i.onRemask).toHaveBeenNthCalledWith(1, ['INTENSITY'])
    expect(i.onRemask).toHaveBeenNthCalledWith(2, null)
    expect(i.rigVerbs.locate).toHaveBeenCalledTimes(1)
    expect(i.rigVerbs.storedLocate.storeFromCurrent).toHaveBeenCalledTimes(1)
    expect(i.rigVerbs.highlight.press).toHaveBeenCalledTimes(1)
    expect(i.onDeselect).toHaveBeenCalledTimes(1)
  })

  it('cannot act where row C would refuse', () => {
    renderHook(() =>
      useSelectionCommandDoors(input({ canSet: false, canFan: false, canReleaseCells: false, canReleaseHeads: false })),
    )
    for (const id of ['selection.set', 'selection.fan', 'selection.releaseCells', 'selection.releaseHeads']) {
      expect(command(id)?.canAct, id).toBe(false)
    }
    // The mask already on is no change to offer.
    expect(command('selection.mask.COLOUR')?.canAct).toBe(false)
    expect(command('selection.mask.BEAM')?.canAct).toBe(true)
  })

  it('offers only the follow toggle with nothing selected', () => {
    renderHook(() => useSelectionCommandDoors(input({ heads: 0, cells: 0, canSet: false, canFan: false, canReleaseCells: false, canReleaseHeads: false })))
    expect(offered().filter((c) => c.canAct).map((c) => c.id)).toEqual(['selection.follow'])
  })

  it('leaves the mask alone while the window keeps its own selection, and offers to follow', () => {
    renderHook(() => useSelectionCommandDoors(input({ onRemask: null, following: false })))
    expect(offered().filter((c) => c.id.startsWith('selection.mask.')).every((c) => !c.canAct)).toBe(true)
    expect(command('selection.follow')?.label).toBe('Follow the desk selection')
    command('selection.follow')!.run!()
    expect(relink).toHaveBeenCalledTimes(1)
  })

  it('unlinks from the desk while following', () => {
    renderHook(() => useSelectionCommandDoors(input()))
    command('selection.follow')!.run!()
    expect(unlink).toHaveBeenCalledTimes(1)
  })

  it('latches Highlight, and says how to let it go', () => {
    const verbs = rigVerbs({ highlight: { isActive: true, press: vi.fn(), release: vi.fn() } as unknown as RigVerbs['highlight'] })
    renderHook(() => useSelectionCommandDoors(input({ rigVerbs: verbs })))
    expect(command('selection.highlight')?.label).toBe('Stop highlighting')
    command('selection.highlight')!.run!()
    expect(verbs.highlight.release).toHaveBeenCalledTimes(1)
  })

  it('registers nothing for the plain lists, and nothing once unmounted', () => {
    const view = renderHook(() => useSelectionCommandDoors(null))
    expect(offered()).toEqual([])
    view.rerender()
    view.unmount()
    const mounted = renderHook(() => useSelectionCommandDoors(input()))
    expect(offered().length).toBeGreaterThan(0)
    mounted.unmount()
    expect(offered()).toEqual([])
  })
})
