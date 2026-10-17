import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { AlertTriangle, CircleSlash, HelpCircle, Layers, Link2, Loader2, MonitorSmartphone, XCircle } from 'lucide-react'
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
import {
  attachAnswersFor,
  attachRefusalOf,
  attachVenuesOf,
  attachVersionsOf,
  behindLineOf,
  decisionGroupsOf,
  defaultAttachVenue,
  hideDecisionOf,
  inheritedLineOf,
  kindNounOf,
  matchingLineOf,
  reachLineOf,
  staleAnswerIdOf,
  type AttachAnswer,
} from '@/lib/venueAttach'
import { useProjectListQuery } from '@/store/projects'
import { useAttachVenueMutation, useVenueAttachReviewQuery } from '@/store/venues'
import type { AttachDecision, AttachItem, AttachOverride, AttachReview, UpdateAnswer } from '@/api/venueApi'
import { facetValueOf, OverlapDecisionRow, ReviewDecisionRow, ReviewGroup, ReviewItemRow } from './VenueReviewParts'

/** How long after the last answer the review is read again: a run of presses is one dry run, each a whole attach rolled back. */
const REVIEW_DEBOUNCE_MS = 250

/**
 * *Attach to venue…* (venue-attach plan §5, D1–D4): a standalone show put onto a venue version. Pick the
 * venue — the one the show was detached from first — and the version; the review is the attach rolled
 * back on the desk, re-read with the answers so far after every answer, since answering one decision
 * can change what else it lists. What the show changed becomes overrides, hides and its own
 * additions; a differing identity keeps the show's unless answered *Take venue's*, which renames its
 * references. A blocker, or an address overlap left unanswered, holds *Attach* back. A refusal is drawn
 * here (`attachVenue` is in `SILENT_ENDPOINTS`) and the answers are kept for the retry; an attach that
 * lands closes on the show's home. Both roles.
 */
