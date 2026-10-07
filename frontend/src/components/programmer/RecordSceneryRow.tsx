import { useMemo } from 'react'
import { heldSceneryIn } from '@/lib/scenery'
import { useProgrammerScenery } from '@/store/programmer'
import { useStageElementListQuery } from '@/store/stageElements'

export interface RecordSceneryRowProps {
  projectId: number
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  /** Where the Record lands — a cue writes only what it would not show anyway, a Look every held state. */
  destination: 'cue' | 'look'
}

/**
 * The Record sheets' *Record scenery too* row (scenery-programmer plan D7, §4's Record row): a
 * checkbox beside *Record effects too*, with a hint naming the pieces the programmer holds.
 *
 * **Absent when nothing is held** — there is nothing to say yes or no to — and ticked by default
 * whenever something is (each sheet resets it on open). The attribute mask and *Selected fixtures
 * only* never govern it: scenery is addressed by element, not through the selection (D3), so the
 * hint says what it does on its own terms.
 */
export function RecordSceneryRow({ projectId, checked, onCheckedChange, destination }: RecordSceneryRowProps) {
  const names = useHeldSceneryNames(projectId)
  if (names.length === 0) return null

  return (
    <div className="space-y-1" data-testid="record-scenery-row">
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => onCheckedChange(e.target.checked)}
          className="size-4"
        />
        Record scenery too
        <span className="text-muted-foreground tabular-nums">({names.length})</span>
      </label>
      <p className="text-xs text-muted-foreground">
        {names.join(' · ')} —{' '}
        {destination === 'cue'
          ? 'each held piece the cue would not already show there becomes a change row.'
          : 'the look keeps every held piece as it is now.'}
      </p>
    </div>
  )
}

/**
 * The names of the pieces the programmer holds in [projectId], in the order first held. A piece the
 * element list has not loaded (or no longer has) reads as *a piece*, so the count stays honest.
 */
export function useHeldSceneryNames(projectId: number): string[] {
  const scenery = useProgrammerScenery()
  const { data: elements } = useStageElementListQuery(projectId, { skip: scenery.elements.length === 0 })
  return useMemo(() => {
    const byUuid = new Map((elements ?? []).map((e) => [e.uuid, e.name]))
    return heldSceneryIn(scenery, projectId).map((item) => byUuid.get(item.elementUuid) ?? 'a piece')
  }, [elements, projectId, scenery])
}

/**
 * What a Record did to the scenery, as notes for the result panel — `1 scenery change`, `1 scenery
 * row removed`, `2 pieces already tracked` — each only where it happened.
 */
export function describeSceneryWrite(written = 0, removed = 0, alreadyTracked = 0): string[] {
  const notes: string[] = []
  if (written > 0) notes.push(`${written} scenery change${written === 1 ? '' : 's'}`)
  if (removed > 0) notes.push(`${removed} scenery row${removed === 1 ? '' : 's'} removed`)
  if (alreadyTracked > 0) {
    notes.push(`${alreadyTracked} held piece${alreadyTracked === 1 ? '' : 's'} already shown there`)
  }
  return notes
}
