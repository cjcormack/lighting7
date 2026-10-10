import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../../api/stageElementApi'
import { buildElement } from './builders'
import {
  charlieSheen,
  finishResponse,
  ggxSpecular,
  LAMBERT_LOBES,
  lobeDefines,
  LOBES_GLSL,
  lobeUniformValues,
  orenNayar,
  orenNayarTerms,
  schlick,
  SHEEN_ALBEDO_BASE,
  SHEEN_ALBEDO_RISE,
  SHEEN_SIN2_FLOOR,
  sheenAlbedo,
  sheenTerms,
  SPECULAR_ALBEDO_GRAZING,
  specularAlbedo,
  type FinishLobes,
} from './lobes'
import { FINISH_LOBES, finishLobes, type ScenePart } from './sceneParts'
import { makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms } from './surfaceShader'

/** A direction at [theta] from the normal (+z), turned [phi] about it. */
function dir(theta: number, phi = 0): [number, number, number] {
  return [Math.sin(theta) * Math.cos(phi), Math.sin(theta) * Math.sin(phi), Math.cos(theta)]
}

/**
 * What a lobe reflects of a light at [thetaI] over the whole hemisphere — its directional albedo, in
 * Lambert's units (Lambert reflects 1): `(1/π) ∫ f cos θo dω`, midpoint rule in cos θ and φ.
 */
function albedo(f: (l: number[], v: number[]) => number, thetaI: number, n = 48): number {
  const l = dir(thetaI)
  let sum = 0
  for (let i = 0; i < n; i++) {
    const mu = (i + 0.5) / n
    const theta = Math.acos(mu)
    for (let j = 0; j < 2 * n; j++) sum += f(l, dir(theta, ((j + 0.5) / (2 * n)) * 2 * Math.PI)) * mu
  }
  return (sum * (1 / n) * (Math.PI / n)) / Math.PI
}

const INCIDENCES = [0, 20, 40, 60, 75, 85].map((d) => (d * Math.PI) / 180)

describe('Oren–Nayar', () => {
  it('is Lambert at σ 0, for every light and eye', () => {
    for (const [nl, nv, lv] of [[1, 1, 1], [0.3, 0.9, -0.2], [0.5, 0.5, 0.9]]) expect(orenNayar(0, nl, nv, lv)).toBe(1)
    expect(orenNayarTerms(0)).toEqual([1, 0])
  })

  it('reflects at normal incidence π / (π + (π/2 − 2/3) σ) of Lambert, and never more than it', () => {
    for (const sigma of [0.3, 0.5, 0.8, 1]) {
      const lobe = (l: number[], v: number[]) => orenNayar(sigma, l[2], v[2], l[0] * v[0] + l[1] * v[1] + l[2] * v[2])
      expect(albedo(lobe, 0)).toBeCloseTo(Math.PI / (Math.PI + (Math.PI / 2 - 2 / 3) * sigma), 3)
      for (const t of INCIDENCES) expect(albedo(lobe, t), `σ ${sigma} at ${t}`).toBeLessThanOrEqual(1.001)
    }
  })

  it('flattens a rough surface: brighter lit from behind the eye than Lambert, darker lit across it', () => {
    const at45 = Math.SQRT1_2
    // The light behind the eye, both 45° off the normal: s = 1 − ½.
    expect(orenNayar(0.5, at45, at45, 1)).toBeGreaterThan(1)
    // Across the normal from the eye: s < 0, only the darker A term.
    expect(orenNayar(0.5, at45, at45, 0)).toBeLessThan(1)
  })
})

