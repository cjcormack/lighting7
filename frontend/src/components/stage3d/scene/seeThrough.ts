import { scrimShare } from './scrimOpen'
import type { DrapeFabric, PartLight } from './sceneParts'

/**
 * **What a cloth does to the eye and to light from behind it** (scrim plan session 4, D6, D9): the
 * surface shader's `SCRIM` and `TRANSLUCENT` arms, as pure numbers. Three.js-free; [SEE_THROUGH_GLSL]
 * is the twin the shader runs, pinned against these by `seeThrough.test.ts` as `scrimOpen.ts`'s GLSL
 * is against its own.
 *
 * - **A scrim is a blend** (D9). A net draws without writing depth, premultiplied over what is behind
 *   it, covering [scrimCover] of each pixel: `1 − open(θ_eye)^gather`, θ_eye between the view ray and
 *   the cloth's normal — `scrimOpen.ts`'s one `open(θ)`, which also decides what a beam passes. So a
 *   gauze seen square on is about half cloth, and from the side of the house it is denser.
 * - **Its threads are round** (D9): [scrimThreadLight] is what a light's incidence passes to the
 *   threads — they still catch light that grazes the cloth ([SCRIM_THREAD_WRAP] at grazing) and glow a
 *   little with light from behind ([SCRIM_THREAD_GLOW]).
 * - **Muslin is translucent** (D6): opaque to beams and to the eye, but light from behind lights its
 *   front through [translucentTint], `τ · paint_front ⊙ paint_back` — [MUSLIN_TRANSMITTANCE] of what
 *   reaches the back, through both paints, an unpainted side counting as white. Which is the day/night
 *   cloth: front light shows the front paint, back light shows the back's openings, coloured by the
 *   front's dye.
 */

/** The share of a grazing light a net's round threads still catch (D9). **Estimate**, judged in session 6. */
export const SCRIM_THREAD_WRAP = 0.45

/** How brightly a net's threads glow with light from behind, at its full incidence (D9). **Estimate**, session 6. */
export const SCRIM_THREAD_GLOW = 0.18

/**
 * Within this cosine of grazing a front light's wrap fades in from nothing, so a light swinging
 * through the cloth's plane does not switch the threads' wrap on in one step (the design record's
 * `smoothstep(0, 0.05, f)`).
 */
export const SCRIM_WRAP_FADE = 0.05

/** τ, the share of light from behind that reaches a muslin's front through its weave (D6). **Estimate**, session 6. */
export const MUSLIN_TRANSMITTANCE = 0.45

/** GLSL's `smoothstep`. */
function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}

/**
 * How much of a pixel a net covers seen at an angle whose cosine from its normal is [cosEye] (either
 * sign), through [gather] layers of it: `1 − open(θ_eye)^gather` — the premultiplied blend's weight.
 */
export function scrimCover(cosEye: number, r: number, gather: number): number {
  return 1 - scrimShare(cosEye, r, gather)
}

/**
 * What a light's incidence passes to a net's threads, for [facing] the cosine between the face drawn
 * and the light (positive on the eye's side): a front light the threads wrap — [SCRIM_THREAD_WRAP] at
 * grazing rising to 1 square on, faded in over [SCRIM_WRAP_FADE] — and a light behind [SCRIM_THREAD_GLOW]
 * of its incidence. 0 exactly in the cloth's plane.
 */
export function scrimThreadLight(facing: number): number {
  if (facing > 0) return (SCRIM_THREAD_WRAP + (1 - SCRIM_THREAD_WRAP) * facing) * smoothstep(0, SCRIM_WRAP_FADE, facing)
  return SCRIM_THREAD_GLOW * Math.max(0, -facing)
}

/**
 * What light from behind a muslin brings to the face drawn, per unit of irradiance on its back:
 * τ times this face's paint (or finish) times the other face's paint — white where it is unpainted.
 * Zero with no light behind it, since it multiplies that irradiance; τ itself through white paint.
 */
export function translucentTint(
  tau: number,
  face: readonly [number, number, number],
  other: readonly [number, number, number] = [1, 1, 1],
): [number, number, number] {
  return [tau * face[0] * other[0], tau * face[1] * other[1], tau * face[2] * other[2]]
}

/**
 * How a part's surface is drawn, decided once when its material is made — a define, never switched
 * per frame:
 *
 * - `scrim` — a net (its light is a `Transmit` of kind `angle`): the `SCRIM` blend.
 * - `translucent` — a muslin cloth: opaque, with `TRANSLUCENT`'s back light.
 * - `opaque` — everything else, a cut cloth included: it discards its holes and writes depth elsewhere.
 *
 * Velour and canvas are opaque, so nothing behind them ever lights their front.
 */
