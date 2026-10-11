import { restApi, type RestTagType } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { NOT_HELD, type VenueHoldState } from '../api/venueHoldApi'
import type { UpdateDecisionAnswer, VenueUpdateReview } from '../api/venueApi'

/**
 * A show that is behind its venue (venue plan §3.5, session 6): the **hold** as `venue.hold` carries
 * it, *Run against*, and the **update** — its review and the update itself. All REST but the hold,
 * so the socket gains no operation. Both roles may review, update and run against: an update is a
 * stored write to the show, like hiding a venue fixture; editing and publishing the venue stay an
 * admin's.
 *
 * **The hold is form 3** (CLAUDE.md §"Where a WS bridge subscribes"): a stream with nothing to
 * refetch, in one cache entry fed by the WS layer and seeded from its last frame. This slice is on
 * the earliest render path (the app header's `ProgrammerIndicator` reads it), which form 3 is safe
 * on: the `queryFn` touches `lightingApi` only when the first reader mounts.
 */

/**
 * Everything an update can change: the pin (the project), the rig the show reads through it, and the
 * show's own rows that linked into the venue — cue events and scenery, a group's members, the busk
 * rig — plus the review itself.
 */
const UPDATE_TAGS: readonly RestTagType[] = [
  'Project',
  'ProjectList',
  'Venue',
  'VenueUpdate',
  'Patch',
  'UniverseConfig',
  'Rigging',
  'StageRegion',
  'StageElement',
  'StageViewpoint',
  'GroupList',
  'Fixture',
  'Cue',
  'CueList',
  'CueStackList',
  'Look',
  'LookList',
  'BuskRig',
]

const venueHoldSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    venueHold: build.query<VenueHoldState, void>({
      queryFn: () => ({ data: lightingApi.venueHold.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.venueHold.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /**
     * What an update to `to` (the installed version when absent) would do. Writes nothing — but the
     * desk answers it by running the update and rolling it back, so it is read only while the sheet or
     * the confirm is open (each refetches on opening) and its tags are narrow: a pin or a publish
     * refetches it, not every patch or cue edit while it sits in the cache.
     */
    venueUpdateReview: build.query<VenueUpdateReview, { projectId: number; to?: number }>({
      query: ({ projectId, to }) => `projects/${projectId}/venue/update${to != null ? `?to=${to}` : ''}`,
      providesTags: ['VenueUpdate', 'Project', 'Venue'],
    }),

    updateVenue: build.mutation<
      VenueUpdateReview,
      { projectId: number; to?: number; decisions: UpdateDecisionAnswer[]; dryRun?: boolean }
    >({
      query: ({ projectId, to, decisions, dryRun }) => ({
        url: `projects/${projectId}/venue/update`,
        method: 'POST',
        body: { ...(to != null ? { to } : {}), decisions, ...(dryRun ? { dryRun: true } : {}) },
      }),
      invalidatesTags: (_result, error, { dryRun }) => (error || dryRun ? [] : [...UPDATE_TAGS]),
    }),

    /** Lift the hold for this session, or put it back. Answers the hold; the frame follows on every window. */
    runVenueAgainst: build.mutation<VenueHoldState, { projectId: number; on: boolean }>({
      query: ({ projectId, on }) => ({ url: `projects/${projectId}/venue/run-against`, method: 'POST', body: { on } }),
    }),
  }),
  overrideExisting: false,
})

export const {
  useVenueHoldQuery,
  useVenueUpdateReviewQuery,
  useUpdateVenueMutation,
  useRunVenueAgainstMutation,
} = venueHoldSlice

/** The desk's hold, as the desk last said; not held before the first frame. */
export function useVenueHold(): VenueHoldState {
  const { data } = useVenueHoldQuery()
  return data ?? NOT_HELD
}