describe('the Charlie sheen', () => {
  const lobe = (r: number) => (l: number[], v: number[]) => {
    const h = [l[0] + v[0], l[1] + v[1], l[2] + v[2]]
    return charlieSheen(r, l[2], v[2], h[2] / Math.hypot(h[0], h[1], h[2]))
  }

  it('reflects a few percent lit square on, and more as the light rakes', () => {
    const r = FINISH_LOBES.VELOUR.sheenRoughness
    const normal = albedo(lobe(r), 0)
    expect(normal).toBeGreaterThan(0.04)
    expect(normal).toBeLessThan(0.1)
    const grazing = albedo(lobe(r), INCIDENCES[INCIDENCES.length - 1])
    expect(grazing).toBeGreaterThan(normal * 4)
    // White and at full strength it reflects at most two thirds of what reaches it, at a grazing
    // incidence; velour's strength is an eighth of that.
    for (const t of INCIDENCES) expect(albedo(lobe(r), t)).toBeLessThan(0.65)
  })

  it('is next to nothing lit and seen square on, and greatest where the half vector lies in the cloth', () => {
    const r = FINISH_LOBES.VELOUR.sheenRoughness
    const [norm, exponent] = sheenTerms(r)
    expect(charlieSheen(r, 1, 1, 1)).toBeCloseTo((norm * SHEEN_SIN2_FLOOR ** exponent) / 4, 10)
    expect(charlieSheen(r, 1, 1, 1)).toBeLessThan(0.01)
    const raking = charlieSheen(r, 0.26, 1, Math.cos((37.5 * Math.PI) / 180))
    expect(raking).toBeGreaterThan(charlieSheen(r, 1, 1, 1) * 20)
    expect(charlieSheen(r, 0.5, 0.5, 0)).toBeGreaterThan(raking)
  })

  it('broadens as r rises: rougher pile shines over more of the hemisphere', () => {
    const tight = sheenTerms(0.3)
    const broad = sheenTerms(1)
    expect(tight[1]).toBeGreaterThan(broad[1])
    expect(broad).toEqual([1.5, 0.5])
  })
})

describe('GGX', () => {
  const lobe = (f0: number, roughness: number) => (l: number[], v: number[]) => {
    const h = [l[0] + v[0], l[1] + v[1], l[2] + v[2]]
    const len = Math.hypot(h[0], h[1], h[2])
    return ggxSpecular(f0, roughness, l[2], v[2], h[2] / len, (v[0] * h[0] + v[1] * h[1] + v[2] * h[2]) / len)
  }

  it('reflects about its F0 square on, and more towards grazing, as Fresnel says', () => {
    for (const r of [FINISH_LOBES.FLOOR.roughness, FINISH_LOBES.DECK.roughness, FINISH_LOBES.PAINT.roughness]) {
      const normal = albedo(lobe(0.04, r), 0, 96)
      expect(normal, `roughness ${r}`).toBeGreaterThan(0.025)
      expect(normal, `roughness ${r}`).toBeLessThanOrEqual(0.041)
      expect(albedo(lobe(0.04, r), INCIDENCES[INCIDENCES.length - 1], 96)).toBeGreaterThan(normal)
    }
  })

  it('peaks at the mirror direction at 1/α², sharper as the floor gets smoother', () => {
    const satin = FINISH_LOBES.FLOOR.roughness
    const a = satin * satin
    // Lit and seen square on: nh = vh = 1, Fresnel its F0, Hammon's visibility ¼.
    expect(ggxSpecular(0.04, satin, 1, 1, 1, 1)).toBeCloseTo((1 / (a * a)) * 0.25 * 0.04, 6)
    expect(ggxSpecular(0.04, 0.2, 1, 1, 1, 1)).toBeGreaterThan(ggxSpecular(0.04, satin, 1, 1, 1, 1))
    // Off the mirror by 20° the satin floor has fallen to a twentieth of its peak.
    expect(ggxSpecular(0.04, satin, 0.9, 0.9, Math.cos(0.35), 0.9)).toBeLessThan(ggxSpecular(0.04, satin, 0.9, 0.9, 1, 0.9) / 20)
  })

  it('takes Schlick’s Fresnel to its F0 square on and to 1 at grazing', () => {
    expect(schlick(0.04, 1)).toBeCloseTo(0.04, 10)
    expect(schlick(0.04, 0)).toBe(1)
  })
})

