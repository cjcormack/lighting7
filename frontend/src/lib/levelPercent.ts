/**
 * A level, 0–100, as a whole percentage that never reads as an end it is not at: 99.6 is 99, not
 * 100 (the rig is dimmed), and 0.4 is 1, not 0 (a strobe still runs). The grand master's chip and
 * fader and a stack's FX size read it.
 */
export function levelPercent(level: number): number {
  if (level >= 100) return 100
  if (level <= 0) return 0
  return Math.min(99, Math.max(1, Math.round(level)))
}
