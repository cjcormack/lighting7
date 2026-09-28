import type { ReactNode } from 'react'
import type { Active } from '@dnd-kit/core'

/**
 * What the app's single `<DragOverlay>` should draw for a given drag.
 *
 * There is exactly one `DndContext` in the app (see `DeskDndProvider`), and a `DragOverlay`
 * registers itself on that context — so two of them would fight over one ref, and collision
 * detection reads the overlay's rect. One overlay it is, with the content dispatched here.
 *
 * A surface registers at **module scope**, which is the point: the app shell renders the overlay
 * without its import graph ever reaching the surface's components. A renderer answers null for a
 * drag it does not own, and the first non-null wins; the provider's plain label chip is the
 * fallback when none does.
 */
export type DragOverlayRenderer = (active: Active) => ReactNode | null

const renderers: DragOverlayRenderer[] = []

export function registerDragOverlay(render: DragOverlayRenderer): void {
  renderers.push(render)
}

export function renderDragOverlay(active: Active | null): ReactNode | null {
  if (active == null) return null
  for (const render of renderers) {
    const node = render(active)
    if (node != null) return node
  }
  return null
}
