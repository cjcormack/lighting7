import { describe, expect, it } from 'vitest'
import { Color, SRGBColorSpace } from 'three'
import { makeVolumeMaterial } from './beamShaders'
import { getGoboTexture } from './goboAtlas'
import { litByFill, LUMA, makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms, ROLL_OFF_GLSL, rollOff, setPleatShift, SURFACE_AMBIENT, SURFACE_LIGHT_GAIN } from './scene/surfaceShader'
import { pleatShape } from './scene/pleat'
import { STAGE_RENDER_PRIORITY } from './StageRender'
import { EMITTER_FLUSH_PRIORITY } from './StageEmitters'
import { BODIES_FLUSH_PRIORITY } from './bodies/StageBodies'
import { BODY_LENS_COLOR, lensColour } from './bodies/palette'

/**
 * The prototype's look (`docs/plans/stage-view-design/prototype.html`): no post-processing, every
 * fragment encoded for the canvas so beams and pools add as the eye sees them, a beam that thins
 * along its throw, and a pool that falls off from the lens.
 */

const sources = import.meta.glob(['/src/**/*.{ts,tsx}', '!/src/**/stageLook.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const surface = (options: Parameters<typeof makeSurfaceMaterial>[2] = {}) =>
  makeSurfaceMaterial(makeSurfaceUniforms(makeLightTexture().texture), { colour: '#808080', pattern: 'PLAIN', emissive: false }, options)

describe('the stage draws straight to the canvas', () => {
  it('has no post-processing: nothing imports the composer', () => {
    expect(Object.keys(sources)).toContain('/src/components/stage3d/Stage3D.tsx')
    const offenders = Object.entries(sources)
      .filter(([, text]) => /from\s+['"]@react-three\/postprocessing['"]/.test(text))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })

  it('renders after both flushes, so a frame draws what it uploaded', () => {
    expect(BODIES_FLUSH_PRIORITY).toBeLessThan(STAGE_RENDER_PRIORITY)
    expect(EMITTER_FLUSH_PRIORITY).toBeLessThan(STAGE_RENDER_PRIORITY)
  })
})

describe('the haze', () => {
  const fragment = makeVolumeMaterial(getGoboTexture()).fragmentShader

  it('encodes its own colour and adds at full alpha', () => {
    expect(fragment).toContain('gl_FragColor = vec4(linearToOutputTexel(vec4(c, 1.0)).rgb, 1.0);')
    expect(fragment).not.toContain('gl_FragColor = vec4(vColor, alpha)')
  })

  it('thins along the throw and as the beam spreads, and rolls off once', () => {
    expect(fragment).toContain('uVolAxialFade * clamp(axial / vBeamLen, 0.0, 1.0)')
    expect(fragment).toContain('uVolSpreadNear + uVolSpread * axial * tanHalf')
    expect(fragment).toContain('1.0 - exp(-vColor * vOpacity * uHaze * uVolGain * chord)')
  })
})

describe('the surfaces', () => {
  const fragment = surface().fragmentShader

  it('falls off from the lens, not the apex behind it, over the beam’s own footprint', () => {
    expect(fragment).toContain('max(dist - aperture.x, 0.3)')
    expect(fragment).toContain('acc += colour.rgb * m * facing * shade * spread / (da * min(da, FALLOFF_KNEE) + 0.5)')
    // A beam narrower than 20° puts the same light on less of the surface; a wider one is unchanged.
    expect(fragment).toContain('clamp(SPREAD_REF_TAN2 / max(frame.w * frame.w, 1e-6), 1.0, SPREAD_GAIN_MAX)')
    expect(fragment).toContain(`#define SPREAD_REF_TAN2 ${(Math.tan(Math.PI / 18) ** 2).toFixed(6)}`)
  })

  it('rolls the finish, the ambient, the fill and every light off together, and encodes every exit', () => {
    // The finish takes its own share of every light: no reflectance floor (stage-light plan D6).
    expect(fragment).toContain('vec3 lit = rollOff(albedo * (vec3((uAmbient + fill) * ao) + acc) * uLightGain);')
    expect(fragment).not.toContain('uReflectFloor')
    expect(fragment).toContain(ROLL_OFF_GLSL)
    const exits = [...fragment.matchAll(/gl_FragColor = (.*);/g)].map((m) => m[1])
    expect(exits.length).toBeGreaterThan(0)
    for (const exit of exits) expect(exit.startsWith('linearToOutputTexel(')).toBe(true)
  })

  it('folds only pleated cloth, in the mesh\'s own frame', () => {
    expect(surface().defines.PLEAT).toBeUndefined()
    const cloth = surface({ pleat: pleatShape({ uuid: 'cloth', depthM: 0.1, params: {} }) })
    expect(cloth.defines.PLEAT).toBe('')
    expect(cloth.uniforms.uPleat.value.y).toBeCloseTo(0.05, 12)
    expect(cloth.fragmentShader).toContain('uniform mat4 modelMatrix;')
    // Both the point and the lamp are measured from the edge the fold hangs from.
    expect(cloth.fragmentShader).toContain('float across = dot(vWorldPos - pleatO, pleatX) + uPleat.z;')
    expect(cloth.fragmentShader).toContain('shade = foldLight(across, dot(apex.xyz - pleatO, pleatX) + uPleat.z, dot(apex.xyz - pleatO, pleatZ));')
    setPleatShift(cloth, 1.25)
    expect(cloth.uniforms.uPleat.value.z).toBe(1.25)
    expect(cloth.fragmentShader).toContain('ao = troughAmbient(across, gl_FrontFacing);')
  })

  it('takes a fill of its own, none by default', () => {
    expect(surface().uniforms.uFill.value).toBe(0)
    expect(surface({ fill: 0.9 }).uniforms.uFill.value).toBe(0.9)
  })
})

describe('the exposure (stage-light plan D6)', () => {
  it('writes the roll-off GLSL in the same steps as the twin', () => {
    expect(ROLL_OFF_GLSL).toContain(`float y = dot(e, vec3(${LUMA.map((w) => w.toFixed(4)).join(', ')}));`)
    expect(ROLL_OFF_GLSL).toContain('float t = 1.0 - exp(-y);')
    expect(ROLL_OFF_GLSL).toContain('vec3 c = e * (y > 1e-6 ? t / y : 1.0);')
    expect(ROLL_OFF_GLSL).toContain('float m = max(c.r, max(c.g, c.b));')
    expect(ROLL_OFF_GLSL).toContain('return m > 1.0 ? mix(vec3(t), c, (1.0 - t) / (m - t)) : c;')
  })

  const luminance = (c: Color) => c.r * LUMA[0] + c.g * LUMA[1] + c.b * LUMA[2]
  /** What a finish shows under [light] linear units of white beam, no ambient. */
  const under = (hex: string, light: number) => {
    const a = new Color(hex)
    return rollOff(a.r * light * SURFACE_LIGHT_GAIN, a.g * light * SURFACE_LIGHT_GAIN, a.b * light * SURFACE_LIGHT_GAIN)
  }

  it('rolls the luminance off and keeps the chroma', () => {
    const out = rollOff(0.3, 0.1, 0.05)
    const y = 0.3 * LUMA[0] + 0.1 * LUMA[1] + 0.05 * LUMA[2]
    expect(luminance(out)).toBeCloseTo(1 - Math.exp(-y), 12)
    expect(out.r / out.g).toBeCloseTo(3, 12)
    expect(out.g / out.b).toBeCloseTo(2, 12)
    // Grey is the old per-channel curve exactly.
    expect(rollOff(0.7, 0.7, 0.7).r).toBeCloseTo(1 - Math.exp(-0.7), 12)
    expect(rollOff(0, 0, 0).getHex()).toBe(0)
  })

  it('runs a colour too bright to keep towards white, never to another hue', () => {
    const out = rollOff(20, 1, 0)
    expect(Math.max(out.r, out.g, out.b)).toBeCloseTo(1, 12)
    expect(luminance(out)).toBeCloseTo(1 - Math.exp(-(20 * LUMA[0] + LUMA[1])), 12)
    expect(out.r).toBeGreaterThan(out.g)
    expect(out.g).toBeGreaterThan(out.b)
    // Moved towards its own grey, not clipped: blue lifts with green.
    expect(out.b).toBeGreaterThan(0)
  })

  it('reflects each finish by its own albedo: black serge dark under a beam, a red drape red', () => {
    const light = 0.8 // a 15° spot at 7 m, raking: the drape harness's pool
    const serge = under('#101012', light)
    const grey = under('#808080', light)
    expect(luminance(serge)).toBeGreaterThan(0.01) // lit, against an unlit serge's ~0
    expect(luminance(serge)).toBeLessThan(luminance(grey) / 8) // and dark beside a grey card
    const red = under('#3b1219', light)
    expect(red.r).toBeGreaterThan(red.g * 4)
    expect(red.r / red.b).toBeCloseTo(new Color('#3b1219').r / new Color('#3b1219').b, 6)
  })

  it('draws an unlit housing on the same curve', () => {
    const housing = new Color('#2a2d33')
    const k = (SURFACE_AMBIENT + 0.5 * (0.3 + 0.35)) * SURFACE_LIGHT_GAIN
    expect(litByFill(housing, 0.5).equals(rollOff(housing.r * k, housing.g * k, housing.b * k))).toBe(true)
  })
})

describe('lensColour', () => {
  const hue = new Color('#ff8800')
  const srgb = (c: Color) => c.getRGB(new Color(), SRGBColorSpace)

  it('is dark glass at level 0 and the hue at level 1', () => {
    expect(lensColour(new Color(), hue, 0).getHexString()).toBe(BODY_LENS_COLOR.slice(1))
    expect(lensColour(new Color(), hue, 1).getHexString()).toBe('ff8800')
  })

  it("mixes in display space on the prototype's level^0.6", () => {
    const k = 0.5 ** 0.6
    const dark = srgb(new Color(BODY_LENS_COLOR))
    const out = srgb(lensColour(new Color(), hue, 0.5))
    expect(out.r).toBeCloseTo(dark.r + (1 - dark.r) * k, 5)
    expect(out.b).toBeCloseTo(dark.b + (0 - dark.b) * k, 5)
  })
})
