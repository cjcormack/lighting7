// @vitest-environment jsdom
//
// jsdom only because `StageEmitters` imports @react-three/fiber at top level. Nothing here renders,
// and no WebGL context is made: the materials and meshes are plain JS objects.
import { describe, expect, it } from 'vitest'
import { DataTexture, ShaderMaterial, Texture, Vector2 } from 'three'
import type { StageElementDto } from '../../../api/stageElementApi'
import { buildElement } from './builders'
import { buildEmitters } from '../StageEmitters'
import { buildEmitterLayout, MAX_PRISM_LOBES } from '../emitterLayout'
import { FINISH_LOBES, type PartFinish } from './sceneParts'
import { pleatShape } from './pleat'
import { SCRIM_THREAD_SHARE, scrimShare } from './scrimOpen'
import {
  BEAM_RENDER_ORDER,
  fabricTranslucency,
  lightIncidence,
  MUSLIN_TRANSMITTANCE,
  SCRIM_RENDER_ORDER,
  SCRIM_THREAD_GLOW,
  SCRIM_THREAD_WRAP,
  SCRIM_WRAP_FADE,
  SEE_THROUGH_GLSL,
  scrimCover,
  scrimThreadLight,
  surfaceDrawOf,
  surfaceRenderOrder,
  translucentTint,
  type SurfaceDraw,
} from './seeThrough'
import {
  makeLightTexture,
  makeSurfaceMaterial,
  makeSurfaceUniforms,
  setPaintTextures,
  setPleatAmplitude,
  setScrimNet,
  setTranslucency,
  type SurfaceMaterialOptions,
} from './surfaceShader'
import { applyWorkLights, WORK_LIGHT_LEVELS } from './workLights'

const cosDeg = (deg: number) => Math.cos((deg * Math.PI) / 180)
const ST = SCRIM_THREAD_SHARE.SHARKSTOOTH

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'DRAPE', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 8, depthM: 0.1, heightM: 6,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const IMAGE = 'a'.repeat(64)

/** The one cloth part of a dead-hung drape of [fabric] (velour when null), painted on its front with [paint]. */
function cloth(fabric: string | null, paint = false) {
  const params: Record<string, unknown> = { role: 'BACKCLOTH' }
  if (fabric != null) params.fabric = fabric
  if (paint) params.paint = { front: IMAGE }
  return buildElement(element({ params })).parts[0]
}

const drawOf = (fabric: string | null, paint = false) => {
  const part = cloth(fabric, paint)
  return surfaceDrawOf(part.light, part.finish.translucent)
}

const NET_FINISH: PartFinish = { colour: '#e9e5da', pattern: 'PLAIN', emissive: false, lobes: FINISH_LOBES.NET }

function material(draw: SurfaceDraw, options: SurfaceMaterialOptions = {}, finish: PartFinish = NET_FINISH): ShaderMaterial {
  return makeSurfaceMaterial(makeSurfaceUniforms(makeLightTexture().texture), finish, { doubleSided: true, ...options, draw })
}

