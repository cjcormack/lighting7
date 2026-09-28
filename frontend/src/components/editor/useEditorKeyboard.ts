import { useCallback, useRef } from 'react'
import { useEditorForm } from './EditorSurface'

/**
 * The text fields inside a cell editor, in DOM order.
 *
 * Inputs only, and only the ones a value is *typed* into: a checkbox is a toggle rather than a
 * field, and Radix's `Slider` is a div with a `role`, so neither is somewhere comma should land.
 * `:not([disabled])` keeps the skip honest — a field that cannot be typed into is not the next one.
 */
const FIELD_SELECTOR =
  'input[type="number"]:not([disabled]), input[type="text"]:not([disabled]), input[type="search"]:not([disabled])'

/**
 * The keyboard half of a cell editor — one rule for all four, and the reason the typed-value
 * popover could be deleted.
 *
 * There used to be **two** editors per column: the one a click on a cell opens, and a separate
 * single-line field (`CellEntryPopover`) that Enter opened over a marquee, with a grammar of its
 * own (`parseCellEntry`) for hex, percentages and `pan,tilt`. Two editors for one job drift, and
 * the second one could only ever set what a line of text can say. So the second is gone and the
 * first learned to be typed at:
 *
 *  - **Opening focuses the first field**, and selects it, so the value the operator is replacing
 *    is already highlighted — **however the editor was opened**. That is the whole gesture on a
 *    desk: drag three dimmer cells, press Enter (or the bar's Set), type `128`, press Enter. A
 *    first cut focused the field only when a *keystroke* had opened it, on the reasoning that a
 *    tap must not summon the on-screen keyboard — which quietly broke the bar's Set, which is a
 *    click, and at the time the release-open too (a drag opened its editor behind the release,
 *    `PD-POPUP-AFTER-DRAG`, until the bar gained Set). Done in `onOpenAutoFocus` rather than in
 *    an effect, because Radix's own auto-focus is a *parent* effect and parent effects run after a
 *    child's — a focus set from inside the content would be taken straight back off it.
 *  - **Enter in any field applies and closes.** Every field on this desk writes as it is typed
 *    (`useNumberFieldDraft`), so there is nothing left to flush: Enter is the operator saying
 *    "that's the value", which is the editor's job done.
 *  - **Comma moves to the next field**, wrapping — `pan,tilt` and `r,g,b` are how an operator says
 *    a pair or a triple, and that shape survives as a *gesture* now that it is no longer a
 *    grammar. Where an editor has only one field, comma is left alone to be typed: a one-field
 *    editor has nowhere to go, and a type-ahead filter may well want the character.
 *
 * A field that has already handled the key says so with `preventDefault()`, and this steps aside —
 * which is how `SettingCell`'s filter keeps Enter for "take the highlighted option".
 *
 * **The one thing that is not independent of context is the *form*.** A popover is a desk, and a
 * desk has a keyboard worth pointing at a field; both sheet forms are reached by a finger, where
 * taking focus means the on-screen keyboard rising over the grid for a gesture that gains nothing
 * from it. That is a question about the surface rather than about the gesture, so the two answers
 * do not reintroduce the split this hook exists to remove: on any one surface, every way of
 * opening an editor behaves the same.
 */
export function useEditorKeyboard({
  autoFocus = true,
  onDone,
}: {
  /**
   * Take focus on open at all. Defaults to true, which is every cell editor; `SpreadPanel` opts out
   * in both sheet forms and when its marquee spans several spreadable columns or families, because
   * then its first control is a chooser rather than a value, and `ColourPickerPopover` does for its two non-cell
   * callers, which draw no text fields to focus.
   *
   * It is not a *gesture* switch — see the docblock. Whether this editor was opened by a click, the
   * bar's Set or a keystroke makes no difference to focus, only to [seeding][numericSeed].
   */
  autoFocus?: boolean
  /** Enter: the operator is finished. Every caller closes; nothing else has to happen. */
  onDone: () => void
}): {
  /** Put on the element wrapping the editor's controls. */
  contentRef: React.RefObject<HTMLDivElement | null>
  /** Put on the same element. */
  onKeyDown: (event: React.KeyboardEvent<HTMLElement>) => void
  /** Hand to `EditorSurface` (or to the popover the editor draws itself in). */
  onOpenAutoFocus: (event: Event) => void
} {
  const contentRef = useRef<HTMLDivElement>(null)

  const fields = useCallback(
    () => [...(contentRef.current?.querySelectorAll<HTMLInputElement>(FIELD_SELECTOR) ?? [])],
    [],
  )

  // A sheet is a finger's surface; see the docblock. Read here rather than by each caller so the
  // four cell editors cannot answer it differently, and it is the same shared media store
  // `EditorSurface` reads — a subscription per query, not per cell.
  // Called unconditionally: `autoFocus && useEditorForm()` would short-circuit the hook away.
  const form = useEditorForm()
  const focusable = autoFocus && form === 'popover'
  const focusableRef = useRef(focusable)
  focusableRef.current = focusable

  const onOpenAutoFocus = useCallback(
    (event: Event) => {
      if (!focusableRef.current) return
      const first = fields()[0]
      if (!first) return
      event.preventDefault()
      first.focus()
      // `select()` is a no-op on a number input in some engines and throws in none of them —
      // `setSelectionRange` is the one that throws there, which is why it is not used.
      first.select()
    },
    [fields],
  )

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLElement>) => {
      if (event.defaultPrevented) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      const target = event.target
      if (!(target instanceof HTMLElement) || !target.matches(FIELD_SELECTOR)) return

      if (event.key === 'Enter') {
        event.preventDefault()
        onDone()
        return
      }
      if (event.key === ',') {
        const all = fields()
        // One field has nowhere to go, so the character is left to be typed — see the docblock.
        if (all.length < 2) return
        const index = all.indexOf(target as HTMLInputElement)
        if (index === -1) return
        event.preventDefault()
        const next = all[(index + 1) % all.length]
        next.focus()
        next.select()
      }
    },
    [fields, onDone],
  )

  return { contentRef, onKeyDown, onOpenAutoFocus }
}

/**
 * The character that opened an editor, if a **number** field can take it.
 *
 * Typing at the grid opens the editor for the first selected cell, and that cell may be of any
 * kind: a letter typed over a gobo column is the start of a type-ahead, and the very same letter
 * typed over a dimmer column is nothing a byte field can hold. Rather than restrict what opens an
 * editor — which would leave the type-ahead unreachable from the grid — each numeric editor asks
 * this, and simply opens unseeded where the answer is null.
 *
 * `''` (a bare Enter) is null too: there is nothing to seed, and the field's own value stands.
 */
export function numericSeed(seed: string | null): string | null {
  return seed != null && /^[0-9.]$/.test(seed) ? seed : null
}
