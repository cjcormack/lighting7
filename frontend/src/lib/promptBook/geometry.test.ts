import { describe, expect, it } from 'vitest'
import {
  clientRectsToRegion,
  cornersToRect,
  flattenCueOrder,
  flattenShowRows,
  mergeRectsByLine,
  moveRegionVertically,
  nearestAnchoredCue,
  orderedCueIdsForInsert,
  positionLabel,
  positionLabelFor,
  rectToStyle,
} from './geometry'
import type { CueStack, CueStackCueEntry } from '../../api/cueStacksApi'
import type { CueAnchorDto, Region } from '../../api/promptBooksApi'
import type { FlatCue } from './desync'

describe('rectToStyle', () => {
  it('converts normalized coords to CSS percentages', () => {
    expect(rectToStyle({ page: 0, x: 0.06, y: 0.25, w: 0.88, h: 0.04 })).toMatchObject({
      left: '6%',
      top: '25%',
      width: '88%',
      height: '4%',
    })
  })
})

describe('moveRegionVertically', () => {
  const region = [
    { page: 0, x: 0.1, y: 0.3, w: 0.8, h: 0.05 },
    { page: 0, x: 0.1, y: 0.9, w: 0.8, h: 0.05 },
  ]

  it('moves every rect by the same delta', () => {
    const moved = moveRegionVertically(region, 0.02)
    expect(moved[0].y).toBeCloseTo(0.32)
    expect(moved[1].y).toBeCloseTo(0.92)
  })

  it('clamps downward movement so no rect leaves the page', () => {
    // Second rect can only move down by 0.05 before hitting the bottom.
    const moved = moveRegionVertically(region, 0.5)
    expect(moved[1].y).toBeCloseTo(0.95)
    expect(moved[0].y).toBeCloseTo(0.35)
  })

  it('clamps upward movement at the top edge', () => {
    const moved = moveRegionVertically(region, -0.9)
    expect(moved[0].y).toBeCloseTo(0)
    expect(moved[1].y).toBeCloseTo(0.6)
  })
})

describe('cornersToRect', () => {
  it('orders corners regardless of drag direction', () => {
    const r = cornersToRect(2, { x: 0.8, y: 0.6 }, { x: 0.2, y: 0.1 })
    expect(r).toMatchObject({ page: 2, x: 0.2, y: 0.1 })
    expect(r.w).toBeCloseTo(0.6)
    expect(r.h).toBeCloseTo(0.5)
  })

  it('applies a minimum size floor to a click without drag', () => {
    const r = cornersToRect(0, { x: 0.5, y: 0.5 }, { x: 0.5, y: 0.5 })
    expect(r.w).toBeGreaterThan(0)
    expect(r.h).toBeGreaterThan(0)
  })
})

describe('mergeRectsByLine', () => {
  it('unions per-run slivers on the same line into one band', () => {
    const merged = mergeRectsByLine([
      { page: 0, x: 0.1, y: 0.2, w: 0.15, h: 0.02 },
      { page: 0, x: 0.25, y: 0.201, w: 0.2, h: 0.02 }, // adjacent run, tiny y jitter
    ])
    expect(merged).toHaveLength(1)
    expect(merged[0]).toMatchObject({ page: 0, x: 0.1 })
    expect(merged[0].w).toBeCloseTo(0.35)
  })

  it('keeps separate lines separate', () => {
    const merged = mergeRectsByLine([
      { page: 0, x: 0.1, y: 0.2, w: 0.3, h: 0.02 },
      { page: 0, x: 0.1, y: 0.26, w: 0.3, h: 0.02 },
    ])
    expect(merged).toHaveLength(2)
  })

  it('does not snowball tightly-leaded lines into one band', () => {
    // Single-spaced script: line pitch ≈ 1.05× the glyph-box height. A midpoint±
    // tolerance test merged these; overlap-based bucketing keeps them apart.
    const merged = mergeRectsByLine([
      { page: 0, x: 0.1, y: 0.2, w: 0.3, h: 0.02 },
      { page: 0, x: 0.1, y: 0.221, w: 0.3, h: 0.02 },
      { page: 0, x: 0.1, y: 0.242, w: 0.3, h: 0.02 },
    ])
    expect(merged).toHaveLength(3)
  })

  it('splits a two-column line on a large horizontal gap (name | dialogue)', () => {
    const merged = mergeRectsByLine([
      { page: 0, x: 0.06, y: 0.2, w: 0.08, h: 0.02 }, // character name
      { page: 0, x: 0.4, y: 0.2, w: 0.3, h: 0.02 }, // dialogue, big gap before it
    ])
    expect(merged).toHaveLength(2)
    expect(merged[0].x).toBeCloseTo(0.06)
    expect(merged[1].x).toBeCloseTo(0.4)
  })

  it('groups rects by page', () => {
    const merged = mergeRectsByLine([
      { page: 0, x: 0.1, y: 0.9, w: 0.3, h: 0.02 },
      { page: 1, x: 0.1, y: 0.05, w: 0.3, h: 0.02 },
    ])
    expect(new Set(merged.map((r) => r.page))).toEqual(new Set([0, 1]))
  })
})

