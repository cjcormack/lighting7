import { ChevronDown, Unlink2, X } from 'lucide-react'
import type { SubselectMode } from '@/api/selectionApi'
import { LinkBadge } from '@/components/desk/LinkBadge'
import { FOLLOWING_DESK_SELECTION } from '@/components/desk/DeskChip'
import { SubsetMenuSection } from '@/components/desk/SubsetControls'
import { SelectByColourMenu } from '@/components/fixtureColours/SelectByColour'
import type { RigVerbs } from '@/components/fixtures-list/RigVerbs'
import {
  ToolbarMenu,
  ToolbarMenuCheckboxItem,
  ToolbarMenuItem,
  ToolbarMenuLabel,
  ToolbarMenuSeparator,
  useToolbarPhone,
} from '@/components/sheet/ToolbarMenu'
import { relinkToDesk, useDeskFollow } from '@/lib/deskFollow'
import { cn } from '@/lib/utils'
import { unlinkFromDeskNow } from '@/store/selection'

/** The glyph's accessible name while this window keeps its own selection. */
export const OWN_SELECTION = 'This window’s own selection'

export interface HeadsChipProps {
  projectId: number
  /** Anything is selected — a row may stand for no head the list shows (an empty group). */
  selected: boolean
  /** The heads the next press lands on. */
  heads: number
  /** The marquee's cells, 0 for rows alone. */
  cells: number
  /** A subset of the selection over rig order: the desk's while following, the list's own when not. */
  onSubselect: (mode: SubselectMode) => void
  /** *Select by colour*: a colour's fixtures, ⌥ adding them — landed by the same split as [onSubselect]. */
  onPickColour: (keys: readonly string[], add: boolean) => void
  /** The marquee's columns in words (`describeCellScope`), for the hover; null with rows alone. */
  scope: string | null
  /** The fixtures *Select by colour* may pick from — the rig's, not the filtered list's. */
  colourKeys: ReadonlySet<string>
  onDeselect: () => void
  /** Locate, *Set locate from current* and Highlight, which a phone's row has no room for. */
  rigVerbs: RigVerbs | null
}

/**
 * **The heads chip** (programmer-toolbar plan D4, D5) — row C's first cluster, *which heads*: the
 * count, whose selection it is, and a menu to narrow it, follow or leave the desk, or let it go.
 *
 * The count is *6 heads · 6 cells* under a marquee, *4 heads* for rows, *Nothing selected*
 * otherwise; on a phone it counts cells alone. The glyph is the desk link: the link badge while the
 * window follows the desk, a dashed mark while it keeps its own. Unlike the busk band's desk chip
 * the glyph is not a press — the toggle is the menu's *Follow the desk selection* item (D5), the
 * same `unlinkFromDeskNow` / `relinkToDesk` pair.
 *
 * The **×** deselects, and so does the menu's *Deselect*, which on a phone is the only one.
 */
