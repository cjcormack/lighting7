import { useCallback, useEffect, useRef } from 'react'
import { useDispatch } from 'react-redux'
import { toast } from 'sonner'
import { current } from '@reduxjs/toolkit'
import { restApi } from './restApi'
import { store } from './index'
import { lightingApi } from '../api/lightingApi'
import { formatError } from '@/lib/formatError'
import { recordsOnPage, toLayoutRequest } from '@/lib/buskLayout'
import { rigRows, toRigRequest, type RigIds } from '@/lib/buskRig'
import type { BuskRig, BuskRigErrorCode, BuskRigRequest } from '@/api/buskRigApi'
import type {
  AddBuskPadRequest,
  BuskLayoutRequest,
  BuskPage,
  BuskPressRequest,
  BuskPressResponse,
  CreateBuskPageRequest,
  RenameBuskPageRequest,
  ReorderBuskPagesRequest,
} from '@/api/buskApi'

/**
 * The busk layout's REST half: the pages, the whole-page write, and the press.
 *
 * Two things here are not the shapes the rest of the store uses, and both are argued:
 *
 * - **A layout write patches the cache from its own response**, where `reorderTemplates` patches
 *   optimistically and then invalidates. The difference is the response: that route answers
 *   `void`, so a refetch is the only way to learn what it did; this one answers **the page as
 *   written, carrying the ids it minted**. Those ids are load-bearing — the *next* gesture must
 *   send them or the server treats every pad as new and recreates the lot — so taking them from
 *   the response is both cheaper and more correct than a refetch.
 * - **The WS bridge suppresses the echo of our own write.** See {@link startBuskBridge}.
 */

// ─── Tag helpers ────────────────────────────────────────────────────────

/**
 * Page ids are prefixed because they share `BuskPage` with the project id, and `projectId: 7`
 * beside `pageId: 7` would be the same tag. Don't "tidy" the prefix away.
 */
function pageTag(pageId: number) {
  return { type: 'BuskPage' as const, id: `page-${pageId}` }
}

// ─── Endpoints ──────────────────────────────────────────────────────────

