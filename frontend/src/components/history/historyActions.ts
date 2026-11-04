/**
 * A toast's Undo, without hooks or the store (undo plan D11): `HistoryHost` registers this window's
 * `undoRequests` here, so a store module or a sheet can offer Undo for the writes it just made.
 */
type RequestsUndo = (projectId: number, requestIds: readonly string[], message?: string) => Promise<boolean>

let requestsUndo: RequestsUndo | null = null

/** Registers the host's undo; answers the unregister. */
export function registerRequestsUndo(undo: RequestsUndo): () => void {
  requestsUndo = undo
  return () => {
    if (requestsUndo === undo) requestsUndo = null
  }
}

/**
 * Undo the entries [requestIds] made, through the mounted host: its lock, questions and toasts.
 * Answers false with no host, or when nothing came back.
 */
export function undoHistoryRequests(projectId: number, requestIds: readonly string[], message?: string): Promise<boolean> {
  return requestsUndo ? requestsUndo(projectId, requestIds, message) : Promise.resolve(false)
}

/**
 * A toast's **Undo** for the changes [requestIds] made: undone from the history by entry, so it takes
 * back the right thing however much has happened since. None for a gesture that wrote nothing.
 */
export function entryUndoAction(projectId: number, requestIds: readonly string[]) {
  return requestIds.length === 0
    ? undefined
    : { label: 'Undo', onClick: () => void undoHistoryRequests(projectId, requestIds) }
}

const movedListeners = new Set<(projectId: number) => void>()

/** Hear every show entry an undo or redo from this window moves — a store whose draft it may have overtaken. */
export function onHistoryMoved(fn: (projectId: number) => void): () => void {
  movedListeners.add(fn)
  return () => {
    movedListeners.delete(fn)
  }
}

export function notifyHistoryMoved(projectId: number): void {
  for (const fn of [...movedListeners]) fn(projectId)
}
