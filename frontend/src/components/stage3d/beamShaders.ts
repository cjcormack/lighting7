import { AdditiveBlending, BackSide, DoubleSide, ShaderMaterial, Vector2, Vector3 } from 'three'
import type { DataArrayTexture } from 'three'
import { BEAM_LENGTH, MAX_BEAM_REGIONS } from './emitterLayout'
import {
  EDGE_SOFT_RANGE_M,
  FOCUS_LOD_K,
  FOCUS_LOD_MAX,
  HAZE_LEVEL,
  VOLUMETRIC_STEPS,
  VOL_GAIN,
  VOL_LOD_BASE,
} from './washConfig'

/** Compile-time march bound; `uVolSteps` varies below it at runtime. */
export const MAX_VOL_STEPS = 16

/**
 * GLSL programs for the shared beam emitters, extracted from `StageEmitters`
 * so the cone, pool and (later) volumetric materials assemble from the same
 * chunks — in particular the beam cross-section math, which must be identical
 * everywhere the gobo is sampled or the mid-air pattern and the surface
 * pattern drift apart.
 */

// Sentinel "no wall" plane, far enough upstage that nothing reaches it.
export const NO_WALL_Z = -1e6

const REGION_UNIFORMS_GLSL = /* glsl */ `
  uniform int uNumRegions;
  uniform vec3 uRegionCenter[MAX_REGIONS];
  uniform vec3 uRegionHalf[MAX_REGIONS];
  uniform vec2 uRegionYawCs[MAX_REGIONS];
`

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
// samples the gobo — pool now, volumetric later — so the frames cannot drift.
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

// Hollow cone shell, additive, double-sided; alpha biased by abs(N·V) so
// silhouette edges fade. Per-fragment ray-OBB shadow discards fragments
// blocked by regions, which carves the same shape the floor pool projects.
// Per-fixture origin/color/opacity arrive via instance attributes.
const CONE_VERTEX_SHADER = /* glsl */ `
  attribute vec3 aBeamOrigin;
  attribute vec3 aColor;
  attribute float aOpacity;
  attribute vec4 aBeamFx;

  varying vec3 vViewNormal;
  varying vec3 vViewPos;
  varying vec3 vWorldPos;
  varying vec3 vColor;
  varying float vOpacity;
  varying vec3 vBeamOrigin;
  varying float vEdge;
  varying float vFocusDist;

  void main() {
    vEdge = aBeamFx.x;
    vFocusDist = aBeamFx.w;

    vec4 worldPos4 = modelMatrix * instanceMatrix * vec4(position, 1.0);
    vWorldPos = worldPos4.xyz;
    vec4 viewPos4 = viewMatrix * worldPos4;
    vViewPos = viewPos4.xyz;

    // Normal transform that tolerates non-uniform scale on the instance
    // matrix (cone scale is (R, L, R) where L >> R for narrow beams).
    mat3 m = mat3(instanceMatrix);
    vec3 transformedNormal = normal / vec3(
      dot(m[0], m[0]),
      dot(m[1], m[1]),
      dot(m[2], m[2])
    );
    transformedNormal = m * transformedNormal;
    vViewNormal = normalize(normalMatrix * transformedNormal);

    vColor = aColor;
    vOpacity = aOpacity;
    vBeamOrigin = aBeamOrigin;

    gl_Position = projectionMatrix * viewPos4;
  }
`

