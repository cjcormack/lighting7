import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import {
  useDndContext,
  useDndMonitor,
  type DragEndEvent,
  type DragMoveEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import type { BuskPage } from '@/api/buskApi'
import { applyDrop, type DropTarget } from '@/lib/buskLayout'
import { useBuskLayoutCommit, type BuskLayoutOp } from '@/store/busk'
import { buskDragData, dragSourceOf, resolveDropTarget, sameTarget, type BuskDragData } from './buskDnd'

/**
 * The busk page's half of the app's one drag context.
 *
 * It creates **no `DndContext` of its own** — that is the whole design. `Layout.tsx` mounts one
 * around both the FX cue-slot overlay and the routed page, and a nested context would win for this
 * subtree and hide the busk page from the overlay's droppables. `useDndMonitor` subscribes to the
 * ancestor's event bus instead, and gets `onDragOver` even though the provider passes no such prop.
 *
 * What it holds is a **hover target, not a document**: `{source, target}` in local state, at hover
 * rate — the standing reason such state stays out of the store. The renderer draws the source
 * ghosted where it is and one dashed slot at the target, and `applyDrop` runs exactly once, on the
 * drop. That is cheaper than previewing a whole document per hover and, more importantly, stable:
 * the layout moves by one slot the first time and then not again, so the placeholder cannot
 * oscillate under the pointer.
 *
 * Three things about how the hover is fed, each the fix for a drop that landed somewhere other
 * than where the slot was drawn:
 *
 * - **It listens to `onDragMove`, not only `onDragOver`.** dnd-kit fires `onDragOver` when the
 *   `over` id *changes* and at no other time, so a resolver fed from it alone decides the
 *   leading/trailing half of a pad once, on entry, and never again as the pointer crosses the
 *   centre. `onDragMove` carries the same `collisions` and `over` on every pointer movement;
 *   `sameTarget` keeps the state write to the moves that change the answer.
 * - **The droppables are re-measured whenever the target moves.** `MeasuringStrategy.Always` is not
 *   a timer: dnd-kit measures on demand — when the set of droppables changes, or a droppable's own
 *   ResizeObserver fires. Opening the dashed slot shifts every later pad and every row below it
 *   without resizing any of them, so their rects were stale for the rest of the drag. The effect
 *   below runs after the commit that moved the slot, so what it measures includes it. (The
 *   "stack under" strips that mount when a bank is lifted register new droppables, which is a
 *   container-set change dnd-kit re-measures on its own.)
 * - **The drop reads a ref, not state.** A drop that lands before the re-render following the
 *   last hover would otherwise read the target *before* that one.
 */
interface BuskEditContextValue {
  editing: boolean
  source: BuskDragData | null
  target: DropTarget | null
  /**
   * Something **not the page's** is lifted — a rig tile, a rig row, a Rig-tab palette row, a cue
   * slot. The page's droppables disable themselves on it, for the reason a bank's body disables
   * while a bank is lifted: dnd-kit's `over` would otherwise light a bank ring under a drag that
   * `canLand` refuses, and a highlight on a place the drop will not go is the failure §"The busk
   * layout" is written about.
   */
  foreign: boolean
  /** Save one gesture as a whole page. See `useBuskLayoutCommit`. */
  commit: (op: BuskLayoutOp) => void
}

/** Exported for tests that need a source and a target without driving a pointer through dnd-kit. */
export const BuskEditContext = createContext<BuskEditContextValue>({
  editing: false,
  source: null,
  target: null,
  foreign: false,
  commit: () => {},
})

export function useBuskEdit() {
  return useContext(BuskEditContext)
}

export function BuskEditProvider({
  editing,
  projectId,
  page,
  children,
}: {
  editing: boolean
  projectId: number
  page: BuskPage | null
  children: React.ReactNode
}) {
  const [source, setSource] = useState<BuskDragData | null>(null)
  const [foreign, setForeign] = useState(false)
  const [target, setTarget] = useState<DropTarget | null>(null)
  const targetRef = useRef<DropTarget | null>(null)
  /**
   * The drag's `delta` at the moment [targetRef] was last written — the anchor the slot's
   * hysteresis measures from (`TARGET_HYSTERESIS_PX`).
   *
   * dnd-kit's `delta` is the pointer's travel since the press, in client pixels, so the distance
   * between two of them is exactly "how far has the operator moved since the slot was placed".
   * Read off the event rather than reconstructed from `activatorEvent` + coordinates: that
   * reconstruction is the one `edgeDrag.ts` refuses for going wrong under browser zoom, and here
   * there is a first-class value to hand.
   */
  const anchorRef = useRef<{ x: number; y: number } | null>(null)
  /** The one pending re-measure frame, or null. See the effect at the foot of this component. */
  const measureFrameRef = useRef<number | null>(null)
  const commit = useBuskLayoutCommit(projectId, page?.id ?? null)
  const { measureDroppableContainers } = useDndContext()

  const clear = useCallback(() => {
    setSource(null)
    setForeign(false)
    setTarget(null)
    targetRef.current = null
    anchorRef.current = null
  }, [])

  const hover = useCallback(
    (event: DragMoveEvent | DragOverEvent) => {
      const data = buskDragData(event.active)
      if (data == null || page == null) return
      const next = resolveDropTarget({
        page,
        source: dragSourceOf(data).kind,
        activeId: String(event.active.id),
        overId: event.over == null ? null : String(event.over.id),
        collisionIds: (event.collisions ?? []).map((c) => String(c.id)),
        activeRect: event.active.rect.current.translated,
        overRect: event.over?.rect ?? null,
        current: targetRef.current,
        movedSinceTarget:
          anchorRef.current == null
            ? Number.POSITIVE_INFINITY
            : Math.hypot(event.delta.x - anchorRef.current.x, event.delta.y - anchorRef.current.y),
      })
      // A repeat hover must write no state, or the placeholder would re-render at pointer rate.
      if (sameTarget(targetRef.current, next)) return
      targetRef.current = next
      // The anchor moves only when the slot does, so the threshold is measured from where the
      // operator was when they last placed it — not from the previous pointer event, which a slow
      // drag would never exceed.
      anchorRef.current = { x: event.delta.x, y: event.delta.y }
      setTarget(next)
    },
    [page],
  )

  useDndMonitor({
    onDragStart(event: DragStartEvent) {
      const data = buskDragData(event.active)
      if (data != null) setSource(data)
      else setForeign(event.active.data.current != null)
    },
    onDragMove: hover,
    onDragOver: hover,
    onDragEnd(event: DragEndEvent) {
      const data = buskDragData(event.active)
      const landing = targetRef.current
      clear()
      if (data == null || landing == null || page == null) return
      const drop = dragSourceOf(data)
      // A drop that changes nothing is an ordinary outcome, not an edge case: the pointer sensor
      // arms at 8px, so half of all "never mind" gestures end back where they started. Committing
      // one anyway would spend a whole-page PUT and a `busk.layoutChanged` broadcast to every other
      // desk on a page that did not move. `applyDrop` already answers null for it — ask before
      // enqueuing rather than letting the `?? current` fallback swallow the answer.
      if (applyDrop(page, drop, landing) == null) return
      commit((current) => applyDrop(current, drop, landing) ?? current)
    },
    onDragCancel: clear,
  })

  // A drag in flight when the route changes never reaches its drop, and `useDndMonitor`
  // unsubscribes on unmount — so nothing else would clear these.
  useEffect(() => clear, [clear])

  // After the slot has moved (or the strips have appeared), every rect below it is wrong until
  // measured again. Only while something is lifted: outside a drag there is nothing to measure for.
  //
  // **On an animation frame, not inline.** This re-measure re-renders, which can resolve a new
  // target, which schedules another — and done synchronously from an effect that is itself keyed on
  // `target`, React counts those as nested updates and throws *Maximum update depth exceeded* at
  // fifty. `TARGET_HYSTERESIS_PX` is what stops the cycle at its source; deferring to a frame is the
  // backstop, so that anything which ever oscillates again is a flicker rather than a crash. One
  // measure per frame is also all a 60Hz drag can use.
  useEffect(() => {
    if (source == null) return
    // **Coalesce, never cancel-and-reschedule.** Cancelling the pending frame on each `target`
    // change starves it outright: a fast sweep changes the target on consecutive commits, which a
    // 120Hz pointer produces faster than one animation frame, so the measure would be cancelled
    // every time and never run — leaving exactly the stale rects this effect exists to refresh, in
    // exactly the case (a quick flick across a column) that needs it most. Letting the first frame
    // stand measures the *latest* target anyway, since the callback reads nothing captured.
    if (measureFrameRef.current != null) return
    measureFrameRef.current = requestAnimationFrame(() => {
      measureFrameRef.current = null
      measureDroppableContainers([])
    })
  }, [source, target, measureDroppableContainers])

  // Teardown only — a pending frame that outlives the tree would measure against unmounted nodes.
  useEffect(
    () => () => {
      if (measureFrameRef.current == null) return
      cancelAnimationFrame(measureFrameRef.current)
      measureFrameRef.current = null
    },
    [],
  )

  const value = useMemo(
    () => ({ editing, source, target, foreign, commit }),
    [editing, source, target, foreign, commit],
  )
  return <BuskEditContext.Provider value={value}>{children}</BuskEditContext.Provider>
}
