import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { searchGels, type Gel, type GelBrand } from '@/lib/gels'
import { useGelIndex } from '@/hooks/useGelIndex'

type BrandFilter = 'All' | GelBrand

/**
 * The gel library as a searchable list — the search field, the brand chips, *Open white* and every
 * gel with its swatch, code, name and brand. One control in two hosts: the patch editor's
 * `GelPickerField` popover and the patch list's Gel cell (`GelCell`), so a gel is chosen the same
 * way wherever it is set.
 *
 * It is typed at as well as clicked: ↑/↓ move the highlight over the list, Enter takes the
 * highlighted row. While the search is empty the highlight rests on the current gel (or *Open
 * white*); once something is typed it rests on the first match. Enter is claimed with
 * `preventDefault()`, which is how `useEditorKeyboard` knows to stand aside in the cell.
 */
export function GelPicker({
  value,
  onPick,
  initialQuery = '',
  autoFocus = false,
  className,
}: {
  /** The current gel code, or null for open white. */
  value: string | null
  /** A row was chosen: the gel's code, or null for open white. */
  onPick: (code: string | null) => void
  /** What the search opens with — the character typed at the grid that opened the cell. */
  initialQuery?: string
  /** Focus the search on mount. The cell leaves this to its editor's own focus rule. */
  autoFocus?: boolean
  className?: string
}) {
  const [query, setQuery] = useState(initialQuery)
  const [brand, setBrand] = useState<BrandFilter>('All')
  const listId = useId()
  const gels = useGelIndex()
  const results = useMemo(() => searchGels(gels, query, brand), [gels, query, brand])
  /** The rows in list order: open white, then the matches. */
  const items = useMemo<(Gel | null)[]>(() => [null, ...results], [results])

  // A seed that arrives after mount (the cell's keyboard seed is latched a render later).
  useEffect(() => {
    if (initialQuery) setQuery(initialQuery)
  }, [initialQuery])

  const restingIndex = useMemo(() => {
    if (query.trim() !== '') return results.length > 0 ? 1 : 0
    const at = items.findIndex((g) => (g?.code ?? null) === value)
    return at < 0 ? 0 : at
  }, [items, query, results.length, value])
  const [highlight, setHighlight] = useState<number | null>(null)
  const active = Math.min(highlight ?? restingIndex, items.length - 1)

  const listRef = useRef<HTMLUListElement>(null)
  useEffect(() => {
    listRef.current?.querySelector('[data-highlighted="true"]')?.scrollIntoView?.({ block: 'nearest' })
  }, [active])

  const onSearchKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.metaKey || event.ctrlKey || event.altKey) return
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      setHighlight(Math.max(0, Math.min(items.length - 1, active + delta)))
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      const item = items[active]
      if (item !== undefined) onPick(item?.code ?? null)
    }
  }

  return (
    <div className={className}>
      <Input
        type="text"
        role="combobox"
        aria-expanded
        aria-controls={listId}
        aria-activedescendant={items[active] !== undefined ? `${listId}-${active}` : undefined}
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setHighlight(null)
        }}
        onKeyDown={onSearchKeyDown}
        placeholder="Search gels (e.g. L201, blue, amber)…"
        aria-label="Search gels"
        spellCheck={false}
        autoComplete="off"
        className="rounded-none border-0 border-b border-border focus-visible:border-border focus-visible:ring-0"
        autoFocus={autoFocus}
      />
      <div className="flex gap-1 border-b border-border px-2 py-1.5">
        {(['All', ...gels.brands] as BrandFilter[]).map((b) => {
          const selected = brand === b
          return (
            <button
              key={b}
              type="button"
              aria-pressed={selected}
              onClick={() => {
                setBrand(b)
                setHighlight(null)
              }}
              className={cn(
                'rounded px-2.5 py-0.5 text-[11px] transition-colors',
                selected ? 'bg-muted text-foreground' : 'text-muted-foreground/70 hover:text-foreground',
              )}
            >
              {b}
            </button>
          )
        })}
      </div>
      <ul id={listId} ref={listRef} role="listbox" aria-label="Gels" className="max-h-60 overflow-y-auto py-1">
        {items.map((g, index) => {
          const isCurrent = (g?.code ?? null) === value
          const isHighlighted = index === active
          return (
            <li key={g ? g.brand + g.code : 'open-white'} role="presentation">
              <button
                type="button"
                id={`${listId}-${index}`}
                role="option"
                aria-selected={isCurrent}
                data-highlighted={isHighlighted}
                onClick={() => onPick(g?.code ?? null)}
                className={cn(
                  'flex w-full items-center gap-2.5 px-3 py-1.5 text-left hover:bg-muted/60',
                  isHighlighted && 'bg-muted/60',
                )}
              >
                {g ? (
                  <>
                    <span
                      className="block h-4 w-4 shrink-0 rounded-sm border"
                      style={{
                        background: g.color,
                        borderColor: 'rgba(255,255,255,0.1)',
                        boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.5)',
                      }}
                      aria-hidden
                    />
                    <span className="w-10 shrink-0 font-mono text-[11px] text-foreground">{g.code}</span>
                    <span className="flex-1 truncate text-[11px] text-muted-foreground">
                      {g.name}
                      <span className="ml-1.5 text-muted-foreground/60">{g.brand}</span>
                    </span>
                  </>
                ) : (
                  <>
                    <span
                      className="block h-4 w-4 shrink-0 rounded-sm border border-dashed border-muted-foreground/40"
                      aria-hidden
                    />
                    <span className="w-10 font-mono text-[11px] text-muted-foreground">—</span>
                    <span className="flex-1 truncate text-[11px] italic text-muted-foreground/70">Open white</span>
                  </>
                )}
              </button>
            </li>
          )
        })}
        {results.length === 0 && <li role="presentation" className="px-3 py-2 text-[11px] text-muted-foreground/70">No gels match.</li>}
      </ul>
    </div>
  )
}
