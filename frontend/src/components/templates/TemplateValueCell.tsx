import { memo, useCallback, useRef, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { EditorSurface, useEditorForm } from '@/components/editor/EditorSurface'
import { EditorFooter } from '@/components/editor/EditorFooter'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { useEditorKeyboard } from '@/components/editor/useEditorKeyboard'
import { useEditorOpen } from '@/components/editor/useEditorOpen'
import { batchLabelOf, type SheetCellProps } from '@/components/sheet/sheetModel'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import type { TemplateIntent } from '@/lib/templateIntent'
import { FamilyControls } from './familyControls/FamilyControls'
import { templateValuesEmpty, valueChanges, withIntent, type TemplateValues } from './familyControls/templateRows'

/**
 * What the Value column reads and commits: a generic value template's family and its draft. The
 * family travels with the commit because it is the one fact the column's `write` needs from the
 * origin — a commit lands only on templates of the origin's family (library-sheets plan §5).
 */
export interface TemplateValueDraft {
  family: AttributeFamily
  values: TemplateValues
  /**
   * On a commit only: what the operator changed against the origin's seed (`valueChanges`) — the
   * one thing `write` lands on each template of the batch, over that template's **own** values. A
   * template's rows are replaced whole on the wire, so writing the origin's draft to a sibling would
   * delete whatever the sibling holds that the origin does not.
   */
  changes?: Record<string, TemplateIntent | null>
}

/** The batch a commit from this cell would land on, in the origin's family. */
export interface TemplateValueLanding {
  /** Templates of the origin's family — the ones `write` writes. */
  count: number
  /** The rest of the batch, named — *Amber and Deep Blue are Colour · skipped*; null when none. */
  skipped: string | null
}

export interface TemplateValueCellProps extends SheetCellProps<TemplateValueDraft> {
  /** What the cell shows — the Value read-out, in the template's own grammar. */
  face: ReactNode
  /**
   * The batch in the origin's family, computed by the column, which knows each row's template.
   * Called only while the editor is open (the closure is fresh per render of the row).
   */
  landing: () => TemplateValueLanding
}

/**
 * **A generic value template's Value, edited in the cell** (library-sheets plan D6, session 3).
 *
 * It mounts `TemplateEditor`'s own family controls — `FamilyControls` and the four behind it,
 * lifted into `familyControls/` with the rules that turn their values into rows — through the kit's
 * `EditorSurface`, so the cell has the three forms and the double click, and a template's value has
 * one control and one grammar in two hosts. Seeded by `seedValues` (the column's `value`), and a
 * commit carries **what changed** against that seed, which the column's `write` applies over each
 * template's own values and turns into `PUT {rows}` through the one rows builder,
 * `templateRowsFromValues` — which is where the **rgbonly rule** is applied: a white or
 * amber row forces the colour row's policy, so the cell cannot send the pair the write boundary
 * refuses by name. The editor kit's own editors were drawn on the boards and are deliberately not
 * what mounts here (INDEX.md's *Superseded by the plan*).
 *
 * **It commits on Enter and Apply, not as the controls move**, like `NumberCell` and `TextCell`:
 * a rows PUT republishes every cue layering the template, so a colour drag written per frame would
 * be a republish per frame. Enter applies from the hex field (the kit's `useEditorKeyboard`) and
 * from a focused slider (below) — every family but Colour has no text field, and a slider is where
 * the popover puts focus for those.
 *
 * **Clear is refused and Spread is not offered** — a template holds a value, and a spread over
 * colour templates would need the desk to resolve intents without heads (plan §7). Per-fixture and
 * effect templates never mount this: their Value is `undefined`, a read-out, skipped by name.
 */
export const TemplateValueCell = memo(function TemplateValueCell({
  value,
  label,
  batchLabel,
  skipped,
  disabled,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  editorAnchorRef,
  onBeginEdit,
  onCommit,
  face,
  landing,
}: TemplateValueCellProps) {
  const { family } = value
  const [draft, setDraft] = useState<TemplateValues>(value.values)
  const valueRef = useRef(value)
  valueRef.current = value
  // The batch is fixed for one open — a marquee cannot move under an open popover — so the landing
  // is read once, on the open, rather than on every render of a drag. Through a ref, because the
  // column hands a fresh closure on every render of the row.
  const landingRef = useRef(landing)
  landingRef.current = landing
  const [land, setLand] = useState<TemplateValueLanding | null>(null)
  const reset = useCallback(() => {
    setDraft(valueRef.current.values)
    setLand(landingRef.current())
  }, [])

  const { isOpen, setOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
    onOpen: reset,
  })

  const onChange = useCallback((propertyName: string, intent: TemplateIntent | null) => {
    setDraft((prev) => withIntent(prev, propertyName, intent))
  }, [])

  const empty = templateValuesEmpty(family, draft)
  const error = empty ? 'A template holds a value — set one, or open it to delete the template' : null

  const commit = useCallback(() => {
    if (empty) return false
    // The changes, not the draft: a sibling keeps every property the operator did not touch. An
    // untouched Enter changes nothing, so it commits nothing — it only closes.
    const changes = valueChanges(value.values, draft)
    if (Object.keys(changes).length > 0) onCommit({ family, values: draft, changes })
    return true
  }, [draft, empty, family, onCommit, value.values])
  const apply = useCallback(() => {
    if (commit()) setOpen(false)
  }, [commit, setOpen])

  const form = useEditorForm()
  const { contentRef, onKeyDown, onOpenAutoFocus } = useEditorKeyboard({ onDone: apply })

  // Colour has a hex field, which the kit's keyboard focuses and answers Enter in. The other three
  // families are sliders only, so on a desk the first slider takes focus instead — never the first
  // *button*, which is Radix's default and here would be a Clear, turning Enter into a deletion.
  const handleOpenAutoFocus = useCallback(
    (event: Event) => {
      onOpenAutoFocus(event)
      if (event.defaultPrevented || form !== 'popover') return
      const thumb = contentRef.current?.querySelector<HTMLElement>('[role="slider"]')
      if (thumb == null) return
      event.preventDefault()
      thumb.focus()
    },
    [contentRef, form, onOpenAutoFocus],
  )
  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLElement>) => {
      onKeyDown(event)
      if (event.defaultPrevented || event.key !== 'Enter') return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      // A slider has no Enter of its own; a button's Enter is its own press, and stays that.
      if (!(event.target instanceof HTMLElement) || event.target.closest('[role="slider"]') == null) return
      event.preventDefault()
      apply()
    },
    [apply, onKeyDown],
  )

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={`${label} · ${FAMILY_LABELS[family].singular}`}
      contentClassName={family === 'COLOUR' ? COLOUR_WIDTH_CLASS : WIDTH_CLASS}
      onOpenAutoFocus={handleOpenAutoFocus}
      triggerOpens={false}
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button
          type="button"
          disabled={disabled}
          onClick={onBeginEdit}
          className="flex h-full w-full min-w-0 items-center rounded text-left hover:bg-accent/50"
        >
          {face}
        </button>
      }
    >
      {/* The body scrolls under the viewport's height with the footer outside the scroller, as the
          Spread panel keeps it: the colour controls are tall, and a desk window short of the
          editor's height would otherwise put Apply below the fold. The sum takes off the surface's
          12px padding and its 1px border at each end; in a sheet form the variable is unset, the
          calc is invalid, and the sheet scrolls itself. */}
      <div
        ref={contentRef}
        onKeyDown={handleKeyDown}
        data-template-value-editor
        className="flex max-h-[calc(var(--radix-popover-content-available-height)-1.5rem-2px)] flex-col gap-2"
      >
        <EditorLabelLine
          subject={isOpen && land != null ? batchLabelOf(land.count, 'template') : batchLabel}
          column={`${label} · ${FAMILY_LABELS[family].singular}`}
        />
        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto">
          <FamilyControls family={family} values={draft} onChange={onChange} />
          <EditorReadout error={error}>
            <span>The intent — the desk resolves it per head</span>
            {skipped ? <span data-editor-skipped>{skipped}</span> : null}
            {isOpen && land?.skipped ? <span data-editor-skipped>{land.skipped}</span> : null}
          </EditorReadout>
        </div>
        <EditorFooter>
          <Button size="sm" className="h-7 text-xs" disabled={error != null} onClick={apply}>
            Apply
          </Button>
        </EditorFooter>
      </div>
    </EditorSurface>
  )
})

/** The popover's width for the three slider families — the kit's level editor's 288. */
const WIDTH_CLASS = 'w-72'
/** Colour's: the 160px picker beside its hex field, and the emitter rows under it. */
const COLOUR_WIDTH_CLASS = 'w-80'
