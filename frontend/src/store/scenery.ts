import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { NO_SCENERY, type LiveScenery, type SceneryChange, type SceneryWriteItem } from '../api/sceneryApi'

/**
 * Scenery on cues, stacks and Looks (stage-view plan session 8) — see `api/sceneryApi.ts`.
 *
 * **Three whole-list writes**, one per owner: each answers the list as stored and refuses the whole
 * write, every problem at once, when any change does not fit its element's kind. A change to one
 * cue's scenery moves what every later cue in its stack *tracks*, so a cue write invalidates every
 * cue entry, not just its own; a stack's set is tracked by its cues too.
 *
 * **The live scenery is form 3** (CLAUDE.md §"Where a WS bridge subscribes"): `scenery.state` is a
 * stream with nothing to refetch, so it lives in one cache entry fed by the WS layer, seeded from its
 * last frame. Not project-keyed: the desk resolves the current project's, and says which.
 */
const sceneryApiSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    setCueScenery: build.mutation<SceneryChange[], { projectId: number; cueId: number; scenery: SceneryWriteItem[] }>({
      query: ({ projectId, cueId, scenery }) => ({
        url: `projects/${projectId}/cues/${cueId}/scenery`,
        method: 'PUT',
        body: { scenery },
      }),
      // The stack list too: it carries every cue's own changes (`CueStackCueEntry.scenery`), which
      // the cue table's Scenery column and the Prompt Book read (scenery-programmer plan D13, D14).
      invalidatesTags: (_result, error, { projectId }) =>
        error ? [] : ['Cue', { type: 'CueList', id: projectId }, { type: 'CueStackList', id: projectId }],
    }),
    setStackScenery: build.mutation<SceneryChange[], { projectId: number; stackId: number; scenery: SceneryWriteItem[] }>({
      query: ({ projectId, stackId, scenery }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/scenery`,
        method: 'PUT',
        body: { scenery },
      }),
      invalidatesTags: (_result, error, { projectId }) =>
        error ? [] : [{ type: 'CueStackList', id: projectId }, 'Cue'],
    }),
    setLookScenery: build.mutation<SceneryChange[], { projectId: number; lookId: number; scenery: SceneryWriteItem[] }>({
      query: ({ projectId, lookId, scenery }) => ({
        url: `projects/${projectId}/looks/${lookId}/scenery`,
        method: 'PUT',
        body: { scenery },
      }),
      invalidatesTags: (_result, error, { lookId }) => (error ? [] : [{ type: 'Look', id: lookId }]),
    }),
    liveScenery: build.query<LiveScenery, void>({
      queryFn: () => ({ data: lightingApi.scenery.getState() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.scenery.subscribe((scenery) => {
          updateCachedData(() => scenery)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const {
  useSetCueSceneryMutation,
  useSetStackSceneryMutation,
  useSetLookSceneryMutation,
  useLiveSceneryQuery,
} = sceneryApiSlice

/** The stage's live scenery, as the desk resolves it; empty before the first frame. */
export function useLiveScenery(enabled = true): LiveScenery {
  const { data } = useLiveSceneryQuery(undefined, { skip: !enabled })
  return data ?? NO_SCENERY
}
