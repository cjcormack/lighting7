import { useMemo } from 'react'
import { isCueRow } from '@/api/cueStacksApi'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { useProjectCueListQuery } from '@/store/cues'
import type { MenuKind } from '@/components/menus/menuAdapter'
import { AssignToSurfaceMenuItem, type AssignTarget } from './AssignToSurface'
import type { AssignSubject } from './assignOffer'

/**
 * *Assign to surface…* for a cue, from the row its menu acts on: its place first (GO TO, Standby)
 * where it was opened from one, then the cue itself (a loose hold). Its uuids are read from the
 * stacks, and from the cue list only for a cue in none.
 */
export function AssignCueToSurfaceItem({
  projectId,
  cueId,
  entryId,
  name,
  menu,
}: {
  projectId: number
  cueId: number
  entryId: number | null
  name: string
  menu: MenuKind
}) {
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const placed = useMemo(() => {
    let entryUuid: string | null = null
    let cueUuid: string | null = null
    for (const stack of stacks ?? []) {
      for (const row of stack.cues) {
        if (!isCueRow(row) || row.id !== cueId) continue
        cueUuid ??= row.cueUuid ?? null
        if (entryId != null && row.entryId === entryId) entryUuid = row.uuid ?? null
      }
    }
    return { entryUuid, cueUuid }
  }, [stacks, cueId, entryId])
  // The cue list only for a cue in no stack.
  const { data: cues } = useProjectCueListQuery(projectId, { skip: stacks == null || placed.cueUuid != null })
  const target = useMemo<AssignTarget | null>(() => {
    const subjects: AssignSubject[] = []
    if (placed.entryUuid != null) subjects.push({ type: 'record', kind: 'ENTRY', uuid: placed.entryUuid })
    const cueUuid = placed.cueUuid ?? (cues ?? []).find((c) => c.id === cueId)?.uuid
    if (cueUuid != null) subjects.push({ type: 'record', kind: 'CUE', uuid: cueUuid })
    return subjects.length === 0 ? null : { name, subjects, projectId }
  }, [placed, cues, cueId, name, projectId])
  if (target == null) return null
  return <AssignToSurfaceMenuItem target={target} menu={menu} />
}
