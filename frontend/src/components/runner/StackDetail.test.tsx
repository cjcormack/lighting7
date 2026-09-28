// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'

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

// Rows must carry `data-cue-row` — that attribute is the selector both scroll effects query, so a
// probe without it would make them silently no-ops and every assertion here vacuous.
const rowProps = new Map<
  number,
  { isStandby?: boolean; isActive?: boolean; expanded?: boolean; locked?: boolean }
>()
vi.mock('./CueCardEditor', () => ({
  CueCardEditor: (p: {
    cue: CueStackCueEntry
    isStandby?: boolean
    isActive?: boolean
    expanded?: boolean
    locked?: boolean
  }) => {
    rowProps.set(p.cue.id, {
      isStandby: p.isStandby,
      isActive: p.isActive,
      expanded: p.expanded,
      locked: p.locked,
    })
    return <div data-cue-row={p.cue.id} data-testid={`cue-${p.cue.id}`} />
  },
}))
vi.mock('./ShowMarkerRow', () => ({
  ShowMarkerRow: (p: { id: number }) => (
    <div data-cue-row={p.id} data-testid={`marker-${p.id}`} />
  ),
}))

import { StackDetail } from './StackDetail'

const cue = (id: number, over: Partial<CueStackCueEntry> = {}): CueStackCueEntry => ({
  id,
  name: `Q${id}`,
  sortOrder: id,
  layerCount: 0,
  adHocEffectCount: 0,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
  fadeDurationMs: 1000,
  fadeCurve: 'LINEAR',
  cueNumber: String(id),
  cueNumberAuto: false,
  notes: null,
  cueType: 'STANDARD',
  ...over,
})

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
  canEdit: true,
  canDelete: true,
  ...over,
})

interface Props {
  stack?: CueStack
  activeCueId?: number | null
  standbyCueId?: number | null
  expandedCueId?: number | null
  locked?: boolean
  unlockedWarning?: boolean
}

function view(p: Props = {}) {
  return (
    <MemoryRouter>
    <StackDetail
      stack={p.stack ?? mkStack()}
      projectId={1}
      activeCueId={p.activeCueId ?? null}
      standbyCueId={p.standbyCueId}
      isExpanded={(id) => id === (p.expandedCueId ?? null)}
      onToggleExpanded={vi.fn()}
      openedCueId={p.expandedCueId ?? null}
      locked={p.locked}
      unlockedWarning={p.unlockedWarning}
      onRecordIntoStack={vi.fn()}
      onBack={vi.fn()}
      onAddMarker={vi.fn()}
      onMarkerRename={vi.fn()}
      onMarkerDelete={vi.fn()}
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
    expect(screen.getByTestId('marker-2')).toBeTruthy()
  })

  it('scrolls the active cue into view on mount', () => {
    render(view({ activeCueId: 2 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  it('re-scrolls when the active cue moves', () => {
    const { rerender } = render(view({ activeCueId: 2 }))
    scrollIntoView.mockClear()
    rerender(view({ activeCueId: 3 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  it('does not scroll when no cue is active', () => {
    render(view())
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('scrolls an expanded card into view, smoothly', () => {
    // Two effects with deliberately different behaviour: the marker jumps (`nearest`), an operator
    // opening a card gets a smooth scroll. 2b must not collapse them into one.
    render(view({ expandedCueId: 3 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' })
  })

  it('re-scrolls when a different card is expanded', () => {
    const { rerender } = render(view({ expandedCueId: 1 }))
    scrollIntoView.mockClear()
    rerender(view({ expandedCueId: 3 }))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' })
  })

  it('does not scroll when a card is collapsed', () => {
    const { rerender } = render(view({ expandedCueId: 1 }))
    scrollIntoView.mockClear()
    rerender(view({ expandedCueId: null }))
    expect(scrollIntoView).not.toHaveBeenCalled()
  })

  it('marks the active and expanded cue on the row', () => {
    render(view({ activeCueId: 2, expandedCueId: 3 }))
    expect(rowProps.get(2)?.isActive).toBe(true)
    expect(rowProps.get(3)?.expanded).toBe(true)
    expect(rowProps.get(1)?.isActive).toBe(false)
  })

  it('offers the edit affordances while unlocked', () => {
    render(view())
    expect(screen.getByLabelText(/Record the programmer into/)).toBeTruthy()
    expect(screen.getByLabelText('Add separator')).toBeTruthy()
  })

  it('withholds them while locked', () => {
    // Hidden rather than disabled: a row of greyed-out buttons reads as breakage, absence reads as
    // "not now". Recording a cue and adding a separator both change the show.
    render(view({ locked: true }))
    expect(screen.queryByLabelText(/Record the programmer into/)).toBeNull()
    expect(screen.queryByLabelText('Add separator')).toBeNull()
  })

  it('passes the lock down to every row', () => {
    // The rows are where dragging is actually disabled — via dnd-kit's own `disabled`, so a drag is
    // impossible rather than merely discouraged.
    render(view({ locked: true }))
    expect(rowProps.get(1)?.locked).toBe(true)
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
    render(view({ standbyCueId: 3 }))
    expect(rowProps.get(3)?.isStandby).toBe(true)
    expect(rowProps.get(1)?.isStandby).toBe(false)
  })
})
