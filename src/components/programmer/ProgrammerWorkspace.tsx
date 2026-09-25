import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react'
import {
  clampPanelWidth,
  SIDE_PANEL_BODY_CLASS,
  SIDE_PANEL_ENTER_CLASS,
  SIDE_PANEL_MAX_WIDTH,
  SIDE_PANEL_MIN_WIDTH,
  SIDE_PANEL_OVERLAY_CLASS,
  SIDE_PANEL_STRIP_CLASS,
  useSidePanelResize,
} from '@/components/sheet/sidePanel'
import { SidePanelResizeHandle } from '@/components/sheet/SidePanelResizeHandle'
import type { SpreadSeed } from '@/components/editor/SpreadPanel'
import { useSidePanelMode } from '@/lib/sidePanelMode'
import { usePersistentState } from '@/hooks/usePersistentState'
import { cn } from '@/lib/utils'
import { RailTabClaimContext, type RailFocusRequest, type RailTab, type RailTabClaim } from './railTab'

/**
 * The rail's docked width, in px: the stored one is clamped into this range on every read. The
 * range itself is the shared panel's (`components/sheet/sidePanel.ts`) — the busk sheet drags
 * between the same two numbers, because the two are one instrument and neither had a reason of
 * its own for a different floor or ceiling. These names stay because the rail's own tests and
 * doc comments are written in them.
 */
export const RAIL_MIN_WIDTH = SIDE_PANEL_MIN_WIDTH
export const RAIL_MAX_WIDTH = SIDE_PANEL_MAX_WIDTH
export const RAIL_DEFAULT_WIDTH = 300
// Three more numbers govern the rail and are deliberately NOT constants here: the 1200px dock
// breakpoint, the 704px bottom-sheet breakpoint and the overlay's 300px width. All three are
// container-query geometry, so they live as Tailwind literals — `@min-[1200px]:` /
// `@max-[1200px]:` and `@min-[704px]:` / `@max-[704px]:` on the frames below and on the per-arm
// controls in `ProgrammerRail`, and `@max-[1200px]:w-[300px]` on the body frame. A JS constant
// beside them would read as the authority while moving nothing; `grep 1200px` / `grep 704px` is
// how the arms are found, and `ProgrammerWorkspace.test.tsx` pins the class strings.

const WIDTH_KEY = 'programmer.rail.width'
const COLLAPSED_KEY = 'programmer.rail.collapsed'

/** A stored width is data: a value from an older build, or a hand edit, is clamped rather than trusted. */
export function clampRailWidth(value: unknown): number {
  return clampPanelWidth(value, RAIL_DEFAULT_WIDTH)
}

/**
 * The rail's state and the gestures on it, as `ProgrammerRail` reads them.
 *
 * `collapsed`, `overlayOpen` and `sheetOpen` are three facts, not one with three names, and only
 * one of them is ever *drawn* at a time: the docked arm reads `collapsed`, the overlay arm reads
 * `overlayOpen`, the phone arm reads `sheetOpen`. They are written by different controls too —
 * `collapse` / `expand` are offered only where the rail docks, `openOverlay` / `closeOverlay`
 * only where it overlays, `openSheet` / `closeSheet` only from the bottom handle — so closing an
 * overlay on an iPad never writes "collapsed" into the preference a wide desk will read tomorrow,
 * and a phone's bottom sheet writes no preference at all.
 *
 * `sheetOpen` belongs **here and not in `RailGeometry`**: it is a gesture, like the other two, and
 * the geometry context exists solely to keep the dragged width away from everything that reads an
 * arm.
 *
 * **The width is not in here.** It changes sixty times a second under a drag, and `ProgrammerRail`
 * reads this context at its top, above every layer row and every FX row; a width in this object
 * would re-render all of them per pointer move. The width and the drag live in `RailGeometry`,
 * which only `RailBodyFrame` reads — and that frame's children are elements the rail already
 * rendered, so a re-render of the frame reaches none of them.
 */
