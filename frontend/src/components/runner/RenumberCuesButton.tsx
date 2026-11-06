import { useState } from 'react'
import { ListOrdered } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { cueNumberValue } from '@/lib/cueNumber'
import { useRenumberCueStackEntriesMutation } from '@/store/cueStacks'
import { ignoreReportedError } from '@/store/errorToastMiddleware'

/** The highest cue number's whole part: a renumber writes whole numbers only. */
const MAX_WHOLE = 9999

/** A selected cue the renumber writes, in stack order. */
export interface RenumberRow {
  entryId: number
  cueNumber: string | null
}

/** The whole number a field holds, or null for anything else. */
function wholeOf(text: string): number | null {
  const trimmed = text.trim()
  return /^\d+$/.test(trimmed) ? Number(trimmed) : null
}

/**
 * Why a renumber of `count` cues from `start` by `step` cannot be sent, or null. Whole numbers only:
 * the first 0 or more, the step 1 or more, the last at most 9999.
 */
export function renumberProblem(count: number, start: string, step: string): string | null {
  const first = wholeOf(start)
  const by = wholeOf(step)
  if (first == null) return 'The first number is a whole number, 0 or more'
  if (by == null || by < 1) return 'The step is a whole number, 1 or more'
  const last = first + by * (count - 1)
  if (last > MAX_WHOLE) return `The last cue would be Q${last}; cue numbers go up to ${MAX_WHOLE}`
  return null
}

/** The numbers a renumber writes: `start`, `start + step`, … */
export function renumberPreview(count: number, start: number, step: number): string {
  const numbers = Array.from({ length: Math.min(count, 4) }, (_, i) => `Q${start + step * i}`)
  if (count > 4) numbers.splice(3, 1, '…', `Q${start + step * (count - 1)}`)
  return numbers.join(', ')
}

/**
 * *Renumber…* on the cue sheet's selection bar: the selected cues numbered in stack order from a
 * whole number, by a whole step (`POST cue-stacks/{id}/renumber`). Nothing moves; a number that then
 * reads out of order is the banner's *Fix Order* to settle.
 */
export function RenumberCuesButton({
  projectId,
  stackId,
  rows,
  disabledReason,
}: {
  projectId: number
  stackId: number
  rows: readonly RenumberRow[]
  disabledReason?: string | null
}) {
  const [open, setOpen] = useState(false)
  const [start, setStart] = useState('')
  const [step, setStep] = useState('1')
  const [renumber, { isLoading }] = useRenumberCueStackEntriesMutation()

  const count = rows.length
  const problem = renumberProblem(count, start, step)

  const onOpenChange = (next: boolean) => {
    if (next) {
      // Starts from the first selected cue's whole number, so "renumber these from where they begin"
      // is one click.
      const first = rows[0]?.cueNumber != null ? cueNumberValue(rows[0].cueNumber) : null
      setStart(String(first != null ? Math.max(Math.floor(first), 0) : 1))
      setStep('1')
    }
    setOpen(next)
  }

  const submit = () => {
    if (problem || isLoading) return
    const first = Number(start.trim())
    const by = Number(step.trim())
    renumber({ projectId, stackId, entryIds: rows.map((r) => r.entryId), start: first, step: by })
      .unwrap()
      .then(() => {
        toast.success(`Renumbered ${count} ${count === 1 ? 'cue' : 'cues'} from Q${first}`)
        setOpen(false)
      })
      .catch(ignoreReportedError)
  }

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          disabled={disabledReason != null || count === 0}
          title={disabledReason ?? `Number the ${count === 1 ? 'selected cue' : `${count} selected cues`} in order`}
        >
          <ListOrdered className="size-3.5" />
          <span className={WORD_CLASS}>Renumber…</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-72" align="end">
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            submit()
          }}
        >
          <p className="text-sm font-medium">
            Renumber {count} {count === 1 ? 'cue' : 'cues'}
          </p>
          <div className="flex gap-2">
            <div className="flex-1 space-y-1">
              <Label htmlFor="renumber-start" className="text-xs">
                First
              </Label>
              <Input
                id="renumber-start"
                inputMode="numeric"
                value={start}
                onChange={(e) => setStart(e.target.value)}
                autoFocus
              />
            </div>
            <div className="w-20 space-y-1">
              <Label htmlFor="renumber-step" className="text-xs">
                Step
              </Label>
              <Input id="renumber-step" inputMode="numeric" value={step} onChange={(e) => setStep(e.target.value)} />
            </div>
          </div>
          <p className="text-xs text-muted-foreground" data-renumber-preview>
            {problem ?? renumberPreview(count, Number(start.trim()), Number(step.trim()))}
          </p>
          <Button type="submit" size="sm" className="w-full" disabled={problem != null || isLoading}>
            Renumber
          </Button>
        </form>
      </PopoverContent>
    </Popover>
  )
}