describe('the scrim blend (scrim plan D9)', () => {
  it('covers 1 − open(θ_eye)^gather of a pixel, from either side', () => {
    for (const deg of [0, 20, 45, 60, 70, 80]) {
      for (const gather of [1, 1.5, 3]) {
        const c = cosDeg(deg)
        expect(scrimCover(c, ST, gather)).toBeCloseTo(1 - scrimShare(c, ST, gather), 12)
        expect(scrimCover(-c, ST, gather)).toBe(scrimCover(c, ST, gather))
      }
    }
  })

  it('is about half cloth seen square on — the Front section — and denser from the side', () => {
    // Front looks straight down the stage at a gauze facing downstage: θ_eye 0.
    expect(scrimCover(1, ST, 1)).toBeCloseTo(0.51, 12)
    expect(scrimCover(cosDeg(45), ST, 1)).toBeCloseTo(0.6, 2)
    expect(scrimCover(cosDeg(70), ST, 1)).toBeCloseTo(0.91, 2)
    // Edge-on — the Plan and Side sections see a dead-hung gauze end on — it is solid.
    expect(scrimCover(0, ST, 1)).toBe(1)
    // Gathered, a drawn half stacks its layers: a gathered net is denser square on.
    expect(scrimCover(1, ST, 2)).toBeCloseTo(1 - 0.49 * 0.49, 12)
    // Bobbinet, finer, is more open.
    expect(scrimCover(1, SCRIM_THREAD_SHARE.BOBBINET, 1)).toBeLessThan(scrimCover(1, ST, 1))
  })

  it('writes the GLSL twin in the same steps, over the one open(θ)', () => {
    expect(SEE_THROUGH_GLSL).toContain('return 1.0 - scrimShare(cosEye, r, gather);')
    // There is one copy of open(θ): this chunk reads scrimOpen.ts's, never defines its own.
    expect(SEE_THROUGH_GLSL).not.toContain('float scrimOpen(')
    expect(SEE_THROUGH_GLSL).not.toContain('float scrimShare(')
    const fragment = material('scrim').fragmentShader
    expect(fragment.match(/float scrimOpen\(/g)).toHaveLength(1)
    expect(fragment.indexOf('float scrimShare(')).toBeLessThan(fragment.indexOf('float scrimCover('))
    // Weighted by the cloth's own normal against the ray to the eye — or the camera's axis on an
    // orthographic section, where every ray is parallel.
    expect(fragment).toContain('float cover = scrimCover(dot(clothN, towardEye()), uScrim.x, uScrim.y) * uOpacity;')
    expect(fragment).toContain('vec3 clothN = pleatZ;')
    expect(fragment).toContain('vec3 clothN = normalize(vWorldNormal);')
    expect(fragment).toContain('? normalize(vec3(viewMatrix[0][2], viewMatrix[1][2], viewMatrix[2][2]))')
    expect(fragment).toContain(': normalize(cameraPosition - vWorldPos);')
    // Premultiplied, encoded like every exit.
    expect(fragment).toContain('gl_FragColor = vec4(linearToOutputTexel(vec4(lit, 1.0)).rgb * cover, cover);')
  })

  it('wraps a grazing light round the threads and glows faintly from behind', () => {
    expect(scrimThreadLight(1)).toBeCloseTo(1, 12)
    // 0.45 as soon as the light is past the wrap's fade-in, rising with the incidence.
    expect(scrimThreadLight(SCRIM_WRAP_FADE)).toBeCloseTo(SCRIM_THREAD_WRAP + (1 - SCRIM_THREAD_WRAP) * SCRIM_WRAP_FADE, 12)
    expect(scrimThreadLight(0.5)).toBeCloseTo(SCRIM_THREAD_WRAP + (1 - SCRIM_THREAD_WRAP) * 0.5, 12)
    expect(scrimThreadLight(0.2)).toBeGreaterThan(0.2)
    // In the cloth's plane nothing; from behind 0.18 of the light's incidence on the back.
    expect(scrimThreadLight(0)).toBe(0)
    expect(scrimThreadLight(-1)).toBeCloseTo(SCRIM_THREAD_GLOW, 12)
    expect(scrimThreadLight(-0.5)).toBeCloseTo(SCRIM_THREAD_GLOW * 0.5, 12)
    expect(SCRIM_THREAD_WRAP).toBe(0.45)
    expect(SCRIM_THREAD_GLOW).toBe(0.18)
    expect(SEE_THROUGH_GLSL).toContain(
      'if (facing > 0.0) return (SCRIM_THREAD_WRAP + (1.0 - SCRIM_THREAD_WRAP) * facing) * smoothstep(0.0, SCRIM_WRAP_FADE, facing);',
    )
    expect(SEE_THROUGH_GLSL).toContain('return SCRIM_THREAD_GLOW * max(0.0, -facing);')
    expect(SEE_THROUGH_GLSL).toContain(`#define SCRIM_THREAD_WRAP ${SCRIM_THREAD_WRAP.toFixed(4)}`)
    expect(SEE_THROUGH_GLSL).toContain(`#define SCRIM_THREAD_GLOW ${SCRIM_THREAD_GLOW.toFixed(4)}`)
    expect(material('scrim').fragmentShader).toContain('incidence = scrimThreadLight(behind ? -incidence : incidence);')
  })
})

describe('muslin is translucent (scrim plan D6)', () => {
  it('lights its front from behind through τ · front ⊙ back, white paint leaving τ', () => {
    expect(MUSLIN_TRANSMITTANCE).toBe(0.45)
    expect(translucentTint(MUSLIN_TRANSMITTANCE, [1, 1, 1])).toEqual([0.45, 0.45, 0.45])
    expect(translucentTint(MUSLIN_TRANSMITTANCE, [1, 1, 1], [1, 1, 1])).toEqual([0.45, 0.45, 0.45])
    // The day's blue dye over a night painted black but for an open window: the window glows blue.
    const [r, g, b] = translucentTint(MUSLIN_TRANSMITTANCE, [0.2, 0.4, 0.9], [1, 1, 1])
    expect([r, g, b].map((v) => +v.toFixed(4))).toEqual([0.09, 0.18, 0.405])
    expect(translucentTint(MUSLIN_TRANSMITTANCE, [0.2, 0.4, 0.9], [0, 0, 0])).toEqual([0, 0, 0])
    expect(SEE_THROUGH_GLSL).toContain('return tau * face * other;')
  })

  it('takes nothing behind it without back light — the term multiplies only the light behind', () => {
    // A light on the face's own side is never the back term's.
    expect(lightIncidence(0.7, 'translucent')).toEqual({ incidence: 0.7, behind: false })
    expect(lightIncidence(-0.7, 'translucent')).toEqual({ incidence: 0.7, behind: true })
    expect(lightIncidence(0, 'translucent')).toBeNull()
    const fragment = material('translucent', {}, { ...NET_FINISH, lobes: FINISH_LOBES.MATTE }).fragmentShader
    // The back term is its own sum, empty until a light behind the face adds to it.
    expect(fragment).toContain('vec3 accBehind = vec3(0.0);')
    expect(fragment).toContain('float incidence = behind ? abs(facing) : facing;')
    expect(fragment).toContain('accBehind += irradiance;')
    expect(fragment).toContain('light += translucentTint(uTranslucent, albedo, otherSide) * accBehind;')
    // An unpainted side — or one whose image has not loaded — counts as white.
    expect(fragment).toContain('vec3 otherSide = vec3(1.0);')
    expect(fragment).toContain('if (paintFace > 0.0 && uPaintOn.y > 0.5) otherSide = paintBack.rgb;')
    expect(fragment).toContain('if (paintFace < 0.0 && uPaintOn.x > 0.5) otherSide = paintFront.rgb;')
  })

  it('is opaque to the eye and to beams: a solid part, drawn opaque', () => {
    const muslin = cloth('MUSLIN', true)
    expect(muslin.light).toEqual({ kind: 'mask', image: IMAGE, uv: { u0: 0, u1: 1, v0: 0, v1: 1 } })
    expect(cloth('MUSLIN').light).toBe('solid')
    expect(muslin.finish.translucent).toBe(MUSLIN_TRANSMITTANCE)
    const m = material('translucent', { painted: true })
    expect(m.defines.TRANSLUCENT).toBe('')
    expect(m.defines.SCRIM).toBeUndefined()
    expect(m.transparent).toBe(false)
    expect(m.depthWrite).toBe(true)
    expect(m.uniforms.uTranslucent.value).toBe(MUSLIN_TRANSMITTANCE)
  })
})

describe('only a net and a muslin are seen through, and nothing else is lit from behind', () => {
  it('draws each fabric its own way, decided from the part', () => {
    expect(drawOf('SHARKSTOOTH')).toBe('scrim')
    expect(drawOf('BOBBINET')).toBe('scrim')
    // A painted net keeps its blend: its paint is its albedo, its cut-outs angle-only (FU-STAGE-CUT-NET).
    expect(drawOf('SHARKSTOOTH', true)).toBe('scrim')
    expect(drawOf('MUSLIN')).toBe('translucent')
    expect(drawOf('MUSLIN', true)).toBe('translucent')
    expect(drawOf(null)).toBe('opaque')
    expect(drawOf('CANVAS')).toBe('opaque')
    expect(drawOf('CANVAS', true)).toBe('opaque')
    expect(fabricTranslucency('VELOUR')).toBeUndefined()
    expect(fabricTranslucency('CANVAS')).toBeUndefined()
    expect(cloth(null).finish.translucent).toBeUndefined()
    expect(cloth('CANVAS', true).finish.translucent).toBeUndefined()
  })

  it('a velour or canvas cloth is not lit from behind', () => {
    for (const facing of [-1, -0.5, -0.01, 0]) expect(lightIncidence(facing, 'opaque')).toBeNull()
    expect(lightIncidence(0.5, 'opaque')).toEqual({ incidence: 0.5, behind: false })
    for (const [fabric, pleated] of [[null, true], ['CANVAS', false]] as const) {
      const part = cloth(fabric)
      const m = material(surfaceDrawOf(part.light, part.finish.translucent), {}, part.finish)
      expect(m.defines.TRANSLUCENT).toBeUndefined()
      expect(m.defines.SCRIM).toBeUndefined()
      expect(m.uniforms.uTranslucent).toBeUndefined()
      // Without either define the shader skips a light behind the face, as it always did.
      expect(m.fragmentShader).toContain('#else\n      if (facing <= 0.0) continue;\n      bool behind = false;')
      expect(part.geometry.shape).toBe(pleated ? 'pleat' : 'sheet')
    }
    // A pleat's own folds shadow only the light on its own side; the face test is unchanged there.
    expect(material('opaque').fragmentShader).toContain('if (!pleatFaceSeesLamp(lampOut, gl_FrontFacing)) continue;')
  })

  it('asks a gathered cloth which side of its plane a lamp is on, never a fold\'s flank', () => {
    // A lamp in front, on a flank turned from it: nothing, as on any cloth — not the back term.
    expect(lightIncidence(-0.4, 'translucent', false)).toBeNull()
    expect(lightIncidence(-0.4, 'scrim', false)).toBeNull()
    // A lamp behind the cloth, on a flank leaning towards it: the back term at its cosine.
    expect(lightIncidence(0.3, 'translucent', true)).toEqual({ incidence: 0.3, behind: true })
    expect(lightIncidence(0.5, 'scrim', true)?.incidence).toBeCloseTo(SCRIM_THREAD_GLOW * 0.5, 12)
    // A flat cloth asks its own face, as before.
    expect(lightIncidence(-0.4, 'translucent')).toEqual({ incidence: 0.4, behind: true })
    const pleated = material('translucent', { pleat: pleatShape({ uuid: 'muslin', depthM: 0.1, params: {} }) })
    expect(pleated.fragmentShader).toContain('bool behind = !pleatFaceSeesLamp(dot(apex.xyz - pleatO, pleatZ), gl_FrontFacing);')
    expect(pleated.fragmentShader).toContain('if (!behind && facing <= 0.0) continue;')
    expect(pleated.fragmentShader).toContain('incidence = scrimThreadLight(behind ? -incidence : incidence);')
  })

  it('a cut cloth still discards its holes and writes depth', () => {
    const part = cloth('CANVAS', true)
    expect(part.light).toMatchObject({ kind: 'mask' })
    const m = material(surfaceDrawOf(part.light, part.finish.translucent), { painted: true }, part.finish)
    expect(m.defines.PAINT).toBe('')
    expect(m.defines.SCRIM).toBeUndefined()
    expect(m.transparent).toBe(false)
    expect(m.depthWrite).toBe(true)
    expect(m.premultipliedAlpha).toBe(false)
    expect(m.fragmentShader).toContain(
      'if ((uPaintOn.x > 0.5 && paintFront.a < 0.5) || (uPaintOn.y > 0.5 && paintBack.a < 0.5)) discard;',
    )
    // A painted net discards its painted holes too, and blends the rest.
    const net = material('scrim', { painted: true })
    expect(net.defines.PAINT).toBe('')
    expect(net.defines.SCRIM).toBe('')
  })
})

describe('a scrim is drawn after the opaque surfaces and before the beams', () => {
  it('is transparent, premultiplied, and writes no depth', () => {
    const m = material('scrim')
    expect(m.defines.SCRIM).toBe('')
    expect(m.transparent).toBe(true)
    expect(m.depthWrite).toBe(false)
    expect(m.premultipliedAlpha).toBe(true)
    // The opaque surfaces are three's opaque list, drawn before any transparent object.
    const opaque = material('opaque')
    expect(opaque.transparent).toBe(false)
    expect(opaque.depthWrite).toBe(true)
  })

  it('pins the render order: opaque, then the scrim, then the beams', () => {
    expect(surfaceRenderOrder('scrim')).toBe(SCRIM_RENDER_ORDER)
    expect(surfaceRenderOrder('opaque')).toBe(0)
    expect(surfaceRenderOrder('translucent')).toBe(0)
    expect(SCRIM_RENDER_ORDER).toBeLessThan(BEAM_RENDER_ORDER)
    const layout = buildEmitterLayout([{ lobes: MAX_PRISM_LOBES, lights: MAX_PRISM_LOBES }])
    expect(buildEmitters(layout, 0, new ShaderMaterial()).volumeMesh.renderOrder).toBe(BEAM_RENDER_ORDER)
  })
})

describe('nothing recompiles when a light, the camera or the net moves', () => {
  it('holds every define, and the program, across every write a frame makes', () => {
    for (const draw of ['scrim', 'translucent', 'opaque'] as const) {
      const uniforms = makeSurfaceUniforms(makeLightTexture().texture)
      const m = makeSurfaceMaterial(uniforms, NET_FINISH, { doubleSided: true, painted: true, draw })
      const defines = { ...m.defines }
      const version = m.version
      const key = m.customProgramCacheKey()
      // A draw gathers a net; a light moves, its row is repacked and its count changes; work
      // lights switch; an image lands; the camera orbits. All uniforms.
      setScrimNet(m, ST, 2.4)
      setTranslucency(m, 0.3)
      setPleatAmplitude(m, 0.08)
      ;(uniforms.uLights.value as DataTexture).needsUpdate = true
      uniforms.uLightCount.value = 7
      applyWorkLights(uniforms, WORK_LIGHT_LEVELS.on)
      setPaintTextures(m, new Texture(), null)
      // The camera reaches the shader only through three's own built-ins (`cameraPosition`,
      // `viewMatrix`, `isOrthographic` in `towardEye`): no define and no uniform here names it.
      expect(Object.keys(m.defines).some((d) => /CAMERA|ORTHO|EYE/.test(d))).toBe(false)
      expect(m.fragmentShader).toContain('vec3 towardEye() {')
      expect(m.defines).toEqual(defines)
      expect(m.version).toBe(version)
      expect(m.customProgramCacheKey()).toBe(key)
    }
  })

  it('moves the net, the gather and τ as uniforms', () => {
    const scrim = material('scrim')
    setScrimNet(scrim, SCRIM_THREAD_SHARE.BOBBINET, 1.8)
    expect(scrim.uniforms.uScrim.value).toEqual(new Vector2(SCRIM_THREAD_SHARE.BOBBINET, 1.8))
    const muslin = material('translucent')
    setTranslucency(muslin, 0.3)
    expect(muslin.uniforms.uTranslucent.value).toBe(0.3)
    // A material without the define ignores the write.
    const opaque = material('opaque')
    setScrimNet(opaque, ST, 2)
    setTranslucency(opaque, 0.3)
    expect(opaque.uniforms.uScrim).toBeUndefined()
    expect(opaque.uniforms.uTranslucent).toBeUndefined()
  })

  it('never sees through a catch surface', () => {
    const m = material('scrim', { catchOnly: true })
    expect(m.defines.SCRIM).toBeUndefined()
    expect(m.defines.CATCH).toBe('')
  })
})
