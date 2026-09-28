import {
  useEffect,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type ReactNode,
  type RefObject,
} from 'react'
import { Slot } from '@radix-ui/react-slot'
import { Popover, PopoverAnchor, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'

/**
 * Below this **width** a cell editor is a bottom sheet instead of a floating popover.
 *
 * Deliberately the `sm` breakpoint the sheet primitive itself uses for `sm:max-w-sm`: below the
 * width at which a sheet stops filling the screen is exactly the width at which a popover stops
 * having room. A portrait phone is ~390px, so a 256px popover anchored at a cell sits over most of
 * the grid, lands wherever floating-ui can fit it rather than where the thumb is, and has to be
 * dismissed by tapping a strip of screen the operator cannot see.
 */
export const CELL_EDITOR_SHEET_QUERY = '(max-width: 639px)'

/**
 * The space plan's short-viewport fold, spelled for `matchMedia` — a **short** viewport gets a
 * right-hand sheet instead.
 *
 * Duplicated rather than imported, which is the convention `shortViewport.test.ts` enforces across
 * every site that folds at this height; that file pins the five spellings against each other. The
 * number is the same one because the fact is the same one — there is not enough vertical space —
 * even though what each site does about it differs.
 *
 * A landscape phone is the case this exists for: 852×393 before Safari's chrome, perhaps 330px of
 * viewport after it. That is wider than [CELL_EDITOR_SHEET_QUERY], so the editor was a popover
 * there — and the colour editor is ~440px tall, so floating-ui had nowhere to put it and flipped
 * and clipped it by turns. A bottom sheet would be no better: it needs the height it has not got.
 * A full-height panel down the right edge has the one axis a short viewport still has to spend,
 * and scrolls in the axis it has not.
 */
const SHORT_VIEWPORT = '(max-height: 500px)'

/**
 * Below this viewport **height**, a tall editor draws its compact layout — in whichever of the
 * three forms it is in.
 *
 * Only the colour editor is tall enough to care; the other three are a slider and a label. Its
 * full layout is a 357px popover, and a popover has to fit *beside* the cell it belongs to, which
 * can be anywhere in the grid — so the room it actually gets is roughly half the viewport. Below
 * ~725px there is a band of the grid where neither side has 357px and Radix will not rescue it:
 * it flips above the cell and then `limitShift` refuses to slide it back down, because that would
 * cover the very cell it is anchored to. The editor renders at a negative `top` and is clipped by
 * the window, silently. 750 is that figure with a little margin.
 *
 * A **height** rule and not a form rule, which is why it is not folded into `useEditorForm`:
 * the bottom sheet on an upright phone has all the height in the world and should keep the full
 * layout, while a short desktop window has none and is still a popover.
 *
 * This is a second `max-height` in this file, and the reason `shortViewport.test.ts` keeps it out
 * of `SITES` matters more for it: that test asserts *every* `max-height` in a listed file is 500.
 * The spelling check it is in still pins `SHORT_VIEWPORT` above.
 */
const CRAMPED_VIEWPORT = '(max-height: 750px)'

/** Which of the three shapes a cell editor is drawing itself as. */
export type EditorForm = 'popover' | 'bottom-sheet' | 'side-sheet'

/**
 * Stamped on whichever of the three shapes is mounted, and the DOM contract [editorIsOpen]
 * reads. Spread rather than written out at the two sites, so the attribute and its reader cannot
 * drift apart.
 */
const SURFACE_ATTR = 'data-cell-editor-surface'

/**
 * Is a cell editor — or `SpreadPanel`, which is one in every way that matters — on screen?
 *
 * The grid's window-level **Escape** asks this. Radix closes an open editor from a listener on the
 * document, so it closes wherever focus happens to be; the selection clear beside it was guarded
 * only by *where the key was pressed* (`isEditableTarget`, `closest('[role=dialog]')`), which is a
 * different question and answers wrongly the moment focus is anywhere else — on the Set button
 * that opened the editor, or on the body after a close and re-open. Escape then closed the panel
 * **and** took the selection the panel was opened for. Asking whether the editor is open is the
 * question that was meant, and it does not depend on focus at all.
 *
 * The DOM rather than React state because the state is per cell, hundreds of instances down, and
 * the asker is the container above the table; lifting a boolean through five layers to answer
 * "is anything open" would be a subscription per cell for one bit.
 */
export function editorIsOpen(): boolean {
  return typeof document !== 'undefined' && document.querySelector(`[${SURFACE_ATTR}]`) != null
}

/**
 * What a **click** on a cell does, and where the editor then appears.
 *
 * One type rather than four copies of two props, because the four cell editors have to answer this
 * identically: a grid where the dimmer selects and the colour opens would be two gestures wearing
 * one shape. `PropertyCell` spreads it into whichever of them a cell's value kind picks.
 *
 * Absent means the old behaviour — a click opens the editor, anchored at the cell — which is what
 * `CueValueGrid` wants and gets by passing nothing. It was the fixtures and groups lists' answer
 * too, until they gained a cell selection to put a click in.
 */
export interface CellClickBehaviour {
  /**
   * A single click on this cell **selects** it rather than opening its editor; a **double** click
   * opens it, beside the cell. See [triggerOpens], which is the half of it the surface implements;
   * the cell's own trigger button supplies the `onClick`.
   */
  clickSelects?: boolean
  /** Where the editor opens when it does open. See [anchorRef]. */
  editorAnchorRef?: RefObject<HTMLElement | null>
}

/**
 * One `matchMedia` and one listener **per query**, however many cells are mounted.
 *
 * A `useMediaQuery` per cell would be a `matchMedia` call and a `change` listener per *cell* — the
 * grid mounts one editor per visible cell, so a rig of any size means hundreds of both, all
 * answering the same two questions. Each query's answer is cached in this module and pushed to its
 * subscribers, so `getSnapshot` is a variable read (`useSyncExternalStore` calls it on every render
 * of every subscriber, so it must not build anything — including the subscribe/snapshot closures,
 * which have to be stable per query or the store resubscribes on every render).
 */
interface MediaEntry {
  matches: boolean
  listeners: Set<() => void>
  subscribe: (onStoreChange: () => void) => () => void
  getSnapshot: () => boolean
  /** Undone by [resetEditorSurfaceMedia]; absent where there is no `matchMedia` to listen to. */
  dispose?: () => void
}

const mediaEntries = new Map<string, MediaEntry>()

function mediaEntry(query: string): MediaEntry {
  const existing = mediaEntries.get(query)
  if (existing) return existing

  const entry: MediaEntry = {
    matches: false,
    listeners: new Set(),
    subscribe: (onStoreChange) => {
      entry.listeners.add(onStoreChange)
      return () => {
        entry.listeners.delete(onStoreChange)
      }
    },
    getSnapshot: () => entry.matches,
  }
  mediaEntries.set(query, entry)

  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    const list = window.matchMedia(query)
    entry.matches = list.matches
    const onChange = (event: MediaQueryListEvent) => {
      entry.matches = event.matches
      for (const listener of entry.listeners) listener()
    }
    list.addEventListener('change', onChange)
    entry.dispose = () => list.removeEventListener('change', onChange)
  }
  return entry
}

