import {
  AdditiveBlending,
  Color,
  DataArrayTexture,
  DataTexture,
  DoubleSide,
  FloatType,
  FrontSide,
  NearestFilter,
  RGBAFormat,
  ShaderMaterial,
  Vector2,
  Vector3,
  Vector4,
} from 'three'
import { BEAM_FOCUS_GLSL, BEAM_MASK_GLSL } from '../beamMask'
import { getGoboTexture } from '../goboAtlas'
import { GOBO_LAYERS_GLSL } from '../goboLayers'
import { FOCUS_LOD_MAX, GOBO_BLUR_TEXELS } from '../washConfig'
import { LANDING_GLSL, REACH_EPS_M } from './landing'
import { lobeDefines, LOBES_GLSL, lobeUniformValues } from './lobes'
import { BEAM_FRAME_GLSL, LIGHT_TEXELS, MAX_LIGHT_BUDGET, UNPACK_EDGE_IRIS_GLSL, UNPACK_FOCUS_GLSL } from './lightTable'
import { COLLIDER_TEXELS, LIST_TEXELS, makeColliderSet, OCCLUSION_GLSL, type ColliderSet } from './occlusion'
import { PLEAT_GLSL, pleatUniformValues, type PleatShape } from './pleat'
import type { FinishPattern, PartFinish } from './sceneParts'

/**
 * The **light-array receiver** (stage-view plan session 3): one shader for every surface a beam
 * lands on — the venue and set elements, the regions, the stage floor and its back wall — looping
 * over the live lights in the canvas's light table ([`lightTable.ts`](./lightTable.ts)) rather than
 * each beam drawing a "cookie" per receiver. Its cost is pixels × lights, which is what the light
 * budget caps; it has no cap on receivers, which the cookie instances had (16 regions, and a floor
 * and one wall).
 *
 * Per light and fragment: inside the cone from the beam's **apex** (behind its aperture — stage-view
 * plan session 6) and facing the light — then the beam's cross-section, `beamMask`
 * (`../beamMask.ts`), the one the haze is shaped by: its field circle or a segment's rectangle, its
 * iris, and an edge softened by the family and spread by how far the surface sits from the focal
 * plane, which is measured along the axis from the aperture — and then **not shadowed**: the segment
 * from the fragment to the lamp meets none of the colliders in that light's cone (`occlusion.ts`,
 * stage-light plan session 3), so a flat shadows the wall behind it. A light whose cone reaches more
 * colliders than its list holds falls back to where its beam lands (`landing.ts`'s two planes, which
 * the haze still reads). A pool **falls off with distance from the aperture** — as
 * the square of it out to [FALLOFF_KNEE_M], the prototype's throws, and linearly past it, as an eye
 * adapted to the stage sees a long throw rather than as a meter reads it — and is as bright as its
 * beam is narrow: the light is spread over the footprint, radius `da · tan(half
 * field)`: a beam narrower than 20° lands brighter by the area it does not spread over, so a 15°
 * spot still reaches the back of the stage from the balcony. A wider beam lands as the prototype
 * drew it (`da` at least 0.3 m, the gain capped for a pinspot).
 *
 * **Gobos land too** (fixture-optics plan session 4, D10): a light carrying gobo layers (texel 4,
 * `../goboLayers.ts`) samples the atlas in the same frame the mask cuts in, turned by each layer's
 * angle, multiplied over the mask — so the blades, the iris and an oval cut the gobo as they cut the
 * pool — at the blur level of the focus blur the edge is softened by, or of the pixel's footprint
 * where that is wider. The haze samples it through the same function, so a gobo sharp in the air at
 * a distance is sharp on a wall at that distance. A drape's folds shadow each other (stage-light plan D7, `PLEAT`):
 * the fold is a known sine in the part's own frame — the mesh's model matrix — so `pleat.ts`'s
 * `foldLight` answers whether a crest stands between the point and the lamp, and its troughs see
 * less of the room's ambient.
 *
 * The colour (stage-light plan D6): every light, the room's ambient and the material's own fill
 * summed, times the finish **and nothing else** — a `#111` serge reflects its own 0.6 % — plus, per
 * light, the finish's sheen and specular (`lobes.ts`, session 4: Oren–Nayar shapes the diffuse, the
 * Charlie sheen and GGX add over it, uncoloured by the albedo but for the sheen's own hue), exposed by
 * [SURFACE_LIGHT_GAIN] and rolled off once by `1 − exp(−·)` **on the luminance**, the chroma kept
 * ([rollOff]), and encoded. So two pools overlapping add as the eye sees them, a wall under the whole
 * rig is bright rather than a flat blown-out plate, a white pool on a red drape is red, and a colour
 * too bright to keep runs towards white rather than towards another hue. What makes black serge show
 * a spot is the exposure, not a reflectance floor: the finishes keep their own range.
 */

