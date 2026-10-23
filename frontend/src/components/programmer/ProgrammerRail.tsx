import type { ReactNode } from 'react'
import { ChevronLeft, ChevronRight, ChevronUp, Palette, Waves } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import { SidePanelModeToggle } from '@/components/sheet/SidePanelModeToggle'
import { useSidePanelMode } from '@/lib/sidePanelMode'
import { SIDE_PANEL_HEADER_BUTTON_CLASS, SIDE_PANEL_STRIP_CELL_CLASS, usePanelEnter } from '@/components/sheet/sidePanel'
import { cn } from '@/lib/utils'
import { RailBodyFrame, RailHandleFrame, RailStripFrame, useRailArm } from './ProgrammerWorkspace'
import { RailColourTab } from './RailColourTab'
import { RailSpreadTab } from './RailSpreadTab'
import { ScopedEditorContextProvider } from './ScopedEditorContext'
import type { RailTab } from './railTab'

/** The dock's two tabs, in strip order. The glyphs are the busk sheet's. */
export const RAIL_TABS: readonly { id: RailTab; label: string }[] = [
  { id: 'colour', label: 'Colour' },
  { id: 'spread', label: 'Fan' },
]

const GLYPHS: Readonly<Record<RailTab, ReactNode>> = {
  colour: <Palette className="size-3.5" />,
  spread: <Waves className="size-3.5" />,
}

/**
 * **The programmer's dock** (tables-colours plan D4): Colour · Spread, the busk sheet's two docked
 * editors hosted over the marquee (`RailColourTab`, `RailSpreadTab`), beside the grid. It lists
 * nothing — the layers, the effects and the scenery are the Stack, FX and Scenery tables on row B
 * (§The programmer's tables) — so nothing on it competes with the tables for a programmer event.
 *
 * **Every arm the rail had, the dock has**, and which one is showing is `ProgrammerWorkspace`'s
 * (`useRailArm`): docked at ≥1200px of workspace, at the stored width (260–480, default 300), or a
 * 40px strip when collapsed; an overlay over the grid's right edge at 704–1200 and in overlay mode,
 * opened from the strip; and below 704 a 44px handle across the bottom that opens the same panel in
 * a bottom sheet. The strip and the handle carry a door per tab, which opens the dock onto it.
 *
 * **One panel, mounted where it is seen, never twice.** The sheet and the docked frame are
 * exclusive by construction — `sheetOpen` is reachable only from the handle, which exists only
 * where the frame is hidden — and the render says so as a ternary. The panel holds no draft of its
 * own that a close could lose: a value lands through the marquee's published commit.
 *
 * **A tab claims its column's open gesture only where the dock docks** (`RailTabClaim`, keyed in
 * the workspace): the overlay and the sheet shut on a press on the grid, so there a cell's own
 * popover is the form.
 */
export function ProgrammerRail({ projectId }: { projectId: number }) {
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  // The body's enter animation, latched per arm above the conditional mount — see `usePanelEnter`:
  // docked, the body shows as `collapsed` goes false; in the overlay arm it is mounted the whole
  // time and `overlayOpen` is what opens it. Two calls and an OR of the results, never `a || b` of
  // the hooks, which would change the hook count between renders.
  const expandEnter = usePanelEnter(!arm.collapsed)
  const overlayEnter = usePanelEnter(arm.overlayOpen)
  const bodyEnter = overlay ? overlayEnter : expandEnter || overlayEnter
  // In push mode both arms are CSS's, so the body is in the tree for whichever is live; in overlay
  // mode `overlayOpen` is the one flag.
  const bodyShown = overlay ? arm.overlayOpen : !arm.collapsed || arm.overlayOpen

  const panel = <DockPanel projectId={projectId} tab={arm.railTab} />

  return (
    <>
      {arm.sheetOpen ? (
        <Sheet open onOpenChange={(next) => !next && arm.closeSheet()}>
          {/* `p-0 gap-0`: the panel brings its own padding, and the tab strip is the sheet's header.
              The shared close X sits at `top-2` beside it, level with a 40px row rather than the
              default header's 48 (`PD-SHEET-CLOSE-ALIGN`). */}
          <SheetContent side="bottom" className="h-[80%] gap-0 p-0 [&>[data-slot=sheet-close-x]]:top-2">
            <SheetHeader className="shrink-0 border-b px-3 py-1 pr-12">
              <SheetTitle className="sr-only">Colour and fan</SheetTitle>
              <DockTabs />
            </SheetHeader>
            {panel}
          </SheetContent>
        </Sheet>
      ) : (
        bodyShown && (
          <RailBodyFrame enter={bodyEnter}>
            <DockHeader />
            {panel}
          </RailBodyFrame>
        )
      )}
      <RailStripFrame>
        <DockStrip />
      </RailStripFrame>
      <RailHandleFrame>
        <DockHandle />
      </RailHandleFrame>
    </>
  )
}

/**
 * The open tab's editor, inside the grid's own `EditorContext` (`ScopedEditorContextProvider`) so a
 * value lands where the grid is pointed — a focused Look layer's draft, not Local.
 */
function DockPanel({ projectId, tab }: { projectId: number; tab: RailTab }) {
  return (
    <ScopedEditorContextProvider>
      <div role="tabpanel" aria-label={tab === 'colour' ? 'Colour' : 'Fan'} className="flex min-h-0 flex-1 flex-col">
        {tab === 'colour' ? <RailColourTab projectId={projectId} /> : <RailSpreadTab projectId={projectId} />}
      </div>
    </ScopedEditorContextProvider>
  )
}

