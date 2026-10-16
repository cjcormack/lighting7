import { useCallback, useMemo } from 'react'
import { isVenueOwned } from '@/api/patchApi'
import { useReorderGroupMembersMutation } from '@/store/groups'
import { usePatchGroupDetailQuery, usePatchGroupListQuery } from '@/store/patches'
import { useCurrentProjectQuery } from '@/store/projects'
import { useVenueMode } from '@/store/venues'
import { ignoreReportedError } from '@/store/errorToastMiddleware'

/**
 * Whether the group sheet may reorder a group's members, and the write that does (venue-visit plan
 * S7). `editable` writes the members' keys in their new order — only the ones the view shows; the
 * desk keeps the rest in their places — through the patch-group PUT's `memberOrder`.
 *
 * The runtime group (`GET /groups/{name}`) has no patch-group id, owner or patch ids, so they come
 * from the current project's patch groups, joined by name (a group's name is unique in a rig).
 * `readOnly` carries the reason the sheet shows: a venue's group in a show is the venue's to order
 * (the PUT would be 409 `VENUE_OWNED`), and a venue's rig is an admin's (403 `VENUE_ADMIN_ONLY`).
 */
export type GroupMemberReorder =
  | { kind: 'editable'; write: (keys: string[]) => void }
  | { kind: 'readOnly'; reason: string }
  | { kind: 'pending' }

export const VENUE_GROUP_ORDER_REASON = 'The venue’s group: its order is set in the venue.'
export const VENUE_ADMIN_ORDER_REASON = 'Only an admin changes a venue’s rig.'

export function useGroupMemberReorder(groupName: string): GroupMemberReorder {
  const { data: project } = useCurrentProjectQuery()
  const projectId = project?.id
  const { data: patchGroups } = usePatchGroupListQuery(projectId ?? 0, { skip: projectId == null })
  const patchGroup = patchGroups?.find((g) => g.name === groupName)
  const venueOwned = patchGroup != null && isVenueOwned(patchGroup)
  const { data: detail } = usePatchGroupDetailQuery(
    { projectId: projectId ?? 0, groupId: patchGroup?.id ?? 0 },
    { skip: projectId == null || patchGroup == null || venueOwned },
  )
  const { isVenue, canEdit } = useVenueMode(projectId)
  const [reorder] = useReorderGroupMembersMutation()

  const patchIdOf = useMemo(() => new Map((detail?.members ?? []).map((m) => [m.fixtureKey, m.patchId])), [detail])
  const groupId = detail?.id
  const write = useCallback(
    (keys: string[]) => {
      if (projectId == null || groupId == null) return
      const memberOrder = keys.map((key) => patchIdOf.get(key))
      // A key the stored group does not hold (a reload in flight) — send nothing rather than a guess.
      if (memberOrder.some((id) => id === undefined)) return
      reorder({ projectId, groupId, groupName, memberOrder: memberOrder as number[], keys })
        .unwrap()
        .catch(ignoreReportedError)
    },
    [projectId, groupId, groupName, patchIdOf, reorder],
  )

  if (venueOwned) return { kind: 'readOnly', reason: VENUE_GROUP_ORDER_REASON }
  if (isVenue && !canEdit) return { kind: 'readOnly', reason: VENUE_ADMIN_ORDER_REASON }
  if (detail == null) return { kind: 'pending' }
  return { kind: 'editable', write }
}