describe('clientRectsToRegion', () => {
  // Two stacked pages, each 100px wide × 200px tall, with a 20px gap between.
  const pages = [
    { page: 0, left: 0, top: 0, width: 100, height: 200 },
    { page: 1, left: 0, top: 220, width: 100, height: 200 },
  ]

  it('attributes a rect to the page containing its centre and normalizes', () => {
    const region = clientRectsToRegion([{ left: 10, top: 20, right: 50, bottom: 40 }], pages)
    expect(region).toHaveLength(1)
    expect(region[0]).toMatchObject({ page: 0 })
    expect(region[0].x).toBeCloseTo(0.1)
    expect(region[0].y).toBeCloseTo(0.1)
    expect(region[0].w).toBeCloseTo(0.4)
  })

  it('drops rects that fall in the inter-page gap', () => {
    // Centre at y≈210 is between page 0 (0–200) and page 1 (220–420).
    const region = clientRectsToRegion([{ left: 10, top: 205, right: 50, bottom: 215 }], pages)
    expect(region).toHaveLength(0)
  })

  it('discards zero-width slivers', () => {
    const region = clientRectsToRegion([{ left: 10, top: 20, right: 10.2, bottom: 40 }], pages)
    expect(region).toHaveLength(0)
  })

  it('keeps rects across both pages for a multi-page selection', () => {
    const region = clientRectsToRegion(
      [
        { left: 10, top: 180, right: 90, bottom: 195 }, // bottom of page 0
        { left: 10, top: 225, right: 90, bottom: 240 }, // top of page 1
      ],
      pages,
    )
    expect(new Set(region.map((r) => r.page))).toEqual(new Set([0, 1]))
  })
})

describe('flattenCueOrder', () => {
  function cue(id: number, overrides: Partial<CueStackCueEntry> = {}): CueStackCueEntry {
    return {
      id,
      name: `cue-${id}`,
      sortOrder: 0,
      layerCount: 0,
      adHocEffectCount: 0,
      autoAdvance: false,
      autoAdvanceDelayMs: null,
      fadeDurationMs: null,
      fadeCurve: 'LINEAR',
      cueNumber: null,
      cueNumberAuto: false,
      notes: null,
      cueType: 'STANDARD',
      ...overrides,
    }
  }

  function stack(id: number, name: string, cues: CueStackCueEntry[], sortOrder = 0): CueStack {
    return { id, name, loop: false, sortOrder, type: 'STACK', label: null, cues, activeCueId: null, nextCueId: null, canEdit: true, canDelete: true }
  }

  function separator(id: number, label: string, sortOrder: number): CueStack {
    return { id, name: label, loop: false, sortOrder, type: 'SEPARATOR', label, cues: [], activeCueId: null, nextCueId: null, canEdit: true, canDelete: true }
  }

  it('flattens runnable stacks in show order, skipping separators', () => {
    const result = flattenCueOrder([
      stack(11, 'Act Two', [cue(3)], 1),
      stack(10, 'Act One', [cue(1, { cueNumber: '1' }), cue(2, { cueNumber: '2.5' })], 0),
      separator(12, 'End', 2),
    ])
    expect(result.map((c) => c.cueId)).toEqual([1, 2, 3])
    expect(result[0]).toMatchObject({ stackId: 10, stackName: 'Act One', label: 'Q1' })
    expect(result[2]).toMatchObject({ stackId: 11, label: 'cue-3' })
  })

  it('excludes marker-type cues within a stack', () => {
    const result = flattenCueOrder([stack(10, 'S', [cue(1), cue(2, { cueType: 'MARKER' }), cue(3)])])
    expect(result.map((c) => c.cueId)).toEqual([1, 3])
  })

  it('returns empty for missing inputs', () => {
    expect(flattenCueOrder(undefined)).toEqual([])
    expect(flattenCueOrder([])).toEqual([])
  })
})