export interface RailArm {
  collapsed: boolean
  overlayOpen: boolean
  /** The phone arm's bottom sheet. Transient, and reachable only from the bottom handle. */
  sheetOpen: boolean
  collapse: () => void
  expand: () => void
  openOverlay: () => void
  closeOverlay: () => void
  openSheet: () => void
  closeSheet: () => void
  /**
   * The face the rail draws — Stack, Colour or Spread (editor-kit plan session 4). **Docked only**:
   * this reads `'stack'` in overlay mode whatever was chosen, and `RailTabs` writes it back to
   * `'stack'` when the push-mode narrow arm hides the strip — both arms shut on the next press
   * outside them, which a picker over the grid has to survive. Collapsing resets it too, so a
   * collapsed rail never holds a tab it is not drawing.
   */
  railTab: RailTab
  /** A header tab's press. Writes the tab and nothing else. */
  setRailTab: (tab: RailTab) => void
  /**
   * Open the rail onto a tab — the strip's palette and wave cells, and the grid's claims. Expands
   * a collapsed rail as `expand` does, which is the docked arm's gesture: the strip cells that call
   * it are drawn only where the rail docks.
   */
  openTab: (tab: RailTab) => void
  /** A grid gesture a tab has claimed, for the tab to honour once and drop. See `RailTabClaim`. */
  focusRequest: RailFocusRequest | null
  consumeFocusRequest: () => void
  /** The Colour tab's *Spread…* hand-over (`SpreadSeed`), dropped by the Spread tab once read. */
  spreadSeed: SpreadSeed | null
  consumeSpreadSeed: () => void
  /** Open the Spread tab with From set — the claim's `spreadFrom`, and the Colour tab's footer. */
  spreadFrom: (from: { r: number; g: number; b: number }) => void
}

/** The docked width and the drag that sets it. Read by the body frame alone — see `RailArm`. */
export interface RailGeometry {
  /** The docked width: the stored one, or the live one while the handle is being dragged. */
  width: number
  /** A drag on the handle is in progress. */
  resizing: boolean
  /** The handle's `onPointerDown`: the rest of the drag lives on `window`. */
  onResizeStart: (e: React.PointerEvent) => void
}

const RailArmContext = createContext<RailArm | null>(null)
const RailGeometryContext = createContext<RailGeometry | null>(null)

/** The rail's arm. Throws outside `ProgrammerWorkspace` — nothing else has a rail to read. */
export function useRailArm(): RailArm {
  const arm = useContext(RailArmContext)
  if (arm == null) throw new Error('useRailArm must be used inside <ProgrammerWorkspace>')
  return arm
}

function useRailGeometry(): RailGeometry {
  const geometry = useContext(RailGeometryContext)
  if (geometry == null) throw new Error('RailBodyFrame must be used inside <ProgrammerWorkspace>')
  return geometry
}