/**
 * Drop the cached `matchMedia` answers, so a test that stubs `window.matchMedia` is not answered by
 * a stub from an earlier test. The cache outlives a `cleanup()`, being module state.
 *
 * The native listener goes too. Clearing the map alone left it registered on the old
 * `MediaQueryList`, still writing into an entry nothing can read any more and notifying subscribers
 * that had moved on to a fresh entry for the same query — so a suite that reset between tests
 * accumulated one live listener per reset, and the count a test made of them (the "one listener per
 * query" assertion) would have been measuring the wrong thing.
 */
export function resetEditorSurfaceMedia(): void {
  for (const entry of mediaEntries.values()) entry.dispose?.()
  mediaEntries.clear()
}

function useSharedMedia(query: string): boolean {
  const entry = mediaEntry(query)
  // Server snapshot is `false`: a popover is the shape every existing test and the SSR-less build
  // already expect, and a wrong guess here would swap the whole editor on hydration.
  return useSyncExternalStore(entry.subscribe, entry.getSnapshot, () => false)
}

/**
 * Whether a tall cell editor should draw its compact layout. See [CRAMPED_VIEWPORT].
 *
 * Separate from the form on purpose — a popover, a bottom sheet and a side sheet can each be short
 * of height, and only the editor knows whether it is tall enough for the question to arise.
 */
