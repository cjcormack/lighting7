import { describe, expect, it } from 'vitest'
import { Color, SRGBColorSpace } from 'three'
import { makeVolumeMaterial } from './beamShaders'
import { getGoboTexture } from './goboAtlas'
import { makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms } from './scene/surfaceShader'
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
    expect(fragment).toContain('acc += colour.rgb * m * facing * spread / (da * min(da, FALLOFF_KNEE) + 0.5)')
    // A beam narrower than 20° puts the same light on less of the surface; a wider one is unchanged.
    expect(fragment).toContain('clamp(SPREAD_REF_TAN2 / max(frame.w * frame.w, 1e-6), 1.0, SPREAD_GAIN_MAX)')
    expect(fragment).toContain(`#define SPREAD_REF_TAN2 ${(Math.tan(Math.PI / 18) ** 2).toFixed(6)}`)
  })

  it('rolls the finish, the ambient, the fill and every light off together, and encodes every exit', () => {
    expect(fragment).toContain('1.0 - exp(-(albedo * vec3(uAmbient + fill) + max(albedo, vec3(uReflectFloor)) * acc) * uLightGain)')
    const exits = [...fragment.matchAll(/gl_FragColor = (.*);/g)].map((m) => m[1])
    expect(exits.length).toBeGreaterThan(0)
    for (const exit of exits) expect(exit.startsWith('linearToOutputTexel(')).toBe(true)
  })

  it('takes a fill of its own, none by default', () => {
    expect(surface().uniforms.uFill.value).toBe(0)
    expect(surface({ fill: 0.9 }).uniforms.uFill.value).toBe(0.9)
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
