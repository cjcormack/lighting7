/**
 * How light leaves a finish (stage-light plan session 4): three lobes a finish may carry over the
 * Lambert diffuse — so a dance floor, a painted flat and velour serge differ in more than colour.
 *
 * - **Oren–Nayar** (Fujii's form, 2012) for a rough matte surface: a little darker than Lambert lit
 *   square on and brighter lit from behind the eye, which flattens a rough wall as plaster and cloth
 *   look flat. σ 0 is Lambert. Never normalised back to Lambert: it reflects at most what Lambert
 *   does at any angle (`lobes.test.ts` integrates it), so it cannot brighten a hall's overall
 *   balance, only move light towards the house.
 * - **Charlie sheen** (Estevez & Kulla 2017, with Neubelt's visibility) for velour and serge: a
 *   grazing lobe — near nothing lit and seen square on, a soft shine where the light or the eye
 *   rakes the pile. Coloured by the square root of the finish (Filament's cloth default), so red
 *   velour sheens red and black serge a dim grey: the dye that makes a cloth dark absorbs the light
 *   its pile would scatter.
 * - **GGX** (Trowbridge–Reitz, Hammon's fitted Smith visibility, Schlick's Fresnel) for floors and
 *   paint: a highlight towards the eye whose spread is the finish's roughness, so a lamp's lit
 *   aperture reads as a soft streak on a satin floor.
 *
 * The diffuse under a sheen or a specular gives up what that lobe reflects of the light at its
 * incidence ([sheenAlbedo], [specularAlbedo] — fits to the lobes' integrals, so no lookup table), so
 * a finish reflects no more than the light that reaches it at any angle, within a few percent.
 *
 * Every lobe here is in **π units**: the factor that multiplies the irradiance a light puts on the
 * point, with Lambert's diffuse at 1 — so the surface shader keeps summing as it did, and D6's
 * exposure and roll-off stay as they are. A lobe is compiled in only where a finish carries it
 * (`LOBE_*` defines), so a Lambert surface pays nothing.
 *
 * The GLSL and the TypeScript twins are written from the same constants; the twins exist for
 * `lobes.test.ts`, which pins each one's energy, its limits and that the GLSL holds the numbers.
 */

/** A finish's lobes (`sceneParts.ts` defaults them by kind, role and part). */
export interface FinishLobes {
  /** Oren–Nayar's σ: 0 is Lambert, 1 a very rough matte. */
  diffuseRoughness: number
  /** The sheen's strength, times the square root of the finish's albedo (0 none). */
  sheen: number
  /** The sheen's roughness r in (0, 1]: low is a tight shine at the grazing angle, 1 a broad one. */
  sheenRoughness: number
  /** GGX's reflectance at normal incidence (0 none; 0.04 is any dielectric: paint, vinyl, varnish). */
  specular: number
  /** GGX's perceptual roughness (α = r²): low a sharp highlight, 1 a broad sheen. */
  roughness: number
}

/** A Lambert finish: none of the three. */
export const LAMBERT_LOBES: FinishLobes = { diffuseRoughness: 0, sheen: 0, sheenRoughness: 1, specular: 0, roughness: 1 }

/** The floor under the sheen's `sin²θh`, Filament's: keeps the power finite where H meets N. */
export const SHEEN_SIN2_FLOOR = 0.0078125

/**
 * What a white sheen reflects of a light at incidence cosine μ, fitted to its integral at velour's
 * roughness: `SHEEN_ALBEDO_BASE + SHEEN_ALBEDO_RISE · (1 − μ)²`.
 */
export const SHEEN_ALBEDO_BASE = 0.06
export const SHEEN_ALBEDO_RISE = 0.6
/**
 * What GGX reflects of a light at incidence cosine μ, as a share of Schlick's: `F0 + (1 − F0) ·
 * SPECULAR_ALBEDO_GRAZING · (1 − μ)⁵`. The lobe's microfacets see the light nearer square on than
 * the surface does, so its albedo rises a quarter as far as Fresnel's at the surface's own angle.
 */
export const SPECULAR_ALBEDO_GRAZING = 0.25
/** Oren–Nayar's denominator term in Fujii's form, `π/2 − 2/3`. */
const FUJII_K = Math.PI / 2 - 2 / 3

