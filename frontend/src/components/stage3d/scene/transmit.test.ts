import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../../api/stageElementApi'
import { beamReach, boxCollider, elementColliders, sightBlocked, type BeamHit, type Collider } from './beamReach'
import { buildElement } from './builders'
import { landPlane, LAND_UP, packLanding } from './landing'
import type { MaskSampler } from './sceneMasks'

/**
 * Light through cloth (scrim plan session 3, D7): beam reach and sight lines skip a net, and pass a
 * cut cloth through its holes; a transmitting collider is never where a beam lands.
 */

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'DRAPE', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 6, depthM: 0.1, heightM: 4,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const CUT = 'c'.repeat(64)
/** The cut cloth's mask: a hole wherever u < ½ (its stage-right half). */
const halfCut: MaskSampler = { holeAt: (hash, u) => hash === CUT && u < 0.5 }
const nothingLoaded: MaskSampler = { holeAt: () => false }

const hit = (): BeamHit => ({ t: 0, nx: 0, ny: 0, nz: 0, skin: 0, collider: null })

/** A 6 m cloth 3 m upstage of the setting line, and a wall 2 m behind it. Three: z = −lighting y. */
function scene(params: Record<string, unknown>): { cloth: Collider[]; wall: Collider; all: Collider[] } {
  const cloth = elementColliders(element({ positionY: 3 }), buildElement(element({ positionY: 3, params })))
  const wall = boxCollider(0, 2, -5.01, 5, 2, 0.01)
  return { cloth, wall, all: [...cloth, wall] }
}

/** From 8 m out front, 2 m up, towards the cloth at [x] across. */
function castAt(colliders: Collider[], x: number, masks: MaskSampler): BeamHit | null {
  const o = [x, 2, 8]
  const d = [0, 0, -1]
  const out = hit()
  return beamReach(o[0], o[1], o[2], d[0], d[1], d[2], colliders, 40, out, masks) ? out : null
}

describe('beam reach through cloth', () => {
  it('passes a scrim, landing on the solid surface behind it', () => {
    for (const fabric of ['SHARKSTOOTH', 'BOBBINET']) {
      for (const operation of [undefined, 'FLY', 'DRAW']) {
        const { wall, all } = scene({ fabric, operation })
        const h = castAt(all, operation === 'DRAW' ? -2.5 : 0, nothingLoaded)!
        expect(h.collider, `${fabric} ${operation}`).toBe(wall)
        expect(h.t).toBeCloseTo(13, 6)
      }
    }
  })

  it('stops at velour, canvas, muslin and an unpainted cloth', () => {
    for (const fabric of [undefined, 'CANVAS', 'MUSLIN']) {
      const { cloth, all } = scene({ fabric })
      expect(castAt(all, 0, halfCut)!.collider).toBe(cloth[0])
    }
  })

  it('stops at a cut cloth where it is cloth, and passes it through a hole', () => {
    for (const fabric of [undefined, 'CANVAS', 'MUSLIN']) {
      const { cloth, wall, all } = scene({ fabric, paint: { front: CUT } })
      expect(cloth[0].transmit).toMatchObject({ kind: 'mask', image: CUT })
      // Stage left (+x) is the image's right half, cloth; stage right its left half, a hole.
      expect(castAt(all, 1.5, halfCut)!.collider).toBe(cloth[0])
      expect(castAt(all, -1.5, halfCut)!.collider).toBe(wall)
      // A mask not loaded — or missing, or past the atlas's cap — is solid.
      expect(castAt(all, -1.5, nothingLoaded)!.collider).toBe(cloth[0])
    }
  })

  it('reads a drawn cut cloth half by its own half of the image', () => {
    // Drawn half open, each half 1.92 m: stage right's half is the image's left half, all hole.
    const { cloth, wall, all } = scene({ operation: 'DRAW', states: { open: 0.5 }, paint: { front: CUT } })
    const sr = cloth.find((c) => c.cx < 0)!
    const sl = cloth.find((c) => c.cx > 0)!
    expect(castAt(all, sr.cx, halfCut)!.collider).toBe(wall)
    expect(castAt(all, sl.cx, halfCut)!.collider).toBe(sl)
  })
})

describe('a transmitting collider is never a landing plane', () => {
  it('lands a beam through a gauze on the deck behind it, the plane the deck’s', () => {
    // A steep wash from 7 m up over the setting line, down through a gauze 3 m upstage onto a deck.
    const gauze = elementColliders(element({ positionY: 3 }), buildElement(element({ positionY: 3, params: { fabric: 'SHARKSTOOTH' } })))
    const deck = boxCollider(0, -0.01, -4, 6, 0.01, 3)
    const out = hit()
    const d = [0, -Math.SQRT1_2, -Math.SQRT1_2]
    expect(beamReach(0, 4, -1.5, d[0], d[1], d[2], [...gauze, deck], 40, out)).toBe(true)
    expect(out.collider).toBe(deck)
    const packed = [0, 0, 0, 0]
    const p = [0, 4 + d[1] * out.t, -1.5 + d[2] * out.t]
    packLanding({ px: p[0], py: p[1], pz: p[2], nx: out.nx, ny: out.ny, nz: out.nz, skin: out.skin }, null, packed, 0)
    expect(packed[0]).toBe(LAND_UP)
    expect(landPlane(packed[0], packed[1])).toEqual([0, 1, 0, 0])
  })
})

describe('sight lines through cloth', () => {
  it('see through a scrim and through a hole, and not through cloth', () => {
    const { all } = scene({ fabric: 'SHARKSTOOTH' })
    // A label on the wall seen from the stalls through the gauze.
    expect(sightBlocked(0, 2, 8, 0, 0, -1, 13, all, 0.1)).toBe(false)
    const cut = scene({ paint: { front: CUT } }).all
    expect(sightBlocked(-1.5, 2, 8, 0, 0, -1, 13, cut, 0.1, halfCut)).toBe(false)
    expect(sightBlocked(1.5, 2, 8, 0, 0, -1, 13, cut, 0.1, halfCut)).toBe(true)
    expect(sightBlocked(0, 2, 8, 0, 0, -1, 13, scene({ fabric: 'MUSLIN' }).all, 0.1)).toBe(true)
  })
})
