import type { RefObject } from 'react'
import { useHeightGrip } from '@/hooks/useHeightGrip'
import { resetBuskRigHeight, setBuskRigHeight } from '@/lib/buskWindow'
import { cn } from '@/lib/utils'

/**
 * The rig's **handle** — the grip under the rows in Split (busk-further plan D6, rebuilt
 * 2026-09-22 on a pixel height).
 *
 * It sets **how tall the rig region is**, and nothing else. The region is a scroller, so the bar
 * can rest anywhere between the floor ([RIG_MIN_HEIGHT_PX]) and the ceiling the band measures
 * (the page keeps [PAGE_MIN_HEIGHT_PX]); past the last line the region simply has room, which is
 * how the page is made smaller. **The snap is live and magnetic**: while the grip is held, within
 * [RIG_SNAP_PX] of a line's bottom edge the height jumps to it — the region's bottom lands on the
 * line's — and everywhere else it follows the pointer. The last line's bottom is a snap point like
 * any other, so "show the whole rig" is still one drag. The arrow keys step between the same
 * edges, and a **double press** — two still presses within 400 ms — returns the window to its
 * default height. The drag is `useHeightGrip`, shared with the cue sheet's pane. It is read off the pointer presses themselves rather
 * than `dblclick`, because the press cancels `pointerdown` (to keep the grip from starting a text
 * selection) and whether a browser still synthesises the click pair after that is the browser's
 * call: Chromium does, and the desk runs Safari.
 *
 * **The handle never changes focus.** It replaced one that did — dragged past the last line it
 * landed on Rig, above the first on Pads — and the two ends were the inconsistency reported from
 * the desk: those snapped *as the pointer crossed them* while the lines snapped on release, the
 * count could shrink only the rig and never the page, and the bar could not rest between two lines.
 * Pads and Rig are the Focus control's, and the folded strips carry their own chevrons back.
 *
 * The height is written **imperatively** onto the rows grid per move and committed to the window's
 * fact on release, so a pointer frame re-renders this grip and not every tile on the band. The
 * band writes the resolved height the same way, from a layout effect keyed on the value, never as
 * a `style` prop — so a re-render mid-drag rewrites nothing, and when the handle unmounts mid-drag
 * (a `focus: 'rig'` from another window or MIDI) the band's effect has already put the grid right
 * before this teardown runs, which is why the teardown touches the DOM **not at all**: a passive
 * cleanup runs after the commit that cleared the height, and restoring anything there would leave
 * a fixed height on a grid that has to flex. A release that moved nothing writes nothing, which is
 * what keeps the double press's two releases from committing the height they did not change.
 */

/** The least the rig region draws — about one line of tiles, so it never folds to nothing. */
export const RIG_MIN_HEIGHT_PX = 56
/** What the page keeps below the handle, however far the rig is dragged. */
export const PAGE_MIN_HEIGHT_PX = 120
/** Within this of a line's bottom edge the drag snaps to it. */
export const RIG_SNAP_PX = 10

export function clampRigHeight(height: number, min: number, max: number): number {
  return Math.max(min, Math.min(Math.max(min, max), height))
}

/**
 * A dragged height, snapped to the nearest line edge within [RIG_SNAP_PX] and clamped. `edges`
 * are the heights at which the region's bottom sits on a line's bottom, as [lineEdges] measures
 * them. Pure, so a test can feed it a table.
 */
export function snapRigHeight(raw: number, edges: readonly number[], min: number, max: number): number {
  let height = raw
  let nearest = Infinity
  for (const edge of edges) {
    const distance = Math.abs(edge - raw)
    if (distance <= RIG_SNAP_PX && distance < nearest) {
      nearest = distance
      height = edge
    }
  }
  return clampRigHeight(height, min, max)
}

/**
 * The keyboard's step: the next line edge below the current height, or the previous one above it;
 * past the last edge the ceiling, above the first the floor. Pure.
 */
