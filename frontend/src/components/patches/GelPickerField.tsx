import { useMemo, useState } from 'react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Label } from '@/components/ui/label'
import { ChevronDown, X } from 'lucide-react'
import { findGel } from '@/lib/gels'
import { useGelIndex } from '@/hooks/useGelIndex'
import { GelPicker } from './GelPicker'

interface GelPickerFieldProps {
  id?: string
  value: string | null
  onChange: (next: string | null) => void
}

export function GelPickerField({ id, value, onChange }: GelPickerFieldProps) {
  const [open, setOpen] = useState(false)
  const gels = useGelIndex()
  const current = useMemo(() => findGel(gels, value), [gels, value])

  const select = (code: string | null) => {
    onChange(code)
    setOpen(false)
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
          <GelPicker value={value} onPick={(code) => select(code)} autoFocus />
        </PopoverContent>
      </Popover>
    </div>
  )
}
