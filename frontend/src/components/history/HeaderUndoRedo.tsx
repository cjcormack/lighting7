import { Redo2, Undo2 } from "lucide-react"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { cn } from "@/lib/utils"
import { useHistoryState } from "@/store/history"
import type { HistoryTop } from "@/api/historyApi"
import { useHistoryMove } from "./HistoryMove"
import { undoShortcutLabel } from "./undoKeys"

/** What a tooltip or a menu item says for [top]: *Undo · Delete cue Q12*, or that there is nothing. */
export function historyActionLabel(
  direction: "undo" | "redo",
  top: HistoryTop | null,
): string {
  const verb = direction === "undo" ? "Undo" : "Redo"
  return top ? `${verb} · ${top.label}` : `Nothing to ${direction}`
}

/**
 * The header's Undo and Redo (undo plan §4.1): one joined pair, white on the header's blue, taking
 * what ⌘Z and ⇧⌘Z take — the newer of the programmer's top step and your newest show change (D1,
 * D2). The tooltip names it and its key; a programmer step's is violet-tinted. Both dim with
 * nothing to take.
 */
export function HeaderUndoRedo({ className }: { className?: string }) {
  const history = useHistoryState()
  const { undo, redo } = useHistoryMove()
  return (
    <div
      className={cn(
        "inline-flex h-7 shrink-0 items-stretch overflow-hidden rounded-md border border-primary-foreground/30",
        className,
      )}
      role="group"
      aria-label="Undo and redo"
    >
      <HeaderMoveButton
        direction="undo"
        top={history.undo}
        onPress={() => void undo()}
      />
      <HeaderMoveButton
        direction="redo"
        top={history.redo}
        onPress={() => void redo()}
      />
    </div>
  )
}

function HeaderMoveButton({
  direction,
  top,
  onPress,
}: {
  direction: "undo" | "redo"
  top: HistoryTop | null
  onPress: () => void
}) {
  const Icon = direction === "undo" ? Undo2 : Redo2
  const label = historyActionLabel(direction, top)
  return (
    <Tooltip>
      {/* A span, since a disabled button takes no pointer: its tooltip still says there is nothing. */}
      <TooltipTrigger asChild>
        <span className="inline-flex">
          <button
            type="button"
            disabled={top == null}
            onClick={onPress}
            aria-label={
              top
                ? label.replace(" · ", " ")
                : direction === "undo"
                  ? "Undo"
                  : "Redo"
            }
            className={cn(
              "inline-flex items-center px-2 text-primary-foreground transition-colors hover:bg-primary-foreground/10 disabled:pointer-events-none disabled:opacity-40",
              direction === "redo" && "border-l border-primary-foreground/30",
            )}
          >
            <Icon className="size-4" />
          </button>
        </span>
      </TooltipTrigger>
      <TooltipContent
        className={cn(top?.kind === "PROGRAMMER" && "bg-violet-700 text-white")}
      >
        {label}
        {top && (
          <span className="ml-2 font-mono opacity-70">
            {undoShortcutLabel(direction)}
          </span>
        )}
      </TooltipContent>
    </Tooltip>
  )
}
