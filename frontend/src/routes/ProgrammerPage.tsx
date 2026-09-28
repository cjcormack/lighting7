import { memo, useState } from 'react'
import { Navigate, useParams } from 'react-router'
import { SheetPage } from '@/components/sheet/SheetPage'
import { ShowHeader } from '@/components/ShowHeader'
import { ImmersiveEscape } from '@/components/ImmersiveEscape'
import { EditorContextProvider } from '@/components/programmer/EditorContext'
import { ProgrammerActionBar } from '@/components/programmer/ProgrammerActionBar'
import { useColumnVisibility } from '@/components/fixtures-list/ColumnsMenu'
import { createMarqueeStore, MarqueeStoreContext } from '@/components/fixtures-list/marqueeContext'
import { ProgrammerGrid } from '@/components/programmer/ProgrammerGrid'
import { LookRowStoreProvider } from '@/components/programmer/LookRowStore'
import { FocusedTemplateLayerProvider } from '@/components/programmer/FocusedTemplateLayer'
import { ProgrammerRail } from '@/components/programmer/ProgrammerRail'
import { ProgrammerScopeProvider } from '@/components/programmer/ProgrammerScope'
import {
  ProgrammerSheetsProvider,
  useProgrammerSheets,
} from '@/components/programmer/ProgrammerSheets'
import { ProgrammerSourceStrip } from '@/components/programmer/ProgrammerSourceStrip'
import { ProgrammerWorkspace } from '@/components/programmer/ProgrammerWorkspace'
import { useInclude } from '@/components/programmer/useInclude'
import { cn } from '@/lib/utils'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import { usePersistentState } from '@/hooks/usePersistentState'
import { useShowBarProps } from '@/hooks/useShowBarProps'
import { lightingApi } from '@/api/lightingApi'
import { includedCueId } from '@/lib/includedTarget'
import { programmerClearAll } from '@/store/programmer'
import { useCurrentProjectQuery, useProjectQuery } from '@/store/projects'
import { CurrentProjectRedirect } from '@/components/CurrentProjectRedirect'

const GROUPED_KEY = 'programmer.grouped'

/**
 * The short-height arm's threshold (space plan D8). A landscape phone is ~393px tall, and an app
 * header, a `ShowHeader` and two rows of chrome leave it four fixture rows and part of a fifth;
 * 500 is the artboard's number and the one `Layout` unsticks itself at, so the two surfaces fold
 * together rather than at two nearby numbers. (`ShowHeader` was a third until the chrome tidy-up
 * made it `py-2` at every height — see `shortViewport.test.ts`, which pins the sites that are
 * left.)
 *
 * That count was three until this page stopped drawing a `ShowBar` (see the note beside the
 * header below) — the band was ~60px of the same 393.
 */
const SHORT_VIEWPORT = '(max-height: 500px)'

/** Bare `/programmer` → the current project's programmer. Mirrors `ShowRedirect`. */
export function ProgrammerRedirect() {
  return <CurrentProjectRedirect to="programmer" />
}

/**
 * `/programmer/fx` → `/programmer`.
 *
 * FX was a route of its own when the FX sheet was a destination, then a tab, and is now a band of
 * the page that is always on screen. Kept only so a bookmark lands.
 */
export function ProgrammerFxRedirect() {
  const { projectId } = useParams()
  return <Navigate to={projectId ? `/projects/${projectId}/programmer` : '/programmer'} replace />
}

/**
 * The programmer, as a place.
 *
 * It was a page, then three tabs — Values / Layers / FX — of a pane collapsed inside the Show view,
 * and is a page again. The tabs were the problem: they are three readings of *one live object*, so
 * putting them behind a switcher meant the operator could never watch the layer stack that produced
 * the values they were editing. Everything is on screen at once here, which is the entire point.
 *
 * **One header and two rows of chrome, not a header, a show bar and six bands.** It shipped as six
 * deliberately separate siblings — source strip, action bar, scope band, filter row, template
 * strip, column header — each designed on its own, each spending a line on a label or a sentence,
 * and together 489px of a 900px screen before the first fixture, under 115px of `ShowHeader` and
 * `ShowBar` besides. The space plan's session 1 folds the six: **row A** is the noun and the verbs
 * (the source box on the left, Clear / Include / Record on the right, one 40px line), and **row B**
 * is what the grid shows (scope, filter, Lit, Groups, Columns) — which lives *inside*
 * `ProgrammerGrid`'s toolbar, because it describes the grid and has no business spanning the rail.
 * Nothing the labels and sentences said was deleted; every one of them is a `title` or an
 * `aria-label` now, and each component's doc comment says which.
 */
