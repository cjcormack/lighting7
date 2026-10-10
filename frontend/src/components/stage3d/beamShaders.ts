import { AdditiveBlending, DoubleSide, ShaderMaterial, Vector2, Vector3, Vector4 } from 'three'
import type { DataArrayTexture } from 'three'
import { BEAM_FOCUS_GLSL, BEAM_MASK_GLSL, FOCUS_SPREAD_MAX } from './beamMask'
import { GOBO_LAYERS_GLSL } from './goboLayers'
import { MAX_BEAM_REGIONS } from './emitterLayout'
import { LANDING_GLSL, LANDING_REACH_GLSL, REACH_EPS_M } from './scene/landing'
import { getMaskAtlasTexture } from './scene/maskAtlas'
import { TRANSMIT_GLSL } from './scene/occlusion'
import { HAZE_PLANE_FLOATS, HAZE_PLANES_GLSL, MAX_HAZE_PLANES, type HazePlaneList } from './scene/hazePlanes'
import {
  FOCUS_LOD_MAX,
  GOBO_BLUR_TEXELS,
  HAZE_LEVEL,
  VOLUMETRIC_STEPS,
  VOL_AXIAL_FADE,
  VOL_GAIN,
  VOL_LOD_BASE,
  VOL_SPREAD,
  VOL_SPREAD_NEAR,
} from './washConfig'

/** Compile-time march bound; `uVolSteps` varies below it at runtime. */
export const MAX_VOL_STEPS = 16

/**
 * GLSL for the shared beam emitters, extracted from `StageEmitters` so the beam cross-section math
 * is one chunk wherever the gobo is sampled.
 *
 * Every beam in the air is **raymarched** (stage-view plan session 6). Until then an open beam was a
 * hollow additive cone shell and only a gobo beam marched; a shell cannot show a beam's shape
 * inside it — a soft edge, an iris, a segment's rectangle, a shutter cut, an oval — so the shell
 * is gone and the volume draws them all, through `beamMask`, the function the surfaces' pools are
 * shaped by too. What that costs is the haze governor's to pay (`scene/hazeGovernor.ts`).
 *
 * The pool program that drew the floor, wall and region cookies is gone (stage-view plan
 * session 3): where a beam lands is the surface shader's (`scene/surfaceShader.ts`), which reads
 * the light table rather than being instanced per beam × receiver.
 */

// Sentinel "no wall" plane, far enough upstage that nothing reaches it.
export const NO_WALL_Z = -1e6
/** Sentinel side walls, far enough out that nothing reaches them. */
export const NO_SIDE_X = 1e6

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
// lands at |g| = 1 regardless of zoom. The gobo is turned and sampled in this
// frame by goboLayers.ts's goboSample, which the surfaces share, so the frames
// cannot drift.
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
// (the prototype's rule; a segment's hull is scaled to an ellipse round its rectangle). For an eye
// in the beam the hull is folded back in front of where it lands, so that back face passes the depth
// test too. The true bounds come from an analytic ray-cone or ray-pyramid intersection per fragment.
// The chord is clamped by the axial range, where the beam landed (`scene/landing.ts`), the floor,
// the upstage and side walls, the window's haze plane and camera-ray region occlusion — the depth test hides a beam behind something, but cannot cut one that passes
// through a box — then sampled with a per-pixel interleaved-gradient jitter so banding reads as
// grain. Each sample is shaped by `beamMask`: the field edge, the iris and the softness.
//
// The air is dense by the lamp and thins along the throw and as the beam spreads (`washConfig.ts`),
// rolled off by `1 − exp(−light)` and encoded here, so two beams crossing visibly sum.
//
// **The haze splits at a cloth** (scrim plan D10, session 5; `scene/hazePlanes.ts`): a gauze or a cut
// cloth is never where a beam lands, so the chord runs on past it, and each sample is multiplied by
// what the list of eight transmitting planes passes — the eye's share of each plane the view ray
// crossed before it (a gauze's open(θ_eye)^gather, a cut cloth's mask), found once a pixel, and the
// beam's share of each plane its row names (`aBeamFx.z`) between the sample and the aperture. The
// crossing, open(θ) and the mask lookup are the shadows' own (`occlusion.ts`'s TRANSMIT_GLSL).