describe('a finish', () => {
  it('reflects no more light than reaches it, within 5 %, at any incidence, for every preset', () => {
    // The diffuse gives up what the sheen and the specular take (`sheenAlbedo`, `specularAlbedo`);
    // without that, velour reflected 9 % more than arrives at a grazing light and paint 56 % less.
    for (const [name, lobes] of Object.entries(FINISH_LOBES)) {
      for (const t of INCIDENCES) {
        const a = albedo((l, v) => finishResponse(lobes, l, v), t, 64)
        expect(a, `${name} at ${t}`).toBeLessThan(1.05)
        expect(a, `${name} at ${t}`).toBeGreaterThan(0.85)
      }
    }
  })

  it('fits the lobes’ albedo with the two closed forms the diffuse gives up', () => {
    const r = FINISH_LOBES.VELOUR.sheenRoughness
    for (const t of INCIDENCES) {
      const sheen = albedo((l, v) => {
        const h = [l[0] + v[0], l[1] + v[1], l[2] + v[2]]
        return charlieSheen(r, l[2], v[2], h[2] / Math.hypot(h[0], h[1], h[2]))
      }, t)
      expect(Math.abs(sheenAlbedo(Math.cos(t)) - sheen), `sheen at ${t}`).toBeLessThan(0.08)
      for (const rough of [FINISH_LOBES.FLOOR.roughness, FINISH_LOBES.PAINT.roughness]) {
        const spec = albedo((l, v) => {
          const h = [l[0] + v[0], l[1] + v[1], l[2] + v[2]]
          const len = Math.hypot(h[0], h[1], h[2])
          return ggxSpecular(0.04, rough, l[2], v[2], h[2] / len, (v[0] * h[0] + v[1] * h[1] + v[2] * h[2]) / len)
        }, t, 64)
        // One fit for every roughness: close for the satin floor, generous for rough paint at a
        // grazing light, where the diffuse then gives up a little more than the lobe takes.
        expect(specularAlbedo(0.04, Math.cos(t)) - spec, `specular ${rough} at ${t}`).toBeGreaterThan(-0.06)
        expect(specularAlbedo(0.04, Math.cos(t)) - spec, `specular ${rough} at ${t}`).toBeLessThan(0.12)
      }
    }
  })

  it('keeps session 2’s balance: lit square on, every preset within 13 % of Lambert', () => {
    for (const [name, lobes] of Object.entries(FINISH_LOBES)) {
      const normal = albedo((l, v) => finishResponse(lobes, l, v), 0, 64)
      expect(normal, name).toBeGreaterThan(0.87)
      expect(normal, name).toBeLessThanOrEqual(1.001)
    }
  })

  it('compiles in only the lobes it carries', () => {
    expect(lobeDefines(LAMBERT_LOBES)).toEqual({ orenNayar: false, sheen: false, ggx: false })
    expect(lobeDefines(FINISH_LOBES.VELOUR)).toEqual({ orenNayar: true, sheen: true, ggx: false })
    expect(lobeDefines(FINISH_LOBES.PAINT)).toEqual({ orenNayar: true, sheen: false, ggx: true })
    expect(lobeDefines(FINISH_LOBES.FLOOR)).toEqual({ orenNayar: false, sheen: false, ggx: true })
    const uniforms = makeSurfaceUniforms(makeLightTexture().texture)
    const material = (lobes: FinishLobes, emissive = false) =>
      makeSurfaceMaterial(uniforms, { colour: '#3b1219', pattern: 'PLAIN', emissive, lobes })
    expect(material(LAMBERT_LOBES).defines).toEqual({})
    expect(material(FINISH_LOBES.VELOUR).defines).toEqual({ LOBE_OREN_NAYAR: '', LOBE_SHEEN: '' })
    expect(material(FINISH_LOBES.FLOOR).defines).toEqual({ LOBE_GGX: '' })
    // Emissive and catch surfaces take no light through a finish, so carry no lobes.
    expect(material(FINISH_LOBES.VELOUR, true).defines).toEqual({ EMISSIVE: '' })
    expect(
      makeSurfaceMaterial(uniforms, { colour: '#ffffff', pattern: 'PLAIN', emissive: false, lobes: FINISH_LOBES.FLOOR }, { catchOnly: true }).defines,
    ).toEqual({ CATCH: '' })
  })

  it('sheens by √albedo: red velour red, black serge a dim grey', () => {
    const [norm, exponent] = sheenTerms(FINISH_LOBES.VELOUR.sheenRoughness)
    const red = lobeUniformValues(FINISH_LOBES.VELOUR, 0.044, 0.006, 0.01)
    expect(red.sheen[0]).toBeCloseTo(FINISH_LOBES.VELOUR.sheen * norm * Math.sqrt(0.044), 10)
    expect(red.sheen[0]).toBeGreaterThan(red.sheen[1] * 2.5)
    expect(red.sheen[3]).toBe(exponent)
    // The diffuse gives up the brightest channel's share.
    expect(red.sheenStrength).toBeCloseTo(FINISH_LOBES.VELOUR.sheen * Math.sqrt(0.044), 10)
    const black = lobeUniformValues(FINISH_LOBES.VELOUR, 0.005, 0.005, 0.006).sheen
    expect(black[0] / black[2]).toBeGreaterThan(0.9)
    expect(black[0]).toBeLessThan(red.sheen[0] / 2.5)
  })
})

