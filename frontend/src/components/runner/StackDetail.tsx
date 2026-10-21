import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { ArrowLeft, Pencil, SeparatorHorizontal, Zap } from 'lucide-react'
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
import { CueCardEditor } from './CueCardEditor'
import { cuePlacesByCue } from '@/lib/cuePlaces'
import { ShowMarkerRow } from './ShowMarkerRow'
import { OutOfOrderBanner } from '@/components/runner/OutOfOrderBanner'
import { cueNumberColumnChars, detectOutOfOrder } from '@/lib/cueNumber'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import { ShowStackViewSwitcher, TIMELINE_MIN_WIDTH, effectiveStackView, type CardsListView } from '@/components/ViewSwitcher'
import { useContainerBand } from '@/hooks/useContainerBand'
import { CueSheet } from './CueSheet'
import { StackTimeline } from './StackTimeline'
import { OwnerEditor } from '@/components/stage/OwnerEditor'

const TIMELINE_WIDTH_FLOORS = [TIMELINE_MIN_WIDTH, 0] as const
/** `useContainerBand`'s band before the first measurement: no band of the floors. */
const UNMEASURED = -1

interface StackDetailProps {
  stack: CueStack
  /** The project's stacks, for the shared chip. */
  stacks?: CueStack[]
  projectId: number
  /** The live stack's live and armed entries — a row is an entry, and a cue may be twice in one stack. */
  activeEntryId: number | null
  /** Cue queued to fire on the next GO. Only meaningful when drilled into the active stack. */
  standbyEntryId?: number | null
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
  onSetStandby?: (cueId: number, entryId: number) => void
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
  isExpanded: (entryId: number) => boolean
  onToggleExpanded: (entryId: number) => void
  /** The cue named in the URL, if any — the one worth scrolling to. */
  openedEntryId?: number | null
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
  /** A marker is an entry, so both name the marker's entry id. */
  onMarkerRename: (entryId: number, name: string) => void
  onMarkerDelete: (entryId: number) => void
  /**
   * Cards (the inline-expanding cue cards, draggable while unlocked), the cue sheet (`CueSheet`,
   * CLAUDE.md §Sheet kit) or the Timeline (`StackTimeline`). Sibling routes plus a sticky
   * preference, the Fixtures / Groups wiring — see `ShowPage`. The Timeline draws as the Table
   * where the stack is narrower than `TIMELINE_MIN_WIDTH` (C6).
   */
  view?: CardsListView
  /** Open the Prompt Book at a cue's anchor — the cue sheet's Book column. */
  onOpenBook?: (cueId: number) => void
  /** Lift the show-editing lock, where it is the operator's to lift. */
  onRequestUnlock?: () => void
}

