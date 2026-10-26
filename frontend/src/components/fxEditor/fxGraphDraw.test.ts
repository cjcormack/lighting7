import { describe, expect, it } from 'vitest'
import type { FxPreviewAnswer, FxPreviewLane } from '@/store/fxPreview'
import { PixelCanvas } from '@/test/pixelCanvas'
import { attributeRow, colourSeries, levelSeries, positionSeries, previewAnswer, previewFx, row } from '@/test/fxPreviewAnswers'
import { decodePreview } from './fxPreviewDecode'
import { layoutLane } from './fxGraphModel'
import { LIGHT_PALETTE as P, drawLane, drawPathDots, pathPoint, withAlpha, type LaneDrawOptions } from './fxGraphDraw'

/**
 * The graph's drawing (FX editor plan §4.4), onto a pixel stub: a fixture answer draws the expected
 * pixels at three sample points per lane. The plot starts at x 72 and is 80px wide, so each of the
 * window's four samples is 20px: sample s spans x 72 + 20s to 92 + 20s.
 */
const WIDTH = 156
const at = (s: number) => 72 + 20 * s + 10

function draw(answer: FxPreviewAnswer, over: Partial<LaneDrawOptions> = {}, lane = 0) {
  const decoded = decodePreview(answer)
  const layout = layoutLane(decoded.lanes[lane], {
    width: WIDTH,
    gutter: 72,
    order: answer.rows.map((_, i) => i),
    rows: answer.rows,
    stepsAttributes: new Set(decoded.lanes[lane].attributes.filter((a) => a.rows.some((r) => r.fx.some((o) => o.output.steps != null))).map((a) => a.attribute)),
    fixtureName: () => undefined,
    attributeLabel: (a) => (a === 'zoom' ? 'Zoom' : a === 'iris' ? 'Iris' : a),
  })
  const ctx = new PixelCanvas(WIDTH, Math.ceil(layout.height))
  drawLane(ctx, layout, decoded.lanes[lane], {
    samples: answer.window.samples,
    beats: 0,
    startBeat: null,
    view: 'result',
    picked: 0,
    pickedFx: answer.fx[0],
    asSeen: null,
    positionScale: 64,
    palette: P,
    ...over,
  })
  return { ctx, layout }
}

const oneRow = (lane: FxPreviewLane, rows = [row('hex-1', 0)]) => previewAnswer({ rows, lanes: [lane] })