/**
 * The room's light on every surface, and the exposure the summed light is rolled off at. The
 * exposure is what makes black serge show a spot; the ambient and the materials' fills are set
 * against it, so what no beam reaches — the house, a housing — stays near black.
 */
export const SURFACE_AMBIENT = 0.003
export const SURFACE_LIGHT_GAIN = 4.4

/** The weights a colour's luminance is read with: Rec. 709, the canvas's linear working space. */
export const LUMA: readonly [number, number, number] = [0.2126, 0.7152, 0.0722]

/**
 * The roll-off ([ROLL_OFF_GLSL]'s twin): `1 − exp(−Y)` on the luminance `Y`, the colour scaled by
 * as much, so it keeps its chroma. Where that would push a channel past 1, the colour moves towards
 * the grey of its own luminance until it fits — a bright saturated colour runs to white, never to
 * another hue.
 */
export function rollOff(r: number, g: number, b: number, out = new Color()): Color {
  const y = r * LUMA[0] + g * LUMA[1] + b * LUMA[2]
  const t = 1 - Math.exp(-y)
  const k = y > 1e-6 ? t / y : 1
  let cr = r * k
  let cg = g * k
  let cb = b * k
  const m = Math.max(cr, cg, cb)
  if (m > 1) {
    const f = (1 - t) / (m - t)
    cr = t + (cr - t) * f
    cg = t + (cg - t) * f
    cb = t + (cb - t) * f
  }
  return out.setRGB(cr, cg, cb)
}

export const ROLL_OFF_GLSL = /* glsl */ `
  vec3 rollOff(vec3 e) {
    float y = dot(e, vec3(${LUMA.map((w) => w.toFixed(4)).join(', ')}));
    float t = 1.0 - exp(-y);
    vec3 c = e * (y > 1e-6 ? t / y : 1.0);
    float m = max(c.r, max(c.g, c.b));
    return m > 1.0 ? mix(vec3(t), c, (1.0 - t) / (m - t)) : c;
  }
`

/**
 * A finish as the surface shader draws it lit by its ambient and fill alone, with [facing] the
 * normal's dot with the fill's direction — the billboard's stand-in for an unlit housing.
 */
export function litByFill(albedo: Color, fill: number, facing = 0.5): Color {
  const k = (SURFACE_AMBIENT + fill * (0.3 + 0.7 * Math.max(facing, 0))) * SURFACE_LIGHT_GAIN
  return rollOff(albedo.r * k, albedo.g * k, albedo.b * k)
}

/** What a catch surface reflects — the floor round a stage with no room — as a 25 % finish would. */
const CATCH_REFLECTANCE = 0.25

/** The half field below which a pool brightens with how narrow its beam is: a 20° field. */
const SPREAD_REF_HALF_DEG = 10
/** A pinspot's ceiling on that normalisation, so a 2° beam is a hot spot rather than a white hole. */
const SPREAD_GAIN_MAX = 16
/** Where a pool stops falling off as the square of its throw and starts falling off linearly. */
const FALLOFF_KNEE_M = 6

/** The shared uniforms of one canvas's surfaces: the light table, how many rows are live, the room's fill. */
export interface SurfaceUniforms {
  uLights: { value: DataTexture }
  uLightCount: { value: number }
  uAmbient: { value: number }
  uLightGain: { value: number }
  uCatchLevel: { value: number }
  uGobo: { value: DataArrayTexture }
  uGoboBlurTexels: { value: number }
  uLodMax: { value: number }
  /** The scene's colliders, `occlusion.ts`'s `packColliders`. */
  uColliders: { value: DataTexture }
  /** Each packed light row's colliders, `occlusion.ts`'s `cullLightColliders`. */
  uLightColliders: { value: DataTexture }
}

/** A canvas's light texture: [MAX_LIGHT_BUDGET] rows of [LIGHT_TEXELS] RGBA float texels. */
export function makeLightTexture(): { texture: DataTexture; data: Float32Array } {
  const data = new Float32Array(MAX_LIGHT_BUDGET * LIGHT_TEXELS * 4)
  return { texture: fetchOnly(new DataTexture(data, LIGHT_TEXELS, MAX_LIGHT_BUDGET, RGBAFormat, FloatType)), data }
}

