import type { KeyboardEvent, ReactNode } from 'react'
import { Anchor } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useProjectCueQuery } from '@/store/cues'
import { MiniStage } from '@/components/cues/MiniStage'
import { CueDetailContent } from '@/components/cues/CueDetailContent'
import { collectCueTargets } from '@/components/cues/targetUtils'
import { InlineEditField } from '@/components/InlineEditField'
import { TruncateStart } from '@/components/TruncateStart'
import { formatFadeText } from '@/lib/cueUtils'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import type { CueStackCueEntry } from '@/api/cueStacksApi'

export type ExpansionMode = 'stage' | 'details'
/** cur = live (green), nxt = the armed next (blue + "Up next"), other = any other cue (blue, no label). */
export type CueCardKind = 'cur' | 'nxt' | 'other'

interface CueCardBodyProps {
  kind: CueCardKind
  cue: CueStackCueEntry | null
  projectId: number
  /** Which detail body is open; null = neither (toggle bar dimmed). */
  mode: ExpansionMode | null
  /** Toggle a detail body. Receives the next mode, or null when collapsing the open one. */
  onModeChange: (mode: ExpansionMode | null) => void
  /** Reading position, e.g. "top of p. 9". Rendered with an Anchor icon when set. */
  location?: string | null
  /** Header title for the 'other' kind (which has no fixed state word) — the cue label. */
  headerLabel?: string
  /** Right-aligned header content (counter/Change on Run; chevron/controls in the book). Hidden while fading. */
  headerTrailing?: ReactNode
  /** Extra content pinned to the foot of the card (book: Edit cue / Set next / anchor controls). */
  footer?: ReactNode
  /** When set, the Q/name/position area becomes a click target (book: scroll to the cue). */
  onBodyClick?: () => void
  /** cur-only: 0..1 fade-in progress; drives the amber fade bar + FADING badge. */
  fadeProgress?: number | null
  fadeRemainMs?: number | null
  /**
   * When set, the hero Q number becomes inline-editable (unlocked Prompt Book). Receives
   * the trimmed text, or null when cleared. Omit for a read-only card (Run).
   */
  onCueNumberCommit?: (next: string | null) => void
  /** When set, the cue name becomes inline-editable. Rejects an empty name. */
  onCueNameCommit?: (next: string) => void
  /**
   * When set, the note becomes inline-editable and a cue with none gets an "Add note"
   * affordance. Receives the trimmed text, or null when cleared. Omit for a read-only card.
   */
  onNotesCommit?: (next: string | null) => void
}

/**
 * Shared presentational cue card — the Run redesign's card visuals, reused by the
 * Run mobile view (`RunMobileCueCard`) and the Prompt Book rail (`PromptBookCueCard`).
 * Green "Now playing" (cur), blue "Up next" (nxt), blue unlabelled (other): hero Q +
 * name, an optional position line, a Stage / Details toggle, meta strip,
 * notes, and slots for host-specific chrome. The parent owns the expansion state.
 *
 * `[container-type:inline-size]` scopes the hero Q's `16cqw` to the card width, so it
 * sizes correctly in the narrow Prompt Book rail as well as the full-width mobile card.
 */
