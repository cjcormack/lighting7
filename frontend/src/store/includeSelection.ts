import { useSyncExternalStore } from 'react'

/**
 * The fixtures the last Include pulled in, published for the programmer sheet to select —
 * MagicQ's "Select Heads on Include". A small detail with outsized workflow impact: you
 * Include a cue and can immediately fan, locate or edit exactly its heads.
 *
 * ## Why a module-level store rather than a prop
 *
 * The programmer sheet is mounted in two places — the `/programmer` route and the Program
 * view's embedded pane — so a Record/Include control in either has no prop path to the other's
 * container.
 *
 * This publishes only the *request*: "select these keys". Containers opt in
 * (`respondToIncludeSelection`), so the plain Fixtures and Groups lists are unaffected.
 *
 * Selection state itself now lives in `store/selectionSlice`, which this predates. The two are
 * not redundant: the slice holds *what is selected* and answers reads from outside the list,
 * while this carries a one-shot *imperative request* that only a mounted, opted-in container
 * can honour — it has to map fixture keys onto row ids, which depend on that container's
 * rollup mode and filter. Publishing straight into the slice would need those row ids up
 * front, which the caller doesn't have.
 *
 * [nonce] rather than comparing the key arrays: including the same cue twice is a legitimate
 * gesture and must re-select, and the operator may have changed the selection in between.
 */
export interface IncludeSelectionRequest {
  fixtureKeys: readonly string[]
  groupKeys: readonly string[]
  nonce: number
}

const EMPTY: IncludeSelectionRequest = { fixtureKeys: [], groupKeys: [], nonce: 0 }

let current: IncludeSelectionRequest = EMPTY
let nextNonce = 1
const listeners = new Set<() => void>()

/** Ask every mounted programmer sheet to select these fixtures. */
export function publishIncludeSelection(
  fixtureKeys: readonly string[],
  groupKeys: readonly string[] = [],
): void {
  if (fixtureKeys.length === 0 && groupKeys.length === 0) return
  current = { fixtureKeys, groupKeys, nonce: nextNonce++ }
  listeners.forEach((fn) => fn())
}

function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange)
  return () => {
    listeners.delete(onStoreChange)
  }
}

function getSnapshot(): IncludeSelectionRequest {
  return current
}

export function useIncludeSelectionRequest(): IncludeSelectionRequest {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
