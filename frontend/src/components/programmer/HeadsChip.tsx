import { useCallback } from 'react'
import { ChevronDown, Unlink2, X } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import type { CueTarget } from '@/api/cuesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { SubselectMode } from '@/api/selectionApi'
import type { LocateTarget } from '@/store/locate'
import { relinkToDesk, useDeskFollow } from '@/lib/deskFollow'
import { unlinkFromDeskNow } from '@/store/selection'
import { useStoredLocateActions } from '@/hooks/useStoredLocateActions'
import { LinkBadge } from '@/components/desk/LinkBadge'
import { FOLLOWING_DESK_SELECTION } from '@/components/desk/DeskChip'
import { SubselectMenuItems } from '@/components/selection/Subselect'
import { SelectByColourSub } from '@/components/fixtureColours/SelectByColour'
import { useLocateSelection } from '@/components/fixtures-list/SelectionToolbar'
import { useHighlight } from '@/components/fixtures-list/useHighlight'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import { useRowCBand } from './rowCBand'
import { plural } from '@/lib/plural'

const MENU_HEADING_CLASS = 'px-2 pb-1 pt-1.5 text-[9.5px] font-bold uppercase tracking-[0.1em] text-muted-foreground'

export interface HeadsChipProps {
  projectId: number
  /** The heads a press lands on. */
  heads: number
  /** The marquee's cells, 0 for rows alone. */
  cells: number
  /** The selection as targets: what *Select by colour*'s ⌥ arm adds to. */
  targets: readonly CueTarget[]
  /** The subsets over rig order; null where the list has none to offer. */
  onSubselect: ((mode: SubselectMode) => void) | null
  /** Replace the selection and its mask — the desk's while following, the list's own when not. */
  onSelectTargets: (targets: CueTarget[], families: readonly AttributeFamily[] | null) => void
  onDeselect: () => void
  /** What Locate, *Set locate from current* and Highlight act on, in the phone arm's menu. */
  locateTargets: readonly LocateTarget[]
  writeTargets: readonly WriteTarget[]
}

/**
 * The left end of the programmer's row C (plan D4, D5): how many heads the next gesture reaches,
 * whose selection it is, and a menu of ways to change it.
 *
 * The glyph says whether the window follows the desk — the link badge — or keeps its own — the
 * dashed mark — and only says it: the toggle is the menu's *Follow the desk selection*, so a press
 * on the count can never unlink a window by accident. This is the programmer's row only; the busk
 * band's desk chip keeps its press-to-toggle.
 *
 * On a phone the count is the cells alone when there are any, the × goes (Deselect is in the menu),
 * and Locate, *Set locate from current* and Highlight join the menu, since the row has no room for
 * them.
 */
