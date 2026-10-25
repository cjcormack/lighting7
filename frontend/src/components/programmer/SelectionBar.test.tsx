// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'

/**
 * Row C, the programmer's arm (programmer-toolbar plan D3–D5, D12) and the plain lists' arm.
 *
 * jsdom measures nothing, so the row's rung (`rowCBand.ts`) is set through the measuring hook; the
 * container-query word folds are pinned by the class they carry.
 */
const shortViewport = vi.hoisted(() => ({ current: false }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => shortViewport.current }))
const measured = vi.hoisted(() => ({ band: 0 }))
vi.mock('@/hooks/useContainerBand', () => ({ useContainerBand: () => [() => {}, measured.band] }))
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
    usePressFamilies: (local: AttributeFamily[] | null) => (useDeskFollow() ? desk.snapshot.families : local),
  }
})
// The chip's store-connected pieces have suites of their own.
vi.mock('@/components/fixtureColours/SelectByColour', () => ({ SelectByColourSub: () => null }))
const rig = vi.hoisted(() => ({ wordClass: null as string | null }))
vi.mock('@/components/fixtures-list/SelectionToolbar', () => ({
  LocateHighlightButtons: ({ wordClass }: { wordClass: string }) => {
    rig.wordClass = wordClass
    return <span data-testid="locate-highlight" />
  },
  useLocateSelection: () => ({ allLocated: false, locateSelection: () => {} }),
}))
vi.mock('@/hooks/useStoredLocateActions', () => ({
  useStoredLocateActions: () => ({ busy: false, projectId: 1, storeFromCurrent: () => {}, clearLocate: () => {} }),
}))
vi.mock('@/components/fixtureSheet/useRelease', () => ({ useReleaseTargets: () => async () => {} }))

const { SelectionBar } = await import('./SelectionBar')
const { ProgrammerSelectionVerbs } = await import('./ProgrammerSelectionVerbs')
import { MID_FOLDED_CLASS, PHONE_FOLDED_CLASS } from '@/components/sheet/toolbarFolds'
import type { DeskSelectionSnapshot } from '@/api/selectionApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'

const CELLS: CellRef<ColumnKey>[] = [
  { rowId: 'fixture:a', col: 'colour' },
  { rowId: 'fixture:b', col: 'colour' },
]
const TWO = [
  { type: 'fixture' as const, key: 'a' },
  { type: 'fixture' as const, key: 'b' },
]
const BANDS = { desk: 0, lap: 1, tab: 2, phone: 3 }

const NONE = { targets: [], onSubselect: vi.fn(), onSelectTargets: vi.fn(), onDeselect: vi.fn(), locateTargets: [], writeTargets: [] }

const heads = {
  targets: TWO,
  onSubselect: vi.fn(),
  onSelectTargets: vi.fn(),
  onDeselect: vi.fn(),
  locateTargets: TWO,
  writeTargets: [],
}

function verbs(cells: number): ReactNode {
  return (
    <ProgrammerSelectionVerbs
      heads={2}
      cells={cells}
      set={{
        title: 'Set',
        enabled: true,
        setRef: () => {},
        onSet: () => {},
        families: ['INTENSITY', 'COLOUR'],
        onSetFamily: () => {},
      }}
      fan={<button type="button">Fan</button>}
      release={{ targets: [], heads: 2, onReleaseCells: () => {}, cellsTitle: 'Clear' }}
      locateTargets={TWO}
      writeTargets={[]}
    />
  )
}

function bar(over: Partial<React.ComponentProps<typeof SelectionBar>> = {}) {
  const cells = over.cells ?? CELLS
  return (
    <SelectionBar
      projectId={1}
      selection={verbs(cells.length)}
      cells={cells}
      cellEntryKey={false}
      cellClearKey={false}
      paletteTargets={TWO}
      marqueeDragging={false}
      onRemask={(families) => desk.masks.push(families == null ? null : [...families])}
      heads={heads}
      {...over}
    />
  )
}

