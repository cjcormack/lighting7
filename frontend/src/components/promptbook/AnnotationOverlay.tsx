import type { ReactNode } from 'react'
import { Scissors } from 'lucide-react'
import { cn } from '@/lib/utils'
import { InlineEditField } from '@/components/InlineEditField'
import type { AnnotationDto, NoteTone, Rect } from '../../api/promptBooksApi'
import { MARKER_LANE_X, marginRailStyle, rectToStyle } from '../../lib/promptBook/geometry'

/** Above this normalized height a cut rect is a multi-line block/region (a drawn
 *  box or legacy band) rather than one text line, and gets the obvious crossed-out
 *  treatment instead of a single strikethrough rule. */
const BLOCK_CUT_MIN_H = 0.03

/** A bubble's colourway. `tailBd`/`tailBg` are concrete colours for the CSS speech-bubble tail. */
interface BubbleStyle {
  box: string
  tag: string
  label: string
  tailBd: string
  tailBg: string
}

/** Note callout styling by tone — a dark sticky-note that reads over the page. */
const toneStyles: Record<NoteTone, BubbleStyle> = {
  NOTE: {
    box: 'border-sky-700 bg-sky-950/95 text-sky-100',
    tag: 'border-sky-700 text-sky-300',
    label: 'Note',
    tailBd: '#0369a1',
    tailBg: '#082f49',
  },
  WARN: {
    box: 'border-amber-700 bg-amber-950/95 text-amber-100',
    tag: 'border-amber-700 text-amber-300',
    label: 'Warn',
    tailBd: '#b45309',
    tailBg: '#451a03',
  },
  SAFETY: {
    box: 'border-red-800 bg-red-950/95 text-red-100',
    tag: 'border-red-800 text-red-300',
    label: 'Safety',
    tailBd: '#991b1b',
    tailBg: '#450a0a',
  },
}

/**
 * On-page cut. Two treatments, chosen per rect by height:
 *  • Text-line rect (from a selection) → a real strikethrough: a thin rule at the
 *    rect's vertical midpoint, following the struck words exactly (the text-layer
 *    glyphs are transparent, so they can't be CSS `line-through`d directly).
 *  • Block / region rect (a drawn box or legacy band, taller than one line) → an
 *    obvious crossed-out treatment: a red wash, outline, and a diagonal X, since a
 *    single mid-rule across a tall block reads as far too subtle.
 * The margin marker (see {@link CutMarginMarker}) carries the "CUT" label.
 */
export function CutOverlay({
  rects,
  locked,
  onClick,
}: {
  rects: Rect[]
  locked: boolean
  onClick: () => void
}) {
  return (
    <>
      {rects.map((rect, i) =>
        rect.h > BLOCK_CUT_MIN_H ? (
          <div
            key={i}
            style={rectToStyle(rect)}
            onClick={locked ? undefined : onClick}
            className={cn(
              'rounded-sm bg-red-500/12 outline outline-1 outline-red-500/45',
              !locked && 'pointer-events-auto cursor-pointer hover:bg-red-500/20',
            )}
          >
            {/* Inset wrapper (a block box sizes to `inset`; a bare <svg> would keep
                its intrinsic height) so the cross ends inside the rounded corners. */}
            <div className="absolute inset-[4px]">
              <svg className="h-full w-full" viewBox="0 0 100 100" preserveAspectRatio="none">
                <line x1="0" y1="0" x2="100" y2="100" stroke="rgba(220,38,38,0.7)" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
                <line x1="0" y1="100" x2="100" y2="0" stroke="rgba(220,38,38,0.7)" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
              </svg>
            </div>
          </div>
        ) : (
          <div
            key={i}
            style={rectToStyle(rect)}
            onClick={locked ? undefined : onClick}
            className={cn('flex items-center', !locked && 'pointer-events-auto cursor-pointer')}
          >
            <span className={cn('h-[2px] w-full rounded-full bg-red-600/85', !locked && 'hover:bg-red-500')} />
          </div>
        ),
      )}
    </>
  )
}

/** On-page freetext annotation — text rendered inside its region. */
export function FreetextOverlay({
  annotation,
  rects,
  locked,
  onClick,
}: {
  annotation: AnnotationDto
  rects: Rect[]
  locked: boolean
  onClick: () => void
}) {
  return (
    <>
      {rects.map((rect, i) => (
        <div
          key={i}
          style={rectToStyle(rect)}
          onClick={locked ? undefined : onClick}
          className={cn(
            'rounded-sm outline-1 outline-dashed outline-slate-400/40',
            !locked && 'pointer-events-auto cursor-pointer hover:outline-slate-300/70',
          )}
        >
          <span
            className="absolute inset-0 flex items-start overflow-hidden p-0.5 text-[11px] leading-tight font-medium"
            style={{ color: annotation.color ?? '#1d4ed8' }}
          >
            {annotation.text}
          </span>
        </div>
      ))}
    </>
  )
}