const CONE_FRAGMENT_SHADER = /* glsl */ `
  #define MAX_REGIONS ${MAX_BEAM_REGIONS}
  uniform float uFloorY;
  uniform float uWallZ;
  uniform float uHaze;
  uniform float uEdgeSoftRange;
  ${REGION_UNIFORMS_GLSL}

  varying vec3 vViewNormal;
  varying vec3 vViewPos;
  varying vec3 vWorldPos;
  varying vec3 vColor;
  varying float vOpacity;
  varying vec3 vBeamOrigin;
  varying float vEdge;
  varying float vFocusDist;

  ${RAY_OBB_T_GLSL}

  void main() {
    if (vWorldPos.y < uFloorY) discard;
    // The upstage wall is a real surface now; the beam stops at it instead of
    // punching through the back of the venue.
    if (vWorldPos.z < uWallZ) discard;

    vec3 toFrag = vWorldPos - vBeamOrigin;
    float fragDist = length(toFrag);

    if (uNumRegions > 0 && fragDist > 0.0001) {
      vec3 rayDir = toFrag / fragDist;
      for (int i = 0; i < MAX_REGIONS; i++) {
        if (i >= uNumRegions) break;
        float t = rayObbT(vBeamOrigin, rayDir, uRegionCenter[i], uRegionHalf[i], uRegionYawCs[i]);
        if (t > 0.0 && t < fragDist - 0.01) discard;
      }
    }

    vec3 V = normalize(-vViewPos);
    float ndotv = abs(dot(normalize(vViewNormal), V));
    // Same edge rule as the pools: with a focal distance the shell sharpens
    // where the fragment sits near the focal plane, so a tightly-focused beam
    // doesn't read as a hard floor spot inside a woolly column of air. The
    // sentinel (< 0) keeps the raw channel hardness, so a fixture with no
    // focus channel (or an older backend) renders unchanged.
    float effEdge = vFocusDist < 0.0
      ? vEdge
      : 1.0 - smoothstep(0.0, uEdgeSoftRange, abs(fragDist - vFocusDist));
    float radial = pow(ndotv, mix(0.7, 1.15, effEdge));
    // uHaze scales the mid-air beam volume (atmosphere); 0.0 leaves only the
    // surface pools (a hazeless room). Brightness is deliberately uniform
    // along the throw — a stylised consistent cone, not a physical falloff
    // (see the axial-profile note in washConfig).
    float a = vOpacity * radial * uHaze;
    gl_FragColor = vec4(vColor, a);
  }
`

// Cookie geometry projected onto a surface (floor quad, region box, wall
// quad). Used by every pool InstancedMesh — same shader, only the baked
// per-instance geometry differs (placement is in the instance matrix).
// `aVisible < 0.5` lets a mesh hide individual cookies without rewriting
// their matrices each frame.
const POOL_VERTEX_SHADER = /* glsl */ `
  attribute vec3 aBeamOrigin;
  attribute vec3 aBeamDir;
  attribute vec3 aColor;
  attribute float aOpacity;
  attribute float aCosHalfAngle;
  attribute float aVisible;
  #ifdef USE_GOBO
  attribute vec4 aBeamFx;
  attribute vec3 aBeamRight;
  attribute float aShadowMask;
  #endif

  varying vec3 vWorldPos;
  varying vec3 vBeamOrigin;
  varying vec3 vBeamDir;
  varying vec3 vColor;
  varying float vOpacity;
  varying float vCosHalfAngle;
  #ifdef USE_GOBO
  varying vec4 vBeamFx;
  varying vec3 vBeamRight;
  varying float vShadowMask;
  #endif

  void main() {
    if (aVisible < 0.5) {
      // Send out of clip volume; rasterizer rejects all fragments.
      gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
      return;
    }

    vec4 wp = modelMatrix * instanceMatrix * vec4(position, 1.0);
    vWorldPos = wp.xyz;

    vBeamOrigin = aBeamOrigin;
    vBeamDir = aBeamDir;
    vColor = aColor;
    vOpacity = aOpacity;
    vCosHalfAngle = aCosHalfAngle;
    #ifdef USE_GOBO
    vBeamFx = aBeamFx;
    vBeamRight = aBeamRight;
    vShadowMask = aShadowMask;
    #endif

    gl_Position = projectionMatrix * viewMatrix * wp;
  }
`