export function useEditorCramped(): boolean {
  return useSharedMedia(CRAMPED_VIEWPORT)
}

/**
 * Which shape a cell editor should take here.
 *
 * **Short beats narrow.** A viewport that is both — a small phone in landscape, a short browser
 * window — has no vertical room to give a bottom sheet, which is the one thing a bottom sheet
 * needs; the side sheet is the arm that works with what is left.
 *
 * It is also the honest answer to "is this a touch surface", which is why `SettingCell` reads it
 * for its row height: both sheets are reached by a finger and the popover is not. A `sm:` variant
 * cannot say that — see the note there.
 */
export function useEditorForm(): EditorForm {
  const narrow = useSharedMedia(CELL_EDITOR_SHEET_QUERY)
  const short = useSharedMedia(SHORT_VIEWPORT)
  if (short) return 'side-sheet'
  return narrow ? 'bottom-sheet' : 'popover'
}

/**
 * How much of the layout viewport the on-screen keyboard is covering, in CSS pixels.
 *
 * A sheet is `position: fixed`, which on iOS is laid out against the **layout** viewport — and
 * that does not shrink when the keyboard opens. So a sheet holding a number field (the dimmer
 * editor, the colour editor's R/G/B boxes, the marquee's typed value) would slide itself neatly
 * behind the keyboard the instant it was used. `visualViewport` is the only thing that reports the
 * covered strip; both sheet forms give the strip back — the bottom one by rising, the side one by
 * shortening.
 *
 * Only measured while the sheet is [open], so a closed editor costs no listeners.
 */
function useKeyboardInset(open: boolean | undefined): number {
  const [inset, setInset] = useState(0)

  useEffect(() => {
    const viewport = typeof window === 'undefined' ? undefined : window.visualViewport
    if (!open || !viewport) {
      setInset(0)
      return
    }
    const update = () => {
      // `offsetTop` is how far the visual viewport has been scrolled *down* inside the layout
      // viewport; without it, a page pinch-scrolled to the bottom reads as a keyboard.
      const hidden = window.innerHeight - viewport.height - viewport.offsetTop
      setInset(Math.max(0, Math.round(hidden)))
    }
    update()
    viewport.addEventListener('resize', update)
    viewport.addEventListener('scroll', update)
    return () => {
      viewport.removeEventListener('resize', update)
      viewport.removeEventListener('scroll', update)
    }
  }, [open])

  return inset
}