/**
 * The grid and the rail, on one screen.
 *
 * The point of the whole view: values, layers and effects were three tabs of a collapsed pane, so
 * the three readings of one live object could never be seen together, and editing values while
 * watching the layer stack that produced them was impossible by construction.
 *
 * **This component owns the rail's state, and it must stay here.** `programmer.rail.width`
 * (260–480, default 300) and `programmer.rail.collapsed` persist per desk; `overlayOpen` is
 * transient. All three are new pieces of state that sit *inside* `ProgrammerBody`'s memo barrier
 * — held above it, in `ProgrammerPage`, every re-render of that component would reach the grid
 * again, and `useShowBarProps` up there moves on every cue change. That used to read "every
 * ShowBar re-render", which was the loudest source of them until the space plan's session 5
 * took the bar off this page; the barrier is no less load-bearing for it, because the hook
 * still runs there and still feeds `ShowHeader`. The
 * `grid` and `rail` elements are created by `ProgrammerBody` and arrive here as props, so a state
 * change here re-renders this frame and nothing inside the grid. The rail reads two contexts, and
 * which one carries what is the reason there are two: `RailArm` (the flags) changes on a gesture
 * and may re-render the rail; `RailGeometry` (the width) changes per pointer move and reaches only
 * `RailBodyFrame`, whose children are the rail's already-rendered elements.
 *
 * **Three arms, by the workspace's own width** (space plan D6 and D8), all container queries on
 * the child of the `@container` wrapper — see the trap below:
 *
 * - **≥1200px, docked.** The rail sits beside the grid at the stored width, with a 5px handle on
 *   its left edge that sets it. Collapsed, it is a 40px strip carrying the two counts and a `+`.
 * - **≥1200px, collapsed.** The strip alone; the grid takes the rest.
 * - **704–1200px.** The strip always, and opening it mounts the rail as a 300px `absolute` overlay
 *   with a shadow over the grid's right edge, left of the strip. It closes from the strip's
 *   chevron, from its own header's chevron, on Escape, or on a press anywhere on the grid.
 * - **<704px — the phone.** The row turns into a column: the grid takes the whole width and the
 *   rail becomes a 44px **handle across the bottom**, which opens the same body in a
 *   `Sheet side="bottom"` at 80% height. A 300px overlay over a 393px screen is not an overlay,
 *   it is a takeover with a sliver of grid showing at its left, and the 40px right strip took an
 *   eighth of the value columns for two badges.
 *
 * **704px is the workspace's width, not the viewport's**, because that is what a container query
 * can ask. With the sidebar on its 64px rail — which is where D7 starts every live view — 704 of
 * workspace is a **768px viewport**, i.e. Tailwind's `md`, which is what the plan says. Below
 * `md` the sidebar is off-canvas and the workspace *is* the viewport, so on a phone the same
 * number is read directly: 393 and 852 land either side of it exactly as the `Phone` and
 * `PhoneLandscape` artboards do.
 *
 * The rail stays on the **right**: that keeps `FixturesTable`'s sticky name column against the
 * page edge, and it is the only side that can collapse without moving the grid.
 *
 * **The `@max-[900px]` stacking arm is gone, not kept beside the overlay.** It dropped the rail
 * *beneath* the grid, gave the grid a `min-h-[26rem]` floor and made the whole column the page
 * scroller. On an iPad in portrait that put the layers off the bottom of the screen behind a
 * floor of fixture rows, which is the defect D6 exists to remove — and keeping it as a fourth arm
 * would have meant two different answers to "where do the layers go on a narrow page", switching
 * at a width nobody would remember. The overlay answers it at every narrow width: the grid is the
 * scroller everywhere, the rail scrolls itself, and nothing is ever below the fold.
 *
 * **No padding and no gap**, since session 1. `p-4` and `gap-3` cost 16px of height, 32px of width
 * and a 12px trench down the middle of a page whose whole point is the grid. The seam between the
 * two columns is the rail's `border-l` — a line, not a gutter.
 *
 * **The `@container` is a wrapper, and the queried classes are on its children.** A container
 * query matches an element's *ancestor* containers, never the element that declares the
 * container, so `@max-[900px]:flex-col` on the same element as `@container` never fired — while
 * the rail's `@max-[900px]:w-full`, one level down, did. Below 900px that left the row direction
 * with a full-width rail in it: the grid was flexed to zero width, its toolbar and legend rendered
 * at min-content in a sliver at the left, and the rail painted over them. Found on a desk at a
 * narrow window, three sessions after it shipped. Every query here — the frames' arms, the strip's
 * per-arm chevrons — is on a descendant of the wrapper for that reason, and the rail declares no
 * `@container` of its own, so its queries measure the workspace and not the rail.
 *
 * **The drag ends on `pointercancel` exactly as on `pointerup`** — the busk speed rail's lesson.
 * On a touchscreen a drag the browser reclaims as a pan ends with no release at all, and a frame
 * left `resizing` would keep its window listeners and write a width on the next pointer movement
 * anywhere on the page with nothing held down. The live width is written by the pointer handlers
 * into a ref as well as state, never at render time: a fast drag can dispatch the last move and
 * the release inside one task, and a ref assigned during render would commit the width from the
 * move *before* last.
 */
