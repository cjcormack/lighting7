/**
 * What a cell looks like when the current scope holds no value for it.
 *
 * One component for all four cell kinds, so "unset" cannot end up drawn four slightly different
 * ways. It replaces the *display* only — the trigger around it stays live, and the editor that
 * opens is still seeded from the live value, so clicking an em-dash starts the slider where the
 * rig actually is rather than at zero. That split is the point: the grid answers "what will Record
 * take?" honestly while a busk still begins from wherever the look currently sits.
 */
export function UnsetCellMark() {
  return (
    <span className="flex h-full w-full items-center px-1.5 text-xs text-muted-foreground/60">
      —
    </span>
  )
}

/** Hover text for an unset cell, so the em-dash is explained rather than merely absent. */
export const UNSET_CELL_TITLE = 'Nothing set here in this scope — click to set one'
