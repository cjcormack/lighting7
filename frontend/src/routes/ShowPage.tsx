import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useNavigate, Navigate, useSearchParams, useLocation } from 'react-router'
import { SheetPage } from '@/components/sheet/SheetPage'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useProjectCueStackListQuery } from '../store/cueStacks'
import { useCreateProjectCueMutation } from '../store/cues'
import { useProjectProgramStateQuery } from '../store/cueStacks'
import type { Cue } from '../api/cuesApi'
import { buildCueInput } from '../lib/cueUtils'
import { useShowBarProps } from '../hooks/useShowBarProps'
import { useEditLock } from '../hooks/useEditLock'
import { useCueExpansion } from '../hooks/useCueExpansion'
import { useTransportKeys } from '../hooks/useTransportKeys'
import { useNarrowContainer } from '../hooks/useContainerBand'
import { StackTabStrip } from '../components/runner/StackTabStrip'
import { OffPlayheadBanner } from '../components/runner/OffPlayheadBanner'
import { RunMobile } from '../components/runner/mobile/RunMobile'
import { useCueLocationLabels, useRunnerDisplay } from '../hooks/useRunnerDisplay'
import { useMakeStackLive } from '../hooks/useMakeStackLive'
import { ShowLockControl } from '../components/runner/ShowLockControl'
import { ShowHeader } from '../components/ShowHeader'
import { ImmersiveEscape } from '@/components/ImmersiveEscape'
import { ShowBar } from '../components/ShowBar'
import { ShowView } from '../components/runner/ShowView'
import { RecordSheet } from '../components/programmer/RecordSheet'
import { useInclude } from '../components/programmer/useInclude'
import type { CueStack } from '../api/cueStacksApi'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'
import {
  CARDS_LINK_STATE,
  SHOW_VIEW_KEY,
  setStoredCardsListView,
  stickyRedirectsToList,
  type CardsListView,
} from '../components/ViewSwitcher'

/** Below this container width the view becomes the phone runner, which is always locked. */
const MOBILE_RUNNER_THRESHOLD = 600

/** Stable no-op for the phone runner's requeue while it is reading a stack off the playhead. */
const NO_REQUEUE = () => {}

/**
 * The location state that says "the operator asked for the stack list" — set by the Stacks button
 * and by the breadcrumb, and read by the auto-drill so it stands aside. It rides the *location*
 * rather than a ref because the three show routes each carry their own `element`, so leaving a
 * stack remounts this component; see the effect that reads it.
 */
const STACK_LIST_STATE = { showStacks: true } as const

function isStackListRequest(state: unknown): boolean {
  return typeof state === 'object' && state !== null && (state as { showStacks?: unknown }).showStacks === true
}

/** The cue's display name for the Record sheet's header, or undefined if it has vanished. */
function cueNameFor(stacks: CueStack[] | undefined, cueId: number): string | undefined {
  for (const stack of stacks ?? []) {
    const cue = stack.cues?.find((c) => c.id === cueId)
    if (cue) return cue.name
  }
  return undefined
}

export function ShowRedirect() {
  return <CurrentProjectRedirect to="show" />
}

/**
 * `stackView` is which of the drilled stack's two views this route draws: the cue cards, or the
 * cue sheet at `/show/stacks/:stackId/table` (CLAUDE.md §Sheet kit). Wired exactly like
 * `/fixtures/list`: the table route writes the sticky preference on mount, and the cards route
 * redirects to it when the sticky says so — carrying `?cue=`, which is an external contract.
 */