/**
 * A float texture read with `texelFetch` only — never filtered, never mipmapped (float linear
 * filtering is an extension).
 */
function fetchOnly<T extends DataTexture>(texture: T): T {
  texture.magFilter = NearestFilter
  texture.minFilter = NearestFilter
  texture.generateMipmaps = false
  texture.needsUpdate = true
  return texture
}

/**
 * A canvas's occlusion textures (`occlusion.ts`): the colliders, [COLLIDER_TEXELS] RGBA texels a
 * row, and each light row's list, [LIST_TEXELS] RGBA texels a row, one row per light row.
 */
export interface OcclusionTextures {
  colliders: DataTexture
  set: ColliderSet
  lists: DataTexture
  listData: Float32Array
}

export function makeOcclusionTextures(): OcclusionTextures {
  const set = makeColliderSet()
  const colliders = fetchOnly(new DataTexture(set.data, COLLIDER_TEXELS, set.data.length / (COLLIDER_TEXELS * 4), RGBAFormat, FloatType))
  const listData = new Float32Array(LIST_TEXELS * 4 * MAX_LIGHT_BUDGET)
  const lists = fetchOnly(new DataTexture(listData, LIST_TEXELS, MAX_LIGHT_BUDGET, RGBAFormat, FloatType))
  return { colliders, set, lists, listData }
}

export function makeSurfaceUniforms(texture: DataTexture, occlusion: OcclusionTextures = makeOcclusionTextures()): SurfaceUniforms {
  return {
    uLights: { value: texture },
    uLightCount: { value: 0 },
    uAmbient: { value: SURFACE_AMBIENT },
    uLightGain: { value: SURFACE_LIGHT_GAIN },
    uCatchLevel: { value: 0.36 },
    uGobo: { value: getGoboTexture() },
    uGoboBlurTexels: { value: GOBO_BLUR_TEXELS },
    uLodMax: { value: FOCUS_LOD_MAX },
    uColliders: { value: occlusion.colliders },
    uLightColliders: { value: occlusion.lists },
  }
}

const PATTERN_INDEX: Record<FinishPattern, number> = { PLAIN: 0, PANELS: 1, TILES: 2, BOARDS: 3 }

const SURFACE_VERTEX_SHADER = /* glsl */ `
  varying vec3 vWorldPos;
  varying vec3 vWorldNormal;
  #ifdef USE_INSTANCING_COLOR
  varying vec3 vTint;
  #endif

  void main() {
    mat4 m = modelMatrix;
    #ifdef USE_INSTANCING
    m = m * instanceMatrix;
    #endif
    vec4 wp = m * vec4(position, 1.0);
    vWorldPos = wp.xyz;
    vWorldNormal = normalize(mat3(m) * normal);
    #ifdef USE_INSTANCING_COLOR
    vTint = instanceColor;
    #endif
    gl_Position = projectionMatrix * viewMatrix * wp;
  }
`

