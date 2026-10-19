import { createContext, useContext } from 'react'

/**
 * Which editor the programmer's dock draws (tables-colours plan D4): **Colour** or **Spread**, the
 * busk sheet's two docked editors hosted over the marquee. Held in `ProgrammerWorkspace` beside
 * `collapsed`, and never persisted: every arrival rests on Colour.
 */
export type RailTab = 'colour' | 'spread'

/** A gesture made at the grid, landed in an open tab — R focused and `seed` typed, or From focused. */
export interface RailFocusRequest {
  tab: 'colour' | 'spread'
  /** The character that opened it, `''` for Enter, Set or a double click. */
  seed: string
  /** Bumped per request, so two presses of the same key are two requests. */
  key: number
}

/**
 * What the **grid** may ask of the rail's tabs — the half of the rail's state the value grid and
 * the selection bar read, provided by `ProgrammerWorkspace` around *both* its children. Separate
 * from `RailArm` so the grid does not re-render on the rail's own gestures (overlay, sheet); this
 * changes only when the drawn tab does.
 *
 * **A tab claims its own column's open gesture** (call 9): with the Colour tab open, ⏎, Set, a
 * typed digit and a double click on a Colour cell land in the tab rather than opening a second
 * colour editor over the first; with the Spread tab open, row C's Spread focuses the tab's From.
 * Every other column and verb is untouched. Null outside the programmer — the plain lists and the
 * cue grid claim nothing.
 */
export interface RailTabClaim {
  /**
   * The tab that claims, or null: only the docked, expanded dock does. An overlay or a bottom sheet
   * shuts on a press on the grid, so there a cell's own popover is the form.
   */
  tab: RailTab | null
  /** The Colour tab takes the open: focus its R, seeded. */
  focusColour: (seed: string) => void
  /** The Spread tab takes row C's Spread: focus its From. */
  focusSpread: () => void
  /** A colour editor's *Spread…*: open the Spread tab with From set to this RGB (`SpreadSeed`'s shape). */
  spreadFrom: (from: { r: number; g: number; b: number }) => void
  /**
   * A position cell's *Spread…*: open the Spread tab on Position, in Fan, at no fan (venue-visit plan
   * S12). False where the dock does not dock — overlay mode, or push mode's narrow arm — and the
   * caller opens row C's popover instead.
   */
  spreadFan: () => boolean
}

export const RailTabClaimContext = createContext<RailTabClaim | null>(null)

/** The rail's claim on grid gestures, or null where there is no programmer rail. */
export function useRailTabClaim(): RailTabClaim | null {
  return useContext(RailTabClaimContext)
}