const POOL_FRAGMENT_SHADER = /* glsl */ `
  #define MAX_REGIONS ${MAX_BEAM_REGIONS}
  #define MAX_DIST ${BEAM_LENGTH.toFixed(1)}
  uniform float uCoreBoost;
  uniform float uWallZ;
  ${REGION_UNIFORMS_GLSL}

  varying vec3 vWorldPos;
  varying vec3 vBeamOrigin;
  varying vec3 vBeamDir;
  varying vec3 vColor;
  varying float vOpacity;
  varying float vCosHalfAngle;
  #ifdef USE_GOBO
  uniform sampler2DArray uGobo;
  uniform float uLodK;
  uniform float uLodMax;
  uniform float uEdgeSoftRange;
  varying vec4 vBeamFx;
  varying vec3 vBeamRight;
  varying float vShadowMask;
  #endif

  ${RAY_OBB_T_GLSL}
  ${CROSS_SECTION_GLSL}

  void main() {
    // No receiving surface exists beyond the upstage wall; light that "would"
    // land there lands on the wall cookie instead.
    if (vWorldPos.z < uWallZ - 0.005) discard;

    vec3 toFrag = vWorldPos - vBeamOrigin;
    float fragDist = length(toFrag);
    if (fragDist > MAX_DIST || fragDist < 0.001) discard;
    vec3 rayDir = toFrag / fragDist;

    float cosAngle = dot(rayDir, vBeamDir);
    if (cosAngle < vCosHalfAngle) discard;

    if (uNumRegions > 0) {
      #ifdef USE_GOBO
      // The CPU cull writes a bitmask of regions this beam can even touch, so
      // the common case tests 0-2 OBBs here instead of all 16. The fragment's
      // own region (a cookie on a region face) shadows itself for the far
      // faces; the relative epsilon keeps grazing hits on the near face from
      // false-self-shadowing.
      int mask = int(vShadowMask + 0.5);
      #endif
      for (int i = 0; i < MAX_REGIONS; i++) {
        if (i >= uNumRegions) break;
        #ifdef USE_GOBO
        if ((mask & (1 << i)) == 0) continue;
        #endif
        float t = rayObbT(vBeamOrigin, rayDir, uRegionCenter[i], uRegionHalf[i], uRegionYawCs[i]);
        if (t > 0.0 && t < fragDist * 0.995 - 0.01) discard;
      }
    }

    float t = (cosAngle - vCosHalfAngle) / max(0.0001, 1.0 - vCosHalfAngle);
    // Edge hardness. With a focal distance (vBeamFx.w ≥ 0) the rim and the
    // gobo sharpen *together* exactly when this surface sits at the focal
    // plane — the physical behaviour of racking focus. The sentinel (< 0)
    // keeps the legacy channel-value hardness in .x, so a fixture with no
    // focus channel is pixel-identical to before.
    #ifdef USE_GOBO
    float defocus = vBeamFx.w < 0.0 ? 0.0 : abs(fragDist - vBeamFx.w);
    float effEdge = vBeamFx.w < 0.0
      ? vBeamFx.x
      : 1.0 - smoothstep(0.0, uEdgeSoftRange, defocus);
    float radial = mix(pow(t, 0.7), smoothstep(0.0, 0.12, t), effEdge);
    #else
    float radial = pow(t, 0.7);
    #endif

    #ifdef USE_GOBO
    if (vBeamFx.y >= 0.5) {
      // Sample the gobo in the beam's own cross-section frame rather than the
      // cookie's UV: cookie geometry is world-axis-aligned and sized to the
      // slacked cull bound, so its UV neither rotates with pan nor keeps a
      // stable scale. Projecting rayDir onto a basis carried with the head
      // gives a frame that pans, tilts and keystones on an oblique hit for
      // free. aBeamRight comes from the head's matrix — deriving a basis from
      // the direction alone is unstable when the beam points near straight
      // down, which is most of the time.
      vec2 g = beamCrossSection(rayDir, vBeamDir, vBeamRight, cosAngle, vCosHalfAngle);
      vec2 guv = goboUv(g, vBeamFx.z);
      // Defocus blur is a mip LOD bias — the atlas is mipmapped, so a surface
      // away from the focal plane samples a blurrier level while grazing-angle
      // auto-minification still applies underneath.
      radial *= texture(uGobo, vec3(guv, vBeamFx.y), min(uLodK * defocus, uLodMax)).r;
    }
    #endif

    // Core white-hot boost for a beam's hotspot. Disabled (uCoreBoost=0) for
    // wash pools so overlapping per-pixel colours stay coloured, not white.
    float core = pow(radial, 4.0);
    vec3 finalColor = mix(vColor, vec3(1.0), core * uCoreBoost);

    // Pool strength is deliberately independent of throw distance, matching
    // the uniform mid-air cone.
    float a = vOpacity * radial;
    gl_FragColor = vec4(finalColor, a);
  }
`