export function ProgrammerPage() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  // Only `showHeaderProps` is read: this page draws no `ShowBar` (see the note beside the header
  // below). The hook is still the right source for the header's Start/Stop — the same derivation
  // the other three views get, and hand-rolling it here is the drift `useShowBarProps` exists to
  // prevent.
  //
  // `frameRateProgress: false` mattered when this page mounted the hook for a bar it no longer
  // draws, and matters more now that nothing here reads the transport at all: without it a running
  // fade re-renders the whole page (and everything under `ProgrammerBody`) per rAF, exactly while
  // channel frames are also landing.
  const { showHeaderProps } = useShowBarProps(projectIdNum, {
    frameRateProgress: false,
  })

  // Loading and not-found keep the list's shape (CLAUDE.md §List shell): the same header row,
  // the body centred on the spinner or the sentence, so nothing changes shape when the grid
  // arrives.
  if (currentLoading || projectLoading) {
    return (
      <SheetPage>
        <SheetPage.Header>
          <ImmersiveEscape bare />
        </SheetPage.Header>
        <SheetPage.Empty loading />
      </SheetPage>
    )
  }

  if (!Number.isFinite(projectIdNum)) {
    return currentProject ? (
      <Navigate to={`/projects/${currentProject.id}/programmer`} replace />
    ) : null
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
    <ProgrammerSheetsProvider projectId={projectIdNum}>
      <div className="flex h-full flex-col">
        <ShowHeader
          view="programmer"
          projectId={projectIdNum}
          projectName={project.name}
          {...showHeaderProps}
        />
        {/* **No `ShowBar` here, and this is the only view without one.** The space plan's session 5
            asked whether `ShowHeader` should fold into the bar on all four live views; the answer
            taken at the desk was narrower and blunter — the *programmer* does not want the show
            chrome at all, and the other three keep both bands exactly as they were.

            The reasoning is the plan's own D1, applied to a band rather than to a row: everything
            above the grid earns its place by the line, and 60px of blackout, tempo, cue numbers
            and a transport is the largest thing on this page that is not about editing values. It
            is not lost, it is one pill away — the switcher in the header above reaches Show and
            the Prompt Book, which carry the full bar, and Busk, whose side sheet's Show tab is the
            same transport in the phone runner's shape.

            What genuinely goes with it, so nobody rediscovers it as a bug:

             - **Blind is here, and blackout is gone outright.** Blind is *not* show chrome: it is
               a programmer fact — `ProgrammerSummary.blind`, written by `programmerSetBlind`, faded
               by the programmer's own fade — and the desk pass that followed session 5 found a
               programmer you could not go blind on unliveable (`PD-BLIND-ON-PROGRAMMER`). So the
               one Blind control in the app is the action bar's, in row A's Stage zone beside Clear
               and the fade, and the bar draws no tile for any host; the other three views *report*
               it through `ProgrammerIndicator`, as the app header does here. Session 5's note used
               to refuse exactly this as "a second Blind toggle" — it is not second, because
               `useShowBarProps` supplies none. What must not happen is the reverse drift: a Blind
               tile creeping back into the bar for one host, which would put one control in two
               places again. Blackout is a genuine absence; it was confirmed unimportant here.
             - **GO and BACK are not on this page**, and the programmer binds no transport keys —
               `useTransportKeys` is Show's and the Prompt Book's. Busking from the grid means
               keeping Show or Busk on screen, or a MIDI surface.
             - **The speed masters are not resident on this page**, and `PD-SPEED-OVERLAY` did not
               change that: the bank is *summoned*, from the app header's Speed Masters overview
               panel, which hangs over every route and is nobody's view chrome. Nothing was added
               to this page for it. `ProgrammerFxList`'s own rows still name each effect's master,
               and `/speed-masters` still manages the bank. The panel's toggle persists app-wide,
               so an operator who opens it here has a tempo band on screen until they close it —
               by their own door, which is what makes it not session 5's band returning. */}
        <ProgrammerBody projectId={projectIdNum} />
      </div>
    </ProgrammerSheetsProvider>
  )
}

