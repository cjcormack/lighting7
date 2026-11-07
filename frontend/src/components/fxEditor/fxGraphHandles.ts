import { clampSpeed, cycleOf, speedOf } from '@/lib/fxSpeed'
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
 * The Speed handle's cycle for [cycle] (fx-speed plan §4): the nearest power of two on the log
 * scale, as the slider's snaps, written in whole ticks (seconds to the millisecond on the wall
 * clock). [also] joins them — the cycle the drag began on, so a Speed off the snaps (a triplet's ×⅔)
 * is kept until the drag leaves it for one.
 */
export function snapCycle(cycle: number, wallClock = false, also?: number): number {
  const speed = clampSpeed(speedOf(cycle, wallClock))
  const snapped = cycleOf(2 ** Math.round(Math.log2(speed)), wallClock)
  if (also == null) return snapped
  return Math.abs(Math.log(also / cycle)) < Math.abs(Math.log(snapped / cycle)) ? also : snapped
}

/**
 * The cycle from the first cycle's right edge, dragged from [edgeX] to [x]: the cycle scaled by how
 * far the edge moved from the plot's left, snapped to a power of two's Speed or kept where it began.
 * [seconds] is a wall-clock FX's cycle; an Own tempo's is beats.
 */
export function cycleFromDrag(startCycle: number, plotLeft: number, edgeX: number, x: number, seconds = false): number {
  const span = edgeX - plotLeft
  if (!(span > 0) || !(startCycle > 0)) return startCycle
  const scaled = (startCycle * Math.max(1, x - plotLeft)) / span
  return snapCycle(scaled, seconds, startCycle)
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
