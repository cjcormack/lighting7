import { useState } from 'react'
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
import { FACET_LABELS } from '@/lib/venue'
import { useUpdateVenueMutation, useVenueUpdateReviewQuery } from '@/store/venueHold'
import type { UpdateAnswer, UpdateDrift, UpdateOrder } from '@/api/venueApi'
import { VenueChangeList } from './VenueChangeList'
import { facetValueOf, OverlapDecisionRow, ReviewGroup as Group, ReviewItemRow } from './VenueReviewParts'

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
 * draws it; so is the show's order of a venue group the venue reordered (group-order plan D12). A
 * refusal is drawn here (`updateVenue` is in `SILENT_ENDPOINTS`) and the answers are kept for a retry.
 * A sheet rather than the board's centred modal, because the decisions are a form (CLAUDE.md §Sheets
 * vs Dialogs).
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
  const kept = [...(review?.drift ?? []), ...(review?.orders ?? [])]
  const asked = new Set([...decisions.map((d) => d.id), ...kept.map((d) => d.id)])
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
                    {blockers.map((b, i) => <ReviewItemRow key={`${b.kind}-${i}`} sign="!" detail={b.detail} tone="red" />)}
                  </Group>
                )}

                {decisions.length > 0 && (
                  <Group label="Needs a decision" count={decisions.length} tone="red" icon={<AlertTriangle className="size-3.5 text-red-600 dark:text-red-400" />}>
                    {decisions.map((d) => (
                      <OverlapDecisionRow key={d.id} decision={d} chosen={answers[d.id]} onChoose={(a) => answer(d.id, a)} />
                    ))}
                  </Group>
                )}

                {kept.length > 0 && (
                  <Group label="Kept — the venue changed underneath" count={kept.length} tone="amber">
                    {(review.drift ?? []).map((d) => (
                      <DriftRow
                        key={d.id}
                        drift={d}
                        to={review.to}
                        taken={answers[d.id] === 'TAKE_VENUES'}
                        onToggle={() => answer(d.id, answers[d.id] === 'TAKE_VENUES' ? null : 'TAKE_VENUES')}
                      />
                    ))}
                    {(review.orders ?? []).map((o) => (
                      <OrderRow
                        key={o.id}
                        order={o}
                        to={review.to}
                        taken={answers[o.id] === 'TAKE_VENUES'}
                        onToggle={() => answer(o.id, answers[o.id] === 'TAKE_VENUES' ? null : 'TAKE_VENUES')}
                      />
                    ))}
                  </Group>
                )}

                {(review.dropped?.length ?? 0) > 0 && (
                  <Group label="Dropped" count={review.dropped!.length} icon={<CircleSlash className="size-3.5 text-muted-foreground" />}>
                    {review.dropped!.map((item, i) => <ReviewItemRow key={`${item.kind}-${i}`} sign="−" detail={item.detail} />)}
                  </Group>
                )}

                {(review.deadReferences?.length ?? 0) > 0 && (
                  <Group label="Will point at nothing" count={review.deadReferences!.length} icon={<HelpCircle className="size-3.5 text-muted-foreground" />}>
                    {review.deadReferences!.map((ref) => (
                      <ReviewItemRow key={`${ref.kind}-${ref.key}`} sign="?" detail={ref.detail} />
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

function DriftRow({ drift, to, taken, onToggle }: { drift: UpdateDrift; to: number; taken: boolean; onToggle: () => void }) {
  const facet = FACET_LABELS[drift.facet]
  const theirs = facetValueOf(drift.facet, drift.venue)
  const mine = facetValueOf(drift.facet, drift.value)
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

/**
 * Both orders by key, each from the first place they differ so the change is in view, cut short past
 * [shown] so a big group stays one line.
 */
function orderLines(mine: readonly string[], venue: readonly string[], shown = 6): [string, string] {
  let from = 0
  while (from < mine.length && from < venue.length && mine[from] === venue[from]) from++
  if (from >= Math.max(mine.length, venue.length)) from = 0
  const line = (keys: readonly string[]) => {
    const rest = keys.length - from - shown
    return `${from > 0 ? `from ${from + 1}: ` : ''}${keys.slice(from, from + shown).join(', ')}${rest > 0 ? ` +${rest}` : ''}`
  }
  return [line(mine), line(venue)]
}

function OrderRow({ order, to, taken, onToggle }: { order: UpdateOrder; to: number; taken: boolean; onToggle: () => void }) {
  const [mine, venue] = orderLines(order.mine, order.venue)
  return (
    <li className="flex items-start gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm">
      <span aria-hidden className="w-3 shrink-0 text-center font-mono font-bold text-amber-600 dark:text-amber-400">~</span>
      <span className="min-w-0 flex-1">
        <span className="block">{order.groupName} · Order</span>
        <span className="block text-xs text-muted-foreground">
          {taken
            ? `v${to}'s order (${venue}) will replace yours`
            : `v${to} reordered it (${venue}); yours (${mine}) is kept`}
        </span>
      </span>
      <Button size="sm" variant={taken ? 'default' : 'outline'} className="h-7 shrink-0" aria-pressed={taken} onClick={onToggle}>
        Take venue&apos;s
      </Button>
    </li>
  )
}