/**
 * The dock's 40px header, level with row B: the tabs, the mode toggle and the chevron that puts the
 * dock away. Two chevrons in push mode, one per arm and each hidden where it does not belong, so
 * the docked arm's `collapsed` preference is never written from the overlay's (`RailArm`).
 */
function DockHeader() {
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  return (
    <div className={CHROME_ROW_CLASS}>
      <DockTabs />
      <span className="flex-1" />
      <SidePanelModeToggle className="shrink-0" />
      {overlay ? (
        <HeaderChevron label="Close the dock" onClick={arm.closeOverlay} />
      ) : (
        <>
          <HeaderChevron label="Collapse the dock" title="Collapse the dock to a strip" onClick={arm.collapse} className="@max-[1200px]:hidden" />
          <HeaderChevron label="Close the dock" onClick={arm.closeOverlay} className="@min-[1200px]:hidden" />
        </>
      )}
    </div>
  )
}

function HeaderChevron({ label, title, onClick, className }: { label: string; title?: string; onClick: () => void; className?: string }) {
  return (
    <Button
      variant="ghost"
      size="icon"
      className={cn(SIDE_PANEL_HEADER_BUTTON_CLASS, 'shrink-0', className)}
      aria-label={label}
      title={title ?? label}
      onClick={onClick}
    >
      <ChevronRight className="size-3.5" />
    </Button>
  )
}

/** **Colour · Fan**, each with its word: two tabs fit the dock's 260px floor whole. */
function DockTabs() {
  const { railTab, setRailTab } = useRailArm()
  return (
    <div role="tablist" aria-label="Dock" data-rail-tabs className="flex min-w-0 items-center gap-0.5">
      {RAIL_TABS.map((tab) => {
        const open = tab.id === railTab
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={open}
            data-rail-tab-button={tab.id}
            onClick={() => setRailTab(tab.id)}
            className={cn(
              'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs font-semibold transition-colors',
              open ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {GLYPHS[tab.id]}
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}

/**
 * The 40px strip: the chevron, then a door per tab. Each door is two buttons in push mode, one per
 * arm and hidden by the arms' own container queries, as the chevron is — the docked arm's expands
 * onto the tab (writing the stored `collapsed`), the narrow arm's opens the overlay on it.
 */
function DockStrip() {
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  return (
    <>
      {overlay ? (
        <StripCell label="Open the dock" onClick={arm.openOverlay} glyph={<ChevronLeft className="size-3.5" />} />
      ) : (
        <>
          <StripCell label="Expand the dock" onClick={arm.expand} glyph={<ChevronLeft className="size-3.5" />} className="@max-[1200px]:hidden" />
          <StripCell label="Open the dock" onClick={arm.openOverlay} glyph={<ChevronLeft className="size-3.5" />} className="@min-[1200px]:hidden" />
        </>
      )}
      {RAIL_TABS.map((tab) => {
        const overTheGrid = () => {
          arm.setRailTab(tab.id)
          arm.openOverlay()
        }
        return overlay ? (
          <StripCell key={tab.id} tab={tab.id} label={`Open the dock on ${tab.label}`} onClick={overTheGrid} glyph={GLYPHS[tab.id]} />
        ) : (
          <span key={tab.id} className="contents">
            <StripCell tab={tab.id} label={`Expand the dock on ${tab.label}`} onClick={() => arm.openTab(tab.id)} glyph={GLYPHS[tab.id]} className="@max-[1200px]:hidden" />
            <StripCell tab={tab.id} label={`Open the dock on ${tab.label}`} onClick={overTheGrid} glyph={GLYPHS[tab.id]} className="@min-[1200px]:hidden" />
          </span>
        )
      })}
    </>
  )
}

function StripCell({
  tab,
  label,
  onClick,
  glyph,
  className,
}: {
  tab?: RailTab
  label: string
  onClick: () => void
  glyph: ReactNode
  className?: string
}) {
  return (
    <Button
      variant="ghost"
      size="icon"
      className={cn(SIDE_PANEL_STRIP_CELL_CLASS, className)}
      aria-label={label}
      title={label}
      data-rail-strip-tab={tab}
      onClick={onClick}
    >
      {glyph}
    </Button>
  )
}

/**
 * The phone's 44px handle: a door per tab, which opens the bottom sheet on it, and the chevron.
 */
function DockHandle() {
  const arm = useRailArm()
  return (
    <>
      {RAIL_TABS.map((tab) => (
        <Button
          key={tab.id}
          variant="ghost"
          size="sm"
          className="h-8 shrink-0 text-muted-foreground"
          aria-label={`Show ${tab.label}`}
          onClick={() => {
            arm.setRailTab(tab.id)
            arm.openSheet()
          }}
        >
          {GLYPHS[tab.id]}
          {tab.label}
        </Button>
      ))}
      <span className="flex-1" />
      <Button
        variant="ghost"
        size="icon"
        className="size-8 shrink-0 text-muted-foreground"
        aria-label="Open Colour and Fan"
        aria-expanded={arm.sheetOpen}
        title="Open Colour and Fan"
        onClick={arm.openSheet}
      >
        <ChevronUp className="size-4" />
      </Button>
    </>
  )
}