export function HeadsChip({
  projectId,
  selected,
  heads,
  cells,
  onSubselect,
  onPickColour,
  scope,
  colourKeys,
  onDeselect,
  rigVerbs,
}: HeadsChipProps) {
  const following = useDeskFollow()
  // A phone's row (< 600): cells only, no ×, and the rig verbs in the menu.
  const phone = useToolbarPhone()
  const hasSelection = selected || heads > 0 || cells > 0
  const headsLabel = `${heads} head${heads === 1 ? '' : 's'}`
  const cellsLabel = `${cells} cell${cells === 1 ? '' : 's'}`
  // Under 660 of the row the heads go from a marquee's count, as on a phone: the 600–799 arm
  // measured 656px wide with them once FX joined the verbs (programmer-toolbar plan §9).
  const count = !hasSelection ? (
    'Nothing selected'
  ) : cells === 0 ? (
    headsLabel
  ) : phone ? (
    cellsLabel
  ) : (
    <>
      <span className="@max-[660px]:hidden">{headsLabel} · </span>
      {cellsLabel}
    </>
  )
  const whose = following ? 'following the desk' : 'this window’s own'
  const aria = hasSelection
    ? `Selection: ${headsLabel}${cells > 0 ? `, ${cellsLabel}` : ''} — ${whose}. Open to narrow, follow or deselect`
    : `Nothing selected — ${whose}. Open to follow or not`
  // The marquee's columns ride the hover, the one place a phone's row says them.
  const title = scope != null ? `${headsLabel} · ${scope} — edit once, applies to all` : aria

  return (
    <span
      data-heads-chip
      className={cn(
        'inline-flex h-8 shrink-0 items-stretch overflow-hidden rounded-md border',
        !hasSelection && 'border-transparent',
      )}
    >
      <ToolbarMenu
        title="The selection"
        contentClassName="w-[260px]"
        trigger={
          <button
            type="button"
            aria-label={aria}
            title={title}
            className={cn(
              'inline-flex items-center gap-1.5 px-2 text-xs transition-colors hover:bg-accent data-[state=open]:bg-accent',
              hasSelection ? 'font-semibold tabular-nums' : 'text-muted-foreground',
            )}
          >
            {following ? (
              <LinkBadge label={FOLLOWING_DESK_SELECTION} className="h-4 min-w-4 border-0 px-0 text-sky-500" />
            ) : (
              <span
                role="img"
                aria-label={OWN_SELECTION}
                title={OWN_SELECTION}
                className="inline-flex size-4 items-center justify-center rounded border border-dashed border-muted-foreground/60 text-muted-foreground"
              >
                <Unlink2 className="size-[10px]" aria-hidden />
              </span>
            )}
            <span className="whitespace-nowrap">{count}</span>
            <ChevronDown className="size-3.5 text-muted-foreground" />
          </button>
        }
      >
        <SubsetMenuSection onSubselect={onSubselect} disabled={!hasSelection} />
        <SelectByColourMenu projectId={projectId} visibleKeys={colourKeys} onPick={onPickColour} />
        <ToolbarMenuSeparator />
        <ToolbarMenuCheckboxItem
          checked={following}
          onCheckedChange={(on) => (on ? relinkToDesk() : unlinkFromDeskNow())}
        >
          <span className="flex flex-col">
            <span>Follow the desk selection</span>
            <span className="text-[10.5px] text-muted-foreground">Off: this window keeps its own</span>
          </span>
        </ToolbarMenuCheckboxItem>
        {phone && rigVerbs != null && hasSelection && (
          <>
            <ToolbarMenuSeparator />
            <ToolbarMenuLabel>On the rig</ToolbarMenuLabel>
            <ToolbarMenuItem onSelect={rigVerbs.locate}>
              {rigVerbs.allLocated ? 'Release locate' : 'Locate'}
            </ToolbarMenuItem>
            <ToolbarMenuItem
              disabled={!rigVerbs.canStoreLocate || rigVerbs.storedLocate.busy}
              onSelect={() => rigVerbs.storedLocate.storeFromCurrent(rigVerbs.locateTargets)}
            >
              Set locate from current
            </ToolbarMenuItem>
            {/* A menu row cannot be held, so on a phone Highlight latches: a second press lets go. */}
            <ToolbarMenuItem
              onSelect={rigVerbs.highlight.isActive ? rigVerbs.highlight.release : rigVerbs.highlight.press}
            >
              {rigVerbs.highlight.isActive ? 'Stop highlighting' : 'Highlight'}
            </ToolbarMenuItem>
          </>
        )}
        {hasSelection && (
          <>
            <ToolbarMenuSeparator />
            <ToolbarMenuItem onSelect={onDeselect}>
              <X className="size-4" />
              Deselect
            </ToolbarMenuItem>
          </>
        )}
      </ToolbarMenu>
      {hasSelection && !phone && (
        <button
          type="button"
          aria-label="Deselect"
          title="Deselect"
          onClick={onDeselect}
          className="inline-flex w-7 items-center justify-center border-l text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        >
          <X className="size-3.5" />
        </button>
      )}
    </span>
  )
}
