// @vitest-environment jsdom
import { cueRow, markerRow } from '@/test/cueEntries'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { CueStack, CueStackCueEntry, CueStackCueRow, CueStackMarkerRow } from '@/api/cueStacksApi'

/**
 * Characterisation suite written before session 2b (`desk-simplification-plan.md` §Session 2b,
 * phase 0). Nothing under `components/runner/` had a test. The two scroll effects are
 * pinned because 2b changes what feeds *both*: the active-cue effect gains a sibling fade cursor,
 * and the expansion effect stops being driven by a single scalar.
 */

const scrollIntoView = vi.fn()

vi.mock('@/store/cueStacks', () => ({
  useReorderCueStackCuesMutation: () => [vi.fn()],
  useSortCueStackByCueNumberMutation: () => [vi.fn()],
}))

// Rows must carry `data-entry-row` — that attribute is the selector both scroll effects query, so
// a probe without it would make them silently no-ops and every assertion here vacuous. Rows are
// entries, so the probe is keyed by entry id.
const rowProps = new Map<
  number,
  { isStandby?: boolean; isActive?: boolean; expanded?: boolean; locked?: boolean; onSetStandby?: () => void }
>()
// The header's Move in dark chip reads the store; its words are `StackMovingChip.test.tsx`'s.
vi.mock('@/components/cues/StackMovingChip', () => ({
  StackMovingChip: ({ stackId }: { stackId: number }) => <span data-testid="moving-chip" data-stack={stackId} />,
}))
vi.mock('./CueCardEditor', () => ({
  CueCardEditor: (p: {
    cue: CueStackCueRow
    isStandby?: boolean
    isActive?: boolean
    expanded?: boolean
    locked?: boolean
    onSetStandby?: () => void
  }) => {
    rowProps.set(p.cue.entryId, {
      isStandby: p.isStandby,
      isActive: p.isActive,
      expanded: p.expanded,
      locked: p.locked,
      onSetStandby: p.onSetStandby,
    })
    return <div data-entry-row={p.cue.entryId} data-testid={`cue-${p.cue.id}`} />
  },
}))
vi.mock('./ShowMarkerRow', () => ({
  ShowMarkerRow: (p: { id: number }) => (
    <div data-entry-row={p.id} data-testid={`marker-${p.id}`} />
  ),
}))

vi.mock('./StackTimeline', () => ({ StackTimeline: () => <div data-testid="timeline" /> }))
vi.mock('./CueSheet', () => ({ CueSheet: () => <div data-testid="table" /> }))

import { StackDetail } from './StackDetail'

/** A cue row of the stack, or — with `cueType: 'MARKER'` — a marker entry, its entry id `id + 100`. */
function cue(id: number, over?: Partial<CueStackCueRow>): CueStackCueRow
function cue(id: number, over: { cueType: 'MARKER'; name?: string }): CueStackMarkerRow
function cue(
  id: number,
  over: Partial<CueStackCueRow> | { cueType: 'MARKER'; name?: string } = {},
): CueStackCueEntry {
  if (over.cueType === 'MARKER') return markerRow(id + 100, over.name ?? `Q${id}`, { sortOrder: id })
  return cueRow(id, { name: `Q${id}`, sortOrder: id, fadeDurationMs: 1000, cueNumber: String(id), ...(over as Partial<CueStackCueRow>) })
}

const mkStack = (over: Partial<CueStack> = {}): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cue(1), cue(2), cue(3)],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

/** Cursors are entries: a cue's entry in these fixtures is its id + 100. */
interface Props {
  stack?: CueStack
  activeEntryId?: number | null
  standbyEntryId?: number | null
  expandedEntryId?: number | null
  onSetStandby?: (cueId: number, entryId: number) => void
  locked?: boolean
  unlockedWarning?: boolean
  view?: 'cards' | 'list' | 'timeline'
}

