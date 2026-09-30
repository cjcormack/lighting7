import { AdditiveBlending, DoubleSide, ShaderMaterial, Vector2, Vector3 } from 'three'
import type { DataArrayTexture } from 'three'
import { BEAM_HARDNESS_GLSL, BEAM_MASK_GLSL } from './beamMask'
import { MAX_BEAM_REGIONS } from './emitterLayout'
import {
  EDGE_SOFT_RANGE_M,
  FOCUS_LOD_K,
  FOCUS_LOD_MAX,
  HAZE_LEVEL,
  VOLUMETRIC_STEPS,
  VOL_GAIN,
  VOL_LOD_BASE,
  VOL_SPREAD,
} from './washConfig'

/** Compile-time march bound; `uVolSteps` varies below it at runtime. */
export const MAX_VOL_STEPS = 16

/**
 * GLSL for the shared beam emitters, extracted from `StageEmitters` so the beam cross-section math
 * is one chunk wherever the gobo is sampled.
 *
 * Every beam in the air is **raymarched** (stage-view plan session 6). Until then an open beam was a
 * hollow additive cone shell and only a gobo beam marched; a shell cannot show a beam's shape
 * inside it — a soft edge, an iris, a segment's rectangle, session 7's shutter cut — so the shell
 * is gone and the volume draws them all, through `beamMask`, the function the surfaces' pools are
 * shaped by too. What that costs is the haze governor's to pay (`scene/hazeGovernor.ts`).
 *
 * The pool program that drew the floor, wall and region cookies is gone (stage-view plan
 * session 3): where a beam lands is the surface shader's (`scene/surfaceShader.ts`), which reads
 * the light table rather than being instanced per beam × receiver.
 */

// Sentinel "no wall" plane, far enough upstage that nothing reaches it.
export const NO_WALL_Z = -1e6

const REGION_UNIFORMS_GLSL = /* glsl */ `
  uniform int uNumRegions;
  uniform vec3 uRegionCenter[MAX_REGIONS];
  uniform vec3 uRegionHalf[MAX_REGIONS];
  uniform vec2 uRegionYawCs[MAX_REGIONS];
`

// `yawCs` is (cos yaw, sin yaw), the region's own turn; the ray is rotated
// into the box's frame by −yaw.
const RAY_OBB_T_GLSL = /* glsl */ `
  float rayObbT(vec3 origin, vec3 dir, vec3 center, vec3 halfExt, vec2 yawCs) {
    vec3 rel = origin - center;
    float c = yawCs.x; float s = yawCs.y;
    vec3 lo = vec3(c * rel.x - s * rel.z, rel.y, s * rel.x + c * rel.z);
    vec3 ld = vec3(c * dir.x - s * dir.z, dir.y, s * dir.x + c * dir.z);
    vec3 invD = 1.0 / ld;
    vec3 t1 = (-halfExt - lo) * invD;
    vec3 t2 = ( halfExt - lo) * invD;
    vec3 tmin = min(t1, t2);
    vec3 tmax = max(t1, t2);
    float tNear = max(max(tmin.x, tmin.y), tmin.z);
    float tFar  = min(min(tmax.x, tmax.y), tmax.z);
    if (tNear > tFar || tFar < 0.0 || tNear < 0.0) return -1.0;
    return tNear;
  }
`

