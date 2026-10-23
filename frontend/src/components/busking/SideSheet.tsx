import { useCallback, useState, type CSSProperties, type ReactNode } from 'react'
import { Aperture, AudioWaveform, ChevronRight, Gauge, Move, Palette, Play, SunMedium, Waves, type LucideIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { useEditorForm, type EditorForm } from '@/components/editor/EditorSurface'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import {
  SIDE_PANEL_BODY_CLASS,
  SIDE_PANEL_ENTER_CLASS,
  SIDE_PANEL_HEADER_BUTTON_CLASS,
  SIDE_PANEL_OVERLAY_CLASS,
  buskTabWordClass,
  usePanelEnter,
  useSidePanelResize,
} from '@/components/sheet/sidePanel'
import { SidePanelResizeHandle } from '@/components/sheet/SidePanelResizeHandle'
import { SidePanelModeToggle } from '@/components/sheet/SidePanelModeToggle'
import { useSidePanelMode } from '@/lib/sidePanelMode'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { LIVE_SHEET_TABS, setBuskSheet, useBuskSheet, type BuskSheetTab } from '@/lib/buskWindow'
import { cn } from '@/lib/utils'
import { BuskSpeedRail } from './BuskSpeedRail'
import { ColourSheet } from './ColourSheet'
import { ShowTab, type ShowTabSource } from './ShowTab'
import { SideSheetFold } from './SideSheetFold'
import { BLIND_NAME_SUFFIX, BlindDot } from './BlindMarks'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { SpreadSheet, type SpreadSeed } from './SpreadSheet'
import { EffectsSheet } from './EffectsSheet'
import { BeamSheet, LevelSheet, PositionSheet } from './FamilySheets'
import type { BuskingTarget } from './buskingTypes'

/**
 * The busk view's **side sheet** — one rail, eight tabs, one fold, one fact (busk-further plan
 * D7; `Sheets.dc.html` is the authority on the sheet's layout, `ShowTab.dc.html` on the Show tab
 * and the strip's fold). The tabs are **Level · Colour · Position · Beam · Spread · Effects · Speed
 * · Show** (apply-model plan §4.5, D10): a tab per family first, each the per-property popup's body
 * over the busk selection, then the tools.
 *
 * **Speed is `BuskSpeedRail`**, mounted here and nowhere else in play mode — filling the sheet's
 * width and drawing no heading since 2026-09-21. **Colour is `ColourSheet`** (D8): the colour
 * editor's body hosted here, writing literals to Local for the selection. **Spread is
 * `SpreadSheet`** (D9, D10): two intents the desk resolves per head; its preview strip went the same
 * day, the rig being the preview. **Show is `ShowTab`** (busk-chrome plan D1): the phone runner
 * mounted in the sheet over the transport `routes/Busk.tsx` holds, which is what let the `ShowBar`
 * leave this view — its strip, two cards collapsed by default, BACK · GO as the tab's static footer
 * the way Colour and Spread keep their verbs. **Effects is `EffectsSheet`** (fixture-fx-sheets plan
 * D20), between Spread and Show: what runs on the selection, edited live, a pad's effect as the
 * running instance only, with *+ Effect…* and the split edit. **Level, Position and Beam are
 * `FamilySheets.tsx`'s**: the family's Palettes band, the
 * editor, the Effects band and *Save as palette…*, the Colour tab's shape in the other three
 * families. `LIVE_SHEET_TABS` in `lib/buskWindow.ts` is still the one list — a new tab would be
 * hidden there until it landed, because a tab that opens onto nothing is a promise the desk cannot
 * keep.
 *
 * **The tab strip is glyphs, and only the open tab gains its word, from 380px of sheet**
 * (`buskTabWordClass`): eight worded tabs want ~610px, past the panel's 480 ceiling, so no width
 * words them all. The strip is its own `@container`, every tab keeps its glyph and its name as a title, and the
 * sheet's 320px floor holds eight 26px glyphs, the mode toggle and the fold chevron on one row.
 * **The fold shows the live cue number under the Show glyph** (D4), from the server cursor the bar
 * read — so a folded sheet still says what is on stage.
 *
 * **The Colour tab's *Spread to a second colour…* button opens Spread with *From* set.** The hand-over is a
 * `SpreadSeed` held by whichever host mounts the two tabs — they are never mounted together, so
 * the seed travels through the host's state: the button writes it and the fact, and `SpreadSheet`
 * applies it once and asks for it to be dropped, so a later visit to the tab by any other door
 * does not re-apply a stale colour.
 *
 * **The sheet is one fact, `busk.sheet`, and the fold is `none`.** There is no `sheetOpen`
 * beside it, whatever `Focus.dc.html`'s older sketch lists — the fold chevron writes `none`, a tab
 * glyph on the fold writes its tab, and both are the same store every other reader of the fact
 * uses (the announce, ⌘K, the Screens sheet, a MIDI `BuskSheetToggle`). A fact naming a tab that
 * has not landed draws the fold, so a `sheet=colour` link arriving early is quiet rather than
 * broken.
 *
 * **Off the desk board the rail is not drawn** — below `md`, as it never was, and on the short
 * board (`BuskingView`'s `board`), where a docked 288px rail would leave the page a bank four pads
 * wide: the sheet is `SideSheetOverlay`, a bottom sheet on an upright phone and a right-hand
 * overlay where the viewport is short, through `useEditorForm`'s three forms — the fold
 * decides the form, the window decides the tab — and it carries **every tab but Speed** (D6):
 * Speed was withheld there because the ShowBar had the tempo chip, and the
 * Show tab's strip carries that chip now, so the reason is met by the tab that replaced the bar. It
 * opens from the page strip's button onto Colour.
 *
 * The palette still replaces this whole region while editing; that swap is `BuskingView`'s.
 *
 * **Its chrome is the programmer rail's, through `components/sheet/sidePanel.ts`.** The two are
 * the same instrument — a column against the right edge of a live view, folded to a strip of
 * glyphs — and they had drifted in every measurement the chrome system is supposed to settle. The
 * body fill, the 40px header, the 40px strip cell, the chevron vocabulary (◀ opens, ▶ folds) and
 * the enter animation all come from that module now; what stays this view's own is the *fact*
 * behind the fold, because `busk.sheet` is a per-window value the announce, ⌘K, the Screens sheet
 * and MIDI all write, where the rail's `collapsed` is a stored desk preference.
 */

interface TabSpec {
  id: BuskSheetTab
  label: string
  icon: LucideIcon
}

export const SIDE_SHEET_TABS: readonly TabSpec[] = [
  { id: 'level', label: 'Level', icon: SunMedium },
  { id: 'colour', label: 'Colour', icon: Palette },
  { id: 'position', label: 'Position', icon: Move },
  { id: 'beam', label: 'Beam', icon: Aperture },
  { id: 'spread', label: 'Fan', icon: Waves },
  { id: 'effects', label: 'FX', icon: AudioWaveform },
  { id: 'speed', label: 'Speed', icon: Gauge },
  { id: 'show', label: 'Show', icon: Play },
]

/**
 * A tab's word on either strip: the open tab's only, from 380px of sheet (`buskTabWordClass`, in
 * `components/sheet/sidePanel.ts`; re-exported here, where the sheet's tests
 * read it). The strip's **unpadded wrapper** is the `@container`, so 380 is 380 of the sheet — what
 * the operator dragged it to, or the overlay's own width — and not the window's. The overlay strip
 * takes the same rule: its right-hand form is 320, so there every tab is a glyph, clear of
 * `SheetContent`'s close cross; an upright phone's bottom sheet is wide enough for the open word.
 */
export { buskTabWordClass }

/** A tab's name, its word or its glyph's: Show's carries blind — the dot is aria-hidden (`BlindMarks.tsx`). */
function tabName(tab: TabSpec, blind: boolean): string {
  return tab.id === 'show' && blind ? `${tab.label}${BLIND_NAME_SUFFIX}` : tab.label
}

/**
 * Which tabs a sheet offers in a given form: the landed ones, docked; the landed ones **minus
 * Speed** in every overlay form below `md`, where the Show tab's strip carries the tempo chip that
 * reaches every master (D6, D7). `'popover'` is an overlay form too, not a second name for docked:
 * `useEditorForm` answers it for any viewport 640px and wider that is not short, which includes
 * the 640–767px band where the rail is still not drawn — and an overlay with a Speed tab and no
 * rail behind it would open onto nothing.
 */
export function sideSheetTabs(form: 'docked' | EditorForm): readonly TabSpec[] {
  const live = SIDE_SHEET_TABS.filter((tab) => LIVE_SHEET_TABS.includes(tab.id))
  return form === 'docked' ? live : live.filter((tab) => tab.id !== 'speed')
}

export interface SideSheetProps {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  /** The selection's attribute mask, which the tabs' presses carry. Null is every attribute. */
  families: AttributeFamily[] | null
  /** The route's transport and bar state, for the Show tab and the fold's live cue. */
  show: ShowTabSource
}

/**
 * What the fold writes under the Show glyph: the cue on stage by its number — its name where it
 * has none — read off the **server** cursor, which holds on the outgoing cue through a fade.
 */
function liveCueLabel(show: ShowTabSource): string | null {
  const { serverActiveCueId, activeStack } = show.transport
  if (serverActiveCueId == null) return null
  const cue = activeStack?.cues.find((c) => c.id === serverActiveCueId)
  if (cue == null) return null
  return cue.cueNumber ?? cue.name
}

/**
 * The *Second colour* hand-over, as one hook both hosts share: the seed, the button's handler
 * (which also opens the tab), and the drop the Spread tab calls once it has read it.
 */
function useSpreadSeed() {
  const [seed, setSeed] = useState<SpreadSeed | null>(null)
  const onSpread = useCallback((from: { r: number; g: number; b: number }) => {
    setSeed((prev) => ({ from: { r: from.r, g: from.g, b: from.b }, key: (prev?.key ?? 0) + 1 }))
    setBuskSheet('spread')
  }, [])
  const onSeedConsumed = useCallback(() => setSeed(null), [])
  return { seed, onSpread, onSeedConsumed }
}

/** The docked sheet, on the desk board: the fold when `busk.sheet` is `none`, else the tab strip and the tab. */
export function SideSheet({ projectId, selectedTargets, families, show }: SideSheetProps) {
  const sheet = useBuskSheet()
  const { seed, onSpread, onSeedConsumed } = useSpreadSeed()
  const tabs = sideSheetTabs('docked')
  const open = tabs.find((tab) => tab.id === sheet)
  const blind = useProgrammerBlind()
  // Latched here rather than inside the panel below: the fold and the panel are two subtrees of
  // this component and only one is ever mounted, so a hook in either would see every appearance
  // as its first render and animate on arrival at the route as readily as on an unfold.
  const enter = usePanelEnter(open != null)
  const overlay = useSidePanelMode() === 'overlay'
  if (open == null) {
    return <SideSheetFold projectId={projectId} selectedTargets={selectedTargets} tabs={tabs} liveCue={liveCueLabel(show)} />
  }
  const panel = (
    <DockedSideSheet openId={open.id} enter={enter} overlay={overlay}>
      {/* The strip's unpadded wrapper is the `@container`, so the open tab's word comes at 380px
          of sheet exactly (`buskTabWordClass`) — on the padded row itself it would come 24px late. */}
      <div className="@container shrink-0">
        <div role="tablist" aria-label="Side sheet" className={cn(CHROME_ROW_CLASS, 'gap-0.5')}>
          {/* **The tabs are what gives, and the two buttons never do.** The group takes the row's
              slack and clips its own overflow, so a row too narrow for everything loses the end of
              the last tab rather than pushing the mode toggle and the fold chevron outside the
              panel — which is what happened when the toggle was added to a row already tuned to
              fit. `SHEET_MIN_WIDTH` is set so this never actually bites; it is here so that the
              next thing added to the row degrades instead of clipping. */}
          <div className="flex min-w-0 flex-1 items-center gap-0.5 overflow-hidden">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                role="tab"
                aria-selected={tab.id === open.id}
                // The name is the label whatever the width (a tab's word is `display: none` but
                // the open one's from 380, which would leave the name to whatever else is inside), and
                // on Show it carries blind — the dot is aria-hidden (`BlindMarks.tsx`).
                aria-label={tabName(tab, blind)}
                title={tabName(tab, blind)}
                onClick={() => setBuskSheet(tab.id)}
                // `px-1.5`, a 26px glyph: eight tabs, the mode toggle and the chevron fit the 320
                // floor at that, and at `px-2` they came to more than the column has.
                className={cn(
                  'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-1.5 text-xs font-semibold transition-colors',
                  tab.id === open.id ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                <span className="relative inline-flex">
                  <tab.icon className="size-3.5" />
                  {tab.id === 'show' && <BlindDot />}
                </span>
                <span className={buskTabWordClass(tab.id === open.id)}>{tab.label}</span>
              </button>
            ))}
          </div>
          <SidePanelModeToggle className="shrink-0" />
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setBuskSheet('none')}
            aria-label="Fold the side sheet"
            title="Fold the sheet to its strip"
            className={cn(SIDE_PANEL_HEADER_BUTTON_CLASS, 'shrink-0')}
          >
            <ChevronRight className="size-3.5" />
          </Button>
        </div>
      </div>
      {/* The tab sizes itself and scrolls itself; the wrapper only hands it the column's height.
          The left edge is this panel's now, so a tab's own `border-l` is taken off — the region
          has one line down its side, not two stacked on the same pixel. */}
      <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
        {open.id === 'speed' && <BuskSpeedRail />}
        {open.id === 'level' && <LevelSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />}
        {open.id === 'position' && <PositionSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />}
        {open.id === 'beam' && <BeamSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />}
        {open.id === 'colour' && (
          <ColourSheet projectId={projectId} selectedTargets={selectedTargets} families={families} onSpread={onSpread} />
        )}
        {open.id === 'spread' && (
          <SpreadSheet
            projectId={projectId}
            selectedTargets={selectedTargets}
            families={families}
            seed={seed}
            onSeedConsumed={onSeedConsumed}
          />
        )}
        {open.id === 'effects' && <EffectsSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />}
        {open.id === 'show' && <ShowTab projectId={projectId} show={show} />}
      </div>
    </DockedSideSheet>
  )
  // **Overlay mode keeps the fold's 40px in the flow.** The panel is absolute and takes no room,
  // so swapping the fold for it alone handed the fold's width back to the page — every bank beside
  // it reflowed on each open and close, which is the one thing floating the sheet is meant to
  // avoid. The spacer holds the room the fold held; the panel, flush `right-0` and wider than 40,
  // covers it. It draws nothing, so the rule that the fold and the panel are never both on screen
  // stands. The programmer rail does the same with its strip made `invisible` (`RailStripFrame`).
  //
  // One fragment in both modes, the spacer a conditional slot before the panel: returning the
  // panel bare in push mode would move it between two parent shapes as the mode toggle in its own
  // header is pressed, and React would remount the tab under the operator's hand.
  return (
    <>
      {overlay && <div aria-hidden data-side-sheet-spacer className="hidden w-10 shrink-0 md:block" />}
      {panel}
    </>
  )
}

