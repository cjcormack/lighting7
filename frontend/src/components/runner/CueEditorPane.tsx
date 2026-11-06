import { useLayoutEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react'
import { cn } from '@/lib/utils'
import { createSyncStore, sessionStorageArea } from '@/lib/syncStore'
import { useHeightGrip } from '@/hooks/useHeightGrip'
import { GRIP_CLASS, clampRigHeight, snapRigHeight, stepRigHeight } from '@/components/busking/RigHandle'
import { CueEditor, type CueEditorTabRequest } from '@/components/cues/editor/CueEditor'
import { paneBlankText, type CuePaneContent } from './cuePaneContent'

/**
 * The cue sheet's and the Timeline's **bottom pane** (show-cue-editing plan D5, §4.3): the cue
 * editor for the one selected cue, under the sheet, with a grip for its height.
 *
 * **It is this window's toggle, not the selection's** (show-view-fixes plan D3–D5): while
 * [cuePaneOpenStore] is on the host always draws it at its height, so selecting never moves the
 * rows; off, never. The selection only chooses what it shows (`paneContentOf`) — the cue, or a
 * blank state for nothing, several cues or only markers, drawn in the same sized box.
 *
 * **The height is this window's** (`sessionStorage cueSheet.paneHeight`), null until the window
 * drags it, drawn then at half the column. The grip is the busk `RigHandle`'s, its drag the same
 * `useHeightGrip` and its snap the same `snapRigHeight`, with the pane's own snap points: its 160px
 * floor and half the column, nothing else. The ceiling leaves the sheet its header and three rows
 * ([SHEET_KEEP_PX]), measured from the column rather than from the pane, whose content can be
 * taller than the room. ↑ / ↓ step between the floor, half and the ceiling; a double press restores
 * the default. The height is clamped **on read**: one stored on a taller window comes back when the
 * window does.
 *
 * The grip and the editor are **key regions** (`data-key-region`): a key pressed in them is never
 * the sheet's. A blank state is not one, so a press on it leaves the keys with the sheet.
 */

/** The least the pane draws. */
export const PANE_MIN_PX = 160
/** What the sheet keeps above the pane however far it is dragged: its header row and three rows. */
export const SHEET_KEEP_PX = 30 + 3 * 36
/** The grip's own row. */
const GRIP_PX = 16

/** Whether this window draws the pane — on in a window that has never chosen. */
export const cuePaneOpenStore = createSyncStore<boolean>({
  key: 'cueSheet.paneOpen',
  fallback: true,
  parse: (parsed) => (typeof parsed === 'boolean' ? parsed : true),
  storage: sessionStorageArea,
})

export function useCuePaneOpen(): boolean {
  return useSyncExternalStore(cuePaneOpenStore.subscribe, cuePaneOpenStore.getSnapshot, cuePaneOpenStore.getServerSnapshot)
}

export const cuePaneHeightStore = createSyncStore<number | null>({
  key: 'cueSheet.paneHeight',
  fallback: null,
  parse: (parsed) => (typeof parsed === 'number' && Number.isFinite(parsed) && parsed > 0 ? parsed : null),
  storage: sessionStorageArea,
})

/** A dragged height, snapped to the floor or to half the column, clamped. */
export function snapPaneHeight(raw: number, half: number, max: number): number {
  return snapRigHeight(raw, [PANE_MIN_PX, half], PANE_MIN_PX, max)
}

/** The keyboard's step, up (taller) or down, between the floor, half the column and the ceiling. */
export function stepPaneHeight(current: number, half: number, max: number, direction: 1 | -1): number {
  return stepRigHeight(current, [PANE_MIN_PX, clampRigHeight(half, PANE_MIN_PX, max), max], direction, PANE_MIN_PX, max)
}

export function CueEditorPane({
  projectId,
  content,
  locked,
  onRequestUnlock,
  tabRequest,
  sheetRef,
  onResized,
}: {
  projectId: number
  content: CuePaneContent
  locked: boolean
  /** Lift the lock, where it is the operator's — the editor's refused edits ask to. */
  onRequestUnlock?: () => void
  tabRequest?: CueEditorTabRequest | null
  /** The sheet's own box above the grip — the one other child that gives way to the pane. */
  sheetRef: RefObject<HTMLElement | null>
  /** The pane took a new height, or opened: the host keeps the edited row in view. */
  onResized?: () => void
}) {
  const regionRef = useRef<HTMLDivElement>(null)
  const paneRef = useRef<HTMLDivElement>(null)
  const stored = useSyncExternalStore(cuePaneHeightStore.subscribe, cuePaneHeightStore.getSnapshot, cuePaneHeightStore.getServerSnapshot)
  const [bounds, setBounds] = useState<{ half: number; max: number }>({ half: PANE_MIN_PX * 2, max: Infinity })
  const draggingRef = useRef(false)

  // Half the column, and the room the sheet and the pane share: the column less every other child
  // in it (the selection bar, the footer), which nothing the pane holds can change. The column is
  // the region's parent, read from the DOM: the pane mounts with the sheet, and an ancestor's ref
  // is not yet attached when a child's layout effect runs.
  useLayoutEffect(() => {
    const column = regionRef.current?.parentElement
    if (column == null) return
    const measure = () => {
      const columnHeight = column.getBoundingClientRect().height
      let others = 0
      for (const child of column.children) {
        if (child === sheetRef.current || child === regionRef.current) continue
        others += child.getBoundingClientRect().height
      }
      const half = Math.round(columnHeight / 2)
      const max = Math.max(PANE_MIN_PX, Math.floor(columnHeight - others - GRIP_PX - SHEET_KEEP_PX))
      setBounds((b) => (b.half === half && b.max === max ? b : { half, max }))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(column)
    // The sheet gives way when another child of the column grows (the selection bar arriving), which
    // the column, `flex-1`, does not see.
    if (sheetRef.current != null) observer.observe(sheetRef.current)
    return () => observer.disconnect()
  }, [sheetRef])

  const height = clampRigHeight(stored ?? bounds.half, PANE_MIN_PX, bounds.max)
  // Written onto the pane from an effect keyed on the value, never a `style` prop: the grip writes
  // the same property per move, and a re-render mid-drag must not put it back.
  const onResizedRef = useRef(onResized)
  onResizedRef.current = onResized
  useLayoutEffect(() => {
    if (draggingRef.current || paneRef.current == null) return
    paneRef.current.style.height = `${height}px`
    onResizedRef.current?.()
  }, [height])

  const { dragging, onPointerDown } = useHeightGrip({
    target: paneRef,
    direction: -1,
    snap: (raw) => snapPaneHeight(raw, bounds.half, bounds.max),
    onCommit: (h) => cuePaneHeightStore.set(h),
    onReset: () => cuePaneHeightStore.set(null),
    onDragging: (d) => {
      draggingRef.current = d
    },
  })

  return (
    <div ref={regionRef} className="flex min-h-0 shrink-0 flex-col">
      <div className="flex h-4 shrink-0 items-center justify-center border-t bg-muted/20" data-key-region>
        <div
          role="separator"
          aria-orientation="horizontal"
          aria-label="Cue editor height"
          aria-valuemin={PANE_MIN_PX}
          aria-valuemax={Number.isFinite(bounds.max) ? Math.round(bounds.max) : undefined}
          aria-valuenow={Math.round(height)}
          tabIndex={0}
          data-cue-pane-handle
          data-cue-pane-dragging={dragging ? 'true' : undefined}
          title="Drag to size the cue editor — it snaps at its smallest and at half the column; press twice for the default"
          className={cn(
            GRIP_CLASS,
            'cursor-row-resize touch-none',
            dragging ? 'bg-primary' : 'bg-border hover:bg-muted-foreground/70',
          )}
          onPointerDown={onPointerDown}
          onKeyDown={(e) => {
            if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
              e.preventDefault()
              cuePaneHeightStore.set(stepPaneHeight(height, bounds.half, bounds.max, e.key === 'ArrowUp' ? 1 : -1))
            }
          }}
        />
      </div>
      <div
        ref={paneRef}
        className="flex min-h-0 shrink-0 flex-col border-t bg-background"
        data-cue-pane={content.kind === 'cue' ? content.entryId : ''}
        data-cue-pane-state={content.kind}
        data-key-region={content.kind === 'cue' ? '' : undefined}
      >
        {content.kind === 'cue' ? (
          <CueEditor
            key={content.entryId}
            projectId={projectId}
            cueId={content.cueId}
            entryId={content.entryId}
            locked={locked}
            onRequestUnlock={onRequestUnlock}
            host="pane"
            tabRequest={tabRequest}
            className="min-h-0 flex-1"
          />
        ) : (
          <PaneBlank content={content} />
        )}
      </div>
    </div>
  )
}

function PaneBlank({ content }: { content: Exclude<CuePaneContent, { kind: 'cue' }> }) {
  const { title, detail } = paneBlankText(content)
  return (
    <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-1 px-6 text-center" data-cue-pane-blank>
      <p className="text-sm font-medium text-foreground">{title}</p>
      <p className="text-xs text-muted-foreground">{detail}</p>
    </div>
  )
}
