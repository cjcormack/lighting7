import { useMemo } from 'react'
import { Filter } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { plural } from '@/lib/plural'
import { describeCellScope, type CellRef } from '@/components/sheet/cellSelectionModel'
import { cellFamilies, columnLabel, type ColumnKey } from '@/components/fixtures-list/columns'
import { SelectionBar as SheetSelectionBar } from '@/components/sheet/SelectionBar'
import { useContainerBand } from '@/hooks/useContainerBand'
import { useDeskFollow } from '@/lib/deskFollow'
import { usePressFamilies } from '@/store/selection'
import type { LocateTarget } from '@/store/locate'
import { HeadsChip, type HeadsChipProps } from './HeadsChip'
import { SelectionMaskPill, maskWords } from './SelectionMaskPill'
import { ROW_C_BANDS, ROW_C_FLOORS, RowCBandContext, type RowCBand } from './rowCBand'

/** What the heads chip acts through, which the list owns. */
export type HeadsControls = Pick<
  HeadsChipProps,
  'targets' | 'onSubselect' | 'onSelectTargets' | 'onDeselect' | 'locateTargets' | 'writeTargets'
>

/**
 * Row C on the programmer — the heads chip, the mask, and the selection's verbs in clusters (plan
 * D3) — and, with no `projectId`, row C on the two plain lists, the kit's bar with its counts. One
 * component for both so the counts and the hints cannot be computed two ways.
 *
 * The bar itself — the 40px line, the hold-its-place rule on a short viewport — is
 * `sheet/SelectionBar.tsx`, which every sheet shares. What is the programmer's is what this file
 * draws into the kit's `lead` slot, and the rung it measures the row at (`rowCBand.ts`), which the
 * chip and the verbs read.
 *
 * **The mask is the one the next press carries**, not only the one the marquee named:
 * `usePressFamilies` — the desk's while following, the marquee's own when unlinked. The two are the
 * same whenever this tab made the marquee; they differ when another window moved the mask under
 * it, which the bridge lands here as a *row* selection — and then the pill is the only thing on this
 * bar saying that a Colour press will be refused.
 *
 * **While this window follows the desk the pill is the mask's picker** (apply-model plan D3,
 * `SelectionMaskPill`), drawn over every selection — `All` when unmasked — and a change goes
 * through the list's desk bridge as [onRemask]. An **unlinked** programmer keeps a badge: its own
 * selection is the list's, whose only mask is the marquee's columns, so there is no fact for a
 * picker to write.
 */
export function SelectionBar({
  projectId,
  selection,
  cells,
  cellEntryKey,
  cellClearKey,
  paletteTargets,
  marqueeDragging,
  onRemask = null,
  heads,
}: {
  /** The programmer's project. Absent — the plain lists — draws the kit's own counts. */
  projectId?: number
  selection: React.ReactNode | null
  cells: readonly CellRef<ColumnKey>[]
  cellEntryKey: boolean
  cellClearKey: boolean
  paletteTargets: readonly LocateTarget[]
  marqueeDragging: boolean
  /** The desk bridge's mask write, which makes the pill its picker; null when the list does not bridge. */
  onRemask?: ((families: readonly AttributeFamily[] | null) => void) | null
  /** The programmer's heads chip controls; absent on the plain lists. */
  heads?: HeadsControls
}) {
  const askedFamilies = useMemo(() => (cells.length > 0 ? cellFamilies(cells) : null), [cells])

  // The head count is `paletteTargets` — the heads a press actually lands on, which is the
  // cells' heads under a marquee and the selected rows' otherwise — and deliberately not the
  // footer's `selectedCount`, which counts visible *rows* with a group as one. *Heads*, not
  // fixtures: a selected cell is its own target, so three zones of one Shehds are three heads.
  const fixtures = plural(paletteTargets.length, 'head')

  if (projectId == null || heads == null) {
    return (
      <SheetSelectionBar
        rowLabel={paletteTargets.length > 0 ? fixtures : null}
        cellLabel={cells.length > 0 ? plural(cells.length, 'cell') : null}
        cellTitle={`${fixtures} · ${describeCellScope(cells, columnLabel)} — edit once, applies to all`}
        family={askedFamilies != null && askedFamilies.length > 0 ? formatFamilyList(askedFamilies, ' · ') : null}
        hints={{ entry: cellEntryKey, clear: cellClearKey }}
        verbs={selection}
        marqueeDragging={marqueeDragging}
      />
    )
  }

  return (
    <ProgrammerRowC
      projectId={projectId}
      selection={selection}
      cells={cells}
      askedFamilies={askedFamilies}
      paletteTargets={paletteTargets}
      marqueeDragging={marqueeDragging}
      onRemask={onRemask}
      heads={heads}
    />
  )
}