// Whether a point is inside the hull as it is drawn unfolded: the test the vertex shader's fold and
// the fragment shader's face both start from. The cone is the field, or past it by the most a focus
// blur spreads the edge (beamMask.ts's FOCUS_SPREAD_MAX) for a head with a focus channel.
const IN_HULL_GLSL = /* glsl */ `
  float beamTanBound(float cosField, float focusDist) {
    float sinHalf = sqrt(max(0.0, 1.0 - cosField * cosField));
    float tanHalf = max(1e-4, sinHalf / max(1e-4, cosField));
    return focusDist >= 0.0 ? tanHalf * ${(1 + FOCUS_SPREAD_MAX).toFixed(4)} : tanHalf;
  }

  bool inHull(vec3 p, vec3 apex, vec3 d, vec3 bx, vec3 by, float near, float len, float tanBound, float aspect) {
    vec3 co = p - apex;
    float cod = dot(co, d);
    float cos2 = 1.0 / (1.0 + tanBound * tanBound);
    return cod >= near && cod <= len && (aspect > 0.0
      ? abs(dot(co, bx)) <= cod * tanBound && abs(dot(co, by)) <= cod * tanBound * aspect
      : cod * cod >= cos2 * dot(co, co));
  }
`

const VOLUME_VERTEX_SHADER = /* glsl */ `
  attribute vec3 aBeamOrigin;
  attribute vec3 aBeamDir;
  attribute vec3 aBeamRight;
  // Packed to stay inside WebGL's guaranteed sixteen vertex attributes: three's prefix declares
  // position, normal and uv, the instance matrix takes four, and these eight take the rest —
  // fifteen. The colour carries the opacity in .a; the fx carry the edge, the packed gobo layers
  // (goboLayers.ts, fixture-optics session 4), the haze planes the beam crosses (bit k for plane k of
  // scene/hazePlanes.ts's list, scrim plan session 5) and the focal distance; the shape carries near,
  // iris, aspect and the depth of field (fixture-optics session 1); the gate carries the half-angle,
  // the shadow mask and the two packed blade words (stage-view plan session 7). A program past
  // sixteen does not link on ANGLE, and every beam in the air goes dark (StageEmitters.test.ts pins it).
  attribute vec4 aColor;
  attribute vec4 aBeamFx;
  attribute vec4 aBeamShape;
  attribute vec4 aBeamGate;
  // Where the beam landed: two planes packed (scene/landing.ts), nothing behind both.
  attribute vec4 aBeamLand;

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
  varying vec4 vBeamLand;
  varying vec4 vBeamLandEdge;
  // Flat: the blades and the gobo layers are packed integers (beamMask.ts's packBlades,
  // goboLayers.ts's packGobos), which interpolation — even of three equal corners — could nudge off
  // by an ulp and unpack as the wrong blade or pattern.
  flat varying vec2 vBeamBlades;
  flat varying float vBeamGobos;
  // Flat for the same reason: a bit mask.
  flat varying float vBeamPlanes;

  ${LANDING_GLSL}
  ${LANDING_REACH_GLSL}
  ${IN_HULL_GLSL}

  void main() {
    vBeamShape = aBeamShape;
    vBeamBlades = aBeamGate.zw;
    vBeamGobos = aBeamFx.y;
    vBeamPlanes = aBeamFx.z;
    // The drawn length is the instance's y scale, from the apex: far enough for the whole cone to
    // cross the planes it landed on (the director's coneLandingDepth), or BEAM_LENGTH past its
    // aperture in open air.
    vBeamLen = length(instanceMatrix[1].xyz);
    vBeamLand = landPlane(aBeamLand.x, aBeamLand.y);
    vBeamLandEdge = landPlane(aBeamLand.z, aBeamLand.w);
    vec4 wp = modelMatrix * instanceMatrix * vec4(position, 1.0);
    // The hull runs past the planes it landed on, so the far side an eye inside it draws would lie
    // behind the surface. For that eye, each vertex is moved back along its apex line to just in
    // front of the planes. Not for a section, which would lose the haze beside a plane seen edge-on,
    // and not onto the floor and walls, where the hull would cut across their corner.
    vec3 apex = aBeamOrigin;
    vec3 bx = normalize(aBeamRight - aBeamDir * dot(aBeamRight, aBeamDir));
    float tanBound = beamTanBound(aBeamGate.x, aBeamFx.w);
    if (!isOrthographic
        && inHull(cameraPosition, apex, aBeamDir, bx, cross(aBeamDir, bx), aBeamShape.x, vBeamLen, tanBound, aBeamShape.z)) {
      wp.xyz = apex + (wp.xyz - apex) * landingReach(wp.xyz, apex, vBeamLand, vBeamLandEdge, ${REACH_EPS_M.toFixed(3)});
    }
    vWorldPos = wp.xyz;
    vBeamOrigin = aBeamOrigin;
    vBeamDir = aBeamDir;
    vBeamRight = aBeamRight;
    vColor = aColor.rgb;
    vOpacity = aColor.a;
    vCosHalfAngle = aBeamGate.x;
    vBeamFx = aBeamFx;
    vShadowMask = aBeamGate.y;
    gl_Position = projectionMatrix * viewMatrix * wp;
  }
`

