import { useState, type ReactNode } from 'react'
import { AlertTriangle, ArrowUpCircle, CircleSlash, HelpCircle, Layers, XCircle } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { formatError } from '@/lib/formatError'
import { cn } from '@/lib/utils'
import { FACET_LABELS } from '@/lib/venue'
import { useUpdateVenueMutation, useVenueUpdateReviewQuery } from '@/store/venueHold'
import type { UpdateAnswer, UpdateDecision, UpdateDrift, UpdateItem } from '@/api/venueApi'
import { VenueChangeList } from './VenueChangeList'

/** The words on a decision's answers (P8), in the board's order. */
const ANSWER_LABELS: Record<UpdateAnswer, string> = {
  KEEP_BOTH: 'Keep both',
  READDRESS_MINE: 'Re-address mine',
  HIDE_VENUES: "Hide the venue's",
  REMOVE_MINE: 'Remove mine',
  KEEP_MINE: 'Keep mine',
  TAKE_VENUES: "Take venue's",
}

/**
 * *Review update* (venue plan §3.5, P3, P8; `Update.dc.html`'s callouts 3–6): the hall's changes from
 * the show's version to the installed one, beside what they do to this show — carried over, kept
 * under drift, dropped, the clashes that need a decision, and every key that will point at nothing —
 * before anything is written. The review is the update rolled back on the desk, so it cannot disagree
 * with what *Update* does.
 *
 * An address overlap the update introduces needs an answer before *Update* is enabled; a key clash is
 * a blocker that cannot be kept, and the show must change first. A drifted override is kept unless
 * answered *Take venue's*, and after the update its drift lights the Patch list's chip as session 5
 * draws it. A refusal is drawn here (`updateVenue` is in `SILENT_ENDPOINTS`) and the answers are kept
 * for a retry. A sheet rather than the board's centred modal, because the decisions are a form
 * (CLAUDE.md §Sheets vs Dialogs).
 */