describe('flattenShowRows', () => {
  function cue(id: number, overrides: Partial<CueStackCueEntry> = {}): CueStackCueEntry {
    return {
      id,
      name: `cue-${id}`,
      sortOrder: 0,
      layerCount: 0,
      adHocEffectCount: 0,
      autoAdvance: false,
      autoAdvanceDelayMs: null,
      fadeDurationMs: null,
      fadeCurve: 'LINEAR',
      cueNumber: null,
      cueNumberAuto: false,
      notes: null,
      cueType: 'STANDARD',
      ...overrides,
    }
  }

  function stack(id: number, name: string, cues: CueStackCueEntry[], sortOrder = 0): CueStack {
    return { id, name, loop: false, sortOrder, type: 'STACK', label: null, cues, activeCueId: null, nextCueId: null, canEdit: true, canDelete: true }
  }

  function separator(id: number, label: string, sortOrder: number): CueStack {
    return { id, name: label, loop: false, sortOrder, type: 'SEPARATOR', label, cues: [], activeCueId: null, nextCueId: null, canEdit: true, canDelete: true }
  }

  it('keeps in-stack MARKER cues as separator rows and (single stack) emits no stack header', () => {
    const rows = flattenShowRows([
      stack(10, 'Act One', [
        cue(1, { cueNumber: '1' }),
        cue(2, { cueType: 'MARKER', name: 'Interval' }),
        cue(3),
      ]),
    ])
    expect(rows.map((r) => r.type)).toEqual(['cue', 'separator', 'cue'])
    expect(rows[1]).toMatchObject({ type: 'separator', source: 'cue', id: 2, name: 'Interval' })
    expect(rows.some((r) => r.type === 'header')).toBe(false)
  })

  it('distinguishes a SEPARATOR stack from a MARKER cue that share a numeric id', () => {
    // Both origins produce separator rows; keyed on id alone they would collide (both id 5).
    const rows = flattenShowRows([
      stack(1, 'Act One', [cue(9), cue(5, { cueType: 'MARKER', name: 'in-stack marker' })], 0),
      separator(5, 'project separator', 1),
    ])
    const seps = rows.filter((r) => r.type === 'separator')
    expect(seps).toEqual([
      { type: 'separator', source: 'cue', id: 5, name: 'in-stack marker' },
      { type: 'separator', source: 'stack', id: 5, name: 'project separator' },
    ])
    // A source+id composite key is unique even though the raw ids collide.
    const keys = seps.map((r) => (r.type === 'separator' ? `${r.source}-${r.id}` : ''))
    expect(new Set(keys).size).toBe(seps.length)
  })

  it('emits a per-stack header only when the show spans more than one runnable stack', () => {
    const rows = flattenShowRows([stack(10, 'Act One', [cue(1)], 0), stack(11, 'Act Two', [cue(2)], 1)])
    expect(rows.map((r) => r.type)).toEqual(['header', 'cue', 'header', 'cue'])
    expect(rows.filter((r) => r.type === 'header').map((r) => (r.type === 'header' ? r.stackName : ''))).toEqual([
      'Act One',
      'Act Two',
    ])
  })

  it('orders by stack sortOrder and renders project-level SEPARATOR stacks as separators', () => {
    const rows = flattenShowRows([
      stack(11, 'B', [cue(2)], 1),
      stack(10, 'A', [cue(1)], 0),
      separator(12, 'Interval', 2),
    ])
    const cueIds = rows.flatMap((r) => (r.type === 'cue' ? [r.cue.cueId] : []))
    expect(cueIds).toEqual([1, 2])
    expect(rows.some((r) => r.type === 'separator' && r.source === 'stack' && r.name === 'Interval')).toBe(true)
  })

  it('returns empty for missing inputs', () => {
    expect(flattenShowRows(undefined)).toEqual([])
    expect(flattenShowRows([])).toEqual([])
  })
})

