import { useState, useCallback, useRef, useMemo, useEffect } from 'react'
import { useNavigate } from 'react-router'
import { Eye, Trash2, X } from 'lucide-react'
import { useDraggable, useDroppable, useDndMonitor } from '@dnd-kit/core'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuTrigger,
  ContextMenuSeparator,
} from '@/components/ui/context-menu'
import { cn } from '@/lib/utils'
import { useCurrentProjectQuery } from '../store/projects'
import {
  useProjectCueSlotsQuery,
  useAssignCueSlotMutation,
  useClearCueSlotMutation,
  type CueSlot,
} from '../store/cueSlots'
import { useHandPlace } from '@/store/hand'
import type { HeldRecord } from '@/api/handApi'
import { HAND_TARGET_RING, useHandOffer } from './hand/HandTarget'
import { useApplyCueMutation, useStopCueMutation, useActiveCueIds } from '../store/cues'
import { useToggleLookMutation } from '../store/looks'
import { useProgrammerAppliedQuery } from '../store/programmer'
import { lookIsApplied } from './busking/lookPresence'
import { useBuskEditMode } from '@/store/buskEditSlice'
import { isSlotRefusedDrag } from './dnd/slotDrop'
import { CollapsiblePanel } from './CollapsiblePanel'
import { usePersistentState } from '@/hooks/usePersistentState'
import { dispatchSyntheticContextMenu, useLongPress } from '@/hooks/useLongPress'
import {
  SlotItemContent,
  slotLitClass,
  type CueSlotDropTargetData,
  type CueSlotSwapDragData,
} from './cueSlotShared'
import { useDeskDnd } from './dnd/DeskDndProvider'

export { type CueSlot } from '../store/cueSlots'

const SLOTS_PER_PAGE = 8
// Read and written through `usePersistentState`, which already wraps both directions. The
// encoding is unchanged by the move: this value is a number, and `JSON.stringify(3)` is the same
// `"3"` the hand-rolled `String(page)` wrote, so a desk's stored page survives the switch.
const PAGE_STORAGE_KEY = 'cue-slot-overview-page'

/**
 * The drag context lives on `DeskDndProvider` — the app's one `DndContext`, which wraps this panel
 * *and* the routed page so a busk page can drop onto a slot. Re-exported under the old name so this
 * panel's own call sites read as they did.
 *
 * **Edit mode is not here.** A slot's cross and its drop target follow the *busk view's* edit mode
 * (`useBuskEditMode`), because that is where slots are filled from: the library palette that fills
 * a bank fills a slot (busk-layout plan D7). The panel's own long-press-to-wiggle mode and its
 * inline assign panel went with that change.
 */
export const useCueSlotDnd = useDeskDnd

export type { CueSlotSwapDragData, CueSlotDropTargetData } from './cueSlotShared'

// ─── Panel component ──────────────────────────────────────────────────

interface CueSlotOverviewPanelProps {
  isVisible: boolean
}

export function CueSlotOverviewPanel({ isVisible }: CueSlotOverviewPanelProps) {
  const { isDragging } = useCueSlotDnd()
  // Held mounted through a drag: unmounting the body takes every droppable with it, so a drag
  // in flight when the panel is hidden would resolve with no `over` target and be discarded in
  // silence. Rare — the pointer is captured, so the toggle is hard to reach mid-drag — but the
  // failure is invisible, which is the kind worth spending a boolean on.
  return (
    <CollapsiblePanel isVisible={isVisible} holdMounted={isDragging}>
      <CueSlotOverviewPanelBody />
    </CollapsiblePanel>
  )
}

/**
 * Below the collapse boundary: the slot and cue-stack queries, the wheel/pointer listeners and
 * the drag monitor, none of which a hidden panel has any use for. The page survives a collapse
 * because it is persisted on every change, not because the state outlives the unmount.
 */
