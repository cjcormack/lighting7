import { useId, useMemo, useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { useProjectCueListQuery } from '@/store/cues'
import { cuePickerRows, filterCuePickerRows } from './cuePickerRows'

export interface RecordCuePickerProps {
  projectId: number
  value: number | null
  onChange: (cueId: number) => void
  disabled?: boolean
}

/**
 * Choose the cue a Merge, Replace or Remove Record writes into — the Record sheet's answer to
 * "An existing cue…" in the programmer's Record menu, which until S10 opened the sheet with only
 * *New cue* to offer.
 *
 * A filter over one radio list rather than a stack select then a cue select: the operator knows the
 * cue, not where it lives, and "where it lives" may be nowhere (*Loose*) or several places, each
 * of which the row names.
 */
export function RecordCuePicker({ projectId, value, onChange, disabled }: RecordCuePickerProps) {
  const { data: cues, isLoading } = useProjectCueListQuery(projectId)
  const [filter, setFilter] = useState('')
  const name = useId()

  const rows = useMemo(() => cuePickerRows(cues ?? []), [cues])
  const shown = useMemo(() => filterCuePickerRows(rows, filter), [rows, filter])

  return (
    <div className="space-y-2">
      <Label htmlFor={`${name}-filter`}>Cue</Label>
      <Input
        id={`${name}-filter`}
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        placeholder="Filter cues"
        aria-label="Filter cues"
        disabled={disabled}
      />
      <div
        role="radiogroup"
        aria-label="Cue"
        className="max-h-56 space-y-0.5 overflow-y-auto rounded-md border p-1"
      >
        {shown.map((row) => (
          <label
            key={row.id}
            className={cn(
              'flex cursor-pointer items-baseline gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent',
              row.id === value && 'bg-accent',
            )}
          >
            <input
              type="radio"
              name={name}
              value={row.id}
              checked={row.id === value}
              onChange={() => onChange(row.id)}
              disabled={disabled}
              className="size-3.5 shrink-0 self-center"
            />
            <span className="min-w-0 flex-1 truncate">{row.name}</span>
            <span className="max-w-[60%] shrink-0 truncate text-xs text-muted-foreground" data-picker-places>
              {row.places.length === 0
                ? 'Loose'
                : row.places.map((place, i) => (
                    <span key={i}>
                      {i > 0 && ', '}
                      {place.number != null && (
                        <span className={cn('tabular-nums', place.numberAuto && AUTO_CUE_NUMBER_CLASS)}>
                          {place.number}
                        </span>
                      )}
                      {place.number != null && ' · '}
                      {place.stackName}
                    </span>
                  ))}
            </span>
          </label>
        ))}
        {shown.length === 0 && (
          <p className="px-2 py-1.5 text-sm text-muted-foreground">
            {isLoading ? 'Loading cues…' : rows.length === 0 ? 'This show has no cues yet.' : 'No cues match.'}
          </p>
        )}
      </div>
    </div>
  )
}

/** The picked cue's name from the same list, for the sheet's description; null until it loads. */
export function usePickedCueName(projectId: number, cueId: number | null): string | null {
  // Skipped with nothing picked — a New cue Record has no use for the whole cue list.
  const { data: cues } = useProjectCueListQuery(projectId, { skip: cueId == null })
  if (cueId == null) return null
  return cues?.find((cue) => cue.id === cueId)?.name ?? null
}