export function AttachVenueSheet({
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
  const navigate = useNavigate()
  const { data: projects } = useProjectListQuery(undefined, { skip: !open })
  const detachedFrom = projects?.find((p) => p.id === projectId)?.detachedFrom ?? null
  const venues = useMemo(() => attachVenuesOf(projects, detachedFrom), [projects, detachedFrom])

  const [venueId, setVenueId] = useState<number | null>(null)
  const [to, setTo] = useState<number | null>(null)
  const [answers, setAnswers] = useState<Record<string, AttachAnswer>>({})
  /** The answers the review was last asked about: [answers] a moment after the last press, so a run of presses is one dry run. */
  const [asked, setAsked] = useState<Record<string, AttachAnswer>>({})
  /** The desk said the origin version is not here: it is not offered again. */
  const [originGone, setOriginGone] = useState(false)
  const [attaching, setAttaching] = useState(false)
  /** The review the progress is drawn over, once *Attach* is pressed. */
  const pressed = useRef<AttachReview | null>(null)

  // The sheet opens on the origin venue at its installed version, once the list is here.
  const fallback = defaultAttachVenue(venues)
  if (open && venueId == null && fallback != null) {
    setVenueId(fallback.id)
    setTo(fallback.installed)
  }
  const venue = venues.find((v) => v.id === venueId) ?? null
  const versions = attachVersionsOf(venue, detachedFrom, !originGone)

  const decisions = attachAnswersFor(asked)
  const pending = JSON.stringify(attachAnswersFor(answers)) !== JSON.stringify(decisions)
  useEffect(() => {
    if (!pending) return
    const timer = setTimeout(() => setAsked(answers), REVIEW_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [answers, pending])
  const { data, error: reviewError, isFetching } = useVenueAttachReviewQuery(
    { projectId, venueId: venueId ?? 0, to: to ?? undefined, decisions },
    { skip: !open || venueId == null || to == null || attaching, refetchOnMountOrArgChange: true },
  )
  // The last review drawn is for this venue and version; another's is not shown while this one reads.
  const review = data != null && data.venueId === venueId && data.to === to ? data : undefined
  const [attach, { error: attachError, reset }] = useAttachVenueMutation()

  useEffect(() => {
    if (review?.origin?.status === 'MISSING') setOriginGone(true)
  }, [review?.origin])

  // Answering one decision can retire another, or take an answer off it: the desk then refuses that
  // answer by name, and it goes.
  const stale = staleAnswerIdOf(reviewError)
  useEffect(() => {
    if (stale == null) return
    const without = (prev: Record<string, AttachAnswer>) => {
      if (!(stale in prev)) return prev
      const next = { ...prev }
      delete next[stale]
      return next
    }
    setAnswers(without)
    setAsked(without)
  }, [stale])

  const close = () => {
    setVenueId(null)
    setTo(null)
    setAnswers({})
    setAsked({})
    setOriginGone(false)
    setAttaching(false)
    pressed.current = null
    reset()
    onOpenChange(false)
  }

  const pickVenue = (id: number) => {
    const next = venues.find((v) => v.id === id)
    if (next == null || next.installed == null || id === venueId) return
    setVenueId(id)
    setTo(next.installed)
    setAnswers({})
    setAsked({})
    reset()
  }
  const pickVersion = (n: number) => {
    if (n === to) return
    setTo(n)
    setAnswers({})
    setAsked({})
    reset()
  }
  const answer = (id: string, value: AttachAnswer | null) => {
    reset()
    setAnswers((prev) => {
      const next = { ...prev }
      if (value == null) delete next[id]
      else next[id] = value
      return next
    })
  }

  const refusal = attachRefusalOf(review, answers)
  // A review that could not be read with the answers given has not shown what they do.
  const canAttach = review != null && refusal == null && !reviewError && !pending && !isFetching && !attaching

  const handleAttach = async () => {
    if (review == null || venueId == null) return
    pressed.current = review
    setAttaching(true)
    try {
      await attach({ projectId, venueId, to: review.to, decisions: attachAnswersFor(answers, review) }).unwrap()
    } catch {
      setAttaching(false)
      return
    }
    close()
    navigate(`/projects/${projectId}`)
  }

  const shown = attaching ? pressed.current : review
  const target = shown ?? (venue && to != null ? { venueName: venue.name, to } : null)
  const title = target ? `Attach ${showName} to ${target.venueName} v${target.to}` : `Attach ${showName} to a venue`
  const matching = venue == null ? null : matchingLineOf(showName, detachedFrom, review)

  return (
    <Sheet
      open={open}
      // Held open while the attach runs: its answer, a refusal included, is drawn here and nowhere else.
      onOpenChange={(o) => (o ? onOpenChange(true) : attaching ? undefined : close())}
      unsavedChanges={Object.keys(answers).length > 0 && !attaching}
    >
      <SheetContent className="flex flex-col sm:max-w-2xl" data-attach-sheet="">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <Link2 className="size-4" />
            {title}
          </SheetTitle>
          <SheetDescription>
            Its rows that are the venue&apos;s records go, and what it changed is kept on top. Nothing changes until you
            press Attach.
          </SheetDescription>
        </SheetHeader>
        <SheetBody>
          {attaching && pressed.current ? (
            <div className="flex items-center gap-2 rounded-md border bg-card px-3 py-3 text-sm" role="status">
              <Loader2 className="size-4 animate-spin text-muted-foreground" />
              Attaching {showName} to {pressed.current.venueName} v{pressed.current.to}…
            </div>
          ) : (
            <>
              <PickSection
                venues={venues}
                venueId={venueId}
                onVenue={pickVenue}
                versions={versions}
                to={to}
                onVersion={pickVersion}
                showName={showName}
                matching={matching}
                loading={projects == null}
              />
              {(attachError || (reviewError && stale == null)) && (
                <Alert variant="destructive">
                  <XCircle className="size-4" />
                  <AlertTitle>{attachError ? 'Not attached' : 'The review could not be read'}</AlertTitle>
                  <AlertDescription>{formatError(attachError ?? reviewError)}</AlertDescription>
                </Alert>
              )}
              {venue == null ? null : review == null ? (
                <p className="text-sm text-muted-foreground">{isFetching ? 'Reading the attach…' : ''}</p>
              ) : (
                <Review review={review} showName={showName} answers={answers} onAnswer={answer} />
              )}
            </>
          )}
        </SheetBody>
        <SheetFooter className="flex-row flex-wrap items-center justify-between gap-2">
          <p className="min-w-0 text-xs text-muted-foreground" data-attach-refusal="">
            {attaching ? null : review ? (refusal ?? (pending || isFetching ? 'Reading the attach…' : null)) : null}
          </p>
          <div className="flex shrink-0 gap-2">
            <SheetClose asChild>
              <Button variant="outline" disabled={attaching}>
                Cancel
              </Button>
            </SheetClose>
            <Button onClick={handleAttach} disabled={!canAttach} title={refusal ?? undefined}>
              {attaching ? 'Attaching…' : review ? `Attach to ${review.venueName} v${review.to}` : 'Attach'}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function PickSection({
  venues,
  venueId,
  onVenue,
  versions,
  to,
  onVersion,
  showName,
  matching,
  loading,
}: {
  venues: ReturnType<typeof attachVenuesOf>
  venueId: number | null
  onVenue: (id: number) => void
  versions: ReturnType<typeof attachVersionsOf>
  to: number | null
  onVersion: (n: number) => void
  showName: string
  matching: string | null
  loading: boolean
}) {
  return (
    <section className="space-y-3" aria-label="Venue and version">
      <div className="space-y-1.5">
        <EditorLabel>Venue</EditorLabel>
        {venues.length === 0 ? (
          <p className="text-sm text-muted-foreground">{loading ? 'Reading the venues…' : 'No venue on this desk.'}</p>
        ) : (
          <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Venue">
            {venues.map((v) => (
              <Button
                key={v.id}
                role="radio"
                aria-checked={v.id === venueId}
                variant={v.id === venueId ? 'default' : 'outline'}
                size="sm"
                className="h-auto min-h-7 flex-col items-start gap-0 py-1 text-left"
                disabled={v.installed == null}
                data-attach-venue={v.id}
                onClick={() => onVenue(v.id)}
              >
                <span>
                  {v.name}
                  {v.installed != null && ' '}
                  {v.installed != null && <span className="tabular-nums opacity-70">v{v.installed}</span>}
                </span>
                {v.origin && ' '}
                {v.origin && <span className="text-[10px] font-normal opacity-80">Detached from here</span>}
                {v.installed == null && ' '}
                {v.installed == null && <span className="text-[10px] font-normal opacity-80">Nothing published</span>}
              </Button>
            ))}
          </div>
        )}
      </div>
      {versions.length > 0 && (
        <div className="space-y-1.5">
          <EditorLabel>Version</EditorLabel>
          <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Version">
            {versions.map((v) => (
              <Button
                key={v.number}
                role="radio"
                aria-checked={v.number === to}
                variant={v.number === to ? 'default' : 'outline'}
                size="sm"
                className="h-7"
                data-attach-version={v.number}
                onClick={() => onVersion(v.number)}
              >
                v{v.number}{' '}
                <span className="font-normal opacity-80">
                  · {v.marks.map((m) => (m === 'installed' ? 'installed' : `the version ${showName} left`)).join(' · ')}
                </span>
              </Button>
            ))}
          </div>
        </div>
      )}
      {matching && (
        <p className="text-sm text-muted-foreground" data-attach-matching="">
          {matching}
        </p>
      )}
    </section>
  )
}

/**
 * A hidden record with why: a release says it all; a hide names the venue's record before its reason,
 * and one hidden by its own toggle is named alone, under the group that already says it is hidden.
 */
function hideLineOf(item: AttachItem): string {
  if (item.reason === 'RELEASE' || item.venue == null) return item.detail
  const key = item.venue.key && item.venue.key !== item.venue.name ? ` (${item.venue.key})` : ''
  const record = `The venue's ${kindNounOf(item.kind)} ${item.venue.name}${key}`
  return item.reason === 'ANSWERED' ? record : `${record} — ${item.detail}`
}

function Review({
  review,
  showName,
  answers,
  onAnswer,
}: {
  review: AttachReview
  showName: string
  answers: Readonly<Record<string, AttachAnswer>>
  onAnswer: (id: string, value: AttachAnswer | null) => void
}) {
  const { choices, drift, hide } = decisionGroupsOf(review.decisions)
  const output = review.output ?? []
  const drawing = review.drawing ?? []
  const overlaps = review.overlaps ?? []
  const blockers = review.blockers ?? []
  const overrides = (review.overrides ?? []).filter((o) => o.decisionId == null)
  const driftOverride = new Map((review.overrides ?? []).filter((o) => o.decisionId != null).map((o) => [o.decisionId!, o]))
  const incoming = review.incoming ?? []
  const hides = review.hides ?? []
  const yours = [...(review.kept ?? []), ...(review.additions ?? [])]
  const inherited = inheritedLineOf(review.inherited)
  const behind = behindLineOf(showName, review)
  const chosen = (d: AttachDecision) => answers[d.id]?.answer ?? d.answer

  const hideToggle = (item: AttachItem) => {
    // A hide the desk refused comes in whatever was answered; its line says why.
    if (item.reason === 'HIDE_REFUSED') return null
    const decision = hideDecisionOf(item, hide)
    if (decision == null) return null
    const hidden = chosen(decision) === 'HIDE_VENUES'
    return (
      <Button
        size="sm"
        variant={hidden ? 'default' : 'outline'}
        className="h-7 shrink-0"
        aria-pressed={hidden}
        onClick={() => onAnswer(decision.id, { answer: hidden ? 'TAKE_VENUES' : 'HIDE_VENUES' })}
      >
        Hide in this show
      </Button>
    )
  }

  return (
    <div className="space-y-5">
      {behind && (
        <Alert data-attach-behind={review.behind}>
          <AlertTriangle className="size-4" />
          <AlertDescription>{behind}</AlertDescription>
        </Alert>
      )}

      {blockers.length > 0 && (
        <ReviewGroup label="Can't be attached" count={blockers.length} tone="red" icon={<XCircle className="size-3.5 text-red-600 dark:text-red-400" />}>
          {blockers.map((b, i) => (
            <ReviewItemRow key={`${b.kind}-${i}`} sign="!" detail={b.detail} tone="red" />
          ))}
        </ReviewGroup>
      )}

      <section className="space-y-2" aria-label="Output">
        <EditorLabel>Output</EditorLabel>
        {output.length === 0 ? (
          <p className="text-sm text-muted-foreground">Attaching changes no output.</p>
        ) : (
          <ReviewGroup
            label="Changes output"
            count={output.length}
            icon={<AlertTriangle className="size-3.5 text-orange-600 dark:text-orange-400" />}
          >
            {output.map((item, i) => (
              <ReviewItemRow key={`o-${i}`} sign="~" detail={item.detail} />
            ))}
          </ReviewGroup>
        )}
        {drawing.length > 0 && (
          <ReviewGroup label="Drawing only" count={drawing.length} icon={<MonitorSmartphone className="size-3.5 text-muted-foreground" />}>
            {drawing.map((item, i) => (
              <ReviewItemRow key={`d-${i}`} sign="~" detail={item.detail} />
            ))}
          </ReviewGroup>
        )}
      </section>

      <section className="space-y-1.5" aria-label="From the venue">
        <EditorLabel>From the venue</EditorLabel>
        <div className="flex items-start gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm">
          <Layers className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
          <span data-attach-inherited="">
            {inherited ? `${inherited}: the venue's replace yours.` : 'Nothing the show holds is the venue’s already.'}
          </span>
        </div>
      </section>

      {(overrides.length > 0 || drift.length > 0) && (
        <ReviewGroup label="Overrides" count={overrides.length + drift.length}>
          {overrides.map((o, i) => (
            <ReviewItemRow key={`ov-${i}`} sign="≠" detail={<OverrideLine override={o} />} />
          ))}
          {drift.map((d) => (
            <ReviewDecisionRow
              key={d.id}
              tone="plain"
              message={d.message}
              label={`Answer: ${d.venue.name}`}
              answers={d.answers}
              chosen={chosen(d)}
              onChoose={(a) => onAnswer(d.id, { answer: a })}
            >
              {driftOverride.has(d.id) && (
                <span className="block text-xs text-muted-foreground">
                  <OverrideLine override={driftOverride.get(d.id)!} />
                </span>
              )}
            </ReviewDecisionRow>
          ))}
        </ReviewGroup>
      )}

      {(choices.length > 0 || overlaps.length > 0) && (
        <ReviewGroup label="Decisions" count={choices.length + overlaps.length}>
          {choices.map((d) => (
            <ReviewDecisionRow
              key={d.id}
              tone="plain"
              message={d.message}
              label={`Answer: ${d.show?.name ?? d.venue.name}`}
              answers={d.answers}
              chosen={chosen(d)}
              onChoose={(a) => onAnswer(d.id, { answer: a })}
            >
              {d.rename && (
                <span className="block text-xs text-muted-foreground" data-attach-reach={d.id}>
                  Take venue&apos;s {reachLineOf(d.rename)}.
                </span>
              )}
            </ReviewDecisionRow>
          ))}
          {overlaps.map((d) => (
            <OverlapDecisionRow
              key={d.id}
              decision={d}
              chosen={answers[d.id]?.answer}
              onChoose={(a: UpdateAnswer) => onAnswer(d.id, { answer: a })}
            />
          ))}
        </ReviewGroup>
      )}

      {incoming.length > 0 && (
        <ReviewGroup label="Comes in from the venue" count={incoming.length}>
          {incoming.map((item, i) => (
            <ReviewItemRow key={`in-${i}`} sign="+" detail={item.detail}>
              {hideToggle(item)}
            </ReviewItemRow>
          ))}
        </ReviewGroup>
      )}

      {hides.length > 0 && (
        <ReviewGroup label="Hidden in this show" count={hides.length} icon={<CircleSlash className="size-3.5 text-muted-foreground" />}>
          {hides.map((item, i) => (
            <ReviewItemRow key={`h-${i}`} sign="−" detail={hideLineOf(item)}>
              {item.reason === 'ANSWERED' && hideToggle(item)}
            </ReviewItemRow>
          ))}
        </ReviewGroup>
      )}

      {yours.length > 0 && (
        <ReviewGroup label="Yours" count={yours.length}>
          {yours.map((item, i) => (
            <ReviewItemRow key={`y-${i}`} sign="·" detail={item.detail} />
          ))}
        </ReviewGroup>
      )}

      {(review.deadReferences?.length ?? 0) > 0 && (
        <ReviewGroup label="Will point at nothing" count={review.deadReferences!.length} icon={<HelpCircle className="size-3.5 text-muted-foreground" />}>
          {review.deadReferences!.map((ref) => (
            <ReviewItemRow key={`${ref.kind}-${ref.key}`} sign="?" detail={ref.detail} />
          ))}
        </ReviewGroup>
      )}

      {(review.notes?.length ?? 0) > 0 && (
        <section className="space-y-1" aria-label="Notes">
          <EditorLabel>Before you attach</EditorLabel>
          {review.notes!.map((note, i) => (
            <p key={i} className="text-sm text-muted-foreground">
              {note}
            </p>
          ))}
        </section>
      )}
    </div>
  )
}

/** One override: the venue's patch, the facet, and the two values where a short line says them. */
function OverrideLine({ override }: { override: AttachOverride }) {
  const facet = FACET_LABELS[override.facet]
  const mine = facetValueOf(override.facet, override.value)
  const theirs = facetValueOf(override.facet, override.venueValue)
  const values = mine || theirs ? `: yours${mine ? ` ${mine}` : ''}, the venue's${theirs ? ` ${theirs}` : ''}` : ''
  return (
    <>
      {override.venue.name} · {facet}
      {values}
      {override.dormant && <span className="text-muted-foreground"> — names a universe or rigging the rig lacks, so it does nothing</span>}
    </>
  )
}
