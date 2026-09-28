import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { usePersistentState } from '@/hooks/usePersistentState'

/**
 * **One docked side panel, stated once** — the programmer's rail and the busk view's side sheet.
 *
 * The two are the same instrument: a column against the right edge of a live view, folded to a
 * narrow strip of glyphs when it is not wanted, carrying a 40px header over a scroller. They were
 * built a session apart and had drifted in every measurement that is supposed to be the chrome
 * system's — the rail's body is an opaque fill on a 40px header with 40px strip cells, the sheet's
 * was a transparent column on a 36px header with a 44px strip and `p-1` glyph buttons — so the two
 * read as two surfaces rather than one idea in two places. `sheetFrame.ts` is the precedent and
 * the model: the class strings live here, each surface imports the ones it needs, and a surface
 * that wants to differ says so at its own import.
 *
 * What is deliberately **not** here: the panel's state. The rail's `collapsed` is a persisted desk
 * preference in `ProgrammerWorkspace`; the sheet's fold is `busk.sheet === 'none'`, a per-tab fact
 * that rides the window announce and is written by ⌘K, the Screens sheet and MIDI besides. Those
 * are different facts about different things and folding them together would make one surface's
 * fold reach the other's view.
 */

/**
 * The panel body: one left edge, and an **opaque** fill mixed from the card tint and the page
 * background rather than `bg-card/40`. Docked, a 40% tint over the page reads the same — but both
 * panels have an overlay arm that sits over live content, and a translucent panel let the row
 * beneath it bleed through.
 */
export const SIDE_PANEL_BODY_CLASS =
  'flex min-h-0 flex-col border-l bg-[color-mix(in_oklab,var(--card)_40%,var(--background))]'

/**
 * **There is no header class here, deliberately.** A panel's header is a 40px chrome row like any
 * other, so both surfaces import `CHROME_ROW_CLASS` from `sheetFrame.ts` directly. A
 * `SIDE_PANEL_HEADER_CLASS` beside it would have been byte-for-byte the same string under a
 * second name — one measurement stated twice, which is the drift this module and that one both
 * exist to close.
 */

/**
 * The folded strip: 40px of glyphs, not the 44px of a touch handle. The programmer's phone arm is
 * the one that is 44 (`RailHandleFrame`), and it says why — it is a target for a finger, where
 * this is a column read with a mouse on the desk board. Both panels' strips are drawn only on a
 * board wide enough to dock, so neither is ever the phone's.
 */
export const SIDE_PANEL_STRIP_CLASS = 'flex w-10 shrink-0 flex-col items-center border-l bg-card/40'

/** A cell of the strip — a full-width 40px square with the strip's own dividing line under it. */
export const SIDE_PANEL_STRIP_CELL_CLASS =
  'h-10 w-10 shrink-0 rounded-none border-b text-muted-foreground'

/** The header's own chevron: the third control tier, a control inside a control. */
export const SIDE_PANEL_HEADER_BUTTON_CLASS = 'size-6 text-muted-foreground'

/**
 * A header tab's word: drawn at 400px of tab strip and up, and below that only on the **open** tab
 * (busk-chrome plan D3). Both panels' headers are tab strips — the busk sheet's four tabs and the
 * rail's Stack · Colour · Spread — and each strip's **unpadded wrapper** is the `@container`
 * (`sheetFrame.ts`'s convention for a chrome row: a size query measures the content box, and the
 * row's own `px-3` would fire the threshold 24px early). Every tab keeps its glyph at every width.
 *
 * It lived in the busk sheet until the rail took a strip of its own (editor-kit plan session 4);
 * the rule is the panels' shared chrome, so it moved here rather than being copied. On the rail
 * the strip is narrower than the rail by the mode toggle and the chevron, so at the rail's 480
 * ceiling it is still under 400 — the fold holds at every width the rail has, which is what the
 * board draws.
 */
export function tabWordClass(open: boolean): string {
  return open ? 'inline' : 'hidden @[400px]:inline'
}

/**
 * Where an **overlay**-mode panel sits: over the content, against the right edge, under nothing.
 * Both surfaces hide their strip while the panel is up (`SIDE_PANEL_MODE`), so this is flush
 * `right-0` rather than inset by a strip width.
 *
 * No scrim, and it must not gain one: the rail overlays a grid the operator goes on clicking, and
 * the sheet overlays pads they go on pressing. That is also why neither uses the `Sheet` primitive
 * here — a Radix dialog is modal, and off the desk board (where it *is* used) the surface really
 * is one thing at a time.
 */
