import { useSyncExternalStore } from 'react'
import { projectIdOfPath } from '@/lib/windowViews'

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
 *
 * ## A request lands once (programmer-fixes plan D10)
 *
 * The request outlives the grid that answers it — the grid unmounts on every route change — so a
 * grid that mounted later would otherwise find the last one and land it again, re-selecting heads
 * and clearing the filter on the way back to the programmer. So the store remembers which nonce is
 * **spent**: a grid that lands a request spends it ([spendIncludeSelection]), and a grid mounting
 * later finds nothing to land. A request carries the project its window was showing and when it was
 * published; one published while no grid is mounted is landed by the next grid of that project to
 * mount within [INCLUDE_LANDING_WINDOW_MS] (Include from ⌘K, then the route change) and is otherwise
 * never landed ([includeSelectionLandable]).
 */
export interface IncludeSelectionRequest {
  fixtureKeys: readonly string[]
  groupKeys: readonly string[]
  nonce: number
  /** The project the publishing window was showing, from its address; null off a project route. */
  projectId: number | null
  /** `Date.now()` at publish. */
  publishedAt: number
}

/** How long a request published with no grid mounted waits for one. */
export const INCLUDE_LANDING_WINDOW_MS = 5000

const EMPTY: IncludeSelectionRequest = { fixtureKeys: [], groupKeys: [], nonce: 0, projectId: null, publishedAt: 0 }

let current: IncludeSelectionRequest = EMPTY
let nextNonce = 1
let spentNonce = 0
const listeners = new Set<() => void>()

/** Ask every mounted programmer sheet to select these fixtures. */
export function publishIncludeSelection(
  fixtureKeys: readonly string[],
  groupKeys: readonly string[] = [],
): void {
  if (fixtureKeys.length === 0 && groupKeys.length === 0) return
  current = {
    fixtureKeys,
    groupKeys,
    nonce: nextNonce++,
    projectId: typeof window === 'undefined' ? null : projectIdOfPath(window.location.pathname),
    publishedAt: Date.now(),
  }
  listeners.forEach((fn) => fn())
}

/**
 * May a grid of [projectId] land [request]? Not once spent, nor another project's; and one that
 * was already waiting when the grid mounted ([mountedAt], [nonceAtMount]) only within the window.
 */
export function includeSelectionLandable(
  request: IncludeSelectionRequest,
  projectId: number,
  mount: { mountedAt: number; nonceAtMount: number },
): boolean {
  if (request.nonce === 0 || request.nonce <= spentNonce) return false
  if (request.projectId != null && request.projectId !== projectId) return false
  if (request.nonce !== mount.nonceAtMount) return true
  return mount.mountedAt - request.publishedAt <= INCLUDE_LANDING_WINDOW_MS
}

/** Mark [nonce] landed, so no later grid lands it again. */
export function spendIncludeSelection(nonce: number): void {
  if (nonce > spentNonce) spentNonce = nonce
}

/** The request now, for a grid noting what was already waiting when it mounted. */
export function currentIncludeSelection(): IncludeSelectionRequest {
  return current
}

/** Test seam: back to no request, nothing spent. */
export function resetIncludeSelection(): void {
  current = EMPTY
  spentNonce = 0
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
