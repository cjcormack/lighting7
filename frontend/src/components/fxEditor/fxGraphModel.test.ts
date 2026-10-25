import { describe, expect, it } from 'vitest'
import { attributeRow, levelSeries, previewAnswer, previewFx, row } from '@/test/fxPreviewAnswers'
import { decodePreview } from './fxPreviewDecode'
import {
  GraphPlayhead,
  HEAT_ROW_PX,
  graphRowOrder,
  barBeatAt,
  isHolding,
  ladderBrackets,
  layoutLane,
  phaseAt,
  playheadClockOf,
  rowAt,
  sampleAt,
  startBeatOf,
  truncatedWords,
  wingBreaks,
} from './fxGraphModel'

const dimmerLane = (rows: number) =>
  decodePreview(
    previewAnswer({
      rows: Array.from({ length: rows }, (_, i) => row(`h${i}`, i)),
      lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: Array.from({ length: rows }, (_, i) => attributeRow(i, levelSeries([0]))) }] }],
    }),
  ).lanes[0]

const layoutOf = (rows: number, options: { maxRows?: number } = {}) =>
  layoutLane(dimmerLane(rows), {
    width: 400,
    order: Array.from({ length: rows }, (_, i) => i),
    rows: Array.from({ length: rows }, (_, i) => row(`h${i}`, i)),
    stepsAttributes: new Set(),
    fixtureName: () => undefined,
    attributeLabel: (a) => a,
    ...options,
  })

describe('the rows', () => {
  it('come in the answer’s order — the picked FX’s Order', () => {
    const answer = { rows: [row('c', 2), row('a', 0), row('b', 1)] }
    expect(graphRowOrder(answer, false)).toEqual([0, 1, 2])
  })

  it('hold still in rig order while the Order changes', () => {
    // The same three heads under Normal, then Reverse: the answer reorders them, rig order does not.
    const normal = { rows: [row('a', 0), row('b', 1), row('c', 2)] }
    const reverse = { rows: [row('c', 2), row('b', 1), row('a', 0)] }
    const keys = (answer: typeof normal) => graphRowOrder(answer, true).map((i) => answer.rows[i].key)
    expect(keys(normal)).toEqual(['a', 'b', 'c'])
    expect(keys(reverse)).toEqual(['a', 'b', 'c'])
    expect(graphRowOrder(reverse, false).map((i) => reverse.rows[i].key)).toEqual(['c', 'b', 'a'])
  })

  it('name a fixture once, over its first element', () => {
    const rows = [
      row('bar.0', 0, { fixtureKey: 'bar', label: 'Bar 1 · 1', element: 0 }),
      row('bar.1', 1, { fixtureKey: 'bar', label: 'Bar 1 · 2', element: 1 }),
      row('hex', 2, { label: 'Hex 1' }),
    ]
    const lane = decodePreview(
      previewAnswer({ rows, lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [0, 1, 2].map((r) => attributeRow(r, levelSeries([0]))) }] }] }),
    ).lanes[0]
    const layout = layoutLane(lane, { width: 300, order: [0, 1, 2], rows, stepsAttributes: new Set(), fixtureName: () => 'Bar 1', attributeLabel: (a) => a })
    expect(layout.sections[0].rows.map((r) => [r.fixtureLabel, r.label, r.element])).toEqual([
      ['Bar 1', '1', true],
      [null, '2', true],
      [null, 'Hex 1', false],
    ])
  })
})

describe('the layout', () => {
  it('draws 24 rows at the family’s height and a heat map from 25, 6px a row', () => {
    expect(layoutOf(24).sections[0]).toMatchObject({ heat: false, rowH: 28 })
    const heat = layoutOf(25).sections[0]
    expect(heat).toMatchObject({ heat: true, rowH: HEAT_ROW_PX })
    expect(heat.rows[24].y).toBe(24 * HEAT_ROW_PX)
  })

  it('cuts a section at the narrow arm’s rows', () => {
    expect(layoutOf(10, { maxRows: 6 }).sections[0].rows).toHaveLength(6)
  })

  it('finds the sample and row under the pointer', () => {
    const layout = layoutOf(3)
    expect(sampleAt(layout, 4, layout.plotLeft - 1)).toBeNull()
    expect(sampleAt(layout, 4, layout.plotLeft + layout.plotWidth * 0.6)).toBe(2)
    expect(rowAt(layout, 30)?.row.row).toBe(1)
    expect(rowAt(layout, 900)).toBeNull()
  })
})