export function VenueUpdateSheet({
  open,
  onOpenChange,
  projectId,
  showName,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  showName: string
}) {
  const { data: review, error: reviewError, isFetching } = useVenueUpdateReviewQuery(
    { projectId },
    { skip: !open, refetchOnMountOrArgChange: true },
  )
  const [update, { isLoading: updating, error, reset }] = useUpdateVenueMutation()
  const [answers, setAnswers] = useState<Record<string, UpdateAnswer>>({})
  const [showCarried, setShowCarried] = useState(false)
  /** The version the answers were given for. */
  const [answeredFor, setAnsweredFor] = useState<number | null>(null)

  const close = () => {
    setAnswers({})
    setAnsweredFor(null)
    setShowCarried(false)
    reset()
    onOpenChange(false)
  }

  const decisions = review?.decisions ?? []
  const blockers = review?.blockers ?? []
  const undecided = decisions.filter((d) => answers[d.id] == null)
  const canUpdate = review != null && !updating && blockers.length === 0 && undecided.length === 0

  // Only the answers to what this review asks: a refetch (another window's edit, a newer version) can
  // drop a decision, and an answer to one the desk no longer has is refused as a whole.
  const asked = new Set([...decisions.map((d) => d.id), ...(review?.drift ?? []).map((d) => d.id)])
  const live = Object.entries(answers).filter(([id]) => asked.has(id))
  if (review != null && answeredFor !== review.to) {
    // A review of another version is another set of questions.
    setAnsweredFor(review.to)
    if (answeredFor != null) setAnswers({})
  }

  const handleUpdate = async () => {
    if (review == null) return
    try {
      await update({
        projectId,
        to: review.to,
        decisions: live.map(([id, answer]) => ({ id, answer })),
      }).unwrap()
    } catch {
      return
    }
    close()
  }

  const answer = (id: string, value: UpdateAnswer | null) =>
    setAnswers((prev) => {
      const next = { ...prev }
      if (value == null) delete next[id]
      else next[id] = value
      return next
    })

  const title = review ? `Update ${showName} to ${review.venueName} v${review.to}` : `Update ${showName}`
  return (
    <Sheet open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())} unsavedChanges={live.length > 0 && !updating}>
      <SheetContent className="flex flex-col sm:max-w-2xl">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <ArrowUpCircle className="size-4" />
            {title}
          </SheetTitle>
          <SheetDescription>
            {review
              ? `From v${review.from}${review.fromNote ? ` · “${review.fromNote}”` : ''} → v${review.to}${review.toNote ? ` · “${review.toNote}”` : ''}. Nothing changes until you press Update.`
              : 'Nothing changes until you press Update.'}
          </SheetDescription>
        </SheetHeader>
        <SheetBody>
          {(error || reviewError) && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertTitle>{error ? 'Not updated' : 'The review could not be read'}</AlertTitle>
              <AlertDescription>{formatError(error ?? reviewError)}</AlertDescription>
            </Alert>
          )}
          {review == null ? (
            <p className="text-sm text-muted-foreground">{isFetching ? 'Reading the update…' : ''}</p>
          ) : (
            <div className="grid gap-6 lg:grid-cols-2">
              <section className="space-y-2" aria-label="What changed in the hall">
                <EditorLabel>What changed in the hall</EditorLabel>
                <VenueChangeList
                  diff={{ from: review.from, to: review.to, output: review.output ?? [], drawing: review.drawing ?? [] }}
                  empty="The hall did not change."
                />
              </section>
              <section className="space-y-4" aria-label="What happens to this show">
                <EditorLabel>What happens to this show</EditorLabel>

                <div className="rounded-md border bg-card px-2.5 py-1.5 text-sm">
                  <div className="flex items-center gap-2">
                    <Layers className="size-3.5 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 flex-1">
                      <strong className="tabular-nums">{review.carryOver?.length ?? 0}</strong> carry over unchanged
                      <span className="block text-xs text-muted-foreground">Overrides, hides and additions on records v{review.to} didn&apos;t touch.</span>
                    </span>
                    {(review.carryOver?.length ?? 0) > 0 && (
                      <Button variant="ghost" size="sm" className="h-7" onClick={() => setShowCarried((s) => !s)}>
                        {showCarried ? 'Hide' : 'Show'}
                      </Button>
                    )}
                  </div>
                  {showCarried && (
                    <ul className="mt-1.5 space-y-0.5 text-xs text-muted-foreground">
                      {(review.carryOver ?? []).map((item, i) => <li key={`${item.kind}-${item.recordUuid ?? i}`}>{item.detail}</li>)}
                    </ul>
                  )}
                </div>

                {blockers.length > 0 && (
                  <Group label="Can't be kept" count={blockers.length} tone="red" icon={<XCircle className="size-3.5 text-red-600 dark:text-red-400" />}>
                    {blockers.map((b, i) => <ItemRow key={`${b.kind}-${i}`} sign="!" item={b} tone="red" />)}
                  </Group>
                )}

                {decisions.length > 0 && (
                  <Group label="Needs a decision" count={decisions.length} tone="red" icon={<AlertTriangle className="size-3.5 text-red-600 dark:text-red-400" />}>
                    {decisions.map((d) => (
                      <DecisionRow key={d.id} decision={d} chosen={answers[d.id]} onChoose={(a) => answer(d.id, a)} />
                    ))}
                  </Group>
                )}

                {(review.drift?.length ?? 0) > 0 && (
                  <Group label="Kept — the venue changed underneath" count={review.drift!.length} tone="amber">
                    {review.drift!.map((d) => (
                      <DriftRow
                        key={d.id}
                        drift={d}
                        to={review.to}
                        taken={answers[d.id] === 'TAKE_VENUES'}
                        onToggle={() => answer(d.id, answers[d.id] === 'TAKE_VENUES' ? null : 'TAKE_VENUES')}
                      />
                    ))}
                  </Group>
                )}

                {(review.dropped?.length ?? 0) > 0 && (
                  <Group label="Dropped" count={review.dropped!.length} icon={<CircleSlash className="size-3.5 text-muted-foreground" />}>
                    {review.dropped!.map((item, i) => <ItemRow key={`${item.kind}-${i}`} sign="−" item={item} />)}
                  </Group>
                )}

                {(review.deadReferences?.length ?? 0) > 0 && (
                  <Group label="Will point at nothing" count={review.deadReferences!.length} icon={<HelpCircle className="size-3.5 text-muted-foreground" />}>
                    {review.deadReferences!.map((ref) => (
                      <ItemRow key={`${ref.kind}-${ref.key}`} sign="?" item={{ kind: ref.kind, name: ref.key, detail: ref.detail }} />
                    ))}
                  </Group>
                )}
              </section>
            </div>
          )}
        </SheetBody>
        <SheetFooter className="flex-row items-center justify-between gap-2">
          <p className="hidden min-w-0 text-xs text-muted-foreground sm:block">
            {review
              ? blockers.length > 0
                ? 'Change the show first: a clash cannot be kept.'
                : undecided.length > 0
                  ? `${undecided.length} clash${undecided.length === 1 ? '' : 'es'} still need${undecided.length === 1 ? 's' : ''} a decision.`
                  : `The update replays this show's layer onto v${review.to}. Output returns when it completes.`
              : null}
          </p>
          <div className="flex shrink-0 gap-2">
            <SheetClose asChild>
              <Button variant="outline" disabled={updating}>{review ? `Stay on v${review.from}` : 'Cancel'}</Button>
            </SheetClose>
            <Button onClick={handleUpdate} disabled={!canUpdate}>
              {updating ? 'Updating…' : review ? `Update to v${review.to}` : 'Update'}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function Group({
  label,
  count,
  icon,
  tone,
  children,
}: {
  label: string
  count: number
  icon?: ReactNode
  tone?: 'red' | 'amber'
  children: ReactNode
}) {
  return (
    <div className="space-y-1.5" role="group" aria-label={label}>
      <div className="flex items-center gap-1.5">
        {icon}
        <EditorLabel
          className={cn(tone === 'red' && 'text-red-700 dark:text-red-400', tone === 'amber' && 'text-amber-700 dark:text-amber-400')}
        >
          {label}
        </EditorLabel>
        <span className="rounded bg-muted px-1.5 text-[10px] font-semibold tabular-nums">{count}</span>
      </div>
      <ul className="space-y-1">{children}</ul>
    </div>
  )
}