interface EditorSurfaceProps {
  /** Undefined leaves the popover to hold its own state — the two property visualizers do. */
  open?: boolean
  onOpenChange: (open: boolean) => void
  /**
   * Names the property in a **sheet**'s header. The popover has no header, so this is unused
   * there — but it is required rather than optional, because a sheet is a Radix dialog and a
   * dialog with no title is unreadable to a screen reader.
   */
  title: string
  /**
   * The cell's own button.
   *
   * Required in practice, though the type still allows its absence: this surface also took an
   * `anchor` rect for a trigger-less popover, which existed for `CellEntryPopover` alone — an
   * editor opened by Enter over a marquee, pointed at a cell it knew only by `(rowId, col)`. The
   * keyboard opens the cell's own editor now, so every caller has a trigger and that branch went
   * with it.
   */
  trigger?: ReactNode
  /**
   * Popover only: its **width**. A sheet sizes itself against the screen.
   *
   * Not its rhythm any more. Each editor wraps its own controls in the element that carries
   * `useEditorKeyboard`'s ref and key handler, and the spacing rides on that — so one number
   * applies in all three forms, rather than the popover's coming from here and both sheets'
   * from `SheetBody`'s own `space-y-4` between what used to be its direct children.
   */
  contentClassName?: string
  /** Popover only. */
  align?: 'start' | 'center' | 'end'
  /**
   * Both primitives spell this the same way. Every cell editor passes `useEditorKeyboard`'s,
   * which is how the first text field takes focus on open — in that callback rather than in an
   * effect, because Radix's own auto-focus is a parent effect and would take it straight back.
   */
  onOpenAutoFocus?: (event: Event) => void
  /**
   * This editor's content needs the **wide** side sheet.
   *
   * Only the colour editor does: on a short viewport it lays its emitter rows beside the picker
   * rather than under them, which is what stops it scrolling, and that wants ~528px of content.
   * The other three are a slider and a label, and looked absurd in that much room — so the width
   * is the content's to ask for rather than one number for the surface. It says nothing about the
   * bottom sheet (as wide as the screen) or the popover (sized by `contentClassName`).
   */
  wide?: boolean
  /**
   * Whether a **single** click on [trigger] opens this editor.
   *
   * **False in the programmer's grid, and that is a decision rather than a detail.** There a click
   * on a cell *selects* it; the editor is opened by the selection bar's Set, by Enter, by typing —
   * or by a **double** click on the cell, which is the pointer's own way of saying the second
   * gesture. One gesture for "say what to edit" and another for "edit it", rather than a click
   * doing both. So the trigger stops being a `PopoverTrigger` and becomes a `PopoverAnchor`: it
   * still positions the popover (which is what [anchorRef] then overrides), it still takes focus
   * and Tab, and its own `onClick` is free to mean "select this cell".
   *
   * The double click is the surface's rather than the four cells', for the reason [CellClickBehaviour]
   * itself exists: one rule, or a grid where the dimmer opens on two clicks and the colour does not.
   * It opens through [onOpenChange] like any other opener, so it lands in `useEditorOpen`'s
   * click path — beside the cell, with no typed seed, and with whatever a click's open resets reset.
   * It is withheld while this editor is already open, which is not tidiness — see the guard at
   * `onTriggerDoubleClick` for what a second open does to a panel the operator is typing into.
   *
   * True everywhere else — today `CueValueGrid`, which passes no `clickSelects`: it has no
   * selection to put a click in and would otherwise lose every way into an editor. There a single
   * click already opens, so no double click is wired: two clicks would toggle the editor shut and
   * back open again, which is a worse answer than the one a click already gives. The two plain
   * fixture/group list routes were in this list until they gained a cell selection of their own.
   *
   * `ColourPickerPopover`'s two visualizer callers are **not** in that list, though its own doc
   * comment says they are: they pass no `sheetWhenNarrow`, so they take its plain-`Popover` branch
   * and never mount this component at all. Nothing here decides anything for them.
   */
  triggerOpens?: boolean
  /**
   * Position the popover at **this** element rather than at the cell it belongs to.
   *
   * The programmer passes its Set button, and only for an open that button made: anchoring at the
   * cell put the panel wherever in the grid the first selected cell happened to be — often nowhere
   * near the hand that pressed Set, and for a marquee drawn near the bottom of a tall list, off the
   * top of the editor's reach entirely. Enter and a typed character are gestures made at the
   * selection and keep the cell anchor; the cells decide, by withholding this.
   *
   * Spread lands beside its own button *by default*, that button being its own `PopoverTrigger`.
   * **This is new machinery rather than that idea reused** — the only `virtualRef` in the repo — so
   * don't read `SpreadPanel` as a precedent for pointing a panel at an element elsewhere in the
   * tree. It has never needed to.
   *
   * Read at render time and only while [open]: a `virtualRef` whose `current` is null would set
   * Radix's anchor to null and leave the content unpositioned, so a missing button falls back to
   * the trigger anchor instead. Popover form only — a sheet is anchored to the screen edge.
   */
  anchorRef?: RefObject<HTMLElement | null>
  children: ReactNode
}

