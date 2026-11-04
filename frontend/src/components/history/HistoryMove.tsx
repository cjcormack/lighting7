import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import { toast } from 'sonner'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { useProgrammerFade } from '@/lib/programmerFade'
import { selectLockRequested, unlockRequested } from '@/store/editLockSlice'
import { useProjectProgramStateQuery } from '@/store/cueStacks'
import {
  entriesForRequests,
  historyRefusalOf,
  historySlice,
  useHistoryMoveMutation,
  useHistoryState,
  type HistoryMoveAnswer,
  type HistoryMoveArg,
} from '@/store/history'
import type { HistoryState } from '@/api/historyApi'
import type { store } from '@/store'
import { isShowEntryId, movedMessage, runHistoryMove, type HistoryQuestion } from './undoFlow'
import { useUndoKeys } from './undoKeys'
import { notifyHistoryMoved, registerRequestsUndo } from './historyActions'

/**
 * The one question open at a time, module-level because the doors that ask (the keys, the header,
 * ⌘K, the sheet, the toasts) are not ancestors of the one `HistoryDialogs` `Layout` mounts.
 */
let open: { question: HistoryQuestion; resolve: (yes: boolean) => void } | null = null
const listeners = new Set<() => void>()

function setOpen(next: typeof open) {
  open = next
  for (const fn of [...listeners]) fn()
}

/** Ask [question]; a second question while one is open answers the first no. */
export function askHistoryQuestion(question: HistoryQuestion): Promise<boolean> {
  open?.resolve(false)
  return new Promise((resolve) => {
    setOpen({ question, resolve })
  })
}

function answer(yes: boolean) {
  const current = open
  if (current == null) return
  setOpen(null)
  current.resolve(yes)
}

function useOpenQuestion(): HistoryQuestion | null {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn)
      return () => {
        listeners.delete(fn)
      }
    },
    () => open?.question ?? null,
    () => null,
  )
}

