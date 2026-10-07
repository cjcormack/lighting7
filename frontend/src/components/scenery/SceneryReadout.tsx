import { useMemo } from 'react'
import { ArrowUpDown, Blinds, Box } from 'lucide-react'
import { describeCueChange, describeSceneryState, sceneryKeysOf } from '@/lib/scenery'
import { useStageElementListQuery } from '@/store/stageElements'
import { cn } from '@/lib/utils'
import { TRACKED_HATCH_CLASS } from './trackedHatch'
import type { SceneryChange, TrackedScenery } from '@/api/sceneryApi'
import type { StageElementDto } from '@/api/stageElementApi'

/** A row's icon: a flown piece flies, a drawn drape draws, anything else is a piece. Shared with the programmer rail's scenery rows. */
export function ElementIcon({ element }: { element: StageElementDto | undefined }) {
  const keys = element ? sceneryKeysOf(element) : []
  const Icon = keys.includes('trimM') ? ArrowUpDown : keys.includes('open') ? Blinds : Box
  return <Icon className="size-3.5 shrink-0 text-muted-foreground" />
}

/**
 * A cue's scenery as its card reads it (stage-view plan session 8, `Cue.dc.html`): the changes the
 * cue makes on GO, each on its clock, and under them — hatched — what the cue shows without moving
 * it, tracked from an earlier cue or held by its stack's set. Read-only, like the rest of the card;
 * the changes are edited in Cue properties.
 */
export function CueSceneryReadout({
  projectId,
  scenery,
  tracked,
  enabled = true,
}: {
  projectId: number
  scenery: readonly SceneryChange[]
  tracked: readonly TrackedScenery[]
  enabled?: boolean
}) {
  const { data: elements } = useStageElementListQuery(projectId, { skip: !enabled })
  const byUuid = useMemo(() => new Map((elements ?? []).map((e) => [e.uuid, e])), [elements])

  if (scenery.length === 0 && tracked.length === 0) {
    return <p className="text-[11px] text-muted-foreground">None.</p>
  }
  return (
    <div className="space-y-1">
      {[...scenery]
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .map((change) => {
          const element = byUuid.get(change.elementUuid)
          return (
            <div key={change.uuid} className="flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs">
              <ElementIcon element={element} />
              <span className="font-medium">{element?.name ?? change.elementName}</span>
              <span className="text-muted-foreground">{describeCueChange(element, change.state, change.transitionMs)}</span>
            </div>
          )
        })}
      {tracked.map((t) => {
        const element = byUuid.get(t.elementUuid)
        // A number reads as Q2; a cue known only by its name reads as its name.
        const label = t.fromCueLabel
        const from = t.fromSet ? "the stack's set" : label == null ? 'an earlier cue' : /^\d/.test(label) ? `Q${label}` : label
        return (
          <div
            key={`tracked-${t.elementUuid}`}
            className={cn('rounded-md px-2 py-1 text-xs text-muted-foreground', TRACKED_HATCH_CLASS)}
          >
            {`${element?.name ?? t.elementName}: ${describeSceneryState(element, t.state)} (${t.fromSet ? 'held by' : 'tracked from'} ${from})`}
          </div>
        )
      })}
    </div>
  )
}