export type SurfaceDraw = 'scrim' | 'translucent' | 'opaque'

export function surfaceDrawOf(light: PartLight, translucent: number | undefined): SurfaceDraw {
  if (typeof light === 'object' && light.kind === 'angle') return 'scrim'
  return translucent != null ? 'translucent' : 'opaque'
}

/** A drape's τ by its fabric (D6): muslin's, and none for every other fabric. */
export function fabricTranslucency(fabric: DrapeFabric): number | undefined {
  return fabric === 'MUSLIN' ? MUSLIN_TRANSMITTANCE : undefined
}

/**
 * What one light's incidence passes to the face drawn, by how the surface is drawn — the light loop's
 * first question in the surface shader, for [facing] the cosine between the face and the light
 * (positive on the eye's side). [behindCloth] is which side of the **cloth** the lamp is on: the face's
 * own sign for a flat cloth, the cloth's plane for a gathered one (`pleatFaceSeesLamp`), so a fold's
 * flank turned from a lamp in front is not lit as if from behind. Null where the light is skipped: an
 * opaque face — velour, canvas, a cut cloth — takes nothing from behind it, and nothing takes a lamp in
 * front on a flank turned away. `behind` takes no lobes and no fold shadow, meets the cloth at its
 * cosine from the back whichever way a fold leans, and on a muslin lights the face through
 * [translucentTint].
 */
export function lightIncidence(
  facing: number,
  draw: SurfaceDraw,
  behindCloth: boolean = facing <= 0,
): { incidence: number; behind: boolean } | null {
  if (draw === 'opaque') return facing > 0 ? { incidence: facing, behind: false } : null
  const behind = behindCloth
  if (!behind && facing <= 0) return null
  const cosine = behind ? Math.abs(facing) : facing
  if (draw === 'scrim') {
    const incidence = scrimThreadLight(behind ? -cosine : cosine)
    return incidence > 0 ? { incidence, behind } : null
  }
  return cosine > 0 ? { incidence: cosine, behind } : null
}

/**
 * **Draw order among the see-through things** (`Object3D.renderOrder`). Three.js draws every opaque
 * object first, then the transparent ones by this, then by distance. A scrim draws after the opaque
 * surfaces behind it (it blends over them, and writes no depth), and **before the beams**, so the
 * additive haze lands on top of it. An edit-mode rigging guide drawn faint writes no depth either, so
 * it takes the scrim's order and three sorts the two by distance: a guide hung in front of a gauze is
 * not blended over by it. The catch floor keeps 0, drawn before both (an opaque stage floor hides the
 * gauze's fragments behind it).
 */
export const SCRIM_RENDER_ORDER = 1
export const BEAM_RENDER_ORDER = 2

/** A part's `renderOrder` by how it is drawn ([surfaceDrawOf]). */
export function surfaceRenderOrder(draw: SurfaceDraw): number {
  return draw === 'scrim' ? SCRIM_RENDER_ORDER : 0
}

/**
 * The GLSL twins of [scrimCover], [scrimThreadLight] and [translucentTint]. They read `scrimShare`
 * from `scrimOpen.ts`'s `SCRIM_OPEN_GLSL`, which the surface shader already holds through
 * `occlusion.ts`'s `OCCLUSION_GLSL`: this chunk goes after it, and there is one copy of `open(θ)`.
 */
export const SEE_THROUGH_GLSL = /* glsl */ `
  #define SCRIM_THREAD_WRAP ${SCRIM_THREAD_WRAP.toFixed(4)}
  #define SCRIM_THREAD_GLOW ${SCRIM_THREAD_GLOW.toFixed(4)}
  #define SCRIM_WRAP_FADE ${SCRIM_WRAP_FADE.toFixed(4)}

  float scrimCover(float cosEye, float r, float gather) {
    return 1.0 - scrimShare(cosEye, r, gather);
  }

  float scrimThreadLight(float facing) {
    if (facing > 0.0) return (SCRIM_THREAD_WRAP + (1.0 - SCRIM_THREAD_WRAP) * facing) * smoothstep(0.0, SCRIM_WRAP_FADE, facing);
    return SCRIM_THREAD_GLOW * max(0.0, -facing);
  }

  vec3 translucentTint(float tau, vec3 face, vec3 other) {
    return tau * face * other;
  }
`
