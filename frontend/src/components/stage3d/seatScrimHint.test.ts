import { describe, expect, it } from 'vitest'
import { NO_SCENERY, type LiveScenery } from '../../api/sceneryApi'
import type { StageElementDto } from '../../api/stageElementApi'
import { seatEyeOf } from './savedViewpoints'
import { SCRIM_HINT_RATIO, readScrim, seatScrimHint, shownScrims } from './seatScrimHint'
import { SCRIM_THREAD_SHARE, scrimShare } from './scene/scrimOpen'

/** A seated eye's height, where the tiny test gauzes hang their centre. */
const EYE_Z = 1.15
/** Ten metres from the cloth, at [deg] round from its normal (towards stage left, +X). */
const seatAt = (deg: number, y = 5) => {
  const a = (deg * Math.PI) / 180
  return { x: 10 * Math.sin(a), y: y - 10 * Math.cos(a), z: EYE_Z }
}

function drape(fields: Partial<StageElementDto> & { params?: Record<string, unknown> }): StageElementDto {
  const heightM = fields.heightM ?? 0.02
  return {
    id: 1, uuid: 'gauze', name: 'Forest gauze', kind: 'DRAPE', layer: 'SET',
    positionX: 0, positionY: 5, positionZ: EYE_Z - heightM / 2, yawDeg: 0, widthM: 0.02, depthM: 0.1, heightM,
    finishColour: null, finishPattern: null, emissive: false, hidden: false, sortOrder: 0,
    ...fields,
    params: { operation: 'DEAD', role: 'BACKCLOTH', fabric: 'SHARKSTOOTH', ...fields.params },
  }
}

const live = (entries: LiveScenery['entries']): LiveScenery => ({ projectId: 1, entries })
const at = (state: Record<string, unknown>) => ({ elementUuid: 'gauze', state, from: state, startedAtMs: 0, durationMs: 0 })

const hintFor = (eye: { x: number; y: number; z: number }, elements: StageElementDto[], scenery: LiveScenery = NO_SCENERY) =>
  seatScrimHint(eye, 'F6', shownScrims(elements, scenery))