export const SIDE_PANEL_OVERLAY_CLASS =
  'absolute inset-y-0 right-0 z-20 shadow-[-12px_0_32px_rgba(0,0,0,0.55)]'

/**
 * The enter animation, played once when the panel opens: the panel's **whole width**, slid in from
 * the right, plus a fade.
 *
 * It was `slide-in-from-right-4` — 16px — and the desk's verdict was that neither panel felt like
 * it was animating at all, which was right. 16px of travel is smaller than the thing that happens
 * beside it: the content reflows by the panel's full width in a single frame, so the eye reads the
 * jump and never registers the slide. A full-width slide is what the app's own `Sheet` primitive
 * uses (`slide-in-from-right`, bare), and it is the reason a sheet reads as arriving.
 *
 * **The content's reflow is still instant, in push mode, and that is a deliberate limit.** Making
 * the page open smoothly means animating the panel's `width`, which relayouts a virtualised grid
 * on every frame, needs `overflow: hidden` (which would clip the rail's resize handle, drawn 3px
 * outside its own left edge) and needs an inner fixed-width wrapper or the header's tabs reflow
 * all the way in. What the operator sees instead is the space opening at once and the panel
 * arriving into it — the ordinary behaviour of every sheet on this desk.
 *
 * There is deliberately **no exit animation**. An exiting panel has to stay mounted for the length
 * of it, which for these two means holding a layer list, an FX list and their subscriptions —
 * or a colour picker mid-drag — alive after the operator has asked for them to go. The ask was to
 * animate the opening, and opening is the half that can be done without keeping state alive past
 * its welcome.
 *
 * **The duration and the easing are arbitrary *animation* properties, never `duration-300` and
 * `ease-out`, and that is not a style preference.** Those two utilities set the transition
 * properties as well — `tailwindcss-animate` re-declares `duration-*` as `animation-duration`,
 * and Tailwind's own `duration-*` sets `transition-duration` — and CSS's initial
 * `transition-property` is `all`. So a panel carrying them transitions **every** property over
 * 300ms, and because `usePanelEnter` latches the class for as long as the panel is open, that
 * lasts the whole visit. What it broke was the resize: dragging the handle set a new width every
 * frame and each one was *transitioned* to, so the panel lagged behind the pointer and eased
 * toward wherever it had last been told to go. Reported from the desk as the drag feeling like an
 * animation rather than a drag. Nothing here may reintroduce a bare `duration-*` or `ease-*`.
 */
export const SIDE_PANEL_ENTER_CLASS =
  'animate-in fade-in-0 slide-in-from-right [animation-duration:300ms] [animation-timing-function:cubic-bezier(0,0,0.2,1)]'

/**
 * Whether the panel should play its enter animation this render.
 *
 * True from the render in which `open` turns on until it turns off again — **latched**, not
 * derived per render, and that is the whole point: `animate-in` is a class, so a re-render that
 * dropped it mid-flight would cut the animation off part-way, and both panels re-render freely
 * while open (a layer arrives, a marquee moves, a tempo ticks).
 *
 * False on the **first** render whatever `open` says, so a panel that is already open when the
 * view mounts is simply there rather than sliding in on every arrival at the route. That is the
 * common case on both surfaces — the rail's collapsed flag is a stored preference, the sheet's
 * tab is a stored per-window fact — so without it the animation would fire on every navigation
 * and on every reload.
 *
 * This is React's documented "adjust state while rendering" pattern rather than an effect: an
 * effect runs after paint, so the class would land one frame late and the panel would flash into
 * its final position before sliding from it.
 *
 * It must be called from a component that **outlives the panel**, since a panel unmounted while
 * folded takes any hook inside it along: `ProgrammerRail` and `SideSheet` both render the strip
 * or the body and stay mounted across the swap, which is why the flag is computed there and
 * handed down rather than read inside the frame.
 */
export function usePanelEnter(open: boolean): boolean {
  const [previous, setPrevious] = useState(open)
  const [enter, setEnter] = useState(false)
  if (previous !== open) {
    setPrevious(open)
    setEnter(open)
  }
  return enter
}

/**
 * The width a docked panel may be dragged to. One range for both, because they are one
 * instrument and neither has a reason of its own for a different floor or ceiling: below ~260 a
 * layer row's name and a colour picker both stop fitting, and above ~480 either panel is taking
 * more of a desk screen than the thing it annotates.
 */
export const SIDE_PANEL_MIN_WIDTH = 260
export const SIDE_PANEL_MAX_WIDTH = 480

/**
 * A stored width is data: a value from an older build, or a hand edit, is clamped rather than
 * trusted. `min` is the panel's own floor where it needs one above the shared 260 — see
 * `useSidePanelResize`.
 */