function view(p: Props = {}) {
  return (
    <MemoryRouter>
    <StackDetail
      stack={p.stack ?? mkStack()}
      projectId={1}
      activeEntryId={p.activeEntryId ?? null}
      standbyEntryId={p.standbyEntryId}
      isExpanded={(id) => id === (p.expandedEntryId ?? null)}
      onToggleExpanded={vi.fn()}
      openedEntryId={p.expandedEntryId ?? null}
      onSetStandby={p.onSetStandby}
      locked={p.locked}
      unlockedWarning={p.unlockedWarning}
      onRecordIntoStack={vi.fn()}
      onBack={vi.fn()}
      onAddMarker={vi.fn()}
      onMarkerRename={vi.fn()}
      onMarkerDelete={vi.fn()}
      view={p.view}
    />
    </MemoryRouter>
  )
}

beforeEach(() => {
  Element.prototype.scrollIntoView = scrollIntoView
  rowProps.clear()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('StackDetail', () => {
  it('renders standard cues as rows and markers as dividers', () => {
    render(view({ stack: mkStack({ cues: [cue(1), cue(2, { cueType: 'MARKER' })] }) }))
    expect(screen.getByTestId('cue-1')).toBeTruthy()
    // A marker row is keyed by its entry id — `2 + 100` in these fixtures.
    expect(screen.getByTestId('marker-102')).toBeTruthy()
  })

  it('scrolls the active cue into view on mount', () => {
    render(view({ activeEntryId: 102 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  it('re-scrolls when the active cue moves', () => {
    const { rerender } = render(view({ activeEntryId: 102 }))
    scrollIntoView.mockClear()
    rerender(view({ activeEntryId: 103 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  it('does not scroll when no cue is active', () => {
    render(view())
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('scrolls an expanded card into view, smoothly', () => {
    // Two effects with deliberately different behaviour: the marker jumps (`nearest`), an operator
    // opening a card gets a smooth scroll. 2b must not collapse them into one.
    render(view({ expandedEntryId: 103 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' })
  })

  it('re-scrolls when a different card is expanded', () => {
    const { rerender } = render(view({ expandedEntryId: 101 }))
    scrollIntoView.mockClear()
    rerender(view({ expandedEntryId: 103 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' })
  })

  it('does not scroll when a card is collapsed', () => {
    const { rerender } = render(view({ expandedEntryId: 101 }))
    scrollIntoView.mockClear()
    rerender(view({ expandedEntryId: null }))
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('marks the active and expanded cue on the row', () => {
    render(view({ activeEntryId: 102, expandedEntryId: 103 }))
    expect(rowProps.get(102)?.isActive).toBe(true)
    expect(rowProps.get(103)?.expanded).toBe(true)
    expect(rowProps.get(101)?.isActive).toBe(false)
  })

  it('offers the edit affordances while unlocked', () => {
    render(view())
    expect(screen.getByLabelText(/Record the programmer into/)).toBeTruthy()
    expect(screen.getByLabelText('Add separator')).toBeTruthy()
    // The stack's settings — its set — from inside the stack, as the stack list's menu offers them.
    expect(screen.getByLabelText('Stack settings…')).toBeTruthy()
  })

  it('withholds them while locked', () => {
    // Hidden rather than disabled: a row of greyed-out buttons reads as breakage, absence reads as
    // "not now". Recording a cue and adding a separator both change the show.
    render(view({ locked: true }))
    expect(screen.queryByLabelText(/Record the programmer into/)).toBeNull()
    expect(screen.queryByLabelText('Add separator')).toBeNull()
    expect(screen.queryByLabelText('Stack settings…')).toBeNull()
  })

  it('draws what the stack moves in the dark in its header, locked or not — a read-out', () => {
    render(view({ locked: true }))
    expect(screen.getByTestId('moving-chip')).toHaveAttribute('data-stack', '10')
  })

  it('passes the lock down to every row', () => {
    // The rows are where dragging is actually disabled — via dnd-kit's own `disabled`, so a drag is
    // impossible rather than merely discouraged.
    render(view({ locked: true }))
    expect(rowProps.get(101)?.locked).toBe(true)
  })

  it('withholds the out-of-order fix while locked', () => {
    // "Fix Order" re-sorts a whole stack in one press — the single most show-changing thing on this
    // surface, and it used to sit in a banner that appears on its own.
    const jumbled = mkStack({
      cues: [cue(1, { cueNumber: '3' }), cue(2, { cueNumber: '2' }), cue(3, { cueNumber: '1' })],
    })
    render(view({ stack: jumbled }))
    expect(screen.getByText('Cue numbers are out of order.')).toBeTruthy()

    cleanup()
    render(view({ stack: jumbled, locked: true }))
    expect(screen.queryByText('Cue numbers are out of order.')).toBeNull()
  })

  it('washes its navigation row with the rest of the chrome band', () => {
    const { container } = render(view({ unlockedWarning: true }))
    expect(container.querySelector('.bg-amber-400\\/15')).toBeTruthy()
  })

  it('leaves the navigation row plain by default', () => {
    const { container } = render(view())
    expect(container.querySelector('.bg-amber-400\\/15')).toBeNull()
  })

  it('forwards a standby cue to the row when it is given one', () => {
    // The prop works; nothing currently passes it. See the companion assertion in
    // ShowView.test.tsx — 2b is what finally wires it through.
    render(view({ standbyEntryId: 103 }))
    expect(rowProps.get(103)?.isStandby).toBe(true)
    expect(rowProps.get(101)?.isStandby).toBe(false)
  })

  it('keys a cue twice in one stack by entry: live, expanded and armed per place', () => {
    // Cue 5 sits at entries 105 and 205 of one stack (cue model plan D7).
    const twice = mkStack({ cues: [cue(5), cue(6), cue(5, { entryId: 205, cueNumber: '9' })] })
    const onSetStandby = vi.fn()
    render(view({ stack: twice, activeEntryId: 205, expandedEntryId: 105, onSetStandby }))
    expect(rowProps.get(205)?.isActive).toBe(true)
    expect(rowProps.get(105)?.isActive).toBe(false)
    expect(rowProps.get(105)?.expanded).toBe(true)
    expect(rowProps.get(205)?.expanded).toBe(false)
    // Arming the first place names its entry, though the cue is live at the other.
    rowProps.get(105)?.onSetStandby?.()
    expect(onSetStandby).toHaveBeenCalledWith(5, 105)
  })
})

describe('the Timeline from 1100 px (C6)', () => {
  /** The stack's content box, as the width hook measures it on mount. */
  function atWidth(width: number) {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width, height: 600 } as DOMRect)
  }

  afterEach(() => vi.restoreAllMocks())

  it('offers three views, and draws the Timeline, where the stack is 1100 px wide', () => {
    atWidth(1100)
    render(view({ view: 'timeline' }))
    expect(screen.getByRole('link', { name: 'Cards' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Table' })).toBeTruthy()
    expect(screen.getByLabelText('Timeline').getAttribute('aria-current')).toBe('page')
    expect(screen.getByTestId('timeline')).toBeTruthy()
  })

  it('hides the Timeline below 1100 px, and a linked or remembered one draws as the Table', () => {
    atWidth(1099)
    render(view({ view: 'timeline' }))
    expect(screen.queryByLabelText('Timeline')).toBeNull()
    expect(screen.getByLabelText('Table').getAttribute('aria-current')).toBe('page')
    expect(screen.getByTestId('table')).toBeTruthy()
    expect(screen.queryByTestId('timeline')).toBeNull()
  })

  it('keeps the Table and the cards as they were at any width', () => {
    atWidth(800)
    render(view({ view: 'list' }))
    expect(screen.getByTestId('table')).toBeTruthy()
    expect(screen.queryByLabelText('Timeline')).toBeNull()
  })
})
