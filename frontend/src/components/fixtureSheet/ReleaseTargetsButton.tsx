import { ChevronDown, Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ToolbarMenu, ToolbarMenuItem } from '@/components/sheet/ToolbarMenu'
import { cn } from '@/lib/utils'
import { useReleaseTargets, type ReleaseTarget } from './useRelease'

/** The ⌫ key cap beside a verb's word — the shape the selection bar's hints use. */
const KBD_CLASS = 'rounded border bg-muted/50 px-1 py-px font-mono text-[9.5px] leading-none text-muted-foreground'

/**
 * Release's cell arm, which only the programmer's row C passes (programmer-toolbar plan D7): the
 * marquee's cells, the gesture ⌫ makes, and why the scope refuses it.
 */
export interface ReleaseCellsArm {
  /** The marquee's cells; 0 with rows alone. */
  count: number
  /** The heads the whole-heads arm releases: a group row is every member, shown or not. */
  heads: number
  /** Take them out of Local — the grid's ⌫. */
  onRelease: () => void
  /** Why the scope refuses it (Output, a layer), or null where it acts. */
  refused: string | null
  /** Where the ⌫ key cap shows. */
  kbdClass: string
}

/**
 * **Release n** over a selection (fixture-fx-sheets plan D18): the fixture sheet's Release, with
 * the eraser glyph and the count, over every selected target — one `clearTarget` each
 * ([useReleaseTargets]). The count is the targets; the word folds by the host's own rung
 * ([wordClass]) — `VERB_WORD_CLASS` on the busk rig row — and the count goes with it, kept on the
 * title and the accessible name. Like the verbs beside it, it is not gated on the socket: a send on a
 * closed one is toasted by the send.
 *
 * **Given [cells] it is the programmer's split button** (programmer-toolbar plan D7): the face
 * releases the cells when there are any, else the whole heads, and the menu has both arms — *These
 * n cells* (⌫: their values leave Local) and *The whole n heads* (every value and Local FX on them).
 * The count moves off the face there: the heads chip beside it says it.
 */
export function ReleaseTargetsButton({
  targets,
  wordClass,
  className,
  disabledReason,
  cells,
}: {
  targets: readonly ReleaseTarget[]
  wordClass: string
  className?: string
  /** The host refuses it here, and says why on the title — the programmer's focused-layer scope. */
  disabledReason?: string
  cells?: ReleaseCellsArm
}) {
  const release = useReleaseTargets(targets)
  const n = targets.length
  const headsTitle =
    disabledReason != null
      ? disabledReason
      : n === 0
        ? 'Nothing selected to release'
        : `Release ${n} — every value and local FX the selection holds in the programmer; pads stay lit`
  if (cells == null) {
    return (
      <Button
        variant="outline"
        size="sm"
        data-release-selection
        className={className}
        disabled={n === 0 || disabledReason != null}
        aria-label={`Release ${n}`}
        title={headsTitle}
        onClick={() => void release()}
      >
        <Eraser className="size-3.5" />
        <span className={wordClass}>Release</span>
        <span className={`${wordClass} font-mono text-muted-foreground`}>{n}</span>
      </Button>
    )
  }

  const heads = `${cells.heads} head${cells.heads === 1 ? '' : 's'}`
  const cellWord = `${cells.count} cell${cells.count === 1 ? '' : 's'}`
  const headsRefused = n === 0 || disabledReason != null
  const cellsRefused = cells.count === 0 || cells.refused != null
  const onCells = cells.count > 0
  const faceRefused = onCells ? cellsRefused : headsRefused
  return (
    <div data-release-selection className={cn('inline-flex h-8 shrink-0 items-stretch', className)}>
      <Button
        variant="outline"
        size="sm"
        className="rounded-r-none"
        disabled={faceRefused}
        aria-label={onCells ? `Release the ${cellWord}` : `Release the ${heads}`}
        title={
          onCells
            ? (cells.refused ?? `Take the ${cellWord} out of Local, with any FX busked on them (Backspace)`)
            : headsTitle
        }
        onClick={onCells ? cells.onRelease : () => void release()}
      >
        <Eraser className="size-3.5" />
        <span className={wordClass}>Release</span>
        {/* Only where ⌫ does it: a refused key is not advertised. */}
        {onCells && !cellsRefused && <kbd className={cn(KBD_CLASS, cells.kbdClass)}>⌫</kbd>}
      </Button>
      <ToolbarMenu
        title="Release"
        align="end"
        contentClassName="w-[280px]"
        trigger={
          <Button
            variant="outline"
            size="sm"
            className="-ml-px rounded-l-none px-1.5"
            aria-label="Release options"
            disabled={cellsRefused && headsRefused}
          >
            <ChevronDown className="size-3.5" />
          </Button>
        }
      >
        <ToolbarMenuItem disabled={cellsRefused} onSelect={cells.onRelease} className="items-start">
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="text-xs font-medium">{cells.count > 0 ? `These ${cellWord}` : 'These cells'}</span>
            <span className="text-[10.5px] text-muted-foreground">
              {cells.count === 0 ? 'Pick cells first' : (cells.refused ?? 'Their values leave Local')}
            </span>
          </span>
          {!cellsRefused && <kbd className={KBD_CLASS}>⌫</kbd>}
        </ToolbarMenuItem>
        <ToolbarMenuItem disabled={headsRefused} onSelect={() => void release()} className="items-start">
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="text-xs font-medium">{`The whole ${heads}`}</span>
            <span className="text-[10.5px] text-muted-foreground">
              {disabledReason ?? 'Every value and Local FX on them'}
            </span>
          </span>
        </ToolbarMenuItem>
      </ToolbarMenu>
    </div>
  )
}
