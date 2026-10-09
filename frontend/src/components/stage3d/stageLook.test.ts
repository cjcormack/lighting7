import { describe, expect, it } from 'vitest'
import { Color, SRGBColorSpace, Texture } from 'three'
import { makeVolumeMaterial } from './beamShaders'
import { getGoboTexture } from './goboAtlas'
import { LIFT_ALBEDO_FLOOR, litByFill, LUMA, makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms, PAINT_FACE_ATTRIBUTE, ROLL_OFF_GLSL, rollOff, setPaintTextures, setPleatAmplitude, setPleatShift, SURFACE_AMBIENT, SURFACE_LIGHT_GAIN } from './scene/surfaceShader'
import { WORK_LIGHT_LEVELS } from './scene/workLights'
import { LAMBERT_LOBES } from './scene/lobes'
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
  makeSurfaceMaterial(makeSurfaceUniforms(makeLightTexture().texture), { colour: '#808080', pattern: 'PLAIN', emissive: false, lobes: LAMBERT_LOBES }, options)

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
    expect(fragment).toContain('vec3 irradiance = colour.rgb * m * facing * shade * spread / (da * min(da, FALLOFF_KNEE) + 0.5);')
    expect(fragment).toContain('acc += irradiance * diffuse;')
    // A beam narrower than 20° puts the same light on less of the surface; a wider one is unchanged.
    expect(fragment).toContain('clamp(SPREAD_REF_TAN2 / max(frame.w * frame.w, 1e-6), 1.0, SPREAD_GAIN_MAX)')
    expect(fragment).toContain(`#define SPREAD_REF_TAN2 ${(Math.tan(Math.PI / 18) ** 2).toFixed(6)}`)
  })

  it('rolls the finish, the ambient, the fill and every light off together, and encodes every exit', () => {
    // The finish takes its own share of every light: no reflectance floor (stage-light plan D6). The
    // sheen and the specular (session 4) are rolled off with it, beside the albedo's light.
    expect(fragment).toContain('vec3 lit = rollOff((albedo * (vec3((uAmbient + fill) * ao) + acc) + lift + gloss) * uLightGain);')
    expect(fragment).not.toContain('uReflectFloor')
    // Work lights' lift (stage-view menu D6, D7): along the fill's direction, off at least the floor
    // albedo — the lift's share only, never a light's.
    expect(fragment).toContain('float fill = uFill * fillDirection;')
    expect(fragment).toContain('vec3 lift = max(albedo, vec3(uLiftAlbedoFloor)) * (uLift * fillDirection * ao);')
    expect(fragment).toContain('acc += irradiance * diffuse;')
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
    expect(cloth.fragmentShader).toContain('float lampOut = dot(apex.xyz - pleatO, pleatZ);')
    expect(cloth.fragmentShader).toContain('shade = foldLight(across, dot(apex.xyz - pleatO, pleatX) + uPleat.z, lampOut);')
    // A face sees only its own side of the sheet, whichever way it faces the lamp.
    expect(cloth.fragmentShader).toContain('if (!pleatFaceSeesLamp(lampOut, gl_FrontFacing)) continue;')
    setPleatShift(cloth, 1.25)
    expect(cloth.uniforms.uPleat.value.z).toBe(1.25)
    // A gathered cloth's fold deepens as it is drawn, as a uniform (scrim plan D2).
    setPleatAmplitude(cloth, 0.08)
    expect(cloth.uniforms.uPleat.value.y).toBe(0.08)
    expect(cloth.fragmentShader).toContain('ao = troughAmbient(across, gl_FrontFacing);')
  })

  it('paints a painted part from its images by face, cutting holes in both, as uniforms (scrim plan D4, D5)', () => {
    expect(surface().defines.PAINT).toBeUndefined()
    expect(surface().uniforms.uPaintOn).toBeUndefined()
    // A catch surface is never painted.
    expect(surface({ painted: true, catchOnly: true }).defines.PAINT).toBeUndefined()
    const cloth = surface({ painted: true, doubleSided: true })
    expect(cloth.defines.PAINT).toBe('')
    expect(cloth.vertexShader).toContain(`attribute float ${PAINT_FACE_ATTRIBUTE};`)
    expect(cloth.vertexShader).toContain('vPaintUv = uv;')
    const f = cloth.fragmentShader
    // The face drawn picks the image; the back sees it mirrored; rows top first.
    expect(f).toContain('float paintFace = vPaintFace * (gl_FrontFacing ? 1.0 : -1.0);')
    expect(f).toContain('texture(uPaintFront, vec2(vPaintUv.x, 1.0 - vPaintUv.y))')
    expect(f).toContain('texture(uPaintBack, vec2(1.0 - vPaintUv.x, 1.0 - vPaintUv.y))')
    expect(f).toContain('if ((uPaintOn.x > 0.5 && paintFront.a < 0.5) || (uPaintOn.y > 0.5 && paintBack.a < 0.5)) discard;')
    // The paint is the albedo every light, the fill and the work lights' lift then see.
    expect(f.indexOf('albedo = paintFront.rgb;')).toBeGreaterThan(-1)
    expect(f.indexOf('albedo = paintFront.rgb;')).toBeLessThan(f.indexOf('vec3 lift = max(albedo'))
    // Nothing on until an image is bound; binding is a uniform write, never a recompile.
    expect(cloth.uniforms.uPaintOn.value.toArray()).toEqual([0, 0])
    const version = cloth.version
    const front = new Texture()
    setPaintTextures(cloth, front, null)
    expect(cloth.uniforms.uPaintFront.value).toBe(front)
    expect(cloth.uniforms.uPaintOn.value.toArray()).toEqual([1, 0])
    setPaintTextures(cloth, null, front)
    expect(cloth.uniforms.uPaintOn.value.toArray()).toEqual([0, 1])
    expect(cloth.uniforms.uPaintFront.value).not.toBe(front)
    expect(cloth.version).toBe(version)
    // An unpainted material ignores it.
    expect(() => setPaintTextures(surface(), front, front)).not.toThrow()
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

  it('draws it unchanged with work lights off — the default is the off row, exactly', () => {
    const housing = new Color('#2a2d33')
    expect(litByFill(housing, 0.5, 0.5, WORK_LIGHT_LEVELS.off).equals(litByFill(housing, 0.5))).toBe(true)
  })

  it('lifts it with work lights on: the room’s ambient, and a lift off at least the floor albedo', () => {
    const housing = new Color('#2a2d33') // linear ≈ 0.023 — under the 4 % floor on every channel
    const on = WORK_LIGHT_LEVELS.on
    const direction = 0.3 + 0.35
    const k = (on.ambient + 0.5 * direction) * SURFACE_LIGHT_GAIN
    const lift = on.lift * direction * SURFACE_LIGHT_GAIN
    const expected = rollOff(
      housing.r * k + Math.max(housing.r, LIFT_ALBEDO_FLOOR) * lift,
      housing.g * k + Math.max(housing.g, LIFT_ALBEDO_FLOOR) * lift,
      housing.b * k + Math.max(housing.b, LIFT_ALBEDO_FLOOR) * lift,
    )
    expect(litByFill(housing, 0.5, 0.5, on).equals(expected)).toBe(true)
    expect(luminance(litByFill(housing, 0.5, 0.5, on))).toBeGreaterThan(luminance(litByFill(housing, 0.5)))
  })

  it('gives black serge a dark grey under work lights, where its own albedo would give it nothing', () => {
    const serge = new Color('#111111') // 0.6 % reflectance
    const on = WORK_LIGHT_LEVELS.on
    const lifted = litByFill(serge, 0, 1, on)
    const ownAlbedo = rollOff(...([serge.r, serge.g, serge.b].map((a) => a * (on.ambient + on.lift) * SURFACE_LIGHT_GAIN) as [number, number, number]))
    expect(luminance(lifted)).toBeGreaterThan(luminance(ownAlbedo) * 3)
    // Still dark: under 2 % linear, a deep grey rather than a lit cloth.
    expect(luminance(lifted)).toBeLessThan(0.02)
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