describe('the spread’s marks, read off the ladder', () => {
  it('brackets the rows Segments gave one rank, and none where Segments did not act', () => {
    const fx = previewFx(4, {
      ladder: {
        order: { ranks: [0, 1, 2, 3], counts: [4, 4, 4, 4] },
        segments: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
        wings: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
        parts: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
        spread: [0, 0, 0.5, 0.5],
      },
    })
    expect(ladderBrackets(fx, [0, 1, 2, 3]).segments).toEqual([
      { from: 0, to: 1, part: null },
      { from: 2, to: 3, part: null },
    ])
    expect(ladderBrackets(previewFx(4), [0, 1, 2, 3])).toEqual({ segments: [], parts: [] })
  })

  it('brackets each Part, and marks where the Wings mirror', () => {
    const fx = previewFx(4, {
      ladder: {
        order: { ranks: [0, 1, 2, 3], counts: [4, 4, 4, 4] },
        segments: { ranks: [0, 1, 2, 3], counts: [4, 4, 4, 4] },
        wings: { ranks: [0, 1, 1, 0], counts: [2, 2, 2, 2] },
        parts: { ranks: [0, 0, 0, 0], counts: [1, 1, 1, 1] },
        spread: [0, 0, 0, 0],
      },
    })
    expect(wingBreaks(fx, [0, 1, 2, 3])).toEqual([2])
    expect(ladderBrackets(fx, [0, 1, 2, 3]).parts.map((b) => [b.from, b.to, b.part])).toEqual([[0, 0, 1], [1, 2, 2], [3, 3, 1]])
  })

  it('reads holding as the inclusive ranges', () => {
    expect(isHolding([[2, 3]], 3)).toBe(true)
    expect(isHolding([[2, 3]], 4)).toBe(false)
    expect(isHolding(null, 0)).toBe(false)
  })

  it('reads a row’s phase from where its cycles start, and none where it holds', () => {
    const fx = previewFx(1, { starts: [[2, 6]], holding: [[[8, 9]]] })
    expect(phaseAt(fx, 0, 4, null)).toBe(0.5)
    expect(phaseAt(fx, 0, 0, null)).toBe(0.5)
    expect(phaseAt(fx, 0, 7, null)).toBe(0.25)
    expect(phaseAt(fx, 0, 8, null)).toBeNull()
    expect(phaseAt(previewFx(1, { starts: [[2]] }), 0, 3, 4)).toBe(0.25)
    expect(phaseAt(previewFx(1, { starts: [[2]] }), 0, 3, null)).toBeNull()
  })

  it('says what the desk cut, in words', () => {
    expect(truncatedWords([])).toBeNull()
    expect(truncatedWords(['ROWS', 'SAMPLES'])).toBe('The desk cut the rows, at 96, the samples, at 1024 a row.')
  })
})

