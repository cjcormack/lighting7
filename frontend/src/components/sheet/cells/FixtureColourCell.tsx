import { memo, useCallback, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { useEditorOpen } from '@/components/editor/useEditorOpen'
import { ColourSwatch, ColourSwatchGrid } from '@/components/fixtureColours/ColourSwatches'
import {
  FIXTURE_COLOURS,
  FIXTURE_COLOUR_LABELS,
  colourForDigit,
  readColour,
  type FixtureColour,
} from '@/lib/fixtureColours'
import type { SheetCellProps } from '../sheetModel'

/** The popover's clear: its word and what it does, on its hover. */
export interface ColourClear {
  label: string
  title: string
}

/** The popover's *From group* press: its label's group, and the copy itself, all of them with ⌥. */
export interface ColourFromGroup {
  label: string
  run: (all: boolean) => void
}

/**
 * A fixture's **Colour** on the sheet kit (tables-colours plan D7, D11; `Colours.dc.html` §B): the
 * swatch and its name, and a popover of the ten swatches through `EditorSurface`, so it takes the
 * three forms and the double click every kit cell does. Not `fixtures-list/cells/ColourCell`, which
 * edits a light's live output: this one says which fixture a row is.
 *
 * The value is a key, `''` for none, and the column's `write` reads either. Over the grid, keys 1–9
 * and 0 pick a colour without opening this (the column's `typed`) and ⌫ is the kit's Clear; inside
 * the open popover the same keys pick and close. [owner] `SHOW` is a show's recolour of a venue
 * record (D10), drawn *yours*; [clear] names what the clear does over the batch, null where it
 * would change nothing. It and [fromGroup] read the batch, so they are asked only while the popover
 * is open.
 */
export const FixtureColourCell = memo(function FixtureColourCell({
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
  yours = false,
  clear = noneClear,
  fromGroup = noFromGroup,
}: SheetCellProps<string> & {
  yours?: boolean
  clear?: () => ColourClear | null
  fromGroup?: () => ColourFromGroup | null
}) {
  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
  })
  const colour = readColour(value)
  const gridRef = useRef<HTMLDivElement>(null)

  const pick = useCallback(
    (next: FixtureColour | null) => {
      onCommit(next ?? '')
      setOpen(false)
    },
    [onCommit, setOpen],
  )

  // Focus the swatch a typed letter names (`r` is Red), else the current colour's, else the first.
  const onOpenAutoFocus = useCallback(
    (event: Event) => {
      const letter = keyboardOpen?.toLowerCase() ?? ''
      const wanted =
        FIXTURE_COLOURS.find((c) => letter !== '' && FIXTURE_COLOUR_LABELS[c].toLowerCase().startsWith(letter)) ??
        colour ??
        FIXTURE_COLOURS[0]
      const button = gridRef.current?.querySelector<HTMLButtonElement>(`[data-colour="${wanted}"]`)
      if (!button) return
      event.preventDefault()
      button.focus()
    },
    [colour, keyboardOpen],
  )

  const onKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLElement>) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return
      const digit = colourForDigit(event.key)
      if (digit) {
        event.preventDefault()
        pick(digit)
      } else if ((event.key === 'Backspace' || event.key === 'Delete') && clear() != null) {
        event.preventDefault()
        pick(null)
      }
    },
    [clear, pick],
  )

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={label}
      contentClassName="w-[300px] p-3"
      onOpenAutoFocus={onOpenAutoFocus}
      triggerOpens={false}
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button
          type="button"
          disabled={disabled}
          onClick={onBeginEdit}
          className="flex h-full w-full items-center rounded text-left hover:bg-accent/50"
        >
          {colour ? (
            <span className="mx-1.5 flex min-w-0 items-center gap-1.5">
              <ColourSwatch colour={colour} />
              <span className="truncate text-xs">{FIXTURE_COLOUR_LABELS[colour]}</span>
              {yours && (
                <span className="shrink-0 text-[10px] text-primary" title="This show's colour, laid over the venue's">
                  yours
                </span>
              )}
            </span>
          ) : (
            <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
          )}
        </button>
      }
    >
      <div className="flex flex-col gap-2.5" onKeyDown={onKeyDown} ref={gridRef}>
        <EditorLabelLine subject={batchLabel} column={label} />
        <ColourSwatchGrid value={colour} onPick={pick} />
        <ColourEditorVerbs clear={clear()} fromGroup={fromGroup()} onClear={() => pick(null)} onDone={() => setOpen(false)} />
        <EditorReadout>
          {skipped ? <span data-editor-skipped>{skipped}</span> : 'Keys 1–9, 0 and ⌫ while the column’s cells are selected.'}
        </EditorReadout>
      </div>
    </EditorSurface>
  )
})

const noneClear = (): ColourClear => ({ label: 'None', title: 'Take the colour off' })
const noFromGroup = () => null

/** The popover's footer: the clear and *From group* (D9), both over the batch. */
function ColourEditorVerbs({
  clear,
  fromGroup,
  onClear,
  onDone,
}: {
  clear: ColourClear | null
  fromGroup: ColourFromGroup | null
  onClear: () => void
  onDone: () => void
}) {
  if (clear == null && fromGroup == null) return null
  return (
    <div className="flex gap-1.5 border-t pt-2.5">
      {clear != null && (
        <Button variant="outline" size="sm" onClick={onClear} title={`${clear.title} (Backspace)`}>
          {clear.label}
        </Button>
      )}
      {fromGroup && (
        <Button
          variant="outline"
          size="sm"
          className="min-w-0 flex-1"
          title="Copy the group's colour onto the fixtures that have none — hold ⌥ for all of them"
          onClick={(e) => {
            fromGroup.run(e.altKey)
            onDone()
          }}
        >
          <span className="truncate">{fromGroup.label}</span>
        </Button>
      )}
    </div>
  )
}
