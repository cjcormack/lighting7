import type { ReactNode } from 'react'
import { AlertTriangle, MonitorSmartphone } from 'lucide-react'
import type { VenueChange, VenueDiff } from '@/api/venueApi'
import { changeLine, changeSign } from '@/lib/venue'
import { cn } from '@/lib/utils'
import { EditorLabel } from '@/components/editor/EditorLabel'

/**
 * A diff as the Publish sheet and History draw it (venue plan §3.1): **Changes output** first, red-
 * amber, then **Drawing only** — the same classification a show's update will use, so the note
 * writes itself. Each change is a sign, a title and, where it helps, what moved.
 */
export function VenueChangeList({ diff, empty = 'No changes.' }: { diff: VenueDiff; empty?: string }) {
  if (diff.output.length === 0 && diff.drawing.length === 0) {
    return <p className="text-sm text-muted-foreground">{empty}</p>
  }
  return (
    <div className="space-y-4">
      {diff.output.length > 0 && (
        <ChangeGroup
          label="Changes output"
          icon={<AlertTriangle className="size-3.5 text-orange-600 dark:text-orange-400" />}
          changes={diff.output}
          output
        />
      )}
      {diff.drawing.length > 0 && (
        <ChangeGroup
          label="Drawing only"
          icon={<MonitorSmartphone className="size-3.5 text-muted-foreground" />}
          changes={diff.drawing}
        />
      )}
    </div>
  )
}

function ChangeGroup({
  label,
  icon,
  changes,
  output = false,
}: {
  label: string
  icon: ReactNode
  changes: VenueChange[]
  output?: boolean
}) {
  return (
    <section className="space-y-1.5" aria-label={label}>
      <div className="flex items-center gap-1.5">
        {icon}
        <EditorLabel className={cn(output && 'text-orange-700 dark:text-orange-400')}>{label}</EditorLabel>
        <span className="rounded bg-muted px-1.5 text-[10px] font-semibold tabular-nums">{changes.length}</span>
      </div>
      <ul className="space-y-1">
        {changes.map((change) => {
          const { title, detail } = changeLine(change)
          return (
            <li
              key={`${change.table}/${change.uuid ?? change.name}`}
              className="flex items-start gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm"
            >
              <span
                aria-hidden
                className={cn(
                  'w-3 shrink-0 text-center font-mono font-bold',
                  change.change === 'ADDED' && 'text-emerald-600 dark:text-emerald-400',
                  change.change === 'REMOVED' && 'text-red-600 dark:text-red-400',
                  change.change === 'CHANGED' && 'text-amber-600 dark:text-amber-400',
                )}
              >
                {changeSign(change)}
              </span>
              <span className="min-w-0">
                <span className="block truncate">{title}</span>
                {detail && <span className="block truncate text-xs text-muted-foreground">{detail}</span>}
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
