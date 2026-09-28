import { useMemo, useRef } from 'react'
import { toast } from 'sonner'
import { store } from './index'
import { restApi } from './restApi'
import { patchesApi, suspendPatchInvalidation } from './patches'
import { riggingsApi } from './riggings'
import { stageRegionsApi } from './stageRegions'
import { formatError } from '../lib/formatError'

// Optimistic cache writes and rollback for stage placement edits.
//
// Drags write to the RTK Query cache on every frame so the object follows the
// cursor, then PUT once on settle. Two consequences fall out of that, and both
// are handled here rather than at the call site:
//
//  1. The DTO a settle callback receives is the *dragged* value, not the
//     pre-drag one — the frames already overwrote it. Comparing against it to
//     skip no-op writes therefore compares the final value against itself.
//  2. `updateQueryData(...).undo()` reverts to the value immediately before that
//     one patch, which mid-drag is the last dragged frame — not where the object
//     started. So it cannot serve as a rollback for a drag.
//
// Both are fixed by snapshotting the object once at the start of the gesture:
// the snapshot is what the no-op guard compares against and what a rejected
// write restores.

export interface PatchPlacementValues {
  riggingUuid?: string | null
  stageX?: number | null
  stageY?: number | null
  stageZ?: number | null
  baseYawDeg?: number | null
  basePitchDeg?: number | null
}

export interface RegionPlacementValues {
  centerX?: number | null
  centerY?: number | null
  centerZ?: number | null
  yawDeg?: number | null
  widthM?: number | null
  depthM?: number | null
  heightM?: number | null
}

export interface RiggingPlacementValues {
  positionX?: number | null
  positionY?: number | null
  positionZ?: number | null
  yawDeg?: number | null
  pitchDeg?: number | null
  rollDeg?: number | null
  lengthM?: number | null
}

/**
 * Remembers each object's state at the start of a drag gesture.
 *
 * `remember` is idempotent within a gesture — the first frame wins, so callers
 * can call it unconditionally on every frame without needing to detect the
 * start of the drag.
 */
export function useDragOrigin<T>() {
  const map = useRef<Map<number, T>>(new Map())
  return useMemo(
    () => ({
      remember(id: number, value: T) {
        if (!map.current.has(id)) map.current.set(id, value)
      },
      /** Read and clear. Call once, at settle. */
      take(id: number): T | undefined {
        const v = map.current.get(id)
        map.current.delete(id)
        return v
      },
      clear() {
        map.current.clear()
      },
    }),
    [],
  )
}

// — single-entity cache writes ————————————————————————————————————————

export function writePatchPlacement(
  projectId: number,
  patchId: number,
  values: PatchPlacementValues,
) {
  store.dispatch(
    patchesApi.util.updateQueryData('patchList', projectId, (draft) => {
      const p = draft.find((x) => x.id === patchId)
      if (p) Object.assign(p, values)
    }),
  )
}

export function writeRegionPlacement(
  projectId: number,
  regionId: number,
  values: RegionPlacementValues,
) {
  store.dispatch(
    stageRegionsApi.util.updateQueryData('stageRegionList', projectId, (draft) => {
      const r = draft.find((x) => x.id === regionId)
      if (r) Object.assign(r, values)
    }),
  )
}

export function writeRiggingPlacement(
  projectId: number,
  riggingId: number,
  values: RiggingPlacementValues,
) {
  store.dispatch(
    riggingsApi.util.updateQueryData('riggingList', projectId, (draft) => {
      const r = draft.find((x) => x.id === riggingId)
      if (r) Object.assign(r, values)
    }),
  )
}

/** True when every key present in `next` already equals the same key in `prev`. */
export function placementUnchanged<T extends object>(next: T, prev: T): boolean {
  return (Object.keys(next) as Array<keyof T>).every((k) => {
    if (next[k] === undefined) return true
    return next[k] === prev[k]
  })
}

// — bulk ————————————————————————————————————————————————————————————

export interface PlacementChange extends PatchPlacementValues {
  patchId: number
}

export interface CommitPlacementsResult {
  ok: number
  failed: Array<{ patchId: number; error: unknown }>
}

