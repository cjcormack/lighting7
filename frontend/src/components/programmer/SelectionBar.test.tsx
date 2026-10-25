// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'

/**
 * Row C — the programmer's three clusters (programmer-toolbar plan D3, D12), and the plain lists'
 * kit bar, which must draw exactly as it always has.
 *
 * jsdom evaluates no container query, so what is pinned is *which* element carries a fold class and
 * which arm the measured width picks (`useContainerBand`, faked here).
 */
const shortViewport = vi.hoisted(() => ({ current: false }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => shortViewport.current }))
/** The row's measured band: 0 is a desk's, 1 a phone's (`ROW_C_PHONE_FLOORS`). */
const rowBand = vi.hoisted(() => ({ current: 0 }))
vi.mock('@/hooks/useContainerBand', () => ({ useContainerBand: () => [() => {}, rowBand.current] }))
const desk = vi.hoisted(() => ({
  snapshot: { targets: [], families: null, source: null } as DeskSelectionSnapshot,
  /** Every mask the pill wrote, in order — the bridge's `remask` argument. */
  masks: [] as (AttributeFamily[] | null)[],
}))
vi.mock('@/store/selection', async () => {
  const { unlinkFromDesk, useDeskFollow } = await import('@/lib/deskFollow')
  return {
    useDeskSelectionSnapshot: () => desk.snapshot,
    unlinkFromDeskNow: () => unlinkFromDesk({ targets: desk.snapshot.targets, families: desk.snapshot.families }),
    // The press mask: the desk's while following, the marquee's own when not — the real rule, over
    // the mocked snapshot and the real (subscribed) follow store.
    usePressFamilies: (local: AttributeFamily[] | null) => (useDeskFollow() ? desk.snapshot.families : local),
  }
})
// *Select by colour* draws nothing for a rig with no colours.
vi.mock('@/hooks/useFixtureColourIndex', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useFixtureColourIndex')>()),
  useFixtureColourIndex: () => new Map(),
}))

const { SelectionBar } = await import('./SelectionBar')
import { MID_FOLDED_CLASS, PHONE_FOLDED_CLASS } from '@/components/sheet/toolbarFolds'
import type { DeskSelectionSnapshot } from '@/api/selectionApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'
import type { HeadsChipParts } from './SelectionBar'

const CELLS: CellRef<ColumnKey>[] = [
  { rowId: 'fixture:a', col: 'colour' },
  { rowId: 'fixture:b', col: 'colour' },
]
const TARGETS = [
  { type: 'fixture' as const, key: 'a' },
  { type: 'fixture' as const, key: 'b' },
]

function heads(over: Partial<HeadsChipParts> = {}): HeadsChipParts {
  return {
    selected: true,
    heads: 2,
    cells: 2,
    onSubselect: () => {},
    onPickColour: () => {},
    scope: '2 × Colour',
    colourKeys: new Set(),
    onDeselect: () => {},
    rigVerbs: null,
    ...over,
  }
}

function bar(over: Partial<React.ComponentProps<typeof SelectionBar>> = {}) {
  return (
    <SelectionBar
      projectId={1}
      selection={<span data-testid="rig-verbs" />}
      actions={<span data-testid="actions" />}
      heads={heads()}
      cells={CELLS}
      cellEntryKey
      cellClearKey
      paletteTargets={TARGETS}
      marqueeDragging={false}
      onRemask={(families) => desk.masks.push(families == null ? null : [...families])}
      {...over}
    />
  )
}

const follows = (a: Node, b: Node) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0

beforeEach(() => {
  shortViewport.current = false
  rowBand.current = 0
  desk.snapshot = { targets: [], families: null, source: null }
  desk.masks = []
})
afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

