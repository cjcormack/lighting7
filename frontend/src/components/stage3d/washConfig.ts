import { GOBO_TILE_PX } from './goboAtlas'

// Stage-3D atmosphere tuning. These are code-level knobs, not UI settings — tweak the values here.
// Read by the beam volumes (`beamShaders.ts`, through `StageEmitters`) and the surface shader.

/** How dense the haze is: the prototype's haze slider at its default. 0 = surfaces only (no haze
 *  in the air); higher is a smokier room. Surface pools are unaffected. */
export const HAZE_LEVEL = 0.5

// — axial profile ——————————————————————————————————————————————————
// The prototype's (see `scene/surfaceShader.ts`). A beam is densest by its lamp and thins along its
// throw (VOL_AXIAL_FADE) and as it spreads (VOL_SPREAD); a pool falls off with distance from the
// aperture. Both roll off by `1 − exp(−light)` and are encoded before they blend, so overlapping
// beams and pools add as the eye sees them.

// — focal model ————————————————————————————————————————————————————
// Focus maps the fixture's focus channel to a focal *distance* from the
// aperture (resolveDeclaredFocusDistance / resolveFocusDistance in beamOptics);
// the edge's penumbra and the in-air gobo's blur both grow with the blur a point
// sees, the relative focus error |f − d| / f along the axis times the type's
// depth of field (focusBlur in beamMask, fixture-optics plan D9). Only the edge
// was tuned to it (DEPTH_OF_FIELD in bodies/archetype.ts, and beamMask's
// FOCUS_SPREAD_MAX); the gobo constants below were not.

/** A gobo tile's texels across the field's radius. A blur `b` field radii wide
 *  spans `b ×` this many texels and each mip level averages twice as many, so the
 *  in-air gobo samples at LOD log2(1 + b × this). The blur is the relative-error
 *  one since fixture-optics session 1, which runs larger than the old blur circle
 *  on a long throw (a 24 m Revolution one DMX step off focus is about LOD 3). */
export const GOBO_BLUR_TEXELS = GOBO_TILE_PX / 2

/** LOD ceiling for defocus blur (128px atlas has 8 mip levels; 6 is mush). */
export const FOCUS_LOD_MAX = 6

// — volumetric beam ————————————————————————————————————————————————
// Every beam in the air is a raymarched volume since stage-view plan session 6 (the silhouette
// shell an open beam used to be could not show a soft edge, an iris or a segment's rectangle), and
// a gobo breaks it into sub-beams through the haze.

/** March samples per fragment at dpr ≤ 1.5; high-dpr displays drop a third
 *  (fill quadruples at dpr 2, so trade depth for area). Compile-time max 16. */
export const VOLUMETRIC_STEPS = 12

/** The air's gain: the marched density integral, times the beam's colour × level and the haze,
 *  goes through `1 − exp(−gain × …)` — the prototype's 0.11. */
export const VOL_GAIN = 0.11

/** How fast the air thins as a beam spreads, per metre of beam radius: density falls as
 *  1 / (VOL_SPREAD_NEAR + VOL_SPREAD · r). */
export const VOL_SPREAD = 2.5

/** The spread divisor at the apex: what keeps a beam finite where its radius is zero. */
export const VOL_SPREAD_NEAR = 0.15

/** How much of the air's density is gone by the end of the beam: density falls as
 *  1 − fade · (axial / length) along the throw. */
export const VOL_AXIAL_FADE = 0.55

/** Base mip level for in-air gobo samples; defocus LOD adds on top. 0 keeps
 *  the pattern crisp for the full throw — the stylised "consistent cone"
 *  look. The march samples with textureLod (no automatic minification), so
 *  raise this if fine gobos (dots/stars) shimmer or alias at long throw, or
 *  if march banding shows through the jitter. */
export const VOL_LOD_BASE = 0.0