/**
 * Split from `ProgrammerPage` only so it sits *inside* `ProgrammerSheetsProvider` and can call
 * `useProgrammerSheets`.
 *
 * Memoized: its only prop is `projectId`, so this is the barrier that keeps `ProgrammerPage`'s own
 * re-renders (`useShowBarProps`, `useCurrentProjectQuery`, `useProjectQuery`) from cascading into
 * the whole grid/rail/scope subtree below it.
 */
const ProgrammerBody = memo(function ProgrammerBody({ projectId }: { projectId: number }) {
  const sheets = useProgrammerSheets()
  const { includeCue } = useInclude(projectId)
  // Grouping is a toggle rather than a route split: busking a whole wash wants group rows, plotting
  // an individual mover wants the flat list, and both are the same grid.
  const [grouped, setGrouped] = usePersistentState<boolean>(GROUPED_KEY, false)
  // Owned here rather than inside the grid, and it must stay here: this component is the memo
  // barrier, and state held above it in `ProgrammerPage` would put every re-render of the chrome
  // above — `ShowHeader`, and the `useShowBarProps` call feeding it — through the whole grid/rail
  // subtree. That used to read "every ShowBar re-render", which was the loudest source of them
  // until this page stopped drawing one; the barrier is no less load-bearing for it, since the
  // hook still runs here and still moves on every cue change. The *controls* render on row B,
  // inside the grid's own toolbar.
  const [columnVisibility, setColumnVisibility] = useColumnVisibility()
  // Short-height mode (space plan D8): under 500px of viewport height rows A and B are one row,
  // and row A's two halves are handed to the grid's toolbar as `leading` rather than drawn here.
  // A media query rather than a container query because HEIGHT is the question and a container
  // query cannot ask it; a hook here rather than CSS because the fold is a change of *place*, not
  // of appearance — and it is safe here specifically because this component is the memo barrier,
  // so the media change re-renders the subtree once and never the chrome above it. The
  // `ProgrammerWorkspace`/`ProgrammerGrid` elements keep their slots either way, so the grid
  // re-renders and never remounts — the rule `ProgrammerPage.test.tsx` gates on.
  const shortViewport = useMediaQuery(SHORT_VIEWPORT)
  // The marquee's store (editor-kit plan session 4): the grid publishes into it and the rail's
  // Colour and Spread tabs read it, so it sits above both. A store rather than state here, because
  // the marquee moves at pointer rate and this component is the memo barrier — a value held here
  // would re-render the whole subtree per frame; the store re-renders only a subscribed tab.
  const [marqueeStore] = useState(createMarqueeStore)

  // Revert is drop-everything-then-re-Include. There is no server-side revert, and those two steps
  // in that order are what the operator means: throw away the busk, load the cue again.
  //
  // The include target is read at click time rather than subscribed via
  // `useProgrammerSummaryQuery`: this component is the memo barrier for the whole grid/rail
  // subtree, and a summary subscription held *here* re-rendered all of it on every Include,
  // Record, blind flip or entry-count move — a wake `memo` cannot block, since it only
  // compares props. Read before `programmerClearAll`, which is about to clear the target.
  const handleRevert = () => {
    const cueId = includedCueId(lightingApi.programmer.getState().lastIncluded)
    programmerClearAll(0)
    if (cueId != null) void includeCue(cueId)
  }

  // The two halves of row A, built once: drawn on their own row at an ordinary height, and handed
  // to row B as its leading block when the viewport is too short for two rows of chrome.
  const rowA = (
    <>
      <ProgrammerSourceStrip
        projectId={projectId}
        onUpdate={sheets.openUpdate}
        onRevert={handleRevert}
      />
      {/* **Where the row's spare width goes**, which is a question only since the two sourceless
          states became `flex-initial` — as wide as the two words they say. Until then the box was
          `flex-1` in every state and simply ate the slack, so there was none to place.

          It goes wherever the seam is. Unfolded, row A holds nothing but these two halves, so the
          slack belongs behind the verbs and `ml-auto` puts them at the right edge, exactly where
          the `flex-1` box used to leave them. Folded, row B's tools follow on the same line and
          are already at that edge — so `ml-auto` there would push the verbs up against them and
          open the whole row's slack as a hole *between the box and its own verbs*, which is the
          one place it reads as a mistake rather than as a gutter. Left off, the gap falls at the
          seam the divider after the verbs already marks: what the programmer is holding and what
          you do to it on one side, the grid's own tools on the other. */}
      <span
        className={cn(
          'h-[22px] w-px shrink-0 self-center bg-border',
          !shortViewport && 'ml-auto',
        )}
      />
      <ProgrammerActionBar projectId={projectId} />
    </>
  )

  return (
    <ProgrammerScopeProvider>
      {/* The outer editor context stays `live` for the *rail* — its FX controls write the
          programmer whatever the grid is pointed at. `ProgrammerGrid` provides its own inner
          context derived from the scope. */}
      {/* The rail's width, collapsed flag and overlay flag are `ProgrammerWorkspace`'s, below
          this barrier — deliberately not state of this component or of `ProgrammerPage`, where a
          drag on the rail's handle would re-render the grid per pointer move. */}
      {/* Above the workspace, not inside the grid: the rail's own layer chrome reads the same
          store, and one fetch per focused layer is the point of it living here. The **scope band is
          inside it too**, and must stay there: it reads `useLookSaveState()`, which outside the
          provider silently answers the context default — so "Unsaved", "Saving…" and, worst of the
          three, "Save failed" could never appear. */}
      {/* Its template sibling, for the same reason and at the same height: a focused *template*
          layer has no `LookRowStore` (that one owns a row draft and engages only for a LOOK), and
          the grid, the notices and the scope band all need to know what it holds. */}
      <MarqueeStoreContext.Provider value={marqueeStore}>
        <LookRowStoreProvider projectId={projectId}>
          <FocusedTemplateLayerProvider projectId={projectId}>
            <EditorContextProvider value={{ kind: 'live' }}>
              <ProgrammerWorkspace
                header={
                  // Row A: the noun and the verbs, on one 40px line. It declares the `@container`
                  // both halves query — neither may declare its own, or each would measure itself
                  // instead of the width it has to share (see `ProgrammerWorkspace`'s doc comment
                  // for that bug).
                  //
                  // **It is the workspace's header, in the grid's column**, so the rail stands
                  // beside it and under nothing but the `ShowHeader` — the busk sheet's place
                  // beside the rig band. It was drawn above the workspace, which put the rail a
                  // row lower on this view than the sheet is on that one. Its fold ladder measures
                  // the row it now has, which is the column less the rail, as the band's does.
                  //
                  // Under 500px of viewport height it is not drawn at all: the same two components
                  // go into row B's leading slot, which is `ProgrammerGrid`'s `leading` prop, and
                  // the page's chrome is one line rather than two. `null` in that arm rather than
                  // a hidden div, because the components are mounted in the other place and two
                  // copies would be two of every query behind them.
                  //
                  // The shell's chrome row, with the one ground any row has: row A keeps its
                  // `bg-card/50` wash by decision (list-shell-design, called 2026-09-15) — the
                  // verbs' own band.
                  shortViewport ? null : <SheetPage.Row className="@container bg-card/50">{rowA}</SheetPage.Row>
                }
                grid={
                  <ProgrammerGrid
                    projectId={projectId}
                    grouped={grouped}
                    onGroupedChange={setGrouped}
                    columnVisibility={columnVisibility}
                    onColumnVisibilityChange={setColumnVisibility}
                    leading={shortViewport ? rowA : null}
                  />
                }
                rail={<ProgrammerRail />}
              />
            </EditorContextProvider>
          </FocusedTemplateLayerProvider>
        </LookRowStoreProvider>
      </MarqueeStoreContext.Provider>
    </ProgrammerScopeProvider>
  )
})
