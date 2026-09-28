import { describe, expect, it } from 'vitest'
import { computeWarnings, regionsOverlap, scriptPosition, type FlatCue } from './desync'
import type { AnnotationDto, CueAnchorDto, Rect } from '../../api/promptBooksApi'

function rect(page: number, y: number, overrides: Partial<Rect> = {}): Rect {
  return { page, x: 0.06, y, w: 0.88, h: 0.04, ...overrides }
}

function anchor(cueId: number, ...region: Rect[]): CueAnchorDto {
  return { cueId, region, label: `A${cueId}` }
}

function strike(id: number, ...region: Rect[]): AnnotationDto {
  return { id, kind: 'STRIKETHROUGH', region, text: null, color: null, tone: null }
}

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

describe('scriptPosition', () => {
  it('returns the earliest page, topmost y across a multi-rect region', () => {
    expect(scriptPosition([rect(2, 0.1), rect(1, 0.9), rect(1, 0.4)])).toEqual({ page: 1, y: 0.4 })
  })
})

describe('regionsOverlap', () => {
  it('detects intersection on the same page', () => {
    expect(regionsOverlap([rect(0, 0.2)], [rect(0, 0.21)])).toBe(true)
  })

  it('ignores identical boxes on different pages', () => {
    expect(regionsOverlap([rect(0, 0.2)], [rect(1, 0.2)])).toBe(false)
  })

  it('treats edge-touching boxes as non-overlapping', () => {
    // First rect spans y=[0.25, 0.5]; second starts exactly at 0.5 (binary-exact
    // values so the edge case isn't blurred by float rounding).
    expect(regionsOverlap([rect(0, 0.25, { h: 0.25 })], [rect(0, 0.5, { h: 0.25 })])).toBe(false)
  })
})

describe('computeWarnings', () => {
  it('returns nothing for a clean book', () => {
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.1)), anchor(2, rect(0, 0.5)), anchor(3, rect(1, 0.2))],
      [],
      order([1], [2], [3]),
    )
    expect(warnings).toEqual([])
  })

  it('says nothing about an unanchored cue', () => {
    // Pre-show states and auto-followed cues reasonably have no line to point at.
    expect(computeWarnings([anchor(1, rect(0, 0.1))], [], order([1], [2]))).toEqual([])
  })

  it('says nothing about a wholly unanchored stack', () => {
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.1))],
      [],
      order([1, 1], [10, 2], [11, 2], [12, 2]),
    )
    expect(warnings).toEqual([])
  })

  it('flags out-of-order anchors on the same page', () => {
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.5)), anchor(2, rect(0, 0.2))],
      [],
      order([1], [2]),
    )
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toMatchObject({ kind: 'out-of-order', cueId: 2 })
    expect(warnings[0].message).toContain('Q1')
  })

  it('flags out-of-order anchors across pages', () => {
    const warnings = computeWarnings(
      [anchor(1, rect(1, 0.1)), anchor(2, rect(0, 0.9))],
      [],
      order([1], [2]),
    )
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toMatchObject({ kind: 'out-of-order', cueId: 2 })
  })

  it('uses the earliest rect of a multi-rect region for ordering', () => {
    // Cue 2's region starts above cue 1 on the same page even though its second
    // rect is below — the reading position is the earliest rect.
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.4)), anchor(2, rect(0, 0.2), rect(0, 0.8))],
      [],
      order([1], [2]),
    )
    expect(warnings.map((w) => w.kind)).toEqual(['out-of-order'])
  })

  it('skips unanchored cues when checking order', () => {
    // 1 (y=.1) → 2 unanchored → 3 (y=.5): still monotonic, so nothing to report.
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.1)), anchor(3, rect(0, 0.5))],
      [],
      order([1], [2], [3]),
    )
    expect(warnings).toEqual([])
  })

  it('still compares across an unanchored cue', () => {
    // 1 (y=.5) → 2 unanchored → 3 (y=.2): the gap must not hide the disorder.
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.5)), anchor(3, rect(0, 0.2))],
      [],
      order([1], [2], [3]),
    )
    expect(warnings.map((w) => w.kind)).toEqual(['out-of-order'])
    expect(warnings[0]).toMatchObject({ cueId: 3 })
    expect(warnings[0].message).toContain('Q1')
  })

  it('flags an anchor overlapping a strikethrough', () => {
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.2))],
      [strike(1, rect(0, 0.19, { h: 0.1 }))],
      order([1]),
    )
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toMatchObject({ kind: 'anchor-in-cut', cueId: 1 })
  })

  it('ignores non-strikethrough annotations for the cut check', () => {
    const note: AnnotationDto = { id: 1, kind: 'NOTE', region: [rect(0, 0.2)], text: 'x', color: null, tone: null }
    expect(computeWarnings([anchor(1, rect(0, 0.2))], [note], order([1]))).toEqual([])
  })

  it('handles an empty book and empty order', () => {
    expect(computeWarnings([], [], [])).toEqual([])
  })

  it('can report multiple kinds for one cue', () => {
    // Cue 2 is both out-of-order and anchored in a cut.
    const warnings = computeWarnings(
      [anchor(1, rect(0, 0.5)), anchor(2, rect(0, 0.2))],
      [strike(1, rect(0, 0.2))],
      order([1], [2]),
    )
    expect(warnings.map((w) => w.kind).sort()).toEqual(['anchor-in-cut', 'out-of-order'])
  })
})
