import { useState, type ReactNode } from 'react'
import { ChevronDown, Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { useReleaseTargets, type ReleaseTarget } from './useRelease'
import { plural } from '@/lib/plural'
import { KBD_CLASS } from '@/components/sheet/toolbarFolds'

/**
 * The programmer's row C arm (plan D7): one Release over the selection's **cells** or its **whole
 * heads**. The face does the cells when there are any, else the heads; the menu offers both.
 */
export interface ReleaseSplit {
  /** The heads the selection reaches, for the words. */
  heads: number
  /** The marquee's cells; 0 for rows alone. */
  cells: number
  /** Take the cells' values out of Local — ⌫'s gesture. */
  onReleaseCells: () => void
  /** Why this scope refuses the cells arm (`cellActionCopy`'s clear wording), or undefined. */
  cellsRefusal?: string
  /** The cells arm's hover where it is offered. */
  cellsTitle: string
  /** Draw ⌫ on the face and the cells item (the row's widest rung). */
  showKeys: boolean
  /** Open the menu as a bottom sheet (a phone). */
  sheet: boolean
}

/**
 * **Release n** over a selection (fixture-fx-sheets plan D18): the fixture sheet's Release, with
 * the eraser glyph and the count, over every selected target — one `clearTarget` each
 * ([useReleaseTargets]). The count is the targets; the word folds by the host's own rung
 * ([wordClass]) — `WORD_CLASS` on the programmer's row C, `VERB_WORD_CLASS` on the busk rig row —
 * and the count goes with it, kept on the title and the accessible name. Like the verbs beside it,
 * it is not gated on the socket: a send on a closed one is toasted by the send.
 *
 * With [split] it is the programmer's split button instead ([ReleaseSplit]).
 */
export function ReleaseTargetsButton({
  targets,
  wordClass,
  className,
  disabledReason,
  split,
}: {
  targets: readonly ReleaseTarget[]
  wordClass: string
  className?: string
  /** The host refuses it here, and says why on the title — the programmer's focused-layer scope. */
  disabledReason?: string
  split?: ReleaseSplit
}) {
  const release = useReleaseTargets(targets)
  const n = targets.length
  if (split != null) {
    return (
      <ReleaseSplitButton
        split={split}
        canReleaseHeads={n > 0 && disabledReason == null}
        headsRefusal={disabledReason}
        onReleaseHeads={() => void release()}
        wordClass={wordClass}
        className={className}
      />
    )
  }
  return (
    <Button
      variant="outline"
      size="sm"
      data-release-selection
      className={className}
      disabled={n === 0 || disabledReason != null}
      aria-label={`Release ${n}`}
      title={
        disabledReason != null
          ? disabledReason
          : n === 0
          ? 'Nothing selected to release'
          : `Release ${n} — every value and local FX the selection holds in the programmer; pads stay lit`
      }
      onClick={() => void release()}
    >
      <Eraser className="size-3.5" />
      <span className={wordClass}>Release</span>
      <span className={`${wordClass} font-mono text-muted-foreground`}>{n}</span>
    </Button>
  )
}

function ReleaseSplitButton({
  split,
  canReleaseHeads,
  headsRefusal,
  onReleaseHeads,
  wordClass,
  className,
}: {
  split: ReleaseSplit
  canReleaseHeads: boolean
  headsRefusal?: string
  onReleaseHeads: () => void
  wordClass: string
  className?: string
}) {
  const [sheetOpen, setSheetOpen] = useState(false)
  const { heads, cells, onReleaseCells, cellsRefusal, cellsTitle, showKeys, sheet } = split
  const onCells = cells > 0
  const canReleaseCells = onCells && cellsRefusal == null
  const cellsWords = plural(cells, 'cell')
  const headsWords = plural(heads, 'head')
  const headsTitle = headsRefusal ?? `Release the whole ${headsWords} — every value and Local FX on them; pads stay lit`

  const arms: ReleaseArm[] = [
    {
      key: 'cells',
      title: cells === 0 ? 'The selected cells' : cells === 1 ? 'This cell' : `These ${cellsWords}`,
      sub: !onCells ? 'Pick cells first' : (cellsRefusal ?? (cells === 1 ? 'Its values leave Local' : 'Their values leave Local')),
      hover: onCells ? (cellsRefusal ?? cellsTitle) : undefined,
      enabled: canReleaseCells,
      keyCap: true,
      run: onReleaseCells,
    },
    {
      key: 'heads',
      title: heads === 1 ? 'The whole head' : `The whole ${headsWords}`,
      sub: headsRefusal ?? 'Every value and Local FX on them',
      hover: headsTitle,
      enabled: canReleaseHeads,
      keyCap: false,
      run: onReleaseHeads,
    },
  ]

  const face = onCells
    ? { label: `Release ${cellsWords}`, title: cellsRefusal ?? cellsTitle, enabled: canReleaseCells, run: onReleaseCells }
    : { label: `Release ${headsWords}`, title: headsTitle, enabled: canReleaseHeads, run: onReleaseHeads }

  const chevron = (
    <button
      type="button"
      aria-label="Release options"
      title="Release the cells, or the whole heads"
      onClick={sheet ? () => setSheetOpen(true) : undefined}
      className="inline-flex items-center border-l px-1.5 text-muted-foreground hover:bg-accent data-[state=open]:bg-accent"
    >
      <ChevronDown className="size-3.5" />
    </button>
  )

  return (
    <div
      data-release-selection
      className={cn('inline-flex h-8 shrink-0 items-stretch overflow-hidden rounded-md border', className)}
    >
      <button
        type="button"
        data-release-face={onCells ? 'cells' : 'heads'}
        aria-label={face.label}
        title={face.title}
        disabled={!face.enabled}
        onClick={face.run}
        className="inline-flex items-center gap-1.5 px-2.5 text-xs font-medium hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
      >
        <Eraser className="size-3.5" />
        <span className={wordClass}>Release</span>
        {showKeys && onCells && <kbd className={KBD_CLASS}>⌫</kbd>}
      </button>
      {sheet ? (
        <>
          {chevron}
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <SheetContent side="bottom" className="gap-0 pb-4">
              <SheetHeader className="pb-2">
                <SheetTitle>Release</SheetTitle>
                <SheetDescription>Out of the programmer; pads stay lit.</SheetDescription>
              </SheetHeader>
              <div className="flex flex-col gap-1 px-2">
                {arms.map((arm) => (
                  <button
                    key={arm.key}
                    type="button"
                    data-release-arm={arm.key}
                    disabled={!arm.enabled}
                    title={arm.hover}
                    onClick={() => {
                      setSheetOpen(false)
                      arm.run()
                    }}
                    className="flex min-h-11 flex-col items-start justify-center rounded-md px-3 text-left hover:bg-accent disabled:opacity-50"
                  >
                    <ArmWords arm={arm} showKeys={false} />
                  </button>
                ))}
              </div>
            </SheetContent>
          </Sheet>
        </>
      ) : (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>{chevron}</DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-[248px]">
            {arms.map((arm) => (
              <DropdownMenuItem
                key={arm.key}
                data-release-arm={arm.key}
                disabled={!arm.enabled}
                title={arm.hover}
                onSelect={arm.run}
                className="items-start gap-2"
              >
                <ArmWords arm={arm} showKeys={showKeys} />
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  )
}

interface ReleaseArm {
  key: 'cells' | 'heads'
  title: string
  sub: string
  hover?: string
  enabled: boolean
  keyCap: boolean
  run: () => void
}

function ArmWords({ arm, showKeys }: { arm: ReleaseArm; showKeys: boolean }): ReactNode {
  return (
    <>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="text-xs font-medium">{arm.title}</span>
        <span className="text-[10.5px] text-muted-foreground">{arm.sub}</span>
      </span>
      {showKeys && arm.keyCap && <kbd className={cn(KBD_CLASS, 'mt-px')}>⌫</kbd>}
    </>
  )
}
