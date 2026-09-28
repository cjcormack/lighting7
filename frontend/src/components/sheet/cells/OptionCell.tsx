import { memo, useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { Check } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { EditorSurface, useEditorForm } from '../../editor/EditorSurface'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'
import type { SheetCellProps } from '../sheetModel'

export interface SheetOption {
  value: string
  label: string
  /** A colour chip beside the name — a gel's swatch. */
  swatch?: string
}

export interface OptionCellProps extends SheetCellProps<string> {
  options: readonly SheetOption[]
  /** What the cell shows. Defaults to the current option's label, or an em-dash. */
  face?: ReactNode
}

/**
 * Below this many options the filter is not drawn: there is nothing to narrow, and a search box
 * over a two-row list is a control asking to be read for no gain.
 */
const FILTER_FROM_OPTIONS = 3

/**
 * An option cell — one of a closed list: a fade curve, a mount, a stage flag
 * (CLAUDE.md §Sheet kit). The same type-ahead `SettingCell` draws over a fixture's wheel, over
 * plain `{value, label}` options: the filter matches on the label, ↑/↓ move the highlight, and
 * Enter takes the highlighted option — the top match by default. An Enter that matches nothing is
 * swallowed rather than closing.
 *
 * The label line above the list is the editor kit's (D8). Under the list, the **skip read-out**
 * names the rows the marquee covers that the choice will not reach — master 1's Follows, a snap
 * cue's Curve (library-sheets plan D12): the kit drops them before `write`, and this is where the
 * editor says so. The popover
 * is 256px (D17). `w-64`, measured in the app at 256px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const OptionCell = memo(function OptionCell({
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
  options,
  face,
}: OptionCellProps) {
  const [query, setQuery] = useState('')
  const [highlight, setHighlight] = useState(0)
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
  useEffect(() => {
    if (keyboardOpen) setQuery(keyboardOpen)
  }, [keyboardOpen])
  // Both sheets are reached by a finger, so each option is a touch target there and not in the
  // popover — the form is what answers "is this a finger", never a width variant.
  const touchTarget = useEditorForm() !== 'popover'

  const showFilter = options.length >= FILTER_FROM_OPTIONS
  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!showFilter || needle === '') return options
    return options.filter((option) => option.label.toLowerCase().includes(needle))
  }, [options, query, showFilter])
  const active = matches.length === 0 ? -1 : Math.min(highlight, matches.length - 1)

  const choose = useCallback(
    (next: string) => {
      onCommit(next)
      setOpen(false)
    },
    [onCommit, setOpen],
  )

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
        event.preventDefault()
        const option = matches[active]
        if (option) choose(option.value)
      }
    },
    [active, choose, matches],
  )

  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (active < 0) return
    listRef.current?.querySelector('[data-highlighted="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const current = options.find((option) => option.value === value)

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={label}
      contentClassName="w-64 p-1"
      onOpenAutoFocus={onOpenAutoFocus}
      triggerOpens={false}
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button
          type="button"
          disabled={disabled}
          onClick={onBeginEdit}
          className="flex h-full w-full items-center gap-1.5 rounded text-left hover:bg-accent/50"
        >
          {face ?? (
            <>
              {current?.swatch && (
                <span
                  className="ml-1.5 size-3 shrink-0 rounded-sm border border-border"
                  style={{ backgroundColor: current.swatch }}
                />
              )}
              <span className={cn('mx-1.5 truncate text-xs', current == null && 'text-muted-foreground/60')}>
                {current?.label ?? '—'}
              </span>
            </>
          )}
        </button>
      }
    >
      <div ref={contentRef} onKeyDown={onKeyDown}>
        <EditorLabelLine subject={batchLabel} column={label} className="px-2 pt-1 pb-1.5" />
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
            const isCurrent = option.value === value
            const isHighlighted = showFilter && index === active
            return (
              <button
                key={option.value}
                type="button"
                id={`${listId}-${index}`}
                role="option"
                aria-selected={showFilter ? isHighlighted : undefined}
                data-highlighted={isHighlighted}
                className={cn(
                  'flex w-full items-center gap-2 rounded px-2 text-left text-xs hover:bg-accent',
                  touchTarget ? 'py-2.5' : 'py-1.5',
                  isHighlighted && 'bg-accent text-accent-foreground',
                )}
                onClick={() => choose(option.value)}
              >
                {option.swatch ? (
                  <span className="size-3 shrink-0 rounded-sm border border-border" style={{ backgroundColor: option.swatch }} />
                ) : (
                  <span className="size-3 shrink-0" />
                )}
                <span className="flex-1 truncate">{option.label}</span>
                {isCurrent && <Check className="size-3 shrink-0 text-primary" />}
              </button>
            )
          })}
        </div>
        {skipped && (
          <EditorReadout className="px-2 pt-1.5 pb-1">
            <span data-editor-skipped>{skipped}</span>
          </EditorReadout>
        )}
      </div>
    </EditorSurface>
  )
})