/**
 * Cut marker in the fixed left-margin rail: a dashed red band + a "CUT" pill,
 * anchored to {@link MARKER_LANE_X} so it lines up with the cue markers (overflowing
 * left into the paper gutter), independent of where the struck text starts.
 */
export function CutMarginMarker({
  rects,
  locked,
  laneX = MARKER_LANE_X,
  onClick,
}: {
  rects: Rect[]
  locked: boolean
  /** Normalized x of the shared margin rail (just left of the page's text block). */
  laneX?: number
  onClick: () => void
}) {
  return (
    <div
      style={marginRailStyle(rects, laneX)}
      onClick={locked ? undefined : onClick}
      className={cn(
        'flex items-start',
        locked ? 'pointer-events-none' : 'pointer-events-auto cursor-pointer',
      )}
    >
      <span className="mr-1 inline-flex items-center gap-0.5 rounded border border-red-300 bg-red-50 px-1 font-mono text-[9px] font-bold tracking-wide text-red-600 uppercase">
        <Scissors className="size-2.5" />
        cut
      </span>
      <span className="h-full w-0 shrink-0 border-r-2 border-dashed border-red-500/70" />
    </div>
  )
}

/** The visual shell every gutter bubble shares — tone box, tag chip, optional tail. */
function Bubble({
  style,
  label,
  withTail,
  onClick,
  interactive,
  children,
}: {
  style: BubbleStyle
  /** Overrides the style's own tag text (a cue note is tagged with its cue number). */
  label?: string
  withTail?: boolean
  /** Whole-bubble click target. Omit when the bubble's own content handles pointers. */
  onClick?: () => void
  /** Whether the bubble takes pointer events at all — the overlay above the page is inert. */
  interactive: boolean
  children: ReactNode
}) {
  return (
    <div
      onClick={onClick}
      className={cn(
        'relative rounded-md border p-2 shadow-lg',
        style.box,
        interactive && 'pointer-events-auto',
        interactive && onClick && 'cursor-pointer hover:brightness-110',
      )}
    >
      {/* Left-pointing speech-bubble tail (two triangles: border then fill). */}
      {withTail && (
        <>
          <span
            className="absolute top-3 -left-[7px]"
            style={{
              borderTop: '6px solid transparent',
              borderBottom: '6px solid transparent',
              borderRight: `7px solid ${style.tailBd}`,
            }}
          />
          <span
            className="absolute top-3 -left-[6px] mt-px"
            style={{
              borderTop: '5px solid transparent',
              borderBottom: '5px solid transparent',
              borderRight: `6px solid ${style.tailBg}`,
            }}
          />
        </>
      )}
      <span
        className={cn(
          'mb-1 block w-fit rounded border px-1 font-mono text-[8.5px] leading-tight font-bold tracking-wide uppercase',
          style.tag,
        )}
      >
        {label ?? style.label}
      </span>
      {children}
    </div>
  )
}

/** Tone-coloured note callout. Used in the right gutter (desktop, with a tail) or inline (narrow). */
function NoteBubble({
  annotation,
  locked,
  onClick,
  withTail,
}: {
  annotation: AnnotationDto
  locked: boolean
  onClick: () => void
  withTail?: boolean
}) {
  const tone: NoteTone = annotation.tone ?? 'NOTE'
  return (
    <Bubble
      style={toneStyles[tone]}
      withTail={withTail}
      interactive={!locked}
      onClick={locked ? undefined : onClick}
    >
      <div className="text-[11.5px] leading-snug whitespace-pre-wrap">{annotation.text}</div>
    </Bubble>
  )
}

/**
 * Note callout, top-aligned to the annotated region, with its tail anchored just
 * right of the text block ({@link leftPct}) so it hugs the text's right edge and
 * extends into the paper gutter. Rendered in the page overlay, mirroring the cue
 * markers on the left.
 */