export function stepRigHeight(current: number, edges: readonly number[], direction: 1 | -1, min: number, max: number): number {
  const sorted = [...edges].sort((a, b) => a - b)
  const next = direction > 0 ? sorted.find((edge) => edge > current + 1) : sorted.reverse().find((edge) => edge < current - 1)
  return clampRigHeight(next ?? (direction > 0 ? max : min), min, max)
}

/**
 * The heights at which the rows grid's bottom edge sits on each line's bottom, **as drawn** —
 * viewport rects, so a scrolled grid answers the edges where they are now. A line is the union of
 * the rows on it (two half-width rows share one). Every line is in the DOM in Split, so a line
 * below the fold still reports.
 */
export function lineEdges(rows: HTMLElement): number[] {
  const top = rows.getBoundingClientRect().top
  const bottoms = new Map<number, number>()
  for (const el of rows.querySelectorAll<HTMLElement>('[data-rig-line]')) {
    const line = Number(el.dataset.rigLine)
    const bottom = el.getBoundingClientRect().bottom - top
    bottoms.set(line, Math.max(bottoms.get(line) ?? -Infinity, bottom))
  }
  return [...bottoms.entries()].sort((a, b) => a[0] - b[0]).map(([, bottom]) => bottom)
}

export const GRIP_CLASS =
  'h-1.5 w-16 rounded-full outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring/50'

export interface RigHandleProps {
  /** The rig region's height as drawn — `aria-valuenow`, and where a drag and a key step start from. */
  height: number
  /** The ceiling the band measured: the page keeps its minimum. */
  max: number
  /** The rows grid the drag sizes and measures. */
  rowsRef: RefObject<HTMLDivElement | null>
  /**
   * A drag began or ended. The band holds off its own height write while one is live: a column
   * resize mid-drag re-measures the ceiling, which can move the resolved height, and the band's
   * effect would otherwise stamp the stored wish over the height the drag is writing.
   */
  onDragging?: (dragging: boolean) => void
}

export function RigHandle({ height, max, rowsRef, onDragging }: RigHandleProps) {
  const { dragging, onPointerDown } = useHeightGrip({
    target: rowsRef,
    direction: 1,
    // The ceiling moves with the window mid-drag, so it is read at move time, as the edges are.
    snap: (raw) => (rowsRef.current == null ? raw : snapRigHeight(raw, lineEdges(rowsRef.current), RIG_MIN_HEIGHT_PX, max)),
    onCommit: setBuskRigHeight,
    onReset: resetBuskRigHeight,
    onDragging,
  })

  const step = (direction: 1 | -1) => {
    const rows = rowsRef.current
    const edges = rows == null ? [] : lineEdges(rows)
    setBuskRigHeight(stepRigHeight(height, edges, direction, RIG_MIN_HEIGHT_PX, max))
  }

  return (
    <div className="flex h-4 shrink-0 items-center justify-center">
      <div
        role="separator"
        aria-orientation="horizontal"
        aria-label="Rig height"
        aria-valuemin={RIG_MIN_HEIGHT_PX}
        // Never under the floor: a column too short for both keeps the rig at its floor (`clampRigHeight`).
        aria-valuemax={Number.isFinite(max) ? Math.round(Math.max(RIG_MIN_HEIGHT_PX, max)) : undefined}
        aria-valuenow={Math.round(height)}
        tabIndex={0}
        data-rig-rows-handle
        data-rig-rows-dragging={dragging ? 'true' : undefined}
        title="Drag to show more or less of the rig — it snaps to a row's edge as it passes one; press twice for the default"
        className={cn(
          GRIP_CLASS,
          'cursor-row-resize touch-none',
          dragging ? 'bg-primary' : 'bg-border hover:bg-muted-foreground/70',
        )}
        onPointerDown={onPointerDown}
        onKeyDown={(e) => {
          // One line edge at a time; past the last edge the ceiling, above the first the floor.
          if (e.key === 'ArrowDown' || e.key === 'ArrowRight') {
            e.preventDefault()
            step(1)
          } else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') {
            e.preventDefault()
            step(-1)
          }
        }}
      />
    </div>
  )
}
