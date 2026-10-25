import { describe, expect, it } from 'vitest'
import { attributeRow, colourSeries, levelSeries, plane, positionSeries, previewAnswer, row } from '@/test/fxPreviewAnswers'
import { decodePlane, decodePreview, decodeSeries, ownOutputOnly } from './fxPreviewDecode'

/** The preview's planes, decoded once into typed arrays (FX editor plan §3.2's sample format). */
describe('decoding the preview', () => {
  it('reads a plane as a byte a sample', () => {
    expect([...decodePlane(plane([0, 128, 255, 7]))]).toEqual([0, 128, 255, 7])
  })

  it('decodes a level plane', () => {
    const series = decodeSeries(levelSeries([10, 20, 30]))
    expect(series.planes.level).toBeInstanceOf(Uint8Array)
    expect([...series.planes.level!]).toEqual([10, 20, 30])
    expect(series.steps).toBeNull()
  })

  it('decodes a colour as its three planes and every bundled emitter the head has', () => {
    const series = decodeSeries(colourSeries([[255, 0, 1], [0, 255, 2]], { white: [5, 6], uv: [9, 8] }))
    expect([...series.planes.red!]).toEqual([255, 0])
    expect([...series.planes.green!]).toEqual([0, 255])
    expect([...series.planes.blue!]).toEqual([1, 2])
    expect([...series.planes.white!]).toEqual([5, 6])
    expect([...series.planes.uv!]).toEqual([9, 8])
    expect(series.planes.amber).toBeUndefined()
  })

  it('decodes a position as pan and tilt', () => {
    const series = decodeSeries(positionSeries([128, 160], [96, 128]))
    expect([...series.planes.pan!]).toEqual([128, 160])
    expect([...series.planes.tilt!]).toEqual([96, 128])
  })

  it('decodes a Steps output’s step indices beside its labels', () => {
    const series = decodeSeries({ ...levelSeries([40, 40, 200]), steps: [0, 0, 1], stepLabels: ['Warm', 'Cold'] })
    expect(series.steps).toBeInstanceOf(Int32Array)
    expect([...series.steps!]).toEqual([0, 0, 1])
    expect(series.stepLabels).toEqual(['Warm', 'Cold'])
    expect([...series.planes.level!]).toEqual([40, 40, 200])
  })

  it('decodes every row, attribute and FX output once, indexed by the answer’s row', () => {
    const answer = previewAnswer({
      rows: [row('hex-1', 0), row('hex-2', 1)],
      lanes: [
        {
          family: 'INTENSITY',
          attributes: [
            {
              attribute: 'dimmer',
              kind: 'SLIDER',
              rows: [
                attributeRow(1, levelSeries([1, 2]), {
                  parked: true,
                  underneath: levelSeries([9]),
                  fx: [{ fx: 0, output: levelSeries([3, 4]), heldBackReason: 'BLIND' }],
                }),
              ],
            },
          ],
        },
        { family: 'SOMETHING', attributes: [] },
      ],
    })
    const decoded = decodePreview(answer)
    expect(decoded.samples).toBe(4)
    expect(decoded.lanes.map((l) => l.family)).toEqual(['INTENSITY', null])
    const dimmer = decoded.lanes[0].attributes[0]
    expect(dimmer.byRow.has(0)).toBe(false)
    const second = dimmer.byRow.get(1)!
    expect(second.parked).toBe(true)
    expect([...second.underneath.planes.level!]).toEqual([9])
    expect([...second.result.planes.level!]).toEqual([1, 2])
    expect(second.fx[0]).toMatchObject({ fx: 0, heldBackReason: 'BLIND' })
    expect([...second.fx[0].output.planes.level!]).toEqual([3, 4])
  })
})

/** *This FX*: the picked FX alone, nothing underneath, its holding stretches keeping its own value. */
describe('ownOutputOnly', () => {
  const answer = previewAnswer({
    rows: [row('hex-1', 0), row('hex-2', 1)],
    lanes: [
      {
        family: 'INTENSITY',
        attributes: [
          {
            attribute: 'dimmer',
            kind: 'SLIDER',
            rows: [
              // hex-1: the FX's own output, holding (the stage's 77 filled in) after its Runs at samples 4–5.
              attributeRow(0, levelSeries([9, 9, 9, 9, 9, 9]), { underneath: levelSeries([77]), parked: true, fx: [{ fx: 0, output: levelSeries([0, 100, 200, 50, 77, 77]), heldBackReason: 'PROGRAMMER' }] }),
              // hex-2: painted only by another FX.
              attributeRow(1, levelSeries([9, 9, 9, 9, 9, 9]), { fx: [{ fx: 1, output: levelSeries([1, 1, 1, 1, 1, 1]) }] }),
            ],
          },
        ],
      },
    ],
  })

  it('makes each row’s own output its result, with nothing underneath, never parked or held back, and drops rows it does not paint', () => {
    const own = ownOutputOnly(decodePreview(answer), 0)
    const rows = own.lanes[0].attributes[0].rows
    expect(rows.map((r) => r.row)).toEqual([0])
    expect([...rows[0].result.planes.level!]).toEqual([0, 100, 200, 50, 77, 77])
    expect(rows[0].underneath.planes).toEqual({})
    expect(rows[0].parked).toBe(false)
    expect(rows[0].fx[0].heldBackReason).toBeNull()
  })

  it('holds the FX’s own value over a holding stretch rather than the stage’s, from before it or else after it', () => {
    const after = ownOutputOnly(decodePreview(answer), 0, () => [[4, 5]])
    expect([...after.lanes[0].attributes[0].rows[0].result.planes.level!]).toEqual([0, 100, 200, 50, 50, 50])
    const before = ownOutputOnly(decodePreview(answer), 0, () => [[0, 1]])
    expect([...before.lanes[0].attributes[0].rows[0].result.planes.level!]).toEqual([200, 200, 200, 50, 77, 77])
  })
})
