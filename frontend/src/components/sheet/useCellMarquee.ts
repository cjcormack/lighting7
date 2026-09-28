import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useLongPress } from '@/hooks/useLongPress'
import { columnRange, rectFrom, rowIndexRange, type ColumnBand } from './cellMarquee'
import { listSelectionIntentFor } from './listSelectionModel'
import type { CellRef, RowId } from './cellSelectionModel'
import type { CellSelection } from './useCellSelection'

/** The least a marquee needs to know about a row: its id. */
export interface MarqueeRow {
  id: RowId
}

/**
 * How far the pointer must travel before a press becomes a marquee rather than a click.
 *
 * The same number dnd-kit's `activationConstraint` and `Stage2DView`'s pan threshold already use.
 * A *threshold* rather than a modifier key on purpose: drag-select is the primary gesture here, and
 * hiding it behind ⌘ or Shift would make it undiscoverable.
 */
const DRAG_THRESHOLD_PX = 5

/**
 * How long a finger must hold still before a touch press becomes a marquee rather than a scroll.
 *
 * A touch has no "travelled 5px" that a scroll does not also have — a flick crosses the threshold
 * before the browser has decided the gesture is a pan — so on a touchscreen the marquee is armed
 * by **time**, not distance (`PD-MARQUEE-TOUCH`): touch pans, and only a hold marquees. The number
 * is `useLongPress`'s default, which is what the busk view's pads and the speed rail's hold-to-slide
 * already answer to, so one hold means one thing across the desk.
 */
const TOUCH_HOLD_MS = 500

/**
 * How long, after a marquee is released, the click that release generates is still swallowed.
 * An upper bound only — the click itself or the next `pointerdown` ends it sooner. See
 * `onPointerUp` in `useCellMarquee` for why a zero timer was not enough.
 */
const SWALLOW_WINDOW_MS = 350

/** How close to an edge the pointer must get before the marquee scrolls the list. */
const AUTOSCROLL_EDGE_PX = 24
const AUTOSCROLL_SPEED_PX = 14


/**
 * The drag-select gesture: a rubber band over the rows, resolved to cells by arithmetic.
 *
 * A **ref drives the gesture and state drives the band** — the idiom `Stage2DView` already uses.
 * The ref is what lets the handlers stay referentially stable through a drag; re-creating them
 * mid-gesture would detach the listener the pointer capture is bound to.
 *
 * Three things about the press are load-bearing:
 *
 *  - Radix `Popover` opens on `click`, not `pointerdown`, so arming a marquee on pointerdown opens
 *    nothing. All four cell editors are popovers, which is what makes this coexist with them.
 *  - Pointer capture is taken only once the threshold is crossed. Capturing on the initial press
 *    would swallow the click that opens an editor, so a plain click would stop working entirely.
 *  - After a real drag the trailing `click` is suppressed in the capture phase, or the editor under
 *    the release point opens on top of the selection just made.
 *
 * **A touch arms by time, a mouse by distance** (`PD-MARQUEE-TOUCH`). `button === 0` is true of a
 * finger too, and a scroll flick crosses `DRAG_THRESHOLD_PX` at once, so a distance-armed marquee
 * on a touchscreen selected cells on every scroll. Now a `touch` or `pen` press is handed to
 * `useLongPress` — the hook the busk pads and the speed rail's hold-to-slide already
 * use, so a hold means the same thing everywhere on the desk — and the browser keeps the touch
 * until the hold fires: a finger that moves first is a pan, which ends in `pointercancel` and
 * disarms it. Once the hold has fired the marquee needs the *rest* of the touch, and `touch-action`
 * cannot give it — it is read once, at touch start — so a non-passive `touchmove` guard on the
 * scroller cancels the pan for exactly as long as a marquee is live. A pen takes the touch arm
 * too: on an iPad it scrolls the page the way a finger does.
 *
 * The hold selects the cell under the finger the moment it fires — a zero-size rectangle still
 * covers one cell — which is the only acknowledgement a touchscreen gets that the hold took.
 *
 * Written once for every sheet (CLAUDE.md §Sheet kit); it was a local of `FixturesTable` until
 * the patch list, the DMX sheet and the cue sheet needed the same gesture. It reads three DOM
 * marks the table it serves must hang: `[data-grid-header]` (the sticky header, for its height),
 * `[data-column-header=<col>]` on each value column's header cell (the bands), and
 * `[data-grid-name-header]` on the first column's header (the row/cell divide). A sheet with no
 * name column — the DMX grid — hangs no name header, and every press is then a cell press.
 *
 * **The same gesture selects rows from the name column.** Which of the two a press is for is
 * decided once, at the press, by which side of the first value column it landed on — a rectangle
 * dragged from the name column into the values is still a row marquee, the way a spreadsheet's
 * row-header drag is. A row drag resolves the same `rowIndexRange` to row ids, folds the press's
 * modifier in against the selection it began over, and hands the whole list to `onRowMarquee`;
 * the container puts it in the row selection, which is what the checkbox column used to be for.
 */