describe('the Intensity lane', () => {
  const answer = oneRow({
    family: 'INTENSITY',
    attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([0, 128, 255, 255]), { underneath: levelSeries([255]) })] }],
  })

  it('draws an area per row, 0–100 %, at three samples', () => {
    const { ctx } = draw(answer)
    // Row 0 spans y 0–28; its baseline is y 27, full is y 2.
    expect(ctx.pixel(at(0), 15)).toBeNull()
    expect(ctx.pixel(at(1), 20)).toBe(P.area)
    expect(ctx.pixel(at(1), 8)).toBeNull()
    expect(ctx.pixel(at(2), 15)).toBe(P.area)
  })

  it('draws what is underneath as a dashed line', () => {
    const { ctx } = draw(answer)
    expect(ctx.pixel(73, 2)).toBe(P.underneath)
    expect(ctx.pixel(76, 2)).toBeNull()
  })

  it('draws This FX — the picked FX’s own output — in its place', () => {
    const own = oneRow({
      family: 'INTENSITY',
      attributes: [
        {
          attribute: 'dimmer',
          kind: 'SLIDER',
          rows: [attributeRow(0, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([255, 255, 0, 0]) }] })],
        },
      ],
    })
    expect(draw(own).ctx.pixel(at(0), 15)).toBeNull()
    expect(draw(own, { view: 'this' }).ctx.pixel(at(0), 15)).toBe(P.pickedArea)
  })

  it('hatches a held-back FX’s own lane under This FX', () => {
    const held = oneRow({
      family: 'INTENSITY',
      attributes: [
        {
          attribute: 'dimmer',
          kind: 'SLIDER',
          rows: [attributeRow(0, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([0, 0, 0, 0]), heldBackReason: 'PROGRAMMER' }] })],
        },
      ],
    })
    const { ctx } = draw(held, { view: 'this' })
    const hatched = Array.from({ length: 80 }, (_, i) => ctx.pixel(72 + i, 14)).filter((p) => p === P.heldBack)
    expect(hatched.length).toBeGreaterThan(0)
  })

  it('ticks where each row’s cycle starts, and hatches where it holds', () => {
    const marked = previewAnswer({
      rows: [row('hex-1', 0)],
      lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([0, 0, 0, 0]) }] })] }] }],
      fx: [previewFx(1, { starts: [[1]], holding: [[[3, 3]]] })],
    })
    const { ctx } = draw(marked)
    expect(ctx.pixel(92, 1)).toBe(P.start)
    expect(ctx.pixel(at(0), 1)).toBeNull()
    const hatching = Array.from({ length: 20 }, (_, i) => ctx.pixel(132 + i, 14)).filter((p) => p === P.holding)
    expect(hatching.length).toBeGreaterThan(0)
    expect(Array.from({ length: 20 }, (_, i) => ctx.pixel(72 + i, 14)).filter((p) => p === P.holding)).toHaveLength(0)
  })

  it('brackets Segments at the left', () => {
    const fx = previewFx(2, {
      ladder: {
        order: { ranks: [0, 1], counts: [2, 2] },
        segments: { ranks: [0, 0], counts: [1, 1] },
        wings: { ranks: [0, 0], counts: [1, 1] },
        parts: { ranks: [0, 0], counts: [1, 1] },
        spread: [0, 0],
      },
    })
    const answer = previewAnswer({
      rows: [row('a', 0), row('b', 1)],
      lanes: [
        {
          family: 'INTENSITY',
          attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [0, 1].map((r) => attributeRow(r, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([0, 0, 0, 0]) }] })) }],
        },
      ],
      fx: [fx],
    })
    const { ctx } = draw(answer)
    expect(ctx.pixel(66, 28)).toBe(P.bracket)
  })
})

describe('the beat grid', () => {
  it('falls on the master’s beats, from the tick the window started on', () => {
    const answer = oneRow({ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([0, 0, 0, 0]))] }] })
    // Two beats from beat 41.75: lines at beats 42 and 43, a quarter and three quarters across.
    const { ctx } = draw(answer, { beats: 2, startBeat: 41.75, pickedFx: null, picked: null })
    expect(ctx.pixel(82, 20)).toBe(P.grid)
    expect(ctx.pixel(122, 20)).toBe(P.grid)
    expect(ctx.pixel(102, 20)).toBeNull()
    expect(draw(answer, { beats: 2, startBeat: null, pickedFx: null, picked: null }).ctx.pixel(82, 20)).toBeNull()
  })
})

