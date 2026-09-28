import { useMemo, useState } from 'react'
import { useDndMonitor } from '@dnd-kit/core'
import { isSlotTarget } from './slotDrop'

/**
 * Is the drag in flight currently over an FX cue slot?
 *
 * The busk view's library palette needs this to dim the rows a slot cannot take, and the palette is
 * in the routed page while the slots are in the header — so the signal has to cross. It is a
 * `useDndMonitor` rather than state on `DeskDndContext` deliberately: a boolean on the context
 * would re-render the whole routed page every time the pointer crossed a slot boundary, and
 * `useDndContext()` re-renders at pointer rate. `onDragOver` fires only when the `over` droppable
 * *changes*, and the flip guard collapses slot-to-slot moves, so only the caller re-renders and
 * only twice per hover.
 *
 * Must be called inside the app's `DndContext` (`DeskDndProvider`, mounted in `Layout.tsx`).
 */
export function useCueSlotHover(): boolean {
  const [hovered, setHovered] = useState(false)

  const listeners = useMemo(
    () => ({
      onDragOver(event: { over: { data: { current?: unknown } } | null }) {
        const next = isSlotTarget(event.over?.data.current)
        setHovered((prev) => (prev === next ? prev : next))
      },
      onDragEnd() {
        setHovered(false)
      },
      onDragCancel() {
        setHovered(false)
      },
    }),
    [],
  )

  useDndMonitor(listeners)
  return hovered
}
