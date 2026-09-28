import { RotateCcw } from 'lucide-react'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import type { CueStack } from '@/api/cueStacksApi'

interface StackPickerSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The project's ordered stacks + separators. */
  stacks: CueStack[]
  selectedStackId: number | null
  onSelectStack: (stack: CueStack) => void
}

export function StackPickerSheet({
  open,
  onOpenChange,
  stacks,
  selectedStackId,
  onSelectStack,
}: StackPickerSheetProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="bottom"
        className="max-h-[70dvh] p-0 gap-0"
      >
        <SheetHeader className="border-b">
          <SheetTitle>Stacks</SheetTitle>
        </SheetHeader>
        <div className="overflow-y-auto">
          {stacks.length === 0 ? (
            <div className="p-6 text-center text-sm text-muted-foreground">
              This show has no stacks yet.
            </div>
          ) : (
            stacks.map((stack) => {
              if (stack.type === 'SEPARATOR') {
                return (
                  <div
                    key={stack.id}
                    className="flex items-center gap-2.5 px-4 py-2 border-b"
                  >
                    <div className="flex-1 h-px bg-border" />
                    <span className="text-xs font-medium uppercase text-muted-foreground">
                      {stack.label ?? stack.name}
                    </span>
                    <div className="flex-1 h-px bg-border" />
                  </div>
                )
              }
              const isSelected = stack.id === selectedStackId
              return (
                <button
                  key={stack.id}
                  type="button"
                  onClick={() => {
                    onSelectStack(stack)
                    onOpenChange(false)
                  }}
                  className={cn(
                    'flex w-full items-center gap-2 h-12 px-4 border-b text-left transition-colors hover:bg-muted/40',
                    isSelected &&
                      'bg-primary/10 border-l-[3px] border-l-primary',
                  )}
                >
                  <span
                    className={cn(
                      'flex-1 text-sm font-medium truncate',
                      isSelected && 'text-primary',
                    )}
                  >
                    {stack.name}
                  </span>
                  {stack.loop && (
                    <RotateCcw className="size-3.5 text-muted-foreground shrink-0" />
                  )}
                  {stack.activeCueId != null && (
                    <span className="text-[10px] font-medium uppercase tracking-wide text-green-500 shrink-0">
                      Live
                    </span>
                  )}
                </button>
              )
            })
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
