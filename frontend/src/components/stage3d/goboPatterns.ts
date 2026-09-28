/**
 * The gobo pattern vocabulary: ordered names (= texture array layers) and the
 * procedural generator behind each.
 *
 * The names are the wire contract with the backend's `GoboPattern` enum —
 * fixture definitions reference patterns by name, and this registry decides
 * what a name looks like. That's the image-swap seam: a future entry can be
 * backed by baked image data instead of a generator and only the atlas build
 * changes; fixture definitions and the resolvers never do.
 *
 * Pure and three-free so `beamOptics.ts` (node-env tests) can depend on it.
 */

export const GOBO_PATTERNS = [
  // Layer 0 is "open" — the renderer skips sampling for slot 0, the entry
  // exists to keep layer index and gobo slot the same number.
  'open',
  // Layers 1..7 are the original procedural set, now addressable by name.
  'dots',
  'breakup',
  'spokes',
  'triple',
  'rings',
  'starburst',
  'bars',
  // Patterns added for the fixture-definition vocabulary.
  'cone',
  'fan',
  'beam_split',
  'fibroid',
  'holes',
  'circles',
  'stars',
  'swirl',
  'clouds',
] as const

export type GoboPatternName = (typeof GOBO_PATTERNS)[number]

/** Layers in the gobo array texture (including layer 0, "open"). */
export const GOBO_SLOT_COUNT: number = GOBO_PATTERNS.length

const LAYER_BY_NAME = new Map<string, number>(GOBO_PATTERNS.map((n, i) => [n, i]))

/**
 * Texture layer for a backend pattern name; undefined when the name is absent
 * or unknown (a backend whose vocabulary is newer than this build). Callers
 * treat undefined-on-an-annotated-wheel as "render open" — degrading to no
 * pattern beats drawing the wrong one.
 */
export function goboLayerFor(name: string | undefined): number | undefined {
  return name == null ? undefined : LAYER_BY_NAME.get(name)
}

// Smooth 0..1 ramp, mirroring GLSL smoothstep so the patterns anti-alias
// instead of stair-stepping when the pool is drawn large.
export function smoothstep(edge0: number, edge1: number, x: number): number {
  if (edge1 <= edge0) return x < edge0 ? 0 : 1
  const t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}

/** Number of full-brightness spokes/lobes for the radial patterns. */
const SPOKE_COUNT = 6
const DOT_RING_COUNT = 8

// Fixed layouts for the point-based patterns. Hand-placed rather than hashed:
// determinism is a hard requirement (no Math.random anywhere in the atlas),
// and a dozen coordinates reads better than a seeded PRNG.
const HOLE_SPOTS: ReadonlyArray<[number, number, number]> = [
  [-0.45, -0.35, 0.2], [0.3, -0.5, 0.16], [0.55, 0.1, 0.22], [-0.1, 0.05, 0.18],
  [-0.6, 0.3, 0.15], [0.15, 0.55, 0.2], [-0.25, -0.72, 0.13], [0.68, -0.28, 0.12],
  [-0.05, 0.78, 0.11],
]
const CIRCLE_SPOTS: ReadonlyArray<[number, number, number]> = [
  [-0.3, -0.25, 0.42], [0.35, -0.1, 0.38], [0.0, 0.4, 0.35], [-0.45, 0.35, 0.28],
]
const STAR_SPOTS: ReadonlyArray<[number, number, number]> = [
  [-0.5, -0.4, 0.07], [0.25, -0.55, 0.09], [0.6, 0.0, 0.06], [-0.15, -0.05, 0.1],
  [-0.65, 0.25, 0.06], [0.4, 0.45, 0.08], [-0.3, 0.6, 0.07], [0.05, -0.8, 0.05],
  [0.72, -0.35, 0.05], [-0.05, 0.82, 0.05], [-0.78, -0.1, 0.05], [0.1, 0.25, 0.06],
]

function pointsPattern(
  spots: ReadonlyArray<[number, number, number]>,
  x: number,
  y: number,
  soft: number,
): number {
  let v = 0
  for (const [cx, cy, rad] of spots) {
    const d = Math.hypot(x - cx, y - cy)
    const s = 1 - smoothstep(rad * soft, rad, d)
    if (s > v) v = s
  }
  return v
}

/**
 * Per-pattern generator at normalised disc coordinates: `r` is 0 at the beam
 * centre and 1 at the rim, `a` is the angle in radians. Returns 0..1 *before*
 * the shared disc mask — the atlas build applies the rim fade so every layer
 * gets the identical soft edge.
 */
