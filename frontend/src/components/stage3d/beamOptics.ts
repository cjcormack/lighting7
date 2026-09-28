import type {
  SettingOption,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
} from '../../store/fixtures'
import { MAX_PRISM_LOBES } from './emitterLayout'
import { GOBO_SLOT_COUNT, goboLayerFor } from './goboPatterns'

/**
 * Pure decoding of the beam-shaping DMX channels into renderer inputs.
 *
 * Everything here is a total function of (descriptor, raw DMX value) — and of an
 * elapsed time where motion is involved, always passed in, never read from a
 * clock. That keeps the frame loop allocation-free, keeps the profile harness
 * reproducible, and makes the whole module unit-testable without a renderer.
 */

export { GOBO_SLOT_COUNT }

const TAU = Math.PI * 2

/** Raw DMX value of a slider- or setting-backed single-byte channel. */
export type ByteDescriptor = SliderPropertyDescriptor | SettingPropertyDescriptor

/**
 * The band containing `level`, as [start, end] inclusive.
 *
 * Setting options carry only a *start* level; a band runs until the next option
 * begins, and the last runs to 255. Deriving it here rather than adding an end
 * to every fixture enum keeps 28 Kotlin files untouched, and the derivation is
 * exactly the kind of off-by-one that deserves a test rather than being inlined.
 *
 * Options are assumed level-ascending, which is how the backend sorts them.
 */
export function settingBand(
  options: readonly SettingOption[],
  level: number,
): { start: number; end: number; index: number } {
  if (options.length === 0) return { start: 0, end: 255, index: -1 }
  let index = 0
  for (let i = options.length - 1; i >= 0; i--) {
    if (level >= options[i].level) {
      index = i
      break
    }
  }
  const start = options[index].level
  const end = index + 1 < options.length ? options[index + 1].level - 1 : 255
  return { start, end, index }
}

/** Fraction (0..1) of the way through the band containing `level`. */
function bandFraction(options: readonly SettingOption[], level: number): number {
  const { start, end } = settingBand(options, level)
  if (end <= start) return 0
  return Math.max(0, Math.min(1, (level - start) / (end - start)))
}

// Option names meaning "this position does nothing" — open gate, no gobo, no
// prism, rotation stopped. Matched case-insensitively after stripping digits so
// OPEN_WHITE_2 and ROTATION_STOP_2 fall in with their unsuffixed siblings.
const NO_OP_NAMES = new Set([
  'OPEN',
  'OPENWHITE',
  'WHITE',
  'NOFUNCTION',
  'NONE',
  'OFF',
  'ROTATIONSTOP',
  'STOP',
  'NOROT',
  'NOROTATION',
  'PRISMOFF',
])

function normaliseOptionName(name: string): string {
  return name.toUpperCase().replace(/[^A-Z]/g, '')
}

function isNoOpOption(name: string | undefined): boolean {
  if (!name) return true
  return NO_OP_NAMES.has(normaliseOptionName(name))
}

function optionsOf(prop: ByteDescriptor): readonly SettingOption[] {
  return prop.type === 'setting' ? prop.options : []
}

// Module-level predicates: these run once per fixture per frame inside
// useFrame, and an inline arrow there would allocate a closure per call —
// the exact class of churn this module's header rules out.
function hasGoboAnnotation(o: SettingOption): boolean {
  return o.gobo != null
}

function hasPrismAnnotation(o: SettingOption): boolean {
  return o.prismFacets != null
}

/**
 * Gobo slot for the current wheel position: 0 = open, 1..GOBO_SLOT_COUNT-1 = a
 * pattern layer.
 *
 * Prefers the pattern *name* the backend declares on the option, resolved
 * through the registry in `goboPatterns.ts`. On a wheel where any option is
 * named, a nameless option means "deliberately open" (scroll/rainbow bands) and
 * an unknown name means "vocabulary newer than this build" — both render open
 * rather than guessing. Only a wholly unannotated wheel falls back to the
 * option's index, because fixture enums are wildly inconsistent — the Fusion
 * names them GOBO_1..GOBO_5 but the Martin MAC 250 uses descriptive names
 * (FIBROID, DEC_BEAM, CONE_SHAKE) with no numbers at all. Index works for both,
 * and which pattern an unannotated slot maps to is arbitrary anyway.
 */