const VOLUME_FRAGMENT_SHADER = /* glsl */ `
  #define MAX_REGIONS ${MAX_BEAM_REGIONS}
  #define MAX_VOL_STEPS ${MAX_VOL_STEPS}
  #define REACH_EPS ${REACH_EPS_M.toFixed(3)}
  uniform float uFloorY;
  uniform float uWallZ;
  // The outermost side walls, (min x, max x).
  uniform vec2 uSideX;
  // Where the air shows the beam: dot(p, xyz) + w >= 0. (0, 0, 0, 1) everywhere.
  uniform vec4 uHazeClip;
  uniform float uHaze;
  uniform sampler2DArray uGobo;
  uniform int uVolSteps;
  uniform float uVolGain;
  uniform float uVolSpread;
  uniform float uVolSpreadNear;
  uniform float uVolAxialFade;
  uniform float uVolLodBase;
  uniform float uGoboBlurTexels;
  uniform float uLodMax;
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
  varying vec4 vBeamLand;
  varying vec4 vBeamLandEdge;
  flat varying vec2 vBeamBlades;
  flat varying float vBeamGobos;
  flat varying float vBeamPlanes;

  ${RAY_OBB_T_GLSL}
  ${IN_HULL_GLSL}
  ${CROSS_SECTION_GLSL}
  ${BEAM_MASK_GLSL}
  ${BEAM_FOCUS_GLSL}
  ${GOBO_LAYERS_GLSL}
  ${TRANSMIT_GLSL}
  ${HAZE_PLANES_GLSL}

  // Clamp the chord [t0, t1] to the half-space value(t) = base + t*rate >= 0.
  void clampHalfSpace(float base, float rate, inout float t0, inout float t1) {
    if (abs(rate) < 1e-6) {
      if (base < 0.0) { t1 = t0 - 1.0; }
      return;
    }
    float tc = -base / rate;
    if (rate > 0.0) { t0 = max(t0, tc); } else { t1 = min(t1, tc); }
  }

  // The span of t where base + t*rate < 0 — behind a plane — as (from, to); empty when from >= to.
  vec2 behindSpan(float base, float rate) {
    if (abs(rate) < 1e-6) return base < 0.0 ? vec2(-1e9, 1e9) : vec2(1.0, -1.0);
    float tc = -base / rate;
    return rate > 0.0 ? vec2(-1e9, tc) : vec2(tc, 1e9);
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
    float dof = vBeamShape.w;
    float focusDist = vBeamFx.w;
    float cosField = vCosHalfAngle;
    float vd = dot(rayDir, d);
    vec3 co = camPos - O;
    float cod = dot(co, d);
    float sinHalf = sqrt(max(0.0, 1.0 - cosField * cosField));
    float tanHalf = max(1e-4, sinHalf / max(1e-4, cosField));
    // The cone the march runs through (beamTanBound). The mask still cuts in the field's own frame
    // (tanHalf).
    float tanBound = beamTanBound(cosField, focusDist);
    float cos2 = 1.0 / (1.0 + tanBound * tanBound);
    vec3 bx = normalize(vBeamRight - d * dot(vBeamRight, d));
    vec3 by = cross(d, bx);

    // Which face of the hull draws this pixel: its front face, depth-tested against the scene, when
    // the view ray starts outside the beam — so the stalls, a pros wall or a flat in front of a beam
    // hide it, as they hid the retired shell — and its back face when the ray starts inside it (the
    // camera standing in a beam, or a section's plane cutting one), whose front faces are behind it.
    // A perspective eye behind both landing planes is outside the beam: its hull is folded off it.
    float ty0 = tanBound * aspect;
    bool rayStartsInside = inHull(camPos, O, d, bx, by, near, vBeamLen, tanBound, aspect)
      && (isOrthographic || !(dot(vBeamLand.xyz, camPos) < vBeamLand.w && dot(vBeamLandEdge.xyz, camPos) < vBeamLandEdge.w));
    if (gl_FrontFacing == rayStartsInside) discard;
    // A front face behind where the beam lands, or outside the room the haze is clipped to, is on
    // the far side of a surface — the hull runs on past the plane for the cone's far rim, and a beam
    // landing on the deck runs on through the back wall. The march ignores depth, so marched from
    // there it would sum the beam through that surface. Without it, a surface hides the beam behind
    // it by depth, and a room's wall, drawn from inside only, still shows the beam in the room. The
    // room is taken in by REACH_EPS: a flat stands against its wall, and the hull between the two
    // would still draw. Folded, the hull still dips behind both planes along an edge.
    if (!rayStartsInside && (
        (dot(vBeamLand.xyz, vWorldPos) < vBeamLand.w && dot(vBeamLandEdge.xyz, vWorldPos) < vBeamLandEdge.w)
        || vWorldPos.y < uFloorY + REACH_EPS || vWorldPos.z < uWallZ + REACH_EPS
        || vWorldPos.x < uSideX.x + REACH_EPS || vWorldPos.x > uSideX.y - REACH_EPS)) discard;

    float tEnter = 0.0;
    float tExit = -1.0;
    if (aspect > 0.0) {
      // A segment's rectangular frustum: inside the four planes through the apex whose normals are
      // (±bx − d·tan) and (±by − d·tan·aspect) — each a half-space linear in t.
      tEnter = 0.0;
      tExit = 1e6;
      vec3 n1 = bx - d * tanBound;
      vec3 n2 = -bx - d * tanBound;
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
    // Floor, upstage wall, and how far this window's haze reaches (hazeClipFor).
    clampHalfSpace(camPos.y - uFloorY, rayDir.y, tEnter, tExit);  // y >= floor
    clampHalfSpace(camPos.z - uWallZ, rayDir.z, tEnter, tExit);   // z >= wall
    clampHalfSpace(camPos.x - uSideX.x, rayDir.x, tEnter, tExit); // x >= the SR side wall
    clampHalfSpace(uSideX.y - camPos.x, -rayDir.x, tEnter, tExit); // x <= the SL side wall
    clampHalfSpace(dot(camPos, uHazeClip.xyz) + uHazeClip.w, dot(rayDir, uHazeClip.xyz), tEnter, tExit);
    // Nothing behind both landing planes, however obliquely the beam lands. Behind one is a half-line
    // of the ray and behind both their overlap: at an end of the chord it trims it, and inside it
    // (an edge seen side-on) the march skips it.
    vec2 landGap = behindSpan(dot(camPos, vBeamLand.xyz) - vBeamLand.w, dot(rayDir, vBeamLand.xyz));
    vec2 edgeGap = behindSpan(dot(camPos, vBeamLandEdge.xyz) - vBeamLandEdge.w, dot(rayDir, vBeamLandEdge.xyz));
    landGap = vec2(max(landGap.x, edgeGap.x), min(landGap.y, edgeGap.y));
    if (landGap.x < landGap.y) {
      if (landGap.x <= tEnter) { tEnter = max(tEnter, landGap.y); landGap = vec2(1.0, -1.0); }
      else if (landGap.y >= tExit) { tExit = min(tExit, landGap.x); landGap = vec2(1.0, -1.0); }
    }

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
    // grain instead of rings.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));

    // The cross-section frame (bx, by, tanHalf, above) is constant per fragment — hoisted out of
    // the march (WebKit's compiler is not trusted to do it). Same construction as the surface
    // shader's, so the in-air shape and the pool are the same image at every distance.
    // The gobo layers are per-fragment constant too — decoded once (each layer's cos, sin and atlas
    // layer) with the frame; goboSample keeps the rotate itself shared with the surfaces. A
    // segment carries none.
    vec4 goboA;
    vec4 goboB;
    goboRots(aspect <= 0.0 ? vBeamGobos : 0.0, goboA, goboB);
    bool hasGobo = goboA.w > 0.5 || goboB.w > 0.5;

    // Where the view ray crosses each haze plane, and what it keeps of what lies behind it: a
    // per-fragment constant, so the march compares and multiplies (scene/hazePlanes.ts).
    vec4 eyeAt0;
    vec4 eyeAt1;
    vec4 eyeShare0;
    vec4 eyeShare1;
    hazeEyeCrossings(camPos, rayDir, eyeAt0, eyeAt1, eyeShare0, eyeShare1);
    int planes = int(vBeamPlanes + 0.5);

    int lightMask = int(vShadowMask + 0.5);
    float sum = 0.0;
    for (int i = 0; i < MAX_VOL_STEPS; i++) {
      if (i >= uVolSteps) break;
      float t = mix(tEnter, tExit, (float(i) + jitter) / float(uVolSteps));
      if (t > landGap.x && t < landGap.y) continue;
      vec3 p = camPos + rayDir * t;
      vec3 rel = p - O;
      float relLen = max(length(rel), 1e-4);
      vec3 lightDir = rel / relLen;

      float cosAngle = dot(lightDir, d);
      // What reaches this sample through the cloths: the eye's side and the beam's, to the aperture.
      float through = hazeSampleShare(t, p, -lightDir, relLen - near / max(cosAngle, 1e-4), planes, eyeAt0, eyeAt1, eyeShare0, eyeShare1);
      if (through <= 0.0) continue;
      vec2 g = vec2(dot(lightDir, bx), dot(lightDir, by)) / (max(1e-4, cosAngle) * tanHalf);
      // Focus is a distance along the axis from the aperture, not from the apex behind it.
      float blur = beamFocusBlur(rel, d, near, focusDist, dof);
      // A segment's rectangle or an oval's narrow axis: the field edge at 1 on v too. An oval is
      // marched through the round cone of its wide field, and the mask cuts it to the oval.
      vec2 mg = aspect != 0.0 ? vec2(g.x, g.y / abs(aspect)) : g;
      float radial = beamMask(mg, aspect, iris, 1.0 - vBeamFx.x, blur, vBeamBlades);

      float gobo = 1.0;
      if (hasGobo) {
        float lod = min(uLodMax, uVolLodBase + goboLod(blur, 0.0, uGoboBlurTexels, uLodMax));
        gobo = goboPair(g, goboA, goboB, lod);
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

      // The air thins along the throw and as the beam spreads — the same light over a wider
      // cross-section, so a wide wash is not a white wall to look down.
      float axial = max(0.0, relLen * cosAngle);
      float density = (1.0 - uVolAxialFade * clamp(axial / vBeamLen, 0.0, 1.0)) / (uVolSpreadNear + uVolSpread * axial * tanHalf);
      sum += gobo * radial * lit * density * through;
    }

    // The prototype integrated in the unit cone's frame scaled by its end radius: metres side-on,
    // shortened by tanHalf looking down the axis, which keeps a beam seen end-on bounded.
    float metric = sqrt(max(1e-4, 1.0 - vd * vd * (1.0 - tanHalf * tanHalf)));
    float chord = sum * (tExit - tEnter) / float(uVolSteps) * metric;
    vec3 c = 1.0 - exp(-vColor * vOpacity * uHaze * uVolGain * chord);
    if (max(c.r, max(c.g, c.b)) <= 0.0005) discard;
    gl_FragColor = vec4(linearToOutputTexel(vec4(c, 1.0)).rgb, 1.0);
  }
`