// The beam's own cross-section frame: project a ray direction onto a basis
// carried with the head (aBeamRight), normalised by tan(halfAngle) so the rim
// lands at |g| = 1 regardless of zoom. Shared verbatim by every material that
// samples the gobo, so the frames cannot drift.
//
// Normalise by tan(halfAngle), not sin: dividing the perpendicular part of
// rayDir by its axial part already gives tan(offAxisAngle), which reaches
// tan(half) at the rim. Dividing by sin(half) instead would map the rim to
// 1/cos(half) — a 3% crop on a 30° spot but 41% on a 90° wash, so the pattern
// would visibly zoom as zoom widened the beam.
export const CROSS_SECTION_GLSL = /* glsl */ `
  vec2 beamCrossSection(vec3 rayDir, vec3 beamDir, vec3 beamRight, float cosAngle, float cosHalfAngle) {
    vec3 bx = normalize(beamRight - beamDir * dot(beamRight, beamDir));
    vec3 by = cross(beamDir, bx);
    float axial = max(1e-4, cosAngle);
    float sinHalf = sqrt(max(0.0, 1.0 - cosHalfAngle * cosHalfAngle));
    float tanHalf = max(1e-4, sinHalf / max(1e-4, cosHalfAngle));
    return vec2(dot(rayDir, bx), dot(rayDir, by)) / (axial * tanHalf);
  }

  vec2 goboUvCs(vec2 g, float ca, float sa) {
    return vec2(ca * g.x - sa * g.y, sa * g.x + ca * g.y) * 0.5 + 0.5;
  }

  vec2 goboUv(vec2 g, float angle) {
    return goboUvCs(g, cos(angle), sin(angle));
  }
`

// Raymarched beam volume: a **frustum** that leaves its aperture (stage-view plan session 6). The
// beam's origin is its apex, behind the aperture at the aperture's radius over tan(half-field), and
// the march runs from the aperture (axial `near`) to where the beam lands. A round aperture throws a
// cone frustum; a segment (aspect > 0) a rectangular one, whose chord is four half-spaces through
// the apex.
//
// The closed hull geometry is only a conservative fragment generator — one face per covered pixel:
// the front face, depth-tested, while the view ray starts outside the beam, so the scene in front of
// a beam hides it; the back face while it starts inside, where the front faces are behind the eye
// (the prototype's rule; a segment's hull is scaled to an ellipse round its rectangle). The true
// bounds come from an analytic ray-cone or ray-pyramid intersection per fragment. The chord is
// clamped by the axial range, the floor, the upstage wall, and camera-ray region occlusion — the
// depth test hides a beam behind something, but cannot cut one that passes through a box — then
// sampled with a per-pixel interleaved-gradient jitter so banding dissolves under bloom. Each sample
// is shaped by `beamMask`: the field edge, the iris and the softness.
const VOLUME_VERTEX_SHADER = /* glsl */ `
  attribute vec3 aBeamOrigin;
  attribute vec3 aBeamDir;
  attribute vec3 aBeamRight;
  attribute vec3 aColor;
  attribute float aOpacity;
  attribute float aCosHalfAngle;
  attribute vec4 aBeamFx;
  attribute float aShadowMask;
  attribute vec4 aBeamShape;

  varying vec3 vWorldPos;
  varying vec3 vBeamOrigin;
  varying vec3 vBeamDir;
  varying vec3 vBeamRight;
  varying vec3 vColor;
  varying float vOpacity;
  varying float vCosHalfAngle;
  varying vec4 vBeamFx;
  varying float vShadowMask;
  varying float vBeamLen;
  varying vec4 vBeamShape;

  void main() {
    vBeamShape = aBeamShape;
    // The drawn length is the instance's y scale, from the apex: the beam ends where its axis meets
    // a surface (the director's axial reach), or BEAM_LENGTH past its aperture in open air.
    vBeamLen = length(instanceMatrix[1].xyz);
    vec4 wp = modelMatrix * instanceMatrix * vec4(position, 1.0);
    vWorldPos = wp.xyz;
    vBeamOrigin = aBeamOrigin;
    vBeamDir = aBeamDir;
    vBeamRight = aBeamRight;
    vColor = aColor;
    vOpacity = aOpacity;
    vCosHalfAngle = aCosHalfAngle;
    vBeamFx = aBeamFx;
    vShadowMask = aShadowMask;
    gl_Position = projectionMatrix * viewMatrix * wp;
  }
`