export function NoteCallout({
  annotation,
  topPct,
  locked,
  leftPct,
  widthPx,
  onClick,
}: {
  annotation: AnnotationDto
  /**
   * Normalized y (×100) of the bubble's top. Supplied by the caller rather than read off the
   * region, because the gutter is shared: bubbles are spread apart there so two notes anchored
   * to the same line don't stack on top of each other.
   */
  topPct: number
  locked: boolean
  /** Normalized x (×100) of the note's tail — just right of the text block. */
  leftPct: number
  /** Bubble width in px — fills from the text's right edge across the paper gutter. */
  widthPx: number
  onClick: () => void
}) {
  return (
    <div
      className="pointer-events-none absolute"
      style={{ top: `${topPct}%`, left: `${leftPct}%`, width: widthPx }}
    >
      <NoteBubble annotation={annotation} locked={locked} onClick={onClick} withTail />
    </div>
  )
}

/**
 * A cue's own note, as opposed to an annotation on the script.
 *
 * Deliberately a different colourway from the tone notes: those belong to the script and are
 * authored on the page, this one belongs to the cue and follows it wherever the cue is anchored.
 * Tagged with the cue number so it is obvious which cue on the page it is speaking for.
 */
const cueNoteStyle: BubbleStyle = {
  box: 'border-slate-600 bg-slate-900/95 text-slate-100',
  tag: 'border-slate-600 text-slate-300',
  label: 'Cue',
  tailBd: '#475569',
  tailBg: '#0f172a',
}

/**
 * Cue note in the right gutter, positioned like {@link NoteCallout} — top-aligned to the cue's
 * anchored text, tail against the text's right edge. Editable in place when the book is
 * unlocked, so a note can be written without leaving for the Show editor.
 */
export function CueNoteCallout({
  label,
  notes,
  topPct,
  locked,
  leftPct,
  widthPx,
  onCommit,
}: {
  /** The cue's display number, e.g. "Q12" — the bubble's tag. */
  label: string
  notes: string | null
  /** Normalized y (×100) of the bubble's top — see {@link NoteCallout}. */
  topPct: number
  locked: boolean
  leftPct: number
  widthPx: number
  onCommit: (next: string | null) => void
}) {
  return (
    <div
      className="pointer-events-none absolute"
      style={{ top: `${topPct}%`, left: `${leftPct}%`, width: widthPx }}
    >
      <Bubble style={cueNoteStyle} label={label} withTail interactive={!locked}>
        <CueNoteText
          notes={notes}
          locked={locked}
          onCommit={onCommit}
        />
      </Bubble>
    </div>
  )
}

/** Narrow-layout cue note: inline under the anchored line, where there is no gutter to use. */
export function CueNoteInline({
  label,
  notes,
  topPct,
  locked,
  onCommit,
}: {
  label: string
  notes: string | null
  /** Normalized y (×100) of the bubble's top — see {@link NoteCallout}. */
  topPct: number
  locked: boolean
  onCommit: (next: string | null) => void
}) {
  return (
    <div
      className="pointer-events-none absolute right-2 left-2"
      style={{ top: `${topPct}%`, marginTop: 4 }}
    >
      <div className="max-w-[min(320px,90%)]">
        <Bubble style={cueNoteStyle} label={label} interactive={!locked}>
          <CueNoteText
            notes={notes}
            locked={locked}
            onCommit={onCommit}
          />
        </Bubble>
      </div>
    </div>
  )
}

/** The note body — read-only text when locked, click-to-edit when not. */
function CueNoteText({
  notes,
  locked,
  onCommit,
}: {
  notes: string | null
  locked: boolean
  onCommit: (next: string | null) => void
}) {
  if (locked) {
    return <div className="text-[11.5px] leading-snug whitespace-pre-wrap">{notes}</div>
  }
  return (
    <InlineEditField
      value={notes ?? ''}
      onCommit={(next) => onCommit(next.trim() || null)}
      ariaLabel="cue notes"
      placeholder="Performance note…"
      multiline
      rows={3}
      formatDisplay={(v) => v || <span className="opacity-60">+ Add note</span>}
      className="w-full text-[11.5px] leading-snug"
    />
  )
}

/** Narrow-layout note: rendered inline over the page, just below the region. */
export function NoteInline({
  annotation,
  topPct,
  locked,
  onClick,
}: {
  annotation: AnnotationDto
  /** Normalized y (×100) of the bubble's top — see {@link NoteCallout}. */
  topPct: number
  locked: boolean
  onClick: () => void
}) {
  return (
    <div className="absolute right-2 left-2" style={{ top: `${topPct}%`, marginTop: 4 }}>
      <div className="max-w-[min(320px,90%)]">
        <NoteBubble annotation={annotation} locked={locked} onClick={onClick} />
      </div>
    </div>
  )
}