/** The three confirms (undo plan §4.5), mounted once in `Layout`. */
export function HistoryDialogs() {
  const question = useOpenQuestion()
  const verb = question?.direction === 'redo' ? 'Redo' : 'Undo'
  return (
    <AlertDialog open={question != null} onOpenChange={(next) => !next && answer(false)}>
      <AlertDialogContent>
        {question?.kind === 'conflict' && (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>{question.mine ? 'Undo back to here?' : `Undo ${question.later.length + 1} changes?`}</AlertDialogTitle>
              <AlertDialogDescription>
                {question.message}.{' '}
                {question.mine
                  ? 'Undoing it takes these back too, with every change of yours since it:'
                  : 'Undoing it takes these back too:'}
              </AlertDialogDescription>
            </AlertDialogHeader>
            <ul className="flex max-h-60 flex-col gap-1 overflow-y-auto text-sm" aria-label="Later changes">
              {question.later.map((later) => (
                <li key={later.id} className="flex items-baseline gap-2">
                  <span className="min-w-0 flex-1 truncate">{later.label}</span>
                  {later.source && <span className="shrink-0 text-xs text-muted-foreground">{later.source.name}</span>}
                </li>
              ))}
            </ul>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={() => answer(true)}>
                {question.mine ? 'Undo back to here' : `Undo ${question.later.length + 1} changes`}
              </AlertDialogAction>
            </AlertDialogFooter>
          </>
        )}
        {question?.kind === 'confirm' && (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>{verb} on stage?</AlertDialogTitle>
              <AlertDialogDescription>The show is running, and this {verb.toLowerCase()} moves what is on stage:</AlertDialogDescription>
            </AlertDialogHeader>
            <ul className="flex max-h-60 list-disc flex-col gap-1 overflow-y-auto pl-5 text-sm" aria-label="What moves">
              {question.moves.map((move) => (
                <li key={move}>{move}</li>
              ))}
            </ul>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction className="bg-amber-500 text-black hover:bg-amber-400" onClick={() => answer(true)}>
                {verb} on stage
              </AlertDialogAction>
            </AlertDialogFooter>
          </>
        )}
        {question?.kind === 'lock' && (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>The show is locked</AlertDialogTitle>
              <AlertDialogDescription>
                Editing is locked while the show runs. Unlock it to {verb.toLowerCase()} this change.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={() => answer(true)}>Unlock and {verb.toLowerCase()}</AlertDialogAction>
            </AlertDialogFooter>
          </>
        )}
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** Where a move goes: absent fields are the key's choice on the live project. */
export interface HistoryMoveOptions {
  projectId?: number
  entryId?: string
  programmerOnly?: boolean
  /**
   * Whether the entry is this window's source's own — false only for someone else's row in the
   * sheet. Taking an own entry back to here takes every later one of yours too, which its conflict
   * question says.
   */
  mine?: boolean
  /** The toast's words once it lands, in place of the entry's label: a surface keeping its own (D11). */
  message?: string
  /** No toast: the caller announces several moves as one. */
  quiet?: boolean
}

let lastToast: string | number | null = null
let toastSeq = 0

/**
 * One toast for the history at a time, each under its own id: a toast whose action was just clicked
 * is still on its way out for a moment, and a new one under its id would go with it.
 */
function historyToast(kind: 'success' | 'error', message: string, action?: { label: string; onClick: () => void }) {
  if (lastToast != null) toast.dismiss(lastToast)
  lastToast = `history-move-${++toastSeq}`
  if (kind === 'success') toast.success(message, { id: lastToast, action })
  else toast.error(message, { id: lastToast })
}

/**
 * Undo and redo from any door (undo plan §3.5, §4): the key's choice, a named step or entry, or the
 * entries a gesture's requests made (a toast's Undo, D11). Each goes through the lock and the
 * desk's questions (`runHistoryMove`) and ends in one toast — what came back, with the way back —
 * or one error toast in the record's words.
 */
export function useHistoryMove() {
  const [trigger] = useHistoryMoveMutation()
  const dispatch = useDispatch<typeof store.dispatch>()
  const history = useHistoryState()
  const fadeMs = Number(useProgrammerFade()) || 0
  const lockRequested = useSelector(selectLockRequested)
  const { data: program } = useProjectProgramStateQuery(history.projectId ?? 0, { skip: history.projectId == null })
  const latest = useRef({ history, fadeMs, lockRequested, running: program?.activeStackId != null })
  latest.current = { history, fadeMs, lockRequested, running: program?.activeStackId != null }

  return useMemo(() => {
    const move = async (direction: 'undo' | 'redo', options: HistoryMoveOptions = {}): Promise<HistoryMoveAnswer | null> => {
      const now = latest.current
      const projectId = options.projectId ?? now.history.projectId
      if (projectId == null) return null
      const showEntry =
        options.entryId != null
          ? isShowEntryId(options.entryId)
          : !options.programmerOnly && (direction === 'undo' ? now.history.undo : now.history.redo)?.kind === 'SHOW'
      const arg: HistoryMoveArg = {
        projectId,
        direction,
        fadeMs: now.fadeMs,
        ...(options.entryId ? { entryId: options.entryId } : {}),
        ...(options.programmerOnly ? { programmerOnly: true } : {}),
      }
      return runHistoryMove(
        {
          send: async (request) => {
            try {
              return { ok: true, answer: await trigger(request).unwrap() }
            } catch (error) {
              return { ok: false, refusal: historyRefusalOf(error) }
            }
          },
          ask: askHistoryQuestion,
          mine: options.mine ?? true,
          locked: showEntry && projectId === now.history.projectId && now.running && now.lockRequested,
          unlock: () => dispatch(unlockRequested()),
          done: (request, answered) => {
            if (answered.moved.some((m) => isShowEntryId(m.id))) notifyHistoryMoved(request.projectId)
            if (!options.quiet) announce(request.projectId, direction, answered, options.message)
          },
          refused: (_request, refusal) => historyToast('error', refusal.message),
        },
        arg,
      )
    }

    /** What came back, with the way back: Redo after an undo, Undo after a redo. */
    const announce = (projectId: number, direction: 'undo' | 'redo', answered: HistoryMoveAnswer, message?: string) => {
      const back = direction === 'undo' ? 'redo' : 'undo'
      historyToast('success', message ?? movedMessage(direction, answered), {
        label: back === 'redo' ? 'Redo' : 'Undo',
        onClick: () => void moveBack(back, projectId, answered),
      })
    }

    /**
     * Several entries, one at a time — newest first on an undo, oldest first on a redo — announced as
     * one move. An entry an earlier one already took with it (back to here) is skipped. Stops at the
     * first refused or cancelled; what moved before it is still announced.
     */
    const moveEntries = async (direction: 'undo' | 'redo', projectId: number, entryIds: readonly string[], message?: string) => {
      const moved: HistoryMoveAnswer['moved'] = []
      let complete = true
      for (const entryId of entryIds) {
        if (moved.some((m) => m.id === entryId)) continue
        const answered = await move(direction, { projectId, entryId, quiet: true })
        if (answered == null) {
          complete = false
          break
        }
        moved.push(...answered.moved)
      }
      if (moved.length > 0) announce(projectId, direction, { moved }, complete ? message : undefined)
      return complete && moved.length > 0
    }

    /** The way back from a move: the same steps or entries, the other way. */
    const moveBack = async (direction: 'undo' | 'redo', projectId: number, answered: HistoryMoveAnswer) => {
      const ids = answered.moved.map((m) => m.id)
      if (ids.length === 0) return
      if (!isShowEntryId(ids[0])) {
        const through = deepestStep(latest.current.history, direction, ids)
        if (through != null) await move(direction, { projectId, entryId: through, programmerOnly: true })
        return
      }
      // A move answers in the order it moved — newest first on an undo, oldest first on a redo — so
      // the way back, which goes the other way, takes them in reverse.
      await moveEntries(direction, projectId, [...ids].reverse())
    }

    /**
     * Undo the entries a gesture's requests made (its `X-History-Entry`s), newest first, one at a
     * time: the toasts' Undo, which names its change by request so it undoes the right thing after
     * other edits (D11). Stops at the first that is refused or cancelled.
     */
    const undoRequests = async (projectId: number, requestIds: readonly string[], message?: string): Promise<boolean> => {
      if (requestIds.length === 0) return false
      const page = await dispatch(
        historySlice.endpoints.historyPage.initiate({ projectId, limit: 200 }, { forceRefetch: true, subscribe: false }),
      )
        .unwrap()
        .catch(() => null)
      const found = page ? entriesForRequests(page, requestIds) : []
      const entries = found.filter((row) => !row.undone)
      if (entries.length === 0) {
        historyToast('error', found.length > 0 ? 'That change is already undone' : 'That change is no longer in the history')
        return false
      }
      return moveEntries(
        'undo',
        projectId,
        entries.map((entry) => entry.id),
        message,
      )
    }

    return { move, undo: () => move('undo'), redo: () => move('redo'), undoRequests }
  }, [trigger, dispatch])
}

/** Registers this window's undo for the toasts, and ⌘Z / ⇧⌘Z (undo plan §4.6). Mounted once, in `Layout`. */
export function HistoryHost() {
  const { undo, redo, undoRequests } = useHistoryMove()
  useEffect(() => registerRequestsUndo(undoRequests), [undoRequests])
  useUndoKeys(
    () => void undo(),
    () => void redo(),
  )
  return null
}

/** The step furthest down [direction]'s stack among [ids]: moving through it moves all of them. */
function deepestStep(history: HistoryState, direction: 'undo' | 'redo', ids: readonly string[]): string | null {
  const stack = direction === 'undo' ? history.programmer.undo : history.programmer.redo
  const wanted = new Set(ids)
  let deepest: string | null = null
  for (const step of stack) if (wanted.has(step.id)) deepest = step.id
  return deepest
}
