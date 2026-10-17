import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { ChevronRight, Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { useProjectListQuery } from '@/store/projects'
import { useCopyCuesFromMutation, useProjectCueListQuery } from '@/store/cues'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import type { Cue, CopyCuesFromResponse } from '@/api/cuesApi'

type Step = 'source' | 'cues' | 'stack' | 'review'

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** Where a source cue sits, as the Cues table's *In* reads it: `Act 2 · Q14`, or *Loose*. */
export function cuePlacesLabel(cue: Cue): string {
  const entries = cue.entries ?? []
  if (entries.length === 0) return 'Loose'
  return entries.map((e) => (e.cueNumber ? `${e.stackName} · Q${e.cueNumber}` : e.stackName)).join(', ')
}

/**
 * **Copy from another show…** (lighting7 cue model plan §3.8, D9, D18): the Cues table's library
 * verb. Pick the project, then its cues, then where they go — a stack, at its end, or loose — and the
 * desk's dry run says what would be copied and what will not resolve before anything is written.
 *
 * The mapping is the desk's alone (`POST /cues/copy-from`): Looks and palettes by name, masters by
 * index, scripts by name, venue records by uuid between two shows on one venue, fixtures by key and
 * elements by name. A target the copy cannot find is kept and the cue reads it as missing; an event
 * or scenery change that cannot resolve is left out. Both are listed here before the copy.
 */
export function CopyFromShowSheet({
  projectId,
  open,
  onOpenChange,
}: {
  /** The project the cues are copied into — the current one. */
  projectId: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [step, setStep] = useState<Step>('source')
  const [sourceId, setSourceId] = useState<number | null>(null)
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set())
  const [stackId, setStackId] = useState<number | null>(null)
  const [query, setQuery] = useState('')
  const [review, setReview] = useState<CopyCuesFromResponse | null>(null)
  const [copyCuesFrom, { isLoading: copying }] = useCopyCuesFromMutation()

  const { data: projects } = useProjectListQuery(undefined, { skip: !open })
  // `currentData`, not `data`: while another source loads, the last one's cues must not be listed.
  const { currentData: sourceCues, isFetching: cuesLoading } = useProjectCueListQuery(sourceId ?? 0, {
    skip: !open || sourceId == null,
  })
  const { data: stacks } = useProjectCueStackListQuery(projectId, { skip: !open })

  const sources = useMemo(
    () =>
      (projects ?? [])
        .filter((p) => p.id !== projectId)
        .sort((a, b) => Number(a.kind === 'VENUE') - Number(b.kind === 'VENUE') || a.name.localeCompare(b.name)),
    [projects, projectId],
  )
  const runnable = useMemo(() => (stacks ?? []).filter((s) => s.type === 'STACK'), [stacks])
  const source = sources.find((p) => p.id === sourceId) ?? null
  const cuesById = useMemo(() => new Map((sourceCues ?? []).map((c) => [c.id, c])), [sourceCues])
  const q = query.trim().toLowerCase()
  const shownSources = q === '' ? sources : sources.filter((p) => p.name.toLowerCase().includes(q))
  const shownCues = useMemo(
    () =>
      [...(sourceCues ?? [])]
        .sort((a, b) => a.name.localeCompare(b.name))
        .filter((c) => q === '' || c.name.toLowerCase().includes(q) || cuePlacesLabel(c).toLowerCase().includes(q)),
    [sourceCues, q],
  )
  /** The picked cues in the order the list draws them, which is the order they are placed. */
  const picked = useMemo(
    () => [...(sourceCues ?? [])].sort((a, b) => a.name.localeCompare(b.name)).filter((c) => selected.has(c.id)),
    [sourceCues, selected],
  )
  const stackName = runnable.find((s) => s.id === stackId)?.name ?? null

  const reset = () => {
    setStep('source')
    setSourceId(null)
    setSelected(new Set())
    setStackId(null)
    setQuery('')
    setReview(null)
  }
  const close = (next: boolean) => {
    if (!next) reset()
    onOpenChange(next)
  }
  const go = (next: Step) => {
    setQuery('')
    setStep(next)
  }

  const request = () => ({
    projectId,
    fromProjectId: sourceId!,
    cueIds: picked.map((c) => c.id),
    ...(stackId == null ? {} : { stackId }),
  })

  const runReview = async () => {
    try {
      setReview(await copyCuesFrom({ ...request(), dryRun: true }).unwrap())
      go('review')
    } catch {
      ignoreReportedError()
    }
  }

  const confirm = async () => {
    try {
      const done = await copyCuesFrom(request()).unwrap()
      const n = done.copied.length
      toast.success(
        `Copied ${plural(n, 'cue')} from ${source?.name ?? 'another show'}${stackName == null ? ', in no stack' : ` into ${stackName}`}`,
      )
      close(false)
    } catch {
      ignoreReportedError()
    }
  }

  const toggle = (id: number) =>
    setSelected((s) => {
      const next = new Set(s)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  const allShown = shownCues.length > 0 && shownCues.every((c) => selected.has(c.id))
  const toggleAllShown = () =>
    setSelected((s) => {
      const next = new Set(s)
      for (const c of shownCues) {
        if (allShown) next.delete(c.id)
        else next.add(c.id)
      }
      return next
    })

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent className="flex flex-col sm:max-w-md" data-copy-from-step={step}>
        <SheetHeader>
          <SheetTitle>Copy from another show</SheetTitle>
          <SheetDescription>{stepSentence(step, source?.name ?? null, picked.length, stackName)}</SheetDescription>
        </SheetHeader>

        {(step === 'source' || step === 'cues') && (
          <div className="relative px-4 pb-2">
            <Search className="absolute left-7 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Filter…"
              aria-label={step === 'source' ? 'Filter projects' : 'Filter cues'}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="h-8 pl-9"
            />
          </div>
        )}

        <SheetBody>
          {step === 'source' && (
            <ul className="divide-y rounded-md border" aria-label="Projects">
              {shownSources.length === 0 && <li className="p-3 text-sm text-muted-foreground">No other project.</li>}
              {shownSources.map((p) => (
                <li key={p.id}>
                  <button
                    type="button"
                    className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted/50"
                    onClick={() => {
                      if (p.id !== sourceId) setSelected(new Set())
                      setSourceId(p.id)
                      go('cues')
                    }}
                  >
                    <span className="min-w-0 flex-1 truncate font-medium">{p.name}</span>
                    {p.kind === 'VENUE' && <span className="text-xs text-muted-foreground">Venue</span>}
                    {p.venue?.name != null && <span className="truncate text-xs text-muted-foreground">{p.venue.name}</span>}
                    <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                  </button>
                </li>
              ))}
            </ul>
          )}

          {step === 'cues' && (
            <div className="space-y-2">
              {shownCues.length > 0 && (
                <label className="flex items-center gap-2 px-1 text-xs text-muted-foreground">
                  <input type="checkbox" checked={allShown} onChange={toggleAllShown} />
                  {allShown ? 'Clear the cues shown' : 'Select the cues shown'}
                </label>
              )}
              <ul className="divide-y rounded-md border" aria-label="Cues">
                {cuesLoading && sourceCues == null && <li className="p-3 text-sm text-muted-foreground">Reading…</li>}
                {!cuesLoading && shownCues.length === 0 && (
                  <li className="p-3 text-sm text-muted-foreground">
                    {(sourceCues ?? []).length === 0 ? `${source?.name ?? 'That project'} has no cues.` : 'No cues match.'}
                  </li>
                )}
                {shownCues.map((c) => (
                  <li key={c.id}>
                    <label className="flex cursor-pointer items-center gap-2 px-3 py-2 text-sm hover:bg-muted/50">
                      <input type="checkbox" checked={selected.has(c.id)} onChange={() => toggle(c.id)} />
                      <span className="min-w-0 flex-1 truncate">{c.name}</span>
                      <span className="max-w-[45%] truncate text-xs text-muted-foreground">{cuePlacesLabel(c)}</span>
                    </label>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {step === 'stack' && (
            <fieldset className="space-y-1" aria-label="Where the copies go">
              {[{ id: null as number | null, name: 'No stack — loose cues' }, ...runnable.map((s) => ({ id: s.id as number | null, name: s.name }))].map(
                (s) => (
                  <label
                    key={s.id ?? 'loose'}
                    className={cn(
                      'flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm',
                      stackId === s.id && 'border-primary bg-primary/5',
                    )}
                  >
                    <input type="radio" name="copy-stack" checked={stackId === s.id} onChange={() => setStackId(s.id)} />
                    <span className="truncate">{s.name}</span>
                    {s.id != null && <span className="ml-auto text-xs text-muted-foreground">at the end, unnumbered</span>}
                  </label>
                ),
              )}
            </fieldset>
          )}

          {step === 'review' && review != null && <CopyReview review={review} cuesById={cuesById} />}
        </SheetBody>

        <SheetFooter className="flex-row justify-between">
          {step === 'source' ? (
            <span />
          ) : (
            <Button variant="outline" onClick={() => go(step === 'review' ? 'stack' : step === 'stack' ? 'cues' : 'source')}>
              Back
            </Button>
          )}
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => close(false)}>
              Cancel
            </Button>
            {step === 'cues' && (
              <Button disabled={picked.length === 0} onClick={() => go('stack')}>
                Next{picked.length > 0 ? ` · ${picked.length}` : ''}
              </Button>
            )}
            {step === 'stack' && (
              <Button disabled={copying || picked.length === 0} onClick={runReview}>
                Review
              </Button>
            )}
            {step === 'review' && (
              <Button disabled={copying} onClick={confirm}>
                Copy {plural(picked.length, 'cue')}
              </Button>
            )}
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function stepSentence(step: Step, source: string | null, count: number, stack: string | null): string {
  switch (step) {
    case 'source':
      return 'Which project are the cues in?'
    case 'cues':
      return `Pick ${source ?? 'its'} cues to copy.`
    case 'stack':
      return count === 1 ? 'Where does the copy go?' : `Where do the ${count} copies go?`
    case 'review':
      return `Nothing is copied until you confirm. ${stack == null ? 'They go in no stack.' : `They go at the end of ${stack}.`}`
  }
}

/** The dry run's answer: what would be copied, and every reference that would not come across. */
export function CopyReview({
  review,
  cuesById,
}: {
  review: CopyCuesFromResponse
  cuesById: ReadonlyMap<number, Cue>
}) {
  const groups: [string, string[] | undefined][] = [
    ['Looks copied', review.looksCopied],
    ['Looks linked by name', review.looksLinked],
    ['Palettes copied', review.palettesCopied],
    ['Palettes linked by name', review.palettesLinked],
  ]
  return (
    <div className="space-y-4 text-sm" data-copy-review>
      <section>
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {plural(review.copied.length, 'cue')} to copy
        </h3>
        <ul className="list-disc space-y-0.5 pl-5" aria-label="Cues to copy">
          {review.copied.map((c) => (
            <li key={c.fromCueId}>{cuesById.get(c.fromCueId)?.name ?? `Cue ${c.fromCueId}`}</li>
          ))}
        </ul>
      </section>
      {groups.map(([label, names]) =>
        names != null && names.length > 0 ? (
          <section key={label}>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</h3>
            <p>{names.join(', ')}</p>
          </section>
        ) : null,
      )}
      <section>
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Will not resolve</h3>
        {review.unresolved.length === 0 ? (
          <p className="text-muted-foreground">Every fixture, element, master and script was found here.</p>
        ) : (
          <ul className="space-y-1.5" aria-label="Unresolved">
            {review.unresolved.map((u) => (
              <li key={u.problem} className="rounded-md border border-amber-500/40 bg-amber-500/5 px-2 py-1.5">
                <div>{u.problem}</div>
                <div className="text-xs text-muted-foreground">{u.in.join(', ')}</div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