// Raymarched beam volume, used instead of the shell when a gobo is in the
// beam: the pattern must exist *inside* the cone (a dot gobo = separate
// beamlets through haze), which a silhouette-faded surface cannot show.
//
// The closed cone geometry is only a conservative fragment generator — back
// faces alone are rasterized (one fragment per covered pixel, camera-inside
// safe) and the true bounds come from an analytic ray-cone intersection per
// fragment. The chord is clamped by the axial range, the floor, the upstage
// wall, and camera-ray region occlusion (depthTest is off; the depth buffer
// can't clip a marched interior correctly), then sampled with a per-pixel
// interleaved-gradient jitter so banding dissolves under bloom.
const VOLUME_VERTEX_SHADER = /* glsl */ `
  attribute vec3 aBeamOrigin;
  attribute vec3 aBeamDir;
  attribute vec3 aBeamRight;
  attribute vec3 aColor;
  attribute float aOpacity;
  attribute float aCosHalfAngle;
  attribute vec4 aBeamFx;
  attribute float aShadowMask;

  varying vec3 vWorldPos;
  varying vec3 vBeamOrigin;
  varying vec3 vBeamDir;
  varying vec3 vBeamRight;
  varying vec3 vColor;
  varying float vOpacity;
  varying float vCosHalfAngle;
  varying vec4 vBeamFx;
  varying float vShadowMask;

  void main() {
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
  #define BEAM_LEN ${BEAM_LENGTH.toFixed(1)}
  uniform float uFloorY;
  uniform float uWallZ;
  uniform float uHaze;
  uniform sampler2DArray uGobo;
  uniform int uVolSteps;
  uniform float uVolGain;
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

  ${RAY_OBB_T_GLSL}
  ${CROSS_SECTION_GLSL}

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
    vec3 camPos = cameraPosition;
    vec3 rayDir = normalize(vWorldPos - camPos);
    vec3 O = vBeamOrigin;
    vec3 d = vBeamDir;

    // Analytic infinite-double-cone intersection: f(t) = axial² − cos²·|rel|²
    // is ≥ 0 inside. With A < 0 the inside is *between* the roots (ray crosses
    // the side walls); with A > 0 it's *outside* them (ray runs steeper than
    // the surface), and the two branches are the forward and mirror nappes —
    // pick whichever has positive axial distance.
    vec3 co = camPos - O;
    float cos2 = vCosHalfAngle * vCosHalfAngle;
    float vd = dot(rayDir, d);
    float cod = dot(co, d);
    float A = vd * vd - cos2;
    float B = 2.0 * (vd * cod - cos2 * dot(rayDir, co));
    float C = cod * cod - cos2 * dot(co, co);
    // Rays parallel to the cone surface make A degenerate; nudging it keeps
    // the quadratic solvable and the error is sub-texel at the silhouette.
    if (abs(A) < 1e-7) A = A < 0.0 ? -1e-7 : 1e-7;
    float disc = B * B - 4.0 * A * C;
    // Degenerate default: empty chord (the final tExit <= tEnter test culls).
    float tEnter = 0.0;
    float tExit = -1.0;
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

    tEnter = max(tEnter, 0.0);
    // Axial range [0, BEAM_LEN]: axial(t) = cod + t*vd.
    clampHalfSpace(cod, vd, tEnter, tExit);                       // axial >= 0
    clampHalfSpace(BEAM_LEN - cod, -vd, tEnter, tExit);           // axial <= L
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

    // The cross-section frame is constant per fragment — hoisted out of the
    // march (WebKit's compiler is not trusted to do it). Same construction as
    // beamCrossSection, so the in-air pattern and the surface pattern are the
    // same image at every distance.
    vec3 bx = normalize(vBeamRight - d * dot(vBeamRight, d));
    vec3 by = cross(d, bx);
    float sinHalf = sqrt(max(0.0, 1.0 - cos2));
    float tanHalf = max(1e-4, sinHalf / max(1e-4, vCosHalfAngle));
    // The gobo rotation is per-fragment constant too — cos/sin hoisted with
    // the frame; goboUvCs keeps the rotate itself shared with the pool path.
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
      float rr = length(g);
      float tEquiv = clamp(1.0 - rr, 0.0, 1.0);
      float defocus = focusDist < 0.0 ? 0.0 : abs(relLen - focusDist);
      float effEdge = focusDist < 0.0
        ? vBeamFx.x
        : 1.0 - smoothstep(0.0, uEdgeSoftRange, defocus);
      float radial = mix(pow(tEquiv, 0.7), smoothstep(0.0, 0.12, tEquiv), effEdge);

      float gobo = 1.0;
      if (vBeamFx.y >= 0.5) {
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
        if (tb > 0.0 && tb < relLen - 0.01) { lit = 0.0; break; }
      }

      sum += gobo * radial * lit;
    }

    float alpha = uHaze * vOpacity * uVolGain * sum * (tExit - tEnter) / float(uVolSteps);
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
    depthTest: false,
    side: BackSide,
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

export function makeConeMaterial(): ShaderMaterial {
  return new ShaderMaterial({
    uniforms: {
      uFloorY: { value: 0.0 },
      uWallZ: { value: NO_WALL_Z },
      uHaze: { value: HAZE_LEVEL },
      uEdgeSoftRange: { value: EDGE_SOFT_RANGE_M },
      ...makeRegionUniforms(),
    },
    vertexShader: CONE_VERTEX_SHADER,
    fragmentShader: CONE_FRAGMENT_SHADER,
    transparent: true,
    blending: AdditiveBlending,
    depthWrite: false,
    side: DoubleSide,
  })
}

/**
 * `withGobo` compiles in the focus/gobo/shadow-mask path. Off for the
 * per-pixel wash pools: a strip draws 16 pools per fixture (× regions) and
 * can't gobo anything, so it shouldn't pay for the texture fetch or the two
 * cross products — and the `#ifdef` also keeps three extra instanced
 * attributes off that geometry.
 */
export function makePoolMaterial(withGobo: boolean, gobo?: DataArrayTexture): ShaderMaterial {
  return new ShaderMaterial({
    defines: withGobo ? { USE_GOBO: '' } : {},
    uniforms: {
      uCoreBoost: { value: 0.5 },
      uWallZ: { value: NO_WALL_Z },
      ...(withGobo
        ? {
            uGobo: { value: gobo ?? null },
            uLodK: { value: FOCUS_LOD_K },
            uLodMax: { value: FOCUS_LOD_MAX },
            uEdgeSoftRange: { value: EDGE_SOFT_RANGE_M },
          }
        : {}),
      ...makeRegionUniforms(),
    },
    vertexShader: POOL_VERTEX_SHADER,
    fragmentShader: POOL_FRAGMENT_SHADER,
    transparent: true,
    blending: AdditiveBlending,
    depthWrite: false,
  })
}
