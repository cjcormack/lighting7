import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import {
  GroupSummary,
  GroupDetail,
  ApplyFxRequest,
  ApplyFxResponse,
  GroupActiveEffect,
  GroupPropertyDescriptor,
  type ElementMode,
} from "../api/groupsApi"

// `GroupList` freshness is not wired here: the group register only changes inside
// `Fixtures.register {}`, whose tail fires `fixturesChanged`, so `store/fixtures.ts` carries the
// tag. The groups WS layer that used to claim this job was deleted along with the backend's
// `GroupSocket` — it had been announcing frames the server never sent.

// Invalidate GroupActiveEffects when any FX changes (shared /fx/{id} endpoints)
lightingApi.fx.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['GroupActiveEffects']))
})

// === Types ===

export interface UpdateGroupFxRequest {
  effectType?: string
  parameters?: Record<string, string>
  beatDivision?: number
  blendMode?: string
  phaseOffset?: number
  distributionStrategy?: string
  elementMode?: ElementMode
  elementFilter?: string
  stepTiming?: boolean
}

export const groupsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    // List all groups
    groupList: build.query<GroupSummary[], void>({
      query: () => 'groups',
      providesTags: ['GroupList'],
    }),

    // Get single group with members
    group: build.query<GroupDetail, string>({
      query: (name) => `groups/${encodeURIComponent(name)}`,
      providesTags: (_result, _error, name) => [{ type: 'GroupList', id: name }],
    }),

    // Get group properties (aggregated property descriptors for all members)
    groupProperties: build.query<GroupPropertyDescriptor[], string>({
      query: (name) => `groups/${encodeURIComponent(name)}/properties`,
      providesTags: (_result, _error, name) => [{ type: 'GroupList', id: name }],
    }),

    // The distribution-strategy vocabulary is not fetched: `DistributionStrategy` is a closed
    // union in `api/groupsApi.ts` and the pickers render it directly, so the route's list would
    // only be a second spelling of the same set.

    // Get active effects for a group
    groupActiveEffects: build.query<GroupActiveEffect[], string>({
      query: (groupName) => `groups/${encodeURIComponent(groupName)}/fx/active`,
      providesTags: (_result, _error, groupName) => [
        { type: 'GroupActiveEffects', id: groupName },
      ],
    }),

    // Apply effect to group
    applyGroupFx: build.mutation<ApplyFxResponse, { groupName: string } & ApplyFxRequest>({
      query: ({ groupName, ...request }) => ({
        url: `groups/${encodeURIComponent(groupName)}/fx`,
        method: 'POST',
        body: request,
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupList', id: groupName },
        { type: 'GroupActiveEffects', id: groupName },
        'GroupList',
      ],
    }),

    // `clearGroupFx` (DELETE the whole group's effects) stood here. Every surface removes
    // effects one at a time through `removeGroupFx`, so the bulk delete had no caller.

    // Pause a single group effect (shared /fx/{id} endpoint)
    pauseGroupFx: build.mutation<void, { id: number; groupName: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/pause`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupActiveEffects', id: groupName },
        'FixtureEffects',
      ],
    }),

    // Resume a single group effect
    resumeGroupFx: build.mutation<void, { id: number; groupName: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/resume`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupActiveEffects', id: groupName },
        'FixtureEffects',
      ],
    }),

    // Remove a single group effect
    removeGroupFx: build.mutation<void, { id: number; groupName: string }>({
      query: ({ id }) => ({
        url: `fx/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupActiveEffects', id: groupName },
        { type: 'GroupList', id: groupName },
        'GroupList',
        'FixtureEffects',
      ],
    }),

    // Update a single group effect
    updateGroupFx: build.mutation<void, { id: number; groupName: string; body: UpdateGroupFxRequest }>({
      query: ({ id, body }) => ({
        url: `fx/${id}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupActiveEffects', id: groupName },
        'FixtureEffects',
      ],
    }),
  }),
  overrideExisting: false,
})

export const {
  useGroupListQuery,
  useGroupQuery,
  useGroupPropertiesQuery,
  useGroupActiveEffectsQuery,
  useApplyGroupFxMutation,
  usePauseGroupFxMutation,
  useResumeGroupFxMutation,
  useRemoveGroupFxMutation,
  useUpdateGroupFxMutation,
} = groupsApi