export function CueCardBody({
  kind,
  cue,
  projectId,
  mode,
  onModeChange,
  location,
  headerLabel,
  headerTrailing,
  footer,
  onBodyClick,
  fadeProgress,
  fadeRemainMs,
  onCueNumberCommit,
  onCueNameCommit,
  onNotesCommit,
}: CueCardBodyProps) {
  const isCur = kind === 'cur'
  const isNxt = kind === 'nxt'
  const isFading = isCur && fadeProgress != null && fadeProgress < 1
  const label = isCur ? 'Now playing' : isNxt ? 'Up next' : (headerLabel ?? '')

  // Editable identity (unlocked Prompt Book). The fields inherit the surrounding type
  // styles — including the hero Q's container-query font size — so the card looks the same
  // whether or not it is editable.
  // The hero Q is 96px at full size, so a long number has to be clipped — at the START, keeping
  // the tail that identifies the cue. It stays `w-full` because the hero is centred: a
  // shrink-wrapped box would have nothing to centre within. The smaller header renders its number
  // in an `auto` grid column instead, which shows it whole — nothing to clip there.
  const heroQ = (text: string) =>
    isCur ? <TruncateStart text={text} title={text} className="w-full" /> : text

  const cueNumberNode =
    cue == null ? null : onCueNumberCommit ? (
      <InlineEditField
        value={cue.cueNumber ?? ''}
        formatDisplay={(v) => heroQ(v ? `Q${v}` : '—')}
        onCommit={(next) => onCueNumberCommit(next.trim() || null)}
        ariaLabel="cue number"
        placeholder="Q#"
        className={cn(
          isCur ? 'w-full text-center' : 'max-w-full',
          cue.cueNumberAuto && AUTO_CUE_NUMBER_CLASS,
        )}
      />
    ) : cue.cueNumber ? (
      // `block` only for the hero, where TruncateStart needs a block box to measure. On the
      // smaller card the parent ellipsises with `truncate`, and a block child would give it no
      // inline content to ellipsise — the number would hard-clip mid-glyph instead.
      <span className={cn(isCur && 'block', cue.cueNumberAuto && AUTO_CUE_NUMBER_CLASS)}>
        {heroQ(`Q${cue.cueNumber}`)}
      </span>
    ) : (
      '—'
    )

  const cueNameNode =
    cue == null ? null : onCueNameCommit ? (
      <InlineEditField
        value={cue.name}
        onCommit={(next) => {
          const trimmed = next.trim()
          if (trimmed === '') return false
          onCueNameCommit(trimmed)
        }}
        ariaLabel="cue name"
        className={cn('max-w-full', isCur ? 'text-center' : 'truncate')}
      />
    ) : (
      cue.name
    )

  return (
    <section
      className={cn(
        'rounded-xl border overflow-hidden flex flex-col bg-card shrink-0 [container-type:inline-size]',
        isCur
          ? 'border-green-900 shadow-[0_0_0_1px_rgba(20,83,45,0.25)]'
          : isNxt
            ? 'border-blue-900/70'
            : 'border-border',
        isFading && 'border-amber-700/70 shadow-[0_0_0_1px_rgba(133,77,14,0.4),0_0_12px_rgba(251,191,36,0.18)_inset]',
      )}
    >
      {/* Header strip — clickable (with the body) to navigate when onBodyClick is set. */}
      <header
        onClick={onBodyClick}
        className={cn(
          'flex items-center gap-2 px-3 py-2 border-b font-mono text-[10px] font-bold uppercase tracking-[0.12em]',
          onBodyClick && 'cursor-pointer',
          isCur
            ? 'bg-green-950/40 text-green-400 border-green-900'
            : isNxt
              ? 'bg-blue-950/40 text-blue-400 border-blue-900/70'
              : 'bg-muted/40 text-muted-foreground border-border',
          isFading && 'bg-amber-950/40 text-amber-400 border-amber-900',
        )}
      >
        <span
          className={cn(
            'size-1.5 rounded-full bg-current',
            isCur && !isFading && 'shadow-[0_0_6px_currentColor]',
          )}
          style={
            isCur && !isFading
              ? { animation: 'r-fade-pulse 1.6s ease-in-out infinite' }
              : isFading
                ? { animation: 'r-fade-pulse 0.9s ease-in-out infinite' }
                : undefined
          }
        />
        {label && <span>{label}</span>}
        {(isFading || headerTrailing) && (
          <div className="ml-auto flex items-center gap-2">
            {isFading && (
              <span className="inline-flex items-center gap-1 rounded-full border border-amber-900 bg-amber-950/60 px-2 py-px text-[10px] tracking-[0.08em]">
                FADING · {((fadeRemainMs ?? 0) / 1000).toFixed(1)}s
              </span>
            )}
            {headerTrailing}
          </div>
        )}
      </header>

      {/* Fade progress bar — only on Current */}
      {isCur && isFading && (
        <div className="h-[3px] bg-amber-950/40 overflow-hidden">
          <div
            className="h-full bg-gradient-to-r from-amber-500 to-amber-400 shadow-[0_0_8px_rgba(251,191,36,0.6)]"
            style={{
              width: `${(fadeProgress! * 100).toFixed(2)}%`,
              transition: 'width 80ms linear',
            }}
          />
        </div>
      )}

      {!cue ? (
        <div className="px-4 py-7 text-center text-sm text-muted-foreground italic">
          {isCur ? 'No cue running. Press GO to start.' : 'End of stack.'}
        </div>
      ) : (
        <>
          {/* Q + name + reading position — an optional click target that
              scrolls the book to this cue (Prompt Book); inert otherwise (Run). */}
          <div
            className={cn(onBodyClick && 'cursor-pointer')}
            {...(onBodyClick
              ? {
                  role: 'button',
                  tabIndex: 0,
                  onClick: onBodyClick,
                  onKeyDown: (e: KeyboardEvent) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      onBodyClick()
                    }
                  },
                }
              : {})}
          >
            {isCur ? (
              <div className="px-3 pt-3 pb-2 text-center">
                <div
                  className="font-mono font-extrabold leading-[0.95] tracking-tight text-green-400"
                  style={{
                    fontSize: 'clamp(56px, 16cqw, 96px)',
                    textShadow: '0 0 24px rgba(74,222,128,0.3)',
                  }}
                >
                  {cueNumberNode}
                </div>
                <div className="mt-1 text-[15px] font-semibold text-foreground">
                  {cueNameNode}
                </div>
              </div>
            ) : (
              <div className="px-3 py-2 grid grid-cols-[auto_1fr] items-center gap-2.5">
                <span
                  className={cn(
                    // Capped so an outsized number can't starve the name beside it.
                    'max-w-[45%] truncate font-mono text-2xl font-extrabold',
                    isNxt ? 'text-blue-400' : 'text-foreground',
                  )}
                >
                  {cueNumberNode}
                </span>
                <span className="min-w-0 truncate text-[13px] font-semibold text-foreground">
                  {cueNameNode}
                </span>
              </div>
            )}

            {location && (
              <div className="flex items-center gap-1.5 px-3 pb-0.5 text-xs">
                <Anchor className="size-3 shrink-0 text-muted-foreground" />
                <span className="truncate text-muted-foreground italic">{location}</span>
              </div>
            )}
          </div>

          {/* Stage / Details toggle */}
          <div
            className={cn(
              'mx-3 mt-2 flex rounded-md border bg-muted/20 p-0.5',
              mode == null && 'opacity-65',
            )}
          >
            <ToggleButton
              active={mode === 'stage'}
              onClick={() => onModeChange(mode === 'stage' ? null : 'stage')}
              label="Stage"
              accent={isCur ? 'green' : isNxt ? 'blue' : 'neutral'}
            />
            <ToggleButton
              active={mode === 'details'}
              onClick={() => onModeChange(mode === 'details' ? null : 'details')}
              label="Details"
              accent={isCur ? 'green' : isNxt ? 'blue' : 'neutral'}
            />
          </div>

          {/* Expanded body */}
          {mode === 'stage' && (
            <div className="mx-3 mt-2.5">
              <MobileStage cueId={cue.id} projectId={projectId} compact={!isCur} />
            </div>
          )}
          {mode === 'details' && (
            <div className="mx-3 mt-2.5 space-y-3">
              <MobileDetails cueId={cue.id} projectId={projectId} />
            </div>
          )}

          {/* Compact meta strip */}
          <div className="px-3 pt-2.5 pb-3 flex flex-wrap items-baseline gap-x-2 gap-y-1 font-mono text-[11px] text-muted-foreground">
            <Cell label="Fade" value={formatFadeText(cue.fadeDurationMs, cue.fadeCurve)} />
            {cue.autoAdvance && (
              <>
                <span>·</span>
                <Cell label="Auto" value="On" />
              </>
            )}
          </div>

          {/* Notes — amber "Trigger" callout on the armed next, subtle italic otherwise.
              Editable hosts (the unlocked Prompt Book) get click-to-edit here, plus a placeholder
              on a cue with no note, so writing one never means leaving for the Show editor. */}
          {onNotesCommit && kind !== 'nxt' && (
            <div className="mx-3 mb-3">
              <InlineEditField
                value={cue.notes ?? ''}
                onCommit={(next) => onNotesCommit(next.trim() || null)}
                ariaLabel="cue notes"
                placeholder="Performance note…"
                multiline
                rows={3}
                formatDisplay={(v) =>
                  v || <span className="not-italic opacity-70">+ Add note</span>
                }
                className="w-full rounded-md border bg-muted/20 px-2.5 py-2 text-[12px] italic text-muted-foreground"
              />
            </div>
          )}
          {!onNotesCommit && cue.notes && kind !== 'nxt' && (
            <div className="mx-3 mb-3 rounded-md border bg-muted/20 px-2.5 py-2 text-[12px] italic text-muted-foreground whitespace-pre-wrap">
              {cue.notes}
            </div>
          )}
          {(cue.notes || onNotesCommit) && kind === 'nxt' && (
            <div className="mx-3 mb-3 rounded-md border border-amber-700/40 bg-amber-950/30 px-2.5 py-2 text-[12px] text-amber-200 grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
              <span className="font-mono text-[9.5px] font-bold uppercase tracking-[0.12em] text-amber-400">
                Trigger
              </span>
              <span />
              <span />
              {/* The armed cue's note is the one the operator is about to act on, so it has to be
                  as editable as the rest — a late change can't mean a trip to the Show view. */}
              {onNotesCommit ? (
                <InlineEditField
                  value={cue.notes ?? ''}
                  onCommit={(next) => onNotesCommit(next.trim() || null)}
                  ariaLabel="cue notes"
                  placeholder="Performance note…"
                  multiline
                  rows={3}
                  formatDisplay={(v) => v || <span className="opacity-70">+ Add note</span>}
                  className="w-full font-medium text-amber-200"
                />
              ) : (
                <span className="font-medium whitespace-pre-wrap">{cue.notes}</span>
              )}
            </div>
          )}

          {footer}
        </>
      )}
    </section>
  )
}