export function HeadsChip({
  projectId,
  heads,
  cells,
  targets,
  onSubselect,
  onSelectTargets,
  onDeselect,
  locateTargets,
  writeTargets,
}: HeadsChipProps) {
  const following = useDeskFollow()
  const band = useRowCBand()
  const phone = band === 'phone'
  // A selected group showing no member still counts: the verbs act on it, so the chip offers × too.
  const hasSelection = heads > 0 || cells > 0 || locateTargets.length > 0

  const count = !hasSelection
    ? 'Nothing selected'
    : cells > 0
      ? phone
        ? plural(cells, 'cell')
        : `${plural(heads, 'head')} · ${plural(cells, 'cell')}`
      : plural(heads, 'head')
  const whose = following ? 'following the desk' : 'this window’s own'
  const label = hasSelection
    ? `Selection: ${plural(heads, 'head')}${cells > 0 ? `, ${plural(cells, 'cell')}` : ''} — ${whose}. Open to narrow, follow or deselect`
    : `Nothing selected — ${whose}. Open to select`

  return (
    <div
      data-heads-chip
      className="inline-flex h-8 shrink-0 items-stretch overflow-hidden rounded-md border bg-background"
    >
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label={label}
            title={label}
            className="inline-flex items-center gap-1.5 px-2 text-xs hover:bg-accent data-[state=open]:bg-accent"
          >
            {following ? (
              <LinkBadge label={FOLLOWING_DESK_SELECTION} className="h-4 min-w-4 border-0 px-0 text-primary" />
            ) : (
              <span
                role="img"
                aria-label="This window’s own selection"
                data-heads-own
                className="inline-flex size-4 items-center justify-center rounded border border-dashed border-muted-foreground/50 text-muted-foreground"
              >
                <Unlink2 className="size-[10px]" />
              </span>
            )}
            <span
              data-heads-count
              className={cn('whitespace-nowrap tabular-nums', hasSelection ? 'font-semibold' : 'text-muted-foreground')}
            >
              {count}
            </span>
            <ChevronDown className="size-3.5 text-muted-foreground" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-[292px]">
          {onSubselect != null && (
            <>
              <DropdownMenuLabel className={MENU_HEADING_CLASS}>In rig order</DropdownMenuLabel>
              <SubselectMenuItems onSubselect={onSubselect} layout="grid" />
              <div className="h-1" />
            </>
          )}
          <SelectByColourSub projectId={projectId} current={targets} onSelect={onSelectTargets} />
          <DropdownMenuSeparator />
          <DropdownMenuCheckboxItem
            checked={following}
            onCheckedChange={(on) => (on ? relinkToDesk() : unlinkFromDeskNow())}
            onSelect={(e) => e.preventDefault()}
            className="items-start"
          >
            <span className="flex flex-col">
              <span className="text-xs">Follow the desk selection</span>
              <span className="text-[10.5px] text-muted-foreground">Off: this window keeps its own</span>
            </span>
          </DropdownMenuCheckboxItem>
          {phone && hasSelection && <PhoneRigItems locateTargets={locateTargets} writeTargets={writeTargets} />}
          {hasSelection && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={onDeselect} className="gap-2 text-xs">
                <X className="size-3.5 text-muted-foreground" />
                Deselect
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {hasSelection && !phone && (
        <button
          type="button"
          aria-label="Deselect"
          title="Deselect"
          onClick={onDeselect}
          className="inline-flex w-7 items-center justify-center border-l text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <X className="size-3.5" />
        </button>
      )}
    </div>
  )
}

/**
 * Row C's right end, folded into the menu on a phone. Highlight is a hold here as it is on its
 * button: the item keeps the menu open, lifts on pointer-down and restores on release, and the
 * hook restores on unmount should the menu close mid-hold.
 */
function PhoneRigItems({
  locateTargets,
  writeTargets,
}: {
  locateTargets: readonly LocateTarget[]
  writeTargets: readonly WriteTarget[]
}) {
  const { allLocated, locateSelection } = useLocateSelection(locateTargets)
  const storedLocate = useStoredLocateActions()
  const getTargets = useCallback(() => [...writeTargets], [writeTargets])
  const highlight = useHighlight(getTargets)
  return (
    <>
      <DropdownMenuSeparator />
      <DropdownMenuLabel className={MENU_HEADING_CLASS}>On the rig</DropdownMenuLabel>
      <DropdownMenuItem onSelect={locateSelection} className="text-xs">
        {allLocated ? 'Release locate' : 'Locate'}
      </DropdownMenuItem>
      <DropdownMenuItem
        disabled={storedLocate.busy || storedLocate.projectId == null}
        onSelect={() => storedLocate.storeFromCurrent(locateTargets)}
        className="text-xs"
      >
        Set locate from current
      </DropdownMenuItem>
      <DropdownMenuItem
        title="Hold: full intensity on the selection, restored on release"
        onSelect={(e) => e.preventDefault()}
        onPointerDown={highlight.press}
        onPointerUp={highlight.release}
        onPointerCancel={highlight.release}
        onPointerLeave={highlight.release}
        className={cn('text-xs', highlight.isActive && 'bg-accent')}
      >
        Highlight
      </DropdownMenuItem>
    </>
  )
}
