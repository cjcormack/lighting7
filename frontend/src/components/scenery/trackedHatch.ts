/**
 * The tracked hatch: a dashed border over diagonal hairlines, the desk's mark for a scenery row that
 * only **inherits** its state — a cue card's tracked rows (`CueSceneryReadout`), and a programmer scenery
 * row the scope does not hold (scenery-programmer plan §4). One string, so the two cannot drift.
 */
export const TRACKED_HATCH_CLASS =
  'border border-dashed [background-image:repeating-linear-gradient(135deg,transparent_0_6px,var(--color-muted)_6px_7px)]'
