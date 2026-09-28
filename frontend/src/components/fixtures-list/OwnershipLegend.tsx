import { AudioWaveform, Layers } from 'lucide-react'
import { cn } from '@/lib/utils'
import { LegendSwatch, SheetPage } from '@/components/sheet/SheetPage'
import { OWNERSHIP_LABELS, layerCellClass, ownershipCellClass } from './ownership'
import {
  LAYER_LEGEND_GLOSS,
  LAYER_LEGEND_ORDER,
  LEGEND_GLOSS,
  LEGEND_ORDER,
  LEGEND_SHORT,
} from './ownershipLegendModel'
import type { LayerLegendKey } from './ownershipLegendModel'
import type { CellState } from './scopedCellValue'
import type { CellOwnership, CellOwnershipSource } from './useRowOwnership'

/**
 * The key beneath the value grid — **a 22px footer** since the space plan's session 1.
 *
 * Each swatch is styled by the **real** `ownershipCellClass`, not a hand-copied colour, so
 * retuning a ring moves the legend with it. The tints are navigational — clicking a tinted cell
 * jumps the grid's scope to whatever won it — which is exactly why they have to be learnable.
 *
 * It used to wrap onto as many lines as it needed, below a grid that had no height to spare. Now
 * it is one line that never wraps, it carries the row and selection counts at its left, and the
 * long glosses appear only at `@[1100px]` — below that the swatches carry them on their `title`,
 * which is the plan's rule for every sentence this session moved: on hover, not gone.
 *
 * Narrower still, it **drops whole items rather than letting the row clip them**: the header word
 * at `@[420px]`, the trailing layer badge at `@[520px]`. The row is `overflow-hidden` with every
 * item `shrink-0`, so anything that does not fit is otherwise sliced off mid-word with no ellipsis
 * and nothing to say it existed — and the counts, which are leftmost and the only thing here that
 * changes minute to minute, are what has to survive to the last.
 */
export function OwnershipSwatch({ source }: { source: CellOwnershipSource }) {
  return (
    <LegendSwatch
      className={cn(
        source === 'baseline' ? 'bg-muted' : ownershipCellClass(swatchOwnership(source)),
        // The badge the cell wears, so the legend teaches the mark and not only the ring.
        source === 'effect' && 'bg-violet-500/90 text-white',
      )}
    >
      {source === 'effect' && <AudioWaveform className="size-2.5" />}
    </LegendSwatch>
  )
}

export function OwnershipLegend({
  className,
  fixtureCount,
  selectedCount,
}: LegendCountProps & { className?: string }) {
  return (
    <LegendFooter className={className} fixtureCount={fixtureCount} selectedCount={selectedCount}>
      <span className="hidden font-medium @[420px]:inline">Owned by</span>
      {LEGEND_ORDER.map((source) => (
        <span
          key={source}
          title={OWNERSHIP_LABELS[source]}
          className={cn('flex shrink-0 items-center gap-1.5', source === 'baseline' && 'opacity-62')}
        >
          <OwnershipSwatch source={source} />
          <span className="@[1100px]:hidden">{LEGEND_SHORT[source]}</span>
          <span className="hidden @[1100px]:inline">{LEGEND_GLOSS[source]}</span>
        </span>
      ))}
      {/* Hidden below `@[520px]` rather than left to be clipped: the row is `overflow-hidden` and
          every item is `shrink-0`, so the alternative is this badge sliced off mid-word with
          nothing saying it was there. It is the item furthest from the counts and the one whose
          glyph the cells themselves already carry, so it is the right one to drop first. */}
      <span
        className="ml-auto hidden shrink-0 items-center gap-1.5 @[520px]:flex"
        title="came from a Look layer"
      >
        <Layers aria-hidden="true" className="size-3" />
        <span className="@[1100px]:hidden">from a layer</span>
        <span className="hidden @[1100px]:inline">came from a Look layer</span>
      </span>
    </LegendFooter>
  )
}

/** The two counts every legend leads with. Optional so the plain lists can mount one without. */
interface LegendCountProps {
  /** Fixture rows currently in the grid, after the filter. */
  fixtureCount?: number
  /** Visibly selected rows — the same set the selection toolbar gates on. */
  selectedCount?: number
}

