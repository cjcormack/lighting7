import { restApi, type RestTagType } from "./restApi"
import type {
  DetachResult,
  ExtractKind,
  ExtractVenueResult,
  ReplaceSeatingResult,
  SeatingPresets,
  VenueDiff,
  VenueHistory,
  VenueVersion,
} from "../api/venueApi"
import { useProjectQuery } from "./projects"
import { useIsNavAdmin } from "../navigation"

/**
 * Every tag a venue's draft is read from. A History or a diff against the draft is stale the moment
 * any of the rig's own caches is, so each provides them all: an edit anywhere in the venue — through
 * this window's mutation, or another window's bridged frame — refetches the draft count, the Patch
 * list's markers and an open Publish sheet. `Project` rides along because publish and discard
 * announce themselves with `projectDetailsChanged`, which the projects slice bridges to it.
 */
const VENUE_DRAFT_TAGS: readonly RestTagType[] = [
  'Venue',
  'Project',
  'Patch',
  'UniverseConfig',
  'Rigging',
  'StageRegion',
  'StageElement',
  'StageViewpoint',
  'GroupList',
]

/**
 * Everything an extract or a detach moves between a show and its venue: the projects (pin, list,
 * History's shows), the rig in every shape, and the show's rows that linked into it.
 */
const SHOW_RIG_TAGS: readonly RestTagType[] = [
  ...VENUE_DRAFT_TAGS,
  'ProjectList',
  'VenueUpdate',
  'Fixture',
  'Cue',
  'CueList',
  'CueStackList',
  'Look',
  'LookList',
  'BuskRig',
]

export const venuesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    venueHistory: build.query<VenueHistory, number>({
      query: (venueId) => `venues/${venueId}/versions`,
      providesTags: [...VENUE_DRAFT_TAGS],
    }),

    /** `from` → `to`: version numbers, 0 the empty venue, `'draft'` the draft. */
    venueDiff: build.query<VenueDiff, { venueId: number; from: number; to: number | 'draft' }>({
      query: ({ venueId, from, to }) => `venues/${venueId}/versions/${from}/diff/${to}`,
      providesTags: [...VENUE_DRAFT_TAGS],
    }),

    publishVenue: build.mutation<VenueVersion, { venueId: number; note: string }>({
      query: ({ venueId, note }) => ({ url: `venues/${venueId}/publish`, method: 'POST', body: { note } }),
      invalidatesTags: ['Venue'],
    }),

    /** Back to the installed version: the venue's rows are replaced, so every rig cache goes. */
    discardVenueDraft: build.mutation<VenueHistory, number>({
      query: (venueId) => ({ url: `venues/${venueId}/draft`, method: 'DELETE' }),
      invalidatesTags: [...VENUE_DRAFT_TAGS, 'Fixture', 'ProjectList'],
    }),

    /**
     * *Extract a venue* (venue plan session 3's route, session 7's sheet): an admin's. A dry run is the
     * move rolled back on the desk — the sheet's preview — and invalidates nothing.
     */
    extractVenue: build.mutation<
      ExtractVenueResult,
      { projectId: number; name: string; include?: ExtractKind[]; dryRun?: boolean }
    >({
      query: ({ projectId, name, include, dryRun }) => ({
        url: `projects/${projectId}/venue/extract`,
        method: 'POST',
        body: { name, ...(include ? { include } : {}), ...(dryRun ? { dryRun: true } : {}) },
      }),
      invalidatesTags: (_result, error, { dryRun }) => (error || dryRun ? [] : [...SHOW_RIG_TAGS]),
    }),

    /**
     * *Detach* (venue plan D9, session 7): the show's rig becomes its own rows and the pin goes. A dry
     * run is the detach rolled back — the confirm's preview — and invalidates nothing.
     */
    detachVenue: build.mutation<DetachResult, { projectId: number; dryRun?: boolean }>({
      query: ({ projectId, dryRun }) => ({
        url: `projects/${projectId}/detach`,
        method: 'POST',
        body: dryRun ? { dryRun: true } : {},
      }),
      invalidatesTags: (_result, error, { dryRun }) => (error || dryRun ? [] : [...SHOW_RIG_TAGS]),
    }),

    /** What a venue offers to seat from (its installed version), or a show (its pinned one), and the show's own. */
    seatingPresets: build.query<SeatingPresets, number>({
      query: (projectId) => `projects/${projectId}/seating/presets`,
      providesTags: ['StageElement', 'Venue', 'Project'],
    }),

    /** *Replace from preset*: the show's stalls swapped for a fresh copy (venue plan D6). */
    replaceSeatingFromPreset: build.mutation<
      ReplaceSeatingResult,
      { projectId: number; elementUuid: string; force?: boolean }
    >({
      query: ({ projectId, elementUuid, force }) => ({
        url: `projects/${projectId}/seating/from-preset`,
        method: 'POST',
        body: { elementUuid, ...(force ? { force: true } : {}) },
      }),
      invalidatesTags: (_result, error) =>
        error ? [] : ['StageElement', 'StageViewpoint', 'Cue', 'CueList', 'CueStackList', 'LookList', 'Look'],
    }),
  }),
})

export const {
  useVenueHistoryQuery,
  useVenueDiffQuery,
  usePublishVenueMutation,
  useDiscardVenueDraftMutation,
  useExtractVenueMutation,
  useDetachVenueMutation,
  useSeatingPresetsQuery,
  useReplaceSeatingFromPresetMutation,
} = venuesApi

/**
 * Where a project stands as a venue: whether it is one, and whether this account may edit it
 * (venue plan D3 — admins edit and publish; an operator sees the venue read-only, with no draft,
 * History or Publish). `canEdit` is presentation, as `useIsNavAdmin` is: the desk refuses an
 * operator's write with `VENUE_ADMIN_ONLY` whatever the UI shows.
 */
export function useVenueMode(projectId: number | undefined): { isVenue: boolean; canEdit: boolean } {
  const { data: project } = useProjectQuery(projectId ?? 0, { skip: projectId == null || !(projectId > 0) })
  const isAdmin = useIsNavAdmin()
  const isVenue = project?.kind === 'VENUE'
  return { isVenue, canEdit: isVenue && isAdmin }
}

/**
 * The venue's History, for an admin in venue mode only — the route answers an operator 403, and a
 * show has none.
 */
export function useVenueHistory(projectId: number | undefined) {
  const { isVenue, canEdit } = useVenueMode(projectId)
  return useVenueHistoryQuery(projectId ?? 0, { skip: !isVenue || !canEdit })
}