/** The busk sheet's docked width, in px. `localStorage`, like the rail's: a width is a fact
 *  about this desk's screen, not about which of two windows you are looking at — which is what
 *  every other `busk.*` key in `sessionStorage` is. */
const SHEET_WIDTH_KEY = 'busk.sheet.width'

/**
 * **This sheet's own floor, above the shared 260, and its header is what sets it.** It was set
 * when the strip held three labelled tabs (73 + 75 + 78) with 2px between them, the mode toggle and
 * the fold chevron (24 each) inside the chrome row's 12px gutters — **304px**, so at 260 the row
 * overflowed by 38 and pushed both buttons clean outside the panel. Measured in the browser at
 * 1180×820, where the Spread tab's own curve and order rows stop overflowing at the same 300.
 *
 * 320 rather than 304: a minimum that sits on the exact fit clips again the moment anything joins
 * that row, which is precisely how this broke — the row fitted until the mode toggle was added to
 * it. The default is the minimum, so the sheet opens at the narrowest width that is honest; it was
 * 288, which is below the floor and is clamped up on read for any desk that stored it.
 *
 * The strip holds **eight** tabs, and 320 still holds only because no closed tab draws its word and each is a 26px glyph (`px-1.5`): at the floor the eight
 * take 222px of the 244 the toggle and the chevron leave the group, measured in headless Chromium
 * against the built stylesheet. The open tab's word waits for 380 (`buskTabWordClass`), where
 * the widest, *Position*, makes the eight 275 of 304.
 */