describe('a fixture’s mixed row (follow-ups D5)', () => {
  const bar = [
    row('bar.0', 0, { fixtureKey: 'bar', label: 'Bar 1', element: 0 }),
    row('bar.1', 1, { fixtureKey: 'bar', label: 'Bar 2', element: 1 }),
  ]

  it('draws a colour as the mean of its elements’ RGB, rounded', () => {
    const { ctx, layout } = draw(
      oneRow(
        {
          family: 'COLOUR',
          attributes: [
            {
              attribute: 'rgbColour',
              kind: 'COLOUR',
              rows: [attributeRow(0, colourSeries([[255, 0, 0], [255, 0, 0], [0, 0, 0], [0, 0, 0]])), attributeRow(1, colourSeries([[0, 0, 255], [0, 0, 0], [0, 0, 0], [0, 0, 0]]))],
            },
          ],
        },
        bar,
      ),
    )
    expect(layout.sections[0].rows.map((r) => r.kind)).toEqual(['fixture', 'element', 'element'])
    // The fixture row is rows[0], 14px; its elements follow.
    expect(ctx.pixel(at(0), 7)).toBe('rgb(128,0,128)')
    expect(ctx.pixel(at(1), 7)).toBe('rgb(128,0,0)')
    expect(ctx.pixel(at(0), 21)).toBe('rgb(255,0,0)')
    expect(ctx.pixel(at(0), 35)).toBe('rgb(0,0,255)')
  })

  it('draws a level as the mean of its elements’ levels', () => {
    const { ctx } = draw(
      oneRow(
        { family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([255, 0, 0, 0])), attributeRow(1, levelSeries([0, 0, 0, 0]))] }] },
        bar,
      ),
      { picked: null, pickedFx: null },
    )
    // Half of 255 fills the bottom half of the fixture's 28px row; the top stays clear.
    expect(ctx.pixel(at(0), 24)).toBe(P.area)
    expect(ctx.pixel(at(0), 4)).toBeNull()
  })

  it('brackets a Segment from the fixture’s first element, never over the fixture’s own row', () => {
    const cells = [0, 1, 2, 3].map((e) => row(`bar.${e}`, e, { fixtureKey: 'bar', label: `Bar ${e + 1}`, element: e }))
    const ladder = {
      order: { ranks: [0, 1, 2, 3], counts: [4, 4, 4, 4] },
      segments: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
      wings: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
      parts: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
      spread: [0, 0, 0.5, 0.5],
    }
    const { ctx } = draw(
      previewAnswer({
        rows: cells,
        lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [0, 1, 2, 3].map((r) => attributeRow(r, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([0, 0, 0, 0]) }] })) }] }],
        fx: [previewFx(4, { ladder })],
      }),
    )
    // The bracket's spine is 6px left of the plot: the fixture's row (y 0–28) has none, its first two cells do.
    expect(ctx.pixel(66, 14)).not.toBe(P.bracket)
    expect(ctx.pixel(66, 50)).toBe(P.bracket)
  })

  it('draws a position’s fixture row with no mix', () => {
    const { ctx } = draw(
      oneRow(
        { family: 'POSITION', attributes: [{ attribute: 'position', kind: 'POSITION', rows: [attributeRow(0, positionSeries([255, 255, 255, 255], [0, 0, 0, 0])), attributeRow(1, positionSeries([0, 0, 0, 0], [255, 255, 255, 255]))] }] },
        bar,
      ),
      { picked: null, pickedFx: null },
    )
    for (let y = 1; y < 24; y++) expect([P.pan, P.tilt]).not.toContain(ctx.pixel(at(1), y))
  })
})

describe('the Colour lane', () => {
  const answer = oneRow({
    family: 'COLOUR',
    attributes: [{ attribute: 'rgbColour', kind: 'COLOUR', rows: [attributeRow(0, colourSeries([[255, 0, 0], [0, 255, 0], [0, 0, 255], [0, 0, 0]], { white: [0, 0, 0, 100] }))] }],
  })

  it('draws a strip per row, as the colour, at three samples', () => {
    const { ctx } = draw(answer)
    expect(ctx.pixel(at(0), 7)).toBe('rgb(255,0,0)')
    expect(ctx.pixel(at(1), 7)).toBe('rgb(0,255,0)')
    // White is light added to the mix.
    expect(ctx.pixel(at(3), 7)).toBe('rgb(100,100,100)')
  })

  it('draws as seen: the colour multiplied by the Intensity result', () => {
    const { ctx } = draw(answer, { asSeen: new Map([[0, Uint8Array.from([255, 0, 128, 0])]]) })
    expect(ctx.pixel(at(0), 7)).toBe('rgb(255,0,0)')
    expect(ctx.pixel(at(1), 7)).toBe('rgb(0,0,0)')
    expect(ctx.pixel(at(2), 7)).toBe('rgb(0,0,128)')
  })
})

describe('the Position lane', () => {
  const answer = oneRow({
    family: 'POSITION',
    attributes: [{ attribute: 'position', kind: 'POSITION', rows: [attributeRow(0, positionSeries([192, 192, 192, 64], [128, 128, 128, 128]))] }],
  })

  it('draws pan and tilt as two traces, centre-relative, at three samples', () => {
    const { ctx } = draw(answer)
    // Row 0 spans y 0–24, its centre y 12, ±64 steps reaching 11px either side.
    expect(ctx.pixel(at(0), 1)).toBe(P.pan)
    expect(ctx.pixel(at(1), 12)).toBe(P.tilt)
    expect(ctx.pixel(at(3), 23)).toBe(P.pan)
  })

  it('puts each element’s dot on the path panel at the playhead', () => {
    const decoded = decodePreview(answer).lanes[0].attributes[0].rows[0].result
    const pathRow = { pan: decoded.planes.pan!, tilt: decoded.planes.tilt!, colour: '#123456' }
    const ctx = new PixelCanvas(132, 132)
    drawPathDots(ctx, 132, [pathRow], 3, 64)
    const [x, y] = pathPoint(pathRow, 3, 132, 64)
    expect(x).toBe(6)
    expect(y).toBe(66)
    expect(ctx.pixel(x, y)).toBe('#123456')
    expect(ctx.pixel(126, 66)).toBeNull()
  })
})