/**
 * Applies many placement changes as one user-visible operation.
 *
 * Backed by `PUT /projects/{id}/patches/placements`: **one request, one
 * transaction, one broadcast**, so an align across forty fixtures costs the same
 * round trip as moving one. Around that:
 *
 *  - a single optimistic cache write for the whole batch, so the SVG tree
 *    re-renders once rather than N times;
 *  - WebSocket `patchListChanged` invalidation suspended for the duration, and
 *    `bulkPlacements` carries no `invalidatesTags` of its own, so this function
 *    owns exactly one invalidation at the end;
 *  - the batch is atomic server-side, so a rejected entry means nothing was
 *    written and the optimistic write is rolled back wholesale.
 *
 * The server also returns `warnings` for things only it can know at write time —
 * chiefly a fixture landing past the end of its truss, since the truss length
 * isn't part of the request.
 */
export async function commitPlacements({
  projectId,
  changes,
  label,
}: {
  projectId: number
  changes: PlacementChange[]
  label: string
}): Promise<CommitPlacementsResult> {
  if (changes.length === 0) return { ok: 0, failed: [] }

  // Snapshot only the fields each change actually touches, so rollback restores
  // exactly what we overwrote and nothing else.
  const before = new Map<number, PatchPlacementValues>()
  const current = patchesApi.endpoints.patchList.select(projectId)(store.getState()).data ?? []
  for (const change of changes) {
    const patch = current.find((p) => p.id === change.patchId)
    if (!patch) continue
    const snapshot: PatchPlacementValues = {}
    for (const key of Object.keys(change) as Array<keyof PlacementChange>) {
      if (key === 'patchId') continue
      ;(snapshot as Record<string, unknown>)[key] = patch[key]
    }
    before.set(change.patchId, snapshot)
  }

  // One draft for the whole batch.
  store.dispatch(
    patchesApi.util.updateQueryData('patchList', projectId, (draft) => {
      for (const { patchId, ...values } of changes) {
        const p = draft.find((x) => x.id === patchId)
        if (p) Object.assign(p, values)
      }
    }),
  )

  const releaseInvalidation = suspendPatchInvalidation()
  const failed: CommitPlacementsResult['failed'] = []

  try {
    const response = await store
      .dispatch(
        patchesApi.endpoints.bulkPlacements.initiate({
          projectId,
          updates: changes,
        }),
      )
      .unwrap()
    for (const failure of response.failed) {
      failed.push({ patchId: failure.patchId, error: failure.error })
    }
    // Surfaced separately from failures: the write succeeded, but the server spotted
    // something the client couldn't — e.g. a fixture past the end of its truss.
    for (const warning of response.warnings) toast.warning(warning)
  } catch (error) {
    // Atomic: a rejected batch wrote nothing, so every change failed.
    for (const change of changes) failed.push({ patchId: change.patchId, error })
  } finally {
    releaseInvalidation()
  }

  if (failed.length > 0) {
    // Roll the whole batch back — a partially-applied placement operation is
    // more confusing than none of it.
    store.dispatch(
      patchesApi.util.updateQueryData('patchList', projectId, (draft) => {
        for (const [patchId, values] of before) {
          const p = draft.find((x) => x.id === patchId)
          if (p) Object.assign(p, values)
        }
      }),
    )
    toast.error(
      `${label}: ${failed.length} of ${changes.length} failed — reloading (${formatError(failed[0].error)})`,
    )
  }

  // Exactly one refetch for the batch, whatever the outcome: on success it
  // confirms the optimistic write, on failure it reconciles whatever did land
  // server-side. `suspendPatchInvalidation`'s release deliberately doesn't
  // dispatch, so this is the only invalidation — otherwise the two of them
  // produced two full list refetches per operation.
  //
  // 'Patch' only, not 'Fixture': every body here is inside the backend's
  // placement-only key set, which by definition skips the fixture-registry
  // rebuild, so the fixture list cannot have changed.
  store.dispatch(restApi.util.invalidateTags(['Patch']))

  return { ok: changes.length - failed.length, failed }
}
