import { restApi } from "./restApi"
import { useWithoutInfrastructure } from "@/lib/infrastructure"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type {
  FixturePatch,
  UniverseConfig,
  CreatePatchRequest,
  UpdatePatchRequest,
  PatchGroup,
  PatchGroupDetail,
  UpdatePatchGroupRequest,
} from "../api/patchApi"

/**
 * One entry in a bulk placement request.
 *
 * `patchId` identifies the row; every other key follows the backend's tri-state
 * convention — absent means unchanged, an explicit `null` clears the field.
 */
export interface BulkPlacementEntry {
  patchId: number
  riggingUuid?: string | null
  stageX?: number | null
  stageY?: number | null
  stageZ?: number | null
  baseYawDeg?: number | null
  basePitchDeg?: number | null
}

export interface BulkPlacementResponse {
  updated: FixturePatch[]
  failed: Array<{ patchId: number; error: string }>
  /** Non-fatal notices, e.g. a fixture placed past the end of its truss. Only
   *  the server knows the bar's length at write time, so only it can spot this. */
  warnings: string[]
}

// A bulk placement operation fires one PUT per patch, and the backend broadcasts
// `patchListChanged` after each one — so a 20-fixture align would trigger 20 full
// list refetches while the batch is still running. `commitPlacements` holds this
// suspension for the duration and takes a single refetch at the end.
//
// Counted rather than boolean so nested/overlapping batches can't have the inner
// one release the outer one's suspension.
let invalidationSuspended = 0
let invalidationPending = false

function invalidatePatchTags() {
  store.dispatch(restApi.util.invalidateTags(['Patch', 'UniverseConfig']))
}

/**
 * Suspends WebSocket-driven patch invalidation until the returned release is
 * called.
 *
 * The release **never dispatches** — it reports whether a broadcast arrived while
 * suspended and leaves the invalidation to the caller. That's deliberate: the
 * caller is going to invalidate anyway once its batch finishes, and having both
 * fire produced two full list refetches per bulk operation instead of one.
 * Ownership sits with whoever knows the batch is done.
 */
export function suspendPatchInvalidation(): () => boolean {
  invalidationSuspended++
  let released = false
  return () => {
    if (released) return false
    released = true
    invalidationSuspended--
    if (invalidationSuspended > 0) return false
    const wasPending = invalidationPending
    invalidationPending = false
    return wasPending
  }
}

// Subscribe to WebSocket patch changes
lightingApi.patches.subscribe(() => {
  if (invalidationSuspended > 0) {
    invalidationPending = true
    return
  }
  invalidatePatchTags()
})