export function StackDetail({
  stack,
  stacks,
  projectId,
  activeEntryId,
  standbyEntryId,
  fadeStackId,
  completedCueIds,
  locationByCue,
  onSetStandby,
  locked = false,
  unlockedWarning = false,
  isExpanded,
  onToggleExpanded,
  openedEntryId,
  onBack,
  onRecordIntoStack,
  onAddMarker,
  onMarkerRename,
  onMarkerDelete,
  view = 'cards',
  onOpenBook,
  onRequestUnlock,
}: StackDetailProps) {
  const [reorderCues] = useReorderCueStackCuesMutation()
  // Band 0 is room for the Timeline. Until the stack is measured neither view of a remembered
  // Timeline is drawn, so a narrow desk never mounts the grid (and its read) it is about to drop.
  const [widthRef, widthBand] = useContainerBand(TIMELINE_WIDTH_FLOORS, UNMEASURED)
  const timelineOffered = widthBand === 0
  const shownView = effectiveStackView(view, timelineOffered)
  const awaitingWidth = view === 'timeline' && widthBand === UNMEASURED
  /** *Stack settings…* open — the stack form, the set's door from inside the stack (D13). */
  const [settingsOpen, setSettingsOpen] = useState(false)
  // A GO that re-locks the show closes the form rather than holding it for the unlock.
  useEffect(() => {
    if (locked) setSettingsOpen(false)
  }, [locked])
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
    if (activeEntryId == null || !listRef.current) return
    const row = listRef.current.querySelector(`[data-entry-row="${activeEntryId}"]`)
    if (row instanceof HTMLElement) {
      row.scrollIntoView({ block: 'nearest' })
    }
  }, [stack.id, activeEntryId])

  // Scroll a card the operator just opened into view. Keyed on the *addressed* cue rather than on
  // every open card: the derived live card opens on its own as the show advances, and scrolling to
  // it would drag the operator off whatever they were reading.
  useEffect(() => {
    if (openedEntryId == null || !listRef.current) return
    const row = listRef.current.querySelector(`[data-entry-row="${openedEntryId}"]`)
    if (row instanceof HTMLElement) {
      row.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  }, [openedEntryId])

  const placesByCue = useMemo(() => cuePlacesByCue(stacks ?? [stack]), [stacks, stack])

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

      // Rows are entries, sorted by entry id — a marker has no cue id, and the two id spaces
      // overlap.
      const oldIndex = stack.cues.findIndex((c) => c.entryId === active.id)
      const newIndex = stack.cues.findIndex((c) => c.entryId === over.id)
      if (oldIndex === -1 || newIndex === -1) return

      const reordered = arrayMove(stack.cues, oldIndex, newIndex)
      reorderCues({
        projectId,
        stackId: stack.id,
        entryIds: reordered.map((c) => c.entryId),
      })
    },
    [stack, projectId, reorderCues],
  )

  /** The cue sheet's drag, on the cards view's own mutation — one reorder, two surfaces. */
  const handleSheetReorder = useCallback(
    (entryIds: number[]) => reorderCues({ projectId, stackId: stack.id, entryIds }),
    [projectId, reorderCues, stack.id],
  )

  return (
    <div ref={widthRef} className="@container flex-1 flex flex-col overflow-hidden">
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
        <ShowStackViewSwitcher current={shownView} projectId={projectId} stackId={stack.id} timelineOffered={timelineOffered} />

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
            {/* **The stack's settings — its set among them — from inside the stack**
                (scenery-programmer plan D13), where the stack list's row menu is the only other
                door. Shown where that menu shows it, unlocked; the same form, mounted in place. */}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setSettingsOpen(true)}
              aria-label="Stack settings…"
              title="Name, loop and the set — the scenery held while this stack is live"
            >
              <Pencil className="size-3.5" />
              <span className="ml-1.5 hidden @[600px]:inline">Stack settings…</span>
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

      {awaitingWidth ? null : shownView === 'timeline' ? (
        <StackTimeline
          stack={stack}
          projectId={projectId}
          activeEntryId={activeEntryId}
          standbyEntryId={standbyEntryId}
          fadeStackId={fadeStackId}
          onSetStandby={onSetStandby}
          locked={locked}
          openedEntryId={openedEntryId}
          placesByCue={placesByCue}
          onRequestUnlock={onRequestUnlock}
          onReorder={handleSheetReorder}
        />
      ) : shownView === 'list' ? (
        <CueSheet
          stack={stack}
          projectId={projectId}
          activeEntryId={activeEntryId}
          standbyEntryId={standbyEntryId}
          completedCueIds={completedCueIds}
          locationByCue={locationByCue}
          onSetStandby={onSetStandby}
          locked={locked}
          openedEntryId={openedEntryId}
          placesByCue={placesByCue}
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
            items={stack.cues.map((c) => c.entryId)}
            strategy={verticalListSortingStrategy}
          >
            {stack.cues.map((cue) => {
              if (cue.cueType === 'MARKER') {
                return (
                  <ShowMarkerRow
                    key={`entry-${cue.entryId}`}
                    id={cue.entryId}
                    name={cue.name}
                    onRename={(name) => onMarkerRename(cue.entryId, name)}
                    onDelete={() => onMarkerDelete(cue.entryId)}
                    locked={locked}
                  />
                )
              }
              return (
                <CueCardEditor
                  key={`entry-${cue.entryId}`}
                  cue={cue}
                  stackId={stack.id}
                  projectId={projectId}
                  places={placesByCue.get(cue.id)}
                  expanded={isExpanded(cue.entryId)}
                  onToggleExpanded={() => onToggleExpanded(cue.entryId)}
                  isActive={cue.entryId === activeEntryId}
                  isStandby={cue.entryId === standbyEntryId}
                  isDone={completedSet.has(cue.id)}
                  fadeStackId={fadeStackId}
                  location={locationByCue?.get(cue.id) ?? null}
                  onSetStandby={onSetStandby ? () => onSetStandby(cue.id, cue.entryId) : undefined}
                  locked={locked}
                  onRequestUnlock={onRequestUnlock}
                />
              )
            })}
          </SortableContext>
        </DndContext>
      </div>
      )}
      {/* Mounted only while open: the stack form reads the store, and a closed one is nothing. */}
      {settingsOpen && !locked && (
        <OwnerEditor projectId={projectId} entry={{ kind: 'set', id: stack.id }} onClose={() => setSettingsOpen(false)} />
      )}
    </div>
  )
}
