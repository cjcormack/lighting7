import { TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'

// The ordering model this banner used to carry inline now lives in `@/lib/cueNumber` — import
// `detectOutOfOrder` from there. The old local version only considered cue numbers starting with a
// digit, so a prefixed stack ("S1-4" before "S1-3.1") never triggered it.

interface OutOfOrderBannerProps {
  onFixOrder: () => void
  onDismiss: () => void
}

export function OutOfOrderBanner({ onFixOrder, onDismiss }: OutOfOrderBannerProps) {
  return (
    <div className="flex items-center gap-2.5 px-4 py-1.5 bg-amber-500/[0.07] border-b border-amber-500/[0.18] text-[12px] text-amber-600 dark:text-amber-500/80 shrink-0">
      <TriangleAlert className="size-3.5 shrink-0" />
      <span>Cue numbers are out of order.</span>
      <Button
        variant="outline"
        size="sm"
        className="h-5 px-2.5 text-[10px] font-bold tracking-wider border-amber-500/30 text-amber-600 dark:text-amber-500/80 hover:bg-amber-500/10"
        onClick={onFixOrder}
      >
        Fix Order
      </Button>
      <Button
        variant="outline"
        size="sm"
        className="h-5 px-2.5 text-[10px] font-bold tracking-wider border-amber-500/30 text-amber-600 dark:text-amber-500/80 hover:bg-amber-500/10"
        onClick={onDismiss}
      >
        Dismiss
      </Button>
    </div>
  )
}