const SURFACE_FRAGMENT_SHADER = /* glsl */ `
  #define REACH_EPS ${REACH_EPS_M.toFixed(3)}
  #define SPREAD_REF_TAN2 ${(Math.tan(SPREAD_REF_HALF_DEG * Math.PI / 180) ** 2).toFixed(6)}
  #define SPREAD_GAIN_MAX ${SPREAD_GAIN_MAX.toFixed(1)}
  #define FALLOFF_KNEE ${FALLOFF_KNEE_M.toFixed(1)}
  #define CATCH_REFLECTANCE ${CATCH_REFLECTANCE.toFixed(4)}
  uniform sampler2D uLights;
  uniform int uLightCount;
  uniform float uAmbient;
  uniform float uLightGain;
  uniform float uCatchLevel;
  uniform sampler2DArray uGobo;
  uniform float uGoboBlurTexels;
  uniform float uLodMax;
  uniform vec3 uAlbedo;
  uniform int uPattern;
  uniform float uOpacity;
  uniform float uFill;
  ${LOBES_GLSL}

  varying vec3 vWorldPos;
  varying vec3 vWorldNormal;
  // three defines USE_INSTANCING_COLOR in the vertex stage only; the fragment stage gets USE_COLOR
  // for it (no receiver uses vertex colours, so here the two mean one thing). Testing the vertex
  // stage's name here compiled the tint out, and no instance was ever tinted.
  #if defined(USE_INSTANCING_COLOR) || defined(USE_COLOR)
  varying vec3 vTint;
  #endif

  ${BEAM_MASK_GLSL}
  ${BEAM_FOCUS_GLSL}
  ${UNPACK_EDGE_IRIS_GLSL}
  ${UNPACK_FOCUS_GLSL}
  ${LANDING_GLSL}
  ${OCCLUSION_GLSL}
  ${BEAM_FRAME_GLSL}
  ${GOBO_LAYERS_GLSL}
  ${ROLL_OFF_GLSL}

  #ifdef PLEAT
  // The part's own frame, x across the cloth and z out of it: the fold is drawn in it.
  uniform mat4 modelMatrix;
  ${PLEAT_GLSL}
  #endif

  float hash21(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

  float gridLine(vec2 q, vec2 cell, float w) {
    vec2 f = fract(q / cell);
    vec2 e = min(f, 1.0 - f) * cell;
    return smoothstep(w * 0.4, w, min(e.x, e.y));
  }

  // The finish's pattern, in world metres on the face's own plane: panels, tiles or boards.
  float finishPattern(vec3 p, vec3 n) {
    vec2 q = abs(n.y) > 0.5 ? p.xz : (abs(n.x) > 0.5 ? p.zy : p.xy);
    if (uPattern == 1) {
      vec2 c = floor(q / 0.6);
      return mix(0.5, 1.0, gridLine(q, vec2(0.6), 0.03)) * (0.9 + 0.14 * hash21(c));
    }
    if (uPattern == 2) return mix(0.62, 1.0, gridLine(q, vec2(0.6), 0.02));
    if (uPattern == 3) {
      float board = floor(q.x / 0.14);
      float f = fract(q.x / 0.14);
      float e = min(f, 1.0 - f) * 0.14;
      return mix(0.7, 1.0, smoothstep(0.003, 0.009, e)) * (0.85 + 0.2 * hash21(vec2(board, floor(q.y / 2.4 + hash21(vec2(board))))));
    }
    return 1.0;
  }

  void main() {
    vec3 n = normalize(vWorldNormal);
    #ifdef PLEAT
    vec3 pleatX = normalize(modelMatrix[0].xyz);
    vec3 pleatZ = normalize(modelMatrix[2].xyz);
    vec3 pleatO = modelMatrix[3].xyz;
    float across = dot(vWorldPos - pleatO, pleatX) + uPleat.z;
    // The fold's own normal at this point, smoother than the facets' interpolated.
    n = normalize(pleatZ - uPleat.y * pleatRate(across) * cos(pleatPhase(across)) * pleatX);
    #endif
    if (!gl_FrontFacing) n = -n;
    vec3 albedo = uAlbedo * finishPattern(vWorldPos, n);
    #if defined(USE_INSTANCING_COLOR) || defined(USE_COLOR)
    albedo *= vTint;
    #endif

    #ifdef EMISSIVE
    gl_FragColor = linearToOutputTexel(vec4(albedo, uOpacity));
    return;
    #endif

    // How wide this pixel is on the surface, in metres — taken here, in uniform control flow, since
    // derivatives inside the light loop (after its continues) are undefined. A gobo is read at the
    // blur level of this footprint where it is wider than the focus blur, so a sharp pattern far
    // away is filtered rather than aliased.
    float footprint = length(fwidth(vWorldPos));

    #if defined(LOBE_OREN_NAYAR) || defined(LOBE_SHEEN) || defined(LOBE_GGX)
    // Towards the eye: the camera's own axis for an orthographic section, where every ray is parallel.
    vec3 V = isOrthographic
      ? normalize(vec3(viewMatrix[0][2], viewMatrix[1][2], viewMatrix[2][2]))
      : normalize(cameraPosition - vWorldPos);
    float NV = max(dot(n, V), 1e-4);
    #endif

    // The diffuse light (times the albedo below) and the gloss the finish adds over it (not).
    vec3 acc = vec3(0.0);
    vec3 gloss = vec3(0.0);
    for (int i = 0; i < uLightCount; i++) {
      vec4 axis = texelFetch(uLights, ivec2(1, i), 0);
      vec4 apex = texelFetch(uLights, ivec2(0, i), 0);
      vec3 v = vWorldPos - apex.xyz;
      float dist = length(v);
      if (dist < 1e-3) continue;
      vec3 L = v / dist;
      float c = dot(L, axis.xyz);
      if (c < axis.w) continue;
      float facing = dot(n, -L);
      if (facing <= 0.0) continue;
      float shade = 1.0;
      #ifdef PLEAT
      float lampOut = dot(apex.xyz - pleatO, pleatZ);
      if (!pleatFaceSeesLamp(lampOut, gl_FrontFacing)) continue;
      shade = foldLight(across, dot(apex.xyz - pleatO, pleatX) + uPleat.z, lampOut);
      if (shade <= 0.0) continue;
      #endif
      vec4 aperture = texelFetch(uLights, ivec2(5, i), 0);
      float axial = dist * c;
      // Behind the aperture is inside the lantern: nothing there is lit by it.
      if (axial <= aperture.x) continue;
      vec4 frame = texelFetch(uLights, ivec2(4, i), 0);
      vec4 colour = texelFetch(uLights, ivec2(2, i), 0);
      // Where the point sits in the beam's cross-section, the field edge at 1 — the frame the haze
      // uses, so a soft edge and an iris shape the pool along the same line as the air. Texel 4
      // carries the frame as (cos, sin) in the axis's own basis (lightTable.ts's frameInBasis).
      vec3 bx;
      vec3 by;
      beamFrame(axis.xyz, frame.xy, bx, by);
      float radius = max(1e-4, axial * frame.w);
      vec2 g = vec2(dot(v, bx), dot(v, by)) / radius;
      vec2 uv = g;
      // A segment's rectangle, or an oval's narrow axis: the field edge at 1 on v too.
      if (aperture.z != 0.0) uv.y /= abs(aperture.z);
      vec2 edgeIris = unpackEdgeIris(colour.w);
      // Focus is a distance along the axis from the aperture — the lens — not from the apex behind
      // it; the blur is the relative error from it times the type's depth of field, both in apex.w.
      vec2 focus = unpackFocus(apex.w);
      float blur = beamFocusBlur(v, axis.xyz, aperture.x, focus.x, focus.y);
      float m = beamMask(uv, aperture.z, edgeIris.y, 1.0 - edgeIris.x, blur, aperture.yw);
      if (m <= 0.0) continue;
      // The gobos, in the haze's frame (g, before an oval's division), as the haze reads them: a
      // segment carries none. Blurred by the same blur the edge is softened by.
      if (aperture.z <= 0.0 && frame.z > 0.5) {
        vec4 rotA;
        vec4 rotB;
        goboRots(frame.z, rotA, rotB);
        m *= goboPair(g, rotA, rotB, goboLod(blur, footprint / radius, uGoboBlurTexels, uLodMax));
        if (m <= 0.0) continue;
      }
      // Shadowed: a collider between here and the lamp, or behind where the beam lands for a light
      // with too many colliders in its cone to test.
      if (lightOccluded(i, vWorldPos, -L, dist, c, aperture.x, texelFetch(uLights, ivec2(3, i), 0))) continue;
      // Falls off from the lens, not the apex behind it (nearer than 0.3 m it holds), over the
      // beam's own footprint: a narrow beam puts the same light on less of the surface.
      float da = max(dist - aperture.x, 0.3);
      float spread = clamp(SPREAD_REF_TAN2 / max(frame.w * frame.w, 1e-6), 1.0, SPREAD_GAIN_MAX);
      vec3 irradiance = colour.rgb * m * facing * shade * spread / (da * min(da, FALLOFF_KNEE) + 0.5);
      float diffuse = 1.0;
      #if defined(LOBE_SHEEN) || defined(LOBE_GGX)
      vec3 H = normalize(V - L);
      float NH = max(dot(n, H), 0.0);
      #endif
      #ifdef LOBE_OREN_NAYAR
      diffuse = orenNayar(facing, NV, dot(-L, V));
      #endif
      #ifdef LOBE_SHEEN
      gloss += irradiance * charlieSheen(facing, NV, NH);
      diffuse *= sheenKeeps(facing);
      #endif
      #ifdef LOBE_GGX
      gloss += irradiance * ggxSpecular(facing, NV, NH, max(dot(V, H), 0.0));
      diffuse *= specularKeeps(facing);
      #endif
      acc += irradiance * diffuse;
    }

    #ifdef CATCH
    // A catch surface is the light alone, added over whatever is behind it: the floor round a stage
    // that has no room modelled, where pools landed before there was anything to land on.
    gl_FragColor = linearToOutputTexel(vec4(rollOff(acc * CATCH_REFLECTANCE * uLightGain) * uCatchLevel, 1.0));
    #else
    // The material's own fill (a housing reads against the dark), from above and a little in front.
    float fill = uFill * (0.3 + 0.7 * max(dot(n, normalize(vec3(0.25, 0.9, 0.35))), 0.0));
    // How much of the room a fold's trough sees.
    float ao = 1.0;
    #ifdef PLEAT
    ao = troughAmbient(across, gl_FrontFacing);
    #endif
    vec3 lit = rollOff((albedo * (vec3((uAmbient + fill) * ao) + acc) + gloss) * uLightGain);
    // The finishes are sRGB hex, held linear by three's colour management: out through the
    // canvas's output transfer, as three's own materials are.
    gl_FragColor = linearToOutputTexel(vec4(lit, uOpacity));
    #endif
  }
`

