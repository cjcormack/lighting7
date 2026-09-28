import { useMemo, useState } from 'react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ChevronDown, X } from 'lucide-react'
import {
  GEL_BRANDS,
  findGel,
  searchGels,
  type Gel,
  type GelBrand,
} from '@/data/gels'

type BrandFilter = 'All' | GelBrand

interface GelPickerFieldProps {
  id?: string
  value: string | null
  onChange: (next: string | null) => void
}

export function GelPickerField({ id, value, onChange }: GelPickerFieldProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [brand, setBrand] = useState<BrandFilter>('All')
  const current = useMemo(() => findGel(value), [value])
  const results = useMemo(() => searchGels(query, brand), [query, brand])

  const select = (gel: Gel | null) => {
    onChange(gel?.code ?? null)
    setOpen(false)
    setQuery('')
  }

  return (
    <div className="space-y-2">
      <Label htmlFor={id}>Gel</Label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            id={id}
            type="button"
            className="flex w-full items-center gap-2.5 rounded-md border border-input bg-background px-2.5 py-1.5 text-left transition-colors hover:border-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <span
              className="block w-5 h-5 rounded-sm shrink-0 border"
              style={{
                background: current?.color ?? 'transparent',
                borderColor: current ? 'rgba(255,255,255,0.1)' : 'var(--border)',
                borderStyle: current ? 'solid' : 'dashed',
                boxShadow: current ? 'inset 0 0 0 1px rgba(0,0,0,0.5)' : undefined,
              }}
              aria-hidden
            />
            <span className="flex-1 min-w-0">
              {current ? (
                <>
                  <span className="block font-mono text-xs text-foreground">
                    {current.code}
                    <span className="ml-1.5 text-[10px] text-muted-foreground/70">
                      {current.brand}
                    </span>
                  </span>
                  <span className="block text-[11px] text-muted-foreground/70 truncate">
                    {current.name}
                  </span>
                </>
              ) : (
                <span className="block text-xs italic text-muted-foreground/70">
                  Open white — no gel
                </span>
              )}
            </span>
            {current && (
              <span
                role="button"
                tabIndex={0}
                onClick={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  onChange(null)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    e.stopPropagation()
                    onChange(null)
                  }
                }}
                className="text-muted-foreground/70 hover:text-foreground p-0.5 rounded-sm cursor-pointer"
                aria-label="Clear gel"
              >
                <X className="size-3.5" />
              </span>
            )}
            <ChevronDown className="size-3.5 text-muted-foreground/70" />
          </button>
        </PopoverTrigger>
        <PopoverContent
          align="start"
          className="w-(--radix-popover-trigger-width) min-w-72 p-0 overflow-hidden"
        >
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search gels (e.g. L201, blue, amber)…"
            className="border-0 border-b border-border rounded-none focus-visible:ring-0 focus-visible:border-border"
            autoFocus
          />
          <div className="flex gap-1 border-b border-border px-2 py-1.5">
            {(['All', ...GEL_BRANDS] as BrandFilter[]).map((b) => {
              const selected = brand === b
              return (
                <button
                  key={b}
                  type="button"
                  onClick={() => setBrand(b)}
                  className={
                    'px-2.5 py-0.5 rounded text-[11px] transition-colors ' +
                    (selected
                      ? 'bg-muted text-foreground'
                      : 'text-muted-foreground/70 hover:text-foreground')
                  }
                >
                  {b}
                </button>
              )
            })}
          </div>
          <ul className="max-h-60 overflow-y-auto py-1">
            <li>
              <button
                type="button"
                onClick={() => select(null)}
                className="flex w-full items-center gap-2.5 px-3 py-1.5 hover:bg-muted/60 text-left"
              >
                <span
                  className="block w-4 h-4 rounded-sm shrink-0 border border-dashed border-muted-foreground/40"
                  aria-hidden
                />
                <span className="font-mono text-[11px] text-muted-foreground w-10">—</span>
                <span className="text-[11px] italic text-muted-foreground/70 truncate">
                  Open white
                </span>
              </button>
            </li>
            {results.map((g) => (
              <li key={g.brand + g.code}>
                <button
                  type="button"
                  onClick={() => select(g)}
                  className="flex w-full items-center gap-2.5 px-3 py-1.5 hover:bg-muted/60 text-left"
                >
                  <span
                    className="block w-4 h-4 rounded-sm shrink-0 border"
                    style={{
                      background: g.color,
                      borderColor: 'rgba(255,255,255,0.1)',
                      boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.5)',
                    }}
                    aria-hidden
                  />
                  <span className="font-mono text-[11px] text-foreground w-10 shrink-0">
                    {g.code}
                  </span>
                  <span className="text-[11px] text-muted-foreground truncate flex-1">
                    {g.name}
                    <span className="ml-1.5 text-muted-foreground/60">{g.brand}</span>
                  </span>
                </button>
              </li>
            ))}
            {results.length === 0 && (
              <li className="px-3 py-2 text-[11px] text-muted-foreground/70">
                No gels match.
              </li>
            )}
          </ul>
        </PopoverContent>
      </Popover>
    </div>
  )
}