describe('the GLSL', () => {
  it('holds the twins’ formulae and constants', () => {
    expect(LOBES_GLSL).toContain('return uOrenNayar.x + uOrenNayar.y * s / (s > 0.0 ? max(nl, nv) : 1.0);')
    expect(LOBES_GLSL).toContain(`float sin2 = max(1.0 - nh * nh, ${SHEEN_SIN2_FLOOR.toFixed(7)});`)
    expect(LOBES_GLSL).toContain('return uSheen.rgb * pow(sin2, uSheen.a) / (4.0 * (nl + nv - nl * nv));')
    expect(LOBES_GLSL).toContain('float vis = 0.5 / (nl * (nv * (1.0 - a) + a) + nv * (nl * (1.0 - a) + a));')
    expect(LOBES_GLSL).toContain('return a2 / (d * d) * vis * schlick(uSpecular.x, vh);')
    expect(LOBES_GLSL).toContain('return f0 + (1.0 - f0) * m2 * m2 * m;')
    expect(LOBES_GLSL).toContain(`return 1.0 - uSheenStrength * (${SHEEN_ALBEDO_BASE.toFixed(7)} + ${SHEEN_ALBEDO_RISE.toFixed(7)} * m * m);`)
    expect(LOBES_GLSL).toContain(`return 1.0 - uSpecular.x - (1.0 - uSpecular.x) * ${SPECULAR_ALBEDO_GRAZING.toFixed(7)} * m2 * m2 * m;`)
    const values = lobeUniformValues(FINISH_LOBES.FLOOR, 0.1, 0.1, 0.1)
    expect(values.specular).toEqual([0.04, FINISH_LOBES.FLOOR.roughness ** 2])
    expect(values.orenNayar).toEqual(orenNayarTerms(0))
  })

  it('sums the lobes in the surface shader as the twin does', () => {
    const fragment = makeSurfaceMaterial(makeSurfaceUniforms(makeLightTexture().texture), {
      colour: '#808080', pattern: 'PLAIN', emissive: false, lobes: FINISH_LOBES.PAINT,
    }).fragmentShader
    expect(fragment).toContain(LOBES_GLSL)
    expect(fragment).toContain('diffuse = orenNayar(facing, NV, dot(-L, V));')
    expect(fragment).toContain('gloss += irradiance * ggxSpecular(facing, NV, NH, max(dot(V, H), 0.0));')
    expect(fragment).toContain('diffuse *= specularKeeps(facing);')
    expect(fragment).toContain('acc += irradiance * diffuse;')
    // D6 unchanged: one exposure, one roll-off, the gloss beside the albedo's light, not under it.
    expect(fragment).toContain('vec3 light = albedo * (vec3((uAmbient + fill) * ao) + acc) + lift + gloss;')
    expect(fragment).toContain('vec3 lit = rollOff(light * uLightGain);')
  })
})