/** Measure [material]'s fold from an edge rather than its centre (`pleat.ts`'s `pleatShift`); no rebuild. */
export function setPleatShift(material: ShaderMaterial, shift: number) {
  const pleat = material.uniforms.uPleat?.value as Vector4 | undefined
  if (pleat != null) pleat.z = shift
}

export interface SurfaceMaterialOptions {
  /** Drawn from both faces: cloth, and anything a camera can see the back of. */
  doubleSided?: boolean
  /** Below 1, blended over what is behind. */
  opacity?: number
  /** Light of the material's own, so it reads against a dark room: a housing's. Default 0. */
  fill?: number
  /** The light alone, added: a catch surface ([SURFACE_FRAGMENT_SHADER]'s `CATCH`). */
  catchOnly?: boolean
  /** Pleated cloth: the fold's normal, its shadow on itself and its troughs' ambient (`pleat.ts`). */
  pleat?: PleatShape
  /**
   * Loses every depth tie: a region under a platform that models the same deck. Without it two
   * coincident surfaces are won by whichever material three draws last, which is creation order.
   */
  behind?: boolean
}

/**
 * A receiver material for [finish], sharing [uniforms] with every other surface of its canvas. An
 * emissive finish is drawn at its colour and ignores the lights. The caller disposes it.
 */
