import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import {
  useDndContext,
  useDndMonitor,
  type DragEndEvent,
  type DragMoveEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import type { BuskRig } from '@/api/buskRigApi'
import { applyDrop, type RigDropTarget, type RigIds } from '@/lib/buskRig'
import { useBuskRigCommit, type BuskRigOp } from '@/store/busk'
import { resolveRigDropTarget, rigDragData, rigDragSourceOf, sameTarget, type RigDragData } from './buskDnd'

/**
 * The rig band's half of the app's one drag context — `BuskEditProvider` over the rig document.
 *
 * It creates **no `DndContext` of its own**, joins the ancestor's event bus through `useDndMonitor`,
 * holds a hover *target* rather than a document, feeds the resolver from `onDragMove` as well as
 * `onDragOver`, re-measures on an animation frame when the slot moves, and reads the drop off a
 * ref. Each of those is a rule the page learned on a real desk (`BuskEditProvider`'s docblock is
 * the record); the rig has the same anatomy — a row of tiles is a bank of pads — so it takes all
 * of them rather than re-learning any.
 *
 * What it adds is **`foreign`**, the mirror of the page provider's flag: while something not the
 * rig's is lifted — a pad, a bank, a Library-tab row, a cue slot — every rig droppable disables
 * itself, so a page drag crossing the band lights nothing `canLand` would refuse.
 */
interface RigEditContextValue {
  editing: boolean
  /** The built document — never the show-all fallback, whose rows are not on the wire. */
  rig: BuskRig
  source: RigDragData | null
  target: RigDropTarget | null
  foreign: boolean
  /** Save one gesture as a whole rig. See `useBuskRigCommit`. */
  commit: (op: BuskRigOp) => void
}

export const RigEditContext = createContext<RigEditContextValue>({
  editing: false,
  rig: { rows: [] },
  source: null,
  target: null,
  foreign: false,
  commit: () => {},
})

export function useRigEdit() {
  return useContext(RigEditContext)
}

export function RigEditProvider({
  editing,
  projectId,
  rig,
  ids,
  children,
}: {
  editing: boolean
  projectId: number
  rig: BuskRig
  ids: RigIds
  children: React.ReactNode
}) {
  const [source, setSource] = useState<RigDragData | null>(null)
  const [foreign, setForeign] = useState(false)
  const [target, setTarget] = useState<RigDropTarget | null>(null)
  const targetRef = useRef<RigDropTarget | null>(null)
  const anchorRef = useRef<{ x: number; y: number } | null>(null)
  const measureFrameRef = useRef<number | null>(null)
  const commit = useBuskRigCommit(projectId, ids)
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
      const data = rigDragData(event.active)
      if (data == null) return
      const next = resolveRigDropTarget({
        rig,
        source: rigDragSourceOf(data).kind,
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
      if (sameTarget(targetRef.current, next)) return
      targetRef.current = next
      anchorRef.current = { x: event.delta.x, y: event.delta.y }
      setTarget(next)
    },
    [rig],
  )

  useDndMonitor({
    onDragStart(event: DragStartEvent) {
      const data = rigDragData(event.active)
      if (data != null) setSource(data)
      else setForeign(event.active.data.current != null)
    },
    onDragMove: hover,
    onDragOver: hover,
    onDragEnd(event: DragEndEvent) {
      const data = rigDragData(event.active)
      const landing = targetRef.current
      clear()
      if (data == null || landing == null) return
      const drop = rigDragSourceOf(data)
      // A drop that changes nothing is an ordinary outcome: ask before enqueuing rather than
      // spending a whole-rig PUT and a `busk.rigChanged` broadcast on a tile that did not move.
      if (applyDrop(rig, drop, landing) == null) return
      commit((current) => applyDrop(current, drop, landing) ?? current)
    },
    onDragCancel: clear,
  })

  useEffect(() => clear, [clear])

  // The slot moved, so every rect after it is stale until measured again — on a frame, coalesced,
  // never cancel-and-reschedule (`BuskEditProvider`'s two reasons).
  useEffect(() => {
    if (source == null) return
    if (measureFrameRef.current != null) return
    measureFrameRef.current = requestAnimationFrame(() => {
      measureFrameRef.current = null
      measureDroppableContainers([])
    })
  }, [source, target, measureDroppableContainers])

  useEffect(
    () => () => {
      if (measureFrameRef.current == null) return
      cancelAnimationFrame(measureFrameRef.current)
      measureFrameRef.current = null
    },
    [],
  )

  const value = useMemo(
    () => ({ editing, rig, source, target, foreign, commit }),
    [editing, rig, source, target, foreign, commit],
  )
  return <RigEditContext.Provider value={value}>{children}</RigEditContext.Provider>
}
