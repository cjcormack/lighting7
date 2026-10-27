import { describe, expect, it } from 'vitest'
import { attributeRow, levelSeries, previewAnswer, previewFx, row } from '@/test/fxPreviewAnswers'
import { decodePreview } from './fxPreviewDecode'
import {
  GraphPlayhead,
  HEAT_ROW_PX,
  graphRowOrder,
  barBeatAt,
  elementLabel,
  isHolding,
  ladderBrackets,
  layoutLane,
  middleCut,
  mixKeyOf,
  phaseAt,
  rowStarts,
  rulerTicks,
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
    gutter: 72,
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

  it('gutter widens with the window’s name column, the plot starting there', () => {
    expect(layoutOf(2).plotLeft).toBe(72)
    const wide = layoutLane(dimmerLane(2), { width: 400, gutter: 152, order: [0, 1], rows: [row('h0', 0), row('h1', 1)], stepsAttributes: new Set(), fixtureName: () => undefined, attributeLabel: (a) => a })
    expect(wide).toMatchObject({ plotLeft: 152, plotWidth: 244 })
  })
})

describe('a fixture’s own row (follow-ups D5)', () => {
  // Two three-element bars, then a par: the bars' elements carry the dimmer, the bars do not.
  const rows = [
    row('bar1.0', 0, { fixtureKey: 'bar1', label: 'LX2 Shehds 19x15 SR Element 1', element: 0 }),
    row('bar1.1', 1, { fixtureKey: 'bar1', label: 'LX2 Shehds 19x15 SR Element 2', element: 1 }),
    row('bar1.2', 2, { fixtureKey: 'bar1', label: 'LX2 Shehds 19x15 SR Element 3', element: 2 }),
    row('bar2.0', 3, { fixtureKey: 'bar2', label: 'Ring A', element: 0 }),
    row('bar2.1', 4, { fixtureKey: 'bar2', label: 'Bar 2', element: 1 }),
    row('par', 5, { label: 'Par 1' }),
  ]
  const names: Record<string, string> = { bar1: 'LX2 Shehds 19x15 SR', bar2: 'Bar 2' }
  const laneOf = (attributeRows: number[]) =>
    decodePreview(
      previewAnswer({ rows, lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: attributeRows.map((r) => attributeRow(r, levelSeries([0]))) }] }] }),
    ).lanes[0]
  const layout = (attributeRows: number[], folded: string[] = [], order = attributeRows) =>
    layoutLane(laneOf(attributeRows), {
      width: 400,
      gutter: 152,
      order,
      rows,
      stepsAttributes: new Set(),
      folded: new Set(folded),
      fixtureName: (k) => names[k],
      attributeLabel: (a) => a,
    }).sections[0].rows.map((r) => [r.kind, r.label, r.row, r.mix])

  it('heads each fixture’s elements with a mixed row where the answer has none for the fixture', () => {
    expect(layout([0, 1, 2, 3, 4, 5])).toEqual([
      ['fixture', 'LX2 Shehds 19x15 SR', -1, [0, 1, 2]],
      ['element', 'Element 1', 0, null],
      ['element', 'Element 2', 1, null],
      ['element', 'Element 3', 2, null],
      ['fixture', 'Bar 2', -1, [3, 4]],
      ['element', 'Ring A', 3, null],
      ['element', '2', 4, null],
      ['head', 'Par 1', 5, null],
    ])
  })

  it('is the answer’s own row for the fixture where it carries the attribute', () => {
    const own = [...rows, row('bar1', 6, { label: 'LX2 Shehds 19x15 SR' })]
    const lane = decodePreview(
      previewAnswer({ rows: own, lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [6, 0, 1].map((r) => attributeRow(r, levelSeries([0]))) }] }] }),
    ).lanes[0]
    const laid = layoutLane(lane, { width: 400, gutter: 152, order: [0, 1, 6], rows: own, stepsAttributes: new Set(), fixtureName: (k) => names[k], attributeLabel: (a) => a })
    expect(laid.sections[0].rows.map((r) => [r.kind, r.key, r.mix])).toEqual([
      ['fixture', 'bar1', null],
      ['element', 'bar1.0', null],
      ['element', 'bar1.1', null],
    ])
  })

  it('folds a fixture’s elements under its row, which is drawn alone', () => {
    expect(layout([0, 1, 2, 3, 4, 5], ['bar1'])).toEqual([
      ['fixture', 'LX2 Shehds 19x15 SR', -1, [0, 1, 2]],
      ['fixture', 'Bar 2', -1, [3, 4]],
      ['element', 'Ring A', 3, null],
      ['element', '2', 4, null],
      ['head', 'Par 1', 5, null],
    ])
  })

  it('keeps a fixture’s elements together under it, in the order the fixture first appears', () => {
    expect(layout([0, 1, 3, 4], [], [0, 3, 1, 4]).map((r) => r[2])).toEqual([-1, 0, 1, -1, 3, 4])
  })

  it('counts a folded fixture as one row towards the heat map', () => {
    const many = Array.from({ length: 26 }, (_, i) => row(`b.${i}`, i, { fixtureKey: 'b', label: `B ${i + 1}`, element: i }))
    const lane = decodePreview(
      previewAnswer({ rows: many, lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: many.map((_, r) => attributeRow(r, levelSeries([0]))) }] }] }),
    ).lanes[0]
    const at = (folded: string[]) =>
      layoutLane(lane, { width: 400, gutter: 152, order: many.map((_, i) => i), rows: many, stepsAttributes: new Set(), folded: new Set(folded), fixtureName: () => 'B', attributeLabel: (a) => a }).sections[0]
    // 26 elements is a heat map; folded, the fixture is one row.
    expect(at([])).toMatchObject({ heat: true })
    expect(at(['b'])).toMatchObject({ heat: false })
    expect(at(['b']).rows).toHaveLength(1)
  })

  it('counts the answer’s rows towards the heat map, not the mixed rows over them', () => {
    const cells = Array.from({ length: 24 }, (_, i) => row(`b${Math.floor(i / 8)}.${i % 8}`, i, { fixtureKey: `b${Math.floor(i / 8)}`, label: `C ${i}`, element: i % 8 }))
    const lane = decodePreview(
      previewAnswer({ rows: cells, lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: cells.map((_, r) => attributeRow(r, levelSeries([0]))) }] }] }),
    ).lanes[0]
    // 24 cells and three mixed rows over them: 27 drawn, 24 counted.
    const section = layoutLane(lane, { width: 400, gutter: 152, order: cells.map((_, i) => i), rows: cells, stepsAttributes: new Set(), fixtureName: () => undefined, attributeLabel: (a) => a }).sections[0]
    expect(section.rows).toHaveLength(27)
    expect(section.heat).toBe(false)
  })

  it('says how many rows the narrow arm cut', () => {
    const section = layoutLane(laneOf([0, 1, 2, 3, 4, 5]), { width: 400, gutter: 152, order: [0, 1, 2, 3, 4, 5], rows, stepsAttributes: new Set(), maxRows: 6, fixtureName: (k) => names[k], attributeLabel: (a) => a }).sections[0]
    // Two mixed rows join the six: eight laid out, two cut.
    expect(section.cut).toBe(2)
  })

  it('draws a mixed row’s start tick only where its elements share it', () => {
    const fx = { starts: [[0, 48], [0, 48], [0, 48], [16], [32]] }
    expect(rowStarts(fx, { row: -1, mix: [0, 1, 2] })).toEqual([0, 48])
    expect(rowStarts(fx, { row: -1, mix: [3, 4] })).toBeNull()
    expect(rowStarts(fx, { row: 3, mix: null })).toEqual([16])
  })

  it('hovers a mixed row by a key no answer row has', () => {
    expect(layoutLane(laneOf([0, 1]), { width: 400, gutter: 152, order: [0, 1], rows, stepsAttributes: new Set(), fixtureName: (k) => names[k], attributeLabel: (a) => a }).sections[0].rows[0].key).toBe(
      mixKeyOf('bar1'),
    )
    expect(rows.some((r) => r.key === mixKeyOf('bar1'))).toBe(false)
  })
})

