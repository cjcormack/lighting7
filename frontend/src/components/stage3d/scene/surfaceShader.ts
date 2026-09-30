import {
  AdditiveBlending,
  Color,
  DataTexture,
  DoubleSide,
  FloatType,
  FrontSide,
  NearestFilter,
  RGBAFormat,
  ShaderMaterial,
} from 'three'
import { BEAM_HARDNESS_GLSL, BEAM_MASK_GLSL } from '../beamMask'
import { EDGE_SOFT_RANGE_M } from '../washConfig'
import { LIGHT_TEXELS, MAX_LIGHT_BUDGET, UNPACK_EDGE_IRIS_GLSL } from './lightTable'
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
 * plan session 6), facing the light, and not behind the plane of the first surface on the beam's axis
 * (the axial reach, which stands in for occlusion) — then the beam's cross-section, `beamMask`
 * (`../beamMask.ts`), the one the haze is shaped by: its field circle or a segment's rectangle, its
 * iris, and an edge softened by the family and by how far the surface sits from the focal plane,
 * which is measured from the aperture. **No falloff with distance**, for `washConfig.ts`'s reason: the
 * desk draws a stylised, consistent beam, and a pool that dimmed with throw would disagree with the
 * uniform cone above it. Gobos land in the air but not on surfaces until the quality tier
 * (`FU-STAGE-QUALITY-TIER`).
 *
 * The colour is the finish lit by the lights plus a little of the light itself (`uSheen`), so a
 * pool still reads as the beam's colour on the near-black finishes a hall is painted in.
 */

/** How far behind the reach plane a fragment may sit and still be lit: the hit surface's own skin. */
const REACH_EPS_M = 0.03

/** The shared uniforms of one canvas's surfaces: the light table, how many rows are live, the room's fill. */
export interface SurfaceUniforms {
  uLights: { value: DataTexture }
  uLightCount: { value: number }
  uAmbient: { value: number }
  uLightGain: { value: number }
  uSheen: { value: number }
  uEdgeSoftRange: { value: number }
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
    uAmbient: { value: 0.16 },
    uLightGain: { value: 1.6 },
    uSheen: { value: 0.18 },
    uEdgeSoftRange: { value: EDGE_SOFT_RANGE_M },
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
  uniform sampler2D uLights;
  uniform int uLightCount;
  uniform float uAmbient;
  uniform float uLightGain;
  uniform float uSheen;
  uniform float uEdgeSoftRange;
  uniform vec3 uAlbedo;
  uniform int uPattern;
  uniform float uOpacity;

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
      vec4 reach = texelFetch(uLights, ivec2(3, i), 0);
      if (dot(reach.xyz, vWorldPos) - reach.w < -REACH_EPS) continue;
      vec4 aperture = texelFetch(uLights, ivec2(5, i), 0);
      float axial = dist * c;
      // Behind the aperture is inside the lantern: nothing there is lit by it.
      if (axial <= aperture.x) continue;
      vec4 frame = texelFetch(uLights, ivec2(4, i), 0);
      vec4 colour = texelFetch(uLights, ivec2(2, i), 0);
      // Where the point sits in the beam's cross-section, the field edge at 1 — the frame the haze
      // uses, so a soft edge and an iris shape the pool along the same line as the air.
      vec3 bx = normalize(frame.xyz - axis.xyz * dot(frame.xyz, axis.xyz));
      vec3 by = cross(axis.xyz, bx);
      vec2 uv = vec2(dot(v, bx), dot(v, by)) / max(1e-4, axial * frame.w);
      // A segment's rectangle, or an oval's narrow axis: the field edge at 1 on v too.
      if (aperture.z != 0.0) uv.y /= abs(aperture.z);
      vec2 edgeIris = unpackEdgeIris(colour.w);
      // Focus is a distance from the aperture — the lens — not from the apex behind it.
      float hard = beamHardness(edgeIris.x, apex.w, abs(dist - aperture.x - apex.w), uEdgeSoftRange);
      float m = beamMask(uv, aperture.z, edgeIris.y, 1.0 - hard, aperture.yw);
      if (m <= 0.0) continue;
      acc += colour.rgb * m * (0.3 + 0.7 * facing);
    }

    #ifdef CATCH
    // A catch surface is the light alone, added over whatever is behind it: the floor round a stage
    // that has no room modelled, where pools landed before there was anything to land on.
    gl_FragColor = linearToOutputTexel(vec4((1.0 - exp(-acc * uLightGain)) * uSheen * 2.0, 1.0));
    #else
    float fill = uAmbient * (0.75 + 0.25 * n.y);
    // Many lights overlapping would add past white; roll the light off instead, as a stop of film
    // would, so a wall under the whole rig is bright rather than a flat blown-out plate.
    vec3 light = 1.0 - exp(-acc * uLightGain);
    vec3 lit = albedo * (vec3(fill) + light * 2.0) + light * uSheen;
    // The finishes are sRGB hex, held linear by three's colour management: out through the
    // canvas's output transfer, as three's own materials are.
    gl_FragColor = linearToOutputTexel(vec4(lit, uOpacity));
    #endif
  }
`

export interface SurfaceMaterialOptions {
  /** Drawn from both faces: cloth, and anything a camera can see the back of. */
  doubleSided?: boolean
  /** Below 1, blended over what is behind — a selected region's highlight. */
  opacity?: number
  /** The light alone, added: a catch surface ([SURFACE_FRAGMENT_SHADER]'s `CATCH`). */
  catchOnly?: boolean
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
    },
    vertexShader: SURFACE_VERTEX_SHADER,
    fragmentShader: SURFACE_FRAGMENT_SHADER,
    side: options.doubleSided ? DoubleSide : FrontSide,
    transparent: options.catchOnly === true || opacity < 1,
    depthWrite: options.catchOnly !== true,
  })
  if (options.catchOnly) material.blending = AdditiveBlending
  return material
}