describe('the Beam lane', () => {
  it('draws an area per attribute per row, each attribute its own section', () => {
    const answer = oneRow({
      family: 'BEAM',
      attributes: [
        { attribute: 'zoom', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([255, 0, 0, 0]))] },
        { attribute: 'iris', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([0, 0, 255, 0]))] },
      ],
    })
    const { ctx, layout } = draw(answer, { pickedFx: null, picked: null })
    expect(layout.sections.map((s) => [s.header, s.top])).toEqual([
      ['Zoom', 12],
      ['Iris', 46],
    ])
    // Zoom's row is y 12–34, Iris's y 46–68.
    expect(ctx.pixel(at(0), 24)).toBe(P.area)
    expect(ctx.pixel(at(2), 24)).toBeNull()
    expect(ctx.pixel(at(2), 58)).toBe(P.area)
    expect(ctx.texts.map((t) => t.text)).toEqual(expect.arrayContaining(['Zoom', 'Iris']))
  })
})

describe('a Steps FX', () => {
  it('draws blocks per row, labelled with its palettes', () => {
    const answer = oneRow({
      family: 'COLOUR',
      attributes: [
        {
          attribute: 'rgbColour',
          kind: 'COLOUR',
          rows: [
            attributeRow(0, colourSeries([[255, 0, 0], [255, 0, 0], [0, 0, 255], [0, 0, 255]]), {
              fx: [{ fx: 0, output: { ...colourSeries([[255, 0, 0], [255, 0, 0], [0, 0, 255], [0, 0, 255]]), steps: [0, 0, 1, 1], stepLabels: ['Red', 'Blue'] } }],
            }),
          ],
        },
      ],
    })
    const { ctx } = draw(answer)
    expect(ctx.pixel(at(0), 4)).toBe(P.stepA)
    expect(ctx.pixel(at(2), 4)).toBe(P.stepB)
    // The strip sits under the band.
    expect(ctx.pixel(at(2), 16)).toBe('rgb(0,0,255)')
    expect(ctx.texts.map((t) => t.text)).toEqual(expect.arrayContaining(['Red', 'Blue']))
  })
})

describe('the heat map', () => {
  it('draws 25 rows at 6px a row, a cell a sample', () => {
    const rows = Array.from({ length: 25 }, (_, i) => row(`h${i}`, i))
    const answer = previewAnswer({
      rows,
      lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: rows.map((_, i) => attributeRow(i, levelSeries(i === 24 ? [255, 0, 0, 51] : [0, 0, 0, 0]))) }] }],
      fx: [previewFx(25, { starts: Array.from({ length: 25 }, () => []) })],
    })
    const { ctx, layout } = draw(answer)
    expect(layout.height).toBe(150)
    expect(ctx.pixel(at(0), 147)).toBe(withAlpha(P.picked, 1))
    expect(ctx.pixel(at(1), 147)).toBe(withAlpha(P.picked, 0))
    expect(ctx.pixel(at(3), 147)).toBe(withAlpha(P.picked, 0.2))
    // No row is labelled at 6px.
    expect(ctx.texts).toHaveLength(0)
  })
})