function ProgrammerRowC({
  projectId,
  selection,
  cells,
  askedFamilies,
  paletteTargets,
  marqueeDragging,
  onRemask,
  heads,
}: {
  projectId: number
  selection: React.ReactNode | null
  cells: readonly CellRef<ColumnKey>[]
  askedFamilies: AttributeFamily[] | null
  paletteTargets: readonly LocateTarget[]
  marqueeDragging: boolean
  onRemask: ((families: readonly AttributeFamily[] | null) => void) | null
  heads: HeadsControls
}) {
  const [measure, bandIndex] = useContainerBand(ROW_C_FLOORS)
  const band = ROW_C_BANDS[bandIndex]
  const following = useDeskFollow()
  const pressFamilies = usePressFamilies(askedFamilies)
  const hasSelection = selection != null
  const scope = `${describeCellScope(cells, columnLabel)} — edit once, applies to all`

  const lead = (
    <>
      <HeadsChip projectId={projectId} heads={paletteTargets.length} cells={cells.length} {...heads} />
      {hasSelection && (
        <span className="inline-flex shrink-0" title={cells.length > 0 ? scope : undefined}>
          {following && onRemask != null ? (
            <SelectionMaskPill
              families={pressFamilies}
              onChange={onRemask}
              tone="bar"
              display={maskDisplay(band)}
            />
          ) : (
            pressFamilies != null &&
            pressFamilies.length > 0 && <MaskBadge families={pressFamilies} band={band} />
          )}
        </span>
      )}
      {!hasSelection && (
        <span className="min-w-0 truncate text-xs text-muted-foreground">
          Drag across cells, or narrow from the heads menu. A Look needs no selection.
        </span>
      )}
    </>
  )

  return (
    <RowCBandContext.Provider value={band}>
      <div ref={measure}>
        <SheetSelectionBar
          lead={lead}
          clusters
          rowLabel={null}
          // Drawn by the chip, not the kit; given so a marquee keeps the bar in the flow as it does
          // on every sheet.
          cellLabel={cells.length > 0 ? `${cells.length}` : null}
          family={null}
          hints={{ entry: false, clear: false }}
          verbs={selection}
          marqueeDragging={marqueeDragging}
        />
      </div>
    </RowCBandContext.Provider>
  )
}

/** The mask's face by the row's rung: its words, abbreviated, or the glyph alone (plan D12). */
function maskDisplay(band: RowCBand): 'full' | 'short' | 'icon' {
  return band === 'phone' ? 'icon' : band === 'tab' ? 'short' : 'full'
}

/** An unlinked window's mask: the marquee's columns, said and not edited. */
function MaskBadge({ families, band }: { families: readonly AttributeFamily[]; band: RowCBand }) {
  const display = maskDisplay(band)
  const title = `Attributes: ${formatFamilyList(families, ', ')} — what the next press carries`
  return (
    <Badge
      variant="outline"
      title={title}
      aria-label={title}
      className="shrink-0 gap-1 whitespace-nowrap px-1.5 py-0 text-[10px]"
    >
      {display === 'icon' ? <Filter className="size-3" /> : maskWords(families, display)}
    </Badge>
  )
}

