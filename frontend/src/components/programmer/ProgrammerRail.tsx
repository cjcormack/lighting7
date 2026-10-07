import { memo, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import {
  ArrowDownUp,
  AudioWaveform,
  Blinds,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  Hand,
  Layers,
  Palette,
  Plus,
  Waves,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import { SidePanelModeToggle } from '@/components/sheet/SidePanelModeToggle'
import { useSidePanelMode } from '@/lib/sidePanelMode'
import {
  SIDE_PANEL_HEADER_BUTTON_CLASS,
  SIDE_PANEL_STRIP_CELL_CLASS,
  tabWordClass,
  usePanelEnter,
} from '@/components/sheet/sidePanel'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { FxSheet } from './FxSheet'
import {
  ProgrammerAddEffectSheet,
  useProgrammerAddEffect,
  type AddEffectOffer,
} from './ProgrammerAddEffect'
import { ProgrammerAddLayerSheet, type ProgrammerAddLayerKind } from './ProgrammerAddLayerSheet'
import { ProgrammerFxList } from './ProgrammerFxList'
import { ProgrammerLookStack } from './ProgrammerLookStack'
import { HandProgrammerLayerStrip } from '@/components/hand/HandLayerTargets'
import { useProgrammerScope, useProgrammerScopeActions } from './ProgrammerScope'
import { useProgrammerSheets } from './ProgrammerSheets'
import { useLocalValueCount } from './useLocalFamilyCounts'
import { RailBodyFrame, RailHandleFrame, RailStripFrame, useRailArm } from './ProgrammerWorkspace'
import { RailColourTab } from './RailColourTab'
import { RailSpreadTab } from './RailSpreadTab'
import { ScopedEditorContextProvider } from './ScopedEditorContext'
import { RailSceneryBand } from './RailSceneryBand'
import { useHeldSceneryCount } from './ProgrammerSceneryList'
import type { RailTab } from './railTab'

/** One label for every band of the rail: the editor kit's `EditorLabel` (`components/editor/`), in a rail that has no icon room — its own copy, tracked at 0.1em where the kit's is 0.08em. */
const LABEL_CLASS =
  'inline-flex shrink-0 items-center gap-1 text-[9px] font-bold uppercase tracking-[0.1em] text-muted-foreground'

type AddKind = ProgrammerAddLayerKind | 'effect'

/**
 * Which half of the one scroller a gesture asked for.
 *
 * `PD-SHEET-ICONS-OPEN`: the collapsed arms draw the body's bands — scenery, layers and effects —
 * as glyph-and-count pairs, so pressing one has to open the rail — and open it *at that band*, since
 * a press on FX that lands on the top of the layer stack has answered a different question. It is a one-shot request rather than a stored position: `RailBody` scrolls to it and
 * clears it, so the operator's own scrolling afterwards is never undone.
 */
type RailBand = 'scenery' | 'layers' | 'fx'

/**
 * The layer stack and the running effects, side by side with the value grid rather than behind
 * tabs — the three readings of one live object, all on screen.
 *
 * **One list, two bands, since session 3 of the space plan.** It was two separately-headed
 * sections — the stack with its own LAYERS heading and Add, the FX band with its own heading and
 * `+ Effect` — stacked in a fixed 404px column. Now it is one scroller under one 40px header
 * (`LAYERS n · FX n` and the collapse chevron, level with row B) and over one footer
 * (`+ Look · + Template · + Effect`). The body runs top to bottom **top wins**: a
 * `VALUES · top wins` label, the **Local values** row — the operator's own entries, which beat
 * every layer, with `Make layer` on it because that is the row it promotes — the layers at the
 * dense density from strongest to weakest (the array reversed; see `LookStack`'s `dense`), the
 * amber boundary that says values beat effects whatever the order, and the effects. The rule the
 * old stack's paragraph stated is the label's two words and its hover.
 *
 * **The strip is drawn here too.** Collapsed — or at 704–1200, where the rail is an overlay — the
 * rail is a 40px strip carrying the two counts as badges under their glyphs and one `+` that opens
 * the same three doors as the footer, so nothing is reachable only with the rail open. **The two
 * counts are doors as well** (`PD-SHEET-ICONS-OPEN`): pressing one opens the rail scrolled to that
 * band, because a glyph that names half the body and does nothing when pressed reads as broken.
 * Which arm is showing is `ProgrammerWorkspace`'s: it owns the width, the collapsed flag, the
 * overlay flag and the sheet flag, and this component reads them through `useRailArm`. The frames
 * are the workspace's as well; this component decides what goes in them.
 *
 * **And so is the phone's handle** (space plan D8, session 4). Below 704px of workspace the strip
 * is hidden and `RailHandle` takes the bottom of the page instead: 44px, the same two counts —
 * pressable there too, and to the same band — the layer names truncated after them, the same `+`,
 * and a chevron that opens the *same body* in a `Sheet side="bottom"` at 80% height. The names
 * are there because a 40px column of two badges said only *how many*, and on the one screen where
 * the stack is never visible beside the grid *which* is the question worth 44px. They are drawn strongest-first, like the dense rows above
 * them, because the stack reads top wins.
 *
 * **The sheet and the docked body are one body in two places, never two.** `sheetOpen` is
 * reachable only from the handle, and the handle only exists below 704 where `RailBodyFrame` is
 * hidden — so the two are mutually exclusive by construction and the render below states that as
 * a ternary rather than trusting it. Mounting both would be two `ProgrammerLookStack`s, two
 * `ProgrammerFxList`s and two of every subscription under them. There is no guarded close on it
 * (`CLAUDE.md` §Sheets vs Dialogs): the rail holds no draft — a layer-scope edit writes through
 * `LookRowDraft`, which is the grid's, not the rail's.
 *
 * **The two sheets are mounted here and not in the body**, because the body unmounts when the
 * rail collapses and the strip's `+` has to open them while it is gone. `useProgrammerAddEffect`
 * is called here for the same reason: its offer is handed to both doors and to the sheet, so the
 * footer button, the menu item and the sheet cannot disagree about whether an effect can land.
 * That hook subscribes to the desk selection, which a marquee changes many times a second, so
 * `RailBody` is memoised and the two lists inside it are too: a selection change re-renders this
 * component, the header and the two door rows, and stops there. The `Per-fixture FX` disclosure's
 * open flag lives here as well, for the plainer reason that the body unmounts with the rail and
 * a diagnostic that shut itself every time the rail was reopened would be a nuisance.
 *
 * **`FxSheet` stays a mount-on-demand disclosure at the foot of the body.** It builds the whole
 * fixture row model a second time, renders every row unvirtualized, and subscribes to
 * `useProgrammerRevision`, which fires on every programmer event — including each 30 Hz commit
 * tick from the grid beside it. Always-mounted would mean re-rendering a 200-row tree at 30 Hz
 * while the operator drags a fader. Behind one click it costs nothing until it is asked for, and
 * it is still the only place per-fixture suppression and programmer-ownership are visible.
 */
export function ProgrammerRail() {
  const { projectId: projectIdParam } = useParams()
  const projectId = Number(projectIdParam)
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  const { data: layers } = useProgrammerLayersQuery()
  const { data: effects } = useActiveEffectsQuery()
  const layerCount = layers?.length ?? 0
  const fxCount = effects?.length ?? 0
  // The programmer's held scenery (scenery-programmer plan D1): a count on the strip, the handle and
  // the faces with room for it, beside the layers' and the effects'.
  const sceneryCount = useHeldSceneryCount(projectId)
  const [adding, setAdding] = useState<AddKind | null>(null)
  const [diagnosticOpen, setDiagnosticOpen] = useState(false)
  const [band, setBand] = useState<RailBand | null>(null)
  const addEffect = useProgrammerAddEffect()
  const closeAdd = useCallback(() => setAdding(null), [])
  // The body's enter animation. Computed **here**, not in `RailBodyFrame`: that frame is mounted
  // conditionally below, so a hook inside it sees every appearance as a first render and could
  // never tell "the operator expanded the rail" from "the view just loaded with it open".
  //
  // **One latch per arm, because the two arms open for different reasons.** Docked, the body
  // shows when `collapsed` goes false; in the overlay arm it is *mounted the whole time* and the
  // container query alone decides whether it is drawn, so what opens it is `overlayOpen`. A
  // single `usePanelEnter(!collapsed || overlayOpen)` cannot see the second: `collapsed` rests
  // at false, so that expression is already true on the first render and opening the overlay
  // transitions nothing — the overlay arm simply never animated. The two calls are separate
  // statements and the *results* are OR-ed: `usePanelEnter(a) || usePanelEnter(b)` would
  // short-circuit past the second hook on any render where the first answered true, which is a
  // changing hook count between renders.
  const expandEnter = usePanelEnter(!arm.collapsed)
  const overlayEnter = usePanelEnter(arm.overlayOpen)
  const bodyEnter = overlay ? overlayEnter : expandEnter || overlayEnter
  // **What mounts the body, per mode.** In push mode the arm is CSS's, so the body has to be in
  // the tree for whichever of the two arms is live: `!collapsed` answers the docked one and
  // `overlayOpen` the narrow one. In overlay mode there is one arm and one flag — and reading
  // `!collapsed` there would leave the body mounted behind a shut panel for the whole visit,
  // holding a layer list, an FX list and every subscription under them, which is the cost this
  // condition exists to avoid.
  const bodyShown = overlay ? arm.overlayOpen : !arm.collapsed || arm.overlayOpen
  // Stable, because `RailBody` is memoised against a parent that re-renders on every selection
  // change — a fresh closure here would defeat that memo at marquee rate.
  const clearBand = useCallback(() => setBand(null), [])

  const body = (
    <RailBody
      projectId={projectId}
      diagnosticOpen={diagnosticOpen}
      onDiagnosticOpenChange={setDiagnosticOpen}
      band={band}
      onBandShown={clearBand}
    />
  )

  return (
    <>
      {arm.sheetOpen ? (
        <Sheet open onOpenChange={(next) => !next && arm.closeSheet()}>
          {/* `p-0 gap-0` because the body and the footer bring their own padding, and the rail's
              own 40px header is replaced by the sheet's — two headings stacked would be the
              phone's scarcest 40px spent saying "Layers" twice.

              `PD-SHEET-CLOSE-ALIGN`: that header is ~32px (8px of padding either side of a 9px
              label), where `SheetContent`'s shared close button is pinned at `top-4` — an offset
              measured against the default `p-4` header, which is ~48px. So the X sat 8px below
              its own centre line and all but touched the bottom border. The divergence is this
              header's and the fix is scoped to it: raising the primitive's `top-4` would move the
              X in every other sheet in the app, none of which has this problem, and padding this
              header out to 48px would spend 16px of the phone's scarcest space on it. */}
          <SheetContent
            side="bottom"
            className="h-[80%] gap-0 p-0 [&>[data-slot=sheet-close-x]]:top-2"
          >
            <SheetHeader className="shrink-0 border-b px-3 py-2">
              <SheetTitle className={cn(LABEL_CLASS, 'text-foreground')}>
                <Layers className="size-3" />
                Layers
                <CountBadge count={layerCount} />
                <AudioWaveform className="ml-1.5 size-3 text-violet-400" />
                <span className="text-violet-400">FX</span>
                <CountBadge count={fxCount} />
                <Blinds className="ml-1.5 size-3" />
                Scenery
                <CountBadge count={sceneryCount} />
              </SheetTitle>
            </SheetHeader>
            {body}
            <RailFooter onAdd={setAdding} addEffect={addEffect} />
          </SheetContent>
        </Sheet>
      ) : (
        bodyShown && (
          <RailBodyFrame enter={bodyEnter}>
            <RailHeader layerCount={layerCount} fxCount={fxCount} sceneryCount={sceneryCount} />
            {/* **The Stack tab is the rail's body** — the scenery band, the layers and the effects —
                and its footer.
                The other two are the busk sheet's docked editors over the marquee, inside the
                grid's own `EditorContext` so a value lands where the grid is pointed (a focused
                Look layer's draft, not Local). One panel mounted at a time, as the busk sheet's. */}
            {arm.railTab === 'stack' ? (
              <>
                {body}
                <RailFooter onAdd={setAdding} addEffect={addEffect} />
              </>
            ) : (
              <ScopedEditorContextProvider>
                <div role="tabpanel" aria-label={arm.railTab === 'colour' ? 'Colour' : 'Spread'} className="flex min-h-0 flex-1 flex-col">
                  {arm.railTab === 'colour' ? <RailColourTab projectId={projectId} /> : <RailSpreadTab projectId={projectId} />}
                </div>
              </ScopedEditorContextProvider>
            )}
          </RailBodyFrame>
        )
      )}
      <RailStripFrame>
        <RailStrip
          layerCount={layerCount}
          fxCount={fxCount}
          sceneryCount={sceneryCount}
          onAdd={setAdding}
          onBand={setBand}
          addEffect={addEffect}
        />
      </RailStripFrame>
      <RailHandleFrame>
        <RailHandle
          layers={layers}
          layerCount={layerCount}
          fxCount={fxCount}
          sceneryCount={sceneryCount}
          onAdd={setAdding}
          onBand={setBand}
          addEffect={addEffect}
        />
      </RailHandleFrame>
      <ProgrammerAddLayerSheet
        projectId={projectId}
        kind={adding === 'look' || adding === 'template' ? adding : null}
        onClose={closeAdd}
      />
      <ProgrammerAddEffectSheet open={adding === 'effect'} offer={addEffect} onClose={closeAdd} />
    </>
  )
}

function CountBadge({ count }: { count: number }) {
  return (
    <Badge variant="secondary" className="px-1 py-0 text-[9px] leading-[1.5] tabular-nums">
      {count}
    </Badge>
  )
}

/**
 * The rail's 40px header — **a tab strip since editor-kit session 4**, level with row B: **Stack**
 * (`LAYERS n · FX n`, the rail as it always was) · **Colour** · **Spread**, then the mode toggle and
 * the chevron that takes the rail away. A second tab row under the header was declined: 40px of
 * rail spent saying the counts twice. The busk sheet puts its strip on its one chrome row, and so
 * does this.
 *
 * **Level with row B is the reason for every number on it.** Row B is a 40px line on a 12px gutter
 * with 8px between its controls, and this header's bottom border meets that row's across the
 * seam; it was `h-9 px-2.5 gap-2.5`, four pixels short and two pixels in, so the two borders
 * stepped where they met. The strip's chevrons below are `h-10` for the same reason.
 *
 * **The tabs are the docked arm's alone.** In push mode the strip carries `@max-[1200px]:hidden`
 * — measured against the workspace, since the strip is its own `@container` and a query never
 * matches the element that declares it — and the narrow arm draws the plain `LAYERS · FX` face in
 * its place; overlay mode draws only that face. Both of those arms shut on the next press outside
 * them, which a picker over the grid has to survive, so the popover is the form there. The strip
 * also writes the tab back to Stack when that arm hides it (`RailTabs`).
 *
 * Two chevrons, one per arm, hidden by the same container query the frames use: the docked
 * rail's writes the collapsed preference, the overlay's only shuts the overlay. One button
 * deciding which to do would need the arm in JS, and the two flags stay honest by never being
 * written from the wrong arm (`RailArm`).
 */
function RailHeader({ layerCount, fxCount, sceneryCount }: { layerCount: number; fxCount: number; sceneryCount: number }) {
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  return (
    <div className={CHROME_ROW_CLASS}>
      {overlay ? (
        <>
          <StackLabels layerCount={layerCount} fxCount={fxCount} sceneryCount={sceneryCount} />
          <span className="flex-1" />
        </>
      ) : (
        <>
          <RailTabs layerCount={layerCount} fxCount={fxCount} />
          <StackLabels layerCount={layerCount} fxCount={fxCount} sceneryCount={sceneryCount} className="@min-[1200px]:hidden" />
          <span className="flex-1 @min-[1200px]:hidden" />
        </>
      )}
      <SidePanelModeToggle className="shrink-0" />
      {/* In overlay mode there is one arm, so one chevron, and it is the overlay's: the docked
          arm's writes `collapsed`, which means nothing while the panel floats. In push mode the
          arm is still the container query's, so both are drawn and each is hidden where it does
          not belong — two buttons rather than one that reads the arm in JS, so neither flag is
          ever written from the wrong arm (`RailArm`). */}
      {overlay ? (
        <Button
          variant="ghost"
          size="icon"
          className={cn(SIDE_PANEL_HEADER_BUTTON_CLASS, 'shrink-0')}
          aria-label="Close the rail"
          title="Close the rail"
          onClick={arm.closeOverlay}
        >
          <ChevronRight className="size-3.5" />
        </Button>
      ) : (
        <>
          <Button
            variant="ghost"
            size="icon"
            className={cn(SIDE_PANEL_HEADER_BUTTON_CLASS, 'shrink-0 @max-[1200px]:hidden')}
            aria-label="Collapse the rail"
            title="Collapse the rail to a strip"
            onClick={arm.collapse}
          >
            <ChevronRight className="size-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className={cn(SIDE_PANEL_HEADER_BUTTON_CLASS, 'shrink-0 @min-[1200px]:hidden')}
            aria-label="Close the rail"
            title="Close the rail"
            onClick={arm.closeOverlay}
          >
            <ChevronRight className="size-3.5" />
          </Button>
        </>
      )}
    </div>
  )
}

/**
 * The rail's face where there are no tabs — the overlay arm and overlay mode: `LAYERS n · FX n ·
 * SCENERY n`. The docked tab strip's Stack face carries no scenery pair: that strip is 211px at the
 * rail's 300px default and its three tabs already need 224 with Stack open, so a third
 * glyph-and-count would push Colour and Spread out of it. The band's own label says the count there,
 * at the top of the body the tab opens on.
 */
function StackLabels({
  layerCount,
  fxCount,
  sceneryCount,
  className,
}: {
  layerCount: number
  fxCount: number
  sceneryCount: number
  className?: string
}) {
  return (
    <span className={cn('inline-flex items-center gap-2', className)}>
      <span className={LABEL_CLASS} title={`${layerCount} layer${layerCount === 1 ? '' : 's'}`}>
        <Layers className="size-3" />
        Layers
        <CountBadge count={layerCount} />
      </span>
      <span
        className={cn(LABEL_CLASS, 'text-violet-400')}
        title={`${fxCount} effect${fxCount === 1 ? '' : 's'} running`}
      >
        <AudioWaveform className="size-3" />
        FX
        <CountBadge count={fxCount} />
      </span>
      <span className={LABEL_CLASS} title={`${sceneryCount} held piece${sceneryCount === 1 ? '' : 's'} of scenery`}>
        <Blinds className="size-3" />
        Scenery
        <CountBadge count={sceneryCount} />
      </span>
    </span>
  )
}

/** The rail's three tabs, in strip order. The glyphs are the busk sheet's for Colour and Spread. */
export const RAIL_TABS: readonly { id: RailTab; label: string }[] = [
  { id: 'stack', label: 'Stack' },
  { id: 'colour', label: 'Colour' },
  { id: 'spread', label: 'Spread' },
]

/**
 * **Stack · Colour · Spread** — the docked header's tab strip (editor-kit plan session 4).
 *
 * **Its words fold by the busk sheet's rule** (`tabWordClass`, the panels' shared chrome): the
 * strip's unpadded wrapper is the `@container`, every tab keeps its glyph, and below 400px of strip
 * only the open tab keeps its word — which, the strip being the rail less its toggle and chevron,
 * is every width the rail has (260–480). The Stack tab's words fold to **the strip's own
 * glyph-and-count pairs** — the layers glyph and its count, the wave and its — so *Layers 3 · FX 2*
 * is a face when open and two badges when not, and the counts are never lost. The group clips its
 * own end rather than pushing the toggle and the chevron out of the panel, the busk strip's rule.
 *
 * **It resets the tab to Stack when the docked arm is not what is drawn.** The strip carries the
 * push-mode narrow arm's `@max-[1200px]:hidden`, and that arm is CSS's to choose — so, as
 * `RailHandleFrame` reads its own box to learn the phone arm has taken over, this reads its own:
 * a zero box while a tab is open means the rail has become an overlay, which closes on the next
 * press on the grid, so the tab goes back to Stack. `ResizeObserver` is guarded for jsdom, which
 * lays nothing out and would otherwise reset every tab on arrival.
 */
function RailTabs({ layerCount, fxCount }: { layerCount: number; fxCount: number }) {
  const { railTab, setRailTab } = useRailArm()
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = ref.current
    if (railTab === 'stack' || !el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      if (el.getBoundingClientRect().height === 0) setRailTab('stack')
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [railTab, setRailTab])

  return (
    <div ref={ref} data-rail-tabs className="@container min-w-0 flex-1 @max-[1200px]:hidden">
      <div role="tablist" aria-label="Rail" className="flex min-w-0 items-center gap-0.5 overflow-hidden">
        {RAIL_TABS.map((tab) => {
          const open = tab.id === railTab
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={open}
              // The name is the label whatever the width: below 400 a closed tab's word is
              // `display: none`, which would leave the name to the badges.
              aria-label={tab.label}
              title={tab.id === 'stack' ? `${layerCount} layer${layerCount === 1 ? '' : 's'} · ${fxCount} effect${fxCount === 1 ? '' : 's'} running` : undefined}
              data-rail-tab-button={tab.id}
              onClick={() => setRailTab(tab.id)}
              className={cn(
                'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs font-semibold transition-colors',
                open ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {tab.id === 'stack' ? (
                <>
                  <Layers className="size-3.5" />
                  <span className={tabWordClass(open)}>Layers</span>
                  <CountBadge count={layerCount} />
                  <AudioWaveform className="size-3.5 text-violet-400" />
                  <span className={cn(tabWordClass(open), 'text-violet-400')}>FX</span>
                  <CountBadge count={fxCount} />
                </>
              ) : (
                <>
                  {tab.id === 'colour' ? <Palette className="size-3.5" /> : <Waves className="size-3.5" />}
                  <span className={tabWordClass(open)}>{tab.label}</span>
                </>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

/**
 * The one scroller: values, the boundary, effects, and the diagnostic disclosure at the foot.
 *
 * Memoised because its parent re-renders on every selection change (see `ProgrammerRail`) and
 * nothing in here reads the selection through props — each row subscribes to what it needs.
 */
const RailBody = memo(function RailBody({
  projectId,
  diagnosticOpen,
  onDiagnosticOpenChange,
  band,
  onBandShown,
}: {
  projectId: number
  diagnosticOpen: boolean
  onDiagnosticOpenChange: (open: boolean) => void
  band: RailBand | null
  onBandShown: () => void
}) {
  const sceneryRef = useRef<HTMLDivElement>(null)
  const layersRef = useRef<HTMLDivElement>(null)
  const fxRef = useRef<HTMLDivElement>(null)

  // The band a collapsed arm's glyph asked for, honoured once and then released. It fires on
  // mount in the case that matters — a press on the strip or the handle opens the body in the
  // same batch that sets it — and again on a later press while the body is already up.
  useEffect(() => {
    if (band == null) return
    const el = band === 'fx' ? fxRef.current : band === 'scenery' ? sceneryRef.current : layersRef.current
    el?.scrollIntoView({ block: 'start' })
    onBandShown()
  }, [band, onBandShown])

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto px-2.5 py-2">
      {/* The programmer's scenery first: it is the resolver's top tier (scenery-programmer plan
          D2), above every value, Look and cue, and drawn rather than output — so it is its own band
          and not a row of the value stack, which governs channels. A band rather than a tab so it
          is in every arm, as the effects are. */}
      <RailSceneryBand ref={sceneryRef} projectId={projectId} />
      <div className="-mx-2.5 my-0.5 border-t" />
      <div ref={layersRef} className="flex items-center gap-1.5 px-0.5">
        <span className={cn(LABEL_CLASS, 'text-primary')}>Values</span>
        {/* The precedence rule, in two words; the sentence the stack's paragraph used to spend a
            line on is its hover. Session 1's rule for every band of this page. */}
        <span
          className="text-[9.5px] text-muted-foreground"
          title="Later layers win, and the values you set yourself win over all of them — for every attribute, intensity included. Across cues, HTP still governs intensity. Record writes this stack into a cue as its layers."
        >
          top wins
        </span>
      </div>
      <LocalValuesRow />
      <ProgrammerLookStack />
      {/* Layer order does not govern the value/effect boundary: effects are Layer 3 and values
          Layer 4, so a value above beats an effect below whatever the rows say. Drawn as a band
          across the rail rather than a caption, because it is the one thing dragging cannot
          change; per-layer stomp is the escape hatch, on the row. */}
      {/* The FX band's scroll anchor is this bar and not the `Effects` label below it: the bar is
          what introduces the half, so landing on the label instead would open the band with the
          one rule that explains it just above the fold. */}
      <div
        ref={fxRef}
        className="-mx-2.5 mt-1 flex items-center gap-1.5 border-y border-amber-800/70 bg-amber-950/40 px-2.5 py-[5px]"
      >
        <ArrowDownUp className="size-3 shrink-0 text-amber-300" />
        <span className={cn(LABEL_CLASS, 'tracking-[0.06em] text-amber-300')}>
          Values above beat effects below
        </span>
      </div>
      <div className="flex items-center gap-1.5 px-0.5 pt-0.5">
        <span className={cn(LABEL_CLASS, 'text-violet-400')}>Effects</span>
      </div>
      <ProgrammerFxList />
      <div className="mt-auto pt-2">
        <button
          type="button"
          onClick={() => onDiagnosticOpenChange(!diagnosticOpen)}
          aria-expanded={diagnosticOpen}
          className="flex w-full items-center gap-1.5 rounded px-1 py-1 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent/30 hover:text-foreground"
        >
          {diagnosticOpen ? (
            <ChevronDown className="size-3.5" />
          ) : (
            <ChevronRight className="size-3.5" />
          )}
          Per-fixture FX
        </button>
        {/* Mounted only when open — see the component's doc comment for why. */}
        {diagnosticOpen && projectId > 0 && (
          <div className="pt-1">
            <FxSheet />
          </div>
        )}
      </div>
    </div>
  )
})

/**
 * The operator's own values, as the top row of the stack — because that is what they are: the
 * last layer, the one that beats every other, and the one Record takes.
 *
 * Painted in `--primary`, which on this page means exactly *you own this value* (space plan D4).
 * The name is the row's focus control, the way a layer row's name badge is: pressing it points the
 * grid at Local. `Make layer` lives here since session 3, moved off row B, because this row is
 * what it promotes; it is disabled rather than hidden with nothing to promote, since an affordance
 * that only appears once you already know to busk first teaches nobody.
 *
 * The row is on screen in every scope, so `Make layer` is reachable from Output and from a
 * focused layer, where row B's button was not (it returned null outside Local). The press
 * **points the grid at Local before opening the sheet**: promoting moves the Local entries into a
 * new layer and clears them, and a grid showing Output or a layer would have watched values it
 * was not displaying vanish. In Local it is the same one-step gesture it always was.
 */
function LocalValuesRow() {
  const count = useLocalValueCount()
  const scope = useProgrammerScope()
  const actions = useProgrammerScopeActions()
  const sheets = useProgrammerSheets()
  const focused = scope?.kind === 'local'
  return (
    <div
      className={cn(
        'flex items-center gap-1.5 rounded-md border border-primary/60 bg-primary/10 px-2 py-1.5 text-xs',
        focused && 'ring-1 ring-primary/60',
      )}
    >
      <Hand className="size-3.5 shrink-0 text-primary" />
      <button
        type="button"
        className="flex min-w-0 flex-1 flex-col text-left focus-visible:ring-1 focus-visible:ring-ring rounded"
        aria-pressed={focused}
        title={focused ? 'The grid is showing your values' : 'Show only the values you set'}
        onClick={() => actions?.setScope({ kind: 'local' })}
      >
        <span className="font-semibold">Local values</span>
        <span className="truncate text-[10px] text-primary/90">
          {count === 0 ? 'nothing yet' : `${count} value${count === 1 ? '' : 's'}`} · yours ·
          beats every layer
        </span>
      </button>
      <Button
        variant="outline"
        size="sm"
        className="h-[22px] shrink-0 px-1.5 text-[10px]"
        disabled={count === 0}
        onClick={() => {
          actions?.setScope({ kind: 'local' })
          sheets.openMakeLayer()
        }}
        title={
          count === 0
            ? 'Set some values first, then promote them into a shared look'
            : 'Save these values as a look and apply it here as a layer'
        }
      >
        <Layers className="size-3" />
        Make layer
      </Button>
    </div>
  )
}

/** The three doors, equal width. `+ Effect` says why when it cannot open. */
function RailFooter({
  onAdd,
  addEffect,
}: {
  onAdd: (kind: AddKind) => void
  addEffect: AddEffectOffer
}) {
  return (
    <div className="flex shrink-0 flex-col gap-1.5 border-t p-2">
      {/* The hand's target for the programmer's stack (multi-screen plan §3.5). Above the three
          doors and drawn only while something that can become a layer is held, so the footer keeps
          its one row the rest of the time. */}
      <HandProgrammerLayerStrip />
      <div className="flex gap-1.5">
      <Button
        variant="outline"
        size="sm"
        className="h-7 flex-1 px-1.5 text-[11px]"
        aria-label="Add a look layer"
        onClick={() => onAdd('look')}
      >
        <Plus className="size-3" />
        Look
      </Button>
      <Button
        variant="outline"
        size="sm"
        className="h-7 flex-1 px-1.5 text-[11px]"
        aria-label="Add a template layer"
        onClick={() => onAdd('template')}
      >
        <Plus className="size-3" />
        Template
      </Button>
      {/* The trigger is a span around the button, not the button: `Button` carries
          `disabled:pointer-events-none`, so a disabled trigger is never hovered and the tooltip —
          whose whole job is to say *why* the door is shut — could never open in exactly the state
          it exists for. The span takes the hover; the button keeps `disabled` for the keyboard. */}
      <Tooltip>
        <TooltipTrigger asChild>
          <span className="flex flex-1">
            <Button
              variant="outline"
              size="sm"
              className="h-7 w-full px-1.5 text-[11px]"
              aria-label="Add an effect"
              disabled={addEffect.disabled}
              onClick={() => onAdd('effect')}
            >
              <Plus className="size-3" />
              Effect
            </Button>
          </span>
        </TooltipTrigger>
        <TooltipContent>{addEffect.reason}</TooltipContent>
      </Tooltip>
      </div>
    </div>
  )
}

/**
 * The 40px strip. Its chevron is two buttons, one per arm, like the header's: the docked arm's
 * expands, the narrow arm's toggles the overlay — the plan names the strip's chevron as a way to
 * close it as well as open it. The two counts split the same way, and for the same reason —
 * `StripCount` owns that split so each band is written once here.
 */
function RailStrip({
  layerCount,
  fxCount,
  sceneryCount,
  onAdd,
  onBand,
  addEffect,
}: {
  layerCount: number
  fxCount: number
  sceneryCount: number
  onAdd: (kind: AddKind) => void
  onBand: (band: RailBand) => void
  addEffect: AddEffectOffer
}) {
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  return (
    <>
      {/* The strip is only ever *visible* while the body is not (`RailStripFrame`) — under an open
          overlay it stays in the row, `invisible`, only so its 40px does not reflow the grid — so
          its chevron always opens: there is no "close" state for it to carry any more, and with it
          went the `aria-expanded` that told the two apart while both were up. */}
      {overlay ? (
        <Button
          variant="ghost"
          size="icon"
          className={SIDE_PANEL_STRIP_CELL_CLASS}
          aria-label="Open the rail"
          title="Show the layers and effects over the grid"
          onClick={arm.openOverlay}
        >
          <ChevronLeft className="size-3.5" />
        </Button>
      ) : (
        <>
          <Button
            variant="ghost"
            size="icon"
            className={cn(SIDE_PANEL_STRIP_CELL_CLASS, '@max-[1200px]:hidden')}
            aria-label="Expand the rail"
            title="Show the layers and effects"
            onClick={arm.expand}
          >
            <ChevronLeft className="size-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className={cn(SIDE_PANEL_STRIP_CELL_CLASS, '@min-[1200px]:hidden')}
            aria-label="Open the rail"
            title="Show the layers and effects over the grid"
            onClick={arm.openOverlay}
          >
            <ChevronLeft className="size-3.5" />
          </Button>
        </>
      )}
      <StripCount
        band="scenery"
        glyph={<Blinds className="size-3.5" />}
        count={sceneryCount}
        title={`${sceneryCount} held piece${sceneryCount === 1 ? '' : 's'} of scenery`}
        onBand={onBand}
      />
      <StripCount
        band="layers"
        glyph={<Layers className="size-3.5" />}
        count={layerCount}
        title={`${layerCount} layer${layerCount === 1 ? '' : 's'}`}
        onBand={onBand}
      />
      <StripCount
        band="fx"
        glyph={<AudioWaveform className="size-3.5" />}
        count={fxCount}
        title={`${fxCount} effect${fxCount === 1 ? '' : 's'} running`}
        onBand={onBand}
        className="text-violet-400"
      />
      {/* A glyph per tab, after the counts (`RailTabs.dc.html`, the collapsed strip): a press
          expands the rail onto that tab, the way the counts open it on a band. **Docked only**, as
          the tabs are — hidden in the narrow arm and not drawn in overlay mode, where the popover
          is the form. */}
      {!overlay && (
        <>
          <StripTabCell tab="colour" glyph={<Palette className="size-3.5" />} label="Expand the rail on the Colour tab" />
          <StripTabCell tab="spread" glyph={<Waves className="size-3.5" />} label="Expand the rail on the Spread tab" />
        </>
      )}
      <span className="flex-1" />
      <AddDoorsMenu
        onAdd={onAdd}
        addEffect={addEffect}
        label="Add a layer or an effect"
        title="Add a look, a template or an effect"
        side="left"
        // `h-10`, like the chevrons above it: this is the 40px strip, and the `+` is a cell of it.
        // It was `h-9` — four pixels short, and the one control in the strip the chrome tidy-up
        // missed, which put a seam at the bottom of the column it sits in.
        className="h-10 w-10 rounded-none"
      />
    </>
  )
}

/**
 * The three doors as a menu — the footer's row of buttons, for the two arms that have no footer
 * on screen: the 40px strip and the phone's 44px handle.
 *
 * One component rather than two copies, so the doors cannot drift apart; the *label* is the
 * caller's, because both arms can be in the DOM at once (they are hidden by container queries,
 * not by JavaScript) and two controls announced by the same words is the thing the chevrons above
 * already avoid.
 */
function AddDoorsMenu({
  onAdd,
  addEffect,
  label,
  title,
  side,
  className,
}: {
  onAdd: (kind: AddKind) => void
  addEffect: AddEffectOffer
  /** The `aria-label`, and the arm's own — see the note above. */
  label: string
  /**
   * The hover text, which is deliberately allowed to differ from the label and to be the *same*
   * on both arms: it can name all three doors where the label has to stay short enough to be a
   * distinct control name. Collapsing the two into one string cost the strip's `+` its
   * "Add a look, a template or an effect" hover, which is information the label does not carry.
   */
  title: string
  side: 'left' | 'top'
  className?: string
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className={cn('text-muted-foreground', className)}
          aria-label={label}
          title={title}
        >
          <Plus className="size-3.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side={side} align="end">
        <DropdownMenuItem onClick={() => onAdd('look')}>
          <Layers className="size-3.5" />
          Look
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => onAdd('template')}>
          <Palette className="size-3.5" />
          Template
        </DropdownMenuItem>
        {/* Disabled with the reason *written under it* rather than omitted: a menu that
            silently loses an entry teaches nobody why. Not a `title` — a disabled Radix item
            is `pointer-events-none`, so a native tooltip on it can never show. */}
        <DropdownMenuItem
          disabled={addEffect.disabled}
          onClick={() => onAdd('effect')}
          className="items-start"
        >
          <AudioWaveform className="mt-0.5 size-3.5" />
          <span className="flex max-w-[16rem] flex-col">
            <span>Effect</span>
            {addEffect.disabled && (
              <span className="text-[10px] leading-snug text-muted-foreground whitespace-normal">
                {addEffect.reason}
              </span>
            )}
          </span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * The phone arm's 44px bottom handle: `LAYERS n · FX n · the layer names · + · ^`.
 *
 * The names are the one thing here the 40px strip could not carry, and they are **reversed**
 * before they are joined — the layer array is `sortOrder` ascending and later wins, so a summary
 * of a stack the rail draws top-wins has to name the strongest first or it reads as the opposite
 * of the list it opens.
 *
 * The `+` is the strip's own menu, unchanged: every door has to be reachable without opening the
 * rail, and on this arm "opening the rail" covers the grid entirely. The chevron is the opener,
 * and it is a separate control from the `+` rather than the whole bar being a button, so the
 * menu's trigger is not nested inside a button that also opens the sheet — which is also why the
 * two counts are their own buttons rather than the bar being one.
 */
function RailHandle({
  layers,
  layerCount,
  fxCount,
  sceneryCount,
  onAdd,
  onBand,
  addEffect,
}: {
  layers: readonly ProgrammerLayer[] | undefined
  layerCount: number
  fxCount: number
  sceneryCount: number
  onAdd: (kind: AddKind) => void
  onBand: (band: RailBand) => void
  addEffect: AddEffectOffer
}) {
  const arm = useRailArm()
  const names = layers?.length
    ? [...layers]
        .reverse()
        .map((l) => l.source.name)
        .join(' · ')
    : null
  const open = (band: RailBand) => {
    onBand(band)
    arm.openSheet()
  }
  return (
    <>
      {/* `PD-SHEET-ICONS-OPEN`: on this arm the chevron was the only way in, so a press on the
          band you were reading did nothing. One button each rather than the strip's pair — the
          handle has a single arm, and `openSheet` is written from nowhere else. */}
      <button
        type="button"
        className={cn(LABEL_CLASS, 'rounded px-1 py-1 transition-colors hover:bg-accent/40')}
        title={`${layerCount} layer${layerCount === 1 ? '' : 's'}`}
        aria-label="Show the layers"
        onClick={() => open('layers')}
      >
        <Layers className="size-3" />
        Layers
        <CountBadge count={layerCount} />
      </button>
      <button
        type="button"
        className={cn(
          LABEL_CLASS,
          'rounded px-1 py-1 text-violet-400 transition-colors hover:bg-accent/40',
        )}
        title={`${fxCount} effect${fxCount === 1 ? '' : 's'} running`}
        aria-label="Show the effects"
        onClick={() => open('fx')}
      >
        <AudioWaveform className="size-3" />
        FX
        <CountBadge count={fxCount} />
      </button>
      {/* Scenery's door on the phone (scenery-programmer plan D17): the sheet opens at its band. A
          glyph and a count rather than the word, so the layer names keep the room they have. */}
      <button
        type="button"
        className={cn(LABEL_CLASS, 'rounded px-1 py-1 transition-colors hover:bg-accent/40')}
        title={`${sceneryCount} held piece${sceneryCount === 1 ? '' : 's'} of scenery`}
        aria-label="Show the scenery"
        onClick={() => open('scenery')}
      >
        <Blinds className="size-3" />
        <CountBadge count={sceneryCount} />
      </button>
      {/* `min-w-0` and a truncate: the names give before the counts and the two controls do, and
          they are the only thing on this bar whose length is the rig's business. */}
      <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground" title={names ?? undefined}>
        {names ?? 'No layers'}
      </span>
      <AddDoorsMenu
        onAdd={onAdd}
        addEffect={addEffect}
        label="Add a look, a template or an effect"
        title="Add a look, a template or an effect"
        side="top"
        className="size-8 shrink-0"
      />
      <Button
        variant="ghost"
        size="icon"
        className="size-8 shrink-0 text-muted-foreground"
        aria-label="Open the layers and effects"
        aria-expanded={arm.sheetOpen}
        title="Show the layers and effects"
        onClick={arm.openSheet}
      >
        <ChevronUp className="size-4" />
      </Button>
    </>
  )
}

/**
 * One band's glyph and count, as the two buttons that open the rail at it (`PD-SHEET-ICONS-OPEN`).
 *
 * **Two buttons, hidden by the arms' own container queries**, exactly as the two chevrons above
 * them are: `expand` writes the docked desk's stored preference and `openOverlay` does not, so a
 * single button covering both arms would have to know which one is on screen — and a wrong answer
 * would carry an iPad's press onto tomorrow's wide desk as a "collapsed" it never asked for.
 *
 * The split lives **here** rather than at the call site so the glyph, the count and the title are
 * written once per band. Written twice, a change to either twin — a new icon, a plural rule — is
 * one that has to be made by hand in two adjacent blocks, and the arm that missed it goes stale
 * silently. Only the label differs between the two on purpose: both arms are in the DOM at once
 * (they are hidden by CSS, not by JavaScript), and two controls announced by the same words is
 * what that whole split exists to avoid.
 */
function StripCount({
  band,
  glyph,
  count,
  title,
  onBand,
  className,
}: {
  band: RailBand
  glyph: ReactNode
  count: number
  title: string
  onBand: (band: RailBand) => void
  className?: string
}) {
  const arm = useRailArm()
  const noun = band === 'fx' ? 'effects' : band === 'scenery' ? 'scenery' : 'layers'
  const press = (open: () => void) => () => {
    onBand(band)
    open()
  }
  return (
    <>
      {/* Onto the Stack tab: the band is half of that tab's body, and the rail may have been
          collapsed from another one. */}
      <CountButton
        glyph={glyph}
        count={count}
        title={title}
        label={`Expand the rail at the ${noun}`}
        onClick={press(() => arm.openTab('stack'))}
        className={cn(className, '@max-[1200px]:hidden')}
      />
      <CountButton
        glyph={glyph}
        count={count}
        title={title}
        label={`Open the rail at the ${noun}`}
        onClick={press(arm.openOverlay)}
        className={cn(className, '@min-[1200px]:hidden')}
      />
    </>
  )
}

/** One tab's glyph on the collapsed strip: a 40px cell that expands the rail onto that tab. */
function StripTabCell({ tab, glyph, label }: { tab: RailTab; glyph: ReactNode; label: string }) {
  const { openTab } = useRailArm()
  return (
    <Button
      variant="ghost"
      size="icon"
      className={cn(SIDE_PANEL_STRIP_CELL_CLASS, '@max-[1200px]:hidden')}
      aria-label={label}
      title={label}
      data-rail-strip-tab={tab}
      onClick={() => openTab(tab)}
    >
      {glyph}
    </Button>
  )
}

function CountButton({
  glyph,
  count,
  title,
  label,
  onClick,
  className,
}: {
  glyph: ReactNode
  count: number
  title: string
  label: string
  onClick: () => void
  className?: string
}) {
  return (
    <button
      type="button"
      // Full width of the 40px strip, so the press target is the column rather than the glyph.
      className={cn(
        'flex w-full flex-col items-center gap-0.5 py-2 transition-colors hover:bg-accent/40',
        className,
      )}
      title={title}
      aria-label={label}
      onClick={onClick}
    >
      {glyph}
      <CountBadge count={count} />
    </button>
  )
}
