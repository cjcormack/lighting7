import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../../api/stageElementApi'
import { buildElement } from './builders'
import {
  CYC_DEPTH_MAX_M,
  FOLD_SHADOW_SOFT_M,
  foldLight,
  FULLNESS_MAX,
  FULLNESS_MIN,
  PITCH_WANDER,
  PLEAT_GLSL,
  PLEAT_WARP_TERMS,
  pleatOffset,
  pleatPhase,
  pleatRate,
  pleatShape,
  pleatShift,
  pleatSlope,
  pleatUniformValues,
  TROUGH_DARKEN,
  troughAmbient,
  type PleatShape,
} from './pleat'

const drape = (depthM: number, uuid = 'drape', role = 'BACKCLOTH') => ({ uuid, depthM, params: { role } })

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'DRAPE', layer: 'SET', positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0,
    widthM: 4, depthM: 0.1, heightM: 3, finishColour: null, finishPattern: null, emissive: false, params: {},
    hidden: false, sortOrder: 0, ...fields,
  }
}

/** The drawn cloth's length over [w] metres of width, per metre: its fullness. */
function drawnFullness(pleat: PleatShape, w: number): number {
  const n = 20000
  let len = 0
  let prev = pleatOffset(-w / 2, pleat)
  for (let i = 1; i <= n; i++) {
    const x = -w / 2 + (w * i) / n
    const z = pleatOffset(x, pleat)
    len += Math.hypot(w / n, z - prev)
    prev = z
  }
  return len / w
}

/**
 * The fold shadow by brute force: walk the section's ray from the point to the lamp in small steps,
 * and answer how far the cloth stands above it at its highest bump ahead — positive is shadowed. A
 * bump is a local maximum of the cloth's lead over the ray; the point itself, where the lead is 0
 * and falling, is not one.
 */
function marchedLead(x: number, lx: number, lz: number, pleat: PleatShape): number {
  const z0 = pleatOffset(x, pleat)
  const side = lz >= z0 ? 1 : -1
  // Past the fold's slab the cloth can only fall behind the ray, so the walk need not stop there.
  // Steps of at most 2 mm: a crest's sag over one is a hundredth of the margin the cases keep.
  const n = Math.max(500, Math.ceil(Math.abs(lx - x) / 0.002))
  const lead: number[] = []
  for (let i = 1; i < n; i++) {
    const t = i / n
    const rx = x + (lx - x) * t
    const rz = z0 + (lz - z0) * t
    lead.push(side * (pleatOffset(rx, pleat) - rz))
  }
  let worst = -Infinity
  for (let i = 1; i < lead.length - 1; i++) {
    if (lead[i] >= lead[i - 1] && lead[i] >= lead[i + 1]) worst = Math.max(worst, lead[i])
  }
  return worst
}

/** A deterministic stream of [0, 1) for the random cases. */
function stream(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0
    return s / 4294967296
  }
}

