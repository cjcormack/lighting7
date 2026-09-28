import { createContext, useCallback, useContext, useState } from 'react'
import {
  closestCenter,
  DndContext,
  DragOverlay,
  MeasuringStrategy,
  PointerSensor,
  pointerWithin,
  useSensor,
  useSensors,
  type Active,
  type CollisionDetection,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import { useCurrentProjectQuery } from '../../store/projects'
import { useAssignCueSlotMutation, useSwapCueSlotsMutation } from '../../store/cueSlots'
import type { CueSlotSwapDragData } from '../cueSlotShared'
import { isSlotTarget, slotAssignmentFor } from './slotDrop'
import { renderDragOverlay } from './dragOverlayRegistry'
import { useEdgeDragReceiver, useEdgeDragSource } from './useEdgeDrag'

/**
 * The app's **one** drag context.
 *
 * It was `CueSlotDndProvider`, and it is not about cue slots any more: `Layout.tsx` mounts it
 * around both the FX cue-slot overlay in the header *and* the routed page, and the busk layout
 * joins it rather than nesting one of its own. Nesting is the thing to avoid — an inner
 * `DndContext` wins for its subtree, so a drag started on a busk pad would never see the overlay's
 * slot droppables, which is exactly the gesture `busk-layout-plan.md` D7 needs.
 *
 * A surface participates by calling `useDndMonitor()` somewhere inside, and by making its drag ids
 * recognisable to itself and foreign to everyone else — `parseBuskDragId` answers null for a
 * `slot-…` id and the handler below ignores anything that is not one. **No handler prop needs
 * adding here for that**: `DndContext` dispatches to its monitor bus whether or not the matching
 * prop is passed, so a monitor receives `onDragOver` even though this provider passes none.
 *
 * The ignorance is of **ids and foreign targets, not of sources**. A drop onto a cue slot is
 * resolved here whatever lifted it — a sibling slot, or a `busk-palette` row from the busk view's
 * library — because this provider owns the slot droppables (mounted on every route the app is
 * drawn on: an immersive window takes the cue-slot overlay with the other three panels, busk-chrome
 * plan D7, so there a palette row has no slot to land on), `projectId`, and both slot mutations. The alternative was a monitor inside the cue-slot panel,
 * and that panel's body unmounts when the overlay hides: the one place a slot mutation must not
 * live. `slotDrop.ts` holds the source→assignment mapping as pure functions, imported type-only
 * from the busk feature, so the shell still reaches no busk runtime code.
 */
interface DeskDndContextValue {
  /**
   * Whether *any* drag is in flight. Lives on the provider rather than in the cue-slot panel,
   * because the panel body is what it exists to keep mounted — unmounting it mid-drag would take
   * every slot droppable with it and the drop would resolve against nothing.
   */
  isDragging: boolean
}

const DeskDndContext = createContext<DeskDndContextValue>({ isDragging: false })

export function useDeskDnd() {
  return useContext(DeskDndContext)
}

/**
 * Pointer-first, centre-second.
 *
 * dnd-kit's default is `rectIntersection`, which compares the *dragged* rect against each droppable
 * — fine for a grid of eight equal cells, wrong for a page of very unequal ones. A 20px column
 * gutter can never win an area contest against the 300px column beside it, and `rectIntersection`
 * answers nothing at all in the gaps between banks. `pointerWithin` asks "is the pointer inside
 * this box", which makes a gutter and a column equally winnable; `closestCenter` catches the gaps,
 * and would be the only answer for a keyboard sensor, which has no pointer to be within anything.
 *
 * The fallback is cut to **one** collision. `closestCenter` answers with every droppable, nearest
 * first, and a consumer that reads the whole list — the busk resolver takes the deepest thing in
 * it — would then be looking at a pad three banks away while `over`, the nearest, lit a gutter: a
 * highlight on one place and a slot in another, and a drop that did neither. `pointerWithin`'s list
 * is left whole, because everything in it contains the pointer and the nesting is the information.
 */
const collisionDetection: CollisionDetection = (args) => {
  const pointer = pointerWithin(args)
  return pointer.length > 0 ? pointer : closestCenter(args).slice(0, 1)
}

/**
 * The busk page changes its own geometry mid-drag — a drop slot opens, the "stack under" strips
 * appear — and droppables are otherwise measured once, at drag start. `Always` lifts the
 * "not while dragging" gate; it is **not** a timer. dnd-kit measures on demand — when the set of
 * droppables changes, or a droppable's own ResizeObserver fires — so a surface that moves things
 * without resizing them has to ask, which `BuskEditProvider` does through
 * `useDndContext().measureDroppableContainers` each time its slot moves.
 */
const measuring = { droppable: { strategy: MeasuringStrategy.Always } }

interface DeskDndProviderProps {
  children: React.ReactNode
}

export function DeskDndProvider({ children }: DeskDndProviderProps) {
  const { data: currentProject } = useCurrentProjectQuery()
  const projectId = currentProject?.id
  const [assignSlot] = useAssignCueSlotMutation()
  const [swapSlots] = useSwapCueSlotsMutation()
  const [active, setActive] = useState<Active | null>(null)
  const [draggedLabel, setDraggedLabel] = useState<string | null>(null)
  const [isDragging, setIsDragging] = useState(false)

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }))

  /**
   * The same-machine edge drag (`useEdgeDrag.ts`, rule in `edgeDrag.ts`).
   *
   * Both halves hang here because this is the app's one drag context and it is mounted once, which
   * is what gives the page exactly one `BroadcastChannel` object — the invariant that stops the
   * sending window claiming its own release. The receiver is live on every route and whether or not
   * anything is being dragged: it is the *other* window's gesture that it answers.
   */
  const { armEdgeDrag, disarmEdgeDrag, edgeDragHandedOff } = useEdgeDragSource()
  useEdgeDragReceiver()

  const handleDragStart = useCallback(
    (event: DragStartEvent) => {
      setIsDragging(true)
      setActive(event.active)
      const data = event.active.data.current
      // A palette row brings its own overlay through `dragOverlayRegistry`; only a slot-to-slot drag
      // needs the plain label below.
      if (data?.type === 'slot-item') {
        setDraggedLabel((data as CueSlotSwapDragData).slot.itemName)
      }
      armEdgeDrag(event.active)
    },
    [armEdgeDrag],
  )

  const clearDrag = useCallback(() => {
    setIsDragging(false)
    setDraggedLabel(null)
    setActive(null)
    // Stands down unless this drag has already handed off — the hand-off *is* a cancel, and the
    // release it still owes has not happened yet. See `useEdgeDragSource`.
    disarmEdgeDrag()
  }, [disarmEdgeDrag])

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      clearDrag()
      if (!projectId) return

      // A drag that has been handed to the hand is over as far as this window is concerned, even
      // though the operator is still holding the button. Cancelling it is what normally stops this
      // handler running at all — but that cancel goes through dnd-kit's own sensor, so if a future
      // version ever declined it the drop would *also* resolve here and the record would be both in
      // the hand and assigned to a slot. One line, and the cancel stops being load-bearing.
      if (edgeDragHandedOff()) return

      const { active: dragged, over } = event
      if (!over) return

      const activeData = dragged.data.current
      const overData = over.data.current
      if (!activeData || !overData) return

      // Only handle drops onto slot targets — everything else belongs to another surface's monitor.
      if (!isSlotTarget(overData)) return

      const targetPage = overData.page
      const targetIndex = overData.slotIndex

      // A library palette row → slot assignment. Null for anything a slot cannot hold (a template,
      // a Look that needs a selection), and the drop is then a no-op — the tile drew a refusal ring
      // on the way in, so the operator has already been told why.
      const assignment = slotAssignmentFor(activeData)
      if (assignment) {
        assignSlot({ projectId, page: targetPage, slotIndex: targetIndex, ...assignment })
        return
      }

      // Slot → slot swap/move
      if (activeData.type === 'slot-item') {
        const { page: fromPage, slotIndex: fromIndex } = activeData as CueSlotSwapDragData
        if (fromPage === targetPage && fromIndex === targetIndex) return
        swapSlots({
          projectId,
          fromPage,
          fromSlotIndex: fromIndex,
          toPage: targetPage,
          toSlotIndex: targetIndex,
        })
      }
    },
    [projectId, assignSlot, swapSlots, clearDrag, edgeDragHandedOff],
  )

  return (
    <DeskDndContext.Provider value={{ isDragging }}>
      {/* `onDragCancel` matters more than it looks: a cancelled drag (Escape, a lost pointer)
          never reaches `onDragEnd`, so without it both the overlay and `isDragging` would stay
          set — and `isDragging` is what holds the cue-slot panel body mounted. */}
      <DndContext
        sensors={sensors}
        collisionDetection={collisionDetection}
        measuring={measuring}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
        onDragCancel={clearDrag}
      >
        {children}
        <DragOverlay dropAnimation={null}>
          {renderDragOverlay(active) ??
            (draggedLabel ? (
              <div className="rounded-md border bg-background px-2 py-1.5 shadow-lg text-sm font-medium opacity-90">
                {draggedLabel}
              </div>
            ) : null)}
        </DragOverlay>
      </DndContext>
    </DeskDndContext.Provider>
  )
}
