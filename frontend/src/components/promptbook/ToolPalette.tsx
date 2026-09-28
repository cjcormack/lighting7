import { MousePointer2, StickyNote, Strikethrough, Type } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type PromptBookTool = 'move' | 'note' | 'strikethrough' | 'freetext'

const TOOLS: { id: PromptBookTool; label: string; icon: typeof MousePointer2; danger?: boolean }[] = [
  { id: 'move', label: 'Select', icon: MousePointer2 },
  { id: 'note', label: 'Add note', icon: StickyNote },
  { id: 'strikethrough', label: 'Mark cut', icon: Strikethrough, danger: true },
  { id: 'freetext', label: 'Text', icon: Type },
]

/**
 * Edit-mode annotation bar — a horizontal row under the toolbar, rendered only
 * while unlocked. Cue anchors are placed from the cue list (click a cue → click the
 * script), so this bar covers only the free annotations.
 *
 * `warn` carries the app's "you are editing" signal: amber while the show is RUNNING,
 * where being unlocked is a hazard. Editing a stopped show is the ordinary case and
 * gets ordinary chrome.
 *
 * Sizing is driven by *container* queries, not the viewport: the bar sits beside the
 * cue rail, so its own width — not the window's — decides what fits. It sheds the
 * hint, then the "Annotate" heading, then the button labels as it narrows, and wraps
 * as a last resort so it can never widen the page into a horizontal scroll.
 */
export function ToolPalette({
  tool,
  warn,
  placingLabel,
  onSelectTool,
}: {
  tool: PromptBookTool
  /** Unlocked mid-show — wash the bar amber. */
  warn: boolean
  /** When a cue is armed for (re-)anchoring, its label ("Q12") — shows a targeted prompt. */
  placingLabel?: string | null
  onSelectTool: (tool: PromptBookTool) => void
}) {
  return (
    <div
      className={cn(
        '@container/annotate flex shrink-0 flex-wrap items-center gap-x-1 gap-y-1 border-b px-2 py-1.5',
        '@md/annotate:gap-x-1.5 @md/annotate:px-4',
        warn && 'border-amber-500/40 bg-amber-400/10',
      )}
    >
      <span
        className={cn(
          'mr-1 hidden text-[10px] font-bold tracking-widest uppercase @md/annotate:inline',
          warn ? 'text-amber-600/80' : 'text-muted-foreground',
        )}
      >
        Annotate
      </span>
      {TOOLS.map(({ id, label, icon: Icon, danger }) => (
        <Button
          key={id}
          variant="ghost"
          size="sm"
          onClick={() => onSelectTool(id)}
          // The label is dropped on the narrowest containers, so it has to survive as
          // the accessible name (and hover title) on its own.
          aria-label={label}
          title={label}
          className={cn(
            'h-7 shrink-0 gap-1.5 px-2 text-xs',
            tool === id
              ? danger
                ? 'bg-red-500/15 text-red-400 hover:bg-red-500/20 hover:text-red-400'
                : warn
                  ? 'bg-amber-400/20 text-amber-600 hover:bg-amber-400/25 hover:text-amber-600'
                  : 'bg-primary/15 text-primary hover:bg-primary/20 hover:text-primary'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <Icon className="size-3.5" />
          <span className="hidden @xs/annotate:inline">{label}</span>
        </Button>
      ))}
      {placingLabel ? (
        <span className="w-full text-[11px] font-semibold text-amber-600 @md/annotate:ml-auto @md/annotate:w-auto">
          Select the script text to anchor {placingLabel}…
        </span>
      ) : (
        // flex-1/min-w-0 keeps the hint on the buttons' line, shrinking and wrapping
        // inside itself rather than claiming a whole second row of chrome.
        <span className="hidden min-w-0 flex-1 text-[11px] text-muted-foreground @3xl/annotate:block">
          Select script text to highlight, cut, or note it — or arm a cue in the list, then select its line.
          On scanned pages, pick a tool and drag a box.
        </span>
      )}
    </div>
  )
}