export function resolveGoboSlot(prop: ByteDescriptor | undefined, level: number): number {
  if (!prop) return 0
  const options = optionsOf(prop)
  if (options.length === 0) {
    // Slider-backed wheel: spread the range over the available slots.
    if (level <= 0) return 0
    return 1 + Math.floor((level / 256) * (GOBO_SLOT_COUNT - 1))
  }
  const { index } = settingBand(options, level)
  if (index < 0) return 0
  const option = options[index]
  const named = goboLayerFor(option.gobo)
  if (named != null) return named
  if (options.some(hasGoboAnnotation)) return 0
  if (isNoOpOption(option.name)) return 0
  return ((index - 1 + GOBO_SLOT_COUNT - 1) % (GOBO_SLOT_COUNT - 1)) + 1
}

// A slider-backed spin channel almost universally means: 0 stop, 1..127 forward
// slow→fast, 128..255 reverse slow→fast. Documented verbatim on the Varytec
// (`Gobo spin (0 stop, 1–127 forward, 128–255 reverse)`). The Martin is really a
// three-band CCW/stop/CW and the Robe's meaning depends on the wheel mode, so
// this is an approximation for those two — acceptable for a visualiser.
const MAX_SPIN_REV_PER_SEC = 1.5

function spinFromSlider(level: number): number {
  if (level <= 0) return 0
  if (level < 128) return (level / 127) * MAX_SPIN_REV_PER_SEC
  return -((level - 128) / 127) * MAX_SPIN_REV_PER_SEC
}

/**
 * Unit-scale (0..1, signed) spin decoded from a setting channel's bands. The
 * *band* names the direction and speed class and the position within the band
 * interpolates. Note the Fusion orders its bands fast-before-slow
 * (FORWARD_ROTATION_FAST at 10, FORWARD_ROTATION_SLOW at 129), so band
 * identity has to come from the name, not from its position.
 */
function spinFromSettingBands(options: readonly SettingOption[], level: number): number {
  const { index } = settingBand(options, level)
  if (index < 0) return 0
  const name = normaliseOptionName(options[index].name ?? '')
  if (isNoOpOption(options[index].name)) return 0

  const reverse = name.includes('REVERSE') || name.includes('CCW')
  const slow = name.includes('SLOW')
  const fast = name.includes('FAST')
  // Within-band position gives a continuous ramp; a band that names neither
  // speed spans the whole range.
  const t = bandFraction(options, level)
  const base = slow ? 0.15 + 0.25 * t : fast ? 0.5 + 0.5 * t : 0.2 + 0.8 * t
  return (reverse ? -1 : 1) * base
}

/** Signed gobo rotation speed in revolutions per second. Positive is forward. */
export function resolveGoboSpin(prop: ByteDescriptor | undefined, level: number): number {
  if (!prop) return 0
  const options = optionsOf(prop)
  if (options.length === 0) return spinFromSlider(level)
  return spinFromSettingBands(options, level) * MAX_SPIN_REV_PER_SEC
}

/**
 * Default prism facet count when a fixture predates the metadata: 0 = prism
 * out. Every annotated prism in the library is 3-facet, so the legacy fallback
 * matches the metadata for all known fixtures.
 */
export const PRISM_FACETS = 3

/**
 * Facet count of the prism currently in the beam; 0 = prism out. Clamped to
 * `MAX_PRISM_LOBES` — the renderer allocates exactly that many lobes per
 * fixture, so a larger return value would index into the next slot's block.
 *
 * Prefers `prismFacets` declared on the option. On an annotated wheel a null
 * option means "prism out" (the OFF bands); only a wholly unannotated wheel
 * falls back to the no-op-name heuristic with the default facet count.
 */
export function resolvePrismFacets(prop: ByteDescriptor | undefined, level: number): number {
  if (!prop) return 0
  const options = optionsOf(prop)
  if (options.length === 0) return level >= 8 ? PRISM_FACETS : 0
  const { index } = settingBand(options, level)
  if (index < 0) return 0
  const option = options[index]
  if (option.prismFacets != null) {
    return Math.max(0, Math.min(MAX_PRISM_LOBES, option.prismFacets))
  }
  if (options.some(hasPrismAnnotation)) return 0
  if (isNoOpOption(option.name)) return 0
  return PRISM_FACETS
}