const SHEET_MIN_WIDTH = 320
const SHEET_DEFAULT_WIDTH = 320

/**
 * The panel around the sheet's header and tab: the width, the drag that sets it, and which of the
 * two modes it is drawn in — `RailBodyFrame`'s counterpart, and the same `children` contract.
 *
 * **The contents arrive as `children` and that is load-bearing, not tidiness.** The width changes
 * at pointer rate during a drag, so whatever holds it re-renders at pointer rate; holding it in
 * `SideSheet` itself would re-render `BuskSpeedRail`, `ColourSheet` or `SpreadSheet` sixty times
 * a second, and a colour picker being dragged is not a thing to rebuild per frame. As `children`
 * the element reference is unchanged between the frame's renders, so React skips those subtrees
 * entirely — exactly how `RailBodyFrame` keeps the dock's editors out of its own drag.
 */
function DockedSideSheet({
  openId,
  enter,
  overlay,
  children,
}: {
  openId: string
  enter: boolean
  overlay: boolean
  children: ReactNode
}) {
  const { width, resizing, onResizeStart } = useSidePanelResize({
    storageKey: SHEET_WIDTH_KEY,
    fallback: SHEET_DEFAULT_WIDTH,
    min: SHEET_MIN_WIDTH,
  })
  const style = { '--sheet-w': `${width}px` } as CSSProperties
  return (
    <div
      data-side-sheet={openId}
      data-sheet-mode={overlay ? 'overlay' : 'push'}
      role="complementary"
      aria-label="Side sheet"
      style={style}
      className={cn(
        SIDE_PANEL_BODY_CLASS,
        // `relative` is the handle's containing block in push mode; in overlay mode the shared
        // overlay class positions the panel itself and this is already satisfied.
        'relative hidden w-[var(--sheet-w)] md:flex',
        // Over the page, or beside it — `lib/sidePanelMode.ts`, one fact shared with the rail.
        // Either way the fold is not drawn behind it: `SideSheet` renders the fold or the panel,
        // never both, which is the reading the rail's strip was brought onto.
        overlay ? SIDE_PANEL_OVERLAY_CLASS : 'shrink-0',
        // `select-none` while the drag runs, so it does not paint a text selection across the
        // page it crosses. The cursor is the handle's own.
        resizing && 'select-none',
        enter && SIDE_PANEL_ENTER_CLASS,
      )}
    >
      <SidePanelResizeHandle label="the side sheet" onResizeStart={onResizeStart} />
      {children}
    </div>
  )
}

