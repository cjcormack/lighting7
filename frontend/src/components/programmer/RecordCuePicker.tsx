import { Fragment, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { useProjectCueListQuery } from '@/store/cues'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { cuePickerSections, filterCuePickerSections } from './cuePickerRows'

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
 * of which the row names. The list runs in show order under a divider per stack (D15), and a cue
 * picked before the list drew it is brought into view once.
 */
export function RecordCuePicker({ projectId, value, onChange, disabled }: RecordCuePickerProps) {
  const { data: cues, isLoading } = useProjectCueListQuery(projectId)
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const [filter, setFilter] = useState('')
  const name = useId()

  const sections = useMemo(() => cuePickerSections(cues ?? [], stacks), [cues, stacks])
  const shown = useMemo(() => filterCuePickerSections(sections, filter), [sections, filter])
  const cueCount = sections.reduce((n, section) => n + section.rows.length, 0)

  const listRef = useRef<HTMLDivElement>(null)
  const revealedRef = useRef<number | null>(null)
  useEffect(() => {
    const list = listRef.current
    if (value == null || revealedRef.current === value || list == null) return
    const row = list.querySelector<HTMLElement>(`[data-cue-id="${value}"]`)
    if (row == null) return
    revealedRef.current = value
    if (row.offsetTop < list.scrollTop || row.offsetTop + row.offsetHeight > list.scrollTop + list.clientHeight) {
      list.scrollTop = row.offsetTop - (list.clientHeight - row.offsetHeight) / 2
    }
  }, [value, shown])

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
        ref={listRef}
        className="relative max-h-56 space-y-0.5 overflow-y-auto rounded-md border p-1"
      >
        {shown.map((section) => (
          <Fragment key={section.key}>
            <div
              data-picker-divider
              className="px-2 pt-2 pb-0.5 text-[10px] font-semibold uppercase tracking-[0.08em] text-muted-foreground first:pt-1"
            >
              {section.label}
              {section.busk && <span className="font-normal normal-case tracking-normal"> · busk</span>}
            </div>
            {section.rows.map((row) => (
              <label
                key={row.id}
                data-cue-id={row.id}
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
                  // A checked radio fires no change, and a preselected row pressed is still a pick.
                  onClick={() => onChange(row.id)}
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
          </Fragment>
        ))}
        {shown.length === 0 && (
          <p className="px-2 py-1.5 text-sm text-muted-foreground">
            {isLoading ? 'Loading cues…' : cueCount === 0 ? 'This show has no cues yet.' : 'No cues match.'}
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