/**
 * The one surface every cell editor opens in: a floating popover on a desk, a bottom sheet on a
 * phone held upright, a right-hand sheet where the viewport is short.
 *
 * Written once rather than four times, for the four cell editors — `SliderCell`, `PositionCell`,
 * `SettingCell`, and `ColourCell` through `ColourPickerPopover`. There was a fifth, the marquee's
 * own typed-value field (`CellEntryPopover`), drawn through this surface precisely so that Enter
 * and a click would produce the same picture. It is deleted: the keyboard now opens the cell's
 * *own* editor, which is the stronger form of that argument — one editor per column rather than
 * two that have to be kept looking alike. See §The programmer's keyboard in CLAUDE.md.
 *
 * The two sheet forms share a component so a rotation between them cannot be the moment one of
 * them grows a rule the other has not got; the popover is its own branch so that a desk pays for
 * no viewport listener at all.
 */
export function EditorSurface({
  open,
  onOpenChange,
  title,
  trigger,
  contentClassName,
  align = 'start',
  onOpenAutoFocus,
  wide,
  triggerOpens = true,
  anchorRef,
  children,
}: EditorSurfaceProps) {
  const form = useEditorForm()

  // One handler, written once and handed to whichever element is the trigger in each of the three
  // branches, so all three answer a double click identically. **Not a wrapper around the trigger**,
  // which is what this was first: every branch already clones the trigger through a `Slot`, so a
  // wrapper meant a second clone per cell per render — and the grid mounts one of these per visible
  // cell, in a viewport that re-renders on every frame of a marquee drag.
  //
  // **Undefined while this editor is already open.** A double click opens; there is nothing for it
  // to do to an editor that is open, and `setOpen(true)` is not idempotent — it re-runs the cell's
  // own open reset (`SliderCell`'s `draft.reset`, `SettingCell`'s filter) over what the operator
  // had half-typed, and re-latches `atButton` to false, which swings a Set-anchored panel to the
  // cell and swaps the branch below from `TriggerState` to `PopoverAnchor`. Those are two component
  // types at one slot, so React remounts the operator's own button under their pointer.
  //
  // **Whether the gesture ever gets that far depends on the environment, which is why the guard is
  // here rather than left to Radix.** At the desk the first `pointerdown` of the double click is an
  // outside press on the open content, and `DismissableLayer` — which listens for `pointerdown` on
  // the document — closes the editor, so the `dblclick` lands on one that has already shut and
  // simply opens it again (checked in the browser, popover form, on this change and on the commit
  // before it). A test never gets that far: `fireEvent` dispatches exactly the one event it names,
  // so no `pointerdown` is ever seen, that listener does not run, and the second open goes straight
  // through — which is what the two tests in `EditorSurface.test.tsx` catch, and both fail if
  // this guard is dropped. **Don't read that as Radix protecting the desk case**: `PopoverContent`
  // suppresses an outside press only where it lands on a real `PopoverTrigger`, and this grid
  // renders none in either environment. See [triggerOpens].
  const onTriggerDoubleClick = triggerOpens || open === true ? undefined : () => onOpenChange(true)

  if (form !== 'popover') {
    return (
      <SheetSurface
        form={form}
        open={open}
        onOpenChange={onOpenChange}
        title={title}
        trigger={trigger}
        onTriggerDoubleClick={onTriggerDoubleClick}
        onOpenAutoFocus={onOpenAutoFocus}
        wide={wide}
        triggerOpens={triggerOpens}
      >
        {children}
      </SheetSurface>
    )
  }

  // Reading a ref during render, deliberately: the question is only asked on the commit that
  // flips [open] to true, and by then the Set button has long been on screen — the selection it
  // acts on is what put it there. A null answer is the honest fallback rather than a bug, because
  // a `virtualRef` pointing at nothing sets Radix's anchor to null and the content is then never
  // positioned at all.
  const atButton = open === true && anchorRef?.current != null

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      {trigger != null &&
        (triggerOpens ? (
          <PopoverTrigger asChild>{trigger}</PopoverTrigger>
        ) : atButton ? (
          // `data-state` is Radix's, and neither of these sets it — so it is restored by hand. It
          // is what says *which cell* the open editor belongs to, which nothing else on screen does
          // once the panel is anchored at the Set button instead of at the cell; the grid's own
          // addressing contract (`data-cell` / `data-row-id`) is read through it, by the tests and
          // by the bar's Set deciding which editor to shut.
          //
          // **Exactly one anchor is mounted.** Rendering both and letting the virtual one win by
          // effect order — a ref callback commits before a passive effect — cost a second
          // `computePosition` per open, and the first of the two could paint: the panel appeared at
          // the cell and jumped to the button. `TriggerState` is how the trigger still takes
          // `data-state` while being no anchor at all.
          <TriggerState open={open} onDoubleClick={onTriggerDoubleClick}>
            {trigger}
          </TriggerState>
        ) : (
          <PopoverAnchor
            asChild
            data-state={open ? 'open' : 'closed'}
            onDoubleClick={onTriggerDoubleClick}
          >
            {trigger}
          </PopoverAnchor>
        ))}
      {atButton && <PopoverAnchor virtualRef={anchorRef} />}
      <PopoverContent
        {...{ [SURFACE_ATTR]: 'popover' }}
        align={align}
        className={contentClassName}
        onOpenAutoFocus={onOpenAutoFocus}
      >
        {children}
      </PopoverContent>
    </Popover>
  )
}