export function ProgrammerWorkspace({
  header,
  grid,
  rail,
}: {
  /**
   * The page's chrome above the grid — row A — drawn in the grid's column, so the rail sits beside
   * it rather than under it. Null where there is none (the short viewport folds row A into row B).
   */
  header?: ReactNode
  grid: ReactNode
  rail: ReactNode
}) {
  const [collapsed, setCollapsed] = usePersistentState<boolean>(COLLAPSED_KEY, false)
  const [overlayOpen, setOverlayOpen] = useState(false)
  const [sheetOpen, setSheetOpen] = useState(false)
  // The rail's tab (editor-kit plan session 4) — plain state, **not persisted** (call 10): every
  // arrival rests on Stack. The two one-shots beside it carry a grid gesture a tab claimed and the
  // Colour tab's *Spread…* hand-over; each is dropped by the tab that reads it.
  const [chosenTab, setChosenTab] = useState<RailTab>('stack')
  const [focusRequest, setFocusRequest] = useState<RailFocusRequest | null>(null)
  const [spreadSeed, setSpreadSeed] = useState<SpreadSeed | null>(null)
  // The tabs are docked-only, and overlay mode is JS's to know — so the fact reads Stack there
  // outright. Push mode's narrow arm is CSS's, and `RailTabs` writes the fact back when it sees
  // that arm hide its strip.
  const overlayMode = useSidePanelMode() === 'overlay'
  const railTab: RailTab = overlayMode ? 'stack' : chosenTab
  // The drag is `useSidePanelResize`'s, shared with the busk view's side sheet — the four rules
  // that make it behave (window listeners, keyed on the boolean, `pointercancel`, the ref written
  // by the handlers) are stated once there rather than once per panel.
  const { width, resizing, onResizeStart } = useSidePanelResize({
    storageKey: WIDTH_KEY,
    fallback: RAIL_DEFAULT_WIDTH,
  })

  // Escape closes the overlay. `defaultPrevented` is how a sheet or a popover open above it says
  // it took the key: Radix's dismissable layer prevents the default on the Escape it handles, so
  // one press closes the sheet and leaves the rail, rather than both.
  useEffect(() => {
    if (!overlayOpen) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented) setOverlayOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [overlayOpen])

  const openTab = useCallback(
    (tab: RailTab) => {
      setChosenTab(tab)
      setCollapsed(false)
    },
    [setCollapsed],
  )
  const spreadFrom = useCallback(
    (from: { r: number; g: number; b: number }) => {
      setSpreadSeed((prev) => ({ from: { r: from.r, g: from.g, b: from.b }, key: (prev?.key ?? 0) + 1 }))
      openTab('spread')
    },
    [openTab],
  )
  const consumeFocusRequest = useCallback(() => setFocusRequest(null), [])
  const consumeSpreadSeed = useCallback(() => setSpreadSeed(null), [])

  const arm = useMemo<RailArm>(
    () => ({
      collapsed,
      overlayOpen,
      sheetOpen,
      collapse: () => {
        setCollapsed(true)
        setChosenTab('stack')
      },
      expand: () => setCollapsed(false),
      openOverlay: () => setOverlayOpen(true),
      closeOverlay: () => setOverlayOpen(false),
      openSheet: () => setSheetOpen(true),
      closeSheet: () => setSheetOpen(false),
      railTab,
      setRailTab: setChosenTab,
      openTab,
      focusRequest,
      consumeFocusRequest,
      spreadSeed,
      consumeSpreadSeed,
      spreadFrom,
    }),
    [collapsed, overlayOpen, sheetOpen, setCollapsed, railTab, openTab, focusRequest, consumeFocusRequest, spreadSeed, consumeSpreadSeed, spreadFrom],
  )
  // The grid's half, keyed on the drawn tab alone so a rail gesture never re-renders the grid.
  const claim = useMemo<RailTabClaim>(
    () => ({
      tab: railTab,
      focusColour: (seed: string) => setFocusRequest((prev) => ({ tab: 'colour', seed, key: (prev?.key ?? 0) + 1 })),
      focusSpread: () => setFocusRequest((prev) => ({ tab: 'spread', seed: '', key: (prev?.key ?? 0) + 1 })),
      spreadFrom,
    }),
    [railTab, spreadFrom],
  )
  const geometry = useMemo<RailGeometry>(
    () => ({ width, resizing, onResizeStart }),
    [width, resizing, onResizeStart],
  )

  return (
    // The claim wraps **both** children: the grid reads it to hand a claimed open to a tab, the
    // rail to know which tab it is drawing. The marquee travels the other way, through
    // `ProgrammerPage`'s marquee store.
    <RailTabClaimContext.Provider value={claim}>
      <div className="@container flex min-h-0 flex-1 flex-col">
        {/* `relative` is the overlay's containing block. `select-none` while resizing keeps the
            drag from painting a text selection across the grid it crosses. */}
        {/* `@max-[704px]:flex-col` is the whole of the phone arm's layout: the same two children,
            stacked, so the rail's strip frame lands *under* the grid as a full-width bar instead of
            beside it as a column. Nothing is hoisted, nothing is portalled, and the grid element
            never moves in the tree. */}
        <div
          className={cn(
            'relative flex min-h-0 flex-1 @max-[704px]:flex-col',
            resizing && 'cursor-col-resize select-none',
          )}
        >
          {/* The grid's column, and the page's own chrome above the grid in it: the rail stands
              beside the view's menus and under nothing but the `ShowHeader`, as the busk sheet
              stands beside the rig band. Row A was drawn above this whole row until the rail and
              the sheet were made to agree, so the rail started a row lower than the sheet did. */}
          <div className="flex min-h-0 min-w-0 flex-1 flex-col">
            {header}
            {/* Capture, not bubble: a press on a cell that stops propagation must still close the
                overlay, and the press itself goes on to land. In the wide arm `overlayOpen` is only
                ever stale-true, and clearing it there changes nothing on screen. The header is
                outside it on purpose: row A's verbs — Record, Clear, Blind — are presses about the
                programmer the rail shows, and closing the rail under them was never the rule. */}
            <div
              className="flex min-h-0 min-w-0 flex-1 flex-col"
              onPointerDownCapture={overlayOpen ? arm.closeOverlay : undefined}
            >
              {grid}
            </div>
          </div>
          <RailArmContext.Provider value={arm}>
            <RailGeometryContext.Provider value={geometry}>{rail}</RailGeometryContext.Provider>
          </RailArmContext.Provider>
        </div>
      </div>
    </RailTabClaimContext.Provider>
  )
}