/** Which lobes [lobes] compiles in. */
export function lobeDefines(lobes: FinishLobes): { orenNayar: boolean; sheen: boolean; ggx: boolean } {
  return { orenNayar: lobes.diffuseRoughness > 0, sheen: lobes.sheen > 0, ggx: lobes.specular > 0 }
}

/** Fujii's Oren–Nayar as `(πA, πB)`: the diffuse is `πA + πB · s / t`. σ 0 is `(1, 0)`, Lambert. */
export function orenNayarTerms(sigma: number): [number, number] {
  const s = Math.max(0, sigma)
  const a = Math.PI / (Math.PI + FUJII_K * s)
  return [a, s * a]
}

/**
 * Oren–Nayar (π units) for a light at [nl], an eye at [nv] and the cosine between them [lv]. `s` is
 * how far the light and the eye agree beyond what their angles to the normal say, and `t` scales it
 * so a light behind the eye brightens and one across from it does not.
 */
export function orenNayar(sigma: number, nl: number, nv: number, lv: number): number {
  const [a, b] = orenNayarTerms(sigma)
  const s = lv - nl * nv
  return a + (b * s) / (s > 0 ? Math.max(nl, nv) : 1)
}

/** The sheen's `(norm, exponent)`: π · D_charlie · V is `norm · (sin²θh)^exponent · V`. */
export function sheenTerms(r: number): [number, number] {
  const inv = 1 / Math.min(1, Math.max(r, 0.05))
  return [(2 + inv) / 2, inv / 2]
}

/** The Charlie sheen (π units, white) with Neubelt's visibility, for [nh] the normal's cosine with the half vector. */
export function charlieSheen(r: number, nl: number, nv: number, nh: number): number {
  const [norm, exponent] = sheenTerms(r)
  const sin2 = Math.max(1 - nh * nh, SHEEN_SIN2_FLOOR)
  return (norm * sin2 ** exponent) / (4 * (nl + nv - nl * nv))
}

/** A white sheen's albedo at incidence cosine [mu] ([SHEEN_ALBEDO_BASE]), times its strength by the caller. */
export function sheenAlbedo(mu: number): number {
  const m = 1 - Math.min(1, Math.max(0, mu))
  return SHEEN_ALBEDO_BASE + SHEEN_ALBEDO_RISE * m * m
}

/** GGX's albedo at incidence cosine [mu] for reflectance [f0] ([SPECULAR_ALBEDO_GRAZING]). */
export function specularAlbedo(f0: number, mu: number): number {
  const m = 1 - Math.min(1, Math.max(0, mu))
  return f0 + (1 - f0) * SPECULAR_ALBEDO_GRAZING * m * m * m * m * m
}

/** Schlick's Fresnel: what a dielectric of reflectance [f0] reflects at an incidence of cosine [c]. */
export function schlick(f0: number, c: number): number {
  const m = 1 - Math.min(1, Math.max(0, c))
  return f0 + (1 - f0) * m * m * m * m * m
}

/**
 * GGX (π units): Trowbridge–Reitz's distribution at [nh], Hammon's fit to the height-correlated
 * Smith visibility, Schlick's Fresnel at [vh]. [roughness] is perceptual; α is its square.
 */
export function ggxSpecular(f0: number, roughness: number, nl: number, nv: number, nh: number, vh: number): number {
  const a = Math.max(roughness * roughness, 1e-3)
  const a2 = a * a
  const d = nh * nh * (a2 - 1) + 1
  const vis = 0.5 / (nl * (nv * (1 - a) + a) + nv * (nl * (1 - a) + a))
  return (a2 / (d * d)) * vis * schlick(f0, vh)
}

/**
 * What [lobes] reflect of a light at [l] for an eye at [v] about the normal +z, in π units, for a
 * white finish — the diffuse (Oren–Nayar, less what GGX's Fresnel takes) plus the sheen and the
 * specular. The twin of the surface shader's per-light sum, for the tests' integrals.
 */