// Prisms visibly rotate slower than gobo wheels; a full-speed prism at gobo
// speed reads as a blur rather than three orbiting images.
const MAX_PRISM_REV_PER_SEC = 0.8

/**
 * The Robe ColorSpot 575's prism-rotation slider curve, documented in the
 * personality: 0 no rotation, 1–127 CW fast→slow, 128–129 stop, 130–255 CCW
 * slow→fast. Note both halves run opposite ways — this is NOT the gobo-spin
 * slider convention (slow→fast in both directions). The Robe is the only
 * slider-backed prism rotation in the library, so this "approximation" is
 * currently exact; for anything else it's the documented best guess.
 */
export function prismSpinFromSlider(level: number): number {
  if (level <= 0) return 0
  if (level < 128) return MAX_PRISM_REV_PER_SEC * (1 - (level - 1) / 126)
  if (level <= 129) return 0
  return -MAX_PRISM_REV_PER_SEC * ((level - 130) / 125)
}

/**
 * Signed prism rotation in revolutions per second; positive is CW.
 *
 * Prefers a dedicated `prism_rotation` channel: setting-backed decodes by band
 * name exactly like gobo spin (the hook for per-fixture curves), slider-backed
 * uses the Robe curve. With no dedicated channel, falls back to rotation bands
 * folded into the prism wheel itself (MAC 250: ROT_CCW / NO_ROT / ROT_CW mixed
 * in with PRISM_OFF and MACRO_* bands — only the explicit ROT bands spin).
 */
export function resolvePrismSpin(
  rotProp: ByteDescriptor | undefined,
  rotLevel: number,
  prismProp: ByteDescriptor | undefined,
  prismLevel: number,
): number {
  if (rotProp) {
    const options = optionsOf(rotProp)
    if (options.length === 0) return prismSpinFromSlider(rotLevel)
    return spinFromSettingBands(options, rotLevel) * MAX_PRISM_REV_PER_SEC
  }

  if (!prismProp) return 0
  const options = optionsOf(prismProp)
  if (options.length === 0) return 0
  const { index } = settingBand(options, prismLevel)
  if (index < 0) return 0
  const raw = options[index].name ?? ''
  const name = normaliseOptionName(raw)
  // Macro bands do rotate on the real fixture, but each macro is a different
  // canned program; without per-macro data, a spinning guess looks worse than
  // a static split.
  if (isNoOpOption(raw) || !name.includes('ROT') || name.includes('MACRO')) return 0
  const reverse = name.includes('CCW') || name.includes('REVERSE')
  const t = bandFraction(options, prismLevel)
  return (reverse ? -1 : 1) * MAX_PRISM_REV_PER_SEC * (0.2 + 0.8 * t)
}

/**
 * Focus as a 0..1 parameter, or `null` when the fixture has no focus channel
 * (the caller then keeps the fixture type's static beam edge).
 */
export function resolveFocusParam(
  prop: SliderPropertyDescriptor | undefined,
  level: number,
): number | null {
  if (!prop) return null
  const span = prop.max - prop.min
  if (span <= 0) return null
  return Math.max(0, Math.min(1, (level - prop.min) / span))
}

/** "Always in focus" sentinel — a fixture with no focus channel renders with
 *  zero defocus everywhere, which is byte-identical to the pre-focal look. */
export const FOCUS_ALWAYS_SHARP = -1

// Focal-plane curve endpoints as fractions of the beam length. Real fixtures
// rack from a couple of metres to the full throw, with most of the *useful*
// travel at distance — the quadratic gives finer control where the pool
// actually lives.
export const FOCUS_NEAR_FRAC = 0.15

/**
 * Focal-plane distance (metres along the throw) for a focus channel value, or
 * [FOCUS_ALWAYS_SHARP] when the fixture has no focus channel. The pool and
 * volume shaders blur the gobo and soften the rim by how far a surface or
 * sample sits from this plane.
 */
export function resolveFocusDistance(focusParam: number | null, beamLength: number): number {
  if (focusParam == null) return FOCUS_ALWAYS_SHARP
  const p = Math.max(0, Math.min(1, focusParam))
  return beamLength * (FOCUS_NEAR_FRAC + (1 - FOCUS_NEAR_FRAC) * p * p)
}