export function makeSurfaceMaterial(
  uniforms: SurfaceUniforms,
  finish: PartFinish,
  options: SurfaceMaterialOptions = {},
): ShaderMaterial {
  const defines: Record<string, string> = {}
  if (finish.emissive) defines.EMISSIVE = ''
  if (options.catchOnly) defines.CATCH = ''
  // A catch surface is the light alone, and an emissive one no light at all: neither has lobes.
  const lobes = finish.emissive || options.catchOnly ? null : finish.lobes
  const on = lobes != null ? lobeDefines(lobes) : null
  if (on?.orenNayar) defines.LOBE_OREN_NAYAR = ''
  if (on?.sheen) defines.LOBE_SHEEN = ''
  if (on?.ggx) defines.LOBE_GGX = ''
  const albedo = new Color(finish.colour)
  const lobeValues = lobes != null ? lobeUniformValues(lobes, albedo.r, albedo.g, albedo.b) : null
  const pleat = options.pleat != null ? pleatUniformValues(options.pleat) : null
  if (pleat != null) defines.PLEAT = ''
  const opacity = options.opacity ?? 1
  const material = new ShaderMaterial({
    defines,
    uniforms: {
      ...uniforms,
      uAlbedo: { value: albedo },
      uPattern: { value: PATTERN_INDEX[finish.pattern] },
      uOpacity: { value: opacity },
      uFill: { value: options.fill ?? 0 },
      ...(lobeValues != null && {
        uOrenNayar: { value: new Vector2(...lobeValues.orenNayar) },
        uSheen: { value: new Vector4(...lobeValues.sheen) },
        uSheenStrength: { value: lobeValues.sheenStrength },
        uSpecular: { value: new Vector2(...lobeValues.specular) },
      }),
      ...(pleat != null && {
        uPleat: { value: new Vector4(...pleat.pleat) },
        uPleatWarp: { value: Array.from({ length: pleat.warp.length / 3 }, (_, i) => new Vector3(...pleat.warp.slice(i * 3, i * 3 + 3))) },
      }),
    },
    vertexShader: SURFACE_VERTEX_SHADER,
    fragmentShader: SURFACE_FRAGMENT_SHADER,
    side: options.doubleSided ? DoubleSide : FrontSide,
    transparent: options.catchOnly === true || opacity < 1,
    depthWrite: options.catchOnly !== true,
  })
  if (options.catchOnly) material.blending = AdditiveBlending
  if (options.behind) {
    material.polygonOffset = true
    material.polygonOffsetFactor = 1
    material.polygonOffsetUnits = 1
  }
  return material
}