beforeEach(() => {
  shortViewport.current = false
  measured.band = BANDS.desk
  rig.wordClass = null
  desk.snapshot = { targets: [], families: null, source: null }
  desk.masks = []
})
afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

function follows(a: Element, b: Element): boolean {
  return (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
}

describe('the programmer’s row C', () => {
  it('reads which heads, what to do to them, and where they are on the rig — left to right', () => {
    desk.snapshot = { targets: [], families: ['COLOUR'], source: null }
    const { container } = render(bar())
    const chip = container.querySelector('[data-heads-chip]')!
    const mask = screen.getByRole('button', { name: 'Colour' })
    const set = screen.getByRole('button', { name: 'Set' })
    const fan = screen.getByRole('button', { name: 'Fan' })
    const release = container.querySelector('[data-release-selection]')!
    const rigVerbs = screen.getByTestId('locate-highlight')
    expect(follows(chip, mask) && follows(mask, set) && follows(set, fan) && follows(fan, release)).toBe(true)
    expect(follows(release, rigVerbs)).toBe(true)
    // The last cluster is pushed to the right edge: the verbs take the row's slack.
    expect(container.querySelector('[data-clusters]')).not.toBeNull()
  })

  it('counts heads and cells on the chip, and has no separate desk chip, hints or strip', () => {
    render(bar())
    expect(screen.getByText('2 heads · 2 cells')).toBeInTheDocument()
    expect(screen.queryByText('edit')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Following the desk selection/ })).not.toBeInTheDocument()
  })

  it('counts the heads alone over rows', () => {
    render(bar({ cells: [] }))
    expect(screen.getByText('2 heads')).toBeInTheDocument()
  })

  it('says Nothing selected on the chip, with the way to select, when there is no selection', () => {
    render(bar({ cells: [], paletteTargets: [], selection: null, heads: NONE }))
    expect(screen.getByText('Nothing selected')).toBeInTheDocument()
    expect(screen.getByText(/Drag across cells, or narrow from the heads menu/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deselect' })).not.toBeInTheDocument()
  })

  describe('across widths (D12)', () => {
    it('puts ⏎ on Set and ⌫ on Release, and words on Locate and Highlight, from 1100', () => {
      render(bar())
      expect(screen.getByRole('button', { name: 'Set' })).toHaveTextContent('⏎')
      expect(screen.getByRole('button', { name: 'Release 2 cells' })).toHaveTextContent('⌫')
      expect(rig.wordClass).toBe('inline')
    })

    it('drops the keys and Locate’s and Highlight’s words from 800 to 1099', () => {
      measured.band = BANDS.lap
      render(bar())
      expect(screen.getByRole('button', { name: 'Set' })).not.toHaveTextContent('⏎')
      expect(screen.getByRole('button', { name: 'Release 2 cells' })).not.toHaveTextContent('⌫')
      expect(rig.wordClass).toBe('hidden')
      expect(screen.getByText('2 heads · 2 cells')).toBeInTheDocument()
    })

    it('abbreviates the mask from 600 to 799', () => {
      measured.band = BANDS.tab
      desk.snapshot = { targets: [], families: ['COLOUR', 'BEAM'], source: null }
      render(bar())
      expect(screen.getByRole('button', { name: 'Col · Beam' })).toBeInTheDocument()
    })

    it('on a phone: cells alone on the chip, no ×, the mask a glyph, and Locate and Highlight off the row', () => {
      measured.band = BANDS.phone
      desk.snapshot = { targets: [], families: ['COLOUR'], source: null }
      render(bar())
      expect(screen.getByText('2 cells')).toBeInTheDocument()
      expect(screen.queryByText('2 heads · 2 cells')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Deselect' })).not.toBeInTheDocument()
      expect(screen.getByLabelText('Mask: Colour')).toBeInTheDocument()
      expect(screen.queryByTestId('locate-highlight')).not.toBeInTheDocument()
    })
  })

  describe('the mask', () => {
    it('reads the desk’s mask while following, over rows with no cells', () => {
      desk.snapshot = { targets: [], families: ['POSITION'], source: { kind: 'window', name: 'Screen 2' } }
      render(bar({ cells: [] }))
      expect(screen.getByRole('button', { name: 'Position' })).toBeInTheDocument()
    })

    it('reads All while following an unmasked desk, whatever the marquee names', () => {
      render(bar())
      expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument()
    })

    it('is a reading of the marquee’s own families once unlinked', () => {
      desk.snapshot = { targets: [], families: ['POSITION'], source: null }
      unlinkFromDesk({ targets: [], families: null })
      render(bar({ onRemask: null }))
      expect(screen.queryByRole('button', { name: 'Colour' })).not.toBeInTheDocument()
      expect(screen.getByText('Colour')).toBeInTheDocument()
      expect(screen.queryByText('Position')).not.toBeInTheDocument()
    })

    it('is the picker while following, writing a real change through the bridge', () => {
      desk.snapshot = { targets: [{ type: 'fixture', key: 'a' }], families: null, source: null }
      render(bar({ cells: [] }))
      fireEvent.click(screen.getByRole('button', { name: 'All' }))
      fireEvent.click(screen.getByRole('switch', { name: 'All attributes' }))
      expect(desk.masks).toEqual([])
      fireEvent.click(screen.getByLabelText('Beam'))
      expect(desk.masks).toEqual([['INTENSITY', 'POSITION', 'COLOUR']])
    })
  })

  it('holds row C out of the flow on a short viewport until something is selected', () => {
    shortViewport.current = true
    const { container } = render(bar({ cells: [], paletteTargets: [], selection: null, heads: NONE }))
    expect(container.querySelector('[data-heads-chip]')).toBeNull()
  })

  it('is a 40px line in both arms — the chrome system', () => {
    const live = render(bar())
    expect(live.container.querySelector('[data-clusters]')!.className).toContain('h-10')
    live.unmount()
    const reserved = render(bar({ cells: [], paletteTargets: [], selection: null, heads: NONE }))
    expect(reserved.container.querySelector('[data-heads-chip]')!.parentElement!.className).toContain('h-10')
  })
})

describe('the plain lists’ row C', () => {
  function plain(over: Partial<React.ComponentProps<typeof SelectionBar>> = {}) {
    return bar({ projectId: undefined, heads: undefined, selection: <button type="button">Deselect all</button>, ...over })
  }

  it('folds the head count at 800 and the family badge at 600, and keeps the cell count', () => {
    render(plain())
    expect(screen.getByText('2 heads')).toHaveClass(MID_FOLDED_CLASS)
    expect(screen.getByText('Colour')).toHaveClass(PHONE_FOLDED_CLASS)
    expect(screen.getByText('2 cells')).not.toHaveClass(MID_FOLDED_CLASS)
  })

  it('keeps the head count at every width when it is the only count', () => {
    render(plain({ cells: [] }))
    expect(screen.getByText('2 heads')).not.toHaveClass(MID_FOLDED_CLASS)
  })

  it('says the head count on the cell count’s hover', () => {
    render(plain())
    expect(screen.getByText('2 cells')).toHaveAttribute('title', expect.stringMatching(/^2 heads · /))
  })

  it('reads the marquee’s families, which never bridge, and draws no heads chip or desk chip', () => {
    desk.snapshot = { targets: [], families: ['POSITION'], source: null }
    const { container } = render(plain())
    expect(screen.getByText('Colour')).toBeInTheDocument()
    expect(container.querySelector('[data-heads-chip]')).toBeNull()
    expect(screen.queryByRole('button', { name: /Following the desk|This window/ })).not.toBeInTheDocument()
  })
})