describe('SelectionBar on the programmer', () => {
  it('draws its three clusters left to right: the heads and the mask, what to do, where on the rig', () => {
    desk.snapshot = { targets: [], families: ['COLOUR'], source: null }
    render(bar())
    const chip = screen.getByRole('button', { name: /^Selection: 2 heads, 2 cells/ })
    const mask = screen.getByRole('button', { name: /^Mask: Colour/ })
    expect(follows(chip, mask)).toBe(true)
    expect(follows(mask, screen.getByTestId('actions'))).toBe(true)
    expect(follows(screen.getByTestId('actions'), screen.getByTestId('rig-verbs'))).toBe(true)
    // The ⏎/⌫ hints moved onto Set and Release, so the kit draws none here.
    expect(screen.queryByText('⏎')).not.toBeInTheDocument()
  })

  it('counts heads and cells, heads alone for rows, and Nothing selected', () => {
    const first = render(bar())
    const chip = screen.getByRole('button', { name: /^Selection:/ })
    expect(chip).toHaveTextContent('2 heads · 2 cells')
    // Under 660 of the row the heads fold, as on a phone.
    expect(screen.getByText(/^2 heads ·/)).toHaveClass('@max-[660px]:hidden')
    first.unmount()
    const rows = render(bar({ cells: [], heads: heads({ cells: 0 }) }))
    expect(screen.getByText('2 heads')).toBeInTheDocument()
    rows.unmount()
    render(bar({ cells: [], paletteTargets: [], selection: null, actions: null, heads: heads({ selected: false, heads: 0, cells: 0 }) }))
    expect(screen.getByText('Nothing selected')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Mask/ })).not.toBeInTheDocument()
  })

  it('counts cells alone on a phone’s row, and leaves Deselect to the chip’s menu', () => {
    rowBand.current = 1
    render(bar())
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deselect' })).not.toBeInTheDocument()
  })

  it('draws the chip’s × on a desk, which deselects', () => {
    const onDeselect = vi.fn()
    render(bar({ heads: heads({ onDeselect }) }))
    fireEvent.click(screen.getByRole('button', { name: 'Deselect' }))
    expect(onDeselect).toHaveBeenCalled()
  })

  it('says whose selection it is with the chip’s glyph — the link badge, or the dashed mark', () => {
    const linked = render(bar())
    expect(screen.getByRole('img', { name: 'Following the desk selection' })).toBeInTheDocument()
    // No press on the glyph (D5): the toggle is the menu's.
    expect(screen.queryByRole('button', { name: 'Following the desk selection' })).not.toBeInTheDocument()
    linked.unmount()
    unlinkFromDesk({ targets: [], families: null })
    render(bar())
    expect(screen.getByRole('img', { name: 'This window’s own selection' })).toBeInTheDocument()
  })

  it('holds the row reserved with nothing selected, the chip still there to follow or not', () => {
    render(bar({ cells: [], paletteTargets: [], selection: null, actions: null, heads: heads({ selected: false, heads: 0, cells: 0 }) }))
    expect(screen.getByRole('button', { name: /^Nothing selected — following the desk/ })).toBeInTheDocument()
    expect(screen.queryByTestId('actions')).not.toBeInTheDocument()
  })

  /**
   * The mask is what the next press carries (multi-screen plan D4): the desk's while following —
   * and its picker there (apply-model plan D3), each real change handed to the list's bridge — and
   * the marquee's own, read-only, once unlinked.
   */
  describe('the mask', () => {
    function openMask(name: RegExp) {
      fireEvent.click(screen.getByRole('button', { name }))
      return screen.getByRole('switch', { name: 'All attributes' })
    }

    it('reads the desk’s mask while following, over rows with no cells', () => {
      desk.snapshot = { targets: [], families: ['POSITION'], source: null }
      render(bar({ cells: [], heads: heads({ cells: 0 }) }))
      expect(screen.getByRole('button', { name: /^Mask: Position/ })).toBeInTheDocument()
    })

    it('reads every attribute while following an unmasked desk, whatever the marquee names', () => {
      render(bar())
      expect(screen.getByRole('button', { name: /^Mask: every attribute/ })).toBeInTheDocument()
    })

    it('writes the new families on a change, and nothing for switching All attributes off', () => {
      render(bar({ cells: [], heads: heads({ cells: 0 }) }))
      fireEvent.click(openMask(/^Mask: every attribute/))
      expect(desk.masks).toEqual([])
      fireEvent.click(screen.getByLabelText('Beam'))
      expect(desk.masks).toEqual([['INTENSITY', 'POSITION', 'COLOUR']])
    })

    it('clears the mask with All attributes', () => {
      desk.snapshot = { targets: [], families: ['COLOUR'], source: null }
      render(bar())
      fireEvent.click(openMask(/^Mask: Colour/))
      expect(desk.masks).toEqual([null])
    })

    it('abbreviates under 800 of the row and keeps only the glyph under 600', () => {
      desk.snapshot = { targets: [], families: ['COLOUR'], source: null }
      render(bar())
      expect(screen.getByText('Colour')).toHaveClass('hidden', '@[800px]:inline-block')
      expect(screen.getByText('Col')).toHaveClass('hidden', '@[600px]:inline', '@[800px]:hidden')
    })

    it('is a reading once unlinked — the marquee’s columns are the only mask there is', () => {
      desk.snapshot = { targets: [], families: ['POSITION'], source: null }
      unlinkFromDesk({ targets: [], families: null })
      render(bar({ onRemask: null }))
      expect(screen.queryByRole('button', { name: /^Mask/ })).not.toBeInTheDocument()
      expect(screen.getByRole('img', { name: /^Mask: Colour/ })).toBeInTheDocument()
    })
  })

  it('is a 40px line in both arms — the chrome system', () => {
    const live = render(bar())
    expect(live.container.querySelector('.h-10')).not.toBeNull()
    live.unmount()
    const reserved = render(bar({ cells: [], paletteTargets: [], selection: null, actions: null, heads: heads({ selected: false, heads: 0, cells: 0 }) }))
    expect(reserved.container.querySelector('.h-10')).not.toBeNull()
  })
})

/** The two plain lists: the kit's own left end, the family badge and the hints, as before. */
describe('SelectionBar on the plain lists', () => {
  const plain = (over: Partial<React.ComponentProps<typeof SelectionBar>> = {}) =>
    bar({ projectId: undefined, heads: undefined, actions: undefined, onRemask: null, ...over })

  it('folds the head count at 800 and the family badge at 600, and keeps the cell count', () => {
    render(plain())
    expect(screen.getByText('2 heads')).toHaveClass(MID_FOLDED_CLASS)
    expect(screen.getByText('Colour')).toHaveClass(PHONE_FOLDED_CLASS)
    expect(screen.getByText('2 cells')).not.toHaveClass(MID_FOLDED_CLASS)
  })

  it('reads the marquee’s families, which never bridge, and draws no heads chip or desk link', () => {
    desk.snapshot = { targets: [], families: ['POSITION'], source: null }
    render(plain())
    expect(screen.getByText('Colour')).toBeInTheDocument()
    expect(screen.queryByText('Position')).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Following the desk selection' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Selection:/ })).not.toBeInTheDocument()
  })

  it('says the head count on the cell count’s hover, and draws the key hints', () => {
    render(plain())
    expect(screen.getByText('2 cells')).toHaveAttribute('title', expect.stringMatching(/^2 heads · /))
    expect(screen.getByText('⏎')).toBeTruthy()
  })

  it('says Nothing selected in the reserved arm', () => {
    render(plain({ cells: [], paletteTargets: [], selection: null }))
    expect(screen.getByText('Nothing selected')).toBeInTheDocument()
  })
})
