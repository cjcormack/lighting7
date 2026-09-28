import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

interface MarkerRowProps {
  name: string
  /**
   * Merged over the row's own classes. `ShowMarkerRow` passes `px-0` because its wrapper already
   * supplies the row padding around the grip column — see the note there.
   */
  className?: string
}

export function MarkerRow({ name, className }: MarkerRowProps) {
  return (
    <div className={cn('flex items-center gap-2.5 px-3.5 py-2', className)}>
      <div className="flex-1 h-px bg-border" />
      <Badge variant="outline" className="text-xs font-medium text-muted-foreground bg-card rounded-sm">
        {name}
      </Badge>
      <div className="flex-1 h-px bg-border" />
    </div>
  )
}