const VOLUME_FRAGMENT_SHADER = /* glsl */ `
  #define MAX_REGIONS ${MAX_BEAM_REGIONS}
  #define MAX_VOL_STEPS ${MAX_VOL_STEPS}
  uniform float uFloorY;
  uniform float uWallZ;
  uniform float uHaze;
  uniform sampler2DArray uGobo;
  uniform int uVolSteps;
  uniform float uVolGain;
  uniform float uVolSpread;
  uniform float uVolLodBase;
  uniform float uLodK;
  uniform float uLodMax;
  uniform float uEdgeSoftRange;
  ${REGION_UNIFORMS_GLSL}

  varying vec3 vWorldPos;
  varying vec3 vBeamOrigin;
  varying vec3 vBeamDir;
  varying vec3 vBeamRight;
  varying vec3 vColor;
  varying float vOpacity;
  varying float vCosHalfAngle;
  varying vec4 vBeamFx;
  varying float vShadowMask;
  varying float vBeamLen;
  varying vec4 vBeamShape;

  ${RAY_OBB_T_GLSL}
  ${CROSS_SECTION_GLSL}
  ${BEAM_MASK_GLSL}
  ${BEAM_HARDNESS_GLSL}

  // Clamp the chord [t0, t1] to the half-space value(t) = base + t*rate >= 0.
  void clampHalfSpace(float base, float rate, inout float t0, inout float t1) {
    if (abs(rate) < 1e-6) {
      if (base < 0.0) { t1 = t0 - 1.0; }
      return;
    }
    float tc = -base / rate;
    if (rate > 0.0) { t0 = max(t0, tc); } else { t1 = min(t1, tc); }
  }

  void main() {
    // The pixel's view ray. A perspective camera's rays fan out from its position; an orthographic
    // section's are parallel, along the camera's forward axis, each starting on the camera plane
    // (the section plane) — so t >= 0 below is also the section's cut.
    vec3 camPos = cameraPosition;
    vec3 rayDir = normalize(vWorldPos - camPos);
    if (isOrthographic) {
      rayDir = -normalize(vec3(viewMatrix[0][2], viewMatrix[1][2], viewMatrix[2][2]));
      camPos = vWorldPos - rayDir * dot(vWorldPos - cameraPosition, rayDir);
    }
    vec3 O = vBeamOrigin;
    vec3 d = vBeamDir;

    float near = vBeamShape.x;
    float iris = vBeamShape.y;
    float aspect = vBeamShape.z;
    float cos2 = vCosHalfAngle * vCosHalfAngle;
    float vd = dot(rayDir, d);
    vec3 co = camPos - O;
    float cod = dot(co, d);
    float sinHalf = sqrt(max(0.0, 1.0 - cos2));
    float tanHalf = max(1e-4, sinHalf / max(1e-4, vCosHalfAngle));
    vec3 bx = normalize(vBeamRight - d * dot(vBeamRight, d));
    vec3 by = cross(d, bx);

    // Which face of the hull draws this pixel: its front face, depth-tested against the scene, when
    // the view ray starts outside the beam — so the stalls, a pros wall or a flat in front of a beam
    // hide it, as they hid the retired shell — and its back face when the ray starts inside it (the
    // camera standing in a beam, or a section's plane cutting one), whose front faces are behind it.
    float ty0 = tanHalf * aspect;
    bool rayStartsInside = cod >= near && cod <= vBeamLen && (aspect > 0.0
      ? abs(dot(co, bx)) <= cod * tanHalf && abs(dot(co, by)) <= cod * ty0
      : cod * cod >= cos2 * dot(co, co));
    if (gl_FrontFacing == rayStartsInside) discard;

    float tEnter = 0.0;
    float tExit = -1.0;
    if (aspect > 0.0) {
      // A segment's rectangular frustum: inside the four planes through the apex whose normals are
      // (±bx − d·tan) and (±by − d·tan·aspect) — each a half-space linear in t.
      tEnter = 0.0;
      tExit = 1e6;
      vec3 n1 = bx - d * tanHalf;
      vec3 n2 = -bx - d * tanHalf;
      vec3 n3 = by - d * ty0;
      vec3 n4 = -by - d * ty0;
      clampHalfSpace(-dot(co, n1), -dot(rayDir, n1), tEnter, tExit);
      clampHalfSpace(-dot(co, n2), -dot(rayDir, n2), tEnter, tExit);
      clampHalfSpace(-dot(co, n3), -dot(rayDir, n3), tEnter, tExit);
      clampHalfSpace(-dot(co, n4), -dot(rayDir, n4), tEnter, tExit);
    } else {
      // Analytic infinite-double-cone intersection: f(t) = axial² − cos²·|rel|²
      // is ≥ 0 inside. With A < 0 the inside is *between* the roots (ray crosses
      // the side walls); with A > 0 it's *outside* them (ray runs steeper than
      // the surface), and the two branches are the forward and mirror nappes —
      // pick whichever has positive axial distance.
      float A = vd * vd - cos2;
      float B = 2.0 * (vd * cod - cos2 * dot(rayDir, co));
      float C = cod * cod - cos2 * dot(co, co);
      // Rays parallel to the cone surface make A degenerate; nudging it keeps
      // the quadratic solvable and the error is sub-texel at the silhouette.
      if (abs(A) < 1e-7) A = A < 0.0 ? -1e-7 : 1e-7;
      float disc = B * B - 4.0 * A * C;
      if (disc < 0.0) {
        // No surface crossing: the whole ray is inside (looking down the barrel
        // from within the cone) or wholly outside.
        if (A > 0.0 && C > 0.0) { tEnter = -1e6; tExit = 1e6; }
      } else {
        float sq = sqrt(disc);
        float lo = (-B - sq) / (2.0 * A);
        float hi = (-B + sq) / (2.0 * A);
        if (lo > hi) { float tmp = lo; lo = hi; hi = tmp; }
        if (A < 0.0) {
          tEnter = lo; tExit = hi;
        } else if (cod + hi * vd > 0.0) {
          tEnter = hi; tExit = 1e6;
        } else {
          tEnter = -1e6; tExit = lo;
        }
      }
    }

    tEnter = max(tEnter, 0.0);
    // Axial range [near, vBeamLen]: from the aperture to where the beam lands. axial(t) = cod + t*vd.
    clampHalfSpace(cod - near, vd, tEnter, tExit);                // axial >= near
    clampHalfSpace(vBeamLen - cod, -vd, tEnter, tExit);           // axial <= L
    // Floor and upstage wall.
    clampHalfSpace(camPos.y - uFloorY, rayDir.y, tEnter, tExit);  // y >= floor
    clampHalfSpace(camPos.z - uWallZ, rayDir.z, tEnter, tExit);   // z >= wall

    // Cheap rejection first: a chord the clamps have already emptied must not
    // pay for the occlusion loop below.
    if (tExit <= tEnter) discard;

    // Anything solid between the camera and the beam truncates the visible
    // chord — the marched interior ignores the depth buffer, so occlusion is
    // analytic against the same region OBBs that shadow the light.
    for (int i = 0; i < MAX_REGIONS; i++) {
      if (i >= uNumRegions) break;
      float tOcc = rayObbT(camPos, rayDir, uRegionCenter[i], uRegionHalf[i], uRegionYawCs[i]);
      if (tOcc > 0.0) tExit = min(tExit, tOcc);
    }

    if (tExit <= tEnter) discard;

    // Interleaved gradient noise — per-pixel phase so undersampling reads as
    // grain (masked by bloom) instead of rings.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));

    // The cross-section frame (bx, by, tanHalf, above) is constant per fragment — hoisted out of
    // the march (WebKit's compiler is not trusted to do it). Same construction as the surface
    // shader's, so the in-air shape and the pool are the same image at every distance.
    // The gobo rotation is per-fragment constant too — cos/sin hoisted with
    // the frame; goboUvCs keeps the rotate itself shared.
    float goboCs = cos(vBeamFx.z);
    float goboSn = sin(vBeamFx.z);

    int lightMask = int(vShadowMask + 0.5);
    float focusDist = vBeamFx.w;
    float sum = 0.0;
    for (int i = 0; i < MAX_VOL_STEPS; i++) {
      if (i >= uVolSteps) break;
      float t = mix(tEnter, tExit, (float(i) + jitter) / float(uVolSteps));
      vec3 p = camPos + rayDir * t;
      vec3 rel = p - O;
      float relLen = max(length(rel), 1e-4);
      vec3 lightDir = rel / relLen;

      float cosAngle = dot(lightDir, d);
      vec2 g = vec2(dot(lightDir, bx), dot(lightDir, by)) / (max(1e-4, cosAngle) * tanHalf);
      // Focus is a distance from the aperture, not from the apex behind it.
      float defocus = focusDist < 0.0 ? 0.0 : abs(relLen - near - focusDist);
      float effEdge = beamHardness(vBeamFx.x, focusDist, defocus, uEdgeSoftRange);
      vec2 mg = aspect > 0.0 ? vec2(g.x, g.y / aspect) : g;
      float radial = beamMask(mg, aspect, iris, 1.0 - effEdge);

      float gobo = 1.0;
      if (vBeamFx.y >= 0.5 && aspect <= 0.0) {
        vec2 guv = goboUvCs(g, goboCs, goboSn);
        float lod = clamp(uVolLodBase + uLodK * defocus, 0.0, uLodMax);
        gobo = textureLod(uGobo, vec3(guv, vBeamFx.y), lod).r;
      }

      // Light-ray shadow: only regions the CPU cull flagged can block.
      float lit = 1.0;
      for (int r = 0; r < MAX_REGIONS; r++) {
        if (r >= uNumRegions) break;
        if ((lightMask & (1 << r)) == 0) continue;
        float tb = rayObbT(O, lightDir, uRegionCenter[r], uRegionHalf[r], uRegionYawCs[r]);
        // From the apex; a box between it and the aperture is the lantern's own inside.
        if (tb > near && tb < relLen - 0.01) { lit = 0.0; break; }
      }

      // The air thins as the beam spreads: the same light over a wider cross-section. Without it
      // a wide wash is as dense per metre as a spot, and looking down one is a white wall.
      float spread = 1.0 / (1.0 + uVolSpread * max(0.0, relLen * cosAngle) * tanHalf);
      sum += gobo * radial * lit * spread;
    }

    // The beam's own opacity is the ceiling — what the retired shell drew it at — and the chord
    // how near it gets: a thin edge is faint, a beam seen side-on is at its opacity within half a
    // metre, and a long chord (the camera looking down the barrel) never adds up past it.
    float alpha = uHaze * vOpacity * (1.0 - exp(-uVolGain * sum * (tExit - tEnter) / float(uVolSteps)));
    if (alpha <= 0.0005) discard;
    gl_FragColor = vec4(vColor, alpha);
  }
`

