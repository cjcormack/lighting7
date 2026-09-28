// Stage-3D atmosphere & pixel-bar wash tuning. These are code-level knobs, not
// UI settings — tweak the values here. Shared by the cone/pool emitters
// (StageEmitters) and the PixelStrip glow so the floor wash and the mid-air
// glow stay consistent (e.g. WASH_ANGLE_DEG shapes both).

/** Mid-air volume strength. 1 = normal beams; 0 = surfaces only (no haze in the
 *  air); >1 = denser, smokier room. Surface pools are unaffected. */
export const HAZE_LEVEL = 1

// — axial profile ——————————————————————————————————————————————————
// Beam cones, gobo volumes and surface pools are deliberately UNIFORM along
// the throw — no axial or distance fade (all three shaders in beamShaders.ts;
// they end hard at BEAM_LENGTH by design). The one exception is the
// PixelStrip bar glow (fixtureBodies/PixelStrip.tsx, GLOW_FADE_POWER): a
// ~0.1 m lens bloom that keeps its lens-to-tip fade so it dies out instead of
// ending in a visible disc. Don't "unify" in either direction without
// deciding the look — and never re-add a fade to only one beam shader, or the
// shell↔march brightness match VOL_GAIN is calibrated against breaks.

/** Full cone angle (deg) shared by each pixel's floor/region wash pool and its
 *  mid-air glow cone. Wider = softer / more spread; narrower = tighter with more
 *  per-pixel colour separation. */
export const WASH_ANGLE_DEG = 90

/** Per-pixel wash pool opacity 0..1 (additive). Low so a bar's overlapping
 *  pixels blend as colour rather than blowing out to white. */
export const WASH_OPACITY = 0.3

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
// A fixture with a gobo in the beam renders a raymarched volume instead of
// the silhouette shell, so the pattern breaks the beam into sub-beams
// through the haze.

/** March samples per fragment at dpr ≤ 1.5; high-dpr displays drop a third
 *  (fill quadruples at dpr 2, so trade depth for area). Compile-time max 16. */
export const VOLUMETRIC_STEPS = 12

/** Overall gain on the marched beam (per-metre density scale), tuned so
 *  engaging a gobo reads as "a pattern appears in the beam", not a brightness
 *  jump against the shell: side-on through a ~1 m chord the shell's two
 *  silhouette faces sum to roughly 0.6× opacity, so the density must sit near
 *  that per metre after the radial/gobo terms eat into it. Brightness is
 *  uniform along the throw (no axial fade) in both the shell and the march,
 *  so the two stay matched without folding a fade into this gain. */
export const VOL_GAIN = 6.0

/** Base mip level for in-air gobo samples; defocus LOD adds on top. 0 keeps
 *  the pattern crisp for the full throw — the stylised "consistent cone"
 *  look. The march samples with textureLod (no automatic minification), so
 *  raise this if fine gobos (dots/stars) shimmer or alias at long throw, or
 *  if march banding shows through the jitter + bloom. */
export const VOL_LOD_BASE = 0.0
