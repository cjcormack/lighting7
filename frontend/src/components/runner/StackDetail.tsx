import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { ArrowLeft, SeparatorHorizontal, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  verticalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable'
import {
  useReorderCueStackCuesMutation,
  useSortCueStackByCueNumberMutation,
} from '@/store/cueStacks'
import { cn } from '@/lib/utils'
import { SheetPage } from '@/components/sheet/SheetPage'
import type { CueStack } from '@/api/cueStacksApi'
import type { Cue } from '@/api/cuesApi'
import { CueCardEditor } from './CueCardEditor'
import { ShowMarkerRow } from './ShowMarkerRow'
import { OutOfOrderBanner } from '@/components/runner/OutOfOrderBanner'
import { cueNumberColumnChars, detectOutOfOrder } from '@/lib/cueNumber'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import { ShowStackViewSwitcher, type CardsListView } from '@/components/ViewSwitcher'
import { CueSheet } from './CueSheet'

interface StackDetailProps {
  stack: CueStack
  projectId: number
  activeCueId: number | null
  /** Cue queued to fire on the next GO. Only meaningful when drilled into the active stack. */
  standbyCueId?: number | null
  /**
   * The playhead's stack id, or null when this stack is not the one running — the rows use it to
   * read their own fade. Deliberately not a fade *value*: see `useCueFade` for why a 60Hz number
   * must not travel down through a memoized subtree.
   */
  fadeStackId?: number | null
  /** Cues this session has already run, for the "played" tick. */
  completedCueIds?: number[]
  /** Prompt-book reading position per cue, e.g. "top of p. 9". */
  locationByCue?: Map<number, string>
  /** Arm a cue as the next GO. Absent where there is no transport. */
  onSetStandby?: (cueId: number) => void
  /** Show-safe mode: no dragging, no inline edits, no destructive actions. */
  locked?: boolean
  /**
   * A running show is unlocked. Tints the navigation row with the header, bar and tab strip above
   * it — distinct from `locked`, because a *stopped* show is unlocked too and warrants no warning.
   */
  unlockedWarning?: boolean
  /**
   * Whether a cue's card is open. A predicate rather than an id because two cards can be: the one
   * the operator opened (`?cue=`) and the one on stage, which is derived — see `useCueExpansion`.
   */
  isExpanded: (cueId: number) => boolean
  onToggleExpanded: (cueId: number) => void
  /** The cue named in the URL, if any — the one worth scrolling to. */
  openedCueId?: number | null
  onBack: () => void
  /**
   * Record the programmer into a new cue in this stack — what replaced "Add Cue".
   *
   * Optional so the surface still renders where nothing can record (a stack list read while the
   * programmer sheets are not mounted); the button disables itself rather than vanishing, so the
   * route in is still discoverable.
   */
  onRecordIntoStack?: (stackId: number) => void
  onAddMarker: () => void
  onMarkerRename: (cueId: number, name: string) => void
  onMarkerDelete: (cueId: number) => void
  onDuplicate?: (cue: Cue) => void
  /** Record the programmer into this cue — opens the Record sheet targeting it. */
  onRecordInto?: (cueId: number) => void
  /** Load this cue into the programmer to edit it on stage. */
  onIncludeCue?: (cueId: number) => void
  includePending?: boolean
  /**
   * Cards (the inline-expanding cue cards, draggable while unlocked) or the cue sheet
   * (`CueSheet`, CLAUDE.md §Sheet kit). A sibling route plus a sticky preference, exactly the
   * Fixtures / Groups wiring — see `ShowPage`.
   */
  view?: CardsListView
  /** Open a cue's card on the cards view — the sheet's read-outs do this. */
  onOpenCue?: (cueId: number) => void
  /** Open the Prompt Book at a cue's anchor — the cue sheet's Book column. */
  onOpenBook?: (cueId: number) => void
  /** Lift the show-editing lock, where it is the operator's to lift. */
  onRequestUnlock?: () => void
}

export function StackDetail({
  stack,
  projectId,
  activeCueId,
  standbyCueId,
  fadeStackId,
  completedCueIds,
  locationByCue,
  onSetStandby,
  locked = false,
  unlockedWarning = false,
  isExpanded,
  onToggleExpanded,
  openedCueId,
  onBack,
  onRecordIntoStack,
  onAddMarker,
  onMarkerRename,
  onMarkerDelete,
  onDuplicate,
  onRecordInto,
  onIncludeCue,
  includePending,
  view = 'cards',
  onOpenCue,
  onOpenBook,
  onRequestUnlock,
}: StackDetailProps) {
  const [reorderCues] = useReorderCueStackCuesMutation()
  const [sortByCueNumber] = useSortCueStackByCueNumberMutation()

  // Offer to fix the order when a cue-number group descends against itself. Dismissal is scoped
  // to the stack so drilling into another one asks again.
  const outOfOrder = useMemo(() => detectOutOfOrder(stack.cues), [stack.cues])
  const [dismissedFor, setDismissedFor] = useState<number | null>(null)
  const showOutOfOrder = outOfOrder && dismissedFor !== stack.id

  // Scroll the active cue into view when drilling in or when the active cue
  // changes for this stack.
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (activeCueId == null || !listRef.current) return
    const row = listRef.current.querySelector(`[data-cue-row="${activeCueId}"]`)
    if (row instanceof HTMLElement) {
      row.scrollIntoView({ block: 'nearest' })
    }
  }, [stack.id, activeCueId])

  // Scroll a card the operator just opened into view. Keyed on the *addressed* cue rather than on
  // every open card: the derived live card opens on its own as the show advances, and scrolling to
  // it would drag the operator off whatever they were reading.
  useEffect(() => {
    if (openedCueId == null || !listRef.current) return
    const row = listRef.current.querySelector(`[data-cue-row="${openedCueId}"]`)
    if (row instanceof HTMLElement) {
      row.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  }, [openedCueId])

  const standardCount = useMemo(
    () => stack.cues.filter((c) => c.cueType === 'STANDARD').length,
    [stack.cues],
  )

  // Hoisted once instead of `completedCueIds.includes(cue.id)` per row — O(n) → O(1) per row,
  // the same fix FS-PERF-MOBILE-SHEET-FADE applied on the phone sheet for the same reason.
  const completedSet = useMemo(() => new Set(completedCueIds), [completedCueIds])

  // Number-column width for every row in this stack. MARKERs are unnumbered dividers, so they
  // must not drag the column down to the "—" placeholder width.
  const cueNumChars = useMemo(
    () =>
      cueNumberColumnChars(
        stack.cues.filter((c) => c.cueType === 'STANDARD').map((c) => c.cueNumber),
      ),
    [stack.cues],
  )

  // dnd-kit sensors
  // `DndContext` stays mounted in both modes: `useSortable` needs its ancestor, and unmounting it
  // to disable dragging breaks every row. The disabling happens per-row via dnd-kit's own
  // `disabled`, which makes a drag genuinely impossible rather than merely discouraged.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event
      if (!over || active.id === over.id) return

      const oldIndex = stack.cues.findIndex((c) => c.id === active.id)
      const newIndex = stack.cues.findIndex((c) => c.id === over.id)
      if (oldIndex === -1 || newIndex === -1) return

      const reordered = arrayMove(stack.cues, oldIndex, newIndex)
      reorderCues({
        projectId,
        stackId: stack.id,
        cueIds: reordered.map((c) => c.id),
      })
    },
    [stack, projectId, reorderCues],
  )

  /** The cue sheet's drag, on the cards view's own mutation — one reorder, two surfaces. */
  const handleSheetReorder = useCallback(
    (cueIds: number[]) => reorderCues({ projectId, stackId: stack.id, cueIds }),
    [projectId, reorderCues, stack.id],
  )

  return (
    <div className="@container flex-1 flex flex-col overflow-hidden">
      {/* Header — controls drop their text labels progressively as the content area narrows
          (container-query, sidebar-aware); everything stays reachable as an icon button down
          to phone widths. */}
      {/* The shell's 48px header row (CLAUDE.md §List shell) — the 12px gutter every list row
          has; this one was `px-4` alone. */}
      <SheetPage.Header
        className={cn('gap-3 transition-colors', unlockedWarning && UNLOCKED_WARNING_CLASS)}
      >
        <Button
          variant="outline"
          size="sm"
          onClick={onBack}
          className="font-bold tracking-wider"
          aria-label="Back to stacks"
        >
          <ArrowLeft className="size-3.5" />
          <span className="ml-1.5 hidden @[520px]:inline">Stacks</span>
        </Button>
        <span className="text-sm font-semibold text-foreground truncate min-w-0">
          {stack.name}
        </span>
        <span className="text-xs text-muted-foreground shrink-0">
          {standardCount} cues
        </span>
        <div className="flex-1" />
        <ShowStackViewSwitcher current={view} projectId={projectId} stackId={stack.id} />

        {/* **Recording is the only way in.** "Add Cue" made an empty cue and opened an editor
            on it, which is backwards: a cue is a captured state, so the thing that makes one is the
            state you captured. An empty cue was a container asking to be filled by hand, which is
            what the three-pane editor existed to do — and it is gone.

            Separators keep their button, and so do stacks: neither is a captured state. That is the
            line, not "no new buttons". */}
        {!locked && (
          <>
            <Button
              variant="outline"
              size="sm"
              onClick={() => onRecordIntoStack?.(stack.id)}
              disabled={!onRecordIntoStack}
              aria-label={`Record the programmer into ${stack.name}`}
              title="Record what the programmer is holding as a new cue in this stack"
            >
              <Zap className="size-3.5" />
              <span className="ml-1.5 hidden @[600px]:inline">Record into {stack.name}</span>
            </Button>
            <Button variant="outline" size="sm" onClick={onAddMarker} aria-label="Add separator">
              <SeparatorHorizontal className="size-3.5" />
              <span className="ml-1.5 hidden @[600px]:inline">Separator</span>
            </Button>
          </>
        )}
      </SheetPage.Header>

      {showOutOfOrder && !locked && (
        <OutOfOrderBanner
          onFixOrder={() => sortByCueNumber({ projectId, stackId: stack.id })}
          onDismiss={() => setDismissedFor(stack.id)}
        />
      )}

      {view === 'list' ? (
        <CueSheet
          stack={stack}
          projectId={projectId}
          activeCueId={activeCueId}
          standbyCueId={standbyCueId}
          completedCueIds={completedCueIds}
          locationByCue={locationByCue}
          onSetStandby={onSetStandby}
          locked={locked}
          openedCueId={openedCueId}
          onOpenCue={(cueId) => onOpenCue?.(cueId)}
          onOpenBook={onOpenBook}
          onRequestUnlock={onRequestUnlock}
          onReorder={handleSheetReorder}
        />
      ) : (
      /* Cue list — scrolls within the recessed Row 4 surface set on the root above. */
      <div
        ref={listRef}
        className="flex-1 overflow-y-auto py-1"
        style={{ '--cue-num-chars': cueNumChars } as CSSProperties}
      >
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragEnd={handleDragEnd}
        >
          <SortableContext
            items={stack.cues.map((c) => c.id)}
            strategy={verticalListSortingStrategy}
          >
            {stack.cues.map((cue) => {
              if (cue.cueType === 'MARKER') {
                return (
                  <ShowMarkerRow
                    key={cue.id}
                    id={cue.id}
                    name={cue.name}
                    onRename={(name) => onMarkerRename(cue.id, name)}
                    onDelete={() => onMarkerDelete(cue.id)}
                    locked={locked}
                  />
                )
              }
              return (
                <CueCardEditor
                  key={cue.id}
                  cue={cue}
                  projectId={projectId}
                  expanded={isExpanded(cue.id)}
                  onToggleExpanded={() => onToggleExpanded(cue.id)}
                  isActive={cue.id === activeCueId}
                  isStandby={cue.id === standbyCueId}
                  isDone={completedSet.has(cue.id)}
                  fadeStackId={fadeStackId}
                  location={locationByCue?.get(cue.id) ?? null}
                  onSetStandby={onSetStandby ? () => onSetStandby(cue.id) : undefined}
                  locked={locked}
                  onDuplicate={onDuplicate}
                  onRecordInto={onRecordInto}
                  onIncludeCue={onIncludeCue}
                  includePending={includePending}
                />
              )
            })}
          </SortableContext>
        </DndContext>
      </div>
      )}
    </div>
  )
}
