import { memo, useCallback, useMemo } from 'react'
import {
  useCreateProjectCueMutation,
  useDeleteProjectCueMutation,
  usePatchProjectCueMutation,
  useProjectCueListQuery,
} from '@/store/cues'
import type { CueStack } from '@/api/cueStacksApi'
import type { Cue } from '@/api/cuesApi'
import { StackDetail } from './StackDetail'
import { ShowOverview } from './ShowOverview'
import { nextAvailableName } from '@/lib/cueUtils'

interface ShowViewProps {
  projectId: number
  stacks: CueStack[]
  drillStackId: number | null
  onDrillStack: (id: number | null) => void
  activeStackId: number | null
  activeCueId: number | null
  /**
   * Cue armed for the next GO. Only meaningful in the live stack, and only reached the row once
   * session 2b unified the transport — `StackDetail` has accepted this and `CueCardEditor` has
   * drawn a blue "next" accent from it since 2a, but nothing supplied it, so the whole affordance
   * was unreachable in Show while Run had it.
   */
  standbyCueId?: number | null
  /** The playhead's stack id — rows read their own fade from it. See `useCueFade`. */
  fadeStackId?: number | null
  /** Cues this session has already run, for the "played" tick. */
  completedCueIds?: number[]
  /** Prompt-book reading position per cue. */
  locationByCue?: Map<number, string>
  /** Arm a cue as the next GO. */
  onSetStandby?: (cueId: number) => void
  /** Show-safe mode: no dragging, no inline edits, no destructive actions. */
  locked?: boolean
  /** A running show is unlocked — tints the header row with the chrome above it. */
  unlockedWarning?: boolean
  /** Whether a cue's card is open — a predicate, because two can be. See `useCueExpansion`. */
  isExpanded: (cueId: number) => boolean
  onToggleExpanded: (cueId: number) => void
  /** The cue named in the URL, if any. */
  openedCueId?: number | null
  onDuplicate?: (cue: Cue) => void
  /** Record the programmer into this cue — opens the Record sheet targeting it. */
  onRecordInto?: (cueId: number) => void
  /** Load this cue into the programmer to edit it on stage. */
  onIncludeCue?: (cueId: number) => void
  /** Record the programmer into a new cue in this stack — what replaced "Add Cue". */
  onRecordIntoStack?: (stackId: number) => void
  includePending?: boolean
  /** Cards or the cue sheet, for the drilled stack. See `StackDetail`. */
  view?: 'cards' | 'list'
  /** Open a cue's card on the cards view. */
  onOpenCue?: (cueId: number) => void
  onOpenBook?: (cueId: number) => void
  onRequestUnlock?: () => void
}

// Memoized: `ShowPage` subscribes to the runner slice (via `useShowTransport` for the Row 3 show
// bar), so it re-renders on every fade frame. Its props are stable during a fade — `activeCueId` is
// the server cursor, not the optimistic fade cursor — so memo keeps the whole editor subtree from
// reconciling ~60x/sec while a cue fades.
//
// That is why no fade *value* is a prop here, only `fadeStackId`. Session 2b needed a fade
// countdown on the row and passing one through would have re-rendered the whole list every frame
// with this memo still in place, looking like it was working. Each row reads its own instead — see
// `useCueFade`.
export const ShowView = memo(function ShowView({
  projectId,
  stacks,
  drillStackId,
  onDrillStack,
  activeStackId,
  activeCueId,
  standbyCueId,
  fadeStackId,
  completedCueIds,
  locationByCue,
  onSetStandby,
  locked,
  unlockedWarning,
  isExpanded,
  onToggleExpanded,
  openedCueId,
  onDuplicate,
  onRecordInto,
  onIncludeCue,
  onRecordIntoStack,
  includePending,
  view,
  onOpenCue,
  onOpenBook,
  onRequestUnlock,
}: ShowViewProps) {
  const [createCue] = useCreateProjectCueMutation()
  const [deleteCue] = useDeleteProjectCueMutation()
  const [patchCue] = usePatchProjectCueMutation()
  const { data: allCues } = useProjectCueListQuery(projectId)

  const drillStack = drillStackId != null ? stacks.find((s) => s.id === drillStackId) : null

  const existingCueNames = useMemo(
    () => new Set((allCues ?? []).map((c) => c.name)),
    [allCues],
  )

  const handleAddMarker = useCallback(async () => {
    if (drillStackId == null) return
    try {
      await createCue({
        projectId,
        name: nextAvailableName('New Separator', existingCueNames),
        layers: [],
        adHocEffects: [],
        cueStackId: drillStackId,
        cueType: 'MARKER',
      }).unwrap()
    } catch {
      // Reported by errorToastMiddleware; caught here only to stop the unhandled rejection.
    }
  }, [drillStackId, projectId, createCue, existingCueNames])

  const handleMarkerRename = useCallback(
    (cueId: number, name: string) => {
      // PATCH so we only touch the name; PUT would wipe children and risk
      // reverting the cueType to the NewCue default.
      patchCue({ projectId, cueId, name })
    },
    [projectId, patchCue],
  )

  const handleMarkerDelete = useCallback(
    (cueId: number) => {
      // In-stack separators are just MARKER cues — deleting one removes the cue.
      deleteCue({ projectId, cueId })
    },
    [projectId, deleteCue],
  )

  if (drillStack) {
    return (
      <StackDetail
        stack={drillStack}
        projectId={projectId}
        activeCueId={drillStackId === activeStackId ? activeCueId : null}
        standbyCueId={drillStackId === activeStackId ? standbyCueId : null}
        // Null unless this *is* the live stack: a stack being read has no fade of its own, and the
        // running stack's runner says nothing about these rows.
        fadeStackId={drillStackId === activeStackId ? fadeStackId : null}
        completedCueIds={drillStackId === activeStackId ? completedCueIds : undefined}
        locationByCue={locationByCue}
        // Gated for the same reason as the three above, and it is the one that bites: `setStandby`
        // arms against the *playhead's* stack, so offering it on a stack that is merely being read
        // would let one click replace the live stack's armed cue with a cue that is not in it — the
        // exact "a stray click changes the show" the browse/arm split exists to prevent.
        onSetStandby={drillStackId === activeStackId ? onSetStandby : undefined}
        locked={locked}
        unlockedWarning={unlockedWarning}
        isExpanded={isExpanded}
        onToggleExpanded={onToggleExpanded}
        openedCueId={openedCueId}
        onBack={() => onDrillStack(null)}
        onRecordIntoStack={onRecordIntoStack}
        onAddMarker={handleAddMarker}
        onMarkerRename={handleMarkerRename}
        onMarkerDelete={handleMarkerDelete}
        onDuplicate={onDuplicate}
        onRecordInto={onRecordInto}
                  onIncludeCue={onIncludeCue}
        includePending={includePending}
        view={view}
        onOpenCue={onOpenCue}
        onOpenBook={onOpenBook}
        onRequestUnlock={onRequestUnlock}
      />
    )
  }

  // Overview — the project's ordered stacks + separators.
  return (
    <ShowOverview
      projectId={projectId}
      stacks={stacks}
      activeStackId={activeStackId}
      onDrillStack={(id) => onDrillStack(id)}
      locked={locked}
      unlockedWarning={unlockedWarning}
    />
  )
})
