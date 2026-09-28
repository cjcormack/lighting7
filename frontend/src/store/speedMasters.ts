import { useMemo } from 'react'
import { toast } from 'sonner'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import { resolveSpeedMasterForCategory, type RoutableMaster } from '../lib/speedMasterModel'
import type { SpeedMasterBeat, SpeedMasterLiveState } from '../api/speedMastersWsApi'
import type {
  CreateSpeedMasterRequest,
  SpeedMaster,
  UpdateSpeedMasterRequest,
} from '../api/speedMastersApi'

// Master CRUD happens on other tabs and other surfaces too, so the WS notification is the
// invalidation signal rather than relying on this tab having made the change itself.
// (Live BPM deliberately does NOT go through invalidation — see speedMasterLive below.)
lightingApi.speedMasters.subscribeList(function () {
  store.dispatch(restApi.util.invalidateTags(['SpeedMaster', 'SpeedMasterList']))
})

/**
 * Shared sonner id for every refused tempo write, per master.
 *
 * A hardware TAP is a burst — an operator taps four beats on a linked master and the server
 * refuses four times. Keying the toast by master makes sonner replace rather than stack, the
 * same reasoning as `PROGRAMMER_ERROR_TOAST_ID`, while still letting two different masters each
 * say their piece.
 */
export const speedMasterErrorToastId = (masterUuid: string | null) =>
  `speed-master-error-${masterUuid ?? 'master-1'}`

/**
 * Bridge `speedMasters.error` into a toast.
 *
 * The UI removes the affordance — a follower's tile shows its ratio where TAP was — so this is
 * the backstop for the writers that have no affordance to remove: a MIDI surface bound to that
 * master, a script, another tab that hasn't seen the link yet. Without it those writes are
 * silently dropped and the operator watches a tempo not move.
 *
 * The frame is unicast to whoever sent the write, so a second tab never toasts for someone
 * else's mistake, and [message] is already the server's own prose — the single phrasing shared
 * with the MIDI log — so it needs no `formatError` and no code-to-copy mapping here. Nothing is
 * invalidated: a full `speedMasters.state` frame always follows and carries the truth.
 *
 * Module scope (form 1 of CLAUDE.md's three) like the list bridge above: this slice is not on
 * the earliest render path, so the deferred form buys nothing.
 */
lightingApi.speedMasters.subscribeError(function (error) {
  toast.error(error.message, { id: speedMasterErrorToastId(error.masterUuid) })
})

