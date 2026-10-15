import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'

/**
 * **Where this window last was on the Show view, per project** (venue-visit plan S3): the stack it
 * had drilled into and the cue card `?cue=` had open.
 *
 * The URL alone held it (`/show/stacks/:id?cue=`), and every way back to Show linked to bare
 * `/show` — so a round trip to the programmer to change a cue lost both, and a running show's
 * auto-drill then put the operator on the live stack instead of the one they were reading.
 *
 * **Per tab, in `sessionStorage`**, like every other fact about how this window is being worked
 * (CLAUDE.md §"Windows, full screen and the hand"): two desk screens are two windows of one
 * profile, and the Show view on one should not follow where the other was reading.
 *
 * **Which doors read it** — the ones that mean "go back to Show": the view switcher's Show pill,
 * the sidebar's Show entry and its ⌘K twin (`navigation.ts`, through `showViewPath`). **Which do
 * not** — the ones that name a place of their own: the Prompt Book's *Edit cue* and a busk cue
 * pad's *View* (a stack and `?cue=`), the legacy `/program*` and `/run*` redirects (whose targets
 * are frozen), and the two that land on bare `/show` — the live stack, if one is running: a cue
 * slot's *View*, and `windows.show`, which another screen sends to put this one on the show. None
 * of them needs to: arriving anywhere on the Show view writes the position, so the next return
 * lands where that door left the window.
 *
 * Only a stack is remembered. On the stack list the project's entry is dropped, so a return lands
 * on bare `/show` and behaves as it always did — the stack list, or the live stack while the show
 * runs. A stack or cue that no longer exists is the Show view's to resolve on arrival: an unknown
 * stack redirects to `/show` (`ShowPage`'s stale-stack effect), and an unknown `?cue=` opens no
 * card. The view itself never writes either, since it records only a stack in the list and a cue
 * in that stack.
 */
export interface ShowPosition {
  stackId: number
  /** The cue whose card `?cue=` had open, or null for none. */
  cueId: number | null
}

/** Keyed by project id, as a string — the storage is JSON. */
type ShowPositions = Readonly<Record<string, ShowPosition>>

const EMPTY: ShowPositions = {}

function isId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

/** Narrow what storage holds: anything malformed is dropped entry by entry, never trusted. */
function parsePositions(parsed: unknown): ShowPositions {
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) return EMPTY
  const out: Record<string, ShowPosition> = {}
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!/^\d+$/.test(key) || value == null || typeof value !== 'object') continue
    const { stackId, cueId } = value as Record<string, unknown>
    if (!isId(stackId)) continue
    out[key] = { stackId, cueId: isId(cueId) ? cueId : null }
  }
  return out
}

const store = createSyncStore<ShowPositions>({
  key: 'show.position',
  fallback: EMPTY,
  parse: parsePositions,
  storage: sessionStorageArea,
})

/** The position this window last had on [projectId]'s Show view, or null for none. */
export function getShowPosition(projectId: number): ShowPosition | null {
  return store.getSnapshot()[String(projectId)] ?? null
}

/**
 * Record where this window is on [projectId]'s Show view: a stack and the open cue, or null when
 * it is on the stack list. A no-op when nothing changed, so the view can call it on every render
 * that settles a position.
 */
export function setShowPosition(projectId: number, position: ShowPosition | null): void {
  const current = store.getSnapshot()
  const key = String(projectId)
  const previous = current[key] ?? null
  if (position == null) {
    if (previous == null) return
    const next = { ...current }
    delete next[key]
    store.set(next)
    return
  }
  if (previous?.stackId === position.stackId && previous.cueId === position.cueId) return
  store.set({ ...current, [key]: { stackId: position.stackId, cueId: position.cueId } })
}

/** The route a "back to Show" door takes for [projectId]: the remembered stack and cue, else `/show`. */
export function showViewPath(projectId: number, position: ShowPosition | null = getShowPosition(projectId)): string {
  const base = `/projects/${projectId}/show`
  if (position == null) return base
  const cue = position.cueId != null ? `?cue=${position.cueId}` : ''
  return `${base}/stacks/${position.stackId}${cue}`
}

/** [showViewPath], re-rendering when the position moves — for a door drawn as a link. */
export function useShowViewPath(projectId: number): string {
  const positions = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
  return showViewPath(projectId, positions[String(projectId)] ?? null)
}

/** Test seam — a module-level cache outlives `sessionStorage.clear()`. */
export function resetShowPositionStore(): void {
  store.reset()
}