describe('the hover lights (FX editor plan §4.5)', () => {
  /** Whether any pixel in the box holds [colour]. */
  const anyPixel = (ctx: PixelCanvas, colour: string, x0: number, x1: number, y0: number, y1: number) => {
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) if (ctx.pixel(x, y) === colour) return true
    return false
  }
  const ROWS = [row('hex-1', 0), row('hex-2', 1)]
  const picked = (over: Parameters<typeof previewFx>[1] = {}) =>
    previewAnswer({
      rows: ROWS,
      lanes: [
        {
          family: 'INTENSITY',
          attributes: [
            {
              attribute: 'dimmer',
              kind: 'SLIDER',
              rows: [0, 1].map((r) => attributeRow(r, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([255, 255, 0, 0]) }] })),
            },
          ],
        },
      ],
      fx: [previewFx(2, over)],
    })

  it('Cycle shades the first cycle under the picked FX’s rows', () => {
    expect(draw(picked()).ctx.pixel(at(0), 15)).toBeNull()
    expect(draw(picked(), { highlight: 'cycle' }).ctx.pixel(at(0), 15)).toBe(P.highlightArea)
  })

  it('Cycle’s shade is not hidden under a colour strip', () => {
    const colour = previewAnswer({
      rows: ROWS,
      lanes: [
        {
          family: 'COLOUR',
          attributes: [
            {
              attribute: 'rgbColour',
              kind: 'COLOUR',
              rows: [0, 1].map((r) => {
                const red = colourSeries([[255, 0, 0], [255, 0, 0], [255, 0, 0], [255, 0, 0]])
                return attributeRow(r, red, { fx: [{ fx: 0, output: red }] })
              }),
            },
          ],
        },
      ],
      fx: [previewFx(2, { family: 'COLOUR', attribute: 'rgbColour' })],
    })
    expect(draw(colour).ctx.pixel(at(0), 7)).toBe('rgb(255,0,0)')
    expect(draw(colour, { highlight: 'cycle' }).ctx.pixel(at(0), 7)).toBe(P.highlightArea)
  })

  it('Spread and Order light the start ticks', () => {
    expect(draw(picked()).ctx.pixel(72, 1)).toBe(P.start)
    expect(draw(picked(), { highlight: 'starts' }).ctx.pixel(72, 1)).toBe(P.highlight)
  })

  it('Amount fills the gap between This FX and Result', () => {
    expect(draw(picked()).ctx.pixel(at(0), 15)).toBeNull()
    const { ctx } = draw(picked(), { highlight: 'amount' })
    expect(ctx.pixel(at(0), 15)).toBe(P.highlightArea)
    // Where the two agree there is no gap to light.
    expect(ctx.pixel(at(2), 15)).toBeNull()
  })

  it('Runs lights the hatching where an element holds', () => {
    const holding = picked({ holding: [[[0, 1]], null] })
    expect(anyPixel(draw(holding).ctx, P.highlight, 72, 112, 0, 28)).toBe(false)
    expect(anyPixel(draw(holding).ctx, P.holding, 72, 112, 0, 28)).toBe(true)
    expect(anyPixel(draw(holding, { highlight: 'holding' }).ctx, P.highlight, 72, 112, 0, 28)).toBe(true)
  })

  it('Segments and Parts light the brackets', () => {
    const segmented = picked({
      ladder: {
        order: { ranks: [0, 1], counts: [2, 2] },
        segments: { ranks: [0, 0], counts: [1, 1] },
        wings: { ranks: [0, 0], counts: [1, 1] },
        parts: { ranks: [0, 0], counts: [1, 1] },
        spread: [0, 0],
      },
    })
    expect(anyPixel(draw(segmented).ctx, P.bracket, 60, 72, 0, 56)).toBe(true)
    expect(anyPixel(draw(segmented, { highlight: 'brackets' }).ctx, P.highlight, 60, 72, 0, 56)).toBe(true)
  })

  it('Wings light the mirror', () => {
    const winged = picked({
      ladder: {
        order: { ranks: [0, 1], counts: [2, 2] },
        segments: { ranks: [0, 1], counts: [2, 2] },
        wings: { ranks: [0, 0], counts: [1, 1] },
        parts: { ranks: [0, 0], counts: [1, 1] },
        spread: [0, 0],
      },
    })
    expect(anyPixel(draw(winged).ctx, P.bracket, 72, 152, 28, 29)).toBe(true)
    expect(anyPixel(draw(winged, { highlight: 'mirror' }).ctx, P.highlight, 72, 152, 27, 30)).toBe(true)
  })

  it('marks the row a ladder dot links to, in the gutter', () => {
    expect(draw(picked()).ctx.pixel(1, 33)).toBeNull()
    const { ctx } = draw(picked(), { linkedRow: 1 })
    expect(ctx.pixel(1, 33)).toBe(P.highlight)
    expect(ctx.pixel(1, 5)).toBeNull()
  })
})