/** Macro program index; 0 = no macro running. */
export function resolveMacroIndex(prop: ByteDescriptor | undefined, level: number): number {
  if (!prop) return 0
  const options = optionsOf(prop)
  if (options.length === 0) return level >= 8 ? 1 : 0
  const { index } = settingBand(options, level)
  if (index < 0 || isNoOpOption(options[index].name)) return 0
  return index
}

/**
 * Pan/tilt offset in degrees for a movement macro, as a pure function of the
 * program index and elapsed seconds. Amplitudes are deliberately modest — this
 * is "the channel is visibly doing something", not a claim to match the
 * fixture's real built-in program.
 */
export interface MacroMovement {
  panDeg: number
  tiltDeg: number
}

const MACRO_PERIOD_S = 6

export function evalMovementMacro(index: number, t: number, out: MacroMovement): MacroMovement {
  if (index <= 0) {
    out.panDeg = 0
    out.tiltDeg = 0
    return out
  }
  const phase = (TAU * t) / MACRO_PERIOD_S
  switch (index % 4) {
    case 1: // circle
      out.panDeg = 25 * Math.cos(phase)
      out.tiltDeg = 18 * Math.sin(phase)
      break
    case 2: // figure eight
      out.panDeg = 30 * Math.sin(phase)
      out.tiltDeg = 15 * Math.sin(2 * phase)
      break
    case 3: // horizontal sweep
      out.panDeg = 40 * Math.sin(phase)
      out.tiltDeg = 0
      break
    default: // slow nod
      out.panDeg = 0
      out.tiltDeg = 22 * Math.sin(phase)
      break
  }
  return out
}

/**
 * Hue rotation (0..1) and intensity scale for an LED macro program. Applied on
 * top of the fixture's base colour rather than replacing it, so a macro that
 * stops leaves the underlying colour intact.
 */
export interface MacroColour {
  hueShift: number
  intensityScale: number
}

export function evalLedMacro(index: number, t: number, out: MacroColour): MacroColour {
  if (index <= 0) {
    out.hueShift = 0
    out.intensityScale = 1
    return out
  }
  const phase = (TAU * t) / MACRO_PERIOD_S
  switch (index % 3) {
    case 1: // slow colour cycle
      out.hueShift = (t / MACRO_PERIOD_S) % 1
      out.intensityScale = 1
      break
    case 2: // pulse, colour held
      out.hueShift = 0
      out.intensityScale = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(phase))
      break
    default: // cycle plus pulse
      out.hueShift = (t / (MACRO_PERIOD_S * 2)) % 1
      out.intensityScale = 0.5 + 0.5 * (0.5 + 0.5 * Math.sin(phase * 2))
      break
  }
  return out
}

/**
 * Beam cone trig, recomputed whenever the effective beam angle changes. Zoom
 * makes the angle a per-frame value, so this can no longer be a React memo;
 * it's a mutable struct the director owns and refreshes behind a dirty check.
 *
 * `cullSlackRad` widens the cull half-angle so cookies fade in before the
 * shader's own cosAngle test would clip them.
 */
export interface BeamGeom {
  beamDeg: number
  beamRadius: number
  cosHalfBeam: number
  cosCull: number
  sinCull: number
  floorSide: number
}

export function makeBeamGeom(): BeamGeom {
  return {
    beamDeg: Number.NaN,
    beamRadius: 0,
    cosHalfBeam: 1,
    cosCull: 1,
    sinCull: 0,
    floorSide: 0,
  }
}

export function computeBeamGeom(
  beamDeg: number,
  beamLength: number,
  cullSlackRad: number,
  out: BeamGeom,
): BeamGeom {
  const half = (beamDeg * Math.PI) / 360
  const cull = half + cullSlackRad
  out.beamDeg = beamDeg
  out.beamRadius = beamLength * Math.tan(half)
  out.cosHalfBeam = Math.cos(half)
  out.cosCull = Math.cos(cull)
  out.sinCull = Math.sin(cull)
  out.floorSide = 2 * beamLength * Math.sin(cull)
  return out
}