export function makeVolumeMaterial(gobo: DataArrayTexture): ShaderMaterial {
  return new ShaderMaterial({
    uniforms: {
      uFloorY: { value: 0.0 },
      uWallZ: { value: NO_WALL_Z },
      uHaze: { value: HAZE_LEVEL },
      uGobo: { value: gobo },
      uVolSteps: { value: VOLUMETRIC_STEPS },
      uVolGain: { value: VOL_GAIN },
      uVolSpread: { value: VOL_SPREAD },
      uVolLodBase: { value: VOL_LOD_BASE },
      uLodK: { value: FOCUS_LOD_K },
      uLodMax: { value: FOCUS_LOD_MAX },
      uEdgeSoftRange: { value: EDGE_SOFT_RANGE_M },
      ...makeRegionUniforms(),
    },
    vertexShader: VOLUME_VERTEX_SHADER,
    fragmentShader: VOLUME_FRAGMENT_SHADER,
    transparent: true,
    blending: AdditiveBlending,
    depthWrite: false,
    side: DoubleSide,
  })
}

export function makeRegionUniforms() {
  return {
    uNumRegions: { value: 0 },
    uRegionCenter: {
      value: Array.from({ length: MAX_BEAM_REGIONS }, () => new Vector3()),
    },
    uRegionHalf: {
      value: Array.from({ length: MAX_BEAM_REGIONS }, () => new Vector3()),
    },
    uRegionYawCs: {
      value: Array.from({ length: MAX_BEAM_REGIONS }, () => new Vector2(1, 0)),
    },
  }
}
