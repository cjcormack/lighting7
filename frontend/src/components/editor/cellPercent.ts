/** The cell's unit: a byte as the percent it reads on the grid. */
export function toPct(value: number): number {
  return Math.round((value / 255) * 100)
}

/** The field's unit back to the rig's: a percent as a byte, before the resolution's clamp. */
export function fromPct(pct: number): number {
  return Math.round((pct / 100) * 255)
}