describe('the finish defaults, by kind, role and part', () => {
  it('reads the table', () => {
    expect(finishLobes('DRAPE', 'LEG')).toBe(FINISH_LOBES.VELOUR)
    expect(finishLobes('DRAPE', 'BACKCLOTH')).toBe(FINISH_LOBES.VELOUR)
    expect(finishLobes('DRAPE', 'CYC')).toBe(FINISH_LOBES.MATTE)
    // A drape's fabric outranks its role (scrim plan session 2): canvas and muslin matte, the nets net.
    expect(finishLobes('DRAPE', 'LEG', 'body', 'CANVAS')).toBe(FINISH_LOBES.MATTE)
    expect(finishLobes('DRAPE', 'BACKCLOTH', 'body', 'MUSLIN')).toBe(FINISH_LOBES.MATTE)
    expect(finishLobes('DRAPE', 'BACKCLOTH', 'body', 'SHARKSTOOTH')).toBe(FINISH_LOBES.NET)
    expect(finishLobes('DRAPE', 'CYC', 'body', 'BOBBINET')).toBe(FINISH_LOBES.NET)
    expect(finishLobes('DRAPE', 'LEG', 'body', null)).toBe(FINISH_LOBES.VELOUR)
    expect(lobeDefines(FINISH_LOBES.NET)).toEqual({ orenNayar: true, sheen: false, ggx: false })
    expect(finishLobes('FLAT', null)).toBe(FINISH_LOBES.PAINT)
    expect(finishLobes('OBJECT', null)).toBe(FINISH_LOBES.PAINT)
    expect(finishLobes('PLATFORM', null)).toBe(FINISH_LOBES.DECK)
    expect(finishLobes('ROOM', null)).toBe(FINISH_LOBES.MATTE)
    expect(finishLobes('ROOM', null, 'floor')).toBe(FINISH_LOBES.DECK)
    expect(finishLobes('ROOM', null, 'ceiling')).toBe(FINISH_LOBES.MATTE)
    expect(finishLobes('PROSCENIUM', null, 'surround')).toBe(FINISH_LOBES.MATTE)
    expect(finishLobes('SEATING', null, 'seat')).toBe(FINISH_LOBES.VELOUR)
    expect(finishLobes('SEATING', null, 'frame')).toBe(FINISH_LOBES.PAINT)
    expect(finishLobes('SOMETHING_NEW', null)).toBe(FINISH_LOBES.PAINT)
  })

  function element(fields: Partial<StageElementDto>): StageElementDto {
    return {
      id: 1, uuid: 'e', name: 'E', kind: 'FLAT', layer: 'SET', positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0,
      widthM: 4, depthM: 0.1, heightM: 3, finishColour: null, finishPattern: null, emissive: false, params: {},
      hidden: false, sortOrder: 0, ...fields,
    }
  }
  const lobesOf = (parts: ScenePart[], key: string) => parts.find((p) => p.key === key)?.finish.lobes

  it('reaches every part the builders make', () => {
    const room = buildElement(element({ kind: 'ROOM', widthM: 10, depthM: 8, heightM: 5, params: { floor: { colour: '#222222' } } })).parts
    expect(lobesOf(room, 'floor')).toBe(FINISH_LOBES.DECK)
    expect(lobesOf(room, 'ceiling')).toBe(FINISH_LOBES.MATTE)
    expect(lobesOf(room, 'upstage')).toBe(FINISH_LOBES.MATTE)
    const pros = buildElement(element({
      kind: 'PROSCENIUM', widthM: 12, depthM: 0.4, heightM: 8,
      params: { openingWidthM: 8, openingHeightM: 5, surroundM: 0.3 },
    })).parts
    expect(lobesOf(pros, 'pier-end')).toBe(FINISH_LOBES.PAINT)
    expect(lobesOf(pros, 'surround-head')).toBe(FINISH_LOBES.MATTE)
    const deck = buildElement(element({ kind: 'PLATFORM', widthM: 4, depthM: 2, heightM: 0.4, params: { railHeightM: 1, railEdge: 'DOWNSTAGE' } })).parts
    expect(lobesOf(deck, 'deck')).toBe(FINISH_LOBES.DECK)
    expect(lobesOf(deck, 'rail')).toBe(FINISH_LOBES.PAINT)
    expect(lobesOf(buildElement(element({ kind: 'DRAPE', params: { role: 'LEG' } })).parts, 'cloth')).toBe(FINISH_LOBES.VELOUR)
    expect(lobesOf(buildElement(element({ kind: 'DRAPE', params: { role: 'CYC' } })).parts, 'cloth')).toBe(FINISH_LOBES.MATTE)
    expect(lobesOf(buildElement(element({ kind: 'OBJECT', params: { shape: 'CYLINDER' } })).parts, 'body')).toBe(FINISH_LOBES.PAINT)
  })
})