/**
 * The sheet off the desk board — below `md`, and on the short board — as a bottom sheet or a
 * right-hand sheet. Open while `busk.sheet` names a tab this form offers; closing writes `none`,
 * the same fact the fold chevron writes. It carries every tab but Speed (D6); Colour is the tab
 * the page strip's button opens onto (`OVERLAY_SHEET_TAB`), and on the short board the Colour and
 * Spread editors take their compact layout, since that form exists for a viewport with no height.
 */
export function SideSheetOverlay({ projectId, selectedTargets, families, show }: SideSheetProps) {
  const form = useEditorForm()
  const sheet = useBuskSheet()
  const { seed, onSpread, onSeedConsumed } = useSpreadSeed()
  const tabs = sideSheetTabs(form)
  const open = tabs.find((tab) => tab.id === sheet)
  const blind = useProgrammerBlind()
  return (
    <Sheet open={open != null} onOpenChange={(next) => !next && setBuskSheet('none')}>
      <SheetContent
        side={form === 'bottom-sheet' ? 'bottom' : 'right'}
        // The right-hand form is the sheet's own 320 floor (`SHEET_MIN_WIDTH`), not the 288 it was
        // while it held only Colour and Spread: the Show tab's strip — name, list, programmer chip,
        // tempo chip — was measured at 320 with the name truncated to its minimum.
        className={cn('flex flex-col gap-0 p-0', form === 'bottom-sheet' ? 'h-[66vh] rounded-t-xl' : 'w-80')}
        style={{ maxWidth: 'none' }}
      >
        <SheetHeader className="sr-only">
          <SheetTitle>{open?.label ?? 'Side sheet'}</SheetTitle>
          <SheetDescription>The busk view’s side sheet</SheetDescription>
        </SheetHeader>
        {/* The unpadded wrapper is the `@container`, as on the docked strip: the right-hand form
            is 320 with `SheetContent`'s close cross drawn over its top-right corner, so there the
            seven tabs are 32px glyphs, and the open one gains its word from 380 of sheet — an
            upright phone's bottom sheet (`buskTabWordClass`). The group clips its own end rather
            than pushing anything out. */}
        <div className="@container shrink-0">
          <div
            role="tablist"
            aria-label="Side sheet"
            // The chrome row's own 12px gutter, at a touch height. Its inset was 8px and is
            // 12 now, deliberately: a tab row is a chrome row here as much as on the desk board.
            // `pr-12` keeps the strip clear of the sheet primitive's close cross.
            className={cn(CHROME_ROW_CLASS, 'h-11 gap-0.5 pr-12')}
          >
            <div className="flex min-w-0 flex-1 items-center gap-0.5 overflow-hidden">
              {tabs.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  role="tab"
                  aria-selected={tab.id === open?.id}
                  aria-label={tabName(tab, blind)}
                  title={tabName(tab, blind)}
                  onClick={() => setBuskSheet(tab.id)}
                  className={cn(
                    'inline-flex h-9 shrink-0 items-center gap-1.5 rounded-md px-2 text-sm font-semibold',
                    tab.id === open?.id ? 'bg-muted text-foreground' : 'text-muted-foreground',
                  )}
                >
                  <span className="relative inline-flex">
                    <tab.icon className="size-4" />
                    {tab.id === 'show' && <BlindDot />}
                  </span>
                  <span className={buskTabWordClass(tab.id === open?.id)}>{tab.label}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
        {open?.id === 'level' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <LevelSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />
          </div>
        )}
        {open?.id === 'position' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <PositionSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />
          </div>
        )}
        {open?.id === 'beam' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <BeamSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />
          </div>
        )}
        {open?.id === 'colour' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <ColourSheet
              projectId={projectId}
              selectedTargets={selectedTargets}
              families={families}
              compact={form === 'side-sheet'}
              onSpread={onSpread}
            />
          </div>
        )}
        {open?.id === 'spread' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <SpreadSheet
              projectId={projectId}
              selectedTargets={selectedTargets}
              families={families}
              compact={form === 'side-sheet'}
              seed={seed}
              onSeedConsumed={onSeedConsumed}
            />
          </div>
        )}
        {open?.id === 'effects' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <EffectsSheet projectId={projectId} selectedTargets={selectedTargets} families={families} />
          </div>
        )}
        {open?.id === 'show' && (
          <div role="tabpanel" className="flex min-h-0 flex-1 flex-col *:min-h-0 *:flex-1 *:border-l-0">
            <ShowTab projectId={projectId} show={show} />
          </div>
        )}
      </SheetContent>
    </Sheet>
  )
}
