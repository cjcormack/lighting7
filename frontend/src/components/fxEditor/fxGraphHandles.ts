import { SPEED_STEPS } from '@/components/fx/fxConstants'
import { MAX_SPREAD, MAX_STEP_SPREAD } from '@/lib/spreadShape'
import type { FxPreviewFx } from '@/store/fxPreview'
import { SAMPLES_PER_CYCLE } from './fxGraphModel'

/**
 * **The graph's handles, as numbers** (FX editor plan D12, §4.4): what a drag of each handle writes,
 * from where the drag began. Each is measured from the gesture's start rather than from the latest
 * answer, since the answer redraws under the finger as the preview follows the draft. Pure; the
 * fields they write are the inspector's, and the desk shapes the FX from them (P2).
 *
 * A cycle is [SAMPLES_PER_CYCLE] samples of the picked FX, whatever the window. A member's own clock
 * is the FX's less its offset plus its Phase (`FxPhase.own`), so a larger offset starts its cycles
 * later and a larger Phase starts them sooner.
 */

/**
 * The cycle step nearest [beats], by ratio: Cycle's handle snaps to the steps the segment offers.
 * [also] joins the steps — the cycle the drag began on, so a cycle off the eight (a triplet, 32
 * beats) is kept until the drag leaves it for a step.
 */
export function snapCycle(beats: number, also?: number): number {
  const steps = also == null ? SPEED_STEPS.map((s) => s.value) : [...SPEED_STEPS.map((s) => s.value), also]
  let best = steps[0]
  for (const value of steps) {
    if (Math.abs(Math.log(value / beats)) < Math.abs(Math.log(best / beats))) best = value
  }
  return best
}

/** A cycle in seconds' range and resolution — the Cycle field's (0.05 s to 600 s). */
const SECONDS_STEP = 0.05
const SECONDS_MAX = 600

/**
 * Cycle from the first cycle's right edge, dragged from [edgeX] to [x]: the cycle scaled by how far
 * the edge moved from the plot's left. A cycle in beats snaps to a step, or stays where it began; one
 * in seconds (a wall-clock FX, or one on *Seconds*) has no steps, and moves to the nearest 0.05 s.
 */
export function cycleFromDrag(startCycle: number, plotLeft: number, edgeX: number, x: number, seconds = false): number {
  const span = edgeX - plotLeft
  if (!(span > 0) || !(startCycle > 0)) return startCycle
  const scaled = (startCycle * Math.max(1, x - plotLeft)) / span
  if (seconds) return Math.min(SECONDS_MAX, Math.max(SECONDS_STEP, Math.round(Math.round(scaled / SECONDS_STEP) * SECONDS_STEP * 100) / 100))
  return snapCycle(scaled, startCycle)
}

/**
 * How far a row's offset moves for each unit of Spread: its rank over the rank count once Parts has
 * acted, from the desk's ladder (offset = Spread × that share) — over the FX's own [steps] instead
 * under a `STEP` spread (fx-settings plan D6), as the desk divides. Null where the row has no rung.
 */
export function spreadShareOf(fx: Pick<FxPreviewFx, 'ladder'>, row: number, steps: number | null = null): number | null {
  const rank = fx.ladder.parts.ranks[row]
  const count = fx.ladder.parts.counts[row]
  if (rank == null || count == null || count <= 0) return null
  return rank / (steps != null ? Math.max(steps, 1) : count)
}

/**
 * Spread from the chase line, a row's start tick dragged [dxSamples] to the right: the row's start
 * follows the finger, so Spread moves by the drag over the row's share of a cycle. Clamped to the
 * desk's range — 0–2, or 0–16 when it counts steps — to a hundredth.
 */
export function spreadFromDrag(startSpread: number, dxSamples: number, share: number, inSteps = false): number {
  if (!(share > 0)) return startSpread
  const next = startSpread + dxSamples / (SAMPLES_PER_CYCLE * share)
  return Math.round(Math.max(0, Math.min(inSteps ? MAX_STEP_SPREAD : MAX_SPREAD, next)) * 100) / 100
}

/**
 * Phase from a row block dragged [dxSamples] to the right: the starts follow the finger, so Phase
 * falls by the drag in cycles. A whole cycle in the offset (Bounce's parity) is kept, as the Phase
 * field keeps it; the part within the cycle wraps, to a whole degree.
 */
export function phaseFromDrag(startPhase: number, dxSamples: number): number {
  const cycles = Math.floor(startPhase)
  const within = startPhase - cycles - dxSamples / SAMPLES_PER_CYCLE
  const degrees = Math.round((((within % 1) + 1) % 1) * 360) % 360
  return cycles + degrees / 360
}
