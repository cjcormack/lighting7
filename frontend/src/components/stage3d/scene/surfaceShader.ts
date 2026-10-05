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
} from 'three'
import { BEAM_HARDNESS_GLSL, BEAM_MASK_GLSL } from '../beamMask'
import { getGoboTexture } from '../goboAtlas'
import { GOBO_LAYERS_GLSL } from '../goboLayers'
import { FOCUS_LOD_MAX, FOCUS_SOFT_BLUR, GOBO_BLUR_TEXELS } from '../washConfig'
import { LANDING_GLSL } from './landing'
import { BEAM_FRAME_GLSL, LIGHT_TEXELS, MAX_LIGHT_BUDGET, UNPACK_EDGE_IRIS_GLSL, UNPACK_FOCUS_GLSL } from './lightTable'
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
 * plan session 6), facing the light, and not behind where the beam lands (`landing.ts`: the plane of
 * the first surface on its axis, and of the second face a beam split across an edge lands on, which
 * stand in for occlusion) — then the beam's cross-section, `beamMask`
 * (`../beamMask.ts`), the one the haze is shaped by: its field circle or a segment's rectangle, its
 * iris, and an edge softened by the family and by how far the surface sits from the focal plane,
 * which is measured from the aperture. A pool **falls off with distance from the aperture** — as
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
 * pool — at the mip level of the focus blur the edge is softened by, or of the pixel's footprint
 * where that is wider. The haze samples it through the same function, so a gobo sharp in the air at
 * a distance is sharp on a wall at that distance. What still does not land is a **shadow**: the
 * axial beam reach stands in for occlusion until the quality tier's shadow maps
 * (`FU-STAGE-QUALITY-TIER`).
 *
 * The colour is the prototype's (`docs/plans/stage-view-design/prototype.html`): every light, the
 * room's ambient and the material's own fill summed, times the finish, rolled off once by
 * `1 − exp(−·)` and encoded — so two pools overlapping add as the eye sees them, and a wall under the
 * whole rig is bright rather than a flat blown-out plate. The light reflects off no less than
 * `uReflectFloor` of itself, so a pool still reads on the near-black finishes a hall is painted in —
 * black serge shows a spot — while the ambient and fill keep the finish's own darkness.
 */

/** The room's light on every surface, and the gain the summed light is rolled off at. */
export const SURFACE_AMBIENT = 0.012
export const SURFACE_LIGHT_GAIN = 1.1

/**
 * A finish as the surface shader draws it lit by its ambient and fill alone, with [facing] the
 * normal's dot with the fill's direction — the billboard's stand-in for an unlit housing.
 */
export function litByFill(albedo: Color, fill: number, facing = 0.5): Color {
  const k = (SURFACE_AMBIENT + fill * (0.3 + 0.7 * Math.max(facing, 0))) * SURFACE_LIGHT_GAIN
  return new Color(1 - Math.exp(-albedo.r * k), 1 - Math.exp(-albedo.g * k), 1 - Math.exp(-albedo.b * k))
}

/** How far behind a landing plane a fragment may sit and still be lit: the hit surface's own skin. */
const REACH_EPS_M = 0.03

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
  uReflectFloor: { value: number }
  uCatchLevel: { value: number }
  uFocusSoftBlur: { value: number }
  uGobo: { value: DataArrayTexture }
  uGoboBlurTexels: { value: number }
  uLodMax: { value: number }
}

/** A canvas's light texture: [MAX_LIGHT_BUDGET] rows of [LIGHT_TEXELS] RGBA float texels. */
export function makeLightTexture(): { texture: DataTexture; data: Float32Array } {
  const data = new Float32Array(MAX_LIGHT_BUDGET * LIGHT_TEXELS * 4)
  const texture = new DataTexture(data, LIGHT_TEXELS, MAX_LIGHT_BUDGET, RGBAFormat, FloatType)
  // Read with texelFetch — never filtered, never mipmapped (float linear filtering is an extension).
  texture.magFilter = NearestFilter
  texture.minFilter = NearestFilter
  texture.generateMipmaps = false
  texture.needsUpdate = true
  return { texture, data }
}