export const speedMastersApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    speedMasterList: build.query<SpeedMaster[], { projectId: number }>({
      query: ({ projectId }) => `projects/${projectId}/speed-masters`,
      providesTags: (_result, _error, { projectId }) => [
        { type: 'SpeedMasterList', id: projectId },
        'SpeedMasterList',
      ],
    }),

    createSpeedMaster: build.mutation<
      SpeedMaster,
      { projectId: number } & CreateSpeedMasterRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/speed-masters`,
        method: 'POST',
        body,
      }),
      // Guarded on the result: a duplicate name is a 409 and nothing moved.
      invalidatesTags: (result) => (result == null ? [] : ['SpeedMasterList']),
    }),

    saveSpeedMaster: build.mutation<
      SpeedMaster,
      { projectId: number; masterId: number } & UpdateSpeedMasterRequest
    >({
      query: ({ projectId, masterId, ...body }) => ({
        url: `projects/${projectId}/speed-masters/${masterId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: (result, _error, { masterId }) =>
        result == null ? [] : [{ type: 'SpeedMaster', id: masterId }, 'SpeedMasterList'],
    }),

    deleteSpeedMaster: build.mutation<
      void,
      { projectId: number; masterId: number; force?: boolean }
    >({
      query: ({ projectId, masterId, force }) => ({
        url: `projects/${projectId}/speed-masters/${masterId}${force ? '?force=true' : ''}`,
        method: 'DELETE',
      }),
      // The 409s (protected master 1, master in use) are ordinary parts of the flow —
      // nothing moved, so nothing refetches.
      invalidatesTags: (_result, error) => (error ? [] : ['SpeedMasterList']),
    }),

    /**
     * The live bank, patched from `speedMasters.state` / `speedMasters.changed` pushes.
     * This is what the masters strip renders — BPM at tap rate, never via invalidation.
     */
    speedMasterLive: build.query<SpeedMasterLiveState[], void>({
      queryFn: () => ({ data: lightingApi.speedMasters.getState() }),
      async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
        const subscription = lightingApi.speedMasters.subscribe((masters) => {
          updateCachedData((draft) => {
            // Field-wise writes rather than replacing the array: RTK Query's Immer draft
            // only notices structural changes, so a push that moved one master's bpm
            // re-renders without invalidating every tile's identity.
            draft.length = masters.length
            masters.forEach((master, i) => {
              const existing = draft[i]
              if (
                existing == null ||
                existing.uuid !== master.uuid ||
                existing.index !== master.index
              ) {
                draft[i] = master
                return
              }
              existing.name = master.name
              existing.bpm = master.bpm
              existing.isRunning = master.isRunning
              existing.source = master.source
              // Routing and follow move on CRUD, not at tap rate — but they still have to be
              // copied here. A field this merge forgets is written once, from the first frame,
              // and then never again: a usage retagged on another tab would leave this one
              // routing busked effects at the old master for the life of the page.
              //
              // Compared before writing, unlike the four above, because these are optional
              // on the wire and the server encodes no defaults — an unrouted, unlinked master,
              // which is the common case, arrives with the keys *absent*. Assigning `undefined`
              // over an absent key counts as a mutation to Immer, so an unconditional write would
              // hand every master a fresh identity on every tempo push: exactly the churn this
              // field-wise merge exists to avoid, and which `useSpeedMasterDisplay`'s per-master
              // subscription depends on not happening.
              if (existing.usage !== master.usage) existing.usage = master.usage
              if (existing.followNum !== master.followNum) existing.followNum = master.followNum
              if (existing.followDen !== master.followDen) existing.followDen = master.followDen
              if (existing.followTargetUuid !== master.followTargetUuid) {
                existing.followTargetUuid = master.followTargetUuid
              }
            })
          })
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

/**
 * Resolve an effect's speed-master reference for display, or null when nothing should
 * render: a null/unknown reference and an explicit master-1 pin all behave as master 1 at
 * apply time, and M1 is the silent default everywhere in the UI.
 *
 * This is THE shared home for that rule (the chip, the FX sheet, and anything else showing
 * a master label go through it), and it subscribes via `selectFromResult` so a consumer
 * re-renders only when *its own* master's entry changes — not on every tap of every master,
 * which matters when dozens of effect rows each mount one of these.
 */
export function useSpeedMasterDisplay(speedMasterUuid: string | null | undefined) {
  const { master } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => ({
      master: speedMasterUuid ? data?.find((m) => m.uuid === speedMasterUuid) : undefined,
    }),
  })
  return master && master.index !== 1 ? master : null
}

/**
 * The apply-time routing lookup: category in, speed-master uuid (or null for master 1) out.
 *
 * **Its caller is `TemplateEditor`** (fx-templates D8): an effect template's master is stamped at
 * the moment the effect is chosen, so a Colour effect template picks up the master whose usage is
 * `colour` without the operator being asked. It spent a while with no caller at all — the busk
 * view's ad-hoc effect pads were the original one, and they went when that view was brought back
 * onto its design — and was kept for exactly this. Every other half of the rule stands unchanged: a
 * master declares a `usage`, the detail sheet sets it, `BuskSpeedRail` badges it, and a null
 * `speedMasterUuid` still means master 1 everywhere.
 *
 * The cost of stamping rather than resolving is that retagging a master's usage later does not move
 * templates already stamped — accepted, and recorded as `FU-TMPL-USAGE-RETAG`.
 *
 * Reads the **live** bank rather than the project list because every caller is acting on the
 * current show and already has the socket open — no `projectId`, no second fetch, and the WS
 * layer re-requests a state frame on `speedMasters.listChanged`, so a usage retagged anywhere
 * lands here. (The manage page and the detail sheet use the list query instead: they are
 * project-scoped and may be looking at a project that isn't current.)
 *
 * Selects a **string** rather than the masters themselves. RTK Query shallow-compares what
 * `selectFromResult` returns, and the live array is rebuilt on every tempo push — so returning
 * an array or a map here would hand back a new identity per tap of any master and re-render
 * every pad that holds this hook. The key only changes when the routing actually does.
 */
export function useSpeedMasterForCategory(): (category: string | null | undefined) => string | null {
  const { routingKey } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => ({
      routingKey: (data ?? [])
        .filter((m) => m.usage != null && m.uuid != null)
        .map((m) => `${m.usage}\u0000${m.uuid}`)
        .join('\u001f'),
    }),
  })

  return useMemo(() => {
    const routed: RoutableMaster[] =
      routingKey === ''
        ? []
        : routingKey.split('\u001f').map((pair) => {
            const [usage, uuid] = pair.split('\u0000')
            return { usage, uuid }
          })
    return (category) => resolveSpeedMasterForCategory(routed, category)
  }, [routingKey])
}

