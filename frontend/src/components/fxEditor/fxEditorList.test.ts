// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import type { ActiveFx } from '@/store/fixtureFx'
import type { FxPreviewAnswer } from '@/store/fxPreview'
import { chan, colourProp, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'
import { coverageLabel, fxLanes, fxTargetsOf, localMovesFor } from './fxEditorList'

/**
 * The FX editor's list (FX editor plan D3): every FX on the selection in family lanes, top first in
 * the desk's apply order — Local over the programmer's layers over a cue's FX — read from the
 * preview's answer within a tier.
 */
const HEX = [1, 2, 3, 4].map((n) =>
  makeFixture(`hex-${n}`, [sliderProp('dimmer', 'dimmer', chan(n * 10)), colourProp('rgbColour', chan(n * 10 + 1), chan(n * 10 + 2), chan(n * 10 + 3))], {
    groups: ['front'],
  }),
)
const ALL = [{ type: 'group' as const, key: 'front' }]

function fx(id: number, over: Partial<ActiveFx> = {}): ActiveFx {
  return makeActiveFx({ id, fxType: `FX ${id}`, targetKey: 'front', isGroupTarget: true, ...over })
}

/** An answer whose `fx` is [ids] in apply order, bottom first. */
function answerOf(ids: number[]): FxPreviewAnswer {
  return {
    window: { beats: 8, seconds: 4, samples: 96, sampleMs: 41 },
    clock: [],
    rows: [],
    lanes: [],
    fx: ids.map((fxId) => ({
      fxId,
      fxType: 'x',
      family: 'INTENSITY',
      attribute: 'dimmer',
      running: true,
      offsets: [],
      starts: [],
      holding: [],
      slots: 1,
      ladder: { order: { ranks: [], counts: [] }, segments: { ranks: [], counts: [] }, wings: { ranks: [], counts: [] }, parts: { ranks: [], counts: [] }, spread: [] },
    })),
  }
}

const cardIds = (lanes: ReturnType<typeof fxLanes>, family: string) =>
  lanes.find((l) => l.family === family)?.cards.map((c) => c.effect.id)

describe('fxLanes', () => {
  it('orders a lane as the desk applies it, top first: Local over layers over cue FX', () => {
    const effects = [
      fx(1, { programmerOwned: false, cueId: 12 }),
      fx(2, { programmerLayerId: 40, paletteId: 3 }),
      fx(3),
      fx(4, { programmerLayerId: 41, lookId: 2 }),
      fx(5),
    ]
    // The desk's apply order, bottom first: the cue's, the layers' by their place, then Local 5 under 3.
    const answer = answerOf([1, 2, 4, 5, 3])
    const lanes = fxLanes({ effects, targets: ALL, fixtures: HEX, layerPosition: new Map([[40, 0], [41, 1]]), answer })
    expect(cardIds(lanes, 'INTENSITY')).toEqual([3, 5, 4, 2, 1])
    expect(lanes.find((l) => l.family === 'INTENSITY')?.cards.map((c) => c.kind)).toEqual(['local', 'local', 'layer', 'layer', 'cue'])
  })

  it('puts an FX the answer has not reached on top of its tier, where a new Local FX lands', () => {
    const effects = [fx(3), fx(5), fx(9)]
    const lanes = fxLanes({ effects, targets: ALL, fixtures: HEX, layerPosition: new Map(), answer: answerOf([5, 3]) })
    expect(cardIds(lanes, 'INTENSITY')).toEqual([9, 3, 5])
  })

  it('files FX by family into Intensity · Colour · Position · Beam, a lane for each family the selection can take', () => {
    const effects = [fx(1, { attribute: 'rgbColour' }), fx(2)]
    const lanes = fxLanes({ effects, targets: ALL, fixtures: HEX, layerPosition: new Map(), answer: null })
    expect(lanes.map((l) => l.family)).toEqual(['INTENSITY', 'COLOUR'])
    expect(cardIds(lanes, 'COLOUR')).toEqual([1])
    expect(cardIds(lanes, 'INTENSITY')).toEqual([2])
  })

  it('lists only FX reaching a selected head, and marks one reaching some of them', () => {
    const effects = [fx(1, { targetKey: 'hex-1', isGroupTarget: false }), fx(2, { targetKey: 'hex-4', isGroupTarget: false }), fx(3)]
    const targets = [
      { type: 'fixture' as const, key: 'hex-1' },
      { type: 'fixture' as const, key: 'hex-2' },
    ]
    const lane = fxLanes({ effects, targets, fixtures: HEX, layerPosition: new Map(), answer: null })[0]
    expect(lane.cards.map((c) => c.effect.id).sort()).toEqual([1, 3])
    expect(coverageLabel(lane.cards.find((c) => c.effect.id === 1)!)).toBe('1 of 2')
    expect(coverageLabel(lane.cards.find((c) => c.effect.id === 3)!)).toBeNull()
  })

  it('leaves out an FX a fade is letting go of, and lists nothing without a selection', () => {
    const effects = [fx(1, { leaving: true }), fx(2)]
    expect(cardIds(fxLanes({ effects, targets: ALL, fixtures: HEX, layerPosition: new Map(), answer: null }), 'INTENSITY')).toEqual([2])
    expect(fxLanes({ effects, targets: [], fixtures: HEX, layerPosition: new Map(), answer: null })).toEqual([])
  })
})

describe('localMovesFor', () => {
  // A lane's Local FX, top first.
  const lane = [10, 20, 30]

  it('moves an FX directly below the card that ends above it', () => {
    expect(localMovesFor(lane, 10, 2)).toEqual([[10, 30]])
    expect(localMovesFor(lane, 30, 1)).toEqual([[30, 10]])
    expect(localMovesFor(lane, 10, 1)).toEqual([[10, 20]])
  })

  it('moves the top FX below the one dragged above it, when that was the next one down', () => {
    expect(localMovesFor(lane, 20, 0)).toEqual([[10, 20]])
  })

  it('takes two moves to the top from further down: there is no card above the top to name', () => {
    expect(localMovesFor(lane, 30, 0)).toEqual([
      [30, 10],
      [10, 30],
    ])
  })

  it('sends nothing for a drop where it already is', () => {
    expect(localMovesFor(lane, 20, 1)).toEqual([])
    expect(localMovesFor(lane, 99, 0)).toEqual([])
  })
})

describe('fxTargetsOf', () => {
  it('names an FX’s heads as a selection names them', () => {
    expect(fxTargetsOf(fx(1))).toEqual([{ type: 'group', key: 'front' }])
    expect(fxTargetsOf(fx(2, { targetKey: 'hex-1', isGroupTarget: false }))).toEqual([{ type: 'fixture', key: 'hex-1' }])
    const over = [
      { type: 'fixture' as const, key: 'hex-2' },
      { type: 'group' as const, key: 'front' },
    ]
    expect(fxTargetsOf(fx(3, { targetType: 'selection', targets: over }))).toEqual(over)
  })
})
