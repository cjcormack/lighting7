import { ListChecks, Minus, Play, Plus, TriangleAlert, Undo2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'

interface PromptBookToolbarProps {
  scriptFileName: string | null
  locked: boolean
  /** A running show is unlocked — tint with the header and show bar above. */
  unlockedWarning?: boolean
  canUndo: boolean
  onUndo: () => void
  /** Leading front-matter (cover/title) pages — offsets cue page labels to the script's numbering. */
  coverPages: number
  /** Total PDF pages — caps the front-matter count at pageCount - 1 (one numbered page must remain). */
  pageCount: number
  onCoverPagesChange: (n: number) => void
  /** Live cue label ("Q12") — renders a chip that jumps to its anchor. */
  activeLabel: string | null
  onJumpToLive: () => void
  warningCount: number
  onToggleWarnings: () => void
  /** Idle re-lock countdown (seconds); null unless the countdown is running. */
  /** Opens the cue-list drawer. Passed only on narrow, where the side rail is a drawer. */
  onOpenCues?: () => void
}

/**
 * Header bar over the script pane.
 *
 * The lock *control* used to live here — a pulsing EDITING badge — and moved into
 * `ShowLockControl` in session 2b so it sits in the same place on Show as here.
 *
 * The amber wash stayed, and takes its cue from the caller now rather than deriving it. The chrome
 * band is made of siblings — header, show bar, this bar — and every one of them has to tint, or the
 * result is a stripe of amber, a stripe of standard, a stripe of amber. Same class, from
 * `UNLOCKED_WARNING_CLASS`, so they cannot drift apart.
 */
export function PromptBookToolbar({
  scriptFileName,
  locked,
  unlockedWarning = false,
  canUndo,
  onUndo,
  coverPages,
  pageCount,
  onCoverPagesChange,
  activeLabel,
  onJumpToLive,
  warningCount,
  onToggleWarnings,
  onOpenCues,
}: PromptBookToolbarProps) {
  // Unlocked mid-show is the only state worth shouting about.
  return (
    <div
      className={cn(
        'flex shrink-0 items-center gap-3 border-b px-4 py-2 transition-colors',
        unlockedWarning ? UNLOCKED_WARNING_CLASS : 'bg-background',
      )}
    >
      <span className="hidden shrink-0 text-[11px] tracking-widest text-muted-foreground uppercase sm:inline">
        Prompt book
      </span>
      {scriptFileName && <span className="truncate text-sm font-medium">{scriptFileName}</span>}

      {activeLabel && (
        <button
          type="button"
          onClick={onJumpToLive}
          title="Jump to the live cue"
          className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-emerald-800 bg-emerald-950/60 py-0.5 pr-2 pl-2.5 text-xs font-semibold text-emerald-300 hover:bg-emerald-900/60"
        >
          <Play className="size-2.5 fill-current" strokeWidth={0} />
          <span className="font-mono">{activeLabel}</span>
          <span
            className="size-1.5 rounded-full bg-emerald-400"
            style={{ animation: 'r-fade-pulse 1.6s ease-in-out infinite' }}
          />
        </button>
      )}

      <span className="flex-1" />

      {onOpenCues && (
        <Button variant="outline" size="sm" onClick={onOpenCues} className="shrink-0">
          <ListChecks className="size-3.5" />
          Cues
        </Button>
      )}

      {warningCount > 0 && (
        <Tooltip>
          <TooltipTrigger asChild>
            <Button variant="ghost" size="sm" onClick={onToggleWarnings} className="text-red-500 hover:text-red-400">
              <TriangleAlert className="size-4" />
              {warningCount}
            </Button>
          </TooltipTrigger>
          <TooltipContent side="bottom">Desync warnings — advisory only</TooltipContent>
        </Tooltip>
      )}

      {/* Front-matter (cover/title) page count — an edit to the book, so it only
          shows while unlocked. Subtracting it aligns cue page labels with the
          script's own numbering. Capped so at least one numbered page remains. */}
      {!locked && (
        <Tooltip>
          <TooltipTrigger asChild>
            <div className="flex shrink-0 items-center gap-1 rounded-md border bg-background px-1.5 py-0.5">
              <span className="hidden text-[11px] text-muted-foreground sm:inline">Front matter</span>
              <Button
                variant="ghost"
                size="icon-sm"
                className="size-6"
                onClick={() => onCoverPagesChange(coverPages - 1)}
                disabled={coverPages <= 0}
                aria-label="Fewer front-matter pages"
              >
                <Minus className="size-3" />
              </Button>
              <span className="min-w-4 text-center font-mono text-xs tabular-nums">{coverPages}</span>
              <Button
                variant="ghost"
                size="icon-sm"
                className="size-6"
                onClick={() => onCoverPagesChange(coverPages + 1)}
                disabled={coverPages >= pageCount - 1}
                aria-label="More front-matter pages"
              >
                <Plus className="size-3" />
              </Button>
            </div>
          </TooltipTrigger>
          <TooltipContent side="bottom" className="max-w-56">
            Leading cover/title pages before the script&rsquo;s page 1 — cue page numbers are offset by this.
          </TooltipContent>
        </Tooltip>
      )}

      {!locked && canUndo && (
        <Button variant="outline" size="sm" onClick={onUndo}>
          <Undo2 className="size-3.5" />
          Undo move
        </Button>
      )}

    </div>
  )
}