export function ShowPage({ stackView = 'cards' }: { stackView?: CardsListView } = {}) {
  const { projectId, stackId } = useParams()
  const projectIdNum = Number(projectId)
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const location = useLocation()

  // Record the sheet as the last-used stack view even when arriving by deep link, so the next
  // drill into a stack lands here.
  useEffect(() => {
    if (stackView === 'list') setStoredCardsListView(SHOW_VIEW_KEY, 'list')
  }, [stackView])

  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const { data: stacks, isLoading: stacksLoading, isFetching: stacksFetching } =
    useProjectCueStackListQuery(projectIdNum)
  const { data: programState } = useProjectProgramStateQuery(projectIdNum)

  const isShowActive = programState?.activeStackId != null
  const activeStackId = programState?.activeStackId ?? null
  const activeStack = useMemo(
    () => (activeStackId != null ? stacks?.find((s) => s.id === activeStackId) : undefined),
    [stacks, activeStackId],
  )

  // ── URL-derived navigation state ──
  // The drilled stack lives in the path (`/show/stacks/:stackId`); the inline-expanded cue is a
  // transient `?cue=` modifier. This mirrors how the (removed) FX Cues view derived its view from
  // the URL, and makes both deep-linkable / refresh-stable.
  const drillStackId = stackId ? Number(stackId) : null
  const drillStack = useMemo(
    () => (drillStackId != null ? stacks?.find((s) => s.id === drillStackId) : null),
    [stacks, drillStackId],
  )
  const cueParam = searchParams.get('cue')
  /** The cue the operator addressed. `?cue=` is an external contract — the Prompt Book mints it. */
  const openedCueId = cueParam ? Number(cueParam) : null

  /**
   * The show-editing lock, shared with the Prompt Book. This is what replaced `/run`: the two views
   * were never different destinations, only different answers to "can a stray click change the
   * show" — which is a mode, and one the Prompt Book already modelled well.
   *
   * `canEdit` comes off the playhead query rather than being assumed: the backend computes it as
   * "is this the current project", and a project that is not current cannot be edited at all.
   *
   * Declared *above* the transport because the transport takes `noteGo` — see below.
   */
  const canEdit = programState?.canEdit ?? false
  const editLock = useEditLock({ canEdit, isShowActive })

  // Row 3 (show bar) — a functional transport here, without Run's keyboard shortcuts. Shown at every
  // width (it collapses responsively) whenever the show is running. Shared with the Programmer view,
  // which mounts the same bar from the same wiring.
  // Start/Stop comes from the same hook as the bar rather than being re-derived here: it was the
  // same three lines off the same state, and a second copy is a copy that drifts.
  // `onBeforeGo: noteGo` is what makes GO end a fix-it session, on this surface as well as on the
  // Prompt Book — the two share one lock, so they have to agree about what GO does to it.
  // `frameRateProgress: false` — the desktop rows read their own fade via `ShowView`'s
  // `fadeStackId`, and the phone runner's Current card and cue-list sheet now do the same, so
  // this page never reads `transport.fadeProgress`/`fadeRemainMs`/`autoProgress`. The bar's
  // FADING badge is unaffected: it animates from the write-once `fade` descriptor.
  const { showBarProps, showHeaderProps, transport, nextStack, activeCue, standbyCue } =
    useShowBarProps(projectIdNum, { onBeforeGo: editLock.noteGo, frameRateProgress: false })
  const runnableStackCount = stacks?.filter((s) => s.type === 'STACK').length ?? 0

  // The phone layout is always locked, whatever the lock says — it is a running surface with no
  // room for the editing chrome, so there is nothing there for an unlocked state to reveal.
  const [bodyRef, isNarrow] = useNarrowContainer(MOBILE_RUNNER_THRESHOLD)
  const locked = editLock.locked || isNarrow
  /**
   * A *running* show that is unlocked — the state the chrome shouts about.
   *
   * Not the same as `!locked`: a stopped show is unlocked too, and there is nothing to be wrong
   * about there. Threaded to every bar of chrome rather than applied once at the top, because the
   * band is made of siblings — header, show bar, tab strip, navigation row — and tinting only some
   * of them reads as stripes rather than as one state.
   */
  const unlockedWarning = !locked && isShowActive

  /**
   * What the phone runner is showing, and whether that is the playhead.
   *
   * The phone falls back to the live stack when nothing is drilled, so "off the playhead" is a
   * question about the stack actually on screen rather than about `drillStackId`.
   */
  const phoneStack = drillStack ?? activeStack
  const phoneOffPlayhead = phoneStack != null && phoneStack.id !== activeStackId

  /**
   * Space/Backspace act only while locked. Unlocked means the operator is editing — inline cue
   * numbers, names and fades are all live text fields — and in an editing surface Space is a space.
   * `L` stays bound in both states, so there is always a keyboard way back to a safe desk.
   */
  useTransportKeys({
    enabled: locked && !showBarProps.goDisabled,
    onGo: transport.go,
    onBack: transport.back,
    onToggleLock: editLock.toggleLock,
  })

  // Per-cue prompt-book reading position ("top of p. 9"), shared with the busk view's Show tab.
  const locationByCue = useCueLocationLabels(projectIdNum)

  /**
   * Arm a cue as the next GO.
   *
   * Depends on `transport.setStandby` rather than on `transport`: the transport hook returns a fresh
   * object literal every render, so taking the whole thing as a dependency gives this callback a new
   * identity on every fade frame — which defeats `ShowView`'s memo, the one thing standing between
   * a fade and several hundred cue rows reconciling at frame rate.
   */
  const { setStandby } = transport
  const handleSetStandby = useCallback((cueId: number) => setStandby(cueId), [setStandby])

  // Only the phone branch below draws this; the hook memoises it, since this component re-renders
  // per fade frame. Shared with the busk view's Show tab, so the two cannot disagree about which
  // cue is next.
  const runnerDisplay = useRunnerDisplay({ transport, activeCue, standbyCue, nextStack })

  const handleSelectStack = useCallback(
    (target: CueStack) => {
      if (target.type !== 'STACK') return
      navigate(`/projects/${projectIdNum}/show/stacks/${target.id}`)
    },
    [navigate, projectIdNum],
  )

  // Move the playhead to the stack being read — `OffPlayheadBanner`'s *Make live*, shared with the
  // busk view's Show tab; the hook's docblock has the three things to know about the sequence.
  const handleMakeLive = useMakeStackLive(projectIdNum, activeStackId, transport)

  const [createCue] = useCreateProjectCueMutation()

  // Record replaces the old "Grab live state" button. Grab-live is still reachable — it is
  // `source: 'STAGE_SNAPSHOT'` in the Record sheet — but it is no longer the only way to get
  // the stage into a cue, and it was the lossy one.
  const { includeCue, isLoading: includePending } = useInclude(projectIdNum)
  const [recordCueId, setRecordCueId] = useState<number | null>(null)
  /**
   * Record into a *new* cue in this stack — what replaced "Add Cue".
   *
   * A separate piece of state from `recordCueId` rather than a union, because the two name ids from
   * different tables: a cue id and a stack id collide freely, and `RecordSheet` keys its draft on
   * which one it was given. Folding them together is exactly the bug `ProgrammerSheets` documents.
   */
  const [recordStackId, setRecordStackId] = useState<number | null>(null)

  // Set/clear the `?cue=` modifier without touching the stack path (replace: no history spam).
  const setExpandedCueId = useCallback(
    (cueId: number | null) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          if (cueId == null) next.delete('cue')
          else next.set('cue', String(cueId))
          return next
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )

  /**
   * Two cards are open at most: the one addressed by `?cue=`, and the one on stage — the latter
   * derived rather than stored, so a GO cannot take away the card the operator opened. Session 2b
   * replaced an auto-expand effect that *wrote* `?cue=` on drill, which conflated "what is live"
   * with "what I am reading" in one slot and let a GO overwrite the second with the first.
   */
  // `?cue=` holds one cue by contract, so the operator's slot is a one-element set and opening a
  // second cue replaces the first. Memoised, and the close is a memoised callback rather than an
  // inline arrow: an arrow would be a fresh identity every render, which would give
  // `toggleExpanded` one too and break `ShowView`'s memo mid-fade.
  const openCueIds = useMemo(
    () => (openedCueId != null ? new Set([openedCueId]) : new Set<number>()),
    [openedCueId],
  )
  const closeExpandedCue = useCallback(() => setExpandedCueId(null), [setExpandedCueId])
  const { isExpanded, toggleExpanded } = useCueExpansion({
    openCueIds,
    onOpen: setExpandedCueId,
    onClose: closeExpandedCue,
    liveCueId: drillStackId === activeStackId ? transport.serverActiveCueId : null,
    resetKey: drillStackId,
  })

  const handleDrillStack = useCallback(
    (id: number | null) => {
      if (id == null) navigate(`/projects/${projectIdNum}/show`, { state: STACK_LIST_STATE })
      else navigate(`/projects/${projectIdNum}/show/stacks/${id}`)
    },
    [navigate, projectIdNum],
  )

  const handleBreadcrumbCurrentPageClick = useCallback(() => {
    navigate(`/projects/${projectIdNum}/show`, { state: STACK_LIST_STATE })
  }, [navigate, projectIdNum])

  const initialDrillDoneRef = useRef(false)

  const handleDuplicate = useCallback(
    async (cue: Cue) => {
      if (drillStackId == null) return
      try {
        const input = buildCueInput(cue)
        input.name = cue.name + ' (copy)'
        input.cueNumber = null
        input.cueStackId = drillStackId
        const result = await createCue({ projectId: projectIdNum, ...input }).unwrap()
        setExpandedCueId(result.id)
      } catch {
        // Reported by errorToastMiddleware; caught here only to stop the unhandled rejection.
      }
    },
    [drillStackId, projectIdNum, createCue, setExpandedCueId],
  )

  const handleRecordInto = useCallback((cueId: number) => setRecordCueId(cueId), [])

  const handleRecordIntoStack = useCallback((stackId: number) => setRecordStackId(stackId), [])

  const handleIncludeCue = useCallback((cueId: number) => void includeCue(cueId), [includeCue])

  /**
   * The sheet's read-outs open a cue's *card*: the cards view with `?cue=` — tagged as the
   * switcher's own Cards click, so the sticky (which still says the sheet) does not bounce it
   * straight back. A peek at a card is not a change of view, so the sticky is left alone.
   */
  const handleOpenCue = useCallback(
    (cueId: number) => {
      if (drillStackId == null) return
      navigate(`/projects/${projectIdNum}/show/stacks/${drillStackId}?cue=${cueId}`, { state: CARDS_LINK_STATE })
    },
    [drillStackId, navigate, projectIdNum],
  )

  /**
   * The Book column's read-out opens the **Prompt Book** at that cue, not the cue's card. It is the
   * one read-out that names a place in another document — "top of p. 8" is an answer about the
   * book — so sending it to the card answered a different question from the one the column asks.
   * `?cue=` is the Prompt Book's own arrival contract, the mirror of the one it mints for Show.
   */
  const handleOpenBook = useCallback(
    (cueId: number) => {
      navigate(`/projects/${projectIdNum}/prompt-book?cue=${cueId}`)
    },
    [navigate, projectIdNum],
  )

  // ── Deep-link normalizer + auto-drill ──
  // - Legacy `/show?stack=X&cue=Y` links (the Prompt Book's "Edit cue" mints the path form now)
  //   are rewritten to
  //   the new path scheme `/show/stacks/X?cue=Y`.
  // - Otherwise, when the show is running, drill into the active stack on first mount so the
  //   operator lands where the action is.
  useEffect(() => {
    if (initialDrillDoneRef.current) return
    if (!stacks) return

    // **The operator asked for the stack list, so leave them on it.** The ref alone cannot say
    // that: `/show`, `/show/stacks/:id` and `/show/stacks/:id/table` are three sibling routes with
    // an `element` each, so going back from a stack *remounts* this component and resets the ref —
    // and the auto-drill below then put them straight back into the live stack, which is what made
    // the Stacks button look broken while a show was running. The signal has to ride the location,
    // which survives that remount; `handleDrillStack(null)` and the breadcrumb both set it.
    if (isStackListRequest(location.state)) {
      initialDrillDoneRef.current = true
      return
    }

    const legacyStack = searchParams.get('stack')
    if (legacyStack && drillStackId == null) {
      initialDrillDoneRef.current = true
      const sid = Number(legacyStack)
      if (Number.isFinite(sid) && stacks.some((s) => s.id === sid)) {
        const cue = searchParams.get('cue')
        navigate(
          `/projects/${projectIdNum}/show/stacks/${sid}${cue ? `?cue=${cue}` : ''}`,
          { replace: true },
        )
      } else {
        navigate(`/projects/${projectIdNum}/show`, { replace: true })
      }
      return
    }

    if (drillStackId == null && isShowActive && activeStackId != null) {
      initialDrillDoneRef.current = true
      navigate(`/projects/${projectIdNum}/show/stacks/${activeStackId}`, { replace: true })
    }
  }, [stacks, isShowActive, activeStackId, drillStackId, location.state, searchParams, navigate, projectIdNum])

  /**
   * Follow the playhead — but only while standing on it.
   *
   * A boundary GO moves the show into the next stack, and an operator who was watching it should go
   * too, or they are left reading the act that just finished. An operator who had deliberately
   * navigated elsewhere must *not* be yanked away mid-read, which is why this is conditional on the
   * drilled stack having been the old playhead.
   *
   * Deliberately a second effect rather than folded into the deep-link normalizer above. The plan
   * for this session proposed one rule with the first mount as its degenerate case; kept separate
   * because the normalizer also owns the legacy `?stack=` rewrite, which must run before any follow
   * navigation, and the two have genuinely different jobs: one lands you somewhere on arrival, this
   * one keeps you with the show afterwards.
   */
  const prevLiveStackRef = useRef<number | null | undefined>(undefined)
  useEffect(() => {
    if (!stacks) return
    const prevLive = prevLiveStackRef.current
    prevLiveStackRef.current = activeStackId
    // First resolution belongs to the normalizer above.
    if (prevLive === undefined || prevLive === activeStackId) return
    if (activeStackId == null || drillStackId !== prevLive) return
    navigate(`/projects/${projectIdNum}/show/stacks/${activeStackId}`, { replace: true })
  }, [stacks, activeStackId, drillStackId, navigate, projectIdNum])

  // Redirect away from a stale/unknown drilled stack (e.g. after deletion). Wait until the list has
  // settled — during the refetch that follows creating a stack, `stacks` briefly lacks the new
  // stack, and redirecting then would bounce the operator straight back out of it.
  useEffect(() => {
    if (
      drillStackId != null &&
      stacks &&
      !stacksFetching &&
      !stacks.some((s) => s.id === drillStackId)
    ) {
      navigate(`/projects/${projectIdNum}/show`, { replace: true })
    }
  }, [drillStackId, stacks, stacksFetching, navigate, projectIdNum])

  // Loading / redirect guards
  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    return <Navigate to={`/projects/${currentProject.id}/show`} replace />
  }

  // Sticky view: a drilled stack lands on the cue sheet once that has been chosen, unless this
  // arrival is the switcher's own Cards click. `?cue=` rides along — the Prompt Book mints it.
  // The phone is not exempted here, deliberately: `isNarrow` is measured from the body, which
  // this early return keeps from mounting, so a guard on it could never fire on a fresh load.
  // Nothing needs it — the phone branch below draws `RunMobile` whichever of the two URLs it is
  // on, since the sticky is a desk preference and the phone has no cue sheet.
  if (stackView === 'cards' && drillStackId != null && stickyRedirectsToList(location.state, SHOW_VIEW_KEY)) {
    return <Navigate to={`/projects/${projectIdNum}/show/stacks/${drillStackId}/table${location.search}`} replace />
  }

  // Loading and not-found keep the list's shape (CLAUDE.md §List shell) for both views — the
  // loaded page is the same full-height column whichever it draws.
  if (projectLoading || currentLoading || stacksLoading) {
    return (
      <SheetPage>
        <SheetPage.Header>
          <ImmersiveEscape bare />
        </SheetPage.Header>
        <SheetPage.Empty loading />
      </SheetPage>
    )
  }

  if (!project) {
    return (
      <SheetPage>
        <SheetPage.Header>
          <ImmersiveEscape bare />
        </SheetPage.Header>
        <SheetPage.Empty className="text-destructive">Project not found</SheetPage.Empty>
      </SheetPage>
    )
  }

  return (
    <div className="flex flex-col h-full">
      <ShowHeader
        view="show"
        projectId={projectIdNum}
        projectName={project.name}
        onCurrentPageClick={handleBreadcrumbCurrentPageClick}
        // Amber while a *running* show is unlocked — the state worth being unmistakable about,
        // matching the Prompt Book. A stopped show is simply editable, so there is nothing to warn
        // about and no wash.
        unlockedWarning={unlockedWarning}
        // The slot was left in place for "session 2b's merged view", which is this. The lock is the
        // one control that belongs beside the view switcher rather than in the page: it changes what
        // the whole view will accept.
        // Not shown at all where the backend would refuse the edit — unlike the Prompt Book, which
        // shows it inert. Offering an unlock that can only 4xx is worse than offering none, and
        // `ShowPage.test.tsx` pins it; `ShowLockControl`'s docblock describes the Prompt Book's arm.
        actions={
          editLock.lockRelevant && !isNarrow ? (
            <ShowLockControl
              locked={editLock.locked}
              onToggle={editLock.toggleLock}
              countdownSecondsLeft={editLock.countdownSecondsLeft}
              onStayUnlocked={editLock.stayUnlocked}
            />
          ) : undefined
        }
        {...showHeaderProps}
      />

      {/* Not gated on the show running. The bar carries blackout, the speed masters and the
          programmer chip — which is also where Blind is *reported* here; the press is the
          programmer's — all of which mean something with the show down, and gating it was what
          once made Blind's location depend on the show's state. `goDisabled` already mutes BACK/GO.
          Still hidden on the phone, where `RunMobile` brings its own transport footer. */}
      {!isNarrow && (
        <ShowBar
          {...showBarProps}
          // The one prop that legitimately differs per view: it advertises keys, and only the host
          // binding them can say whether they act. Show binds them while locked; the Programmer
          // does not bind them at all. Everything else about the bar is identical everywhere,
          // deliberately — it is the same chrome, so it should not read as three near-copies.
          showShortcuts={locked}
          unlockedWarning={unlockedWarning}
        />
      )}

      {isNarrow ? (
        <div ref={bodyRef} className="flex-1 flex flex-col min-w-0 overflow-hidden">
          {/* The phone has no tab strip, so without this the picker could leave the operator
              reading a stack GO does not act on with nothing whatsoever saying so — and no way to
              arm it, since the strip's old click-to-move-the-playhead is gone. */}
          {phoneStack && phoneOffPlayhead && (
            <OffPlayheadBanner
              liveStackName={activeStack?.name ?? null}
              selectedStackName={phoneStack.name}
              liveCueIsOnStage={transport.serverActiveCueId != null}
              onJumpToLive={() =>
                activeStackId != null &&
                navigate(`/projects/${projectIdNum}/show/stacks/${activeStackId}`)
              }
              onMakeLive={() => handleMakeLive(phoneStack)}
            />
          )}
          <RunMobile
            stacks={stacks ?? []}
            selectedStackId={drillStackId ?? activeStackId}
            stack={phoneStack}
            multiStack={runnableStackCount > 1}
            display={runnerDisplay}
            dbo={showBarProps.dbo}
            onGo={transport.go}
            onBack={transport.back}
            onDbo={showBarProps.onDbo}
            onSelectStack={handleSelectStack}
            // Inert off the playhead, for the reason the desktop rows are: `setStandby` arms the
            // *playhead's* stack, so a tap here would arm a cue that is not in it.
            onRequeueCue={phoneOffPlayhead ? NO_REQUEUE : handleSetStandby}
            projectId={projectIdNum}
            fadeStackId={activeStackId}
            activeLocation={activeCue ? locationByCue.get(activeCue.id) ?? null : null}
            standbyLocation={standbyCue ? locationByCue.get(standbyCue.id) ?? null : null}
          />
        </div>
      ) : (
        /* `noteEdit` on any interaction with the unlocked body is what keeps the idle re-lock from
           firing at an operator who is mid-edit — the editing here lives in a dozen handlers spread
           across the overview, the stack detail and the rows, so it is caught once at the boundary
           rather than threaded through every one of them. A no-op while locked (and while the show
           is stopped), so it costs nothing in the normal running state. */
        <div
          ref={bodyRef}
          className="flex-1 flex min-h-0"
          onPointerDownCapture={editLock.noteEdit}
          onKeyDownCapture={editLock.noteEdit}
        >
          <div className="flex-1 min-w-0 flex flex-col min-h-0">
            {/* The stack switcher, and the banner that exists because selecting a tab no longer
                moves the playhead. Both belong to the drill-down: the stack *list* is its own
                switcher, so neither has anything to say there. */}
            {drillStackId != null && (
              <StackTabStrip
                stacks={stacks ?? []}
                selectedStackId={drillStackId}
                liveStackId={activeStackId}
                runnableStackCount={runnableStackCount}
                onSelectStack={handleSelectStack}
                unlockedWarning={unlockedWarning}
              />
            )}
            {drillStack && drillStackId !== activeStackId && (
              <OffPlayheadBanner
                liveStackName={activeStack?.name ?? null}
                selectedStackName={drillStack.name}
                liveCueIsOnStage={transport.serverActiveCueId != null}
                onJumpToLive={() =>
                  activeStackId != null &&
                  navigate(`/projects/${projectIdNum}/show/stacks/${activeStackId}`)
                }
                onMakeLive={() => handleMakeLive(drillStack)}
              />
            )}
            <ShowView
              projectId={projectIdNum}
              stacks={stacks ?? []}
              drillStackId={drillStackId}
              onDrillStack={handleDrillStack}
              activeStackId={activeStackId}
              // The server cursor reflects what's on stage, not the transient
              // fade cursor — so the marker stays stable during fades.
              activeCueId={transport.serverActiveCueId}
              standbyCueId={transport.standbyCueId}
              fadeStackId={activeStackId}
              completedCueIds={transport.completedCueIds}
              locationByCue={locationByCue}
              onSetStandby={handleSetStandby}
              locked={locked}
              unlockedWarning={unlockedWarning}
              isExpanded={isExpanded}
              onToggleExpanded={toggleExpanded}
              openedCueId={openedCueId}
              onDuplicate={handleDuplicate}
              onRecordInto={handleRecordInto}
              onRecordIntoStack={handleRecordIntoStack}
              onIncludeCue={handleIncludeCue}
              includePending={includePending}
              view={stackView}
              onOpenCue={handleOpenCue}
              onOpenBook={handleOpenBook}
              // Withheld where the lock is not the operator's to lift — a project that is not
              // current cannot be edited at all, and offering an unlock that changes nothing is
              // worse than the disabled verbs, which at least say why.
              onRequestUnlock={editLock.lockRelevant ? editLock.toggleLock : undefined}
            />
          </div>
        </div>
      )}

      {recordCueId != null && (
        <RecordSheet
          open
          onOpenChange={(open) => !open && setRecordCueId(null)}
          projectId={projectIdNum}
          targetCueId={recordCueId}
          targetCueName={cueNameFor(stacks, recordCueId)}
        />
      )}
      {recordStackId != null && (
        <RecordSheet
          open
          onOpenChange={(open) => !open && setRecordStackId(null)}
          projectId={projectIdNum}
          defaultCueStackId={recordStackId}
        />
      )}
    </div>
  )
}
