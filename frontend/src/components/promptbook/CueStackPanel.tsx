import { useMemo, type CSSProperties } from 'react'
import { Blinds, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SpeedMastersChip } from '@/components/SpeedMasters'
import { cn } from '@/lib/utils'
import { cueNumberColumnChars } from '@/lib/cueNumber'
import { MarkerRow } from '@/components/runner/MarkerRow'
import type { ExpansionMode } from '@/components/runner/mobile/CueCardBody'
import type { CueAnchorDto } from '../../api/promptBooksApi'
import type { CueStackCueRow } from '../../api/cueStacksApi'
import type { DesyncWarning, FlatCue } from '../../lib/promptBook/desync'
import type { ShowRailRow } from '../../lib/promptBook/geometry'
import type { CueRunStatus } from './AnchorOverlay'
import { DesyncWarningsPanel } from './DesyncWarningsPanel'
import { PromptBookCueCard } from './PromptBookCueCard'

// Stable identity for the common case (a cue with no warnings) — a fresh `[]` literal here
// would be a new prop value every render and defeat `PromptBookCueCard`'s memo for almost
// every row, almost every time.
const NO_WARNINGS: DesyncWarning[] = []

interface CueStackPanelProps {
  /** Rail rows in show order — headers, cue cards, and separators (markers). */
  rows: ShowRailRow[]
  /** Everything keyed by entry: a shared cue is a card per entry, each with its own anchor. */
  anchorByEntry: Map<number, CueAnchorDto>
  /** Per-unanchored-entry hint naming the neighbour the book borrows, e.g. "follows Q12". */
  anchorHintByEntry: Map<number, string>
  /** Full stack entries by entry id — feeds each expanded card's palette/notes/auto. */
  entryRowByEntry: Map<number, CueStackCueRow>
  statusOf: (entryId: number) => CueRunStatus
  warningsByEntry: Map<number, DesyncWarning[]>
  warnings: DesyncWarning[]
  showWarnings: boolean
  locked: boolean
  /** Entry currently armed for click-to-place on the script. */
  placingEntryId: number | null
  /** Whether an entry's card is open — a predicate, because several can be. See `useCueExpansion`. */
  isExpanded: (entryId: number) => boolean
  onToggleExpanded: (entryId: number) => void
  /** Details open or not, per entry — the page owns it so it persists across cue changes. */
  modeOf: (entryId: number, status: CueRunStatus) => ExpansionMode | null
  onCueModeChange: (entryId: number, status: CueRunStatus, mode: ExpansionMode | null) => void
  /**
   * The stack the transport acts on — only its cues can be armed as the next GO, and it is
   * also the id each card gates its own `useCueFade` subscription on (only a live-stack cue
   * can be the one fading in).
   */
  activeStackId: number | null
  onCueClick: (cue: FlatCue) => void
  onRemoveAnchor: (entryId: number) => void
  onWarningClick: (warning: DesyncWarning) => void
  /** Arm an entry as the next GO (sets standby). */
  onSetStandby: (entryId: number) => void
  /** Jump to the entry's cue in Show. */
  onEditCue: (entryId: number) => void
  /** Lift the show-editing lock, where it is the operator's — a card's locked ⋯ action asks first. */
  onRequestUnlock?: () => void
  /** Rename a cue in place (unlocked only — the cards gate on `locked` themselves). */
  onRenameCue: (cueId: number, name: string) => void
  /** Set or clear an entry's number in place (unlocked only). */
  onRenumberCue: (entryId: number, cueNumber: string | null) => void
  /** Set or clear a cue's note in place (unlocked only). */
  onRenoteCue: (cueId: number, notes: string | null) => void
  goDisabled: boolean
  /** Whether the show is running — drives the drawer header dot colour + a "Stopped" label. */
  showActive: boolean
  stackName: string | null
  projectId: number
  /** Front-matter page count, offsets each cue's page label to the script's numbering. */
  coverPages: number
  /** Rendered inside the tablet/phone drawer — shows a close affordance, drops the
   *  border and the transport (the narrow layout carries its own bottom transport). */
  inDrawer?: boolean
  onClose?: () => void
  /**
   * What the next GO moves (scenery-programmer plan D14): the cue on deck and the line itself, e.g.
   * `{cue: 'Q15', moves: 'House tabs close 2 s · Border 2 in'}`. Null, or an empty line, draws nothing.
   */
  onGo?: { cue: string | null; moves: string } | null
  /** Each cue's scenery lines for its card; a cue absent moves nothing. Stable per cue — the cards are memoized. */
  sceneryLinesByCue?: Map<number, readonly string[]>
}

/**
 * Right pane: the flattened show order as one list of Run-style cue cards. The live
 * (green) and next (blue) cues expand by default into full cards; every other cue is
 * a compact row that can be expanded on demand, with MARKER separators between them.
 * Clicking a card/row navigates to that cue's anchor — it never fires a cue; a per-card
 * "Set next" arms the next GO. Transport (stack · BPM · TAP · BACK/GO) lives in the
 * shared Row 3 show bar above the view; only the narrow drawer variant keeps its own header.
 */