export function makeVolumeMaterial(gobo: DataArrayTexture, maskAtlas: DataArrayTexture = getMaskAtlasTexture()): ShaderMaterial {
  return new ShaderMaterial({
    uniforms: {
      // The haze planes (scene/hazePlanes.ts) and the masks a cut cloth's are cut by: uniforms the
      // flush writes every frame (writeHazePlanes), never a define.
      uHazePlaneCount: { value: 0 },
      uHazePlanes: { value: new Float32Array(MAX_HAZE_PLANES * HAZE_PLANE_FLOATS) },
      uMaskAtlas: { value: maskAtlas },
      uFloorY: { value: 0.0 },
      uWallZ: { value: NO_WALL_Z },
      uSideX: { value: new Vector2(-NO_SIDE_X, NO_SIDE_X) },
      uHazeClip: { value: new Vector4(0, 0, 0, 1) },
      uHaze: { value: HAZE_LEVEL },
      uGobo: { value: gobo },
      uVolSteps: { value: VOLUMETRIC_STEPS },
      uVolGain: { value: VOL_GAIN },
      uVolSpread: { value: VOL_SPREAD },
      uVolSpreadNear: { value: VOL_SPREAD_NEAR },
      uVolAxialFade: { value: VOL_AXIAL_FADE },
      uVolLodBase: { value: VOL_LOD_BASE },
      uGoboBlurTexels: { value: GOBO_BLUR_TEXELS },
      uLodMax: { value: FOCUS_LOD_MAX },
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

/**
 * Write the frame's haze planes into the beam materials (scene/hazePlanes.ts's list, filled nearest
 * the eye first): two uniforms, so the list moving never recompiles a program.
 */
export function writeHazePlanes(materials: ReadonlyArray<ShaderMaterial>, list: HazePlaneList): void {
  for (const mat of materials) {
    mat.uniforms.uHazePlaneCount.value = list.count
    ;(mat.uniforms.uHazePlanes.value as Float32Array).set(list.data)
  }
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