describe("a drape's pleats (stage-light plan D2)", () => {
  it('folds as deep as the drape and hangs fuller the deeper it is, between 1.5× and 2×', () => {
    const shallow = pleatShape(drape(0.05))
    const usual = pleatShape(drape(0.1))
    const deep = pleatShape(drape(0.25))
    expect(usual.amplitudeM).toBeCloseTo(0.05, 12)
    expect(shallow.fullness).toBe(FULLNESS_MIN)
    expect(deep.fullness).toBe(FULLNESS_MAX)
    expect(usual.fullness).toBeGreaterThan(FULLNESS_MIN)
    expect(usual.fullness).toBeLessThan(FULLNESS_MAX)
    // The drawn cloth is as full as the shape says, wander and all.
    for (const p of [shallow, usual, deep]) expect(drawnFullness(p, 6)).toBeCloseTo(p.fullness, 1)
  })

  it('stretches a cyc rather than hanging it', () => {
    const cyc = pleatShape(drape(0.1, 'cyc', 'CYC'))
    expect(2 * cyc.amplitudeM).toBe(CYC_DEPTH_MAX_M)
    expect(cyc.fullness).toBeLessThan(1.05)
  })

  it('wanders its pitch by a noise seeded from the element, never by more than the wander', () => {
    const a = pleatShape(drape(0.1, 'one'))
    expect(pleatShape(drape(0.1, 'one'))).toEqual(a)
    expect(pleatShape(drape(0.1, 'two')).warp).not.toEqual(a.warp)
    expect(pleatShape(drape(0.1, 'one'), 'sl').warp).not.toEqual(pleatShape(drape(0.1, 'one'), 'sr').warp)
    expect(a.warp).toHaveLength(PLEAT_WARP_TERMS)
    const mean = (2 * Math.PI) / a.pitchM
    let lo = Infinity
    let hi = -Infinity
    for (let x = -10; x <= 10; x += 0.001) {
      const r = pleatRate(x, a)
      lo = Math.min(lo, r)
      hi = Math.max(hi, r)
    }
    expect(lo).toBeGreaterThanOrEqual(mean * (1 - PITCH_WANDER) - 1e-9)
    expect(hi).toBeLessThanOrEqual(mean * (1 + PITCH_WANDER) + 1e-9)
    // It does wander: a pleat is not a perfect sine.
    expect(hi - lo).toBeGreaterThan(mean * 0.1)
  })

  it('reads a cyc by its role as the drape builder does, whatever its case or spacing', () => {
    expect(pleatShape(drape(0.1, 'c', ' cyc ')).amplitudeM).toBe(pleatShape(drape(0.1, 'c', 'CYC')).amplitudeM)
  })

  it("keeps a drawn half's folds with its outer edge as it gathers", () => {
    const tabs = { uuid: 'tabs', depthM: 0.1, params: { role: 'TABS', operation: 'DRAW' } }
    const widthM = 6
    // The same point of cloth, 0.37 m in from the stage-right edge, at three openings.
    const offsets = [0, 0.4, 0.8].map((open) => {
      const [sr] = buildElement({ ...element(tabs), widthM, params: { ...tabs.params, states: { open } } }).parts
      if (sr.geometry.shape !== 'pleat') throw new Error('not cloth')
      const local = -widthM / 2 + 0.37 - sr.at.x
      return pleatOffset(local + pleatShift(sr.geometry.w, sr.geometry.anchor), sr.geometry.pleat)
    })
    expect(offsets[1]).toBeCloseTo(offsets[0], 12)
    expect(offsets[2]).toBeCloseTo(offsets[0], 12)
    expect(pleatShift(2, 'left')).toBe(1)
    expect(pleatShift(2, 'right')).toBe(-1)
    expect(pleatShift(2, undefined)).toBe(0)
  })

  it('answers its slope as the derivative of its offset', () => {
    const p = pleatShape(drape(0.12, 'slope'))
    for (const x of [-1.3, -0.2, 0, 0.07, 0.9]) {
      const h = 1e-6
      expect(pleatSlope(x, p)).toBeCloseTo((pleatOffset(x + h, p) - pleatOffset(x - h, p)) / (2 * h), 5)
      expect(pleatRate(x, p)).toBeCloseTo((pleatPhase(x + h, p) - pleatPhase(x - h, p)) / (2 * h), 4)
    }
  })
})

