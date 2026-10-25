import { useCallback } from 'react'
import { ChevronDown, Crosshair, Flashlight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useStoredLocateActions } from '@/hooks/useStoredLocateActions'
import { cn } from '@/lib/utils'
import { useLocateStateQuery, useToggleLocateMutation, type LocateTarget } from '../../store/locate'
import { useHighlight } from './useHighlight'
import type { WriteTarget } from './rowModel'

/**
 * **Where the selection is on the rig** — Locate, where Locate points (venue-visit plan D6) and
 * Highlight — as one hook and the two controls drawn from it. The patch list's `SelectionToolbar`
 * draws them, and so does the programmer's row C (programmer-toolbar plan D3), which on a phone
 * moves them into the heads chip's menu: one hook, so the buttons and the menu's items cannot act
 * on different heads.
 */
export function useRigVerbs(locateTargets: readonly LocateTarget[], targets: readonly WriteTarget[]) {
  const { data: locateState } = useLocateStateQuery()
  const [toggleLocate] = useToggleLocateMutation()
  const storedLocate = useStoredLocateActions()
  const getTargets = useCallback(() => [...targets], [targets])
  const highlight = useHighlight(getTargets)

  const isActive = (target: LocateTarget) =>
    locateState?.targets.some((t) => t.type === target.type && t.key === target.key) ?? false
  const allLocated = locateTargets.length > 0 && locateTargets.every(isActive)

  // All located → release everything; otherwise light up the ones not yet on.
  const locate = () => {
    const toToggle = allLocated ? locateTargets : locateTargets.filter((t) => !isActive(t))
    for (const target of toToggle) {
      toggleLocate(target)
        .unwrap()
        .catch((err) => console.error(`Locate toggle failed for ${target.type} '${target.key}'`, err))
    }
  }

  return {
    locateTargets,
    allLocated,
    locate,
    storedLocate,
    highlight,
    /** *Set locate from current* and *Clear locate* have somewhere to write. */
    canStoreLocate: locateTargets.length > 0 && storedLocate.projectId != null,
  }
}

export type RigVerbs = ReturnType<typeof useRigVerbs>

/** Locate, and its chevron: where Locate points the selection. */
export function LocateControl({
  verbs,
  wordClass,
  className,
  chevronClassName = className,
}: {
  verbs: RigVerbs
  wordClass: string
  className?: string
  chevronClassName?: string
}) {
  const { allLocated, locate, storedLocate, locateTargets, canStoreLocate } = verbs
  return (
    <>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant={allLocated ? 'default' : 'outline'}
            size="sm"
            onClick={locate}
            aria-label="Locate"
            className={cn(className, allLocated && 'bg-sky-500 text-white hover:bg-sky-600')}
          >
            <Crosshair className="size-3.5" />
            <span className={wordClass}>Locate</span>
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          {allLocated ? 'Release locate on the selection' : 'Locate the selection: white beam at centre'}
        </TooltipContent>
      </Tooltip>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className={cn(chevronClassName, '-ml-1 px-1.5')}
            aria-label="Locate options"
            title="Where Locate points the selection"
            disabled={!canStoreLocate}
          >
            <ChevronDown className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            disabled={storedLocate.busy}
            onSelect={() => storedLocate.storeFromCurrent(locateTargets)}
          >
            Set locate from current
          </DropdownMenuItem>
          <DropdownMenuItem
            disabled={storedLocate.busy}
            onSelect={() => storedLocate.clearLocate(locateTargets)}
          >
            Clear locate
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  )
}

/** Highlight, held: full intensity on the selection, restored on release. */
export function HighlightButton({ verbs, wordClass, className }: { verbs: RigVerbs; wordClass: string; className?: string }) {
  const { highlight } = verbs
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant={highlight.isActive ? 'default' : 'outline'}
          size="sm"
          aria-label="Highlight"
          className={className}
          onPointerDown={highlight.press}
          onPointerUp={highlight.release}
          onPointerCancel={highlight.release}
          onPointerLeave={highlight.release}
        >
          <Flashlight className="size-3.5" />
          <span className={wordClass}>Highlight</span>
        </Button>
      </TooltipTrigger>
      <TooltipContent>Hold: full intensity on the selection, restored on release</TooltipContent>
    </Tooltip>
  )
}