describe('the playhead', () => {
  const window = { beats: 2, seconds: 1, samples: 4, sampleMs: 250 }
  const masterAnswer = (tickAtStart: number) => previewAnswer({ rows: [row('h', 0)], lanes: [], clock: [{ speedMasterUuid: 'm1', tickAtStart, msPerBeat: 500 }] })

  it('runs on the picked FX’s master from sample 0, wrapping at the window’s end', () => {
    const clock = playheadClockOf(masterAnswer(1000))
    expect(clock).toEqual({ kind: 'master', speedMasterUuid: 'm1', tickAtStart: 1000, msPerBeat: 500 })
    const playhead = new GraphPlayhead()
    playhead.setAnswer(window, clock, 0)
    expect(playhead.sample(0)).toBe(0)
    expect(playhead.sample(500)).toBe(2)
    expect(playhead.sample(1250)).toBe(1)
  })

  it('resyncs on its master’s beat: beat n is tick 24n + 1', () => {
    const playhead = new GraphPlayhead()
    playhead.setAnswer(window, playheadClockOf(masterAnswer(1000)), 0)
    // Beat 42 is tick 1009: 9 ticks past the window's start, 187.5 ms at 120 BPM.
    playhead.resync({ masterUuid: 'm1', beatNumber: 42 }, 10_000)
    expect(playhead.sample(10_000)).toBeCloseTo(0.75)
    expect(playhead.sample(10_250)).toBeCloseTo(1.75)
    playhead.resync({ masterUuid: 'other', beatNumber: 0 }, 10_250)
    expect(playhead.sample(10_250)).toBeCloseTo(1.75)
  })

  it('carries the master’s tick across a re-ask, so a fresh answer does not restart it', () => {
    const playhead = new GraphPlayhead()
    playhead.setAnswer(window, playheadClockOf(masterAnswer(1000)), 0)
    // 500 ms on, the master is at tick 1024; a window starting at tick 1012 is 12 ticks behind it.
    playhead.setAnswer(window, playheadClockOf(masterAnswer(1012)), 500)
    expect(playhead.sample(500)).toBeCloseTo(1)
  })

  it('wraps at the window’s last whole cycle, so it comes round where the stage does', () => {
    // 2 bars of a 3-beat FX: 128 samples, 2⅔ cycles of 48. It wraps after 96 samples, two cycles.
    const playhead = new GraphPlayhead()
    playhead.setAnswer({ beats: 8, seconds: 4, samples: 128, sampleMs: 31.25 }, playheadClockOf(masterAnswer(1000)), 0)
    expect(playhead.sample(96 * 31.25 - 1)).toBeCloseTo(96 - 1 / 31.25)
    expect(playhead.sample(96 * 31.25 + 31.25)).toBeCloseTo(1)
    // Under Bounce the shape repeats every two cycles: 96 samples again here, and a window of one
    // cycle, shorter than that, wraps at its end.
    playhead.setAnswer({ beats: 3, seconds: 1.5, samples: 48, sampleMs: 31.25 }, { kind: 'seconds' }, 0, 96)
    expect(playhead.sample(48 * 31.25 + 31.25)).toBeCloseTo(1)
  })

  it('reads a resyncing beat at the answer’s tempo, which its samples are spaced at, after a retune', () => {
    const playhead = new GraphPlayhead()
    playhead.setAnswer(window, playheadClockOf(masterAnswer(1000)), 0)
    // Beat 42 is 9 ticks in: 187.5 ms at the answer's 120 BPM, whatever the master moved to since.
    playhead.resync({ masterUuid: 'm1', beatNumber: 42 }, 100)
    expect(playhead.sample(100)).toBeCloseTo(0.75)
  })

  it('places the master’s beats in the window from the tick it started on', () => {
    const clock = playheadClockOf(masterAnswer(1020))
    expect(startBeatOf(clock)).toBe(42.5)
    expect(startBeatOf({ kind: 'seconds' })).toBeNull()
    // Tick 1020 is beat 42 and a half: the 3rd beat of its bar, halfway through.
    expect(barBeatAt(clock, { beats: 2, samples: 4 }, 0)).toBeCloseTo(3.5)
    expect(barBeatAt(clock, { beats: 2, samples: 4 }, 2)).toBeCloseTo(4.5)
    expect(barBeatAt(clock, { beats: 2, samples: 4 }, 3)).toBeCloseTo(1)
  })

  it('runs a seconds clock from sample 0, which no beat resyncs', () => {
    const answer = previewAnswer({ rows: [row('h', 0)], lanes: [], clock: [{ seconds: true }], fx: [previewFx(1, { speedMasterUuid: null })] })
    const clock = playheadClockOf(answer)
    expect(clock).toEqual({ kind: 'seconds' })
    const playhead = new GraphPlayhead()
    playhead.setAnswer(window, clock, 100)
    playhead.resync({ masterUuid: 'm1', beatNumber: 42 }, 200)
    expect(playhead.sample(350)).toBe(1)
  })

  it('holds still while frozen, and runs on from there', () => {
    const playhead = new GraphPlayhead()
    playhead.setAnswer(window, playheadClockOf(masterAnswer(1000)), 0)
    playhead.setFrozen(true, 300)
    expect(playhead.sample(5000)).toBeCloseTo(1.2)
    playhead.resync({ masterUuid: 'm1', beatNumber: 42 }, 5000)
    expect(playhead.sample(6000)).toBeCloseTo(1.2)
    playhead.setFrozen(false, 6000)
    expect(playhead.sample(6250)).toBeCloseTo(2.2)
  })
})
