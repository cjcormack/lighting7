import { useRef } from 'react'
import { Redo2, Undo2 } from 'lucide-react'
import { dispatchSyntheticContextMenu, useLongPress } from '@/hooks/useLongPress'
import { useProgrammerFade } from '@/lib/programmerFade'
import { useHistoryRedoMutation, useHistoryState, useHistoryUndoMutation } from '@/store/history'
import type { HistoryStep } from '@/api/historyApi'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'

/** How many steps the menu lists; the history keeps 50, and a menu of 50 is no menu. */
const MENU_STEPS = 12

/**
 * The programmer's undo and redo (undo plan §3.6, §4.3): its steps from the live show's history, and
 * the two actions at the programmer fade. A step is anyone's, since there is one programmer (D1).
 */
export function useProgrammerUndo(projectId: number) {
  const history = useHistoryState()
  const fade = Number(useProgrammerFade()) || 0
  const [undoMutation] = useHistoryUndoMutation()
  const [redoMutation] = useHistoryRedoMutation()
  const live = history.projectId === projectId
  const steps = live ? history.programmer.undo : []
  const redoSteps = live ? history.programmer.redo : []
  return {
    revision: history.revision,
    steps,
    redoSteps,
    /** Undo the newest step, or go back to before [entryId]. */
    undo: (entryId?: string) => {
      if (entryId == null && steps.length === 0) return
      void undoMutation({ projectId, entryId, fadeMs: fade })
    },
    /** Redo the next step, or every one up to [entryId]. */
    redo: (entryId?: string) => {
      if (entryId == null && redoSteps.length === 0) return
      void redoMutation({ projectId, entryId, fadeMs: fade })
    },
  }
}

/** `14:02:31`, the step's time on this machine's clock. */
function timeOf(step: HistoryStep): string {
  const at = new Date(step.at)
  return Number.isNaN(at.getTime()) ? '' : at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/**
 * Undo and Redo, **ahead of Clear** in row A (undo plan §4.3): programmer-only, at the programmer fade.
 * Undo shows the step it takes in muted text where the row has words (`Undo · Clear`). A right-click,
 * or a hold (`useLongPress`, so the release does not also undo), lists the last steps, and picking one
 * goes back to before it.
 *
 * **Redo folds into that menu below `@[800px]`**, where every control on the row is its icon, so the
 * pair costs the row one 32px button there (`ProgrammerGrid` has the arithmetic).
 */
export function ProgrammerUndoRedo({ projectId }: { projectId: number }) {
  const { steps, redoSteps, undo, redo } = useProgrammerUndo(projectId)
  const top = steps[0]
  const next = redoSteps[0]
  const triggerRef = useRef<HTMLDivElement>(null)
  const { handlers, consumeLongPress } = useLongPress({
    onLongPress: (origin) => dispatchSyntheticContextMenu(triggerRef.current, origin),
  })
  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>
        <div ref={triggerRef} className="inline-flex h-8 shrink-0 items-stretch overflow-hidden rounded-md border">
          <button
            type="button"
            disabled={!top}
            {...handlers}
            onClick={() => {
              if (!consumeLongPress()) undo()
            }}
            aria-label={top ? `Undo ${top.label}` : 'Undo'}
            title={top ? `Undo · ${top.label} — right-click for earlier steps` : 'Nothing to undo in the programmer'}
            className="inline-flex items-center gap-1.5 px-2 text-xs font-medium transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
          >
            <Undo2 className="size-3.5" />
            <span className="hidden @[1100px]:inline">Undo</span>
            {top && (
              <span className="hidden max-w-[8rem] truncate font-normal text-muted-foreground @[1100px]:inline">
                · {top.label}
              </span>
            )}
          </button>
          <button
            type="button"
            disabled={!next}
            onClick={() => redo()}
            aria-label={next ? `Redo ${next.label}` : 'Redo'}
            title={next ? `Redo · ${next.label}` : 'Nothing to redo in the programmer'}
            className="hidden items-center border-l px-2 transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50 @[800px]:inline-flex"
          >
            <Redo2 className="size-3.5" />
          </button>
        </div>
      </ContextMenuTrigger>
      <ContextMenuContent className="w-72">
        <ContextMenuLabel>{steps.length > 0 ? 'Go back to before' : 'Nothing to undo'}</ContextMenuLabel>
        {steps.slice(0, MENU_STEPS).map((step) => (
          <ContextMenuItem key={step.id} onSelect={() => undo(step.id)}>
            <span className="min-w-0 flex-1 truncate">{step.label}</span>
            <span className="font-mono text-[11px] text-muted-foreground">{timeOf(step)}</span>
          </ContextMenuItem>
        ))}
        {redoSteps.length > 0 && (
          <>
            <ContextMenuSeparator />
            <ContextMenuItem onSelect={() => redo()}>
              <Redo2 className="size-3.5" />
              <span className="min-w-0 flex-1 truncate">Redo {redoSteps[0].label}</span>
            </ContextMenuItem>
          </>
        )}
      </ContextMenuContent>
    </ContextMenu>
  )
}