export function CueStackPanel({
  rows,
  anchorByEntry,
  anchorHintByEntry,
  entryRowByEntry,
  statusOf,
  warningsByEntry,
  warnings,
  showWarnings,
  locked,
  placingEntryId,
  isExpanded,
  onToggleExpanded,
  modeOf,
  onCueModeChange,
  activeStackId,
  onCueClick,
  onRemoveAnchor,
  onWarningClick,
  onSetStandby,
  onEditCue,
  onRequestUnlock,
  onRenameCue,
  onRenumberCue,
  onRenoteCue,
  goDisabled,
  showActive,
  stackName,
  projectId,
  coverPages,
  inDrawer,
  onClose,
  onGo,
  sceneryLinesByCue,
}: CueStackPanelProps) {
  const hasCues = rows.some((r) => r.type === 'cue')

  // One number-column width for the whole rail — it spans every stack in the show, so the
  // widest number anywhere sets it and each row's name still starts at the same x.
  const cueNumChars = useMemo(
    () =>
      cueNumberColumnChars(
        [...entryRowByEntry.values()]
          .filter((c) => c.cueType === 'STANDARD')
          .map((c) => c.cueNumber),
      ),
    [entryRowByEntry],
  )

  return (
    <div
      className={cn(
        'flex h-full min-h-0 w-full flex-col bg-muted/20',
        inDrawer ? '' : 'max-w-[380px] min-w-[300px] border-l',
      )}
    >
      {/* Header (drawer only) — stack name · BPM · TAP · close. On desktop these controls
          live in the shared Row 3 show bar above the whole view, so the rail is a pure cue list. */}
      {inDrawer && (
        <div className="flex h-12 shrink-0 items-center gap-1 border-b bg-card px-2">
          <span
            className={cn(
              'size-1.5 shrink-0 rounded-full',
              showActive ? 'bg-emerald-500 shadow-[0_0_6px_#22c55e]' : 'bg-muted-foreground/40',
            )}
          />
          <span className="truncate text-sm font-medium">{stackName ?? 'Cue stack'}</span>
          {!showActive && (
            <span className="shrink-0 rounded bg-muted px-1.5 py-px text-[10px] font-semibold tracking-wide text-muted-foreground uppercase">
              Stopped
            </span>
          )}
          <span className="flex-1" />
          {/* Every master, master 1 included — this was a bare M1 readout + TAP, so the drawer
              could neither see nor tap any other master. No `@container` ancestor here, so the
              phone-width arm is asked for by name. */}
          <SpeedMastersChip />
          {onClose && (
            <Button variant="ghost" size="icon-sm" aria-label="Close cues" onClick={onClose}>
              <X className="size-4" />
            </Button>
          )}
        </div>
      )}

      {/* **On GO** — what the next GO moves, at the top of the rail where the next cue is read
          (D14). Not "Standby": that word is the armed next cue in the cue table and the unfired
          later cues here. Absent when nothing moves, so a rig with no scenery keeps its rail. */}
      {onGo != null && onGo.moves !== '' && (
        <div
          data-on-go
          className="mx-2 mt-2 shrink-0 rounded-md border border-sky-500/60 bg-sky-500/10 px-2.5 py-1.5"
        >
          <div className="flex items-center gap-1.5 font-mono text-[10px] font-bold tracking-[0.12em] text-sky-700 uppercase dark:text-sky-300">
            <Blinds className="size-3" />
            On GO{onGo.cue ? ` · ${onGo.cue}` : ''}
          </div>
          <div className="mt-0.5 text-xs font-semibold text-foreground">{onGo.moves}</div>
        </div>
      )}

      {showWarnings && <DesyncWarningsPanel warnings={warnings} onWarningClick={onWarningClick} />}

      <div
        className="min-h-0 flex-1 overflow-y-auto px-2 py-2"
        style={{ '--cue-num-chars': cueNumChars } as CSSProperties}
      >
        {!hasCues && (
          <p className="px-2 py-4 text-sm text-muted-foreground">
            No cues in the show yet. Build the show in Program first.
          </p>
        )}
        {rows.map((row) => {
          if (row.type === 'header') {
            return (
              <div
                key={`h-${row.stackId}`}
                className="px-2 pt-3 pb-1 text-[11px] font-semibold tracking-wide text-muted-foreground/80 uppercase"
              >
                {row.stackName}
              </div>
            )
          }
          if (row.type === 'separator') {
            // Key on source+id: a SEPARATOR stack and a MARKER cue can share a numeric id.
            return <MarkerRow key={`s-${row.source}-${row.id}`} name={row.name} />
          }
          const entryId = row.cue.entryId
          const status = statusOf(entryId)
          // Both facts below are the same underlying question — "is this row on the live
          // stack?" — computed once so they can't drift apart under a future edit.
          const isLiveStack = row.cue.stackId === activeStackId
          return (
            <PromptBookCueCard
              key={entryId}
              cue={row.cue}
              status={status}
              anchor={anchorByEntry.get(entryId)}
              anchorHint={anchorHintByEntry.get(entryId) ?? null}
              cueEntry={entryRowByEntry.get(entryId)}
              projectId={projectId}
              warnings={warningsByEntry.get(entryId) ?? NO_WARNINGS}
              locked={locked}
              placing={placingEntryId === entryId}
              expanded={isExpanded(entryId)}
              mode={modeOf(entryId, status)}
              onModeChange={onCueModeChange}
              fadeStackId={isLiveStack ? activeStackId : null}
              canSetNext={!goDisabled && isLiveStack}
              coverPages={coverPages}
              onCueClick={onCueClick}
              onToggleExpanded={onToggleExpanded}
              onSetStandby={onSetStandby}
              onRemoveAnchor={onRemoveAnchor}
              onEditCue={onEditCue}
              onRequestUnlock={onRequestUnlock}
              onRenameCue={onRenameCue}
              onRenumberCue={onRenumberCue}
              onRenoteCue={onRenoteCue}
              sceneryLines={sceneryLinesByCue?.get(row.cue.cueId)}
            />
          )
        })}
      </div>
    </div>
  )
}