export const buskApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    buskPages: build.query<BuskPage[], number>({
      query: (projectId) => `projects/${projectId}/busk/pages`,
      providesTags: (result, _error, projectId) => [
        { type: 'BuskPage' as const, id: projectId },
        ...(result ?? []).map((page) => pageTag(page.id)),
        'BuskPage' as const,
      ],
    }),

    createBuskPage: build.mutation<BuskPage, { projectId: number } & CreateBuskPageRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/busk/pages`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (result) => (result == null ? [] : ['BuskPage']),
    }),

    renameBuskPage: build.mutation<
      BuskPage,
      { projectId: number; pageId: number } & RenameBuskPageRequest
    >({
      query: ({ projectId, pageId, ...body }) => ({
        url: `projects/${projectId}/busk/pages/${pageId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: (result) => (result == null ? [] : ['BuskPage']),
    }),

    deleteBuskPage: build.mutation<void, { projectId: number; pageId: number }>({
      query: ({ projectId, pageId }) => ({
        url: `projects/${projectId}/busk/pages/${pageId}`,
        method: 'DELETE',
      }),
      // Guarded on the error rather than the result: a 204 carries no body, so `result` is
      // undefined on success too (the `deleteTemplate` note).
      invalidatesTags: (_result, error) => (error != null ? [] : ['BuskPage']),
    }),

    reorderBuskPages: build.mutation<void, { projectId: number } & ReorderBuskPagesRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/busk/pages/reorder`,
        method: 'POST',
        body,
      }),
      async onQueryStarted({ projectId, pageIds }, { dispatch, queryFulfilled }) {
        const patch = dispatch(
          buskApi.util.updateQueryData('buskPages', projectId, (draft) => {
            const order = new Map(pageIds.map((id, index) => [id, index]))
            draft.forEach((page) => {
              const next = order.get(page.id)
              if (next != null) page.sortOrder = next
            })
            draft.sort((a, b) => a.sortOrder - b.sortOrder)
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patch.undo()
        }
      },
      invalidatesTags: (_result, error) => (error != null ? [] : ['BuskPage']),
    }),

    /**
     * The whole page (D10). Neither optimistic nor invalidating on its own — {@link useBuskLayoutCommit}
     * owns both, because a gesture's patch has to survive the round trip of the gesture before it.
     */
    saveBuskLayout: build.mutation<
      BuskPage,
      { projectId: number; pageId: number } & BuskLayoutRequest
    >({
      query: ({ projectId, pageId, ...body }) => ({
        url: `projects/${projectId}/busk/pages/${pageId}/layout`,
        method: 'PUT',
        body,
      }),
    }),

    /**
     * Append one pad to one bank (`AddBuskPadRequest`).
     *
     * **`pageId` is in the argument but in neither the URL nor the body.** It is here only for the
     * echo bookkeeping below, and it has to be: the WS frame and the HTTP reply are not ordered
     * against each other, so a frame that beat the response would invalidate, start a refetch of
     * the *pre-append* document, and let that stale read land after we wrote the response in. That
     * is the snap-back {@link startBuskBridge}'s suppression exists to prevent, arriving through a
     * new door. It cannot be read off the 201 body, because the window opens before the body does.
     *
     * **No `invalidatesTags`, and no optimistic patch.** The response is the whole page, so a
     * refetch would re-read every page's every pad — with each pad's embedded record summary — for
     * something already in hand; that is `saveBuskLayout`'s argument, unchanged. And nothing is
     * optimistic because the caller is never *showing* the page: the feedback is a toast.
     */
    addBuskPad: build.mutation<
      BuskPage,
      { projectId: number; pageId: number; bankId: number } & AddBuskPadRequest
    >({
      query: ({ projectId, bankId, templateId, lookId, cueId }) => ({
        url: `projects/${projectId}/busk/banks/${bankId}/pads`,
        method: 'POST',
        // Spelled out rather than spread, so `pageId` and `bankId` cannot leak into the body.
        body: { templateId, lookId, cueId },
      }),
      async onQueryStarted({ projectId, pageId }, { dispatch, queryFulfilled }) {
        beginBuskPageWrite(pageId)
        let landed = false
        try {
          const { data: page } = await queryFulfilled
          landed = true
          dispatch(buskApi.util.updateQueryData('buskPages', projectId, (draft) => upsertPage(draft, page)))
          // An append always changes a `buskPageCount`, so the library rows are refreshed from here
          // rather than from the WS bridge — see {@link invalidateBuskCounts}.
          dispatch(restApi.util.invalidateTags(BUSK_COUNT_TAGS))
        } catch {
          // Reported by the error-toast middleware. Swallowed here because RTK Query attaches no
          // handler to the promise `onQueryStarted` returns, so a rejection escaping this function
          // is an unhandled rejection — `reorderBuskPages` above catches for the same reason.
        } finally {
          endBuskPageWrite(pageId, landed)
        }
      },
    }),

    /**
     * The busk **rig** (busk-further plan §3.2), one per project. An empty rig arrives as `{}` —
     * lighting7 omits a defaulted empty list — and is read as `rows: []`; the show-all fallback is
     * `effectiveRig`'s, never the cache's.
     */
    buskRig: build.query<BuskRig, number>({
      query: (projectId) => `projects/${projectId}/busk/rig`,
      transformResponse: (response: BuskRig | null | undefined) => ({ rows: rigRows(response ?? {}) }),
      providesTags: ['BuskRig'],
    }),

    /**
     * The whole rig. Neither optimistic nor invalidating on its own — {@link useBuskRigCommit} owns
     * both, for `saveBuskLayout`'s reason: a gesture's patch has to survive the round trip of the
     * gesture before it.
     */
    saveBuskRig: build.mutation<BuskRig, { projectId: number } & BuskRigRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/busk/rig`,
        method: 'PUT',
        body,
      }),
      transformResponse: (response: BuskRig | null | undefined) => ({ rows: rigRows(response ?? {}) }),
    }),

    /**
     * A press, whatever the pad holds. No invalidation: what the rig did comes back over the
     * programmer's applied state and the cue stack list, which the pad rings already read.
     */
    pressBuskPad: build.mutation<
      BuskPressResponse,
      { projectId: number; padId: number } & BuskPressRequest
    >({
      query: ({ projectId, padId, ...body }) => ({
        url: `projects/${projectId}/busk/pads/${padId}/press`,
        method: 'POST',
        body,
      }),
    }),

    /**
     * The **showing page**, streamed — see `api/buskPageApi.ts` for what it is and why it is
     * server-owned.
     *
     * A cache entry rather than a hook's `useState`, following `deskSelection` and the surface
     * streams: two readers share one subscription and RTK Query owns the teardown. There is no REST
     * endpoint behind it and nothing to invalidate — the frame is the only source — so `queryFn`
     * seeds from the WS layer's snapshot and everything after arrives by push.
     *
     * Not keyed by project: the desk holds one showing page and clears it on project switch, so a
     * per-project entry would be a second, disagreeing answer.
     */
    buskShowingPage: build.query<number | null, void>({
      queryFn: () => ({ data: lightingApi.buskPage.getState() ?? null }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.buskPage.subscribe((pageId) => {
          updateCachedData(() => pageId)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

/**
 * Move the **desk's** showing page. The `busk.pageState` frame is the acknowledgement, and every
 * window *following* the desk's page moves with it; one that has unlinked (`lib/buskPageFollow.ts`)
 * receives the frame and does not resolve against it.
 *
 * Returns whether the gesture actually reached the desk (false when the socket is down) — see
 * `BuskPageWsApi.setPage`'s doc for why `BuskingView` needs that boolean rather than treating this
 * like every other fire-and-forget gesture.
 */
export function setShowingBuskPage(pageId: number): boolean {
  return lightingApi.buskPage.setPage(pageId)
}

export const {
  useBuskShowingPageQuery,
  useBuskPagesQuery,
  useCreateBuskPageMutation,
  useRenameBuskPageMutation,
  useDeleteBuskPageMutation,
  useReorderBuskPagesMutation,
  useSaveBuskLayoutMutation,
  useAddBuskPadMutation,
  usePressBuskPadMutation,
  useBuskRigQuery,
  useSaveBuskRigMutation,
} = buskApi

// ─── The echo-suppressing bridge ────────────────────────────────────────

/**
 * Pages with a write in flight or queued, **counted** rather than flagged.
 *
 * A count because there are now two independent writers — the commit queue, and an append from a
 * surface that is not the busk view. With a plain set, whichever finished first would clear the
 * flag while the other was still writing, un-suppressing mid-gesture. That is the very race the
 * queue's own docblock warns about, and a refcount removes the class of it rather than arguing
 * that the two cannot overlap today.
 */
const writesInFlight = new Map<number, number>()
/** When each page's last write response landed, so a frame that beat it can be recognised. */
const settledAt = new Map<number, number>()
const ECHO_GRACE_MS = 500

function beginBuskPageWrite(pageId: number) {
  writesInFlight.set(pageId, (writesInFlight.get(pageId) ?? 0) + 1)
}

/**
 * @param landed whether the write actually reached the server. **Only a write that landed opens the
 * grace window**: the window exists because our own successful write broadcasts a frame that may
 * arrive after its HTTP reply, and a write that *failed* broadcasts nothing — so stamping on
 * failure would swallow 500 ms of somebody else's genuine frames for no echo of our own.
 */
function endBuskPageWrite(pageId: number, landed: boolean) {
  const left = (writesInFlight.get(pageId) ?? 1) - 1
  if (left > 0) writesInFlight.set(pageId, left)
  else writesInFlight.delete(pageId)
  // Stamped on the way out rather than per response, so the window starts from the *last* write to
  // settle rather than from whichever finished first.
  if (landed) settledAt.set(pageId, Date.now())
}

/** The library lists, whose rows carry each record's `buskPageCount`. */
const BUSK_COUNT_TAGS: ('TemplateList' | 'LookList')[] = ['TemplateList', 'LookList']

/**
 * Refresh the library rows' `buskPageCount`, but **only when the set of records on the page moved**.
 *
 * A layout write fires `busk.layoutChanged` and never `templateListChanged` / `lookListChanged` —
 * firing those would be a whole-library refetch per gesture, and `LibraryPalette` *is* mounted and
 * subscribed to both lists throughout busk edit mode, so that cost is real rather than theoretical.
 * Most edit-mode gestures are moves and resizes, which change no count at all.
 *
 * `recordsOnPage` answers per *page*, deduped, which is exactly what the count means: dragging a pad
 * between banks, reordering one, or adding a second pad for a record already on the page all leave
 * this set alone and refresh nothing.
 */
function invalidateBuskCounts(
  dispatch: typeof store.dispatch,
  before: Set<string> | null,
  after: Set<string>,
) {
  const changed =
    before == null ||
    before.size !== after.size ||
    [...after].some((key) => !before.has(key))
  if (changed) dispatch(restApi.util.invalidateTags(BUSK_COUNT_TAGS))
}

/** Write a page the server answered with into the list cache. Shared by every write that has one. */
function upsertPage(draft: BuskPage[], page: BuskPage) {
  const index = draft.findIndex((entry) => entry.id === page.id)
  if (index === -1) draft.push(page)
  else draft[index] = page
}

function isOwnEcho(pageIds: number[]): boolean {
  // A layout write announces exactly one page. Page CRUD and reorder announce several — a delete
  // carries the deleted id *plus every survivor*, because their sortOrder moved — so a multi-id
  // frame is never ours to swallow.
  if (pageIds.length !== 1) return false
  const pageId = pageIds[0]
  if ((writesInFlight.get(pageId) ?? 0) > 0) return true
  const settled = settledAt.get(pageId)
  return settled != null && Date.now() - settled < ECHO_GRACE_MS
}

/**
 * `busk.layoutChanged` → invalidate exactly the pages it names.
 *
 * Module scope, the default of the three forms in CLAUDE.md §"Where a WS bridge subscribes": nothing
 * on the earliest render path imports this slice, so there is no reason to defer it to `main.tsx`.
 *
 * **A foreign page id matches no tag and is a silent no-op**, which is why the frame needs no
 * project lookup: only pages this cache holds can be invalidated by their own tag. The bare tag
 * goes out alongside a multi-id frame, because a page *created* in another tab has an id this
 * cache has never seen and nothing else would catch it.
 *
 * **The echo.** Our own layout write broadcasts this frame back to us. Left alone that means a
 * refetch per gesture for a document the PUT response already carried — and, worse, a refetch that
 * can land between an optimistic patch and its own response, snapping the pads back and then
 * forward. So a single-page frame is dropped while that page is saving, or within a short grace
 * after its response (the HTTP reply and the frame are not ordered against each other).
 *
 * The cost is honest: a *foreign* single-page write landing inside that window is swallowed.
 * Per-gesture whole-page saves already concede last-write-wins (`FU-BUSK-EDIT-CONCURRENCY`) and the
 * reconnect resync is the backstop. The one case that would bite — this frame also fires for a
 * **record delete that took pads off the page**, and a stale pad's next PUT is a 400
 * `BUSK_LAYOUT_REF` — is covered from the other end: every layout failure re-reads the page.
 */
lightingApi.busk.subscribe((pageIds) => {
  if (pageIds.length === 0 || isOwnEcho(pageIds)) return
  const tags = pageIds.map(pageTag)
  store.dispatch(restApi.util.invalidateTags(pageIds.length > 1 ? [...tags, 'BuskPage'] : tags))
  // A *foreign* layout change can also have moved a `buskPageCount`. We cannot tell which records
  // from a frame that carries only page ids, so the lists are refreshed wholesale — rare, because
  // this is another desk editing, and the alternative is a count that is simply wrong.
  store.dispatch(restApi.util.invalidateTags(BUSK_COUNT_TAGS))
})

// ─── The rig bridge ─────────────────────────────────────────────────────

/** Rig writes in flight, and when the last one settled — the page bridge's bookkeeping, for one rig. */
let rigWritesInFlight = 0
let rigSettledAt: number | null = null

function beginBuskRigWrite() {
  rigWritesInFlight += 1
}

function endBuskRigWrite(landed: boolean) {
  rigWritesInFlight = Math.max(0, rigWritesInFlight - 1)
  if (landed) rigSettledAt = Date.now()
}

function isOwnRigEcho(): boolean {
  if (rigWritesInFlight > 0) return true
  return rigSettledAt != null && Date.now() - rigSettledAt < ECHO_GRACE_MS
}

/**
 * `busk.rigChanged` → re-read the rig.
 *
 * Module scope, like the page bridge above and for the same reason: nothing on the earliest render
 * path imports this slice. The frame is **payload-free** — there is one rig per project (D1) — so
 * there is nothing to key on and the whole `BuskRig` tag is invalidated; a tab on another project
 * holds no `BuskRig` entry for this one, so the refetch is that tab's own rig, which is what it
 * would want anyway.
 *
 * **The echo.** Our own write broadcasts this frame back to us, and a refetch per gesture would land
 * between an optimistic patch and its own response — the snap-back the commit queue exists to
 * avoid. So the frame is dropped while a rig write is in flight or inside a short grace after one
 * settled. The cost is the page bridge's: a *foreign* write inside that window is swallowed, and the
 * reconnect resync is the backstop. The frame also fires for a **group or patch delete that took
 * tiles off the rig**, and if that lands inside our window a stale tile's next PUT is a 400
 * `BUSK_RIG_REF` — covered from the other end, because every rig failure re-reads the rig.
 */
lightingApi.busk.subscribeRigChanged(() => {
  if (isOwnRigEcho()) return
  store.dispatch(restApi.util.invalidateTags(['BuskRig']))
})

// ─── The commit queue ───────────────────────────────────────────────────

/** One edit, as a function of the page — replayable against whatever the server last confirmed. */
export type BuskLayoutOp = (page: BuskPage) => BuskPage

interface PageQueue {
  ops: BuskLayoutOp[]
  /** The last document the server confirmed. Seeded from the cache when the queue starts. */
  confirmed: BuskPage | null
  running: boolean
}

const queues = new Map<number, PageQueue>()

/**
 * Apply an op to one page in the cache.
 *
 * `current()` is not optional: the op clones the page it is handed, and handing it an Immer draft
 * would put live proxies inside the object written back — which read fine for one tick and then
 * throw once the produce call that made them has finished.
 */
function patchPage(
  dispatch: typeof store.dispatch,
  projectId: number,
  pageId: number,
  op: BuskLayoutOp,
) {
  dispatch(
    buskApi.util.updateQueryData('buskPages', projectId, (draft) => {
      const index = draft.findIndex((page) => page.id === pageId)
      if (index === -1) return
      draft[index] = op(current(draft[index]) as BuskPage)
    }),
  )
}

function queueFor(pageId: number): PageQueue {
  const existing = queues.get(pageId)
  if (existing) return existing
  const created: PageQueue = { ops: [], confirmed: null, running: false }
  queues.set(pageId, created)
  return created
}

/**
 * Save every edit-mode gesture as a whole page, one at a time, without the UI ever going backwards.
 *
 * **Operations are queued, not documents.** A gesture is `(page) => page`, so it can be replayed
 * against the freshest *confirmed* document at send time. A queue of pre-computed documents could
 * not: gesture 2's document was built before gesture 1's response existed, so it would name none of
 * the ids that response minted and the server would recreate every pad it touched.
 *
 * The optimistic patch and the wire both apply the same ops in the same order, and the client's
 * `normalisePage` mirrors the server's dense renumbering, so the only thing the two can disagree
 * about is ids — which step 4 reconciles by writing the **last** response into the cache. Writing
 * an intermediate one would describe the world a gesture ago, and that is exactly the snap-back
 * this exists to avoid.
 */
export function useBuskLayoutCommit(projectId: number, pageId: number | null) {
  const dispatch = useDispatch<typeof store.dispatch>()
  const [saveLayout] = useSaveBuskLayoutMutation()

  useEffect(() => {
    // Tidy the queue away when this page stops being shown — but **only while it is idle**. The
    // drain loop holds its `PageQueue` by closure rather than by map lookup, so deleting a running
    // one does not stop it: the next commit for the same page would mint a *second* queue beside
    // it, and the two then race through the shared `writesInFlight` / `settledAt` globals. The
    // first to finish would patch the cache with its own now-stale document and drain
    // `writesInFlight` while
    // the other is still writing, un-suppressing the echo mid-gesture — precisely the snap-back
    // this queue exists to prevent. The dep is `pageId`, so an ordinary page-tab switch is enough
    // to reach it.
    return () => {
      if (pageId != null && queues.get(pageId)?.running !== true) queues.delete(pageId)
    }
  }, [pageId])

  return useCallback(
    (op: BuskLayoutOp) => {
      if (pageId == null) return
      const queue = queueFor(pageId)

      // Seeded *before* the optimistic patch: reading the cache afterwards would take the gesture
      // in as though the server had confirmed it, and the op would then be applied twice.
      if (queue.ops.length === 0 && !queue.running) {
        const cached = buskApi.endpoints.buskPages.select(projectId)(store.getState()).data
        queue.confirmed = cached?.find((page) => page.id === pageId) ?? null
      }

      patchPage(dispatch, projectId, pageId, op)
      queue.ops.push(op)
      if (queue.running) return
      queue.running = true
      beginBuskPageWrite(pageId)

      void (async () => {
        // What the page held before this burst, so the counts are only refreshed if the burst
        // actually changed which records are on it — see {@link invalidateBuskCounts}.
        const recordsBefore = queue.confirmed == null ? null : recordsOnPage(queue.confirmed)
        let landed = false
        try {
          while (queue.ops.length > 0) {
            const next = queue.ops.shift()!
            const base = queue.confirmed
            if (base == null) throw new Error('The busk page is no longer loaded')
            queue.confirmed = await saveLayout({
              projectId,
              pageId,
              ...toLayoutRequest(next(base)),
            }).unwrap()
            landed = true
            // Only the last response describes the world the operator is looking at.
            if (queue.ops.length === 0) {
              const settled = queue.confirmed
              patchPage(dispatch, projectId, pageId, () => settled)
            }
          }
          if (queue.confirmed != null) {
            invalidateBuskCounts(dispatch, recordsBefore, recordsOnPage(queue.confirmed))
          }
        } catch (err) {
          queue.ops.length = 0
          const restore = queue.confirmed
          if (restore != null) patchPage(dispatch, projectId, pageId, () => restore)
          // Always re-read on a failure: the write may have been refused because a record behind
          // one of these pads was deleted, and that frame is one the echo suppression can swallow.
          dispatch(restApi.util.invalidateTags([pageTag(pageId)]))
          toast.error(formatError(err))
        } finally {
          queue.running = false
          endBuskPageWrite(pageId, landed)
        }
      })()
    },
    [dispatch, projectId, pageId, saveLayout],
  )
}

// ─── The rig commit queue ───────────────────────────────────────────────

/** One edit to the rig, as a function of the rig — replayable against the last confirmed document. */
export type BuskRigOp = (rig: BuskRig) => BuskRig

interface RigQueue {
  ops: BuskRigOp[]
  confirmed: BuskRig | null
  running: boolean
}

/** One rig per project, and the busk view shows one project at a time. */
const rigQueues = new Map<number, RigQueue>()

function patchRig(dispatch: typeof store.dispatch, projectId: number, op: BuskRigOp) {
  dispatch(
    buskApi.util.updateQueryData('buskRig', projectId, (draft) => {
      // `current()` for `patchPage`'s reason: the op clones what it is handed, and an Immer proxy
      // inside the object written back throws once the produce call that made it has finished.
      return op(current(draft) as BuskRig)
    }),
  )
}

/**
 * What a refused rig write says, **by code**: the three refusals name a row and tile by position,
 * and the code says which kind of thing was wrong — a malformed document, an id that is not this
 * rig's, or a record that is gone. A `RigRequestError` thrown before the PUT (a group the desk
 * cannot name) carries its own sentence and no code.
 */
export function rigWriteFailureMessage(err: unknown): string {
  const code = (err as { data?: { code?: string } } | null)?.data?.code as BuskRigErrorCode | undefined
  const message = formatError(err)
  switch (code) {
    case 'BUSK_RIG_INVALID':
      return `The rig was refused: ${message}`
    case 'BUSK_RIG_IDENTITY':
      return `The rig was refused — it names a row or tile the desk does not have: ${message}`
    case 'BUSK_RIG_REF':
      return `The rig was refused — a group, fixture or cell on it is gone: ${message}`
    default:
      return message
  }
}

/**
 * Save every rig gesture as a whole document, one at a time, without the band ever going backwards
 * — {@link useBuskLayoutCommit}'s queue over one rig instead of a page.
 *
 * **Operations are queued, not documents**, replayed against the freshest confirmed rig at send
 * time so gesture 2 carries the ids gesture 1's response minted; only the **last** response is
 * written into the cache. On a failure the queue is dropped, the last confirmed rig restored, the
 * rig re-read (a `BUSK_RIG_REF` means a record behind a tile was deleted, and that frame is one the
 * echo suppression can swallow) and the refusal toasted by code.
 *
 * `ids` is read **at send time** through a ref rather than captured per gesture: the patch list can
 * arrive after the first gesture is enqueued, and a group the desk cannot name is refused by
 * `toRigRequest` in the loop and reported like any other failure.
 */
export function useBuskRigCommit(projectId: number, ids: RigIds) {
  const dispatch = useDispatch<typeof store.dispatch>()
  const [saveRig] = useSaveBuskRigMutation()
  const idsRef = useRef(ids)
  idsRef.current = ids

  useEffect(
    () => () => {
      // Idle queues only, for `useBuskLayoutCommit`'s reason: the drain loop holds its queue by
      // closure, so deleting a running one would let the next commit mint a second beside it.
      if (rigQueues.get(projectId)?.running !== true) rigQueues.delete(projectId)
    },
    [projectId],
  )

  return useCallback(
    (op: BuskRigOp) => {
      let queue = rigQueues.get(projectId)
      if (queue == null) {
        queue = { ops: [], confirmed: null, running: false }
        rigQueues.set(projectId, queue)
      }
      const held = queue

      // Seeded before the optimistic patch, or the gesture would be taken in as confirmed and
      // applied twice.
      if (held.ops.length === 0 && !held.running) {
        held.confirmed = buskApi.endpoints.buskRig.select(projectId)(store.getState()).data ?? null
      }

      patchRig(dispatch, projectId, op)
      held.ops.push(op)
      if (held.running) return
      held.running = true
      beginBuskRigWrite()

      void (async () => {
        let landed = false
        try {
          while (held.ops.length > 0) {
            const next = held.ops.shift()!
            const base = held.confirmed
            if (base == null) throw new Error('The rig is no longer loaded')
            held.confirmed = await saveRig({
              projectId,
              ...toRigRequest(next(base), idsRef.current),
            }).unwrap()
            landed = true
            if (held.ops.length === 0) {
              const settled = held.confirmed
              patchRig(dispatch, projectId, () => settled)
            }
          }
        } catch (err) {
          held.ops.length = 0
          const restore = held.confirmed
          if (restore != null) patchRig(dispatch, projectId, () => restore)
          dispatch(restApi.util.invalidateTags(['BuskRig']))
          toast.error(rigWriteFailureMessage(err))
        } finally {
          held.running = false
          endBuskRigWrite(landed)
        }
      })()
    },
    [dispatch, projectId, saveRig],
  )
}

/**
 * Write a page the server has just answered with into the list cache.
 *
 * For the one write that does not go through {@link useBuskLayoutCommit}: the first-open generator,
 * which creates a page and then fills it in two calls. `saveBuskLayout` deliberately neither
 * invalidates nor patches on its own — the queue owns that — so without this the generated layout
 * would sit unseen until an unrelated `busk.layoutChanged` frame happened to invalidate the page.
 */
export function useCacheBuskPage(projectId: number) {
  const dispatch = useDispatch<typeof store.dispatch>()
  return useCallback(
    (page: BuskPage) => {
      dispatch(
        buskApi.util.updateQueryData('buskPages', projectId, (draft) => upsertPage(draft, page)),
      )
    },
    [dispatch, projectId],
  )
}

/** Test seam: forget every queued gesture and echo window, the rig's included. */
export function resetBuskCommitState() {
  queues.clear()
  writesInFlight.clear()
  settledAt.clear()
  rigQueues.clear()
  rigWritesInFlight = 0
  rigSettledAt = null
}
