import { Maximize2 } from 'lucide-react'
import { Button } from '@/components/ui/button'

/**
 * A card's **corner** (fixture-fx-sheets plan §4, the Hosts board): it opens the whole sheet — the
 * fixture's 512px pop-up, or a group card's group sheet — for what the card host leaves out (the
 * header's views, Release, the scope line). It sits last in the card's header actions, after Locate
 * and Park, where the per-card Edit pencil used to be.
 */
export function OpenSheetButton({ name, onOpen }: { name: string; onOpen: () => void }) {
  return (
    <Button
      variant="outline"
      size="icon"
      data-open-sheet
      aria-label={`Open ${name}’s sheet`}
      title={`Open ${name}’s sheet`}
      className="size-8"
      onClick={onOpen}
    >
      <Maximize2 className="size-3.5" />
    </Button>
  )
}
