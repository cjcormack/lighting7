import { useCallback } from 'react'
import { toast } from 'sonner'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import type { HeldRecord } from '../api/handApi'
import type { BuskPadKind } from '../api/buskApi'

/**
 * The desk's **hand** — see `api/handApi.ts` for the wire and its rules.
 *
 * **Where this bridge subscribes** (CLAUDE.md §"Where a WS bridge subscribes"): **form 3**, per
 * cache entry, and nothing at module scope. The plan's session-3 bullet says form 1; it is wrong,
 * and `store/windows.ts` is the precedent that settles it in two steps.
 *
 * The first step rules form 1 out. `HandChip` is mounted in `Layout.tsx`, so this module sits on
 * the **earliest render path** — the case §"Where a WS bridge subscribes" names as form 2's, where
 * a bare `lightingApi.hand.subscribe(…)` in the module body can run while `api/lightingApi` is
 * still mid-initialisation and throw a TDZ `ReferenceError` that takes every export with it,
 * invisibly to `tsc`, `vite build` and the tests.
 *
 * The second step rules form 2 out in favour of form 3. `hand.state` is a **stream**: the value
 * itself arrives over WS, carries the whole held record including its summary DTOs, and there is
 * nothing to refetch — so there is no invalidation for a bridge to dispatch. A `queryFn` that
 * *closes over* `lightingApi` touches it only when the first reader mounts, long after every module
 * has evaluated, which is why this needs no `startHandBridge()` while `store/looks.ts` and its
 * three siblings do. Same shape as `deskWindows` and `deskSelection`, for the same two reasons.
 *
 * Not project-keyed, and that is **not** because the hand is machine-scoped — it is not; the desk
 * clears it on a project switch. It takes no project argument because it never needs one: the desk
 * is the only thing that can put a record in it, and a switch empties it before this client could
 * read a stale id.
 */

const handApiSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    deskHand: build.query<HeldRecord | null, void>({
      queryFn: () => ({ data: lightingApi.hand.getState() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.hand.subscribe((held) => {
          updateCachedData(() => held)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useDeskHandQuery } = handApiSlice

/** What the desk is holding, or null. Null until the first frame, which is also an empty hand. */
export function useHand(): HeldRecord | null {
  const { data } = useDeskHandQuery()
  return data ?? null
}

/**
 * Take a record into the hand. A second pick-up replaces; there is no "put it back".
 *
 * Returns whether the frame reached the rig, which only the edge drag reads — see
 * {@link import('@/api/handApi').HandWsApi.pickUp}.
 */
export function handPickUp(kind: BuskPadKind, id: number): boolean {
  return lightingApi.hand.pickUp(kind, id)
}

/**
 * Is the desk holding this exact record **right now**?
 *
 * The synchronous half of {@link whenHandHolds}, and the edge drag's receiving window needs both:
 * one to wait for the hand to arrive, and this one to re-ask in the instant before it acts. The
 * hand is shared, so anything can move it in between — another window, a control surface, the
 * operator's own second pick-up.
 */
export function handHolds(record: { kind: BuskPadKind; id: number }): boolean {
  const held = lightingApi.hand.getState()
  return held != null && held.kind === record.kind && held.id === record.id
}

/**
 * Resolve once the desk is holding this exact record, or with null when the wait runs out.
 *
 * The edge drag's receiving window needs this because its two inputs race: the release arrives over
 * a **local** `BroadcastChannel` while the hand arrives over the **WebSocket**, so a release can
 * beat the `hand.state` frame for its own pick-up. Acting immediately would either find no place
 * band rendered yet, or — if something else was already in the hand — find that record's band and
 * place the wrong record.
 *
 * Matching is on `kind` + `id`, which is what `hand.pickUp` itself takes and what identifies a
 * record within the current project; the uuid is not available to the window that picked up until
 * the frame it is waiting for arrives.
 *
 * It resolves **immediately** when the hand already matches, so the ordinary case pays nothing. The
 * subscription is torn down on every exit, including the timeout.
 */
export function whenHandHolds(
  record: { kind: BuskPadKind; id: number },
  timeoutMs: number,
): Promise<HeldRecord | null> {
  const matches = (held: HeldRecord | null) =>
    held != null && held.kind === record.kind && held.id === record.id
  const now = lightingApi.hand.getState()
  if (matches(now)) return Promise.resolve(now)
  return new Promise((resolve) => {
    let done = false
    const finish = (held: HeldRecord | null) => {
      if (done) return
      done = true
      clearTimeout(timer)
      subscription.unsubscribe()
      resolve(held)
    }
    const timer = setTimeout(() => finish(null), timeoutMs)
    const subscription = lightingApi.hand.subscribe((held) => {
      if (matches(held)) finish(held)
    })
  })
}

/**
 * Let go of whatever is held — the chip's ×, Escape, and a client that changed its mind.
 *
 * Deliberately **unguarded**: "whatever is there" is exactly what those two gestures mean. A drop
 * *after a place* is a different call and must name its record — see {@link useHandPlace}.
 */
export function handDrop(): void {
  lightingApi.hand.drop()
}

/**
 * One place, as the placing window performs it.
 *
 * [run] is the window's **own existing mutation** and [undo] its inverse; there is no `hand.place`
 * frame and adding one would reimplement four validated mutations behind a single name (D12).
 */
export interface HandPlacement<R> {
  /**
   * The mutation. Resolves with whatever [undo] needs; **null or a rejection means nothing landed**,
   * and then nothing is dropped and no toast is raised — the failure has already been reported by
   * `errorToastMiddleware`, which toasts every rejected mutation.
   */
  run: () => Promise<R | null>
  /** Where it landed, for the toast: `Colours on Page 1`, `Slot 3`, `the programmer`. */
  where: string
  /**
   * The inverse mutation. Omitted where this window cannot address what it just made.
   *
   * Its return is `unknown` rather than `void | Promise<void>` so an RTK Query trigger can be
   * handed straight in: the mutation's own thenable is not a `Promise<void>`, and the alternative
   * is a wrapping arrow at every call site whose only job is to discard a value nothing reads.
   */
  undo?: (result: R) => unknown
}

/** How long Undo stays offered (plan D12). */
export const HAND_UNDO_MS = 10_000

/**
 * Run a place: the window's mutation, then the **guarded** drop, then Undo for ten seconds.
 *
 * **The drop names the record** (`hand.drop {uuid}`). A place is two independent round-trips, and
 * another window may have picked something up in the gap — a bare drop would then clear an item
 * this window never touched, on exactly the two-screen case the hand exists for. Getting this
 * backwards is invisible in one window, which is why `hand.test.ts` asserts the uuid rather than
 * the call.
 *
 * **Undo does not put the record back in the hand.** The desk let go when the place succeeded, and
 * a re-pick-up would be this window claiming the hand back from whoever has since used it. Undo
 * is the inverse of the *place*, nothing more; picking the record up again is a gesture the
 * operator still has.
 */
export function useHandPlace(): <R>(held: HeldRecord, placement: HandPlacement<R>) => Promise<void> {
  return useCallback(async function place<R>(held: HeldRecord, placement: HandPlacement<R>) {
    let result: R | null
    try {
      result = await placement.run()
    } catch {
      // Reported by errorToastMiddleware; caught here only to keep the hand held, which is the
      // right state after a failed place — the operator still has the item and can try elsewhere.
      return
    }
    if (result == null) return

    lightingApi.hand.drop(held.uuid)

    const name = heldName(held)
    const landed = result
    toast.success(`“${name}” placed in ${placement.where}`, {
      duration: HAND_UNDO_MS,
      // Plain field, not a conditional spread: sonner renders `action` only when it is set, so an
      // explicit `undefined` and an absent key are the same thing to it.
      action:
        placement.undo == null
          ? undefined
          : {
              label: 'Undo',
              onClick: () => {
                void Promise.resolve(placement.undo?.(landed)).catch(() => {
                  // Same reasoning as above: the inverse is an ordinary mutation and its failure is
                  // already on screen. Swallowed only to stop the unhandled rejection.
                })
              },
            },
    })
  }, [])
}

/**
 * The held record's name, from the summary the frame carries.
 *
 * Not `padFaceOf`: that builds a whole face (swatch, detail line, effect glyph) for one string, and
 * this is called from a store module that has no business importing the busk view's pad vocabulary.
 * The chip, which wants the whole face, does use `padFaceOf`.
 */
export function heldName(held: HeldRecord): string {
  if (held.kind === 'TEMPLATE') return held.template?.name ?? 'this template'
  if (held.kind === 'LOOK') return held.look?.name ?? 'this Look'
  return held.cue?.name ?? 'this cue'
}