/**
 * Where the rail body sits: docked at the stored width in the wide arm, an overlay over the grid
 * in the narrow one, hidden in each when its own flag says so. The geometry lives here, beside
 * the state that drives it; what goes *in* the frame is `ProgrammerRail`'s.
 *
 * The width reaches the docked arm through a CSS variable rather than an inline `width`, because
 * an inline width could be overridden by nothing — the narrow arm has to be able to set its own.
 *
 * `enter` is **required, not optional**: a caller that forgot it would get a panel that silently
 * never animates, which reads as a plausible result rather than a mistake, and the compiler is
 * the only thing that can tell the difference. It comes from `usePanelEnter`, which has to be
 * called above this frame — see the hook's own comment for why.
 *
 * Mounted by `ProgrammerRail` only while it can be seen in some arm (`!collapsed || overlayOpen`),
 * and not at all while the phone arm's sheet is open — that arm renders the same body inside the
 * sheet, and mounting it twice would be two layer lists, two FX lists and two subscriptions to
 * each. The one case that leaves it mounted and hidden — an arm where its own flag says show and
 * the container query says otherwise — is accepted: nothing in the rail owns a selection, and the
 * alternative is a JS measurement of the width the container query already answers.
 */
export function RailBodyFrame({ children, enter }: { children: ReactNode; enter: boolean }) {
  const arm = useRailArm()
  const mode = useSidePanelMode()
  const { width, onResizeStart } = useRailGeometry()
  const style = { '--rail-w': `${width}px` } as CSSProperties
  const overlay = mode === 'overlay'
  return (
    <div
      role="complementary"
      aria-label="Layers and effects"
      data-rail-mode={overlay ? 'overlay' : 'push'}
      style={style}
      className={cn(
        // The shared docked-panel body — the busk view's side sheet is the same column, and
        // `components/sheet/sidePanel.ts` says why the fill is opaque rather than `bg-card/40`.
        SIDE_PANEL_BODY_CLASS,
        // The enter animation, latched by `usePanelEnter` in `ProgrammerRail` — it has to be
        // computed there, above the conditional mount, or expanding a collapsed rail could never
        // be told from the view's first render.
        enter && SIDE_PANEL_ENTER_CLASS,
        // **Overlay mode: one arm at every width the rail is drawn at.** The operator asked for
        // the panel over the grid, so there is no 1200px switch to make and `overlayOpen` is the
        // only flag — `collapsed` says nothing here, which is why the header and the strip draw
        // a single chevron in this mode rather than the docked/overlay pair.
        overlay && [
          SIDE_PANEL_OVERLAY_CLASS,
          // The operator's own width: they chose to float it, so the drag they set still applies.
          // Push mode's narrow arm keeps its fixed 300 — there the *width* forced the overlay,
          // and a stored 480 would leave a 704px workspace 224px of grid.
          'w-[var(--rail-w)]',
          !arm.overlayOpen && 'hidden',
        ],
        // **Push mode: the width decides, as it always did.** The mode can only make the rail
        // float where it would have docked, never dock where a 300px column would leave the grid
        // 400px — so the ≥1200 / <1200 pair below is untouched by it.
        !overlay && [
          // ≥1200: beside the grid at the stored width, or gone when collapsed.
          '@min-[1200px]:relative @min-[1200px]:w-[var(--rail-w)] @min-[1200px]:shrink-0',
          arm.collapsed && '@min-[1200px]:hidden',
          // <1200: over the grid's right edge, and gone unless opened.
          '@max-[1200px]:absolute @max-[1200px]:inset-y-0 @max-[1200px]:right-0 @max-[1200px]:z-20 @max-[1200px]:w-[300px] @max-[1200px]:shadow-[-12px_0_32px_rgba(0,0,0,0.55)]',
          !arm.overlayOpen && '@max-[1200px]:hidden',
        ],
        // <704: never here. The body is the bottom sheet's, and the sheet is a portal.
        '@max-[704px]:hidden',
      )}
    >
      {/* The 5px handle, straddling the border. It is drawn wherever the width on screen is the
          stored one: in overlay mode always, and in push mode only at the width that docks —
          push mode's narrow arm is a fixed 300px overlay that a drag would not move. */}
      <SidePanelResizeHandle
        label="the rail"
        onResizeStart={onResizeStart}
        className={overlay ? undefined : '@max-[1200px]:hidden'}
      />
      {children}
    </div>
  )
}