describe('the fold shadow (stage-light plan D7)', () => {
  /** A perfect sine: the closed form is exact on it. */
  const sine: PleatShape = { pitchM: 0.16, amplitudeM: 0.05, fullness: 1.6, warp: [] }
  const wandering = pleatShape(drape(0.1, 'shadow'))

  /** Random points on the cloth, each lit from a random lamp a few metres off, at any angle. */
  function cases(pleat: PleatShape, count: number, seed: number) {
    const random = stream(seed)
    return Array.from({ length: count }, () => {
      const x = (random() - 0.5) * 2
      // A lamp 1–8 m away, raking from either side, in front of the cloth or behind it.
      const angle = (random() - 0.5) * Math.PI * 0.98
      const dist = 1 + 7 * random()
      const behind = random() < 0.25 ? -1 : 1
      return { x, lx: x + Math.sin(angle) * dist, lz: behind * Math.cos(angle) * dist, pleat }
    })
  }

  /** Whether the lamp sees the point's face at all: the shader's facing test, in section. */
  function faces(x: number, lx: number, lz: number, pleat: PleatShape): boolean {
    const nx = -pleatSlope(x, pleat)
    const nz = 1
    const dz = lz - pleatOffset(x, pleat)
    return (nx * (lx - x) + nz * dz) * Math.sign(dz || 1) > 0
  }

  it('shadows exactly where a crest stands between the point and the lamp, on a perfect sine', () => {
    let shadowed = 0
    let checked = 0
    for (const c of cases(sine, 1500, 7)) {
      if (!faces(c.x, c.lx, c.lz, c.pleat)) continue
      const lead = marchedLead(c.x, c.lx, c.lz, c.pleat)
      if (Math.abs(lead) < FOLD_SHADOW_SOFT_M * 2) continue
      checked++
      const lit = foldLight(c.x, c.lx, c.lz, c.pleat)
      if (lead > 0) shadowed++
      expect(lit, `x ${c.x} lamp (${c.lx}, ${c.lz})`).toBe(lead > 0 ? 0 : 1)
    }
    expect(checked).toBeGreaterThan(700)
    expect(shadowed).toBeGreaterThan(100)
  })

  it('stays close to the march on a wandering pitch, which it reads from the point', () => {
    let wrong = 0
    let checked = 0
    for (const c of cases(wandering, 1500, 11)) {
      if (!faces(c.x, c.lx, c.lz, c.pleat)) continue
      const lead = marchedLead(c.x, c.lx, c.lz, c.pleat)
      if (Math.abs(lead) < FOLD_SHADOW_SOFT_M * 2) continue
      checked++
      if (foldLight(c.x, c.lx, c.lz, c.pleat) !== (lead > 0 ? 0 : 1)) wrong++
    }
    expect(checked).toBeGreaterThan(700)
    expect(wrong / checked).toBeLessThan(0.02)
  })

  it('leaves the folds unshadowed in front light and bands them in raking light', () => {
    const shadowedShare = (angleDeg: number) => {
      const a = (angleDeg * Math.PI) / 180
      let facing = 0
      let dark = 0
      for (let x = -1; x <= 1; x += 0.002) {
        const lx = x + Math.sin(a) * 6
        const lz = Math.cos(a) * 6
        if (!faces(x, lx, lz, sine)) continue
        facing++
        if (foldLight(x, lx, lz, sine) < 0.5) dark++
      }
      return dark / facing
    }
    // A 1.6× fold's steepest flank stands at 63°: a lamp nearer the cloth's normal than that clears every crest.
    expect(shadowedShare(0)).toBe(0)
    expect(shadowedShare(20)).toBe(0)
    expect(shadowedShare(40)).toBeGreaterThan(0)
    expect(shadowedShare(75)).toBeGreaterThan(0.2)
    expect(shadowedShare(-75)).toBeCloseTo(shadowedShare(75), 1)
  })

  it('casts the same shadow from behind the cloth as in front, mirrored', () => {
    for (const c of cases(sine, 400, 3)) {
      const mirrored: PleatShape = { ...sine }
      // z → −z is the phase moved half a turn: the same cloth seen from its back.
      const back = foldLight(c.x + sine.pitchM / 2, c.lx + sine.pitchM / 2, -c.lz, mirrored)
      expect(back).toBeCloseTo(foldLight(c.x, c.lx, c.lz, sine), 9)
    }
  })

  it('darkens a trough to the room by how steep its walls are, a crest not at all', () => {
    const crest = sine.pitchM / 4
    const trough = -sine.pitchM / 4
    expect(troughAmbient(crest, true, sine)).toBeCloseTo(1, 9)
    expect(troughAmbient(trough, false, sine)).toBeCloseTo(1, 9)
    const steep = Math.min(1, (sine.amplitudeM * 2 * Math.PI) / sine.pitchM / 2)
    expect(troughAmbient(trough, true, sine)).toBeCloseTo(1 - TROUGH_DARKEN * steep, 9)
    const flat: PleatShape = { ...sine, amplitudeM: 0.001 }
    expect(troughAmbient(trough, true, flat)).toBeGreaterThan(0.98)
  })

  it('writes the GLSL from the same constants and in the same steps as the twin', () => {
    expect(PLEAT_GLSL).toContain(`#define FOLD_SHADOW_SOFT ${FOLD_SHADOW_SOFT_M.toFixed(4)}`)
    expect(PLEAT_GLSL).toContain(`#define TROUGH_DARKEN ${TROUGH_DARKEN.toFixed(4)}`)
    expect(PLEAT_GLSL).toContain(`uniform vec3 uPleatWarp[${PLEAT_WARP_TERMS}];`)
    expect(PLEAT_GLSL).toContain('float shoulder = dx > 0.0 ? acos(c) : PI - acos(c);')
    expect(PLEAT_GLSL).toContain('float lead = a * sqrt(1.0 - c * c) - z - slope * along;')
    expect(PLEAT_GLSL).toContain('return 1.0 - smoothstep(-FOLD_SHADOW_SOFT, FOLD_SHADOW_SOFT, lead);')
    const values = pleatUniformValues(wandering)
    expect(values.pleat[0]).toBeCloseTo((2 * Math.PI) / wandering.pitchM, 12)
    expect(values.pleat[1]).toBe(wandering.amplitudeM)
    expect(values.warp).toEqual(wandering.warp.flatMap((w) => [w.a, w.k, w.theta]))
  })
})
