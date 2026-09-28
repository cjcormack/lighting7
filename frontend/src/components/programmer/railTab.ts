import { createContext, useContext } from 'react'

/**
 * Which face the programmer rail draws (editor-kit plan session 4, `RailTabs.dc.html`): **Stack**
 * — the layers and effects, the rail as it always was — **Colour** or **Spread**, the busk
 * sheet's two docked editors hosted over the marquee.
 *
 * Held in `ProgrammerWorkspace` beside `collapsed`, and **never persisted** (call 10): every
 * arrival rests on Stack, because a rail that opened on a picker for a marquee that does not exist
 * yet would be a panel saying nothing.
 */
export type RailTab = 'stack' | 'colour' | 'spread'

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
  /** The tab on screen: `'stack'` whenever the rail is not docked, since only the docked arm draws the tabs. */
  tab: RailTab
  /** The Colour tab takes the open: focus its R, seeded. */
  focusColour: (seed: string) => void
  /** The Spread tab takes row C's Spread: focus its From. */
  focusSpread: () => void
  /** A colour editor's *Spread…*: open the Spread tab with From set to this RGB (`SpreadSeed`'s shape). */
  spreadFrom: (from: { r: number; g: number; b: number }) => void
}

export const RailTabClaimContext = createContext<RailTabClaim | null>(null)

/** The rail's claim on grid gestures, or null where there is no programmer rail. */
export function useRailTabClaim(): RailTabClaim | null {
  return useContext(RailTabClaimContext)
}
