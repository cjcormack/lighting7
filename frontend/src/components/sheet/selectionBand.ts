/**
 * Whether row C — the programmer grid's selection bar — is drawn, and with what in it.
 *
 * The bar used to be simply absent with nothing selected, which meant the first cell to enter a
 * marquee mounted it and pushed every row down by the bar's height **under a pointer that was
 * mid-drag**
 * (`PD-SELECTION-BAR-SHIFT`). It is not a flicker and the drag lands on the cells it crossed, but
 * on a drag that starts below the top row the whole grid slides while the operator is still
 * choosing.
 *
 * The fix is a hybrid, decided at the desk, and it turns on one judgement: the 40px is only saved
 * while nothing is selected, which is exactly when the grid is not being used — to interact with
 * the programmer you must first select. So on a desk the band is never absent at a moment its
 * absence helps, and it is simply always in the flow. On a **landscape phone** 40px of 393 is
 * worth keeping while reading the grid, so there the band stays out of the flow and its presence
 * is instead *held* for the duration of a drag: it arrives on pointer-up, not on the first cell.
 *
 * Held rather than merely delayed, because the shift has two directions. A drag begun while a
 * previous selection was showing must not make the band *leave* either — that pushes the rows up
 * by the same 40px, under the same pointer.
 */
export type SelectionBandState =
  /** Not in the flow at all — the short-viewport arm, with nothing selected and no drag to hold. */
  | 'absent'
  /** In the flow, holding its height, with nothing to say yet. */
  | 'reserved'
  /** In the flow with the counts, the templates and the selection actions. */
  | 'filled'

export function selectionBandState({
  shortViewport,
  heldPresence,
  hasSelection,
}: {
  /** The `(max-height: 500px)` arm — a landscape phone, where the 40px is worth reserving. */
  shortViewport: boolean
  /**
   * The presence the band had when the current marquee drag began, or null when no drag is in
   * flight. It must be the value from *before* the drag: the flag and the drag's first cells
   * arrive in one commit, so latching at that moment latches the answer the hold exists to avoid.
   */
  heldPresence: boolean | null
  /** Anything selected at all — visible rows, marquee cells, or both. */
  hasSelection: boolean
}): SelectionBandState {
  // Taller viewports reserve the height unconditionally, so nothing the selection does can move
  // the grid and the drag has nothing to be spoiled by.
  if (!shortViewport) return hasSelection ? 'filled' : 'reserved'
  const present = heldPresence ?? hasSelection
  if (!present) return 'absent'
  return hasSelection ? 'filled' : 'reserved'
}