export function useCellMarquee<C extends string, R extends MarqueeRow = MarqueeRow>({
  scrollRef,
  rows,
  rowHeight,
  isSelectableRow,
  visibleColumns,
  cellSelection,
  isRowSelected,
  onRowMarquee,
  onDragChange,
}: {
  scrollRef: React.RefObject<HTMLDivElement | null>
  /** Every row the sheet draws, in display order, dividers included — one `rowHeight` box each. */
  rows: readonly R[]
  /** The height of every virtual item, which is what makes `rowIndexRange` exact. */
  rowHeight: number
  /**
   * Which rows a rectangle can select. A divider is drawn at full row height but holds nothing,
   * so it must not inflate the count on either arm. Absent means every row.
   */
  isSelectableRow?: (row: R) => boolean
  visibleColumns: readonly C[]
  cellSelection?: CellSelection<C>
  /** The row selection a ⌘-drag accumulates over, read at the press. */
  isRowSelected: (id: RowId) => boolean
  /** The rows a name-column drag has arrived at. See the table's `onRowMarquee`. */
  onRowMarquee?: (ids: RowId[]) => void
  /** A drag started or ended — twice per gesture, never per move. See the table's `onMarqueeDragChange`. */
  onDragChange?: (dragging: boolean) => void
}) {
  const [band, setBand] = useState<React.CSSProperties | null>(null)
  const [chip, setChip] = useState<{ x: number; y: number } | null>(null)
  /** How many rows a live *row* marquee has selected, for the chip; null while a cell marquee is live. */
  const [rowCount, setRowCount] = useState<number | null>(null)
  /** Mirrors `draggingRef` as state, for the one class that wants it (see the rows wrapper). */
  const [dragging, setDraggingState] = useState(false)
  const dragRef = useRef<{
    pointerId: number
    /** The rows wrapper the press landed on — the hold arms from a timer, with no event to read it from. */
    el: HTMLElement
    start: { x: number; y: number }
    /** The same point in viewport space, for the scope chip the hold draws before any move. */
    client: { x: number; y: number }
    intent: ReturnType<typeof listSelectionIntentFor>
    /** A touch or pen press: armed by the hold, never by distance. */
    hold: boolean
    dragged: boolean
    /** Rows from the name column, cells from the values — fixed at the press. */
    mode: 'cells' | 'rows'
    /** A row drag's base: the ids selected when it began, which a ⌘-drag unions into. */
    baseRowIds: readonly RowId[]
  } | null>(null)
  const bandsRef = useRef<ColumnBand<C>[] | null>(null)
  const headerHeightRef = useRef(0)
  const autoScrollRef = useRef<number | null>(null)
  /** Last pointer position in scroller-client space, so the autoscroll loop can extend the drag. */
  const lastPosRef = useRef({ x: 0, y: 0 })

  // Latest values, read inside handlers that must not change identity mid-drag.
  const rowsRef = useRef(rows)
  rowsRef.current = rows
  const selectionRef = useRef(cellSelection)
  selectionRef.current = cellSelection
  const onDragChangeRef = useRef(onDragChange)
  onDragChangeRef.current = onDragChange
  const onRowMarqueeRef = useRef(onRowMarquee)
  onRowMarqueeRef.current = onRowMarquee
  const isRowSelectedRef = useRef(isRowSelected)
  isRowSelectedRef.current = isRowSelected

  /**
   * Whether a marquee is live, mirrored outside React state so the handlers keep their identity.
   * Deduplicated here rather than in the consumer: the teardown paths overlap (a `buttons === 0`
   * move can be followed by a `pointerup`), and a second `false` would be a second render for
   * nothing.
   */
  const draggingRef = useRef(false)
  const setDragging = useCallback((next: boolean) => {
    if (draggingRef.current === next) return
    draggingRef.current = next
    setDraggingState(next)
    onDragChangeRef.current?.(next)
  }, [])
  /**
   * Column extents, measured from the sticky header.
   *
   * Measured rather than recomputed from `gridTemplateColumns`: that template carries a `min()` and
   * a `1fr` distribution, and a JS re-implementation would be a second source of truth. Cached per
   * gesture and invalidated whenever the visible columns change.
   */
  const measureBands = useCallback((): ColumnBand<C>[] => {
    const scroller = scrollRef.current
    if (!scroller) return []
    const cells = scroller.querySelectorAll('[data-column-header]')
    const origin = scroller.getBoundingClientRect().left
    const out: ColumnBand<C>[] = []
    cells.forEach((el) => {
      const col = el.getAttribute('data-column-header') as C | null
      if (!col) return
      const r = el.getBoundingClientRect()
      out.push({ col, left: r.left - origin, right: r.right - origin })
    })
    return out
  }, [scrollRef])

  useEffect(() => {
    bandsRef.current = null
  }, [visibleColumns])

  const stopAutoScroll = useCallback(() => {
    if (autoScrollRef.current != null) {
      cancelAnimationFrame(autoScrollRef.current)
      autoScrollRef.current = null
    }
  }, [])

  const isSelectableRef = useRef(isSelectableRow)
  isSelectableRef.current = isSelectableRow

  /** The selectable rows the rectangle covers, in display order. */
  const rowsFor = useCallback(
    (endX: number, endY: number): R[] => {
      const scroller = scrollRef.current
      const drag = dragRef.current
      if (!scroller || !drag) return []
      const rect = rectFrom(drag.start, { x: endX, y: endY })
      const range = rowIndexRange(rect, {
        scrollTop: scroller.scrollTop,
        headerHeight: headerHeightRef.current,
        rowHeight,
        rowCount: rowsRef.current.length,
      })
      if (!range) return []
      const out: R[] = []
      for (let i = range[0]; i <= range[1]; i++) {
        const row = rowsRef.current[i]
        if (row && (isSelectableRef.current?.(row) ?? true)) out.push(row)
      }
      return out
    },
    [rowHeight, scrollRef],
  )

  const hitsFor = useCallback(
    (endX: number, endY: number): CellRef<C>[] => {
      const drag = dragRef.current
      if (!drag) return []
      const rect = rectFrom(drag.start, { x: endX, y: endY })
      const cols = columnRange<C>(rect, bandsRef.current ?? [])
      if (cols.length === 0) return []
      const hits: CellRef<C>[] = []
      for (const row of rowsFor(endX, endY)) {
        for (const col of cols) hits.push({ rowId: row.id, col })
      }
      return hits
    },
    [rowsFor],
  )

  /** Redraw the band and re-resolve the hits from the last known pointer position. */
  const updateFromPointer = useCallback(() => {
    const scroller = scrollRef.current
    const drag = dragRef.current
    if (!scroller || !drag?.dragged) return
    const { x, y } = lastPosRef.current
    const rect = rectFrom(drag.start, { x, y })
    // The band is drawn inside the ROWS wrapper, whose origin is below the sticky header and at
    // content x/y 0 — but `rect` is in scroller-client space. Both offsets have to come off, or the
    // rubber band sits a header's height below the cells it is actually selecting.
    setBand({
      left: rect.left + scroller.scrollLeft,
      top: rect.top + scroller.scrollTop - headerHeightRef.current,
      width: rect.right - rect.left,
      height: rect.bottom - rect.top,
    })
    if (drag.mode === 'rows') {
      const covered = rowsFor(x, y).map((row) => row.id)
      // Shift has no distinct meaning for a rectangle — the rectangle *is* the range — so it
      // replaces, as it does for cells (`applyCellSelection`). ⌘ accumulates onto the selection
      // the drag began over, not onto its own last frame, so shrinking the rectangle un-selects.
      const accumulate = drag.intent === 'toggle' || drag.intent === 'range-add'
      // In display order either way, so the comparison below is order-insensitive by construction.
      const ids = accumulate
        ? (() => {
            const wanted = new Set([...drag.baseRowIds, ...covered])
            return rowsRef.current.filter((row) => wanted.has(row.id)).map((row) => row.id)
          })()
        : covered
      // The chip counts what the drag has *selected* — the accumulated list under ⌘ — the way the
      // cell arm counts the accumulated cells, not the rectangle alone.
      setRowCount(ids.length)
      // Sent only when it differs from what the container holds *now*, not from what this drag
      // last sent: a dispatch per move would re-render every consumer for nothing, and a memory of
      // our own last send would fall silent exactly when another writer (the desk bridge) moved
      // the selection under a live drag.
      const current = rowsRef.current
        .filter((row) => isRowSelectedRef.current(row.id))
        .map((row) => row.id)
      if (current.length === ids.length && ids.every((id, i) => current[i] === id)) return
      onRowMarqueeRef.current?.(ids)
      return
    }
    selectionRef.current?.select(hitsFor(x, y), drag.intent)
  }, [hitsFor, rowsFor, scrollRef])

  // The rAF loop closes over its first `step`, so it reads the callback through a ref rather than
  // capturing a stale one.
  const updateFromPointerRef = useRef(updateFromPointer)
  updateFromPointerRef.current = updateFromPointer

  /**
   * The armed press becomes a marquee: the one place both arms — distance for a mouse, time for a
   * touch — go through, so capture and the edge-scroll loop cannot be set up by one and forgotten
   * by the other.
   */
  const arm = useCallback(() => {
    const drag = dragRef.current
    if (!drag || drag.dragged) return
    drag.dragged = true
    setDragging(true)
    // On the plain lists text stays selectable until this moment (see the rows wrapper), so the
    // five pixels before the threshold may have started a text selection; drop it.
    window.getSelection()?.removeAllRanges()
    try {
      drag.el.setPointerCapture(drag.pointerId)
    } catch {
      // Safari throws when the pointer has already been released. Losing capture only means
      // the drag ends at the edge of the element, which is survivable.
    }
  }, [setDragging])

  /**
   * The edge-scroll loop, started by the first move of a live marquee rather than by `arm()`.
   * Without it a selection can never exceed one viewport of rows, which on a real rig is the
   * normal case. It waits for a move because the hold arms with the finger still on the press
   * point: started from there, a hold within `AUTOSCROLL_EDGE_PX` of the bottom would begin
   * scrolling — and growing the selection — before the operator had moved at all.
   */
  const startAutoScroll = useCallback(() => {
    if (autoScrollRef.current != null) return
    const step = () => {
      const el = scrollRef.current
      if (!el || !dragRef.current?.dragged) return
      const { y: yNow } = lastPosRef.current
      const before = el.scrollTop
      if (yNow < AUTOSCROLL_EDGE_PX) el.scrollTop -= AUTOSCROLL_SPEED_PX
      else if (yNow > el.clientHeight - AUTOSCROLL_EDGE_PX) el.scrollTop += AUTOSCROLL_SPEED_PX
      // Re-resolve after scrolling: the pointer has not moved, but the CONTENT under it has, so
      // without this the marquee would stop growing the moment the operator held still at the
      // edge — which looks exactly like autoscroll being broken.
      if (el.scrollTop !== before) updateFromPointerRef.current()
      autoScrollRef.current = requestAnimationFrame(step)
    }
    autoScrollRef.current = requestAnimationFrame(step)
  }, [scrollRef])

  // The touch arm. The hook's handlers are called from this hook's own, and only for a press whose
  // `hold` is set — a mouse never reaches them. `onPress` is deliberately not given: a touch that
  // was neither held nor moved is a tap, and a tap is the cell's `click`, which the browser is
  // already about to deliver.
  const { handlers: hold } = useLongPress({
    delayMs: TOUCH_HOLD_MS,
    onLongPress: () => {
      const drag = dragRef.current
      if (!drag || drag.dragged) return
      arm()
      // Select the cell under the finger now, before any move: the acknowledgement that the hold
      // took, and the start of the rectangle the finger is about to draw.
      lastPosRef.current = drag.start
      setChip({ x: drag.client.x, y: drag.client.y })
      updateFromPointerRef.current()
    },
  })

  const onPointerDown = useCallback(
    (e: React.PointerEvent) => {
      // Same portal trap the scroller's background `onClick` documents, and the one this handler
      // was missing: React bubbles a synthetic event up the *React* tree, and every cell editor is
      // rendered from inside a row — so a press on the dimmer slider, the colour picker or the hue
      // bar arrived here as a press on the grid, started a marquee from wherever the editor
      // happened to be over, and selected cells while the operator was dragging a value. Invisible
      // in a popover, which is small and beside its own cell; obvious in a sheet, which is not.
      // The DOM subtree is the question, so the DOM is what is asked.
      if (!e.currentTarget.contains(e.target as Node)) return
      if ((!selectionRef.current && !onRowMarqueeRef.current) || e.button !== 0) return
      const scroller = scrollRef.current
      if (!scroller) return
      // A second pointer while one is already down. A live marquee keeps the pointer it has and
      // ignores the newcomer; a *pending* hold is dropped outright, because two fingers are the
      // start of a pinch, not of a hold — and left alone the first finger's timer would have fired
      // for a press it no longer describes.
      const current = dragRef.current
      if (current) {
        if (current.dragged) return
        if (current.hold) hold.onPointerCancel()
        dragRef.current = null
        return
      }
      bandsRef.current = measureBands()
      headerHeightRef.current =
        scroller.querySelector('[data-grid-header]')?.getBoundingClientRect().height ?? 0
      const origin = scroller.getBoundingClientRect()
      const x = e.clientX - origin.left
      // On the sticky name column a drag selects rows; on a value column it selects cells. The
      // name column is measured from its *own* header cell, not inferred from the first value
      // band's left edge: the name cell is `sticky left-0` and stays put while the bands scroll
      // under it, so once the grid is scrolled sideways the first band's edge is at or left of
      // zero and a press on the still-visible name cell would have read as a cell press — and
      // rubber-banded the cells hidden behind it. Each arm needs its consumer, or the press is
      // nobody's.
      const nameRight =
        (scroller.querySelector('[data-grid-name-header]')?.getBoundingClientRect().right ?? origin.left) -
        origin.left
      const mode: 'cells' | 'rows' = x < nameRight ? 'rows' : 'cells'
      if (mode === 'rows' ? !onRowMarqueeRef.current : !selectionRef.current) return
      // The base a ⌘-drag accumulates onto, taken now: the selection can move under a live drag
      // (the container clears the *other* kind the moment this one selects anything).
      const baseRowIds =
        mode === 'rows' ? rowsRef.current.filter((row) => isRowSelectedRef.current(row.id)).map((row) => row.id) : []
      // Touch *and* pen: a pen scrolls like a finger on the one tablet this runs on. Named
      // positively rather than as `!== 'mouse'` because a `pointerType` can be empty — jsdom's
      // always is — and an unknown device is a mouse's kind of thing, not a scroller's.
      const isHold = e.pointerType === 'touch' || e.pointerType === 'pen'
      dragRef.current = {
        pointerId: e.pointerId,
        el: e.currentTarget as HTMLElement,
        start: { x, y: e.clientY - origin.top },
        client: { x: e.clientX, y: e.clientY },
        intent: listSelectionIntentFor(e),
        hold: isHold,
        dragged: false,
        mode,
        baseRowIds,
      }
      if (isHold) hold.onPointerDown(e)
    },
    [hold, measureBands, scrollRef],
  )

  const onPointerMove = useCallback(
    (e: React.PointerEvent) => {
      const drag = dragRef.current
      const scroller = scrollRef.current
      if (!drag || !scroller) return
      // Another finger's move says nothing about this gesture.
      if (e.pointerId !== drag.pointerId) return
      // No button held any more: the release happened somewhere this element never saw — over the
      // sticky header, the scrollbar, or outside the window — before the threshold was crossed, so
      // no pointer capture was taken and no `pointerup` arrived here. Without this the armed press
      // outlives the gesture and the operator's next plain HOVER over the grid starts a marquee
      // from a start point they set minutes ago.
      if (e.buttons === 0) {
        // Tear the whole gesture down, not just the ref. If capture was ever lost while a marquee
        // was live, nulling `dragRef` alone would leave the rubber band and the scope chip frozen
        // on screen until the next drag — the autoscroll loop stops on its own, since it re-reads
        // `dragRef.current?.dragged` each frame.
        dragRef.current = null
        setBand(null)
        setChip(null)
        setRowCount(null)
        stopAutoScroll()
        setDragging(false)
        return
      }
      const origin = scroller.getBoundingClientRect()
      const x = e.clientX - origin.left
      const y = e.clientY - origin.top
      lastPosRef.current = { x, y }

      if (!drag.dragged) {
        if (drag.hold) {
          // A touch that moves before the hold has fired is the browser's scroll, not ours. The
          // hook drops the hold past its own slop; the pan, if the scroller takes it, arrives as
          // `pointercancel` and tears the press down. Either way nothing is armed by distance.
          hold.onPointerMove(e)
          return
        }
        if (Math.hypot(x - drag.start.x, y - drag.start.y) < DRAG_THRESHOLD_PX) return
        arm()
      }

      e.preventDefault()
      // The first move of a live marquee — a mouse's arming move, or a held finger's first
      // travel — is what starts the edge-scroll loop. See `startAutoScroll`.
      startAutoScroll()
      setChip({ x: e.clientX, y: e.clientY })
      updateFromPointer()
    },
    [arm, hold, scrollRef, setDragging, startAutoScroll, stopAutoScroll, updateFromPointer],
  )

  const onPointerUp = useCallback(
    (e: React.PointerEvent) => {
      const drag = dragRef.current
      // Lifting a second finger must not end the gesture the first one owns.
      if (drag && e.pointerId !== drag.pointerId) return
      dragRef.current = null
      setBand(null)
      setChip(null)
      setRowCount(null)
      stopAutoScroll()
      setDragging(false)
      if (!drag) return
      // A pending hold dies with the press — this is the `pointercancel` path too, which is the
      // one a scroller's pan ends a touch with, and the reason the hook has a cancel at all.
      if (drag.hold) hold.onPointerCancel()
      try {
        ;(e.currentTarget as HTMLElement).releasePointerCapture(drag.pointerId)
      } catch {
        // Already released; nothing to undo.
      }
      if (!drag.dragged) return

      // A marquee ends with focus on the document, not on the cell trigger the press began on.
      // Chromium (and Firefox on Windows) focus a `<button>` on mousedown, and every cell's editor
      // trigger is a button filling its cell — so a mouse-drawn marquee left that button focused,
      // and the container's window handler then read the operator's Enter as "from a focused
      // control" and handed it to the button, which opened one cell's popover instead of the typed
      // field. Safari does not focus buttons on mousedown, which is why the gesture worked there
      // and nowhere else. Blurring only after a real drag keeps Tab-then-Enter on a trigger intact.
      // (Since a click on a cell selects rather than opening, what Enter on a focused trigger does
      // there is select that one cell — still not the marquee's editor, and still wrong.)
      if (document.activeElement instanceof HTMLElement && scrollRef.current?.contains(document.activeElement)) {
        document.activeElement.blur()
      }

      // Swallow the click this release is about to generate, or the cell under the pointer opens
      // its editor on top of the selection just made.
      //
      // It lives until the click arrives, the next pointer goes down, or `SWALLOW_WINDOW_MS`
      // passes — whichever is first. A mouse's click lands in the same task as this release, but
      // a touch's compatibility click is the browser's to schedule and a zero timer could lose
      // the race to it, in which case the click would hit the auto-opened editor's trigger and
      // toggle it straight back shut. The next `pointerdown` is the one certain bound: a click
      // belonging to this release cannot come after the press that starts the next gesture. A
      // drag that ends outside the document generates no click at all, which is what the timer
      // is for — without it the listener would sit there and eat the operator's next one.
      let dispose = () => {}
      const swallow = (ev: MouseEvent) => {
        ev.preventDefault()
        ev.stopPropagation()
        dispose()
      }
      const timer = window.setTimeout(() => dispose(), SWALLOW_WINDOW_MS)
      dispose = () => {
        window.clearTimeout(timer)
        window.removeEventListener('click', swallow, true)
        window.removeEventListener('pointerdown', dispose, true)
      }
      window.addEventListener('click', swallow, true)
      window.addEventListener('pointerdown', dispose, true)
      // And that is all a release does. It used to open the first selected cell's editor when the
      // drag stayed in one column (`PD-POPUP-AFTER-DRAG`); the selection bar's Set is that gesture
      // now, and Enter its key. For a row marquee the swallow above is load-bearing twice over:
      // the name cell's own `onClick` would otherwise select the row under the release, replacing
      // the very selection the drag just made.
    },
    [hold, scrollRef, setDragging, stopAutoScroll],
  )

  /**
   * A finger that leaves the rows before its hold fires has not held anything. Only the *pending*
   * hold is dropped: an armed marquee holds pointer capture, so the pointer cannot leave it in
   * any sense this handler should act on.
   */
  const onPointerLeave = useCallback(() => {
    const drag = dragRef.current
    // A mouse press is left alone: it arms by distance, and a press that strays over the sticky
    // header and back was always allowed to become a marquee.
    if (!drag || drag.dragged || !drag.hold) return
    hold.onPointerLeave()
    dragRef.current = null
  }, [hold])

  // The pan guard. `touch-action` is decided when the touch starts, and at that moment this touch
  // is a scroll — it becomes a marquee only once held — so the scroll has to be refused per move
  // instead, and only a non-passive listener can refuse it. Registered natively: React's own
  // touch listeners are passive, so an `onTouchMove` prop could not call `preventDefault`. On the
  // scroller rather than the rows wrapper because that is the element the pan belongs to. It
  // prevents nothing while no marquee is live, which is the whole time a mouse is in use.
  useEffect(() => {
    const scroller = scrollRef.current
    if (!scroller) return
    const guard = (e: TouchEvent) => {
      if (dragRef.current?.dragged) e.preventDefault()
    }
    scroller.addEventListener('touchmove', guard, { passive: false })
    return () => scroller.removeEventListener('touchmove', guard)
  }, [scrollRef])

  useEffect(
    () => () => {
      stopAutoScroll()
      // A grid that unmounts mid-drag would otherwise leave the consumer believing one is still
      // in flight, and the selection bar holding a place for a gesture that has gone.
      setDragging(false)
      // And a grid that unmounts mid-*hold* must not arm afterwards: `useLongPress` cancels its
      // own timer on unmount, but this ref is what `onLongPress` would read if it fired, so the
      // press is dropped here too. The unmount is reachable with a finger down — the table
      // leaves the tree whenever the container's row list empties, which a live channel push
      // can do under `onlyLit`.
      if (dragRef.current?.hold) hold.onPointerCancel()
      dragRef.current = null
    },
    [hold, setDragging, stopAutoScroll],
  )

  return { band, chip, rowCount, dragging, onPointerDown, onPointerMove, onPointerUp, onPointerLeave }
}