/**
 * Master 1's uuid, or null before the first live frame.
 *
 * Needed because the read streams and the write messages address master 1 differently: a null
 * uuid *writes* to master 1, but `speedMasters.beat` tags master 1's frames with its real uuid
 * once the bank has loaded (`SpeedMasterBank.emitBeat`), and null only for the synthetic
 * pre-load master. So anything *subscribing* on master 1's behalf has to resolve the real one
 * — subscribing with null would silently never match a frame. Null while unresolved is exactly
 * right: that is the pre-load master 1, which really does emit null.
 *
 * `selectFromResult` like [useSpeedMasterDisplay], so a consumer re-renders only when master
 * 1's identity moves rather than on every tap of every master.
 */
export function useMaster1Uuid(): string | null {
  const { uuid } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => ({
      uuid: data?.find((m) => m.index === 1)?.uuid ?? null,
    }),
  })
  return uuid
}

/**
 * One master's live BPM, or null while it is unresolved (no uuid yet, or a uuid naming no
 * master in the live bank).
 *
 * For anything that has to *react* to a retune rather than merely display it — `BeatIndicator`
 * re-seeds its free-running timer off this, because server beat frames are throttled and a
 * tempo move makes the local timer wrong about the rate, not just drifted.
 *
 * `selectFromResult` like [useMaster1Uuid], so a dot re-renders only when its own master's
 * tempo moves rather than on every tap of every master.
 */
export function useSpeedMasterBpm(masterUuid: string | null): number | null {
  const { bpm } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => ({
      bpm: masterUuid == null ? null : (data?.find((m) => m.uuid === masterUuid)?.bpm ?? null),
    }),
  })
  return bpm
}

/** Set one master's live BPM over WS (null uuid → master 1). */
export function setSpeedMasterBpm(masterUuid: string | null, bpm: number) {
  lightingApi.speedMasters.setBpm(masterUuid, bpm)
}

/** Tap one master's tempo over WS (null uuid → master 1). */
export function tapSpeedMaster(masterUuid: string | null) {
  lightingApi.speedMasters.tap(masterUuid)
}

/**
 * Subscribe to one master's beat boundaries (null uuid → master 1). Frames are throttled
 * server-side, so callers interpolate locally between them — see `BeatIndicator`.
 */
export function subscribeToSpeedMasterBeat(
  masterUuid: string | null,
  fn: (beat: SpeedMasterBeat) => void,
) {
  return lightingApi.speedMasters.subscribeBeat(masterUuid, fn)
}

/**
 * Ask for one immediate beat frame for a master already subscribed to. For recovering from a
 * stale local timer without re-subscribing — see `BeatIndicator`'s visibility handler.
 */
export function requestSpeedMasterBeat(masterUuid: string | null) {
  lightingApi.speedMasters.requestBeat(masterUuid)
}

export const {
  useSpeedMasterListQuery,
  useCreateSpeedMasterMutation,
  useSaveSpeedMasterMutation,
  useDeleteSpeedMasterMutation,
  useSpeedMasterLiveQuery,
} = speedMastersApi
