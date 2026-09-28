import { memo } from 'react'
import { Anchor, ChevronRight, Pencil, TriangleAlert, X } from 'lucide-react'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { positionLabel } from '@/lib/promptBook/geometry'
import { InlineEditField } from '@/components/InlineEditField'
import { TruncateStart } from '@/components/TruncateStart'
import { cueNumberCellWidth } from '@/lib/cueNumber'
import { CueCardBody, type CueCardKind, type ExpansionMode } from '@/components/runner/mobile/CueCardBody'
import { useCueFade } from '@/hooks/useCueFade'
import type { CueAnchorDto } from '@/api/promptBooksApi'
import type { CueStackCueEntry } from '@/api/cueStacksApi'
import type { DesyncWarning, FlatCue } from '@/lib/promptBook/desync'
import type { CueRunStatus } from './AnchorOverlay'

interface PromptBookCueCardProps {
  cue: FlatCue
  status: CueRunStatus
  anchor: CueAnchorDto | undefined
  /** Unanchored cues only: the neighbour the book borrows, e.g. "follows Q12". */
  anchorHint: string | null
  /** The full stack entry (palette/notes/auto), for the expanded card. */
  cueEntry: CueStackCueEntry | undefined
  projectId: number
  warnings: DesyncWarning[]
  locked: boolean
  /** This cue is armed for click-to-place on the script. */
  placing: boolean
  expanded: boolean
  /** Stage / Details mode for this card, owned by the page so it can persist across
   *  cue changes (live cue carries the last-used view). null = neither selected. */
  mode: ExpansionMode | null
  onModeChange: (cueId: number, status: CueRunStatus, mode: ExpansionMode | null) => void
  /**
   * The live stack id, or null when this cue's own stack isn't it — gates this card's own
   * `useCueFade` subscription, which drives the amber fade bar/badge on the live (cur) card.
   */
  fadeStackId: number | null
  /** Show active + editable — enables the "Set next" affordance. */
  canSetNext: boolean
  /** Front-matter page count, offsets the cue's page label to the script's numbering. */
  coverPages: number
  /**
   * These take the row's own id/cue rather than being pre-bound, so `CueStackPanel` can pass
   * the page's own stable (`useCallback`'d) handlers straight through instead of allocating a
   * fresh per-row closure on every render — which is what makes `memo` below hold.
   */
  onCueClick: (cue: FlatCue) => void
  onToggleExpanded: (cueId: number) => void
  onSetStandby: (cueId: number) => void
  onRemoveAnchor: (cueId: number) => void
  onEditCue: (cueId: number) => void
  /** Rename the cue. Only wired up while the book is unlocked. */
  onRenameCue: (cueId: number, name: string) => void
  /** Set (or clear, with null) the cue number. Only wired up while unlocked. */
  onRenumberCue: (cueId: number, cueNumber: string | null) => void
  /** Set (or clear, with null) the cue's note. Only wired up while unlocked. */
  onRenoteCue: (cueId: number, notes: string | null) => void
}

const STATUS_KIND: Record<CueRunStatus, CueCardKind> = {
  live: 'cur',
  next: 'nxt',
  standby: 'other',
  done: 'other',
}

/**
 * One cue in the Prompt Book rail. Collapsed it is a compact status row (the rail's
 * long-standing look); expanded it is the shared Run card (`CueCardBody`) — green live,
 * blue next, blue "other". The primary click always scrolls the book to the cue; a small
 * "Set next" arms it as the next GO, and the anchor place/remove + desync warning
 * affordances ride along. There is no "Change" button.
 *
 * Having no anchor is NOT an error — a pre-show state or an auto-followed cue reasonably
 * has no line to point at. While locked the row says nothing about it beyond slightly
 * dimmer text; the "place anchor" affordance only appears while the book is unlocked,
 * and the expanded card states it in words.
 *
 * Memoized: the rail can hold hundreds of these, and every one used to re-render on every
 * page render (a fade frame among them) for ten freshly-allocated per-row callbacks. The
 * callbacks are now the page's own stable handlers (see the prop doc above), so this bails
 * out except for the row whose own props actually changed.
 */