function CueSlotOverviewPanelBody() {
  const { data: currentProject } = useCurrentProjectQuery()
  const projectId = currentProject?.id
  const { data: slots } = useProjectCueSlotsQuery(projectId!, { skip: !projectId })
  const buskEditing = useBuskEditMode()

  const [page, setPagePersist] = usePersistentState<number>(PAGE_STORAGE_KEY, 0)

  const activeCueIds = useActiveCueIds(projectId)
  // Project-wide and argument-free, so a surface with no selection can still read it. A Look tile
  // lights from the layer stack, never from cue liveness: it is a layer this tile would remove.
  const { data: applied } = useProgrammerAppliedQuery()

  const [applyCue] = useApplyCueMutation()
  const [stopCue] = useStopCueMutation()
  const [toggleLook] = useToggleLookMutation()
  const [clearSlot] = useClearCueSlotMutation()
  const [assignSlot] = useAssignCueSlotMutation()
  const placeFromHand = useHandPlace()

  const navigate = useNavigate()

  // Build a map of page+slotIndex → CueSlot for current page
  const slotMap = useMemo(() => {
    const map = new Map<string, CueSlot>()
    for (const slot of slots ?? []) {
      map.set(`${slot.page}-${slot.slotIndex}`, slot)
    }
    return map
  }, [slots])

  // Calculate total pages (at least 1, plus one extra empty page for new slots)
  const maxUsedPage = useMemo(() => {
    let max = 0
    for (const slot of slots ?? []) {
      if (slot.page > max) max = slot.page
    }
    return max
  }, [slots])

  const totalPages = Math.max(1, maxUsedPage + 2)

  // Swipe handling for touch page navigation
  const swipeStartX = useRef<number | null>(null)
  const swipeStartY = useRef<number | null>(null)
  const swiping = useRef(false)

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    swipeStartX.current = e.touches[0].clientX
    swipeStartY.current = e.touches[0].clientY
    swiping.current = false
  }, [])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (swipeStartX.current === null || swipeStartY.current === null) return
    const dx = e.touches[0].clientX - swipeStartX.current
    const dy = e.touches[0].clientY - swipeStartY.current
    // Only count as swipe if horizontal movement dominates vertical
    if (Math.abs(dx) > 30 && Math.abs(dx) > Math.abs(dy) * 1.5) {
      swiping.current = true
    }
  }, [])

  const handleTouchEnd = useCallback(
    (e: React.TouchEvent) => {
      if (swipeStartX.current === null) return
      const dx = e.changedTouches[0].clientX - swipeStartX.current
      if (swiping.current) {
        if (dx < -50 && page < totalPages - 1) {
          setPagePersist(page + 1)
        } else if (dx > 50 && page > 0) {
          setPagePersist(page - 1)
        }
      }
      swipeStartX.current = null
      swipeStartY.current = null
      swiping.current = false
    },
    [page, totalPages, setPagePersist],
  )

  // Trackpad horizontal scroll → page navigation
  // Must use native listener with { passive: false } to allow preventDefault
  const panelRef = useRef<HTMLDivElement>(null)
  const wheelAccum = useRef(0)
  const wheelTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pageRef = useRef(page)
  const totalPagesRef = useRef(totalPages)
  pageRef.current = page
  totalPagesRef.current = totalPages

  useEffect(() => {
    const el = panelRef.current
    if (!el) return

    const onWheel = (e: WheelEvent) => {
      if (Math.abs(e.deltaX) <= Math.abs(e.deltaY)) return

      e.preventDefault()
      wheelAccum.current += e.deltaX

      if (wheelTimeout.current) clearTimeout(wheelTimeout.current)
      wheelTimeout.current = setTimeout(() => {
        wheelAccum.current = 0
      }, 200)

      const threshold = 80
      if (wheelAccum.current > threshold) {
        wheelAccum.current = 0
        setPagePersist(Math.min(totalPagesRef.current - 1, pageRef.current + 1))
      } else if (wheelAccum.current < -threshold) {
        wheelAccum.current = 0
        setPagePersist(Math.max(0, pageRef.current - 1))
      }
    }

    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [setPagePersist])

  // Edge-drag page navigation: change page when dragging near left/right edges.
  //
  // This is the *panel's* own edge, and predates the same-machine edge drag in `dnd/edgeDrag.ts`,
  // which watches the **window's** edge to hand a record to the screen beside it. Same words, two
  // edges, no shared state — and the two cannot compound into a half-gesture, because the hand-off
  // cancels the drag and `onDragCancel` already clears this timer.
  const [isDraggingAny, setIsDraggingAny] = useState(false)
  const edgeScrollTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const edgeScrollDirection = useRef<'left' | 'right' | null>(null)

  const clearEdgeScroll = useCallback(() => {
    if (edgeScrollTimer.current) {
      clearTimeout(edgeScrollTimer.current)
      edgeScrollTimer.current = null
    }
    edgeScrollDirection.current = null
  }, [])

  // Same unmount hazard as the long-press timers below: `useDndMonitor` unsubscribes on unmount,
  // so onDragEnd never runs, and a pending edge scroll would land a page the operator never
  // reached in localStorage.
  useEffect(() => clearEdgeScroll, [clearEdgeScroll])

  useDndMonitor({
    onDragStart() {
      setIsDraggingAny(true)
    },
    onDragMove(event) {
      if (!panelRef.current) return
      const rect = panelRef.current.getBoundingClientRect()
      const activator = event.activatorEvent as PointerEvent
      // `== null`, not falsy: a drag begun hard against the left edge of the screen — or from a
      // touch that reports 0 — has a perfectly valid `clientX` of 0, and a falsy test disabled
      // edge auto-paging for that entire drag.
      if (activator.clientX == null) return
      const currentX = activator.clientX + event.delta.x

      const edgeThreshold = 40
      let direction: 'left' | 'right' | null = null

      if (currentX < rect.left + edgeThreshold && pageRef.current > 0) {
        direction = 'left'
      } else if (currentX > rect.right - edgeThreshold && pageRef.current < totalPagesRef.current - 1) {
        direction = 'right'
      }

      if (direction !== edgeScrollDirection.current) {
        clearEdgeScroll()
        edgeScrollDirection.current = direction
        if (direction) {
          const dir = direction
          edgeScrollTimer.current = setTimeout(() => {
            if (dir === 'left') {
              setPagePersist(Math.max(0, pageRef.current - 1))
            } else {
              setPagePersist(Math.min(totalPagesRef.current - 1, pageRef.current + 1))
            }
            edgeScrollTimer.current = null
            edgeScrollDirection.current = null
          }, 400)
        }
      }
    },
    onDragEnd() {
      setIsDraggingAny(false)
      clearEdgeScroll()
    },
    onDragCancel() {
      setIsDraggingAny(false)
      clearEdgeScroll()
    },
  })

  /** Is this tile's record on stage? Two different questions, by kind — see `slotLitClass`. */
  const slotIsLit = useCallback(
    (slot: CueSlot) =>
      slot.itemType === 'cue'
        ? activeCueIds.has(slot.itemId)
        : lookIsApplied(applied ?? [], slot.itemId),
    [activeCueIds, applied],
  )

  const handleSlotTap = useCallback(
    (slot: CueSlot) => {
      if (!projectId) return

      if (slot.itemType === 'cue') {
        if (activeCueIds.has(slot.itemId)) {
          stopCue({ projectId, cueId: slot.itemId })
        } else {
          applyCue({ projectId, cueId: slot.itemId })
        }
        return
      }
      // One route for both directions: the desk decides add-or-remove against the same layer stack
      // the tile lights from. **Empty targets is the contract** — the route derives them from the
      // Look's own patched fixtures, which is exactly why only a Look with no deferred effect may
      // sit in a slot (D7). No optimistic ring: the applied feed lands on the same frame, and
      // faking one would fight it.
      void toggleLook({ projectId, lookId: slot.itemId, targets: [] })
    },
    [projectId, activeCueIds, applyCue, stopCue, toggleLook],
  )

  const handleViewSlot = useCallback(
    (slot: CueSlot) => {
      // A Look goes to the library that owns it, mirroring the busk view's own inspect; a cue to
      // the Show view. (The `/show/stacks/{id}` arm died with the cue-stack slot.)
      navigate(
        slot.itemType === 'look'
          ? `/projects/${projectId}/looks`
          : `/projects/${projectId}/show`,
      )
    },
    [navigate, projectId],
  )

  const handleClearSlot = useCallback(
    (slot: CueSlot) => {
      if (!projectId) return
      clearSlot({ projectId, slotId: slot.id })
    },
    [projectId, clearSlot],
  )

  /**
   * Fill an **empty** slot from the desk's hand: this window's own `assignCueSlot`, then the
   * guarded `hand.drop` (D12, multi-screen plan §3.5).
   *
   * Empty slots only. A filled tile's press is live — it puts a cue on stage or takes a Look off —
   * and a hand held over the panel must not quietly become a second meaning for it. Replacing an
   * occupied slot is *Clear slot* and then this, which is two gestures the operator already has.
   */
  const handlePlaceInSlot = useCallback(
    (page: number, slotIndex: number, held: HeldRecord) => {
      if (!projectId) return
      void placeFromHand(held, {
        where: `slot ${slotIndex + 1} on page ${page + 1}`,
        run: () =>
          assignSlot({
            projectId,
            page,
            slotIndex,
            // A TEMPLATE never reaches here: `canHandLand`'s `slot` row refuses one, so the tile
            // offers nothing and this is never called for it. The fallback is written as the Look
            // arm rather than thrown for `layerSource`'s reason — a throw in a click handler on a
            // live desk is worse than a request the server refuses by name — but note what it
            // would cost if that gate were ever loosened: template and Look ids are independent
            // numeric spaces, so a template arriving here would name an unrelated Look.
            ...(held.kind === 'CUE' ? { cueId: held.id } : { lookId: held.id }),
          }).unwrap(),
        // The slot was empty, so the inverse is simply to empty it again.
        undo: (slot) => clearSlot({ projectId, slotId: slot.id }),
      })
    },
    [projectId, assignSlot, clearSlot, placeFromHand],
  )

  const slotsForPage = useMemo(() => {
    const result: (CueSlot | null)[] = []
    for (let i = 0; i < SLOTS_PER_PAGE; i++) {
      result.push(slotMap.get(`${page}-${i}`) ?? null)
    }
    return result
  }, [slotMap, page])

  return (
    <div
      ref={panelRef}
      className="border-b bg-background px-4 py-3"
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
    >
      {/* No *Done* here: the mode belongs to the busk view, and a second exit in the header would
          be a control that leaves a page mid-edit from another route. */}
      {buskEditing && (
        <div className="mb-2 text-xs text-muted-foreground">
          Drag a cue or a bound Look from the library to fill a slot
        </div>
      )}

      {/* Slot grid */}
      <div className="relative">
        <div className="grid grid-cols-4 gap-2 sm:grid-cols-8">
          {slotsForPage.map((slot, index) => (
            <CueSlotCell
              key={`${page}-${index}`}
              slot={slot}
              page={page}
              slotIndex={index}
              isLit={slot ? slotIsLit(slot) : false}
              isEditMode={buskEditing}
              onTap={handleSlotTap}
              onView={handleViewSlot}
              onClear={handleClearSlot}
              onHandPlace={handlePlaceInSlot}
            />
          ))}
        </div>
        {/* Edge indicators during drag */}
        {isDraggingAny && page > 0 && (
          <div className="absolute inset-y-0 -left-3 w-3 bg-gradient-to-r from-primary/20 to-transparent pointer-events-none" />
        )}
        {isDraggingAny && page < totalPages - 1 && (
          <div className="absolute inset-y-0 -right-3 w-3 bg-gradient-to-l from-primary/20 to-transparent pointer-events-none" />
        )}
      </div>

      {/* Page dots */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-1.5 mt-2">
          {Array.from({ length: totalPages }, (_, i) => (
            <button
              key={i}
              onClick={() => setPagePersist(i)}
              className={cn(
                'rounded-full transition-all',
                i === page
                  ? 'size-2 bg-primary'
                  : 'size-1.5 bg-muted-foreground/30 hover:bg-muted-foreground/50',
              )}
            />
          ))}
        </div>
      )}

    </div>
  )
}

