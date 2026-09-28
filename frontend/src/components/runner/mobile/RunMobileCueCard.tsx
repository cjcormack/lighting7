import { ChevronDown } from 'lucide-react'
import type { CueStackCueEntry } from '@/api/cueStacksApi'
import { useCueFade } from '@/hooks/useCueFade'
import { CueCardBody, type ExpansionMode } from './CueCardBody'

export type CardKind = 'cur' | 'nxt'
export type { ExpansionMode }
export interface MobileExpansion {
  card: CardKind
  mode: ExpansionMode
}

interface RunMobileCueCardProps {
  kind: CardKind
  cue: CueStackCueEntry | null
  projectId: number
  /** Mutually-exclusive expansion across the two cards. null = both collapsed. */
  expansion: MobileExpansion | null
  onSetExpansion: (next: MobileExpansion | null) => void
  /** Counter for the Current card ("3 / 12"). */
  counter?: string | null
  /** Open the bottom-sheet picker (Next card "Change" button). */
  onChange?: () => void
  /** The live stack id, or null off the playhead — gates this card's own `useCueFade`
   *  subscription. Only meaningful for the Current card; the Next card never fades. */
  fadeStackId?: number | null
  /** Prompt-book reading position for this cue, e.g. "top of p. 9". */
  location?: string | null
}

/**
 * Mobile cue card for "Now playing" (current) and "Up next". A thin wrapper over the
 * shared `CueCardBody`: maps the mutually-exclusive `MobileExpansion` model to the
 * body's per-card `mode`, and injects the current card's counter pill or the next
 * card's "Change" button as header-trailing chrome.
 */
export function RunMobileCueCard({
  kind,
  cue,
  projectId,
  expansion,
  onSetExpansion,
  counter,
  onChange,
  fadeStackId = null,
  location,
}: RunMobileCueCardProps) {
  const mode = expansion?.card === kind ? expansion.mode : null
  // A sentinel cueId when there's no cue (pre-show, or nothing on deck): no real cue has id -1,
  // so the hook naturally reads as "not fading" rather than needing a conditional hook call.
  const { fadeProgress, fadeRemainMs } = useCueFade(
    kind === 'cur' ? fadeStackId : null,
    cue?.id ?? -1,
    cue?.fadeDurationMs ?? null,
  )
  // CueCardBody now always renders headerTrailing (so the book's chevron survives the
  // fade); preserve the current card's pre-extraction behaviour of hiding the counter
  // while the fade badge is showing.
  const isFading = kind === 'cur' && fadeProgress != null && fadeProgress < 1

  const headerTrailing =
    kind === 'cur'
      ? counter && !isFading
        ? (
            <span className="rounded-full border border-border bg-muted/30 px-2 py-px text-[9.5px] text-muted-foreground tracking-[0.08em]">
              {counter}
            </span>
          )
        : null
      : onChange
        ? (
            <button
              type="button"
              onClick={onChange}
              className="inline-flex items-center gap-1 rounded-full border border-blue-900/60 bg-blue-950/60 px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-[0.04em] text-blue-300 active:scale-95"
            >
              Change
              <ChevronDown className="size-2.5" />
            </button>
          )
        : null

  return (
    <CueCardBody
      kind={kind}
      cue={cue}
      projectId={projectId}
      mode={mode}
      onModeChange={(next) => onSetExpansion(next == null ? null : { card: kind, mode: next })}
      location={location}
      headerTrailing={headerTrailing}
      fadeProgress={fadeProgress}
      fadeRemainMs={fadeRemainMs}
    />
  )
}