export const PromptBookCueCard = memo(function PromptBookCueCard({
  cue,
  status,
  anchor,
  anchorHint,
  cueEntry,
  projectId,
  warnings,
  locked,
  placing,
  expanded,
  mode,
  onModeChange,
  fadeStackId,
  canSetNext,
  coverPages,
  onCueClick,
  onToggleExpanded,
  onSetStandby,
  onRemoveAnchor,
  onEditCue,
  onRenameCue,
  onRenumberCue,
  onRenoteCue,
}: PromptBookCueCardProps) {
  const anchored = anchor != null
  const showSetNext = canSetNext && status !== 'live' && status !== 'next'

  // The live card's own fade — read here rather than drilled from the page, so a fade frame
  // re-renders only this one card instead of every card in the whole show (see `useCueFade`).
  const { fadeProgress, fadeRemainMs } = useCueFade(fadeStackId, cue.cueId, cue.fadeMs)

  // Cue identity is editable exactly while the book is unlocked — the same gate as anchors
  // and annotations. `cueEntry` carries the raw cueNumber (FlatCue only has the folded label).
  const editable = !locked && cueEntry != null

  const warningTriangle =
    warnings.length > 0 ? (
      <Tooltip>
        <TooltipTrigger asChild>
          <TriangleAlert className="size-3.5 shrink-0 text-red-500" />
        </TooltipTrigger>
        <TooltipContent side="left" className="max-w-64">
          {warnings.map((w, i) => (
            <p key={i}>{w.message}</p>
          ))}
        </TooltipContent>
      </Tooltip>
    ) : null

  // Unanchored: nothing VISIBLE while locked (run mode has no opinion about it) — the row's
  // dimmer text is the whole signal, so the state is spelled out for screen readers, which
  // can't see opacity. Unlocked it becomes an editing affordance, muted until the row is
  // hovered, so a mostly unanchored rail doesn't read as a wall of warnings.
  const anchorAffordance = !anchored ? (
    locked ? (
      <span className="sr-only">no anchor</span>
    ) : (
      <span
        className={cn(
          'shrink-0 rounded border px-1.5 py-0.5 text-[10px] font-bold',
          placing
            ? 'border-amber-500 text-amber-500'
            : 'border-transparent text-muted-foreground/50 group-hover:border-amber-500/60 group-hover:text-amber-500',
        )}
      >
        {placing ? 'placing…' : 'place anchor'}
      </span>
    )
  ) : (
    <span className="inline-flex shrink-0 items-center gap-1">
      <Anchor className="size-3 text-muted-foreground/50" />
      {!locked && (
        <Tooltip>
          <TooltipTrigger asChild>
            <span
              role="button"
              tabIndex={0}
              onClick={(e) => {
                e.stopPropagation()
                onRemoveAnchor(cue.cueId)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.stopPropagation()
                  onRemoveAnchor(cue.cueId)
                }
              }}
              aria-label={`Remove anchor for ${cue.label}`}
              className="rounded p-0.5 text-muted-foreground hover:bg-red-500/15 hover:text-red-500"
            >
              <X className="size-3" />
            </span>
          </TooltipTrigger>
          <TooltipContent side="left">Remove anchor</TooltipContent>
        </Tooltip>
      )}
    </span>
  )

  const chevron = (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        onToggleExpanded(cue.cueId)
      }}
      aria-label={expanded ? `Collapse ${cue.label}` : `Expand ${cue.label}`}
      aria-expanded={expanded}
      className="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
    >
      <ChevronRight className={cn('size-4 transition-transform', expanded && 'rotate-90')} />
    </button>
  )

  const setNextButton = showSetNext ? (
    <span
      role="button"
      tabIndex={0}
      onClick={(e) => {
        e.stopPropagation()
        onSetStandby(cue.cueId)
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.stopPropagation()
          onSetStandby(cue.cueId)
        }
      }}
      aria-label={`Set ${cue.label} as the next cue`}
      className="shrink-0 rounded border border-sky-600/50 px-1.5 py-0.5 text-[10px] font-semibold text-sky-400 hover:bg-sky-500/15"
    >
      Set next
    </span>
  ) : null

  if (expanded) {
    return (
      <div className="my-1">
        <CueCardBody
          kind={STATUS_KIND[status]}
          cue={cueEntry ?? null}
          projectId={projectId}
          mode={mode}
          onModeChange={(m) => onModeChange(cue.cueId, status, m)}
          location={
            anchored
              ? positionLabel(anchor.region, coverPages)
              : anchorHint
                ? `No anchor · ${anchorHint}`
                : 'No anchor'
          }
          headerLabel={cue.label}
          fadeProgress={fadeProgress}
          fadeRemainMs={fadeRemainMs}
          onBodyClick={() => onCueClick(cue)}
          onCueNumberCommit={editable ? (next) => onRenumberCue(cue.cueId, next) : undefined}
          onCueNameCommit={editable ? (next) => onRenameCue(cue.cueId, next) : undefined}
          onNotesCommit={editable ? (next) => onRenoteCue(cue.cueId, next) : undefined}
          headerTrailing={
            <>
              {warningTriangle}
              {chevron}
            </>
          }
          footer={
            <div className="flex items-center gap-2 px-3 pt-0.5 pb-3 text-xs">
              {anchorAffordance}
              <span className="flex-1" />
              {setNextButton}
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation()
                  onEditCue(cue.cueId)
                }}
                className="inline-flex items-center gap-1 font-medium text-sky-400 hover:underline"
              >
                <Pencil className="size-3" />
                Edit cue
              </button>
            </div>
          }
        />
      </div>
    )
  }

  // ── Collapsed row ──
  const isLive = status === 'live'
  const isNext = status === 'next'
  const isDone = status === 'done'
  // The whole locked-mode signal for an unanchored cue: one notch quieter than an
  // anchored standby row. Live/next/done carry their own strong tier, so leave those be.
  const dim = !anchored && !isLive && !isNext && !isDone
  // Sized to the longest number in the rail (`--cue-num-chars`, published by CueStackPanel) so
  // "QS1-3.2.10" shows whole where the old fixed 4rem cell clipped it — but still one width for
  // every row, so each name starts at the same x. Overflow is clipped at the START
  // (`TruncateStart`): the tail of a cue number is what tells "S1-3.1" from "S1-3.2".
  const labelClass = cn(
    'shrink-0 text-[13px] font-bold',
    isLive
      ? 'text-emerald-300'
      : isNext
        ? 'text-sky-300'
        : isDone
          ? 'text-muted-foreground/60'
          : dim
            ? 'text-foreground/50'
            : 'text-foreground',
  )
  // px-0 on the editable variant, so no field padding to allow for — just a little slack.
  const labelStyle = cueNumberCellWidth('0.25rem')
  const nameClass = cn(
    'truncate text-xs',
    isLive
      ? 'text-emerald-100/80'
      : isNext
        ? 'text-sky-200/70'
        : isDone
          ? 'text-muted-foreground/50'
          : dim
            ? 'text-muted-foreground/60'
            : 'text-muted-foreground',
  )
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onCueClick(cue)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') onCueClick(cue)
      }}
      className={cn(
        'group my-0.5 flex w-full items-center gap-2.5 rounded-md border px-2.5 py-2 text-left',
        isLive
          ? 'border-emerald-600/50 bg-emerald-950/20'
          : isNext
            ? 'border-sky-600/60 bg-sky-950/30'
            : 'border-transparent hover:bg-muted/40',
        placing && 'border-dashed border-amber-500 bg-amber-400/5',
      )}
    >
      {isLive ? (
        <span
          className="size-2 shrink-0 rounded-full bg-emerald-400 shadow-[0_0_6px_#34d399]"
          style={{ animation: 'r-fade-pulse 1.6s ease-in-out infinite' }}
        />
      ) : isNext ? (
        <span className="shrink-0 rounded border border-sky-700 bg-sky-500/20 px-1 py-px font-mono text-[9px] font-bold tracking-wide text-sky-300">
          NEXT
        </span>
      ) : (
        // The amber dot is the standby colour of this cue's band on the script — an
        // unanchored cue has no band, so it gets a blank (but still space-holding) slot
        // rather than a dot pointing at nothing.
        <span
          className={cn(
            'size-2 shrink-0 rounded-full',
            isDone ? 'bg-slate-500/50' : anchored ? 'bg-amber-500/70' : 'bg-transparent',
          )}
        />
      )}
      {editable ? (
        <>
          {/* The width lives on the wrapper so every row's name starts at the same x; the field
              inside wraps its own text, so only the number opens the editor. */}
          <span className={labelClass} style={labelStyle}>
            <InlineEditField
              value={cueEntry.cueNumber ?? ''}
              // A numberless cue's label folds in its name — keep showing that, so unlocking
              // doesn't change what the rail reads. Fall back to the sibling `cueEntry.name`
              // rather than `cue.label`: both come from the stack list, so clearing a number
              // shows the name at once instead of the stale "Q…" until `cueOrder` refetches.
              formatDisplay={(v) => <TruncateStart text={v ? `Q${v}` : cueEntry.name} />}
              onCommit={(next) => onRenumberCue(cue.cueId, next.trim() || null)}
              ariaLabel="cue number"
              placeholder="Q#"
              // Whatever the cell ends up showing, in full — it is the same cell that folds
              // in the name when there's no number, and either can be clipped.
              title={cueEntry.cueNumber ? `Q${cueEntry.cueNumber}` : cueEntry.name}
              // px-0: the label cell is tight enough that padding would cost the widest
              // number the room the read-only span gives it.
              className="max-w-full px-0"
            />
          </span>
          {/* The field wraps its text rather than filling the row, so the space beside a short
              cue name still belongs to the row (scroll the book / place the anchor). */}
          <span className="flex min-w-0 flex-1">
            <InlineEditField
              value={cueEntry.name}
              onCommit={(next) => {
                const trimmed = next.trim()
                if (trimmed === '') return false
                onRenameCue(cue.cueId, trimmed)
              }}
              ariaLabel="cue name"
              className={cn(nameClass, 'max-w-full')}
            />
          </span>
        </>
      ) : (
        <>
          <TruncateStart
            text={cue.label}
            title={cue.label}
            className={labelClass}
            style={labelStyle}
          />
          <span className={cn(nameClass, 'flex-1')}>{cue.name}</span>
        </>
      )}
      {setNextButton}
      {warningTriangle}
      {anchorAffordance}
      {chevron}
    </div>
  )
})
