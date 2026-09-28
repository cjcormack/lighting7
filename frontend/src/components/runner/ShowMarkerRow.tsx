import { useState, useRef, useEffect, useCallback } from 'react'
import { GripVertical, X } from 'lucide-react'
import { useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Input } from '@/components/ui/input'
import { MarkerRow } from '@/components/runner/MarkerRow'

interface ShowMarkerRowProps {
  id: number
  name: string
  onRename: (name: string) => void
  onDelete: () => void
  /** Show-safe mode: no dragging, no renaming, no deleting. */
  locked?: boolean
}

export function ShowMarkerRow({
  id,
  name,
  onRename,
  onDelete,
  locked = false,
}: ShowMarkerRowProps) {
  const [localName, setLocalName] = useState(name)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled: locked,
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 50 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  useEffect(() => {
    setLocalName(name)
  }, [name])

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])

  const debouncedRename = useCallback(
    (value: string) => {
      if (timerRef.current) clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => {
        if (value.trim()) onRename(value.trim())
      }, 400)
    },
    [onRename],
  )

  // Locked, a separator is just a labelled divider — the same one the phone cue list and the
  // Prompt Book rail draw, so render `MarkerRow` rather than a second spelling of it. The wrapper
  // keeps the unlocked row's padding and grip-column spacer, and suppresses `MarkerRow`'s own
  // horizontal padding, so the divider starts at the same x in both states: nothing shifts
  // sideways as the lock flips.
  if (locked) {
    return (
      <div
        ref={setNodeRef}
        style={style}
        {...attributes}
        className="flex items-center gap-2.5 py-2 px-4 hover:bg-muted/10 transition-colors"
      >
        <div className="size-4 shrink-0" />
        <MarkerRow name={name} className="min-w-0 flex-1 px-0 py-0" />
      </div>
    )
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      className="flex items-center gap-2.5 py-2 px-4 hover:bg-muted/10 transition-colors"
    >
      <div
        {...listeners}
        className="flex items-center justify-center text-muted-foreground hover:text-foreground cursor-grab"
      >
        <GripVertical className="size-4" />
      </div>
      <div className="flex-1 h-px bg-border" />
      <Input
        value={localName}
        onChange={(e) => {
          setLocalName(e.target.value)
          debouncedRename(e.target.value)
        }}
        onClick={(e) => e.stopPropagation()}
        className="h-7 w-auto min-w-[120px] max-w-[200px] text-center text-xs font-medium text-muted-foreground bg-card border-border"
      />
      <div className="flex-1 h-px bg-border" />
      <button
        className="size-5 flex items-center justify-center text-muted-foreground hover:text-destructive transition-colors"
        onClick={(e) => {
          e.stopPropagation()
          onDelete()
        }}
      >
        <X className="size-3.5" />
      </button>
    </div>
  )
}