/**
 * The 40px strip: on screen whenever the body is not docked — collapsed in the docked arm, always
 * in the overlay one, and never on the phone, where the bottom handle takes its place.
 */
export function RailStripFrame({ children }: { children: ReactNode }) {
  const arm = useRailArm()
  const overlay = useSidePanelMode() === 'overlay'
  return (
    <div
      className={cn(
        SIDE_PANEL_STRIP_CLASS,
        // **The strip and the body are never both up.** It used to stand beside the open overlay
        // at 704–1200, which is what the busk sheet has never done — there the fold *is* the
        // closed state of the panel, and the two are one control in two shapes. Reported from the
        // desk as the rail looking wrong beside it, and the sheet's reading is the one kept.
        // In push mode that is the `@max-[1200px]` arm; in overlay mode there is only one arm.
        //
        // **Under an open overlay the strip is `invisible`, never `hidden`.** The overlay is
        // absolute, so it takes no room of its own; a strip taken out of the flow as it opened
        // gave its 40px back to the grid, and every column beside it reflowed on each open and
        // close — the one thing an overlay exists not to do. Invisible, the strip keeps its box
        // (the overlay, flush `right-0` and wider than 40, covers it) and leaves the tab order and
        // the accessibility tree as surely as `display: none` did. The docked arm stays `hidden`:
        // there the body is in the flow and takes the room itself.
        overlay
          ? arm.overlayOpen && 'invisible'
          : [!arm.collapsed && '@min-[1200px]:hidden', arm.overlayOpen && '@max-[1200px]:invisible'],
        '@max-[704px]:hidden',
      )}
    >
      {children}
    </div>
  )
}