describe('the names (follow-ups D6)', () => {
  const measure = (text: string) => text.length * 6

  it('read an element’s own words after its fixture’s name, else its number, never its fixture’s name', () => {
    expect(elementLabel('Bar 1 · 2', 'Bar 1', 1)).toBe('2')
    expect(elementLabel('Bar 1 Cell 3', 'Bar 1', 2)).toBe('Cell 3')
    expect(elementLabel('Bar 1', 'Bar 1', 4)).toBe('5')
    expect(elementLabel('Ring A', 'Bar 1', 0)).toBe('Ring A')
  })

  it('cut a fixture’s name in its middle to fit, whole where it fits', () => {
    expect(middleCut('Par 1', 60, measure)).toBe('Par 1')
    const cut = middleCut('LX2 Shehds 19x15 SR', 84, measure)
    expect(cut).toBe('LX2 She…x15 SR')
    expect(measure(cut)).toBeLessThanOrEqual(84)
    expect(middleCut('LX2 Shehds 19x15 SR', 10, measure)).toBe('LX…')
  })
})

describe('the beat ruler (follow-ups D4)', () => {
  const master = (tickAtStart: number) => ({ kind: 'master' as const, speedMasterUuid: 'm1', tickAtStart, msPerBeat: 500 })

  it('numbers the bars and the beats between, from the bar the window starts in', () => {
    const ticks = rulerTicks(master(0), { beats: 8, seconds: 4 }, 100, 320)
    expect(ticks.map((t) => [t.x, t.major, t.label])).toEqual([
      [100, true, '1'],
      [140, false, '1.2'],
      [180, false, '1.3'],
      [220, false, '1.4'],
      [260, true, '2'],
      [300, false, '2.2'],
      [340, false, '2.3'],
      [380, false, '2.4'],
      [420, true, null],
    ])
  })

  it('starts from the master’s tick, a window opening mid-bar on bar 1’s third beat', () => {
    // Tick 48 is beat 2, the bar's third: the first tick sits on it, bar 2 two beats in.
    const ticks = rulerTicks(master(48), { beats: 4, seconds: 2 }, 0, 160)
    expect(ticks.map((t) => [t.x, t.label])).toEqual([
      [0, '1.3'],
      [40, '1.4'],
      [80, '2'],
      [120, '2.2'],
      [160, null],
    ])
  })

  it('numbers bars alone where the beats have no room, and every bar over 16 beats', () => {
    expect(rulerTicks(master(0), { beats: 8, seconds: 4 }, 0, 160).filter((t) => t.label != null).map((t) => t.label)).toEqual(['1', '2'])
    expect(rulerTicks(master(0), { beats: 32, seconds: 16 }, 0, 320).map((t) => t.label)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', null])
  })

  it('reads seconds on an FX’s own clock', () => {
    expect(rulerTicks({ kind: 'seconds' }, { beats: 8, seconds: 4 }, 0, 200).map((t) => [t.x, t.label])).toEqual([
      [0, '0 s'],
      [50, '1 s'],
      [100, '2 s'],
      [150, '3 s'],
      [200, null],
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