export function makeSurfaceUniforms(texture: DataTexture): SurfaceUniforms {
  return {
    uLights: { value: texture },
    uLightCount: { value: 0 },
    uAmbient: { value: SURFACE_AMBIENT },
    uLightGain: { value: SURFACE_LIGHT_GAIN },
    uReflectFloor: { value: 0.1 },
    uCatchLevel: { value: 0.36 },
    uFocusSoftBlur: { value: FOCUS_SOFT_BLUR },
    uGobo: { value: getGoboTexture() },
    uGoboBlurTexels: { value: GOBO_BLUR_TEXELS },
    uLodMax: { value: FOCUS_LOD_MAX },
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
  uniform sampler2D uLights;
  uniform int uLightCount;
  uniform float uAmbient;
  uniform float uLightGain;
  uniform float uReflectFloor;
  uniform float uCatchLevel;
  uniform float uFocusSoftBlur;
  uniform sampler2DArray uGobo;
  uniform float uGoboBlurTexels;
  uniform float uLodMax;
  uniform vec3 uAlbedo;
  uniform int uPattern;
  uniform float uOpacity;
  uniform float uFill;

  varying vec3 vWorldPos;
  varying vec3 vWorldNormal;
  // three defines USE_INSTANCING_COLOR in the vertex stage only; the fragment stage gets USE_COLOR
  // for it (no receiver uses vertex colours, so here the two mean one thing). Testing the vertex
  // stage's name here compiled the tint out, and no instance was ever tinted.
  #if defined(USE_INSTANCING_COLOR) || defined(USE_COLOR)
  varying vec3 vTint;
  #endif

  ${BEAM_MASK_GLSL}
  ${BEAM_HARDNESS_GLSL}
  ${UNPACK_EDGE_IRIS_GLSL}
  ${UNPACK_FOCUS_GLSL}
  ${LANDING_GLSL}
  ${BEAM_FRAME_GLSL}
  ${GOBO_LAYERS_GLSL}

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
    // mip level of this footprint where it is wider than the focus blur, so a sharp pattern far
    // away is filtered rather than aliased.
    float footprint = length(fwidth(vWorldPos));

    vec3 acc = vec3(0.0);
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
      if (behindLanding(texelFetch(uLights, ivec2(3, i), 0), vWorldPos, REACH_EPS)) continue;
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
      // Focus is a distance from the aperture — the lens — not from the apex behind it; the blur is
      // the relative error from it times the type's depth of field, both in apex.w.
      vec2 focus = unpackFocus(apex.w);
      float blur = focusBlur(dist - aperture.x, focus.x, focus.y);
      float hard = beamHardness(edgeIris.x, focus.x, blur, uFocusSoftBlur);
      float m = beamMask(uv, aperture.z, edgeIris.y, 1.0 - hard, aperture.yw);
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
      // Falls off from the lens, not the apex behind it (nearer than 0.3 m it holds), over the
      // beam's own footprint: a narrow beam puts the same light on less of the surface.
      float da = max(dist - aperture.x, 0.3);
      float spread = clamp(SPREAD_REF_TAN2 / max(frame.w * frame.w, 1e-6), 1.0, SPREAD_GAIN_MAX);
      acc += colour.rgb * m * facing * spread / (da * min(da, FALLOFF_KNEE) + 0.5);
    }

    #ifdef CATCH
    // A catch surface is the light alone, added over whatever is behind it: the floor round a stage
    // that has no room modelled, where pools landed before there was anything to land on.
    gl_FragColor = linearToOutputTexel(vec4((1.0 - exp(-acc * uLightGain)) * uCatchLevel, 1.0));
    #else
    // The material's own fill (a housing reads against the dark), from above and a little in front.
    float fill = uFill * (0.3 + 0.7 * max(dot(n, normalize(vec3(0.25, 0.9, 0.35))), 0.0));
    vec3 lit = 1.0 - exp(-(albedo * vec3(uAmbient + fill) + max(albedo, vec3(uReflectFloor)) * acc) * uLightGain);
    // The finishes are sRGB hex, held linear by three's colour management: out through the
    // canvas's output transfer, as three's own materials are.
    gl_FragColor = linearToOutputTexel(vec4(lit, uOpacity));
    #endif
  }
`

export interface SurfaceMaterialOptions {
  /** Drawn from both faces: cloth, and anything a camera can see the back of. */
  doubleSided?: boolean
  /** Below 1, blended over what is behind. */
  opacity?: number
  /** Light of the material's own, so it reads against a dark room: a housing's. Default 0. */
  fill?: number
  /** The light alone, added: a catch surface ([SURFACE_FRAGMENT_SHADER]'s `CATCH`). */
  catchOnly?: boolean
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
  const opacity = options.opacity ?? 1
  const material = new ShaderMaterial({
    defines,
    uniforms: {
      ...uniforms,
      uAlbedo: { value: new Color(finish.colour) },
      uPattern: { value: PATTERN_INDEX[finish.pattern] },
      uOpacity: { value: opacity },
      uFill: { value: options.fill ?? 0 },
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
