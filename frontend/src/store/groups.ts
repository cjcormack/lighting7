import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import { placeInOrder } from "../lib/memberOrder"
import type { PatchGroupDetail } from "../api/patchApi"
import {
  GroupSummary,
  GroupDetail,
  ApplyFxRequest,
  ApplyFxResponse,
  GroupActiveFx,
  GroupAttributeDescriptor,
} from "../api/groupsApi"

// `GroupList` freshness is not wired here: the group register only changes inside
// `Fixtures.register {}`, whose tail fires `fixturesChanged`, so `store/fixtures.ts` carries the
// tag. The groups WS layer that used to claim this job was deleted along with the backend's
// `GroupSocket` — it had been announcing frames the server never sent.

// Invalidate GroupActiveFxList when any FX changes (shared /fx/{id} endpoints)
lightingApi.fx.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['GroupActiveFxList']))
})

// === Types ===

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
    groupAttributes: build.query<GroupAttributeDescriptor[], string>({
      query: (name) => `groups/${encodeURIComponent(name)}/attributes`,
      providesTags: (_result, _error, name) => [{ type: 'GroupList', id: name }],
    }),

    // The distribution-strategy vocabulary is not fetched: `SpreadOrder` is a closed
    // union in `api/groupsApi.ts` and the pickers render it directly, so the route's list would
    // only be a second spelling of the same set.

    /**
     * Reorder a group's members from the Groups views (venue-visit plan S7): the patch-group PUT's
     * `memberOrder`, sending only the members the view shows — the desk keeps every other member in
     * its place, and so does the optimistic update here ([placeInOrder]), which moves the runtime
     * group's members (renumbering `index`, which `orderSteps` is read by) and the list's
     * `memberKeys` so a drop does not snap back. A failure rolls both back; the errorToastMiddleware
     * reports it. The PUT reloads the rig before it answers, so the tags it invalidates refetch the
     * desk's own order.
     */
    reorderGroupMembers: build.mutation<
      PatchGroupDetail,
      { projectId: number; groupId: number; groupName: string; memberOrder: number[]; keys: string[] }
    >({
      query: ({ projectId, groupId, memberOrder }) => ({
        url: `projects/${projectId}/patch-groups/${groupId}`,
        method: 'PUT',
        body: { memberOrder },
      }),
      async onQueryStarted({ groupName, keys }, { dispatch, queryFulfilled }) {
        const detail = dispatch(
          groupsApi.util.updateQueryData('group', groupName, (draft) => {
            const moved = placeInOrder(draft.members, keys, (m) => m.fixtureKey)
            const last = moved.length - 1
            draft.members = moved.map((m, i) => ({ ...m, index: i, normalizedPosition: last > 0 ? i / last : 0.5 }))
          }),
        )
        const list = dispatch(
          groupsApi.util.updateQueryData('groupList', undefined, (draft) => {
            const group = draft.find((g) => g.name === groupName)
            if (group?.memberKeys) group.memberKeys = placeInOrder(group.memberKeys, keys, (k) => k)
          }),
        )
        try {
          await queryFulfilled
        } catch {
          detail.undo()
          list.undo()
        }
      },
      invalidatesTags: ['Patch', 'Fixture', 'GroupList'],
    }),

    // Get active effects for a group
    groupActiveFx: build.query<GroupActiveFx[], string>({
      query: (groupName) => `groups/${encodeURIComponent(groupName)}/fx/active`,
      providesTags: (_result, _error, groupName) => [
        { type: 'GroupActiveFxList', id: groupName },
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
        { type: 'GroupActiveFxList', id: groupName },
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
        { type: 'GroupActiveFxList', id: groupName },
        'FixtureFx',
      ],
    }),

    // Resume a single group effect
    resumeGroupFx: build.mutation<void, { id: number; groupName: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/resume`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupActiveFxList', id: groupName },
        'FixtureFx',
      ],
    }),

    // Remove a single group effect
    removeGroupFx: build.mutation<void, { id: number; groupName: string }>({
      query: ({ id }) => ({
        url: `fx/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { groupName }) => [
        { type: 'GroupActiveFxList', id: groupName },
        { type: 'GroupList', id: groupName },
        'GroupList',
        'FixtureFx',
      ],
    }),
  }),
  overrideExisting: false,
})

export const {
  useGroupListQuery,
  useGroupQuery,
  useGroupAttributesQuery,
  useGroupActiveFxQuery,
  useApplyGroupFxMutation,
  usePauseGroupFxMutation,
  useResumeGroupFxMutation,
  useRemoveGroupFxMutation,
  useReorderGroupMembersMutation,
} = groupsApi