function ItemRow({ sign, item, tone }: { sign: string; item: UpdateItem; tone?: 'red' }) {
  return (
    <li className={cn('flex items-start gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm', tone === 'red' && 'border-red-500/40')}>
      <span aria-hidden className={cn('w-3 shrink-0 text-center font-mono font-bold', tone === 'red' ? 'text-red-600 dark:text-red-400' : 'text-muted-foreground')}>
        {sign}
      </span>
      <span className="min-w-0">{item.detail}</span>
    </li>
  )
}

function DecisionRow({
  decision,
  chosen,
  onChoose,
}: {
  decision: UpdateDecision
  chosen: UpdateAnswer | undefined
  onChoose: (answer: UpdateAnswer) => void
}) {
  return (
    <li className="space-y-1.5 rounded-md border border-red-500/40 bg-card px-2.5 py-2 text-sm">
      <div className="flex items-start gap-2">
        <span aria-hidden className="w-3 shrink-0 text-center font-mono font-bold text-red-600 dark:text-red-400">!</span>
        <span className="min-w-0">{decision.message}</span>
      </div>
      <div className="flex flex-wrap gap-1.5 pl-5" role="radiogroup" aria-label={`Answer: ${decision.mine.name}`}>
        {decision.answers.map((a) => (
          <Button
            key={a}
            role="radio"
            aria-checked={chosen === a}
            size="sm"
            variant={chosen === a ? 'default' : 'outline'}
            className="h-7"
            onClick={() => onChoose(a)}
          >
            {a === 'READDRESS_MINE' && decision.suggestedAddress ? `${ANSWER_LABELS[a]} → ${decision.suggestedAddress}` : ANSWER_LABELS[a]}
          </Button>
        ))}
      </div>
    </li>
  )
}

/** A short line for a facet's value: a gel's code, an address, else the facet's name. */
function facetValue(drift: UpdateDrift, value: Record<string, unknown> | null | undefined): string | null {
  if (value == null) return null
  if (drift.facet === 'MEDIA' && typeof value.gelCode === 'string') return value.gelCode
  if (drift.facet === 'ADDRESS' && typeof value.startChannel === 'number') return `ch ${value.startChannel}`
  return null
}

function DriftRow({ drift, to, taken, onToggle }: { drift: UpdateDrift; to: number; taken: boolean; onToggle: () => void }) {
  const facet = FACET_LABELS[drift.facet]
  const theirs = facetValue(drift, drift.venue)
  const mine = facetValue(drift, drift.value)
  return (
    <li className="flex items-start gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm">
      <span aria-hidden className="w-3 shrink-0 text-center font-mono font-bold text-amber-600 dark:text-amber-400">~</span>
      <span className="min-w-0 flex-1">
        <span className="block">{drift.patchName ?? drift.patchKey ?? 'A venue fixture'} · {facet}</span>
        <span className="block text-xs text-muted-foreground">
          {taken
            ? `v${to}'s ${facet.toLowerCase()}${theirs ? ` (${theirs})` : ''} will replace yours`
            : `v${to} changed it${theirs ? ` (${theirs})` : ''}; yours${mine ? ` (${mine})` : ''} is kept`}
        </span>
      </span>
      <Button size="sm" variant={taken ? 'default' : 'outline'} className="h-7 shrink-0" aria-pressed={taken} onClick={onToggle}>
        Take venue&apos;s
      </Button>
    </li>
  )
}