// ─── Slot cell component ──────────────────────────────────────────────

interface CueSlotCellProps {
  slot: CueSlot | null
  page: number
  slotIndex: number
  /** On stage: a live cue, or a Look present on the rig. See `slotLitClass`. */
  isLit: boolean
  /** The **busk view's** edit mode: crosses, drags and drop targets all follow it. */
  isEditMode: boolean
  onTap: (slot: CueSlot) => void
  onView: (slot: CueSlot) => void
  onClear: (slot: CueSlot) => void
  /** Fill this slot from the desk's hand. Offered on an empty tile only — see the panel's handler. */
  onHandPlace: (page: number, slotIndex: number, held: HeldRecord) => void
}

function CueSlotCell({
  slot,
  page,
  slotIndex,
  isLit,
  isEditMode,
  onTap,
  onView,
  onClear,
  onHandPlace,
}: CueSlotCellProps) {
  const droppableId = `slot-${page}-${slotIndex}`

  // Only a droppable while the busk view is editing: outside that mode the overlay is press-only,
  // and an armed drop target with nothing to drop on it would only steal a busk page's drags.
  const { setNodeRef: setDropRef, isOver, active } = useDroppable({
    id: droppableId,
    data: { type: 'slot-target', page, slotIndex } satisfies CueSlotDropTargetData,
    disabled: !isEditMode,
  })

  // A palette row over this tile that cannot land in it — a template, or a Look needing a
  // selection. The drop is a no-op either way; this only says why rather than swallowing it.
  const refuses = isOver && isSlotRefusedDrag(active?.data.current)

  // Above the empty-slot early return, because hooks cannot be conditional. The eligibility rule
  // itself is `lib/handTargets.ts`'s, shared with the palette's `slotEligible`.
  //
  // **Offered in edit mode too**, which is `BuskBank`'s rule and has to be this tile's too. It was
  // withheld there at first, to keep a click from meaning two things while the operator arranges
  // the page — but an *empty* tile is not draggable (`useDraggable`'s `disabled: !slot || …`) and
  // its click does nothing at all in edit mode, so there was no second meaning to collide with:
  // the gate cost the gesture and prevented nothing. What was real is that a dashed border means
  // "a drag lands here" while editing, so the tile draws **solid** when it is a hand target there.
  const offer = useHandOffer('slot')

  const {
    attributes: dragAttributes,
    listeners: dragListeners,
    setNodeRef: setDragRef,
    isDragging,
  } = useDraggable({
    id: slot ? `slot-item-${slot.id}` : `empty-${page}-${slotIndex}`,
    data: slot
      ? ({ type: 'slot-item', page, slotIndex, slot } satisfies CueSlotSwapDragData)
      : undefined,
    disabled: !slot || !isEditMode,
  })

  /**
   * Hold → the context menu, and nothing else.
   *
   * There used to be a second stage: keep holding and the panel latched its own wiggle-and-cross
   * edit mode. That mode is gone — a slot's cross follows the busk view's *Edit layout* now — so
   * what is left is exactly `useLongPress`: a hold that opens the menu (the only way to reach
   * *View* / *Clear slot* on touch) and a short press that fires the tile.
   */
  const triggerRef = useRef<HTMLDivElement>(null)

  const { handlers: pressHandlers } = useLongPress({
    onLongPress: (origin) => dispatchSyntheticContextMenu(triggerRef.current, origin),
    onPress: () => {
      if (slot) onTap(slot)
    },
    // While editing, dnd-kit owns the pointer: a tile is a drag handle, not a button.
    disabled: isEditMode,
  })

  // In edit mode, dnd-kit handles pointer events for dragging; otherwise the hold gesture does.
  const pointerHandlers = isEditMode ? {} : pressHandlers

  // Merge refs for drag and drop
  const setRef = useCallback(
    (node: HTMLDivElement | null) => {
      setDropRef(node)
      setDragRef(node)
      ;(triggerRef as React.MutableRefObject<HTMLDivElement | null>).current = node
    },
    [setDropRef, setDragRef],
  )

  // Empty slot — a drop target, and since the hand a tap target too. Its context menu held one
  // item ("Edit slots"), which went with the panel's own edit mode, so there is no menu.
  //
  // This is the one hand target that *is* the tap rather than carrying a band beside it: the tile
  // is 3.5rem and already a dashed placeholder reading `—`, so lighting it and taking the press is
  // the smallest honest affordance. It stays a `<button>` even when nothing is held, so the DOM
  // does not change shape under a pointer — `disabled` is what says there is nothing to do.
  if (!slot) {
    // One derivation, read seven times below. It was re-derived at each of them, which is how the
    // meaning of "can this slot take what is held" ends up with seven places to update.
    const canPlace = offer != null
    return (
      <button
        type="button"
        ref={setDropRef}
        disabled={!canPlace}
        {...(canPlace ? { 'data-hand-target': 'slot' as const } : {})}
        onClick={canPlace ? () => onHandPlace(page, slotIndex, offer) : undefined}
        title={canPlace ? 'Place what the desk is holding here' : undefined}
        aria-label={canPlace ? `Place in slot ${slotIndex + 1}` : undefined}
        className={cn(
          'w-full rounded-md border-2 border-dashed flex items-center justify-center min-h-[3.5rem] transition-colors touch-none select-none',
          refuses
            ? 'border-destructive bg-destructive/5 text-destructive'
            : isOver
              ? 'border-primary bg-primary/5'
              : canPlace
                ? cn(HAND_TARGET_RING, 'hover:bg-primary/10', isEditMode && 'border-solid')
                : 'border-muted-foreground/25 text-muted-foreground/40',
        )}
      >
        <span className="text-xs">{refuses ? '×' : isOver || canPlace ? '+' : '—'}</span>
      </button>
    )
  }

  // Filled slot
  return (
    <ContextMenu>
      {/* The `aria-*` spread comes after `dragAttributes`, and only outside edit mode:
          `useDraggable` sets `role` and an `aria-pressed` meaning *dragging*, which is the honest
          reading while the tile is a drag handle. Pressed here means **on stage**. */}
      <ContextMenuTrigger asChild>
        <div
          ref={setRef}
          {...dragAttributes}
          {...dragListeners}
          {...(isEditMode ? {} : { 'aria-label': slot.itemName, 'aria-pressed': isLit })}
          {...pointerHandlers}
          className={cn(
            'relative rounded-md border flex flex-col items-center justify-center gap-0.5 min-h-[3.5rem] px-1.5 py-1 select-none transition-all touch-none',
            isEditMode ? 'cursor-grab active:cursor-grabbing' : 'cursor-pointer',
            !isEditMode && slotLitClass(slot.itemType, isLit),
            !isLit && !isEditMode && 'hover:bg-muted/50',
            isOver &&
              (refuses ? 'ring-2 ring-destructive ring-offset-1' : 'ring-2 ring-primary ring-offset-1'),
            isDragging && 'opacity-40',
            isEditMode && !isDragging && 'animate-[wiggle_0.3s_ease-in-out_infinite]',
          )}
          style={{
            animationDelay: isEditMode && !isDragging ? `${slotIndex * 50}ms` : undefined,
          }}
        >
          {/* Edit mode X button */}
          {isEditMode && (
            <button
              aria-label={`Clear ${slot.itemName}`}
              className="absolute -top-1.5 -right-1.5 size-5 rounded-full bg-destructive text-destructive-foreground flex items-center justify-center shadow-sm z-10"
              onClick={(e) => {
                e.stopPropagation()
                onClear(slot)
              }}
              onPointerDown={(e) => e.stopPropagation()}
            >
              <X className="size-3" />
            </button>
          )}

          {/* A Look's presence pip — the busk pad's own affordance. A live cue is a solid fill and
              needs none. */}
          {isLit && slot.itemType === 'look' && !isEditMode && (
            <span className="absolute top-1 right-1 size-2 rounded-full bg-primary" />
          )}

          <SlotItemContent name={slot.itemName} itemType={slot.itemType} />
        </div>
      </ContextMenuTrigger>
      <ContextMenuContent>
        <ContextMenuItem onSelect={() => onView(slot)}>
          <Eye className="mr-2 size-4" />
          View
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem
          onSelect={() => onClear(slot)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="mr-2 size-4" />
          Clear slot
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  )
}