function ToggleButton({
  active,
  onClick,
  label,
  accent,
}: {
  active: boolean
  onClick: () => void
  label: string
  accent: 'green' | 'blue' | 'neutral'
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'flex-1 inline-flex items-center justify-center gap-1.5 rounded text-[11.5px] font-semibold py-1.5 transition-colors',
        active
          ? 'bg-card text-foreground shadow-[0_1px_0_rgba(255,255,255,0.04)_inset]'
          : 'text-muted-foreground',
        active && accent === 'green' && 'text-green-400',
        active && accent === 'blue' && 'text-blue-300',
      )}
    >
      {label}
    </button>
  )
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <span className="inline-flex items-baseline gap-1.5 min-w-0">
      <span className="text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground/80">
        {label}
      </span>
      <span className="text-[11.5px] text-foreground">{value}</span>
    </span>
  )
}

function MobileStage({
  cueId,
  projectId,
  compact,
}: {
  cueId: number
  projectId: number
  compact: boolean
}) {
  const { data } = useProjectCueQuery({ projectId, cueId })
  const targets = data ? collectCueTargets(data) : []
  return (
    <MiniStage
      projectId={projectId}
      targets={targets}
      heightClass={compact ? 'h-20' : 'h-24'}
    />
  )
}

function MobileDetails({ cueId, projectId }: { cueId: number; projectId: number }) {
  const { data } = useProjectCueQuery({ projectId, cueId })
  if (!data) {
    return <p className="text-xs text-muted-foreground italic">Loading…</p>
  }
  return <CueDetailContent cue={data} projectId={projectId} />
}
