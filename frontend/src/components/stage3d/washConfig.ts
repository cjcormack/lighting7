// Stage-3D atmosphere tuning. These are code-level knobs, not UI settings — tweak the values here.
// Read by the beam volumes (`beamShaders.ts`, through `StageEmitters`) and the surface shader.

/** Mid-air volume strength. 1 = normal beams; 0 = surfaces only (no haze in the
 *  air); >1 = denser, smokier room. Surface pools are unaffected. */
export const HAZE_LEVEL = 1

// — axial profile ——————————————————————————————————————————————————
// Beam volumes and surface pools are deliberately UNIFORM along the throw — no axial or distance
// fade — so a stylised, consistent beam reads the same at every distance; the volumes end where the
// beam lands, or at BEAM_LENGTH past the aperture. Only the spread thins the air (VOL_SPREAD): the
// same light across a wider cross-section. Don't add a fade to the air alone, or the pools and the
// beams above them disagree. (Session 6 retired the per-pixel wash and its glow, which were the one
// exception, with the cone shell every open beam used to be.)

// — focal model ————————————————————————————————————————————————————
// Focus maps the fixture's focus channel to a focal *distance* along the
// throw; pattern blur and rim softness both grow with how far the receiving
// surface sits from that plane (see resolveFocusDistance in beamOptics).

/** Mip LOD added per metre of defocus — higher = blurrier faster. */
export const FOCUS_LOD_K = 1.2

/** LOD ceiling for defocus blur (128px atlas has 8 mip levels; 6 is mush). */
export const FOCUS_LOD_MAX = 6

/** Defocus distance (m) over which the pool rim fades from crisp to the
 *  original soft falloff. */
export const EDGE_SOFT_RANGE_M = 1.5

// — volumetric beam ————————————————————————————————————————————————
// Every beam in the air is a raymarched volume since stage-view plan session 6 (the silhouette
// shell an open beam used to be could not show a soft edge, an iris or a segment's rectangle), and
// a gobo breaks it into sub-beams through the haze.

/** March samples per fragment at dpr ≤ 1.5; high-dpr displays drop a third
 *  (fill quadruples at dpr 2, so trade depth for area). Compile-time max 16. */
export const VOLUMETRIC_STEPS = 12

/** How fast a marched chord reaches its beam's opacity, per metre: the alpha is
 *  `opacity × (1 − exp(−gain × density × chord))`, so side-on through ~0.5 m of beam it sits at
 *  the opacity the retired cone shell drew (stage-view plan session 6), and no chord, however long,
 *  adds up past it. Brightness is uniform along the throw (no axial fade); the air thins only as the
 *  beam spreads (`VOL_SPREAD`). */
export const VOL_GAIN = 6.0

/** How fast the marched air thins as a beam spreads, per metre of beam radius: density falls as
 *  1 / (1 + k · r). Stage-view plan session 6, when every beam started marching. */
export const VOL_SPREAD = 1.5

/** Base mip level for in-air gobo samples; defocus LOD adds on top. 0 keeps
 *  the pattern crisp for the full throw — the stylised "consistent cone"
 *  look. The march samples with textureLod (no automatic minification), so
 *  raise this if fine gobos (dots/stars) shimmer or alias at long throw, or
 *  if march banding shows through the jitter + bloom. */
export const VOL_LOD_BASE = 0.0