export function clampPanelWidth(
  value: unknown,
  fallback: number,
  min: number = SIDE_PANEL_MIN_WIDTH,
): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return Math.max(min, fallback)
  return Math.min(SIDE_PANEL_MAX_WIDTH, Math.max(min, Math.round(value)))
}

/**
 * **The drag that sets a docked panel's width** — the programmer rail's, lifted out so the busk
 * view's side sheet is resized by the same code rather than by a second copy of it.
 *
 * Both panels sit against the **right** edge, so dragging the handle leftwards grows them; a
 * left-hand panel would need the sign flipped and there is deliberately no option for one until
 * something needs it.
 *
 * Four properties are load-bearing, and each was a bug in the rail's own version first:
 *
 * - **The rest of the drag lives on `window`.** The pointer leaves a 5px handle on the first
 *   movement, and the release very often happens over the content beside it.
 * - **The effect is keyed on `resizing`, the boolean, never on the width** — keyed on the width,
 *   every move would tear the listeners down and rebuild them.
 * - **`pointercancel` ends it exactly as `pointerup` does.** On a touchscreen a drag the browser
 *   reclaims as a pan ends with no release at all, and a panel left `resizing` would keep its
 *   window listeners and write a width on the next pointer movement anywhere on the page with
 *   nothing held down.
 * - **The live width is written by the pointer handlers into a ref as well as state, never at
 *   render time.** A fast drag can dispatch the last move and the release inside one task with no
 *   re-render between, and a ref assigned during render would commit the width from the move
 *   *before* last.
 *
 * The stored value is committed **once, on release**: `usePersistentState` writes storage on every
 * change and a drag is sixty of them a second. It is `localStorage` and not the per-tab
 * `sessionStorage` that `lib/sidePanelMode.ts` uses, because a width is a fact about this desk's
 * screen rather than about which of two windows you are looking at.
 *
 * **Call it from a component the panel's contents do not re-render with.** The width changes at
 * pointer rate, so whatever reads it re-renders at pointer rate: the rail's `RailBodyFrame` and
 * the sheet's `DockedSideSheet` both take their contents as `children`, so a re-render reuses
 * those element references and React skips the subtrees underneath.
 */
export interface SidePanelResize {
  /** The committed width, or the live one while the handle is being dragged. */
  width: number
  /** A drag is in progress — the cursor and `select-none` follow this. */
  resizing: boolean
  /** The handle's `onPointerDown`; the rest of the drag is on `window`. */
  onResizeStart: (e: ReactPointerEvent) => void
}

export function useSidePanelResize({
  storageKey,
  fallback,
  min = SIDE_PANEL_MIN_WIDTH,
}: {
  storageKey: string
  fallback: number
  /**
   * This panel's own floor, where the shared 260 is too narrow for its chrome. The **header** is
   * usually what sets it, not the body: the rail's is two short labels with badges and fits at
   * 240, where the sheet's is three labelled tabs plus the mode toggle and the fold chevron and
   * needs 304. Measure it rather than guessing, and leave slack — a minimum sitting on the exact
   * fit clips the moment anything is added to the row.
   */
  min?: number
}): SidePanelResize {
  const [storedWidth, setStoredWidth] = usePersistentState<number>(storageKey, fallback)
  /** The width under the pointer while a drag runs; null when one is not. */
  const [dragWidth, setDragWidth] = useState<number | null>(null)
  const drag = useRef<{ startX: number; startWidth: number; width: number } | null>(null)
  const resizing = dragWidth != null
  const width = dragWidth ?? clampPanelWidth(storedWidth, fallback, min)

  const onResizeStart = useCallback(
    (e: ReactPointerEvent) => {
      if (e.button !== 0) return
      e.preventDefault()
      const startWidth = clampPanelWidth(storedWidth, fallback, min)
      drag.current = { startX: e.clientX, startWidth, width: startWidth }
      setDragWidth(startWidth)
    },
    [storedWidth, fallback, min],
  )

  useEffect(() => {
    if (!resizing) return
    const onMove = (e: PointerEvent) => {
      const current = drag.current
      if (!current) return
      // The panel is on the right, so dragging its left edge leftwards grows it.
      const next = clampPanelWidth(current.startWidth + (current.startX - e.clientX), fallback, min)
      if (next === current.width) return
      current.width = next
      setDragWidth(next)
    }
    const onUp = () => {
      const final = drag.current?.width
      drag.current = null
      setDragWidth(null)
      if (final != null) setStoredWidth(final)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
    }
  }, [resizing, setStoredWidth, fallback, min])

  return { width, resizing, onResizeStart }
}