describe('positionLabelFor', () => {
  it('maps a 0-based page to a 1-based number with no cover page (default)', () => {
    expect(positionLabelFor(0, 0.1)).toBe('top of p. 1')
    expect(positionLabelFor(11, 0.5)).toBe('middle of p. 12')
  })

  it('buckets y into bands regardless of the offset', () => {
    expect(positionLabelFor(5, 0.05)).toBe('top of p. 6')
    expect(positionLabelFor(5, 0.3)).toBe('upper of p. 6')
    expect(positionLabelFor(5, 0.5)).toBe('middle of p. 6')
    expect(positionLabelFor(5, 0.7)).toBe('lower of p. 6')
    expect(positionLabelFor(5, 0.95)).toBe('bottom of p. 6')
  })

  it('subtracts a single cover page so the first content page reads p. 1', () => {
    // PDF page 0 is the cover; PDF page 1 is the script's printed page 1.
    expect(positionLabelFor(1, 0.1, 1)).toBe('top of p. 1')
    expect(positionLabelFor(9, 0.5, 1)).toBe('middle of p. 9')
  })

  it('handles multiple front-matter pages', () => {
    // 2 leading pages → PDF page 2 is printed page 1.
    expect(positionLabelFor(2, 0.1, 2)).toBe('top of p. 1')
    expect(positionLabelFor(5, 0.5, 2)).toBe('middle of p. 4')
  })

  it('labels a cue anchored on the cover page instead of numbering it', () => {
    expect(positionLabelFor(0, 0.1, 1)).toBe('top of the cover')
    // With more than one cover page, disambiguate by position within the front matter.
    expect(positionLabelFor(0, 0.1, 3)).toBe('top of cover p. 1')
    expect(positionLabelFor(2, 0.5, 3)).toBe('middle of cover p. 3')
  })
})

describe('positionLabel', () => {
  it('reduces a region to its earliest rect and offsets by coverPages', () => {
    const region = [
      { page: 4, x: 0.1, y: 0.6, w: 0.5, h: 0.05 },
      { page: 3, x: 0.1, y: 0.2, w: 0.5, h: 0.05 },
    ]
    // Earliest rect is page 3, y 0.2; with 1 cover page → printed p. 3.
    expect(positionLabel(region, 1)).toBe('upper of p. 3')
  })

  it('returns empty for an empty region', () => {
    expect(positionLabel([], 2)).toBe('')
  })
})

/** A single-rect region at a reading position — shared by the anchor-map fixtures below. */
function regionAt(page: number, y: number): Region {
  return [{ page, x: 0.1, y, w: 0.5, h: 0.03 }]
}

/** Build an anchor map from `[cueId, page, y]` tuples. */
function anchors(entries: Array<[number, number, number]>): Map<number, CueAnchorDto> {
  return new Map(entries.map(([cueId, page, y]) => [cueId, { cueId, region: regionAt(page, y), label: null }]))
}

