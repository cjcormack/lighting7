import { useMemo } from 'react'
import { cn } from '@/lib/utils'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { describeCellScope, type CellRef } from '@/components/sheet/cellSelectionModel'
import { cellFamilies, columnLabel, type ColumnKey } from '@/components/fixtures-list/columns'
import { SelectionBar as SheetSelectionBar } from '@/components/sheet/SelectionBar'
import { ToolbarPhoneContext } from '@/components/sheet/ToolbarMenu'
import { useContainerBand } from '@/hooks/useContainerBand'
import { usePressFamilies } from '@/store/selection'
import { HeadsChip, type HeadsChipProps } from './HeadsChip'
import { MaskRowFace, SelectionMaskPill } from './SelectionMaskPill'
import { ROW_C_PHONE_FLOORS } from './rowCFolds'
import type { LocateTarget } from '@/store/locate'

/** What the list container hands the heads chip; the bar adds the project. */
export type HeadsChipParts = Omit<HeadsChipProps, 'projectId'>

/**
 * Row C on the programmer, and — with no `projectId` — row C on the two plain lists. One component
 * for both so the counts and the family reading cannot be computed two ways.
 *
 * The bar itself — the 40px line, the hold-its-place rule on a short viewport — is
 * `sheet/SelectionBar.tsx`'s (CLAUDE.md §Sheet kit), and the plain lists draw it exactly as they
 * always have: the glyph, the counts, the family badge, the ⏎/⌫ hints and their toolbar.
 *
 * **The programmer's row is three clusters** (programmer-toolbar plan D3), left to right: *which
 * heads* — the heads chip (`HeadsChip`, which counts them and narrows them) and the mask; *what to
 * do to them* — Set ⏎ · Fan · FX · Release ⌫ ▾ (the container's [actions]); and *where they are on the
 * rig* — Locate ▾ · Highlight ([selection]), at the right edge. The kit's slots carry them:
 * `leading` for the first, `strip` for the second, the verbs for the third. Its width ladder is
 * `rowCFolds.ts`; the phone arm, which turns its menus into bottom sheets, is measured here and
 * handed down through `ToolbarPhoneContext`.
 *
 * **The mask is what the next press carries**, not only what the marquee named: `usePressFamilies`
 * — the desk's while following, the marquee's own when unlinked. While the window follows the desk
 * it is the mask's picker (`SelectionMaskPill`, apply-model plan D3), its writes going through the
 * list's desk bridge as [onRemask]; unlinked it is read-only, since the list's own selection has no
 * mask but the marquee's columns. The plain lists keep the marquee's own reading: they never bridge.
 */
export function SelectionBar({
  projectId,
  selection,
  actions = null,
  heads = null,
  cells,
  cellEntryKey,
  cellClearKey,
  paletteTargets,
  marqueeDragging,
  onRemask = null,
}: {
  /** The programmer's project. Absent — the plain lists — draws the kit's own left end. */
  projectId?: number
  selection: React.ReactNode | null
  /** The programmer's *what to do to them*. */
  actions?: React.ReactNode | null
  /** The programmer's heads chip. */
  heads?: HeadsChipParts | null
  cells: readonly CellRef<ColumnKey>[]
  cellEntryKey: boolean
  cellClearKey: boolean
  paletteTargets: readonly LocateTarget[]
  marqueeDragging: boolean
  /** The desk bridge's mask write, which makes the mask its picker; null when the list does not bridge. */
  onRemask?: ((families: readonly AttributeFamily[] | null) => void) | null
}) {
  const askedFamilies = useMemo(() => (cells.length > 0 ? cellFamilies(cells) : null), [cells])
  const pressFamilies = usePressFamilies(askedFamilies)
  const [measure, band] = useContainerBand(ROW_C_PHONE_FLOORS)

  if (projectId == null || heads == null) {
    // The head count is `paletteTargets` — the heads a press lands on, which is the cells' heads
    // under a marquee and the selected rows' otherwise — and not the footer's count of visible
    // *rows*. *Heads*, not fixtures: a selected cell is its own target, so three zones of one
    // Shehds are three heads.
    const fixtures = `${paletteTargets.length} head${paletteTargets.length === 1 ? '' : 's'}`
    return (
      <SheetSelectionBar
        rowLabel={paletteTargets.length > 0 ? fixtures : null}
        cellLabel={cells.length > 0 ? `${cells.length} cell${cells.length === 1 ? '' : 's'}` : null}
        // The sentence becomes the hover. `describeCellScope` is the same string the drag chip
        // shows, so the two agree by construction; the head count rides it, since on a phone the
        // hover is the only place that count is said.
        cellTitle={`${fixtures} · ${describeCellScope(cells, columnLabel)} — edit once, applies to all`}
        family={askedFamilies != null && askedFamilies.length > 0 ? formatFamilyList(askedFamilies, ' · ') : null}
        hints={{ entry: cellEntryKey, clear: cellClearKey }}
        verbs={selection}
        marqueeDragging={marqueeDragging}
      />
    )
  }

  const hasSelection = selection != null
  const masked = pressFamilies != null && pressFamilies.length > 0 ? pressFamilies : null
  const mask = !hasSelection ? null : onRemask != null ? (
    <SelectionMaskPill families={pressFamilies} onChange={onRemask} tone="row" />
  ) : (
    <span
      role="img"
      aria-label={`Mask: ${masked != null ? formatFamilyList(masked, ', ') : 'every attribute'} — this window’s own, the marquee’s columns`}
      className="inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md border px-2 text-xs font-medium text-muted-foreground"
    >
      <MaskRowFace families={masked} readOnly />
    </span>
  )

  return (
    <ToolbarPhoneContext.Provider value={band === 1}>
      <div ref={measure}>
        <SheetSelectionBar
          rowLabel={null}
          cellLabel={null}
          family={null}
          hints={{ entry: false, clear: false }}
          leading={
            <>
              <HeadsChip projectId={projectId} {...heads} />
              {mask}
              {!hasSelection && (
                <span className="hidden truncate text-xs text-muted-foreground @[800px]:inline">
                  Drag across cells, or narrow from the heads menu.
                </span>
              )}
            </>
          }
          strip={
            actions != null ? (
              <>
                {band !== 1 && <span className="mx-0.5 h-5 w-px shrink-0 bg-border" aria-hidden />}
                <div className={cn('flex shrink-0 items-center', band === 1 ? 'gap-1 [&_button]:px-2!' : 'gap-1.5')}>
                  {actions}
                </div>
              </>
            ) : undefined
          }
          foldForStrip={false}
          verbs={selection}
          marqueeDragging={marqueeDragging}
        />
      </div>
    </ToolbarPhoneContext.Provider>
  )
}