/**
 * The phone arm's 44px handle, across the bottom of the page.
 *
 * A sibling frame rather than a mode of `RailStripFrame`, for the same reason the two chevrons
 * are two buttons: the arms are CSS, so both are always in the tree and each is hidden where it
 * does not belong. It is the row's *second* child either way — the row goes `flex-col` below
 * 704px, which is what puts this under the grid instead of beside it — so nothing about the
 * grid's position changes as the arm does.
 *
 * 44px, not the strip's 40: this one is a touch target rather than a column of glyphs. Its gap
 * is the chrome system's 8 between controls, like every other row of the programmer.
 *
 * **It closes the sheet when it stops being drawn**, and that is not decoration. `sheetOpen` is
 * the one arm flag a stale `true` is not harmless for: `collapsed` and `overlayOpen` are read by
 * frames whose *arms are CSS*, so a stale one changes nothing on screen — but `ProgrammerRail`
 * picks the sheet with a JS ternary, so a sheet opened on a phone that is then rotated or resized
 * past 704px would keep covering 80% of a desktop layout **and** skip the docked frame, with the
 * only control that closes it hidden by the query above. (It is still dismissible — Escape, a
 * click outside, the sheet's own X — so this is a wrong picture, not a trap.)
 *
 * It watches **this element's own height**, not the workspace's width, and that is the point: the
 * 704 lives in exactly one place, the Tailwind class above, so there is no JS threshold beside it
 * to drift. Nor is it the width measurement the plan forbids — that rule is about *choosing* an
 * arm, and this reads the arm CSS has already chosen. `ResizeObserver` is guarded for jsdom, which
 * lays nothing out and would otherwise report every handle as hidden and close every sheet on
 * arrival.
 */
export function RailHandleFrame({ children }: { children: ReactNode }) {
  const arm = useRailArm()
  const ref = useRef<HTMLDivElement>(null)
  const { sheetOpen, closeSheet } = arm

  useEffect(() => {
    const el = ref.current
    if (!sheetOpen || !el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      // `display: none` reports a zero box, which is how the container query's answer reaches JS.
      if (el.getBoundingClientRect().height === 0) closeSheet()
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [sheetOpen, closeSheet])

  return (
    <div
      ref={ref}
      className="flex h-11 shrink-0 items-center gap-2 border-t bg-card/40 px-3 @min-[704px]:hidden"
    >
      {children}
    </div>
  )
}
