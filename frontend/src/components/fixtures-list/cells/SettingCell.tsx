import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Check } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import type { CellResolution } from '../columns'
import type { CellBatch, CellCommit } from '../rowModel'
import type { CellValue } from '../useRowValues'
import { EditorSurface, useEditorForm, type CellClickBehaviour } from '../../editor/EditorSurface'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { headsLine, skippedLine } from '../../editor/editorCopy'
import { UNSET_CELL_TITLE, UnsetCellMark } from '../../editor/UnsetCellMark'
import { useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'

interface SettingCellOwnProps {
  value: Extract<CellValue, { kind: 'setting' }>
  resolutions: NonNullable<CellResolution>[]
  /** The column's name, titling the editor where it is a bottom sheet. See `SliderCell`. */
  label?: string
  /** What a commit lands on — see `SliderCell` and `CellBatch`. */
  batch?: CellBatch
  /** *Local*, or the focused Look's name — the label line's scope. */
  scopeLabel?: string
  /** No value in the current scope — see `UnsetCellMark`. */
  placeholder?: boolean
  /**
   * The cell cannot take an edit — the desk is unreachable, so the write would go nowhere.
   * A real `disabled` rather than the wrapper's `pointer-events-none` alone: that stops the
   * mouse and not the keyboard, and this trigger is tabbable.
   */
  disabled?: boolean
  /**
   * A released single-column marquee named this cell: open the editor without a click.
   * See `useEditorOpen`.
   */
  autoOpen?: boolean
  /** The container asked this editor to close — Set pressed again. See `useEditorOpen`. */
  autoClose?: boolean
  /** That open came from the bar's Set, so the editor is anchored there. See `useEditorOpen`. */
  anchorAtButton?: boolean
  /**
   * The auto-open came from a character typed at the grid, which here is the first character of
   * the type-ahead. Focus is not its business — the filter is focused however the editor was
   * opened. See `useEditorKeyboard`.
   */
  keyboardSeed?: string | null
  /**
   * Nothing is selected any more, so this editor's targets are gone with it — close.
   * See `useEditorOpen`.
   */
  selectionEmpty?: boolean
  onCommit: (commit: CellCommit) => void
  onBeginEdit: () => void
}

type SettingCellProps = SettingCellOwnProps & CellClickBehaviour

/**
 * Below this many options the filter is not drawn: there is nothing to narrow, and a search box
 * over a two-row list is a control asking to be read for no gain. Arrow keys and Enter go with it,
 * which is no loss — a list this short is one tap or one Tab away.
 */
const FILTER_FROM_OPTIONS = 3

/**
 * Current option name (with colour chip when the option carries a preview —
 * colour wheels, some gobo wheels); edit via an option list in the shared
 * editor surface. Option
 * levels come from the *first* backing property — heterogeneous groups whose
 * members map options to different levels get the first member's mapping,
 * which is the same compromise the group setting hook makes.
 *
 * Unchanged by the editor kit but for the label line above the list and the skip read-out under
 * it (editor-kit plan D8): a marquee over a par and a spot sweeps the par's empty Gobo cell, and
 * *2 heads have no gobo · skipped* is where that is said. The popover is 256px (D17): `w-64`,
 * measured in the app at 256px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const SettingCell = memo(function SettingCell({
  value,
  resolutions,
  label = 'Setting',
  batch,
  scopeLabel = 'Local',
  placeholder,
  disabled = false,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  clickSelects,
  editorAnchorRef,
  onCommit,
  onBeginEdit,
}: SettingCellProps) {
  // The type-ahead's text, and which of the matches Enter would take. Reset on every open — by a
  // click, a marquee or the keyboard alike — so an editor never reopens holding the last search.
  const [query, setQuery] = useState('')
  const [highlight, setHighlight] = useState(0)
  // A rig fills this grid, so every id here has to be per instance: `useId` rather than a constant,
  // or `aria-controls` would name the first mounted cell's list from every cell in the column.
  const listId = useId()
  const resetFilter = useCallback(() => {
    setQuery('')
    setHighlight(0)
  }, [])
  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
    onOpen: resetFilter,
  })
  const { contentRef, onKeyDown, onOpenAutoFocus } = useEditorKeyboard({
    onDone: () => setOpen(false),
  })
  // The character that opened the editor is the first character of the search — any character,
  // not only a digit, which is the whole reason typing a letter at the grid opens an editor at all.
  useEffect(() => {
    if (keyboardOpen) setQuery(keyboardOpen)
  }, [keyboardOpen])
  // This is the one cell editor whose control *is* the list, so each option has to be a touch
  // target wherever a finger can reach it — which is both sheets, and not the popover.
  //
  // **Not a `sm:` variant**, which was the first attempt and was wrong in exactly the case it was
  // written for: `sm:` is `min-width: 640px`, and the side sheet is chosen by *height*. A landscape
  // phone — 852x393, the reference case in `EditorSurface`'s own doc — is over 640px wide, so
  // the width variant fired and shrank the rows back on the touch surface that most needed them.
  // Width cannot answer "is this a finger" on this desk; the form can.
  const touchTarget = useEditorForm() !== 'popover'
  const first = resolutions[0]
  // Memoised for its `[]` arm alone, which would otherwise hand `matches` below a fresh identity
  // every render — the `?? []` trap CLAUDE.md names. `first.property.options` is already stable.
  const options = useMemo(
    () => (first.kind === 'setting' || first.kind === 'colour-setting' ? first.property.options : []),
    [first],
  )

  const showFilter = options.length >= FILTER_FROM_OPTIONS
  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!showFilter || needle === '') return options
    return options.filter((option) => option.displayName.toLowerCase().includes(needle))
  }, [options, query, showFilter])
  // Clamped rather than reset: a match list that shrinks under the cursor (one more character
  // typed) must still have Enter pointing at something that is on screen.
  const active = matches.length === 0 ? -1 : Math.min(highlight, matches.length - 1)

  const choose = useCallback(
    (level: number) => {
      onCommit({ kind: 'setting', level })
      setOpen(false)
    },
    [onCommit, setOpen],
  )

  /**
   * The type-ahead's own keys, which is why they are not `useEditorKeyboard`'s: Enter here
   * means "take the highlighted option" rather than "that's the value", and the arrows move the
   * highlight rather than the caret. `preventDefault` is how the wrapper below is told to stand
   * aside — it steps back from any key a field has already answered.
   */
  const onFilterKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLInputElement>) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        if (matches.length === 0) return
        const delta = event.key === 'ArrowDown' ? 1 : -1
        setHighlight((prev) => {
          const from = Math.min(prev, matches.length - 1)
          return Math.max(0, Math.min(matches.length - 1, from + delta))
        })
        return
      }
      if (event.key === 'Enter') {
        // Swallowed even with nothing matching: closing on an Enter that set nothing would throw
        // away a search the operator is halfway through fixing.
        event.preventDefault()
        const option = matches[active]
        if (option) choose(option.level)
      }
    },
    [active, choose, matches],
  )

  // Keep the highlighted row on screen — the arrow keys are the only way to reach an option below
  // the fold, and a highlight nobody can see is not a selection.
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (active < 0) return
    listRef.current?.querySelector('[data-highlighted="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const heads = batch ?? { count: 1, skipped: 0, resolutions }
  const skipped = skippedLine(heads.skipped, label.toLowerCase())

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={label}
      contentClassName="w-64 p-1"
      onOpenAutoFocus={onOpenAutoFocus}
      triggerOpens={!clickSelects}
      // Only where the press was made — the bar's Set. Enter and a typed character are gestures
      // made at the selection, so their editor opens beside the cell.
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button
          type="button"
          disabled={disabled}
          // **The whole of what a click does, in every mode.** Where a click selects, the trigger
          // is only an anchor and `onOpenChange` never sees a `true`; where it opens the editor
          // (`CueValueGrid`) it fires alongside that open, which is where this used to
          // live. Unconditional, and identical in all four cells, because the alternative was two
          // mechanisms for one contract — `ColourCell` already did it this way, and a fifth cell
          // modelled on either half could have double-fired or missed. See `CellClickBehaviour`.
          onClick={onBeginEdit}
          className="flex h-full w-full items-center gap-1.5 rounded text-left hover:bg-accent/50"
          title={placeholder ? UNSET_CELL_TITLE : undefined}
        >
          {placeholder ? (
            <UnsetCellMark />
          ) : (
            <>
              {value.option?.colourPreview && (
                <span
                  className="ml-1.5 size-3 shrink-0 rounded-sm border border-border"
                  style={{ backgroundColor: value.option.colourPreview }}
                />
              )}
              <span className="mx-1.5 truncate text-xs text-muted-foreground">
                {value.isUniform ? (value.option?.displayName ?? '—') : 'Mixed'}
              </span>
            </>
          )}
        </button>
      }
    >
      {/* The wrapper carries Enter and comma for every other editor; here the filter answers both
          keys itself and this only catches what it leaves. See `useEditorKeyboard`. */}
      <div ref={contentRef} onKeyDown={onKeyDown}>
        <EditorLabelLine subject={headsLine(heads.count, scopeLabel)} column={label} className="px-2 pt-1 pb-1.5" />
        {showFilter && (
          <Input
            type="text"
            role="combobox"
            aria-expanded
            aria-controls={listId}
            aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
            aria-label={`Filter ${label} options`}
            placeholder="Type to filter"
            spellCheck={false}
            autoComplete="off"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              // A new search is a new list, and its top entry is what Enter takes.
              setHighlight(0)
            }}
            onKeyDown={onFilterKeyDown}
            className="mb-1 h-7 text-xs"
          />
        )}
        <div id={listId} ref={listRef} role="listbox" className="max-h-64 overflow-y-auto">
          {matches.length === 0 && (
            <p className="px-2 py-3 text-center text-xs text-muted-foreground">No matching option</p>
          )}
          {matches.map((option, index) => {
            const isCurrent = value.isUniform && value.option?.name === option.name
            const isHighlighted = showFilter && index === active
            return (
              <button
                key={`${option.name}:${option.level}`}
                type="button"
                id={`${listId}-${index}`}
                role="option"
                // The listbox's *highlight* is what `aria-selected` reports, which is what an
                // `aria-activedescendant` combobox means by it; the desk's current value is the
                // tick beside the name. With no filter drawn there is no highlight to report.
                aria-selected={showFilter ? isHighlighted : undefined}
                data-highlighted={isHighlighted}
                className={cn(
                  'flex w-full items-center gap-2 rounded px-2 text-left text-xs hover:bg-accent',
                  touchTarget ? 'py-2.5' : 'py-1.5',
                  isHighlighted && 'bg-accent text-accent-foreground',
                )}
                onClick={() => choose(option.level)}
              >
                {option.colourPreview ? (
                  <span
                    className="size-3 shrink-0 rounded-sm border border-border"
                    style={{ backgroundColor: option.colourPreview }}
                  />
                ) : (
                  <span className="size-3 shrink-0" />
                )}
                <span className="flex-1 truncate">{option.displayName}</span>
                {isCurrent && <Check className="size-3 shrink-0 text-primary" />}
              </button>
            )
          })}
        </div>
        {skipped && (
          <EditorReadout className="px-2 pt-1.5 pb-1">
            <span>{skipped}</span>
          </EditorReadout>
        )}
      </div>
    </EditorSurface>
  )
})