/**
 * The cell's trigger, marked with the editor's open state and nothing else.
 *
 * `data-state` is Radix's word, and it comes free from a `PopoverTrigger`; neither a `PopoverAnchor`
 * carrying a virtual anchor nor a sheet's bare trigger sets one. It is what says *which cell* the
 * open editor belongs to — the grid's addressing contract, read by `openCellEditorTarget` so the
 * bar's Set can close what it opened, and by the tests.
 *
 * `Slot` and not `<Comp asChild>`: `Slot` has no `asChild` of its own, so the prop would be spread
 * straight onto the cloned `<button>` and React would warn about an unknown DOM attribute on every
 * open — silently, since the default test reporter swallows it.
 */
function TriggerState({
  open,
  onDoubleClick,
  children,
}: {
  open?: boolean
  /** The double click that opens this editor, where a single click selects. See `triggerOpens`. */
  onDoubleClick?: () => void
  children: ReactNode
}) {
  return (
    <Slot data-state={open ? 'open' : 'closed'} onDoubleClick={onDoubleClick}>
      {children}
    </Slot>
  )
}


function SheetSurface({
  form,
  open,
  onOpenChange,
  title,
  trigger,
  onTriggerDoubleClick,
  onOpenAutoFocus,
  wide,
  triggerOpens,
  children,
}: Pick<
  EditorSurfaceProps,
  | 'open'
  | 'onOpenChange'
  | 'title'
  | 'trigger'
  | 'onOpenAutoFocus'
  | 'wide'
  | 'triggerOpens'
  | 'children'
> & {
  form: Exclude<EditorForm, 'popover'>
  /**
   * The double click that opens this editor, already decided above — undefined where a single
   * click opens, and while this editor is open. Passed down rather than recomputed here so the
   * gesture cannot come to mean one thing in a popover and another in a sheet.
   */
  onTriggerDoubleClick?: () => void
}) {
  const keyboardInset = useKeyboardInset(open)
  const atBottom = form === 'bottom-sheet'

  // Inline rather than Tailwind because most of these are computed, and because each of them has
  // to beat a class `SheetContent` already carries — `bottom-0`, `w-full`, `h-full` — which only an
  // inline style does. `viewport-fit=cover` in index.html is what makes the `env()` insets non-zero,
  // and the padding here is the device's strip only: the visual rhythm is `SheetBody`'s `pb-4`.
  const style: CSSProperties = {
    paddingBottom: keyboardInset > 0 ? 0 : 'env(safe-area-inset-bottom)',
    ...(atBottom
      ? {
          bottom: keyboardInset,
          // `svh`, not `dvh`: the dynamic unit changes as Safari's bar hides and shows, which
          // would resize the sheet under the operator's thumb mid-drag. The small viewport is the
          // one that is always true.
          maxHeight: `calc(88svh - ${keyboardInset}px)`,
        }
      : {
          // [wide] is the colour editor's, which needs room to sit its emitter rows *beside* the
          // picker — the thing that stops it scrolling in the ~285px a landscape iPhone actually
          // has once Safari has taken its share. The safe-area inset is added to the width rather
          // than merely padded in: as padding alone it came off the content box, and the picker
          // and its field column no longer fitted, so the sheet scrolled sideways on whichever
          // rotation puts the notch on this edge.
          width: `calc(${wide ? 'min(35rem, 70vw)' : 'min(22rem, 85vw)'} + env(safe-area-inset-right))`,
          // `SheetContent`'s own `sm:max-w-sm` is a 384px cap that silently wins over the width
          // above — the sheet simply stayed narrow and the body scrolled, which looks like the
          // width never being applied at all. The `vw` term in the width is the real cap.
          maxWidth: 'none',
          // Replaces `h-full`, which would otherwise over-constrain the box and make the
          // keyboard's bite be ignored outright.
          height: `calc(100% - ${keyboardInset}px)`,
          // Landscape puts the notch on one side or the other, and `env()` is 0 on the side it is
          // not; a sheet flush to the right edge would otherwise put its body under it.
          paddingRight: 'env(safe-area-inset-right)',
        }),
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      {/* No `SheetTrigger` where a click selects instead of opening: a sheet has no anchor to be,
          so the cell's own button is all that is left of the trigger. Its `onClick` is the
          selection, exactly as in the popover form — one gesture, whichever shape the editor is
          about to take.

          It still carries `data-state`, and that is not cosmetic: the selection bar's Set finds the
          editor to shut by looking for an open cell (`openCellEditorTarget`), and a sheet is
          portalled to `body` like a popover — so with the marker left off, nothing inside
          `[data-cell]` said an editor was open and Set's second press could never close a sheet.
          Both sheet forms are first-class (a phone held upright, and any short viewport), so the
          gesture has to work in all three. */}
      {trigger != null &&
        (triggerOpens === false ? (
          <TriggerState open={open} onDoubleClick={onTriggerDoubleClick}>
            {trigger}
          </TriggerState>
        ) : (
          <SheetTrigger asChild>{trigger}</SheetTrigger>
        ))}
      <SheetContent
        {...{ [SURFACE_ATTR]: form }}
        side={atBottom ? 'bottom' : 'right'}
        // A cell editor is its own description: there is nothing to say beyond the title, and
        // Radix warns about a dialog that neither describes itself nor opts out.
        aria-describedby={undefined}
        // The house shape for a sheet that is a panel rather than a form: `p-0 gap-0` with the
        // padding pushed into the header and body, a ruled header, and square corners. Copied from
        // `AiChatPanel` and `MobileCueListSheet` rather than invented, because a cell editor
        // arriving in its own dialect is exactly what makes an app feel assembled from parts.
        className="p-0 gap-0"
        style={style}
        onOpenAutoFocus={onOpenAutoFocus}
      >
        <SheetHeader className="flex-none border-b py-3 pl-4 pr-10">
          <SheetTitle className="text-base">{title}</SheetTitle>
        </SheetHeader>
        {/* `pt-4`: `SheetBody` has no top padding of its own, so the first control sat hard
            against the header's rule. */}
        <SheetBody className="pt-4">{children}</SheetBody>
      </SheetContent>
    </Sheet>
  )
}
