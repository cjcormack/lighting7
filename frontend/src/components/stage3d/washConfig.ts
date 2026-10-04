import { MASK_EDGE_SOFT } from './beamMask'
import { GOBO_TILE_PX } from './goboAtlas'

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
// Focus maps the fixture's focus channel to a focal *distance* from the
// aperture (resolveDeclaredFocusDistance / resolveFocusDistance in beamOptics);
// rim softness and the in-air gobo's blur both grow with the blur a point sees,
// the relative focus error |f − d| / f times the type's depth of field
// (focusBlur in beamMask, fixture-optics plan D9). Only the edge was tuned to it
// (DEPTH_OF_FIELD in bodies/archetype.ts); the gobo constants below were not.

/** A gobo tile's texels across the field's radius. A blur `b` field radii wide
 *  spans `b ×` this many texels and each mip level averages twice as many, so the
 *  in-air gobo samples at LOD log2(1 + b × this). The blur is the relative-error
 *  one since fixture-optics session 1, which runs larger than the old blur circle
 *  on a long throw (a 24 m Revolution one DMX step off focus is about LOD 3). */
export const GOBO_BLUR_TEXELS = GOBO_TILE_PX / 2

/** LOD ceiling for defocus blur (128px atlas has 8 mip levels; 6 is mush). */
export const FOCUS_LOD_MAX = 6

/** The blur, in field radii, at which the edge is fully soft: a fully soft
 *  beam's own roll-off, so the edge rolls off over the width of the blur. */
export const FOCUS_SOFT_BLUR = MASK_EDGE_SOFT

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
