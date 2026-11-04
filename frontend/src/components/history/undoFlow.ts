import type { HistoryLater, HistoryMoveAnswer, HistoryMoveArg, HistoryRefusal } from '@/store/history'

/**
 * What an undo or redo asks the operator before it is sent again (undo plan §4.5): the later changes
 * an older entry collides with (D3), what it moves on a running stage (D6), or the show-editing lock.
 */
export type HistoryQuestion =
  | { kind: 'conflict'; direction: 'undo'; message: string; later: HistoryLater[]; mine: boolean }
  | { kind: 'confirm'; direction: 'undo' | 'redo'; moves: string[] }
  | { kind: 'lock'; direction: 'undo' | 'redo' }

/** What one undo or redo needs from its host: the send, the dialogs and the toasts. */
export interface HistoryMoveDeps {
  send(arg: HistoryMoveArg): Promise<{ ok: true; answer: HistoryMoveAnswer } | { ok: false; refusal: HistoryRefusal }>
  ask(question: HistoryQuestion): Promise<boolean>
  /** Whether the entry is the caller's own: back to here then takes every later one of theirs too. */
  mine: boolean
  /** Whether the move would change the stored show under the show-editing lock. */
  locked: boolean
  unlock(): void
  done(arg: HistoryMoveArg, answer: HistoryMoveAnswer): void
  refused(arg: HistoryMoveArg, refusal: HistoryRefusal): void
}

/**
 * One undo or redo, through its questions: the lock first, then the desk's 409s — a conflict is
 * resent with `backToHere` once the operator takes the later changes too, a stage move with
 * `confirm` — each asked at most once. Anything else, or a Cancel, sends no more. Answers what moved,
 * or null.
 */
export async function runHistoryMove(deps: HistoryMoveDeps, arg: HistoryMoveArg): Promise<HistoryMoveAnswer | null> {
  if (deps.locked) {
    if (!(await deps.ask({ kind: 'lock', direction: arg.direction }))) return null
    deps.unlock()
  }
  let request = arg
  for (;;) {
    const result = await deps.send(request)
    if (result.ok) {
      deps.done(request, result.answer)
      return result.answer
    }
    const refusal = result.refusal
    if (refusal.kind === 'conflict' && request.direction === 'undo' && !request.backToHere && refusal.later.length > 0) {
      if (!(await deps.ask({ kind: 'conflict', direction: 'undo', message: refusal.message, later: refusal.later, mine: deps.mine }))) return null
      request = { ...request, backToHere: true }
      continue
    }
    if (refusal.kind === 'confirm' && !request.confirm) {
      if (!(await deps.ask({ kind: 'confirm', direction: request.direction, moves: refusal.moves }))) return null
      request = { ...request, confirm: true }
      continue
    }
    deps.refused(request, refusal)
    return null
  }
}

/** Whether [entryId] names a show entry rather than a programmer step, the lock's question. */
export function isShowEntryId(entryId: string): boolean {
  return entryId.startsWith('e-')
}

/** The toast after a move: what came back, or what went again. */
export function movedMessage(direction: 'undo' | 'redo', answer: HistoryMoveAnswer): string {
  const verb = direction === 'undo' ? 'Undone' : 'Redone'
  const [first, ...rest] = answer.moved
  if (first == null) return verb
  return rest.length === 0 ? `${verb} · ${first.label}` : `${verb} · ${first.label} and ${rest.length} more`
}
