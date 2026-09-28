import { useState } from 'react'
import {
  ChevronRight,
  Check,
  Copy,
  Download,
  GripVertical,
  Loader2,
  Sliders,
  Trash2,
  Zap,
} from 'lucide-react'
import { useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { targetKey } from '@/lib/targetKey'
import {
  useDeleteProjectCueMutation,
  usePatchProjectCueMutation,
} from '@/store/cues'
import { InlineEditField } from '@/components/InlineEditField'
import type { Cue } from '@/api/cuesApi'
import type { CueStackCueEntry } from '@/api/cueStacksApi'
import {
  formatFadeCurve,
  formatFadeDuration,
  parseFadeDuration,
} from '@/lib/cueUtils'
import {
  CueStatePip,
  CueTargetChip,
  useExpandedCue,
} from '@/components/cues/CueRowParts'
import { AUTO_CUE_NUMBER_CLASS, cueNumberCellWidth } from '@/lib/cueNumber'
import { TruncateStart } from '@/components/TruncateStart'
import { SaveStatusIndicator } from '@/components/SaveStatusIndicator'
import { CueDetailContent } from '@/components/cues/CueDetailContent'
import { HandCueLayerStrip } from '@/components/hand/HandLayerTargets'
import { CuePropertiesSheet } from '@/components/cues/CuePropertiesSheet'
import { useCueFade } from '@/hooks/useCueFade'
interface CueCardEditorProps {
  cue: CueStackCueEntry
  projectId: number
  expanded: boolean
  onToggleExpanded: () => void
  isActive?: boolean
  isStandby?: boolean
  /** This session has already run the cue — the "done" tick. */
  isDone?: boolean
  /**
   * The stack whose runner owns this row's fade — the project playhead, or null when this stack is
   * not the one running.
   *
   * The row reads its own fade from that rather than being handed one: the fade is the only
   * frame-rate value on a cue row, and passing it down would re-render every row in the stack to
   * animate one. See `useCueFade`.
   */
  fadeStackId?: number | null
  /** Prompt-book reading position, e.g. "top of p. 9". */
  location?: string | null
  /** Arm this cue as the next GO. Absent where there is no transport to arm against. */
  onSetStandby?: () => void
  /**
   * Show-safe mode: no dragging, no inline edits, no destructive actions.
   *
   * Passed `disabled` straight into `useSortable` rather than unmounting the dnd context — the row
   * needs its `SortableContext` ancestor either way, and dnd-kit's own `disabled` makes a drag
   * genuinely impossible rather than merely discouraged.
   */
  locked?: boolean
  onDuplicate?: (cue: Cue) => void
  /** Record the programmer into this cue — opens the Record sheet targeting it. */
  onRecordInto?: (cueId: number) => void
  /** Load this cue into the programmer to edit it on stage. */
  onIncludeCue?: (cueId: number) => void
  includePending?: boolean
}

/**
 * Inline-expanding cue card. The collapsed row is the cue summary (Q# · name · target
 * chips · fade); expanding it shows the cue **read-only** — the same value grid the programmer
 * draws, its layer stack, its effects and its hooks — with two ways out: *Edit in Programmer*,
 * which Includes it, and *Cue properties…*, which opens the drawer for everything a value grid
 * cannot express.
 *
 * **It used to be a three-pane editor** (Targets · Properties · Layers), collapsing to tabs below
 * 1000px, and each expanded card opened its own `cueEdit` WS session. All of that went in session
 * 2a, and the backend protocol behind it was deleted in the post-refactor sweep. Targets and Layers were a second, differently-shaped restatement of what a value grid and a
 * layer stack already say, and keeping two renderings of one state in step is a losing game; the
 * session went with them, because a cue is now edited in exactly one place. The tab machinery went
 * too — there is one body, so there is nothing to switch between.
 */
export function CueCardEditor({
  cue,
  projectId,
  expanded,
  onToggleExpanded,
  isActive = false,
  isStandby = false,
  isDone = false,
  fadeStackId = null,
  location = null,
  onSetStandby,
  locked = false,
  onDuplicate,
  onRecordInto,
  onIncludeCue,
  includePending = false,
}: CueCardEditorProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: cue.id,
    disabled: locked,
  })

  const sortableStyle = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 50 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  const [propsOpen, setPropsOpen] = useState(false)

  const { cueData, targets, isFetching } = useExpandedCue(projectId, cue.id, expanded)
  const [deleteCue] = useDeleteProjectCueMutation()
  const [patchCue] = usePatchProjectCueMutation()


  const targetCount = targets.length

  // Inline edits from the collapsed row. Same PATCH-on-commit contract as the expanded
  // card's Properties pane, so the two stay consistent — every field auto-saves.
  const commitName = (next: string) => {
    const trimmed = next.trim()
    if (trimmed === '') return false
    if (trimmed !== cue.name) patchCue({ projectId, cueId: cue.id, name: trimmed })
  }

  const commitCueNumber = (next: string) => {
    const trimmed = next.trim() || null
    if (trimmed !== (cue.cueNumber ?? null)) {
      patchCue({ projectId, cueId: cue.id, cueNumber: trimmed })
    }
  }

  const commitFade = (next: string) => {
    const parsed = parseFadeDuration(next)
    if (parsed === undefined) return false
    if (parsed !== (cue.fadeDurationMs ?? null)) {
      patchCue({ projectId, cueId: cue.id, fadeDurationMs: parsed })
    }
  }

  const fadeCurveLabel =
    cue.fadeDurationMs != null && cue.fadeDurationMs > 0 ? formatFadeCurve(cue.fadeCurve) : ''

  const { fadeProgress, fadeRemainMs } = useCueFade(fadeStackId, cue.id, cue.fadeDurationMs)
  const isFading = fadeProgress != null

  /**
   * The row body and the chevron do different things, which is what lets one row serve a show being
   * run and a show being edited.
   *
   * The chevron always expands. The body **arms the cue for the next GO while locked** — locked is
   * the running state, where reaching for a cue means "go there next" — and expands while unlocked,
   * where reaching for a cue means "let me look at it". Arming is deliberately not available
   * unlocked: it changes what GO fires, which is the show, and not letting a stray click do that is
   * the entire point of the lock.
   */
  const handleBodyClick = () => {
    if (locked && onSetStandby && !isActive) {
      onSetStandby()
      return
    }
    onToggleExpanded()
  }

  return (
    <div ref={setNodeRef} style={sortableStyle} {...attributes} data-cue-row={cue.id}>
      <div
        className={cn(
          // `@container`: the header's columns below query THIS box. They used to be viewport
          // `max-[…]:` queries against a container-measured body, and the two disagree by exactly
          // the sidebar width — so a card in the sidebar broke its columns at the wrong point.
          '@container rounded-lg border bg-muted overflow-hidden transition-colors mx-2 my-1',
          isActive && 'border-green-500/70 shadow-[0_0_0_1px_rgba(34,197,94,0.3)]',
          isStandby && !isActive && 'border-blue-500/60',
          expanded && !isActive && !isStandby && 'border-primary/40',
        )}
      >
        <div
          className={cn(
            'grid items-center gap-3 px-3 py-2.5 cursor-pointer hover:bg-muted/30 transition-colors',
            // The Q# track is `auto`, sized by the fixed-width cell inside it — see
            // `cueNumberCellWidth`. Every row in a stack resolves the same width, so the column
            // still lines up, but a stack of "Q1"–"Q40" no longer reserves room it never uses.
            'group/cue',
            // One track per child, and the child list changes with the width: the target chips are
            // hidden at/below 1000px, so that range needs its own five-track template. A template
            // with more tracks than children slides every cell one column left — which is what a
            // stale track did to the cue name when the palette bar was removed from this row.
            'grid-cols-[22px_auto_minmax(0,1fr)_auto_auto_18px]',
            '@max-[1000px]:grid-cols-[22px_auto_minmax(0,1fr)_auto_18px]',
            '@max-[800px]:grid-cols-[22px_auto_minmax(0,1fr)_auto_18px] @max-[800px]:gap-2',
          )}
          onClick={handleBodyClick}
        >
          {/* One cell, two jobs. The pip is a read-out and must be visible mid-show; the grip is
              an action and only exists while unlocked. Stacking them costs no width, and hiding
              the pip under the pointer is free — you are not reading it while you drag. */}
          <div className="relative grid size-[22px] place-items-center">
            <span className={cn('transition-opacity', !locked && 'group-hover/cue:opacity-0')}>
              <CueStatePip isActive={isActive} isStandby={isStandby} />
            </span>
            {!locked && (
              <div
                className="absolute inset-0 grid place-items-center text-muted-foreground opacity-0 transition-opacity hover:text-foreground group-hover/cue:opacity-100 cursor-grab"
                {...listeners}
                onClick={(e) => e.stopPropagation()}
              >
                <GripVertical className="size-4" />
              </div>
            )}
          </div>

          <div
            className="font-mono text-sm font-semibold flex items-center gap-1.5 min-w-0"
            // Play icon (12px) + gap (6px) + the inline field's border/padding (6px).
            style={cueNumberCellWidth('1.5rem')}
          >
            {isDone && !isActive && (
              <Check className="size-3 shrink-0 text-muted-foreground/60" aria-label="Played" />
            )}
            <InlineEditField
              value={cue.cueNumber ?? ''}
              // Clipped at the START: the tail is what tells "QS1-3.1" from "QS1-3.2", so it is
              // the end worth keeping.
              formatDisplay={(v) => <TruncateStart text={v ? `Q${v}` : '—'} />}
              onCommit={commitCueNumber}
              ariaLabel="cue number"
              disabled={locked}
              placeholder="14A"
              title={
                cue.cueNumberAuto
                  ? 'Auto-numbered from position — click to set an explicit cue number'
                  : 'Click to edit the cue number'
              }
              // Wraps the number rather than filling the cell, so only the text itself opens the
              // editor; the rest of the cell belongs to the row and expands the cue.
              className={cn(
                'min-w-0 max-w-full px-0.5',
                cue.cueNumberAuto && AUTO_CUE_NUMBER_CLASS,
              )}
            />
          </div>

          <InlineEditField
            value={cue.name}
            onCommit={commitName}
            ariaLabel="cue name"
            disabled={locked}
            title="Click to rename"
            // `justify-self-start` keeps the field the width of the name it holds. Left to
            // stretch (the grid default) it would cover the whole 1fr column, so clicking the
            // empty space beside a short cue name opened the editor instead of expanding the cue.
            className={cn(
              'justify-self-start min-w-0 max-w-full truncate text-sm',
              isActive ? 'text-green-300 font-semibold' : isStandby ? 'text-blue-300 font-semibold' : 'font-medium',
            )}
          />

          <div className="flex items-center gap-1 flex-nowrap @max-[1000px]:hidden">
            {/* Prompt-book reading position — where in the script this cue is called. Sits with the
                other row metadata rather than beside the name, which is an edit field. */}
            {location && (
              <span className="shrink-0 font-mono text-[10px] text-muted-foreground/70">
                {location}
              </span>
            )}
            {targets.slice(0, 4).map((t) => (
              <CueTargetChip key={targetKey(t)} target={t} />
            ))}
            {targetCount > 4 && (
              <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                +{targetCount - 4}
              </Badge>
            )}
            {(cueData?.adHocEffects.length ?? 0) > 0 && (
              <Badge
                variant="outline"
                className="text-[10px] px-1.5 py-0 border-amber-500/40 text-amber-500 bg-amber-500/10"
              >
                {cueData!.adHocEffects.length} FX
              </Badge>
            )}
          </div>

          <div className="flex flex-col items-end font-mono text-xs gap-0 leading-tight shrink-0">
            {isFading ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-900 bg-amber-950/40 px-2 py-px font-mono text-[10px] font-bold tracking-[0.08em] uppercase text-amber-400">
                <span
                  className="size-1.5 rounded-full bg-amber-400"
                  style={{ animation: 'r-fade-pulse 0.9s ease-in-out infinite' }}
                />
                {((fadeRemainMs ?? 0) / 1000).toFixed(1)}s
              </span>
            ) : (
            <span className="flex items-center gap-1 text-foreground">
              <InlineEditField
                value={formatFadeDuration(cue.fadeDurationMs)}
                formatDisplay={(v) => v || 'SNAP'}
                onCommit={commitFade}
                ariaLabel="fade duration"
                placeholder="2s"
                title="Fade duration — e.g. 2s, 500ms (a bare number is seconds)"
                className="w-14 text-right"
                disabled={locked}
              />
              {fadeCurveLabel && (
                <span className="text-muted-foreground">{fadeCurveLabel}</span>
              )}
            </span>
            )}
            {cue.autoAdvance && (
              <span className="text-[9px] text-blue-500 uppercase tracking-wide">
                auto
              </span>
            )}
          </div>

          {/* The chevron is its own target and always expands, in both lock modes. It has to be:
              while locked the row body arms the cue instead of expanding, so without this there is
              no way to open a card at all during a show. */}
          <button
            type="button"
            aria-label={expanded ? 'Collapse cue' : 'Expand cue'}
            aria-expanded={expanded}
            onClick={(e) => {
              e.stopPropagation()
              onToggleExpanded()
            }}
            className="flex items-center justify-center text-muted-foreground hover:text-foreground"
          >
            <ChevronRight
              className={cn('size-4 transition-transform', expanded && 'rotate-90')}
            />
          </button>
        </div>

        {expanded && (
          <div className="border-t bg-background">
              {!cueData ? (
                <div className="flex items-center justify-center py-12 text-muted-foreground">
                  {isFetching ? (
                    <Loader2 className="size-5 animate-spin" />
                  ) : (
                    <span className="text-sm">Loading cue…</span>
                  )}
                </div>
              ) : (
                <>
                  {/* Scoped to this cue: the show-wide pill in the header reports any write in the
                      project, which is no use when you want to know that the field you just left
                      actually landed. The Live / Preview-edit toggle that stood beside it went with
                      the `cueEdit` session it switched — that session no longer exists on either
                      side, and with the cue read-only there is nothing here for it to gate; the
                      programmer's own Blind is the gate that matters.

                      The whole strip is withheld while locked. The properties drawer is an editing
                      form, and with it gone the row would hold only a save indicator for writes that
                      cannot happen from here. */}
                  {!locked && (
                    <div className="flex items-center justify-between gap-2 border-b bg-muted/20 px-3 py-2">
                      <SaveStatusIndicator cueId={cue.id} className="justify-start" />
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 gap-1.5 text-xs"
                        onClick={() => setPropsOpen(true)}
                      >
                        <Sliders className="size-3.5" />
                        Cue properties…
                      </Button>
                    </div>
                  )}

                  {/* The cue read surface — the same value grid the programmer draws, plus its
                      layer stack and its effects, all read-only. Session 2a deleted the three-pane
                      editor that stood here: Targets and Layers were a second, differently-shaped
                      way to express what a value grid and a layer stack already say, and two
                      renderings of one thing do not stay in step. Editing a cue is Include -> the
                      programmer, which is what makes there be exactly one place values are set. */}
                  <div className="space-y-3 px-3 py-3">
                    <CueDetailContent cue={cueData} projectId={projectId} />
                    {/* The hand's target for this cue's stack (multi-screen plan §3.5). Drawn only
                        while something that can become a layer is held, and never while the show is
                        locked — locked is the running state, and a cue must not gain a layer from a
                        stray tap there. It sits under the read surface because that is where the
                        layer stack it would join is drawn. */}
                    {!locked && <HandCueLayerStrip projectId={projectId} cue={cueData} />}
                  </div>

                  {/* The action row, absent in its entirety while locked — hidden rather than
                      disabled, because a row of greyed-out buttons reads as breakage where absence
                      reads as "not now".

                      Every one of these changes the show or reaches the stage, which is why none of
                      them survives the lock. Remove and Duplicate edit the stack. **Edit in
                      Programmer Includes the cue, and Include goes live** (D2 of the plan: the desk
                      does not auto-blind, so opening a cue puts it on stage). Record writes the cue
                      from whatever the programmer is holding. Reading a cue is the only thing this
                      card does while the show is locked. */}
                  {!locked && (
                  <div className="flex items-center gap-2 px-3 py-2 border-t bg-muted/20">
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 text-xs text-destructive hover:text-destructive gap-1"
                      onClick={() => {
                        deleteCue({ projectId, cueId: cue.id })
                      }}
                    >
                      <Trash2 className="size-3.5" />
                      Remove
                    </Button>
                    {onDuplicate && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs gap-1"
                        onClick={() => onDuplicate(cueData)}
                      >
                        <Copy className="size-3.5" />
                        Duplicate
                      </Button>
                    )}
                    {/* The programmer loop, from the cue you're already looking at. Include
                        loads this cue into the programmer to edit on stage; Record writes the
                        programmer back. Together they replace the old "Grab live" button —
                        capturing the whole stage is now one Record source among several,
                        rather than the only (and lossiest) way in. */}
                    {onIncludeCue && (
                      <Button
                        variant="default"
                        size="sm"
                        className="h-7 gap-1 text-xs"
                        onClick={() => onIncludeCue(cue.id)}
                        disabled={includePending}
                        // Named for where it takes you rather than for the verb it sends. "Include"
                        // is the right word for the operation and the wrong word for the only route
                        // from a cue to changing it: an operator looking for "edit" would not guess
                        // it. Blind stays theirs to set first — the desk does not infer it, which is
                        // why the ShowBar carries the switch.
                        title="Load this cue into the programmer to change it"
                      >
                        {includePending ? (
                          <Loader2 className="size-3.5 animate-spin" />
                        ) : (
                          <Download className="size-3.5" />
                        )}
                        Edit in Programmer
                      </Button>
                    )}
                    {onRecordInto && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 text-xs gap-1"
                        onClick={() => onRecordInto(cue.id)}
                      >
                        <Zap className="size-3.5" />
                        Record
                      </Button>
                    )}
                    {/* No "Make hard" here, and nothing replaces it. The gesture existed to
                        resolve value-level references; those retired with the `ref:` grammar, so
                        there is nothing left to make hard. Every route it could have called is
                        gone too — the per-cue one, `POST /programmer/make-hard`, and
                        `POST /cues/{id}/flatten`. Don't add a button back for a concept the
                        composition model no longer has. */}
                  </div>
                  )}
                </>
              )}
          </div>
        )}
      </div>
      <CuePropertiesSheet
        cue={cueData}
        projectId={projectId}
        open={propsOpen}
        onOpenChange={setPropsOpen}
      />
    </div>
  )
}