export function finishResponse(lobes: FinishLobes, l: readonly number[], v: readonly number[]): number {
  const nl = l[2]
  const nv = Math.max(v[2], 1e-4)
  if (nl <= 0) return 0
  const lv = l[0] * v[0] + l[1] * v[1] + l[2] * v[2]
  const hx = l[0] + v[0]
  const hy = l[1] + v[1]
  const hz = l[2] + v[2]
  const hl = Math.hypot(hx, hy, hz) || 1
  const nh = Math.max(hz / hl, 0)
  const vh = Math.max((v[0] * hx + v[1] * hy + v[2] * hz) / hl, 0)
  const on = lobeDefines(lobes)
  let diffuse = on.orenNayar ? orenNayar(lobes.diffuseRoughness, nl, nv, lv) : 1
  let gloss = 0
  if (on.sheen) {
    gloss += lobes.sheen * charlieSheen(lobes.sheenRoughness, nl, nv, nh)
    diffuse *= 1 - lobes.sheen * sheenAlbedo(nl)
  }
  if (on.ggx) {
    gloss += ggxSpecular(lobes.specular, lobes.roughness, nl, nv, nh, vh)
    diffuse *= 1 - specularAlbedo(lobes.specular, nl)
  }
  return diffuse + gloss
}

const f = (v: number) => v.toFixed(7)

/**
 * The lobes' uniforms and functions, for the surface shader after its own `uniform`s. `uOrenNayar`
 * is `(πA, πB)`; `uSheen` the sheen's colour (strength × √albedo × norm) and its exponent, and
 * `uSheenStrength` that colour's brightest channel without the norm; `uSpecular` F0 and α. Each
 * block compiles only under its `LOBE_*` define.
 */
export const LOBES_GLSL = /* glsl */ `
  #ifdef LOBE_OREN_NAYAR
  uniform vec2 uOrenNayar;
  float orenNayar(float nl, float nv, float lv) {
    float s = lv - nl * nv;
    return uOrenNayar.x + uOrenNayar.y * s / (s > 0.0 ? max(nl, nv) : 1.0);
  }
  #endif
  #ifdef LOBE_SHEEN
  uniform vec4 uSheen;
  uniform float uSheenStrength;
  vec3 charlieSheen(float nl, float nv, float nh) {
    float sin2 = max(1.0 - nh * nh, ${f(SHEEN_SIN2_FLOOR)});
    return uSheen.rgb * pow(sin2, uSheen.a) / (4.0 * (nl + nv - nl * nv));
  }
  // What the diffuse gives up to the sheen at this incidence.
  float sheenKeeps(float nl) {
    float m = 1.0 - clamp(nl, 0.0, 1.0);
    return 1.0 - uSheenStrength * (${f(SHEEN_ALBEDO_BASE)} + ${f(SHEEN_ALBEDO_RISE)} * m * m);
  }
  #endif
  #ifdef LOBE_GGX
  uniform vec2 uSpecular;
  float schlick(float f0, float c) {
    float m = 1.0 - clamp(c, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * m2 * m2 * m;
  }
  // What the diffuse gives up to the specular at this incidence.
  float specularKeeps(float nl) {
    float m = 1.0 - clamp(nl, 0.0, 1.0);
    float m2 = m * m;
    return 1.0 - uSpecular.x - (1.0 - uSpecular.x) * ${f(SPECULAR_ALBEDO_GRAZING)} * m2 * m2 * m;
  }
  float ggxSpecular(float nl, float nv, float nh, float vh) {
    float a = uSpecular.y;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float vis = 0.5 / (nl * (nv * (1.0 - a) + a) + nv * (nl * (1.0 - a) + a));
    return a2 / (d * d) * vis * schlick(uSpecular.x, vh);
  }
  #endif
`

/** The sheen's colour for a finish of linear albedo [r, g, b], before its strength: `√albedo`. */
export function sheenTint(r: number, g: number, b: number): [number, number, number] {
  return [Math.sqrt(Math.max(r, 0)), Math.sqrt(Math.max(g, 0)), Math.sqrt(Math.max(b, 0))]
}

/** The uniform values for [lobes] on a finish whose linear albedo is [r, g, b]. */
export function lobeUniformValues(lobes: FinishLobes, r: number, g: number, b: number): {
  orenNayar: [number, number]
  sheen: [number, number, number, number]
  sheenStrength: number
  specular: [number, number]
} {
  const tint = sheenTint(r, g, b)
  const [norm, exponent] = sheenTerms(lobes.sheenRoughness)
  const k = lobes.sheen * norm
  return {
    orenNayar: orenNayarTerms(lobes.diffuseRoughness),
    sheen: [tint[0] * k, tint[1] * k, tint[2] * k, exponent],
    // What the diffuse gives up is the sheen's brightest channel's share.
    sheenStrength: lobes.sheen * Math.max(...tint),
    specular: [lobes.specular, Math.max(lobes.roughness * lobes.roughness, 1e-3)],
  }
}
