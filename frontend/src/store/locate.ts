import { restApi } from "./restApi"
import { lightingApi } from '../api/lightingApi'
import type { LocateTarget } from '../api/locateWsApi'
import type { UnitLocate } from '@/api/patchApi'

// === Types ===

export type { LocateTarget } from '../api/locateWsApi'
export type LocateTargetType = LocateTarget['type']

export interface LocateState {
  targets: LocateTarget[]
}

export interface ToggleLocateResponse {
  active: boolean
  writeCount: number
  /**
   * True when the toggle came back inactive *because* park masks every property locate would
   * have written — otherwise indistinguishable from "this target has no DMX-backed
   * properties".
   */
  parkMasked?: boolean
}

/** One patch's stored locate after a store or clear (`POST …/locate/store` / `…/clear`). */
export interface LocatePatchResult {
  patchId: number
  key: string
  locate: UnitLocate[]
}

/** A target or unit a store or clear took nothing from, with why (`does not pan and tilt`). */
export interface LocateSkip {
  target: LocateTarget
  reason: string
}

export interface StoredLocateResponse {
  stored?: LocatePatchResult[]
  skipped?: LocateSkip[]
}

export interface StoredLocateRequest {
  projectId: number
  targets: LocateTarget[]
}

// === API ===

export const locateApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    /**
     * All currently-located fixtures and groups — a stream, form 3 (CLAUDE.md §"Where a WS bridge
     * subscribes"): `locate.state` feeds it, seeded from the WS layer's last frame, because a Locate also
     * ends when the located fixture is edited, which no toggle here sent, and a REST read racing the
     * stream could land after a newer frame. The toggle patches it from its own answer only while no
     * frame has overtaken that answer.
     */
    locateState: build.query<LocateState, void>({
      queryFn: () => ({ data: { targets: lightingApi.locate.get() } }),
      providesTags: ['Locate'],
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        try {
          await cacheDataLoaded
        } catch {
          return
        }
        const subscription = lightingApi.locate.subscribe((targets) => {
          updateCachedData(() => ({ targets }))
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /**
     * Toggle locate for a fixture or group.
     *
     * Locate writes programmer entries (owner `locate`) rather than destroying effects:
     * covering effects are *suppressed* while the locate holds and resume on release. The
     * effects tags are deliberately not invalidated here — nothing is removed to refetch,
     * and a bare tag would refetch every per-fixture effects query on each toggle.
     */
    toggleLocate: build.mutation<ToggleLocateResponse, LocateTarget>({
      query: (body) => ({
        url: 'locate/toggle',
        method: 'POST',
        body,
      }),
      async onQueryStarted(target, { dispatch, queryFulfilled }) {
        const framesBefore = lightingApi.locate.frames()
        try {
          const { data } = await queryFulfilled
          // A frame since the press is newer than this answer, and already in the cache.
          if (lightingApi.locate.frames() !== framesBefore) return
          dispatch(
            locateApi.util.updateQueryData('locateState', undefined, (draft) => {
              draft.targets = draft.targets.filter((t) => t.type !== target.type || t.key !== target.key)
              if (data.active) draft.targets.push({ type: target.type, key: target.key })
            }),
          )
        } catch {
          // A refused toggle changed nothing.
        }
      },
    }),

    /**
     * *Set locate from current* (venue-visit plan D6): each unit the targets reach that pans and
     * tilts takes the pan and tilt the desk is sending it **now** as its stored locate. The desk
     * reads its own output and writes the patch — or, for a venue's patch in a show, the show's Focus
     * override — so nothing here works out a byte. Current project only. The patch list's tag is
     * invalidated for this tab; other tabs follow `patchListChanged`.
     */
    storeLocate: build.mutation<StoredLocateResponse, StoredLocateRequest>({
      query: ({ projectId, targets }) => ({
        url: `projects/${projectId}/locate/store`,
        method: 'POST',
        body: { targets },
      }),
      invalidatesTags: ['Patch'],
    }),

    /** *Clear locate*: those units locate to their centre again. In a show on a venue it clears the
     *  show's own locate and never the venue's. */
    clearLocate: build.mutation<StoredLocateResponse, StoredLocateRequest>({
      query: ({ projectId, targets }) => ({
        url: `projects/${projectId}/locate/clear`,
        method: 'POST',
        body: { targets },
      }),
      invalidatesTags: ['Patch'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useLocateStateQuery,
  useToggleLocateMutation,
  useStoreLocateMutation,
  useClearLocateMutation,
} = locateApi