describe('the seat scrim hint (scrim plan D13)', () => {
  it('does not flag a seat straight on, nor one 45° round from a flat sharkstooth (82 % of its head-on)', () => {
    const gauze = drape({})
    expect(hintFor(seatAt(0), [gauze])).toBeNull()
    expect(hintFor(seatAt(45), [gauze])).toBeNull()
    const reading = readScrim(seatAt(45), shownScrims([gauze], NO_SCENERY)[0])!
    expect(reading.ratio).toBeCloseTo(0.82, 2)
  })

  it('flags the seat once it is far enough round to cross half its head-on open, in the plan’s words', () => {
    const gauze = drape({})
    // Sharkstooth's line is at 62.5°: (1 − 0.30 / cos θ) = ½ (1 − 0.30).
    expect(hintFor(seatAt(60), [gauze])).toBeNull()
    expect(hintFor(seatAt(65), [gauze])).not.toBeNull()
    const hint = hintFor(seatAt(70), [gauze])!
    // The plan's pin: sharkstooth passes 9 % at 70°.
    expect(hint.text).toBe('Forest gauze reads near-solid from F6 (open 9 %)')
    expect(hint.reading.ratio).toBeLessThan(SCRIM_HINT_RATIO)
    expect(hint.others).toEqual([])
  })

  it('reads through the one open(θ)^gather, judged against the same net’s head-on through the same gather', () => {
    const gauze = drape({ params: { fabric: 'BOBBINET' } })
    const eye = seatAt(70)
    const reading = readScrim(eye, shownScrims([gauze], NO_SCENERY)[0])!
    const r = SCRIM_THREAD_SHARE.BOBBINET
    const cos = Math.cos((70 * Math.PI) / 180)
    expect(reading.open).toBeCloseTo(scrimShare(cos, r, 1), 3)
    expect(reading.ratio).toBeCloseTo(scrimShare(cos, r, 1) / scrimShare(1, r, 1), 3)
    // Bobbinet is the more open net: 70° is still above half its head-on.
    expect(hintFor(eye, [gauze])).toBeNull()
  })

  it('flags a drawn net at 45°: its gathered halves stack layers of themselves', () => {
    const traveller = drape({ widthM: 0.04, params: { operation: 'DRAW', role: 'TABS', states: { open: 1 } } })
    expect(hintFor(seatAt(45), [traveller])).not.toBeNull()
    // Closed, each half hangs flat: the same seat reads it as a flat net.
    const closed = drape({ widthM: 0.04, params: { operation: 'DRAW', role: 'TABS', states: { open: 0 } } })
    expect(hintFor(seatAt(45), [closed])).toBeNull()
  })

  it('reads a scrim at its worst point: a wide gauze’s far corner from a side seat', () => {
    const wide = drape({ widthM: 12, positionY: 3 })
    // From (8, −2) the centre is 58° off the normal (62 % of head-on) and the far corner 70°.
    const eye = { x: 8, y: -2, z: EYE_Z }
    const hint = hintFor(eye, [wide])!
    expect(hint).not.toBeNull()
    expect(hint.reading.open).toBeCloseTo(scrimShare(5 / Math.hypot(14, 5), SCRIM_THREAD_SHARE.SHARKSTOOTH, 1), 3)
    // A tiny gauze at that centre is fine from the same seat.
    expect(hintFor(eye, [drape({ positionY: 3 })])).toBeNull()
  })

  it('names the scrim furthest below its head-on, and counts the others that cross the line', () => {
    const near = drape({ uuid: 'near', name: 'Front gauze', positionY: 3 })
    const deep = drape({ uuid: 'deep', name: 'Forest gauze', positionY: 6 })
    const eye = { x: 14, y: 0, z: EYE_Z }
    const hint = hintFor(eye, [near, deep])!
    expect(hint.reading.name).toBe('Front gauze')
    expect(hint.others.map((r) => r.name)).toEqual(['Forest gauze'])
    expect(hint.detail.split('\n')).toHaveLength(2)
  })

  it('never flags a hidden scrim: hidden, switched off, or hidden by the live scenery', () => {
    const eye = seatAt(75)
    expect(hintFor(eye, [drape({})])).not.toBeNull()
    expect(hintFor(eye, [drape({ hidden: true })])).toBeNull()
    expect(hintFor(eye, [drape({ params: { states: { visible: false } } })])).toBeNull()
    expect(hintFor(eye, [drape({})], live({ gauze: at({ visible: false }) }))).toBeNull()
  })

  it('never flags a flown-out gauze, and flags it again once the scenery flies it in', () => {
    const eye = seatAt(75)
    // In at its Z (1.14 m); its stored trim, 8 m, is its out — where it hangs with nothing moving it.
    const flown = drape({ params: { operation: 'FLY', states: { trimM: 8 } } })
    expect(hintFor(eye, [flown])).toBeNull()
    expect(hintFor(eye, [flown], live({ gauze: at({ trimM: flown.positionZ }) }))).not.toBeNull()
    // Part-way out is still shown (and read where it hangs).
    expect(shownScrims([flown], live({ gauze: at({ trimM: 4 }) }))).toHaveLength(1)
  })

  it('never flags a cloth that is not a net: muslin, canvas, velour or a painted cut cloth', () => {
    const eye = seatAt(80)
    const paint = { front: 'a'.repeat(64) }
    for (const params of [{ fabric: 'MUSLIN' }, { fabric: 'CANVAS' }, { fabric: undefined }, { fabric: 'CANVAS', paint }]) {
      expect(hintFor(eye, [drape({ params })])).toBeNull()
    }
    expect(shownScrims([drape({ kind: 'FLAT', params: { paint } })], NO_SCENERY)).toEqual([])
  })

  it('reads a seat’s eye where a SEAT view of it stands', () => {
    const seating: StageElementDto = {
      ...drape({}),
      uuid: 'stalls',
      kind: 'SEATING',
      layer: 'VENUE',
      positionX: 0,
      positionY: -8,
      positionZ: 0,
      params: { rows: 6, seatsPerRow: 10, rowPitchM: 0.9, seatPitchM: 0.5, firstRow: 'A' },
    }
    const eye = seatEyeOf('stalls', 'A1', [seating])!
    expect(eye.z).toBeCloseTo(1.15, 6)
    expect(eye.y).toBeCloseTo(-8.05, 6)
    expect(seatEyeOf('stalls', 'Z1', [seating])).toBeNull()
    expect(seatEyeOf('gone', 'A1', [seating])).toBeNull()
  })
})
