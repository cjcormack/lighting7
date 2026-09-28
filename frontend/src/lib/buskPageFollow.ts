import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'

/**
 * **Follow / local for the showing busk page** — whether this window's busk page is the desk's.
 *
 * The desk still holds a showing page and still owns it for the reason it always did (see
 * `api/buskPageApi.ts`): a hardware *next page* button and a tab click are two ways of making one
 * gesture, so the surface needs one thing to move. What that argument never established is that
 * *every* window must be pinned to it — and on two screens it is wrong, because the flow this
 * exists for is colour templates on one screen and position templates on another, pressed onto one
 * selection. Reported on the desk 2026-09-16.
 *
 * **Two flags, never one.** This is deliberately a *second* per-tab fact beside
 * `lib/deskFollow.ts`'s, not a widening of it. A single flag cannot express the flow above:
 * following would pin both screens to one page, and unlinking to get two pages would take the
 * shared selection with it, so the operator would have to select twice. Nothing here may read the
 * selection's flag and nothing there may read this one.
 *
 * **What is reused, and what is not.** The machinery is `createSyncStore` + `sessionStorageArea`,
 * and the shape is `deskFollow`'s to the letter — default follow, unlink snapshots what this window
 * is showing and leaves the desk's alone, re-link adopts the desk's and publishes nothing,
 * `sessionStorage` because the two desk screens are two windows of one browser profile. What is
 * *not* shared is a generic follow/local factory over both, for two reasons. The flags must be
 * independent, and two instances of one factory is a standing invitation to give it a shared
 * argument. And this flag is **tri-state** where the selection's is binary: `null` means *this tab
 * has not decided yet*, which is what lets `?page=` mean "this window's page" exactly once, on
 * arrival, without a reload of a following window reading its own mirrored URL back as a
 * deliberate statement (see [isBuskPageDecided]).
 *
 * **Not per project.** The desk clears its showing page on a project switch, so a page id from the
 * project you left resolves against nothing and each window falls through to its own fallback —
 * which is what a following window does too. One fact, one behaviour.
 */

export const BUSK_PAGE_FOLLOW_KEY = 'busk.pageFollow'
export const BUSK_LOCAL_PAGE_KEY = 'busk.localPage'

/** `null` is *undecided* — treated as following, but distinguishable from a deliberate follow. */
const followStore = createSyncStore<boolean | null>({
  key: BUSK_PAGE_FOLLOW_KEY,
  fallback: null,
  parse: (parsed) => (typeof parsed === 'boolean' ? parsed : null),
  storage: sessionStorageArea,
})

/** The page this window is showing while unlinked. `null` before it holds one. */
const localStore = createSyncStore<number | null>({
  key: BUSK_LOCAL_PAGE_KEY,
  fallback: null,
  parse: (parsed) => (typeof parsed === 'number' && Number.isInteger(parsed) ? parsed : null),
  storage: sessionStorageArea,
})

/** Is this window showing the desk's page? Undecided counts as following. */
export function useBuskPageFollow(): boolean {
  return (
    useSyncExternalStore(followStore.subscribe, followStore.getSnapshot, followStore.getServerSnapshot) !==
    false
  )
}

/** For a reader that only needs the answer at click time. */
export function isFollowingBuskPage(): boolean {
  return followStore.getSnapshot() !== false
}

/**
 * Has this tab made its follow decision yet?
 *
 * `?page=` is *this window's* page when the window arrives carrying one — but the view also mirrors
 * the showing page back into `?page=` on every change, so a reload finds a parameter it wrote
 * itself. Without this, a following window would read its own mirror as a deliberate statement and
 * unlink on every refresh.
 *
 * This is the **live** read, for the arrival effect, which must see its own write on a re-run.
 * [useBuskPageDecided] is the rendered one, and the difference between them is load-bearing — see
 * its doc.
 */
export function isBuskPageDecided(): boolean {
  return followStore.getSnapshot() !== null
}

/**
 * The same question, as a **rendered** value, which lags the live one by exactly one render.
 *
 * That lag is the point. The arrival effect and the `?page=` mirror effect run in that order in one
 * commit, and the arrival effect's `unlinkBuskPage` only *schedules* the re-render that moves
 * `activePage` — so the mirror effect beside it still holds the page the window is unlinking
 * *from*, and writes it into the URL before the next pass corrects it. Gating the mirror on a live
 * [isBuskPageDecided] cannot fix that: by the time it runs, the arrival effect has already flipped
 * the flag. Gating it on `useBuskPageFollow()` cannot either, because that collapses `null` and
 * `true` to the same `true` and so never changes in the keep-following arm. Only the rendered
 * tri-state does: false on the commit the decision is made in, true on the one after it.
 */
export function useBuskPageDecided(): boolean {
  return (
    useSyncExternalStore(followStore.subscribe, followStore.getSnapshot, followStore.getServerSnapshot) !==
    null
  )
}

/** The page this window holds while unlinked. Meaningful only while [useBuskPageFollow] is false. */
export function useLocalBuskPage(): number | null {
  return useSyncExternalStore(localStore.subscribe, localStore.getSnapshot, localStore.getServerSnapshot)
}

export function getLocalBuskPage(): number | null {
  return localStore.getSnapshot()
}

/** Move this window's own page. A no-op in meaning while following: the desk's is not edited here. */
export function setLocalBuskPage(pageId: number): void {
  localStore.set(pageId)
}

/**
 * Unlink: keep showing [pageId] in this window alone, and stop following. The desk's is untouched.
 *
 * [pageId] is what the caller is *showing* — not the desk's raw value, which may be null while the
 * window sits on its `?page=` or on the first page. Unlinking is "keep what I have", so handing it
 * the desk's null would drop the window to the first page at the moment it unlinked.
 */
export function unlinkBuskPage(pageId: number | null): void {
  if (pageId != null) localStore.set(pageId)
  followStore.set(false)
}

/** Re-link: follow the desk's page again and drop this window's copy. Nothing is published. */
export function relinkBuskPage(): void {
  followStore.set(true)
  localStore.set(null)
}

/**
 * The page the busk view is **showing** in this window right now — whichever of the desk's page,
 * this window's own, `?page=` or the first page won (`BuskingView` resolves that and reports it
 * here). A plain module value, never stored: it is a reading of what is on screen, and a reload
 * resolves it again.
 *
 * It exists for one reader, a `windows.viewOptions {pageFollows: 'false'}` from another window's
 * Screens row (desk-follow plan D6), which asks this window to *keep what it has* as its own. The
 * handler runs in `Layout`, far from the view that knows what it has, and [unlinkBuskPage] is
 * "keep what I have" only if it is handed what that is — handed nothing, a window on the desk's
 * page would drop to its `?page=` or the first page at the moment it unlinked.
 */
let showing: number | null = null

export function reportShowingBuskPage(pageId: number | null): void {
  showing = pageId
}

export function showingBuskPage(): number | null {
  return showing
}

/**
 * Record that this window follows, without changing what it shows.
 *
 * Only the arrival effect calls it, and only to move the flag off *undecided* — see
 * [isBuskPageDecided] for the reload it exists to stop.
 */
export function keepFollowingBuskPage(): void {
  followStore.set(true)
}

/** Test seam: both stores back to their fallbacks with no listeners. */
export function resetBuskPageFollowStores(): void {
  followStore.reset()
  localStore.reset()
  showing = null
}
