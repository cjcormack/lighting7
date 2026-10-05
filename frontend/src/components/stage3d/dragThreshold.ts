/** Pointer travel that promotes a press from a click into a drag. Shared with the section edit
 *  layer's `useSectionPress`, so every camera tells a click from a drag the same way. */
export const DRAG_PX_THRESHOLD = 4

/** Whether an R3F click was one, rather than the release of a camera drag that began on the object. */
export function isClick(e: { delta: number }): boolean {
  return e.delta <= DRAG_PX_THRESHOLD
}
