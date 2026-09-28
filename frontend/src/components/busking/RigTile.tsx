import { useCallback, useEffect, useRef, useState } from 'react'
import { useDraggable, useDroppable } from '@dnd-kit/core'
import { Layers, LayoutGrid, MoreHorizontal, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { FixturePatch } from '@/api/patchApi'
import type { BuskRigCellMode, BuskRigElement, BuskRigTile } from '@/api/buskRigApi'
import type { Fixture, FixtureTypeInfo } from '@/store/fixtures'
import { FixtureAppearanceSource, type FixtureAppearance } from '@/components/fixtures/fixtureAppearance'
import { useLongPress } from '@/hooks/useLongPress'
import { rigTileId, tileOwnName, type RenderTile, type RigTileAddress } from '@/lib/buskRig'
import { LiveAppearanceReporter } from '@/lib/liveAppearance'
import type { EffectPresence } from './buskingTypes'
import { RIG_DROP_DEPTH, type RigDropData, type RigTileDragData } from './buskDnd'
import { NameField } from './NameField'
import { useRigEdit } from './RigEditProvider'

/**
 * One rig tile: a group, a fixture, one cell, or a run of cells.
 *
 * **A press is a plain toggle**, the target band's rule — the pad-shaped selection vocabulary
 * (`padPresenceClass`'s three rungs) so a lit tile means the same thing a lit pad does. A
 * multi-head fixture's tile draws its cells as **pips**, and a pip is a target of its own
 * (busk-further plan D11, session 7): a tap toggles `{type: 'fixture', key: element.key}` through
 * the one `toggleTarget`, a drag across the pips is a **run** — each pip crossed toggled once — and
 * a tap on the tile is still the whole fixture. The tile reads `some` with its `n of N` when part
 * of its cells is selected, wherever that selection was made, and `all` when every cell is.
 *
 * **The pips are a sibling of the tile's button, not children of it.** A button cannot hold
 * buttons, and a pip has to be one — a tab stop, a checkbox role, a label naming the cell — so the
 * live overlay (`TileLive`: the bar and the pips) is mounted beside the button over the same box,
 * the bar `pointer-events-none` as before and the pip row taking the pointer. The gesture's rules are
 * the marquee's (`useCellMarquee`): **a mouse runs at once** — the pip under the press toggles on
 * `pointerdown`, the row takes pointer capture and every pip the pointer crosses toggles once — and
 * **a touch or pen runs only after a hold** (`useLongPress`, 500ms), because on a touchscreen a
 * finger pans and a distance-armed run would select cells on every scroll; a tap on a pip is the
 * browser's own `click`. A pip rests at the board's **8px** and, while a touch run is live, the pip
 * under the finger grows to **44px** (the design's *Cells* board: "18×8 at rest, 44px under a
 * finger"), and a non-passive `touchmove` guard
 * keeps the browser from panning under it — `touch-action` cannot say "only once held", being read
 * at touch start. In *Edit layout* the row is inert, so a drag can start from the tile's whole face.
 *
 * **The live bar is the stage's own colour**, through `FixtureAppearanceSource` — the same
 * dispatch the 2D plot, the DOM marker and the mini-stage read (`docs/stage-vis-engineering.md`
 * §Fixture appearance), so a tile never disagrees with the stage about what a head is doing. One
 * leaf per fixture tile, mounted here rather than in the band, because the render prop's leaf
 * carries a fixed hook set per colour source and the band would otherwise mount one per head
 * whether or not the head is on a visible row. A group has no channels of its own and draws no bar.
 *
 * **In *Edit layout* every tile in the document carries a menu** (`Rig.dc.html`): the cell modes
 * (D3) on a multi-head fixture, *Rename tile…* on every kind, *Remove from rig* last. A rename is
 * the tile's **label** — the name it wears on the band in place of its record's own, the column the
 * rig has carried since session 3 and the UI the reconciliation audit found missing (busk-further
 * plan §11). It is edited in place: the menu item swaps the tile's face for a `NameField` seeded
 * with **the name the label stands in for** (`tileOwnName`, beside `expandTile` because it follows
 * how that function applies a label): the name shown for a group, a whole fixture or a single-cell
 * tile, where a label replaces it; the fixture's own name for a tile drawn per cell or in halves,
 * where the label is the base the cell names compose on — so a per-cell tile's field says
 * `Bar L` under tiles reading `Bar L · Cell 1…4`, and every drawn sibling takes the one label,
 * being one stored tile. Saving that name back clears the label rather than storing a copy of it,
 * so there is a way back to the record's name without a second verb.
 *
 * **The two edit controls sit inside the tile's top-right corner** (2026-09-21), the cross and the
 * menu side by side, and the tile pads its name away from them. They hung off the corners at −7px,
 * which failed twice at once on a real row: a `SCROLL` row's body clips its overflow, so the top
 * 7px of both were cut off, and at the row's 8px tile gap the menu of one tile and the cross of
 * the next overlapped by 6px. Inside the border nothing clips and nothing collides.
 *
 * **A selected pip is the accent, solid** (`Cells.dc.html`: `pip.on { background: var(--pri) }`),
 * where it was the stage's colour under a 1px ring — which on a dark bar was a dark pip with a
 * hairline, and read as nothing at a glance. The live colour is the unselected pip's; a selected
 * one says *selected* first.
 *
 * **The live bar sits inside the border and is not drawn for a dark head.** It was `inset-x-0
 * bottom-0` at a 15% floor, so a spot at zero intensity wore a faint blue line *over* its bottom
 * border — the "weird bit of styling" reported on the desk. Inside the border it cannot repaint
 * the frame, and a head at zero draws no bar rather than a dim one; the pips keep their floor,
 * since a pip at zero would otherwise vanish and take its press target with it.
 */

/** What a fixture tile's appearance leaf needs, looked up once by the band and threaded down. */
export interface TileLookup {
  patchByKey: ReadonlyMap<string, FixturePatch>
  fixtureByKey: ReadonlyMap<string, Fixture>
  typeByKey: ReadonlyMap<string, FixtureTypeInfo>
}

export interface RigTileProps {
  tile: RenderTile
  /** Its address in the built document, or null for a show-all fallback tile (never in the document). */
  at: RigTileAddress | null
  /** The stored tile this render tile came from, for the cell-mode menu. Null on a fallback tile. */
  stored: BuskRigTile | null
  presence: EffectPresence
  /**
   * Is the tile's whole fixture selected as itself. `all` alone cannot say: every cell selected
   * reads `all` too, and the two are different selections with different presses, so the badge keeps
   * `4 of 4` for the cells and `4` for the parent.
   */
  wholeSelected: boolean
  /** Every cell the selection covers — selected itself, or under a selected parent — for the pips and the `4 of 8` count. */
  selectedCells: ReadonlySet<string>
  editing: boolean
  /** The phone board: 48px tiles. */
  compact: boolean
  lookup: TileLookup
  onPress: () => void
  /** A pip's toggle: the cell as `{type: 'fixture', key: element.key}`, through the one `toggleTarget`. */
  onPressCell: (element: BuskRigElement) => void
  onRemove: () => void
  onSetMode: (mode: BuskRigCellMode, split?: number) => void
  /** *Rename tile…*: the stored tile's `label`, null to clear it back to the record's own name. */
  onRename: (label: string | null) => void
}

const TILE_CLASS =
  'relative flex items-center gap-2 whitespace-nowrap rounded-lg border px-3.5 select-none touch-manipulation text-sm transition-all'

/** One of the two edit controls in the tile's corner: an 18px round button, inside the border. */
const TILE_CONTROL_CLASS =
  'grid size-[18px] place-items-center rounded-full border bg-muted text-muted-foreground hover:bg-accent hover:text-foreground'

function presenceClass(presence: EffectPresence) {
  return cn(
    presence === 'none' && 'border-border bg-card hover:bg-accent/50',
    presence === 'some' && 'border-primary/40 bg-primary/10 hover:bg-primary/15',
    presence === 'all' && 'border-primary bg-primary/20 ring-1 ring-primary/50 hover:bg-primary/25',
  )
}

/** The cells a tile draws: every cell of a `PIPS` fixture, a run's cells, the one cell of a cell tile. */
function cellsOf(tile: RenderTile): BuskRigElement[] {
  if (tile.kind === 'fixture') return tile.pips ? tile.cells : []
  if (tile.kind === 'run') return tile.cells
  if (tile.kind === 'cell') return [tile.element]
  return []
}

export function RigTile({
  tile,
  at,
  stored,
  presence,
  wholeSelected,
  selectedCells,
  editing,
  compact,
  lookup,
  onPress,
  onPressCell,
  onRemove,
  onSetMode,
  onRename,
}: RigTileProps) {
  const { source, foreign } = useRigEdit()
  const [renaming, setRenaming] = useState(false)
  const draggingRow = source?.type === 'rig-row'
  // Per **render** tile, never per stored tile: see `rigTileId`.
  const id = at == null ? `rtile-fallback:${tile.key}` : rigTileId(at, tile.key)
  const inDocument = at != null

  const { attributes, listeners, setNodeRef: setDragRef, isDragging } = useDraggable({
    id,
    data: { type: 'rig-tile', at: at ?? { row: -1, tile: -1 }, name: tile.name } satisfies RigTileDragData,
    disabled: !editing || !inDocument,
  })
  // Not a droppable while a row is lifted (a row lands on the row gaps) or while anything foreign
  // is — the page's own two rules.
  const { setNodeRef: setDropRef } = useDroppable({
    id,
    data: {
      type: 'rig-drop',
      target: { kind: 'tile', at: at ?? { row: -1, tile: -1 } },
      depth: RIG_DROP_DEPTH.tile,
    } satisfies RigDropData,
    disabled: !editing || !inDocument || draggingRow || foreign,
  })
  const nodeRef = useRef<HTMLElement | null>(null)
  const setRef = (node: HTMLElement | null) => {
    setDragRef(node)
    setDropRef(node)
    nodeRef.current = node
  }

  const cells = cellsOf(tile)
  const selectedInTile = cells.filter((cell) => selectedCells.has(cell.key)).length
  const isGroup = tile.kind === 'group'
  const Icon = isGroup ? Layers : LayoutGrid
  const count =
    isGroup
      ? String(tile.group.memberCount)
      : tile.kind === 'fixture' && tile.cells.length > 0
        ? selectedInTile > 0 && !wholeSelected
          ? `${selectedInTile} of ${tile.cells.length}`
          : String(tile.cells.length)
        : null

  const menu = editing && inDocument && stored != null
  // Both edit controls live inside the top-right corner; the name keeps clear of them.
  const editControls = editing && inDocument
  // The cell modes are a multi-head fixture's alone (D3); a cell tile and a group have none.
  const multiHead =
    stored != null && stored.kind === 'FIXTURE' && stored.elementKey == null && (stored.patch?.elements?.length ?? 0) > 1
  // What the tile is called with no label — the name a rename is seeded with, and the one that
  // clears it — as `expandTile` applies a label to this stored tile's kind (see the file note).
  const ownName = stored == null ? tile.name : tileOwnName(stored)

  if (renaming && stored != null) {
    return (
      <div ref={setRef} data-rig-tile-id={id} className="relative flex shrink-0">
        <NameField
          autoFocus
          value={stored.label?.trim() || ownName}
          label="Tile name"
          placeholder={ownName}
          onSave={(name) => onRename(name.trim() === ownName ? null : name)}
          onDone={() => setRenaming(false)}
          className={cn('rounded-lg px-3.5', compact ? 'h-12 min-w-[120px]' : 'h-13 min-w-[148px]')}
        />
      </div>
    )
  }

  return (
    // `flex`, so the wrapper's box is the button's: the live overlay is positioned against it.
    <div ref={setRef} data-rig-tile-id={id} className={cn('relative flex shrink-0', isDragging && 'opacity-40')}>
      <button
        type="button"
        {...(editing && inDocument ? attributes : {})}
        {...(editing && inDocument ? listeners : {})}
        aria-pressed={presence !== 'none'}
        aria-label={tile.name}
        title={tile.name}
        onClick={editing ? undefined : onPress}
        className={cn(
          TILE_CLASS,
          // `flex-1`: in a stacked row (Rig focus below `md`) the wrapper is a grid cell and the
          // button fills it; in a flex row the wrapper is content-sized and this changes nothing.
          'flex-1',
          compact ? 'h-12 min-w-[120px]' : 'h-13 min-w-[148px]',
          presenceClass(presence),
          editing ? (inDocument ? 'cursor-grab' : 'cursor-default') : 'active:scale-[0.96]',
          // Room for the bar and the pips under the name.
          cells.length > 0 && 'pb-2.5',
          // Room for the cross and the menu in the corner.
          editControls && 'pr-12',
        )}
      >
        <Icon className={cn('size-3.5 shrink-0', presence !== 'none' ? 'text-primary' : 'text-muted-foreground')} />
        <span className="flex-1 truncate text-left">{tile.name}</span>
        {count != null && (
          <span className="rounded-full bg-muted px-1.5 py-px text-[10px] tabular-nums text-muted-foreground">
            {count}
          </span>
        )}
      </button>
      {!isGroup && (
        <TileLive
          tile={tile}
          cells={cells}
          selectedCells={selectedCells}
          lookup={lookup}
          inert={editing}
          onPressCell={onPressCell}
        />
      )}
      {editControls && (
        <div data-rig-tile-controls className="absolute top-1 right-1 flex items-center gap-0.5">
          {menu && (
            <TileMenu
              tile={stored}
              name={tile.name}
              multiHead={multiHead}
              onSetMode={onSetMode}
              onRename={() => setRenaming(true)}
              onRemove={onRemove}
            />
          )}
          <button
            type="button"
            onClick={onRemove}
            aria-label={`Remove ${tile.name} from the rig`}
            className={TILE_CONTROL_CLASS}
          >
            <X className="size-2.5" strokeWidth={2.5} />
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * Every in-document tile's menu while editing: the four cell modes (D3) on a multi-head fixture —
 * the whole fixture with pips, the whole fixture only, one tile per cell, or `HALVES` with its
 * split, 2 up to the cell count, which is exactly the range the server accepts — then *Rename
 * tile…* on every kind, and *Remove from rig* last.
 */
function TileMenu({
  tile,
  name,
  multiHead,
  onSetMode,
  onRename,
  onRemove,
}: {
  tile: BuskRigTile
  name: string
  multiHead: boolean
  onSetMode: (mode: BuskRigCellMode, split?: number) => void
  onRename: () => void
  onRemove: () => void
}) {
  const cellCount = tile.patch?.elements?.length ?? 0
  const splits: number[] = []
  for (let n = 2; n <= cellCount; n += 1) splits.push(n)
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button type="button" aria-label={`Options for ${name}`} className={TILE_CONTROL_CLASS}>
          <MoreHorizontal className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">
          {multiHead ? `${tile.patch?.name} · ${cellCount} cells` : name}
        </DropdownMenuLabel>
        {multiHead && (
          <>
            <DropdownMenuRadioGroup
              value={tile.cellMode}
              onValueChange={(mode) => {
                if (mode === 'HALVES') onSetMode('HALVES', tile.cellSplit ?? 2)
                else onSetMode(mode as BuskRigCellMode)
              }}
            >
              <DropdownMenuRadioItem value="PIPS">Whole fixture, cells on the tile</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="WHOLE">Whole fixture only</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="PER_CELL">One tile per cell · {cellCount} tiles</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="HALVES">Split into…</DropdownMenuRadioItem>
            </DropdownMenuRadioGroup>
            <div className="flex flex-wrap gap-0.5 px-1 pb-1">
              {splits.map((split) => (
                <button
                  key={split}
                  type="button"
                  aria-pressed={tile.cellMode === 'HALVES' && tile.cellSplit === split}
                  onClick={() => onSetMode('HALVES', split)}
                  className={cn(
                    'rounded px-2 py-1 text-xs tabular-nums hover:bg-accent',
                    tile.cellMode === 'HALVES' && tile.cellSplit === split && 'bg-muted font-semibold',
                  )}
                >
                  {split}
                </button>
              ))}
            </div>
            <DropdownMenuSeparator />
          </>
        )}
        <DropdownMenuItem onSelect={onRename}>Rename tile…</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive" onSelect={onRemove}>
          Remove from rig
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * The 3px live bar and, for a `PIPS` tile, the pips — one appearance leaf per fixture tile.
 *
 * A cell or run tile draws only its own cells' colours, read off the parent's per-element
 * `segments` by the element's position in the patch's cell list — an index into a list the desk
 * ordered, never a number parsed out of the key.
 */
function TileLive({
  tile,
  cells,
  selectedCells,
  lookup,
  inert,
  onPressCell,
}: {
  tile: Exclude<RenderTile, { kind: 'group' }>
  cells: BuskRigElement[]
  selectedCells: ReadonlySet<string>
  lookup: TileLookup
  inert: boolean
  onPressCell: (element: BuskRigElement) => void
}) {
  const patch = lookup.patchByKey.get(tile.patch.key)
  if (patch == null) return null
  const fixture = lookup.fixtureByKey.get(tile.patch.key)
  const fixtureType = fixture == null ? undefined : lookup.typeByKey.get(fixture.typeKey)
  const allCells = tile.patch.elements ?? []
  return (
    <FixtureAppearanceSource patch={patch} fixture={fixture} fixtureType={fixtureType}>
      {(appearance) => (
        <>
          {/* The Colour tab's *Pick* reads what this leaf resolved (`lib/liveAppearance.ts`). */}
          <LiveAppearanceReporter fixtureKey={tile.patch.key} appearance={appearance} />
          <LiveBar
            appearance={appearance}
            slices={
              tile.kind === 'fixture'
                ? null
                : cells.map((cell) => allCells.findIndex((candidate) => candidate.key === cell.key))
            }
            pips={tile.kind === 'fixture' && tile.pips ? cells : []}
            allCells={allCells}
            selectedCells={selectedCells}
            tileName={tile.name}
            inert={inert}
            onPressCell={onPressCell}
          />
        </>
      )}
    </FixtureAppearanceSource>
  )
}

/** A pip's colour: the stage's, with a floor so a dark cell is still a target. */
function sliceStyle(appearance: FixtureAppearance, index: number | null) {
  const segment = index == null || index < 0 ? undefined : appearance.segments?.[index]
  const color = segment?.css ?? appearance.color
  const intensity = segment?.intensity ?? appearance.intensity
  return { background: color, opacity: 0.15 + 0.85 * Math.max(0, Math.min(1, intensity)) }
}

/** The bar's colour: the stage's, and **nothing at zero** — a dark head wears no line (file note). */
function barStyle(appearance: FixtureAppearance, index: number | null) {
  const segment = index == null || index < 0 ? undefined : appearance.segments?.[index]
  const intensity = Math.max(0, Math.min(1, segment?.intensity ?? appearance.intensity))
  return { background: segment?.css ?? appearance.color, opacity: intensity <= 0 ? 0 : 0.15 + 0.85 * intensity }
}

function LiveBar({
  appearance,
  slices,
  pips,
  allCells,
  selectedCells,
  tileName,
  inert,
  onPressCell,
}: {
  appearance: FixtureAppearance
  /** Segment indices to draw the bar from, or null for the whole fixture's colour. */
  slices: number[] | null
  pips: BuskRigElement[]
  allCells: BuskRigElement[]
  selectedCells: ReadonlySet<string>
  tileName: string
  inert: boolean
  onPressCell: (element: BuskRigElement) => void
}) {
  return (
    <>
      {/* Inside the tile's 1px border, with the border's inner radius, so it never paints over the frame. */}
      <span
        aria-hidden
        data-rig-tile-bar
        className="pointer-events-none absolute inset-x-px bottom-px flex h-[3px] overflow-hidden rounded-b-[7px]"
      >
        {slices == null ? (
          <span className="flex-1" style={barStyle(appearance, null)} />
        ) : (
          slices.map((index, i) => <span key={i} className="flex-1" style={barStyle(appearance, index)} />)
        )}
      </span>
      {pips.length > 0 && (
        <Pips
          pips={pips}
          allCells={allCells}
          appearance={appearance}
          selectedCells={selectedCells}
          tileName={tileName}
          inert={inert}
          onPressCell={onPressCell}
        />
      )}
    </>
  )
}

/** How long a finger must hold still on a pip before a drag across the row is a run — the marquee's number. */
const PIP_HOLD_MS = 500

/**
 * The pips: one checkbox-role button per cell, and the run gesture across them — see the file
 * note for the rules. The row takes pointer capture for a live run, so every pip the pointer
 * crosses is found by `elementFromPoint`; a test dispatching a `pointermove` at a pip reaches the
 * same code through the event's own target.
 */
function Pips({
  pips,
  allCells,
  appearance,
  selectedCells,
  tileName,
  inert,
  onPressCell,
}: {
  pips: BuskRigElement[]
  allCells: BuskRigElement[]
  appearance: FixtureAppearance
  selectedCells: ReadonlySet<string>
  tileName: string
  inert: boolean
  onPressCell: (element: BuskRigElement) => void
}) {
  const rowRef = useRef<HTMLSpanElement | null>(null)
  /** The live run: the pointer that owns it and the cells it has already toggled. */
  const run = useRef<{ pointerId: number; toggled: Set<string> } | null>(null)
  /** A touch press waiting for its hold, and the pip it landed on. */
  const pending = useRef<{ pointerId: number; key: string | null } | null>(null)
  /** Set by a run's release: the click the browser is about to deliver is not a second toggle. */
  const swallowClick = useRef(false)
  /** The pip under a live touch run — the one drawn at 44px. */
  const [hot, setHot] = useState<string | null>(null)
  const touchGuard = useRef<((e: TouchEvent) => void) | null>(null)

  const pressRef = useRef(onPressCell)
  pressRef.current = onPressCell
  const pipsRef = useRef(pips)
  pipsRef.current = pips

  const releaseTouchGuard = useCallback(() => {
    if (touchGuard.current) {
      window.removeEventListener('touchmove', touchGuard.current)
      touchGuard.current = null
    }
  }, [])
  useEffect(() => releaseTouchGuard, [releaseTouchGuard])

  const toggle = useCallback((key: string | null) => {
    const live = run.current
    if (key == null || live == null || live.toggled.has(key)) return
    const cell = pipsRef.current.find((pip) => pip.key === key)
    if (cell == null) return
    live.toggled.add(key)
    pressRef.current(cell)
  }, [])

  const start = useCallback(
    (pointerId: number, key: string | null, held: boolean) => {
      run.current = { pointerId, toggled: new Set() }
      // A finger's run is followed off the row and the browser's pan is refused for as long as
      // it lasts (`useCellMarquee`'s `touchmove` guard, for its reason).
      if (held) {
        const guard = (e: TouchEvent) => e.preventDefault()
        window.addEventListener('touchmove', guard, { passive: false })
        touchGuard.current = guard
        setHot(key)
      }
      try {
        rowRef.current?.setPointerCapture(pointerId)
      } catch {
        // jsdom has no pointer capture; the browser always does.
      }
      toggle(key)
    },
    [toggle],
  )

  const end = useCallback(() => {
    if (run.current != null) {
      run.current = null
      // The click this release generates lands on a pip (or the row, with capture) in the same
      // task or the next; a click that never comes must not eat the next keyboard activation.
      swallowClick.current = true
      setTimeout(() => {
        swallowClick.current = false
      }, 350)
    }
    pending.current = null
    setHot(null)
    releaseTouchGuard()
  }, [releaseTouchGuard])

  // The touch arm: armed by time, never by distance, so a finger that moves before the hold is the
  // browser's scroll and `useLongPress` cancels the hold on its travel.
  const { handlers: hold } = useLongPress({
    delayMs: PIP_HOLD_MS,
    onLongPress: () => {
      const press = pending.current
      if (press == null || run.current != null) return
      start(press.pointerId, press.key, true)
    },
  })

  const pipAt = (e: React.PointerEvent): string | null => {
    const direct = (e.target as Element | null)?.closest?.('[data-rig-pip]')
    const under = direct ?? document.elementFromPoint?.(e.clientX, e.clientY)?.closest('[data-rig-pip]') ?? null
    return under?.getAttribute('data-rig-pip') ?? null
  }

  const onPointerDown = (e: React.PointerEvent) => {
    if (inert || e.button !== 0 || run.current != null || pending.current != null) return
    // The tile's own press must not see this: the row overlays the button.
    e.stopPropagation()
    const key = pipAt(e)
    if (e.pointerType === 'touch' || e.pointerType === 'pen') {
      pending.current = { pointerId: e.pointerId, key }
      hold.onPointerDown(e)
      return
    }
    start(e.pointerId, key, false)
  }

  const onPointerMove = (e: React.PointerEvent) => {
    const live = run.current
    if (live != null) {
      if (e.pointerId !== live.pointerId) return
      const key = pipAt(e)
      if (touchGuard.current) setHot(key)
      toggle(key)
      return
    }
    if (pending.current?.pointerId === e.pointerId) hold.onPointerMove(e)
  }

  const onPointerUp = (e: React.PointerEvent) => {
    if (run.current != null && e.pointerId !== run.current.pointerId) return
    if (pending.current != null && pending.current.pointerId !== e.pointerId) return
    hold.onPointerUp()
    end()
  }

  const onPointerCancel = (e: React.PointerEvent) => {
    if (run.current != null && e.pointerId !== run.current.pointerId) return
    hold.onPointerCancel()
    end()
  }

  return (
    <span
      ref={rowRef}
      data-rig-pips
      role="group"
      aria-label={`${tileName} cells`}
      className={cn(
        'absolute right-3.5 bottom-[5px] left-3.5 flex items-end gap-0.5',
        inert ? 'pointer-events-none' : 'touch-manipulation',
      )}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onClickCapture={(e) => {
        if (!swallowClick.current) return
        swallowClick.current = false
        e.preventDefault()
        e.stopPropagation()
      }}
    >
      {pips.map((cell) => {
        const index = allCells.findIndex((candidate) => candidate.key === cell.key)
        const selected = selectedCells.has(cell.key)
        return (
          <button
            key={cell.key}
            type="button"
            role="checkbox"
            aria-checked={selected}
            aria-label={`${tileName} · ${cell.name}`}
            data-rig-pip={cell.key}
            tabIndex={inert ? -1 : 0}
            // The keyboard's toggle — a mouse or a finger has already gone through the run, and
            // the click that follows a run is swallowed above.
            onClick={() => onPressCell(cell)}
            className={cn(
              'min-w-0 flex-1 rounded-[1px] transition-[height] duration-100',
              hot === cell.key ? 'z-10 h-11 ring-2 ring-primary' : 'h-2',
              // Selected: the accent, solid, over whatever the stage says — the design's `pip.on`.
              selected && 'bg-primary shadow-[0_0_0_1px_var(--background)]',
            )}
            style={selected ? undefined : sliceStyle(appearance, index)}
          />
        )
      })}
    </span>
  )
}

/** The tile-shaped placeholder that opens where a drop would land. */
export function RigDropSlot({ compact }: { compact: boolean }) {
  return (
    <div
      aria-hidden
      // Never a droppable and never hit-testable — `BuskDropSlot`'s reason.
      className={cn(
        'pointer-events-none shrink-0 rounded-lg border-2 border-dashed border-primary bg-primary/5',
        compact ? 'h-12 w-[120px]' : 'h-13 w-[148px]',
      )}
    />
  )
}
