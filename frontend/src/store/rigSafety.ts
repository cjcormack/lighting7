import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import type { CueTarget } from '../api/cuesApi'
import { NOTHING_SAFE, type CommandRole, type CommandRun, type RigSafetyState } from '../api/rigSafetyApi'

/**
 * The end of the night (venue-visit plan S6) — see `api/rigSafetyApi.ts`.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"): `rigSafety.state` is a stream with nothing
 * to refetch. The banner under the app header reads it on every route, so this slice is on the
 * earliest render path, which form 3 is safe on.
 *
 * Every operation is REST and answers with the run it started; how the run goes arrives on the
 * frame. Refusals (`COMMAND_RUN_BUSY`, `COMMAND_NO_UNITS`, `COMMAND_BLIND`, `VENUE_HOLD`,
 * `RIG_NOT_SAFE`, `QUIT_LOCAL_ONLY`, and on the public listener `REMOTE_COMMANDS_DISABLED`) reach
 * the screen through `errorToastMiddleware` — except *Quit desk*'s `RIG_NOT_SAFE`, which its dialog
 * asks about instead.
 */
export interface RigSafetyResponse {
  run?: CommandRun | null
}

/** Absent targets are the whole rig. */
interface TargetsArg {
  projectId: number
  targets?: CueTarget[] | null
}

const rigSafetySlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    rigSafety: build.query<RigSafetyState, void>({
      queryFn: () => ({ data: lightingApi.rigSafety.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.rigSafety.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
    runBulkCommand: build.mutation<RigSafetyResponse, TargetsArg & { role: CommandRole }>({
      query: ({ projectId, role, targets }) => ({
        url: `projects/${projectId}/commands`,
        method: 'POST',
        body: targets ? { role, targets } : { role },
      }),
    }),
    cancelBulkCommand: build.mutation<void, { projectId: number }>({
      query: ({ projectId }) => ({ url: `projects/${projectId}/commands/cancel`, method: 'POST', body: {} }),
    }),
    makeRigSafe: build.mutation<RigSafetyResponse, TargetsArg>({
      query: ({ projectId, targets }) => ({
        url: `projects/${projectId}/rig-safe`,
        method: 'POST',
        body: targets ? { targets } : {},
      }),
    }),
    undoRigSafe: build.mutation<RigSafetyResponse, { projectId: number }>({
      query: ({ projectId }) => ({ url: `projects/${projectId}/rig-safe/undo`, method: 'POST', body: {} }),
    }),
    releaseRigSafe: build.mutation<RigSafetyResponse, { projectId: number }>({
      query: ({ projectId }) => ({ url: `projects/${projectId}/rig-safe/release`, method: 'POST', body: {} }),
    }),
    quitDesk: build.mutation<{ rigWasSafe: boolean }, { force: boolean }>({
      query: ({ force }) => ({ url: 'install/quit', method: 'POST', body: { force } }),
    }),
  }),
  overrideExisting: false,
})

export const {
  useRigSafetyQuery,
  useRunBulkCommandMutation,
  useCancelBulkCommandMutation,
  useMakeRigSafeMutation,
  useUndoRigSafeMutation,
  useReleaseRigSafeMutation,
  useQuitDeskMutation,
} = rigSafetySlice

/** The live project's end-of-night state; nothing safe before the first frame. */
export function useRigSafety(): RigSafetyState {
  const { data } = useRigSafetyQuery()
  return data ?? NOTHING_SAFE
}