/**
 * The shared footer shape: the shell's 22px footer (`SheetPage.Footer`), never wrapping.
 *
 * Both legends use it so they cannot drift into two different footers — they occupy the same slot
 * and swap only on the scope.
 *
 * The footer is the `@container` and every width query is on a descendant — the shape
 * `ProgrammerWorkspace`'s doc comment records the bug for. It measures **the footer's own column**
 * rather than the page, because fitting inside the grid column is the actual question: measuring
 * the page would show the long glosses at 1180 of viewport, where the column is 712 and they clip
 * mid-word. So at 1440 and at 1180 this draws `LEGEND_SHORT`, which is exactly what the `Main` and
 * `TabletLandscape` artboards draw, and the long glosses arrive on a wider desk.
 */
function LegendFooter({
  className,
  fixtureCount,
  selectedCount,
  children,
}: LegendCountProps & { className?: string; children: React.ReactNode }) {
  return (
    <SheetPage.Footer className={className}>
      {fixtureCount != null && (
        <span className="shrink-0 tabular-nums">
          {fixtureCount} fixture{fixtureCount === 1 ? '' : 's'}
          {selectedCount != null && selectedCount > 0 && ` · ${selectedCount} selected`}
        </span>
      )}
      {children}
    </SheetPage.Footer>
  )
}

/** The minimal ownership a swatch needs: uniform, touched, no group and no layer. */
function swatchOwnership(source: CellOwnershipSource): CellOwnership {
  return { source, touched: true, isUniform: true, owners: [] }
}

/**
 * The key beneath the grid while a Look layer is focused.
 *
 * Same construction as its sibling — swatches styled by the real `layerCellClass` — so the two
 * legends cannot drift from the cells they describe.
 */
export function LayerLegend({
  className,
  fixtureCount,
  selectedCount,
}: LegendCountProps & { className?: string }) {
  return (
    <LegendFooter className={className} fixtureCount={fixtureCount} selectedCount={selectedCount}>
      <span className="hidden font-medium @[420px]:inline">In this look</span>
      {LAYER_LEGEND_ORDER.map((key) => (
        <span
          key={key}
          title={LAYER_LEGEND_GLOSS[key]}
          className="flex shrink-0 items-center gap-1.5"
        >
          <LayerSwatch legendKey={key} />
          {/* No short form: these four are already short, and unlike the ownership glosses there
              is no longer sentence to trade against. They stay whole at every width. */}
          {LAYER_LEGEND_GLOSS[key]}
        </span>
      ))}
    </LegendFooter>
  )
}

function swatchState(key: LayerLegendKey): CellState {
  switch (key) {
    case 'set':
      return { value: SWATCH_VALUE, editable: true }
    case 'unset':
      return { editable: true }
    case 'inert':
      return { editable: false, tone: 'inert' }
    case 'untargeted':
      return { editable: false, tone: 'untargeted' }
  }
}

/** Any value at all — `layerCellClass` only asks whether one is present. */
const SWATCH_VALUE = { kind: 'slider', min: 255, max: 255, isUniform: true } as const

function LayerSwatch({ legendKey }: { legendKey: LayerLegendKey }) {
  return (
    <LegendSwatch
      className={layerCellClass(swatchState(legendKey)) || 'bg-muted'}
    />
  )
}

/**
 * The same key, stacked — for the phone, where the 22px footer is not rendered at all and the
 * grid's toolbar offers a key button instead (space plan D8).
 *
 * A separate shape rather than a `vertical` prop on the footer, because the footer's whole
 * construction *is* the horizontal one: one 22px line, `overflow-hidden`, every item `shrink-0`,
 * and three width queries deciding which words survive. None of that means anything in a popover,
 * where there is room for the long gloss and no reason to drop an item. What the two share is the
 * part that must not drift: both style their swatches with the **real** `ownershipCellClass` /
 * `layerCellClass`, through the two components above.
 */
export function OwnershipKey() {
  return (
    <KeyList heading="Owned by">
      {LEGEND_ORDER.map((source) => (
        <li key={source} className="flex items-center gap-2">
          <OwnershipSwatch source={source} />
          <span className={cn(source === 'baseline' && 'opacity-62')}>{LEGEND_GLOSS[source]}</span>
        </li>
      ))}
      <li className="flex items-center gap-2">
        <Layers aria-hidden="true" className="size-3 shrink-0" />
        came from a Look layer
      </li>
    </KeyList>
  )
}

function KeyList({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5 text-[11px] text-muted-foreground">
      <p className="text-[9px] font-bold uppercase tracking-[0.1em]">{heading}</p>
      <ul className="flex flex-col gap-1.5">{children}</ul>
    </div>
  )
}
