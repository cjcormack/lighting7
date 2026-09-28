import { useState, useEffect, useCallback, lazy, Suspense } from "react"
import { Outlet, useLocation } from "react-router"
import { ChevronLeft, Menu, Sparkles, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { FeatureErrorBoundary } from "./components/FeatureErrorBoundary"
import { TooltipProvider } from "@/components/ui/tooltip"
import { cn } from "@/lib/utils"
import { MD_BREAKPOINT, useMediaQuery } from "@/hooks/useMediaQuery"
import { useSidebarOpen } from "@/hooks/useSidebarOpen"
import { useImmersive } from "@/lib/immersive"
import { isLiveViewPath } from "@/lib/liveViews"
import { MobileDrawerContext } from "./components/mobileDrawerContext"

import { ConnectionStatus } from "./connection"
import { ProgrammerIndicator } from './components/ProgrammerIndicator'
import ProjectSwitcher from "./ProjectSwitcher"
import { UserMenu } from "./components/auth/UserMenu"
import { FixtureOverviewPanel } from "./components/FixtureOverviewPanel"
import { StageOverviewPanel } from "./components/StageOverviewPanel"
import { FixtureDetailModal } from "./components/groups/FixtureDetailModal"
import { OverviewToggle, useOverviewPanels } from "./components/overviewPanels"
import { AiChatToggle } from "./components/ai/AiChatToggle"
import { CueSlotOverviewPanel } from "./components/CueSlotOverviewPanel"
import { SpeedMasterOverviewPanel } from "./components/SpeedMasterOverviewPanel"
import { DeskDndProvider } from "./components/dnd/DeskDndProvider"
import CommandPalette from "./components/CommandPalette"
import { AddEditFxSheet, type FxTarget } from "./components/fx/AddEditFxSheet"
import { ChannelValueDialog } from "./components/ChannelValueDialog"
import { SyncNotifications } from "./components/cloudSync/SyncNotifications"
import { SyncReauthBanner } from "./components/cloudSync/SyncReauthBanner"
import { ReturnToFullscreenBanner } from "./components/screens/ReturnToFullscreenBanner"
import { HandChip } from "./components/hand/HandChip"
import { ScreensSheet } from "./components/screens/ScreensSheet"
import { useWindowsBridge } from "./components/screens/useWindowsBridge"

const DRAWER_WIDTH = 240
const DRAWER_COLLAPSED_WIDTH = 64


/**
 * The AI chat panel behind a lazy boundary — react-markdown and its remark/micromark stack are
 * ~120 kB, and the panel is the only thing in the app that renders Markdown. It sits in Layout
 * rather than on a route, so the split is a mount latch rather than a route boundary: nothing is
 * fetched until the operator first opens Lux, and the panel then stays mounted for the rest of
 * the session because the conversation lives in its own component state and closing must not
 * discard it.
 */
const AiChatPanel = lazy(() =>
  import("./components/ai/AiChatPanel").then((m) => ({ default: m.AiChatPanel })),
)

/**
 * Reserves the page area while a lazily-imported route chunk loads. Everything outside `<main>` —
 * the drawer, the ShowBar, the overview panels — is already mounted and stays put, so this only
 * has to fill the scroll container, matching what those routes show while their own queries load.
 */
function RouteFallback() {
  return (
    <Card className="m-4 p-4 flex items-center justify-center">
      <Loader2 className="size-6 animate-spin" />
    </Card>
  )
}

export default function Layout() {
  // Two persisted preferences, one per route group, and the hook decides which the toggle writes.
  // See `hooks/useSidebarOpen.ts`: the four live views start on the 64px rail, the rest of the app
  // starts open.
  const { open, toggle: toggleDrawer } = useSidebarOpen()
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState(false)
  const [selectedFixture, setSelectedFixture] = useState<string | null>(null)
  const [isAiChatVisible, setIsAiChatVisible] = useState(false)
  // Latched by the first open and never cleared — see the `AiChatPanel` note above. Go through
  // `setAiChatVisible` rather than `setIsAiChatVisible` so every opener trips the latch.
  const [hasOpenedAiChat, setHasOpenedAiChat] = useState(false)
  const setAiChatVisible = useCallback((visible: boolean) => {
    if (visible) setHasOpenedAiChat(true)
    setIsAiChatVisible(visible)
  }, [])
  const [applyFxTarget, setApplyFxTarget] = useState<FxTarget | null>(null)
  const [channelDialogMode, setChannelDialogMode] = useState<"park" | "set" | null>(null)
  const { panels, byId } = useOverviewPanels()
  const location = useLocation()
  const isDesktop = useMediaQuery(MD_BREAKPOINT)

  // **Immersive** (busk-chrome plan D7; `Immersive.dc.html`): one boolean, the window's fact
  // *and* a live view. While it holds, the desktop sidebar, the app header, the four overview
  // panels and the sidebar's margin are not drawn — hidden, not collapsed, and the panels'
  // visibility is untouched so they come back on exit. On every other route the app is drawn
  // whatever the fact says: the fixtures list is navigated *from* the sidebar, and a window that
  // leaves a live view for it gets the app back and finds immersive waiting when it returns.
  // Banners, `HandChip`, the AI panel and `DeskDndProvider` are untouched. The `ShowHeader` is
  // the row that stays and carries the way back (D11); below `md` it also draws the mobile
  // drawer's button (D10), through the opener provided below — the drawer itself is still here.
  const immersive = useImmersive() && isLiveViewPath(location.pathname)
  const openMobileDrawer = useCallback(() => setMobileDrawerOpen(true), [])

  // This window's announce to the desk's windows registry, and the handler for the commands
  // another window sends it. Here and not in a store, because it needs the router (see the hook).
  useWindowsBridge()

  // Close mobile drawer on route change
  useEffect(() => {
    setMobileDrawerOpen(false)
  }, [location.pathname])

  const sidebarWidth = open ? DRAWER_WIDTH : DRAWER_COLLAPSED_WIDTH

  // Sidebar content (shared between desktop and mobile)
  const renderSidebarContent = (collapsed: boolean) => (
    <div className="flex-1 overflow-y-auto py-2">
      <ProjectSwitcher collapsed={collapsed} />
    </div>
  )

  return (
    <TooltipProvider delayDuration={0}>
      <MobileDrawerContext.Provider value={openMobileDrawer}>
      <SyncNotifications />
      <div className="flex h-dvh">
        {/* Desktop Sidebar — not drawn while immersive (D7). */}
        {isDesktop && !immersive && (
          <aside
            className={cn(
              "flex flex-col border-r bg-background transition-all duration-200",
              "fixed inset-y-0 left-0 z-50"
            )}
            style={{ width: sidebarWidth }}
          >
            {/* Sidebar Header */}
            <div className="flex h-14 items-center justify-end border-b px-2">
              <Button variant="ghost" size="icon" onClick={toggleDrawer}>
                {open ? (
                  <ChevronLeft className="size-5" />
                ) : (
                  <Menu className="size-5" />
                )}
              </Button>
            </div>

            {renderSidebarContent(!open)}
          </aside>
        )}

        {/* Mobile Drawer Overlay */}
        {!isDesktop && (
          <>
            {/* Backdrop */}
            <div
              className={cn(
                "fixed inset-0 z-50 bg-black/50 transition-opacity duration-200",
                mobileDrawerOpen ? "opacity-100" : "opacity-0 pointer-events-none"
              )}
              onClick={() => setMobileDrawerOpen(false)}
            />
            {/* Drawer */}
            <aside
              className={cn(
                "fixed inset-y-0 left-0 z-50 flex flex-col border-r bg-background transition-transform duration-200",
              )}
              style={{
                width: DRAWER_WIDTH,
                transform: mobileDrawerOpen ? 'translateX(0)' : 'translateX(-100%)',
              }}
            >
              {/* Drawer Header */}
              <div className="flex h-14 items-center justify-between border-b px-3">
                <span className="font-semibold text-sm">Menu</span>
                <Button variant="ghost" size="icon" onClick={() => setMobileDrawerOpen(false)}>
                  <ChevronLeft className="size-5" />
                </Button>
              </div>

              {renderSidebarContent(false)}
            </aside>
          </>
        )}

        {/* Main Content Area */}
        <div
          className="flex flex-1 flex-col transition-all duration-200 min-w-0"
          style={{ marginLeft: isDesktop && !immersive ? sidebarWidth : 0 }}
        >
          {/* Header. The tool row must stay on ONE line: it is chrome, and a wrapped chrome
              bar eats a third of an iPhone's viewport before any content renders.

              Breakpoints here are CONTAINER queries, not viewport ones, for the same reason
              ShowBar uses them — the desktop sidebar insets this region by 64–240px, so at a
              768px viewport the header really has ~528px to work with. Viewport breakpoints
              expand the status/programmer chips into space that isn't there, which then
              squeezes the title down to a stub.

              The thresholds are measured, not guessed: the title is 188px, the compact tool
              row 320px, and the row grows to ~455px once the status and programmer chips
              show their labels. So the title appears at 620px (fits untruncated even with
              the mobile hamburger) and the chips expand at 760px (title + expanded row +
              padding ≈ 691px, with margin).

              **It stops being sticky under 500px of viewport HEIGHT** (space plan D8). On a
              landscape phone this bar is 53 of 393px, and it is the one band on the page that
              says nothing about the show — the project, the view and the transport are all in
              the two headers below it. So on a page that scrolls it goes with the page there,
              instead of holding a seventh of the screen for a title and a theme toggle. A media
              query, not a container query: height is the one thing a container query cannot ask.
              `ProgrammerPage` and `ShowHeader` fold at the same 500.

              **It buys nothing on the four live views, and that is not a bug in this line.** They
              are `h-full` and own their own scrollers, so `<main>` never overflows and there is
              no scroll for the header to leave in — measured at 852×393, the header is 53px
              whether it is `sticky` or `static`. The routes that do scroll (the Fixtures and
              Groups cards, Scripts, Project Settings) get the whole 53 back — the six list views
              are full-height columns on the list shell and never do. Making it *disappear* on a short screen would
              be a different decision, and a worse one below 768px of width, where the hamburger
              inside it is the only navigation there is.

              **Not drawn while immersive** (busk-chrome plan D7). What it carried comes back where
              it is read (D10): the connection state as an *Offline* chip on the `ShowHeader`, only
              while offline; the hamburger on that header's left edge below `md`; theme (a ⌘K
              command of its own since this, `window-theme`), full screen and Screens… through ⌘K.
              The overview panels' toggles go with it — here and in the palette below — and their
              stored visibility is untouched. */}
          {!immersive && (
          <header className="@container sticky top-0 z-40 border-b bg-primary px-2 py-2 text-primary-foreground sm:px-4 [@media(max-height:500px)]:static">
            <div className="flex items-center gap-x-2 sm:gap-x-4">
              {/* Mobile hamburger button */}
              {!isDesktop && (
                <Button
                  variant="ghost"
                  size="icon"
                  className="text-primary-foreground hover:bg-primary-foreground/10 -ml-1 shrink-0"
                  onClick={() => setMobileDrawerOpen(true)}
                >
                  <Menu className="size-5" />
                </Button>
              )}
              {/* Hidden rather than truncated when the row is tight: a title clipped to
                  "C" is noise, and the hamburger plus the page's own breadcrumbs already
                  say where you are. */}
              <h1 className="hidden min-w-0 flex-1 truncate text-base font-semibold @[620px]:block @[760px]:text-lg">
                Chris&apos; DMX Controller v7
              </h1>
              <div className="flex-1 @[620px]:hidden" />
              <div className="flex shrink-0 items-center gap-1 overflow-x-auto sm:gap-2">
                <ConnectionStatus />
                <ProgrammerIndicator />
                {panels.map((panel) => (
                  <OverviewToggle key={panel.id} panel={panel} />
                ))}
                <AiChatToggle isVisible={isAiChatVisible} onToggle={() => setAiChatVisible(!isAiChatVisible)} />
                {/* The theme control is inside this menu, not beside it — see `ThemeMenuItem`.
                    This row is `shrink-0` around an `overflow-x-auto` that therefore cannot
                    engage, so anything added here pushes the header wider than the screen rather
                    than scrolling; at 375px the avatar was the part pushed off. */}
                <UserMenu />
              </div>
            </div>
          </header>
          )}

          {/* The four overview panels, stacked in `DESCRIPTORS`' order so the toolbar reads left
              to right as these read top to bottom. Each is always rendered — but only its animated
              wrapper is: every one of them puts its live body behind `CollapsiblePanel`, which
              unmounts it once the collapse has finished. Adding a panel here means doing the same,
              or the rig pays for it on every route the operator is on. None of the four is drawn
              while immersive (D7): their toggles — on the header above and in the palette's View
              group — are withheld with them, and their stored visibility is left alone so they
              return on exit. */}
          {!immersive && (
            <>
              <StageOverviewPanel
                isVisible={byId.stage.isVisible}
                selectedFixtureKey={selectedFixture}
                onFixtureClick={setSelectedFixture}
              />

              <FixtureOverviewPanel
                onFixtureClick={setSelectedFixture}
                isVisible={byId.fixtures.isVisible}
              />

              {/* The speed-master bank, summoned (`PD-SPEED-OVERLAY`). Outside `DeskDndProvider`,
                  unlike the cue-slot panel: that one is inside because its slots are droppables a
                  busk-page drag must reach, and this panel has no drag at all — nothing in it is
                  draggable and nothing may be dropped on it. */}
              <SpeedMasterOverviewPanel isVisible={byId.speedMasters.isVisible} />
            </>
          )}

          {/* The app's one DndContext, wrapping the cue-slot overlay and the routed page together:
              a drag started in either must be able to land in the other. See DeskDndProvider. */}
          <DeskDndProvider>
            {!immersive && <CueSlotOverviewPanel isVisible={byId.cueSlots.isVisible} />}

            {/* Page Content. The re-auth banner sits inside the scroll container's flex
                column rather than above it so it doesn't shift the ShowBar or the panels;
                it renders nothing at all unless the desk's GitHub connection is rejected
                and the viewer is an admin who can act on it. */}
            <main className="flex-1 overflow-auto bg-muted/40 min-w-0">
              <SyncReauthBanner />
              <ReturnToFullscreenBanner />
              {/* Outside the Suspense, so a route chunk that never arrives is caught here rather
                  than unmounting the desk. Keyed by pathname so navigating away from a failed
                  route clears the boundary instead of pinning the error over every later page. */}
              <FeatureErrorBoundary key={location.pathname} feature="This page">
                <Suspense fallback={<RouteFallback />}>
                  <Outlet />
                </Suspense>
              </FeatureErrorBoundary>
              {/* What the desk is holding (multi-screen plan §3.5). Fixed at the bottom of
                  `<main>`, below every bar and **not** in the header row — D14, which the header
                  comment above already argues. Inside `<main>` so it sits under the same error
                  boundary's sibling and inside `DeskDndProvider`; `position: fixed` takes it out
                  of the scroller regardless, so it does not move with the page. */}
              <HandChip />
            </main>
          </DeskDndProvider>

          {/* Fixture Detail Modal - opens in edit mode from overview */}
          <FixtureDetailModal
            fixtureKey={selectedFixture}
            onClose={() => setSelectedFixture(null)}
            isEditing
          />
        </div>

        {/* AI Chat Panel. Mounted from the first open onwards and never unmounted, so the
            conversation survives closing the sheet; the fallback is `null` because the panel is
            an overlay and has no layout of its own to reserve. */}
        {hasOpenedAiChat && (
          <FeatureErrorBoundary
            feature="Lux"
            className="fixed bottom-4 right-4 z-50 max-w-sm m-0"
          >
            <Suspense fallback={null}>
              <AiChatPanel
                isOpen={isAiChatVisible}
                onClose={() => setAiChatVisible(false)}
              />
            </Suspense>
          </FeatureErrorBoundary>
        )}

        {/* The Screens sheet, mounted once: opened from the user menu and from ⌘K through
            `screensSheetState`, neither of which is an ancestor of the other. */}
        <ScreensSheet />

        {/* Command Palette */}
        <CommandPalette
          onApplyFx={setApplyFxTarget}
          onParkChannelAtValue={() => setChannelDialogMode("park")}
          onSetChannelValue={() => setChannelDialogMode("set")}
          toggles={[
            // The same four panels the toolbar renders, from the same array — the palette used to
            // declare its own copy, which is how the Stage entry drifted onto a second icon. None
            // of them while immersive: the panels are not mounted then, and a row reading "On"
            // for a panel that is not on screen, whose press draws nothing, is a control
            // reporting a state it is not in. Lux stays — the AI panel is outside the gate.
            ...(immersive
              ? []
              : panels.map((panel) => ({
                  label: panel.label,
                  icon: panel.icon,
                  isVisible: panel.isVisible,
                  onToggle: panel.toggle,
                }))),
            { label: "Lux (AI Chat)", icon: Sparkles, isVisible: isAiChatVisible, onToggle: () => setAiChatVisible(!isAiChatVisible) },
          ]}
        />

        {/* Channel Value Dialog (Park / Set) */}
        <ChannelValueDialog
          open={channelDialogMode !== null}
          onOpenChange={(open) => { if (!open) setChannelDialogMode(null) }}
          mode={channelDialogMode ?? "set"}
        />

        {/* Apply FX Sheet (triggered by command palette) */}
        {applyFxTarget && (
          <AddEditFxSheet
            target={applyFxTarget}
            mode={{ mode: "add" }}
            onClose={() => setApplyFxTarget(null)}
          />
        )}
      </div>
      </MobileDrawerContext.Provider>
    </TooltipProvider>
  )
}
