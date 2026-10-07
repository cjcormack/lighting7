import { useMemo } from 'react'
import { ChevronRight } from 'lucide-react'
import { movesWithEntries, type MovesWithEntry } from '@/lib/scenery'
import { cn } from '@/lib/utils'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { useStageElementSceneryQuery } from '@/store/stageElements'
import type { StageElementDto } from '@/api/stageElementApi'

/**
 * What moves one element (scenery-programmer plan D11): every cue that changes it, every stack whose
 * set holds it and every Look that shows it while live, from `GET stage-elements/{id}/scenery`. The
 * Stage popover's read-out and the element form's *Moves with* — one list, so the two cannot name a
 * piece's owners two ways.
 *
 * With [onOpen] each entry is a press that opens that owner's own editor — Cue properties, Stack
 * settings, the Look sheet — since scenery changes are edited on their owner and never on the
 * element; without it the list only reads. Read afresh on every mount: a Look's own scenery write
 * invalidates that Look's entry alone, which the read does not carry.
 */
export function MovesWithList({
  projectId,
  element,
  onOpen,
  className,
}: {
  projectId: number
  element: StageElementDto
  onOpen?: (entry: MovesWithEntry) => void
  className?: string
}) {
  const { currentData: read, isError } = useStageElementSceneryQuery(
    { projectId, elementId: element.id },
    { refetchOnMountOrArgChange: true },
  )
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const entries = useMemo(() => {
    if (read == null) return null
    const names = new Map((stacks ?? []).map((s) => [s.id, s.name]))
    return movesWithEntries(element, read, (id) => names.get(id))
  }, [read, stacks, element])

  if (entries == null) {
    return (
      <p className={cn('text-xs text-muted-foreground', className)} data-moves-with>
        {isError ? 'Could not read what moves it.' : 'Reading…'}
      </p>
    )
  }
  if (entries.length === 0) {
    return (
      <p className={cn('text-xs text-muted-foreground', className)} data-moves-with>
        No cue, stack or Look moves it.
      </p>
    )
  }
  return (
    <ul className={cn('space-y-0.5', className)} data-moves-with aria-label={`What moves ${element.name}`}>
      {entries.map((entry) => (
        <li key={`${entry.kind}:${entry.id}`} data-moves-with-entry={entry.kind}>
          {onOpen == null ? (
            <p className="truncate text-xs" title={`${entry.owner} · ${entry.what}`}>
              <span className="font-medium">{entry.owner}</span>
              <span className="text-muted-foreground"> · {entry.what}</span>
            </p>
          ) : (
            <button
              type="button"
              onClick={() => onOpen(entry)}
              title={`Open ${ownerEditor(entry)}`}
              className="group flex w-full min-w-0 items-center gap-1 rounded-sm px-1 py-1 text-left text-xs hover:bg-accent focus-visible:bg-accent focus-visible:outline-none"
            >
              <span className="min-w-0 flex-1 truncate">
                <span className="font-medium">{entry.owner}</span>
                <span className="text-muted-foreground"> · {entry.what}</span>
              </span>
              <ChevronRight className="size-3.5 shrink-0 text-muted-foreground group-hover:text-foreground" />
            </button>
          )}
        </li>
      ))}
    </ul>
  )
}

/** The editor an entry opens, as its press names it. */
export function ownerEditor(entry: Pick<MovesWithEntry, 'kind' | 'owner'>): string {
  switch (entry.kind) {
    case 'cue':
      return `${entry.owner}'s cue properties`
    case 'set':
      return 'the stack settings'
    case 'look':
      return `${entry.owner}`
  }
}