export const patchesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    // List patches for a project
    patchList: build.query<FixturePatch[], number>({
      query: (projectId) => `projects/${projectId}/patches`,
      providesTags: ['Patch'],
    }),

    // Create a single patch
    createPatch: build.mutation<FixturePatch, { projectId: number } & CreatePatchRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/patches`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Patch', 'UniverseConfig', 'Fixture'],
    }),

    // Update a patch
    updatePatch: build.mutation<FixturePatch, { projectId: number; patchId: number } & UpdatePatchRequest>({
      query: ({ projectId, patchId, ...body }) => ({
        url: `projects/${projectId}/patches/${patchId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['Patch', 'Fixture'],
    }),

    // Bulk placement — one request, one transaction, one broadcast.
    //
    // Deliberately declares NO `invalidatesTags`: `commitPlacements` owns the
    // single invalidation that follows the batch. Only ever call it through
    // `commitPlacements` — calling it directly leaves the cache un-reconciled.
    //
    // Every key in every entry must be inside the backend's placement-only set
    // (METADATA_ONLY_PUT_KEYS in projectPatches.kt); the route rejects anything
    // else with a 400, which is what structurally guarantees it can skip the
    // fixture-registry rebuild.
    bulkPlacements: build.mutation<
      BulkPlacementResponse,
      { projectId: number; updates: BulkPlacementEntry[]; atomic?: boolean }
    >({
      query: ({ projectId, updates, atomic = true }) => ({
        url: `projects/${projectId}/patches/placements`,
        method: 'PUT',
        body: { updates, atomic },
      }),
    }),

    // Renumber heads — the bulk route again, but **not** a placement: `headNumber` is the one key
    // on it with a uniqueness rule, which the desk judges against the batch's final state
    // (`headNumberClashes` in projectPatches.kt). That is what makes numbering a selection one
    // atomic request, a swap included, where a PUT per head would refuse the second of two heads
    // trading numbers. Atomic: a clash anywhere writes nothing and answers 400 naming it.
    //
    // Unlike `bulkPlacements` it invalidates like `updatePatch` does, since there is no batch
    // helper to own a single refetch — one gesture, one request.
    setHeadNumbers: build.mutation<
      BulkPlacementResponse,
      { projectId: number; numbers: ReadonlyArray<{ patchId: number; headNumber: number | null }> }
    >({
      query: ({ projectId, numbers }) => ({
        url: `projects/${projectId}/patches/placements`,
        method: 'PUT',
        body: { updates: numbers, atomic: true },
      }),
      invalidatesTags: ['Patch'],
    }),

    // Delete a patch
    deletePatch: build.mutation<void, { projectId: number; patchId: number }>({
      query: ({ projectId, patchId }) => ({
        url: `projects/${projectId}/patches/${patchId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Patch', 'Fixture'],
    }),

    // List universe configs for a project
    universeConfigList: build.query<UniverseConfig[], number>({
      query: (projectId) => `projects/${projectId}/universe-configs`,
      providesTags: ['UniverseConfig'],
    }),

    // Update a universe config (address, controller type, Art-Net transmit interval)
    updateUniverseConfig: build.mutation<UniverseConfig, { projectId: number; configId: number; address?: string; controllerType?: string; refreshIntervalMs?: number; resetRefreshInterval?: boolean }>({
      query: ({ projectId, configId, ...body }) => ({
        url: `projects/${projectId}/universe-configs/${configId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['UniverseConfig', 'Fixture'],
    }),

    // List patch groups for a project
    patchGroupList: build.query<PatchGroup[], number>({
      query: (projectId) => `projects/${projectId}/patch-groups`,
      providesTags: ['Patch'],
    }),

    // Get patch group detail (with ordered members)
    patchGroupDetail: build.query<PatchGroupDetail, { projectId: number; groupId: number }>({
      query: ({ projectId, groupId }) => `projects/${projectId}/patch-groups/${groupId}`,
      providesTags: ['Patch'],
    }),

    // Update patch group (rename, reorder)
    updatePatchGroup: build.mutation<PatchGroupDetail, { projectId: number; groupId: number } & UpdatePatchGroupRequest>({
      query: ({ projectId, groupId, ...body }) => ({
        url: `projects/${projectId}/patch-groups/${groupId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['Patch', 'Fixture'],
    }),

    // Delete patch group
    deletePatchGroup: build.mutation<void, { projectId: number; groupId: number }>({
      query: ({ projectId, groupId }) => ({
        url: `projects/${projectId}/patch-groups/${groupId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Patch', 'Fixture'],
    }),
  }),
  overrideExisting: false,
})

export const {
  usePatchListQuery,
  useCreatePatchMutation,
  useUpdatePatchMutation,
  useSetHeadNumbersMutation,
  useDeletePatchMutation,
  useUniverseConfigListQuery,
  useUpdateUniverseConfigMutation,
  usePatchGroupListQuery,
  usePatchGroupDetailQuery,
  useUpdatePatchGroupMutation,
  useDeletePatchGroupMutation,
} = patchesApi

/**
 * `usePatchListQuery` with infrastructure patches left out (`FixturePatch.infrastructure`) — what
 * every view but Patches reads when it lists or offers patched fixtures. The Patches view reads the
 * raw list: it is where the flag is set, so it must show what it hides.
 */
export function useVisiblePatchListQuery(projectId: number, options?: Parameters<typeof usePatchListQuery>[1]) {
  const result = usePatchListQuery(projectId, options)
  return useWithoutInfrastructure<typeof result, FixturePatch>(result, result.data)
}