export const PATTERN_GENERATORS: Record<GoboPatternName, (r: number, a: number) => number> = {
  open: () => 1,

  dots: (r, a) => {
    // Ring of dots.
    const spoke = a * (DOT_RING_COUNT / (Math.PI * 2))
    const frac = spoke - Math.floor(spoke)
    const dAng = (Math.min(frac, 1 - frac) * (Math.PI * 2 * 0.62)) / (Math.PI * 2) * 6
    const dRad = Math.abs(r - 0.62)
    const d = Math.hypot(dAng, dRad)
    return 1 - smoothstep(0.1, 0.16, d)
  },

  breakup: (r, a) => {
    // Organic breakup — three offset sinusoids, thresholded.
    const x = r * Math.cos(a)
    const y = r * Math.sin(a)
    const n =
      Math.sin(x * 7.1 + 1.3) * Math.sin(y * 6.3 - 0.7) +
      0.6 * Math.sin(x * 13.7 - 2.1) * Math.sin(y * 11.9 + 0.4)
    return smoothstep(-0.05, 0.35, n)
  },

  spokes: (_r, a) => {
    // Radial spokes.
    const s = Math.cos(a * SPOKE_COUNT)
    return smoothstep(0.05, 0.45, s)
  },

  triple: (r, a) => {
    // Triangular / faceted split.
    const s = Math.cos(a * 3) * (1 - r * 0.5)
    return smoothstep(0.1, 0.5, s)
  },

  rings: (r) => {
    // Concentric rings.
    const rings = Math.cos(r * Math.PI * 2 * 3.5)
    return smoothstep(0.0, 0.5, rings)
  },

  starburst: (r, a) => {
    // Starburst — narrow spikes over a bright hub.
    const spikes = Math.pow(Math.max(0, Math.cos(a * 5)), 8)
    const hub = 1 - smoothstep(0.0, 0.28, r)
    return Math.min(1, hub + spikes * (1 - hub))
  },

  bars: (r, a) => {
    // Parallel slats.
    const y = r * Math.sin(a)
    const bars = Math.cos(y * Math.PI * 2 * 3)
    return smoothstep(0.0, 0.4, bars)
  },

  cone: (r) => {
    // A stopped-down soft disc — the classic "cone of light" reducer.
    return 1 - smoothstep(0.5, 0.72, r)
  },

  fan: (r, a) => {
    // Four broad blades, swept slightly with radius so they read as a fan
    // rather than a cross.
    const s = Math.cos(a * 4 + r * 1.6)
    return smoothstep(0.25, 0.6, s)
  },

  beam_split: (r, a) => {
    // Two thick parallel beams with a dark gutter — a beam splitter.
    const x = r * Math.cos(a)
    const s = Math.cos(x * Math.PI * 2 * 1.5)
    return smoothstep(0.45, 0.75, s)
  },

  fibroid: (r, a) => {
    // Warped filaments — sinusoids sheared by a second frequency.
    const x = r * Math.cos(a)
    const y = r * Math.sin(a)
    const n = Math.sin(x * 9.3 + 2.2 * Math.sin(y * 4.7 + 0.9))
    return smoothstep(0.25, 0.65, n)
  },

  holes: (r, a) => {
    // Scattered round holes of mixed sizes.
    const x = r * Math.cos(a)
    const y = r * Math.sin(a)
    return pointsPattern(HOLE_SPOTS, x, y, 0.65)
  },

  circles: (r, a) => {
    // Overlapping circle outlines.
    const x = r * Math.cos(a)
    const y = r * Math.sin(a)
    let v = 0
    for (const [cx, cy, rad] of CIRCLE_SPOTS) {
      const d = Math.abs(Math.hypot(x - cx, y - cy) - rad)
      const s = 1 - smoothstep(0.04, 0.09, d)
      if (s > v) v = s
    }
    return v
  },

  stars: (r, a) => {
    // A field of small sharp points.
    const x = r * Math.cos(a)
    const y = r * Math.sin(a)
    return pointsPattern(STAR_SPOTS, x, y, 0.4)
  },

  swirl: (r, a) => {
    // Spiral arms — spokes twisted by radius.
    const s = Math.cos(a * 3 + r * 7)
    return smoothstep(0.2, 0.6, s)
  },

  clouds: (r, a) => {
    // Low-frequency blotches, softer-edged than the breakup.
    const x = r * Math.cos(a)
    const y = r * Math.sin(a)
    const n =
      Math.sin(x * 3.1 + 0.5) * Math.sin(y * 2.7 - 1.2) +
      0.5 * Math.sin((x + y) * 4.3 + 0.8)
    return smoothstep(-0.2, 0.55, n)
  },
}