describe('orderedCueIdsForInsert', () => {
  function cue(id: number): CueStackCueEntry {
    return {
      id,
      name: `cue-${id}`,
      sortOrder: 0,
      layerCount: 0,
      adHocEffectCount: 0,
      autoAdvance: false,
      autoAdvanceDelayMs: null,
      fadeDurationMs: null,
      fadeCurve: 'LINEAR',
      cueNumber: null,
      cueNumberAuto: false,
      notes: null,
      cueType: 'STANDARD',
    }
  }
  function stack(cues: CueStackCueEntry[]): CueStack {
    return { id: 1, name: 'S', loop: false, sortOrder: 0, type: 'STACK', label: null, cues, activeCueId: null, nextCueId: null, canEdit: true, canDelete: true }
  }

  it('inserts between two anchored cues by reading position', () => {
    const s = stack([cue(1), cue(2)])
    const byCue = anchors([[1, 0, 0.2], [2, 0, 0.8]])
    expect(orderedCueIdsForInsert(s, byCue, regionAt(0, 0.5), 99)).toEqual([1, 99, 2])
  })

  it('inserts at the front when the selection precedes every anchor', () => {
    const s = stack([cue(1), cue(2)])
    const byCue = anchors([[1, 1, 0.2], [2, 1, 0.8]])
    expect(orderedCueIdsForInsert(s, byCue, regionAt(0, 0.9), 99)).toEqual([99, 1, 2])
  })

  it('appends when the selection follows every anchor (later page)', () => {
    const s = stack([cue(1), cue(2)])
    const byCue = anchors([[1, 0, 0.2], [2, 0, 0.8]])
    expect(orderedCueIdsForInsert(s, byCue, regionAt(3, 0.1), 99)).toEqual([1, 2, 99])
  })

  it('appends to a wholly unanchored stack (no positions to compare)', () => {
    const s = stack([cue(1), cue(2)])
    expect(orderedCueIdsForInsert(s, new Map(), regionAt(0, 0.5), 99)).toEqual([1, 2, 99])
  })

  it('ignores unanchored cues when placing (keeps their slot)', () => {
    // cue 2 is unanchored; the selection sits after cue 1 and before cue 3.
    const s = stack([cue(1), cue(2), cue(3)])
    const byCue = anchors([[1, 0, 0.2], [3, 0, 0.8]])
    expect(orderedCueIdsForInsert(s, byCue, regionAt(0, 0.5), 99)).toEqual([1, 99, 2, 3])
  })

  it('places the only cue of an empty stack first', () => {
    expect(orderedCueIdsForInsert(stack([]), new Map(), regionAt(0, 0.5), 99)).toEqual([99])
  })
})

describe('nearestAnchoredCue', () => {
  function order(...cues: Array<[cueId: number, stackId?: number]>): FlatCue[] {
    return cues.map(([cueId, stackId = 1]) => ({
      cueId,
      label: `Q${cueId}`,
      name: `cue-${cueId}`,
      fadeMs: null,
      fadeCurve: 'LINEAR',
      stackId,
      stackName: `Stack ${stackId}`,
    }))
  }

  it('borrows the previous anchored cue, even when a later one is nearer in the list', () => {
    const found = nearestAnchoredCue(2, order([1], [2], [3]), anchors([[1, 0, 0.2], [3, 0, 0.8]]))
    expect(found).toMatchObject({ direction: 'before', region: regionAt(0, 0.2) })
    expect(found?.cue.cueId).toBe(1)
  })

  it('skips other unanchored cues to reach the nearest anchor behind it', () => {
    const found = nearestAnchoredCue(4, order([1], [2], [3], [4]), anchors([[1, 0, 0.2]]))
    expect(found?.cue.cueId).toBe(1)
    expect(found?.direction).toBe('before')
  })

  it('borrows forwards for a cue ahead of every anchor', () => {
    const found = nearestAnchoredCue(1, order([1], [2], [3]), anchors([[3, 2, 0.4]]))
    expect(found).toMatchObject({ direction: 'after', region: regionAt(2, 0.4) })
    expect(found?.cue.cueId).toBe(3)
  })

  it('borrows across a stack boundary (show order, not stack order)', () => {
    // Stack 2 is wholly unanchored; it borrows from the end of stack 1.
    const found = nearestAnchoredCue(10, order([1, 1], [2, 1], [10, 2], [11, 2]), anchors([[1, 0, 0.2], [2, 1, 0.5]]))
    expect(found?.cue.cueId).toBe(2)
    expect(found?.cue.stackId).toBe(1)
  })

  it('returns null for a book with no anchors at all', () => {
    expect(nearestAnchoredCue(2, order([1], [2]), new Map())).toBeNull()
  })

  it('returns null for a cue that is not in the show order', () => {
    expect(nearestAnchoredCue(99, order([1], [2]), anchors([[1, 0, 0.2]]))).toBeNull()
  })
})
