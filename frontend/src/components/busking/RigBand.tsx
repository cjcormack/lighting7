import { useCallback, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { useDraggable, useDroppable } from '@dnd-kit/core'
import { ChevronDown, ChevronLeft, ChevronRight, Grid2x2, GripVertical, MoreHorizontal, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
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
import { DeskChip } from '@/components/desk/DeskChip'
import { BlindPill } from './BlindMarks'
import { HandPlaceStrip } from '@/components/hand/HandTarget'
import { registerDragOverlay } from '@/components/dnd/dragOverlayRegistry'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { targetKey } from '@/lib/targetKey'
import { cn } from '@/lib/utils'
import type { CueTarget } from '@/api/cuesApi'
import type { SubselectMode } from '@/api/selectionApi'
import type { HeldRecord } from '@/api/handApi'
import { BUSK_FLOWS, BUSK_FLOW_LABELS, BUSK_WIDTHS, BUSK_WIDTH_LABELS, type BuskFlow } from '@/api/buskApi'
import type { BuskRigCellMode, BuskRigRow } from '@/api/buskRigApi'
import { useGroupListQuery } from '@/store/groups'
import { usePatchListQuery } from '@/store/patches'
import { useBuskRigQuery } from '@/store/busk'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useHandPlace } from '@/store/hand'
import { useBuskRigHeight, useDefaultBuskRigRows } from '@/lib/buskWindow'
import { SUBSELECT_FILTER_MODES, SUBSELECT_MODE_LABELS, SUBSELECT_STEP_MODES } from '@/lib/cellsSubSelection'
import {
  applyDrop,
  effectiveRig,
  expandTile,
  removeRow,
  relabelTile,
  removeTile,
  renameRow,
  rigIdsFromPatches,
  rigLines,
  rigRowBodyId,
  rigRowGapId,
  rigRowId,
  RIG_NEW_ROW_ID,
  rowFlow,
  rowTiles,
  rowWidth,
  setRowLayout,
  setTile,
  tileKeyOf,
  type RenderTile,
  type RigPaletteRecord,
  type RigTileAddress,
} from '@/lib/buskRig'
import { RIG_DROP_DEPTH, rigDragData, type RigDropData, type RigRowDragData } from './buskDnd'
import { EditorLabel } from '../editor/EditorLabel'
import { NameField } from './NameField'
import { RigEditProvider, useRigEdit } from './RigEditProvider'
import { PAGE_MIN_HEIGHT_PX, RIG_MIN_HEIGHT_PX, RigHandle, clampRigHeight, lineEdges } from './RigHandle'
import { RigDropSlot, RigTile, type TileLookup } from './RigTile'
import { summariseSelection, type BuskingTarget, type EffectPresence } from './buskingTypes'
import { SelectionVerbButtons, VERB_CLASS, type SelectionVerbs } from './selectionVerbs'

/**
 * The **rig band**: the target band as a document the operator built (busk-further plan D1–D3),
 * across the top of the busk view.
 *
 * Rows of tiles, each tile a group, a fixture, or a fixture's cell; an **empty rig draws every
 * group then every fixture** — `effectiveRig`'s fallback, the client's by decision — so a desk with
 * nothing built sees what the target band showed. There is **one render path**: the fallback is a
 * set of rows like any other, only its tiles carry no address and take no drop.
 *
 * **A row is laid out the way a bank is** (2026-09-21): it carries a `width` share in twelfths and
 * a `flow` — the bank's two facts, plus `SCROLL`, the sideways-scrolling line every row was before
 * it had a flow and still the default. The rows fill a twelve-track grid in order (`rigLines`), so
 * two half-width rows sit side by side as two half-width columns do on a page, and the band's unit
 * is the **line**, not the row: the handle snaps to a line's edge and the default height counts lines. Both
 * facts are set from the row's `…` menu in *Edit layout*, as a bank's are from its own.
 *
 * **A press is a plain toggle**, as it was, and a **pip is a press of its own** (session 7): a
 * `PIPS` tile's cells toggle `{type: 'fixture', key: element.key}` through the same `onToggle`, and
 * a drag across them is a run (`RigTile`). **One row of chrome, then the rows** (busk-chrome plan
 * D13, `Band.dc.html`): the `RIG` label, the **Cells menu** with its two step buttons, the verbs
 * (*Spread…*, Locate, Highlight, Clear, each an icon with a word beside it where the band is wide
 * and the icon alone where it is not — `VERB_WORD_CLASS`), then the family pill, the `BLIND` pill and the desk chip
 * **left-anchored after the verbs**, the gap, and whatever the host hands in as [controls] —
 * the Focus control and *Edit layout* / *Done* — right-anchored. The same DOM order in **Split and
 * Rig**, which are the two shapes this band is drawn in on the desk board: **in Pads the rig row is
 * not drawn** (D17). The row's own controls act on tiles — the Cells menu, the steps and Clear
 * narrow, move or release a selection made on them — and in Pads there are none on screen, so in
 * the shape that exists to give the page the height a full row of chrome was doing nothing; the
 * pad row (`BuskPageStrip`) is the body's top row there, carrying the three selection verbs, the
 * summary and the host's controls. The band drew itself folded to this one row and a chevron pill
 * in Pads from the morning of 2026-09-21 to the evening; before that it was two rows — a label row
 * over a controls row — and before that one row that a desk width with the sidebar open wrapped.
 * The merge cost 32px in every shape and the fold ladder below is what keeps it from wrapping
 * again. Below `md` the band is one row with a row chip and the verbs in a menu (the phone board),
 * and there is no editing: the palette is not drawn there either.
 *
 * **The three selection verbs are the host's** (`useSelectionVerbs` in `selectionVerbs.tsx`):
 * `BuskingView` calls the hook once and hands [verbs] to whichever row is drawn — this band in
 * Split and Rig, the pad row in Pads — so a window has one Highlight capture and one locate fold,
 * and the two rows cannot answer a press two ways. Clear stays here, with the Cells menu and the
 * steps: it releases a selection made on the tiles.
 *
 * **The Cells menu is one desk op** (busk-further plan D12): the seven *filters* — All · Odd ·
 * Even · 1st half · 2nd half · Invert · Masters only — in one menu whose label names the mode last
 * pressed here, and *Prev* / *Next* as two buttons beside it, because a step moves the selection
 * along the rig where a filter narrows it, and the two read as one control only while they sat on
 * one chip. Each press is `onSubselect(mode)`, which is `selection.subselect` while this window
 * follows the desk and `lib/cellsSubSelection.ts`'s mirror over the tab's copy when it is unlinked
 * (`useBuskingSelection`). The desk keeps **no** sub-selection state, so nothing on the face is
 * derived from the selection: the label is only the filter last pressed here, and it resets with
 * the band. A step is never remembered as the label — it is not a mode.
 *
 * **The handle is `RigHandle`, in Split only** (busk-further plan D6, rebuilt 2026-09-22): a grip
 * under the rows that sets **the rig region's height in px** — the window's `busk.rigHeight`
 * (`lib/buskWindow.ts`), `null` until chosen and drawn then as the first `defaultBuskRigRows`
 * whole lines, measured — and nothing else. In Split **every line is mounted and the grid is a
 * scroller** at that height, so the bar can rest between two lines and the rows scroll under it
 * (every fixture tile's appearance leaf is mounted with it — the whole rig's channel
 * subscriptions rather than a few lines' worth, as Rig focus and edit mode already paid, and what
 * makes the Colour tab's *Pick* answer for a head below the fold);
 * dragged past the last line the region simply has room, which is how the page is made smaller.
 * The snap is **live and magnetic**, to a line's bottom edge within `RIG_SNAP_PX` while the grip
 * is held; the keys step between the same edges; a double press restores the default. It is
 * clamped on read, never on write: the page keeps `PAGE_MIN_HEIGHT_PX` below the handle, so a
 * stored height taller than a window that has since shrunk is drawn at the ceiling and comes back
 * when the window does. **It never changes focus** — the count it replaced snapped past its ends
 * into Rig and Pads, live at the ends and on release between them, and could shrink only the
 * rig; Pads and Rig are the Focus control's, and in Rig the folded page strip's chevron is the way
 * back (`BuskPageStrip`), as the rig strip's is off the desk board. The band writes the resolved
 * height onto the grid from a **layout effect, never a `style` prop**, so the handle's per-move
 * write is not undone by a re-render and a handle unmounted mid-drag leaves nothing stale
 * (`RigHandle`'s docblock). In **Rig focus** the
 * band takes `focus="rig"`: every row, the band filling the body and its rows scrolling. **Below `md` Rig
 * focus stacks every row two tiles across, scrolling vertically** (`Phones.dc.html` note 6): it is
 * D15's replacement for the narrow-width target sheet, and a sideways scroll per row on a phone would
 * defeat the point of a list. Edit mode shows every row regardless, since a hidden row cannot take a
 * drop.
 *
 * In *Edit layout* the band joins the app's one `DndContext` through `RigEditProvider`: rows
 * reorder by their grip onto the gaps between rows, a tile or a Rig-tab palette row lands on a tile,
 * a row body or the new-row zone, and every gesture saves the whole rig through the same
 * operation queue the page uses. The show-all fallback is drawn dimmed behind the new-row zone
 * while editing an empty rig, because it is not on the wire and nothing on it can move.
 */

export interface RigBandProps {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  /** The selection's attribute mask, drawn as a pill beside the summary. Null is every attribute. */
  families: AttributeFamily[] | null
  onToggle: (target: CueTarget) => void
  onClear: () => void
  /** *Spread…* · Locate · Highlight — the host's one instance, drawn on this row in Split and Rig. */
  verbs: SelectionVerbs
  /** The Cells menu's and the step buttons' press — one desk op, or the client mirror when unlinked (D12). */
  onSubselect: (mode: SubselectMode) => void
  editing: boolean
  /** Off the desk board — below `md` and on the short board: one row with a row chip, 48px tiles, the verbs in a menu. */
  compact: boolean
  /**
   * Below `md` only: Rig focus stacks every row two tiles across, scrolling with the band
   * (`Phones.dc.html` note 6). Not on the short board — a landscape phone is wider than `md` and
   * keeps its sideways rows, which is why this is not derived from [compact].
   */
  stackRows?: boolean
  /**
   * Split draws the rows as a scroller at `busk.rigHeight` under the handle; Rig fills the body
   * with every row. There is no Pads arm on the desk board (D17): in Pads the host draws the pad
   * row instead of this band, and off the desk board `RigStrip` is the fold.
   */
  focus: 'split' | 'rig'
  /**
   * Drawn at the right end of the one row (the compact board's too): the Focus
   * control and *Edit layout* / *Done*. Handed in rather than mounted here because the band knows
   * nothing about the window's shape or the page's edit mode.
   */
  controls?: ReactNode
}

/**
 * **The folds** (busk-chrome plan D15, D19, D20; `Band.dc.html` §How the row folds). The band is
 * its own `@container`, and the one row gives up its words in a fixed order as the band narrows,
 * so that nothing lands mid-row before the floor:
 *
 * 1. **The verbs' words**, first ([VERB_WORD_CLASS]) — and *Edit layout*'s with them
 *    ([EDIT_WORD_CLASS], which `BuskingView` hands the toggle).
 * 2. **The *Cells:* prefix and the Focus control's words** ([CELLS_PREFIX_CLASS],
 *    [FOCUS_WORD_CLASS]). The Cells control keeps its **mode word** — All · Odd · Even · 1st · 2nd
 *    · Invert · Masters — and is never a bare glyph: *All* is the state an operator most needs to
 *    be sure of, and *Odd* or *Masters* on a 28px button is what the menu was for. The desk chip's
 *    subject goes at this rung too ([CHIP_SUBJECT_CLASS]): the chip is drawn only while the window
 *    is unlinked — linked, it is the link badge, a glyph with nothing to fold (desk-follow plan D8)
 *    — and *This window* alone still says what it is.
 * 3. **The `RIG` label** ([RIG_LABEL_CLASS]) — to nothing, at the narrow end of the ladder and
 *    before the floor, to gain its width (D20). `data-rig-row` is the handle a test reaches the
 *    row by, since the label no longer is.
 * 4. **Two rows, by design** (`TWO_ROWS_CLASS`, `SECOND_ROW_CLASS`): below the floor the row wraps
 *    at exactly one point — the selection verbs on the first row, the pill, the badge or chip and
 *    the Focus control on the second. Not `flex-wrap`'s own choice of break: the row is `flex-nowrap` above
 *    the floor, so a control never lands mid-row. Only under that floor does the verbs group wrap
 *    within its own line (`FIRST_ROW_CLASS`), the last resort for a band narrower than the icons.
 * 5. **The words come back under the floor** (D19). Each row then has its whole line, so what the
 *    ladder took is drawn again while the line holds it and folds again below a second, measured
 *    rung — the verbs' words and then the *Cells:* prefix on the first line, *Edit layout*'s word
 *    and then the Focus words and the chip's subject on the second. Every re-expansion is a
 *    **stacked** `@min-[…]:@max-[floor]:` variant, a closed range that overlaps no rung above the
 *    floor, so no rung depends on the order Tailwind emits the rules in; the label does not come
 *    back, since under the floor the row is plainly the rig's.
 *
 * The desk chip's value truncates before anything else moves — `DeskChip` is given `min-w-0
 * shrink` here, both words, because the pill's base class is `shrink-0` and a bare `min-w-0`
 * leaves it unshrinkable. The badge it draws while linked takes none of that: a glyph that gave
 * way would be the one thing on the row moving for nothing. Under the floor the verbs group itself may wrap (`FIRST_ROW_CLASS`): the
 * desk board can be narrowed to a band below the ~330px the iconic verbs need (a ~900px window, the
 * sidebar open and the sheet at its 480 ceiling), and a size-contained `@container` clips nothing,
 * so without that last resort the verbs painted over the sheet. That wrap is under the designed
 * break, never instead of it: above the floor the row is `flex-nowrap`.
 *
 * **The numbers are the app's, measured in the browser on 2026-09-21 (evening) and re-measured on
 * 2026-09-23 for the link badge** (desk-follow plan D8), at the desk's control sizes, with a
 * one-family pill drawn (~60; the row has to hold when a mask is set) and **the badge drawn** (21,
 * plus a gap: a following window always shows it, and Pads and Rig always follow). Unlinked, the
 * chip takes the badge's place and folds and truncates on its own; its floor is 35, which the 14px
 * of slack at the floor covers exactly. Worded, the row is 972px — the verbs group 573 (`RIG` 19,
 * Cells 107, the steps 58, the four verbs 93 · 81 · 94 · 72, six 8px gaps), the state group 391
 * (pill 60, badge 21, Focus 183, *Edit layout* 103, four gaps round the spacer), 8 between — so the
 * verbs' words and *Edit layout*'s go at **990**; iconic (36 each, the Cells control 71), the row
 * is 704, so the *Cells:* prefix (36) and the Focus words (85) go at **730**; with those gone it is
 * 583, the label (19 + a gap) goes at **610**, and with it gone the row is 556, so the floor is
 * **570**. Under it the verbs line has no label and is 546 fully worded, but the verbs' words still
 * return only with the prefix folded (510), from **520**, and the prefix on the iconic line (349)
 * from **350** up to that 520, where the words take its place — conservative by 24px, kept so the
 * two never share the line; the state line is 399 worded, so *Edit layout*'s word returns from
 * **400**, and 332 with it iconic (the toggle is 36, not the 32 the first note assumed), so the
 * Focus words and the chip's subject return from **340**. Checked in the browser with the band
 * forced to each rung and one below it, and a 60px stand-in for the mask pill: the row's
 * `scrollWidth` never exceeded its `clientWidth`. They were 960 / 700 / 580 / 540 with no chip or
 * badge on the row, 1100 / 820 / 700 with the chip, and 860 / 680 while the summary had a row of
 * its own.
 *
 * **The blind pill is not in those numbers, by design.** `BlindPill` (`BlindMarks.tsx`) is drawn
 * only while the programmer is blind — 63px worded, 26 iconic, plus a gap — so the ladder is
 * measured without it, as it is measured with a one-family pill: the row must hold for the state
 * that lasts all night, not for the one that is an operator's mistake. Its word therefore has a
 * rung of its own above every other (`BLIND_WORD_CLASS`, 1050, and 470 on the state line under the
 * floor), and the pill is `min-w-0 shrink` — the one thing on the row besides the chip that may
 * give. With a mask pill *and* blind, the iconic pill's 34 exceeds the slack at four rungs — 990
 * (18 spare), 730 (26), 610 (27) and 570 (14) — so in the bands 990–1006, 730–738, 610–617 and
 * 570–590 the pill squashes to an amber sliver rather than pushing the Focus control under the
 * sheet. Measured 2026-09-21: the pill on the desk row at 738 of band is 62.5 worded and 26 iconic,
 * and the row's `scrollWidth` equalled its `clientWidth` at 738, 616, 540 and 496 with no mask.
 */

export { snapRigHeight, stepRigHeight } from './RigHandle'

/**
 * The floor, as a number, so the tests can pin every class below against the one value. **Every
 * class below is a literal**, never assembled from this number: Tailwind v4 generates a utility
 * only for a candidate its scanner finds in the source text, and a variant built at runtime from
 * `${…}` is in no file — the first cut did that, and the built CSS had no floor and no
 * re-expansion at all. `RigBand.test.tsx` pins that each literal's floor equals this constant.
 */
export const RIG_ROW_FLOOR_PX = 570

/** The verbs' words: first to fold above the floor, and back on their own line from 520 under it. */
export const VERB_WORD_CLASS = 'hidden @[990px]:inline @min-[520px]:@max-[570px]:inline'
/** *Edit layout*'s word, folded with the verbs' above the floor; under it, on the state line, from 400. */
export const EDIT_WORD_CLASS = 'hidden @[990px]:inline @min-[400px]:@max-[570px]:inline'

/**
 * The *Cells:* prefix, which goes a step after the verbs' words; the mode word beside it never
 * does. Under the floor it is drawn on the iconic line from 350 **up to 520, where the verbs' words
 * return**: the fully worded line is six pixels wider than the floor, so the two never share it.
 */
const CELLS_PREFIX_CLASS = 'hidden @[730px]:inline @min-[350px]:@max-[520px]:inline'
/** The mode word's *short* form, drawn while the prefix is folded — `1st`, not `1st half`. */
const CELLS_SHORT_CLASS = 'inline @[730px]:hidden @min-[350px]:@max-[520px]:hidden'
/** The mode word's full form, drawn beside the prefix. */
const CELLS_FULL_CLASS = CELLS_PREFIX_CLASS

/** The Focus control's labels, by the same measure as the Cells prefix; under the floor from 340. */
export const FOCUS_WORD_CLASS = 'hidden @[730px]:inline @min-[340px]:@max-[570px]:inline'
/**
 * The blind pill's word (`BlindMarks.tsx`): worded only where the fully-worded row has room for
 * its 71 (63 + a gap) — from **1050**, above every other rung — and, under the floor, on the state
 * line from **470** (391 worded + 71). Its glyph stays at every width; see the ladder note above
 * for the four bands where the pill itself gives.
 */
export const BLIND_WORD_CLASS = 'hidden @[1050px]:inline @min-[470px]:@max-[570px]:inline'
/** The desk chip's *Targets:* subject — the chip's second part to go (D19), at the prefix's rung. */
export const CHIP_SUBJECT_CLASS = 'hidden @[730px]:inline @min-[340px]:@max-[570px]:inline'

/** The `RIG` label: folded to nothing below 610, the rung before the floor (D20). */
export const RIG_LABEL_CLASS = 'hidden @[610px]:block'

/** The floor: below it the row wraps once, at the state group, into two rows by design. */
export const TWO_ROWS_CLASS = '@max-[570px]:flex-wrap'
export const SECOND_ROW_CLASS = '@max-[570px]:basis-full'
/** Under the floor the verbs group takes its whole line and may wrap within it — the last resort under ~330px. */
export const FIRST_ROW_CLASS = '@max-[570px]:w-full @max-[570px]:flex-wrap'

/**
 * The Focus control's labels on the **compact** boards — `RigStrip` and the short board's merged
 * row, each its own `@container` — where the row is `RIG`, the summary, the pill, the chip, the
 * verbs menu and the Focus control and nothing else; measured for that row, not this one, and left
 * where it was when the desk row's fold moved.
 */
export const COMPACT_FOCUS_WORD_CLASS = 'hidden @[680px]:inline'

/** The Cells control's face while its prefix is folded: the mode in one word. */
const CELLS_SHORT_LABELS: Record<SubselectMode, string> = {
  ALL: 'All',
  ODD: 'Odd',
  EVEN: 'Even',
  NEXT: 'Next',
  PREV: 'Prev',
  FIRST_HALF: '1st',
  SECOND_HALF: '2nd',
  INVERT: 'Invert',
  MASTERS: 'Masters',
}

export function RigBand(props: RigBandProps) {
  const { projectId, editing } = props
  const { data: rig, isError: rigFailed } = useBuskRigQuery(projectId)
  const { data: patches } = usePatchListQuery(projectId)
  const ids = useMemo(() => rigIdsFromPatches(patches), [patches])
  const document = useMemo(() => rig ?? { rows: [] }, [rig])
  return (
    <RigEditProvider editing={editing} projectId={projectId} rig={document} ids={ids}>
      <RigBandBody {...props} rigLoaded={rig != null || rigFailed} />
    </RigEditProvider>
  )
}

function RigBandBody({
  projectId,
  selectedTargets,
  families,
  onToggle,
  onClear,
  verbs,
  onSubselect,
  editing,
  compact,
  stackRows = false,
  focus,
  controls,
  rigLoaded,
}: RigBandProps & { rigLoaded: boolean }) {
  const { rig, source, foreign, commit } = useRigEdit()
  const { data: groups } = useGroupListQuery()
  const { data: patches } = usePatchListQuery(projectId)
  const { fixtures, fixtureByKey, typeByKey } = useFixtureLookup()
  const placeFromHand = useHandPlace()

  const lookup = useMemo<TileLookup>(() => {
    const patchByKey = new Map(patches?.map((patch) => [patch.key, patch]) ?? [])
    return { patchByKey, fixtureByKey, typeByKey }
  }, [patches, fixtureByKey, typeByKey])

  const effective = useMemo(
    () => (rigLoaded ? effectiveRig(rig, groups, fixtures) : { rows: [], fallback: false }),
    [rigLoaded, rig, groups, fixtures],
  )
  const lines = useMemo(() => rigLines(effective.rows), [effective.rows])

  // Every line is mounted in every shape but the compact boards' one-row Split (a hidden row
  // cannot take a drop, Rig focus *is* the whole rig, and the desk board's Split is a scroller
  // under the handle). The compact Split shows one row with the row chip.
  const everyRow = editing || focus === 'rig'
  const [compactRow, setCompactRow] = useState(0)
  const compactIndex = Math.min(compactRow, Math.max(0, effective.rows.length - 1))
  const visibleLines: number[][] =
    compact && !everyRow
      ? effective.rows.length === 0 ? [] : [[compactIndex]]
      : lines
  const rowsRef = useRef<HTMLDivElement>(null)

  // ── The split's height (D6, rebuilt 2026-09-22) ──
  // The desk board's Split draws the rows as a scroller at the window's `busk.rigHeight`, or —
  // while the window has not chosen — at the first `defaultBuskRigRows` whole lines, measured
  // once they are on screen. Both are clamped to what the column can give: the page keeps
  // `PAGE_MIN_HEIGHT_PX`. The measurement is a layout effect, so the unmeasured first frame (the
  // grid at its natural height) is never painted, and it re-runs when the rows change or the
  // column or the grid resizes — a narrower column reflows a `WRAP` row, a shorter one lowers the
  // ceiling, and a grid that has just become a scroller can lose a scrollbar's width.
  // The height goes onto the grid **imperatively, from a layout effect keyed on the value**, never
  // as a `style` prop: the handle writes the same property per move, and a prop would undo that on
  // any re-render mid-drag (`RigHandle`'s docblock).
  const splitScroller = focus === 'split' && !editing && !compact && lines.length > 0
  const wish = useBuskRigHeight()
  const defaultRows = useDefaultBuskRigRows()
  const [measured, setMeasured] = useState<{ defaultHeight: number; capacity: number } | null>(null)
  // While the grip is held the handle owns the grid's height (`RigHandle`'s `onDragging`).
  const [dragging, setDragging] = useState(false)
  const measureSplit = useCallback(() => {
    const rows = rowsRef.current
    if (rows == null) return
    const gridRect = rows.getBoundingClientRect()
    // Content coordinates: the edges as drawn plus whatever the grid is scrolled by, so the
    // default is the same lines however the operator has scrolled.
    const edges = lineEdges(rows).map((edge) => edge + rows.scrollTop)
    const defaultHeight = edges[Math.min(defaultRows, edges.length) - 1] ?? RIG_MIN_HEIGHT_PX
    // The ceiling is the column's height less every fixed thing in it but the grid and the page —
    // the band's chrome above the grid, the handle and the band's padding below it, the page strip
    // — less the page's minimum. Measured from those boxes and **never inferred from the page
    // body's remainder**: with the grid at its natural height on the first frame, a rig taller
    // than the column has already collapsed the body to nothing, and "grid + body − minimum" then
    // answered several times the real ceiling — a stored height past it was accepted, the region
    // drawn taller than the column, and the page unreachable. The chrome boxes do not move when
    // the band overflows; the column's own box is the viewport's, not its content's.
    const column = rows.closest('[data-busk-column]')
    const band = rows.closest('[data-rig-band]')
    const strip = column?.querySelector('[data-busk-page-strip]')
    const capacity =
      column != null && band != null
        ? (() => {
            const columnRect = column.getBoundingClientRect()
            const bandRect = band.getBoundingClientRect()
            const above = gridRect.top - columnRect.top
            const below = bandRect.bottom - gridRect.bottom + (strip?.getBoundingClientRect().height ?? 0)
            return columnRect.height - above - below - PAGE_MIN_HEIGHT_PX
          })()
        : Infinity
    setMeasured((prev) =>
      prev != null && prev.defaultHeight === defaultHeight && prev.capacity === capacity ? prev : { defaultHeight, capacity },
    )
  }, [defaultRows])
  useLayoutEffect(() => {
    if (!splitScroller) return
    measureSplit()
    const rows = rowsRef.current
    const column = rows?.closest('[data-busk-column]')
    if (rows == null || column == null || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => measureSplit())
    // The column for the ceiling and the width; the grid too, because becoming a scroller can
    // narrow the rows by a scrollbar and reflow a `WRAP` row without the column moving at all.
    observer.observe(column)
    observer.observe(rows)
    return () => observer.disconnect()
    // `lines` re-measures when the rows change: a row added or re-laid-out moves every edge.
  }, [splitScroller, measureSplit, lines])
  const capacity = measured?.capacity ?? Infinity
  const rigHeight =
    measured == null ? null : clampRigHeight(wish ?? measured.defaultHeight, RIG_MIN_HEIGHT_PX, capacity)
  useLayoutEffect(() => {
    const rows = rowsRef.current
    if (rows == null) return
    // Held off only while a drag is live **in Split**: a shape change under a drag (another
    // window's `focus: 'rig'`) must clear the height in this same commit, since the handle's
    // teardown — the thing that ends `dragging` — is a passive cleanup and runs a paint later.
    if (dragging && splitScroller) return
    rows.style.height = splitScroller && rigHeight != null ? `${rigHeight}px` : ''
    // `dragging` is a dep so the release re-syncs the grid to the resolved height — which is the
    // one the drag committed, or the old wish re-clamped if the ceiling moved under a press that
    // moved nothing.
  }, [splitScroller, rigHeight, dragging])

  // Every cell the selection **covers**: the cells selected on their own, and every cell of a
  // selected whole fixture — the desk's `TargetCoverage` reads a cell as covered by its parent, so
  // a pip under a selected parent must read checked, or its press (which narrows the parent) would
  // be the dark-pip-that-deselects reading `presenceOf` refuses below for a cell tile.
  const selectedCells = useMemo(() => {
    const cells = new Set<string>()
    for (const target of selectedTargets.values()) {
      if (target.type !== 'fixture') continue
      if (target.element != null) cells.add(target.key)
      else for (const element of target.fixture.elements ?? []) cells.add(element.key)
    }
    return cells
  }, [selectedTargets])

  /** Is this tile's whole fixture selected as itself — which is what tells `4` from `4 of 4`. */
  const wholeSelected = useCallback(
    (tile: RenderTile): boolean => tile.kind === 'fixture' && selectedTargets.has(targetKey(tile.target)),
    [selectedTargets],
  )

  // The parent↔cell relation runs both ways (D11: a parent covers its cells): a fixture tile reads
  // `some` when one of its cells is selected elsewhere, and a cell or run tile reads `all` while
  // its whole fixture is — the desk would read a press on that cell as a narrowing, and a dark tile
  // whose press narrows is the worst reading of the three.
  const presenceOf = useCallback(
    (tile: RenderTile): EffectPresence => {
      if (tile.kind === 'run' || tile.kind === 'cell') {
        if (selectedTargets.has(targetKey({ type: 'fixture', key: tile.patch.key }))) return 'all'
      }
      if (tile.kind === 'run') {
        const lit = tile.targets.filter((target) => selectedTargets.has(targetKey(target))).length
        return lit === 0 ? 'none' : lit === tile.targets.length ? 'all' : 'some'
      }
      if (selectedTargets.has(targetKey(tile.target))) return 'all'
      if (tile.kind === 'fixture' && tile.cells.length > 0) {
        const lit = tile.cells.filter((cell) => selectedCells.has(cell.key)).length
        // Every cell selected reads `all` as the whole fixture does: what a run across all the pips
        // leaves, and what *Cells: All* would widen to the parent.
        if (lit === tile.cells.length) return 'all'
        if (lit > 0) return 'some'
      }
      return 'none'
    },
    [selectedTargets, selectedCells],
  )

  // A run is one pad: pressed from `all` it goes off, from anything else it goes on — toggling
  // each cell independently would carry a half-lit run to its complement, which is `some` again.
  // A fixture tile lit only by its cells follows the same rule: from `all` it goes off, cell by
  // cell, because toggling the parent there would *add* it (the desk never reads a parent as
  // covered by its cells) and the tile would not visibly move.
  const press = useCallback(
    (tile: RenderTile) => {
      if (tile.kind === 'fixture' && tile.cells.length > 0 && !selectedTargets.has(targetKey(tile.target))) {
        const lit = tile.cells.filter((cell) => selectedCells.has(cell.key))
        if (lit.length === tile.cells.length) {
          tile.cells.forEach((cell) => onToggle({ type: 'fixture', key: cell.key }))
          return
        }
      }
      if (tile.kind !== 'run') {
        onToggle(tile.target)
        return
      }
      const lit = tile.targets.filter((target) => selectedTargets.has(targetKey(target)))
      if (lit.length === tile.targets.length) tile.targets.forEach(onToggle)
      else tile.targets.filter((target) => !selectedTargets.has(targetKey(target))).forEach(onToggle)
    },
    [onToggle, selectedTargets, selectedCells],
  )

  // ── The row's verbs ── (Spread…, Locate and Highlight are the host's, in `verbs`)
  const selected = [...selectedTargets.values()]
  const summary = summariseSelection(selected)

  const [confirmingReset, setConfirmingReset] = useState(false)
  const draggingRow = source?.type === 'rig-row'

  // The hand's rig-row place (session 3's fifth item): the rig PUT through the commit queue, then
  // `hand.drop` through `useHandPlace`, Undo the rig as it stood. `rigRecordOf` answers null for
  // every kind the hand can hold today, so the strip never lights — see `lib/handTargets.ts`.
  const placeHeld = useCallback(
    (row: number, rowName: string, held: HeldRecord) => {
      void placeFromHand(held, {
        where: rowName,
        run: async () => {
          const record = rigRecordOf(held)
          if (record == null) return null
          const before = rig
          const at: RigTileAddress = { row, tile: rowTiles(rig.rows?.[row] ?? { name: '', tiles: [] }).length }
          commit((current) => applyDrop(current, { kind: 'rig-palette', record }, { kind: 'tile', at }) ?? current)
          return before
        },
        undo: (before) => commit(() => before),
      })
    },
    [placeFromHand, rig, commit],
  )

  const nothingToShow = rigLoaded && effective.rows.length === 0

  /** Edit mode's reset, drawn on whichever row the board has — a window narrowed mid-edit keeps it. */
  const resetButton = (
    <Button
      variant="ghost"
      size="sm"
      className={VERB_CLASS}
      onClick={() => setConfirmingReset(true)}
      disabled={effective.fallback}
      title="Remove every row: the rig goes back to every group then every fixture"
    >
      Show every target
    </Button>
  )

  return (
    <div
      data-rig-band={focus}
      // `data-focus`, beside the older `data-rig-band`: the shape this band is drawn in — Split or
      // Rig, since D17 — for the Rig-focus layout below and for a test to read.
      data-focus={focus}
      className={cn(
        // Its own container, so the verbs' words and the Focus labels fold on the band's width —
        // what the rail or the sheet has taken is the band's business, not the viewport's.
        '@container shrink-0 border-b px-4 pt-2.5 pb-2',
        editing && 'bg-muted/20',
        // Rig focus: the band fills the body and the **rows** scroll, not the band — the controls
        // row carries the Focus control, the way back to Split and Pads, and a scroller that took it
        // along would put the only way back off-screen on any rig taller than the body.
        focus === 'rig' && !editing && 'flex min-h-0 flex-1 flex-col',
      )}
    >
      {/* ── The row ── */}
      {compact ? (
        // The compact boards keep the one row they had: the label, the row chip, the summary, the
        // pill, the desk chip, the verbs menu (or edit mode's reset) and the host's controls;
        // `flex-wrap` as the last resort there, since the row chip and the summary are what give.
        <div data-rig-row="compact" className="mb-2 flex min-h-7 flex-wrap items-center gap-x-2 gap-y-1">
          <EditorLabel>Rig</EditorLabel>
          {!everyRow && effective.rows.length > 1 && (
            <RowChip rows={effective.rows} index={compactIndex} onSelect={setCompactRow} />
          )}
          {editing ? (
            <span className="min-w-[6rem] flex-1 truncate text-[11px] text-muted-foreground">
              Editing · drag targets from the palette, rows reorder by their grip
            </span>
          ) : (
            <span className="min-w-[6rem] flex-1 truncate text-[11px] text-muted-foreground" title={summary}>
              {summary}
            </span>
          )}
          <FamilyPill families={families} />
          <BlindPill wordClass={COMPACT_FOCUS_WORD_CLASS} />
          {!editing && <DeskChip showSubject forcedBy={focus === 'rig' ? 'rig' : undefined} />}
          {!editing && (
            <CompactVerbs
              onSubselect={onSubselect}
              onClear={onClear}
              canClear={selected.length > 0}
              verbs={verbs}
            />
          )}
          {editing && resetButton}
          {controls}
        </div>
      ) : (
        // **One row, the same order in Split and Rig** (busk-chrome plan D13, D17): the label, the
        // Cells menu and its steps, the four verbs; then the family pill, the blind pill and the desk chip
        // *left-anchored after the verbs*; the gap; the Focus control and *Edit layout* / *Done*
        // right-anchored. No summary: the lit tiles say it, and in Pads — where nothing would — the
        // pad row carries it. Two groups rather than one flat list, so the floor can break the row
        // at exactly one place.
        <div data-rig-row="desk" className={cn('mb-2 flex min-h-7 items-center gap-x-2 gap-y-1', TWO_ROWS_CLASS)}>
          <div data-rig-row-verbs className={cn('flex shrink-0 items-center gap-2', FIRST_ROW_CLASS)}>
            <EditorLabel className={RIG_LABEL_CLASS}>Rig</EditorLabel>
            {!editing && (
              <>
                <CellsMenu onSubselect={onSubselect} />
                <StepButtons onSubselect={onSubselect} />
              </>
            )}
            {editing ? (
              resetButton
            ) : (
              <>
                {/* The three selection verbs, the pad row's too (`selectionVerbs.tsx`), and Clear
                    after them — the one verb that releases a selection made on these tiles. */}
                <SelectionVerbButtons verbs={verbs} wordClass={VERB_WORD_CLASS} />
                <Button
                  variant="outline"
                  size="sm"
                  className={VERB_CLASS}
                  onClick={onClear}
                  disabled={selected.length === 0}
                  aria-label="Clear"
                  title="Clear the selection"
                >
                  <X className="size-3.5" />
                  <span className={VERB_WORD_CLASS}>Clear</span>
                </Button>
              </>
            )}
          </div>
          <div data-rig-row-state className={cn('flex min-w-0 flex-1 items-center gap-2', SECOND_ROW_CLASS)}>
            <FamilyPill families={families} />
            {/* Blind, beside the mask and drawn on the same terms — only while it holds
                (`BlindMarks.tsx`): the pads' own row is where a press that reaches nothing is made. */}
            <BlindPill wordClass={BLIND_WORD_CLASS} />
            {/* `showSubject`: the pad row below carries the same pill for the page, and two bare
                chips a row apart would be worse than either alone. The link badge while following
                (desk-follow D8). */}
            {!editing && (
              <DeskChip
                showSubject
                forcedBy={focus === 'rig' ? 'rig' : undefined}
                subjectClass={CHIP_SUBJECT_CLASS}
                className="min-w-0 shrink"
              />
            )}
            {editing ? (
              <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground">
                Editing · drag targets from the palette, rows reorder by their grip
              </span>
            ) : (
              <span className="flex-1" />
            )}
            {controls}
          </div>
        </div>
      )}

      {/* ── Rows ── */}
      {nothingToShow && !editing ? (
        <p className="py-4 text-center text-sm text-muted-foreground">No fixtures or groups configured</p>
      ) : (
        <div
          ref={rowsRef}
          data-rig-rows
          className={cn(
            // Twelve tracks, the page's own grid: a row is `span <width>`, so two half-width rows
            // share a line. Compact and stacked rows are one to a line whatever their width.
            // `content-start` in every shape: the handle's drag extends the grid below its last
            // line, and stretched tracks would grow the tiles instead of leaving the room empty.
            'grid grid-cols-12 content-start gap-x-3 gap-y-1.5',
            editing && effective.fallback && 'opacity-60',
            focus === 'rig' && !editing && 'min-h-0 flex-1 overflow-y-auto',
            // The desk board's Split: a scroller at the handle's height (the layout effect above).
            // `scrollbar-gutter: stable` so the rows' width is the same whether or not a scrollbar
            // is showing: otherwise a width-taking scrollbar (Windows, Linux) can reflow a `WRAP`
            // row as the grid's height crosses its content's, and with no height chosen the
            // default's measurement would chase that reflow — taller with the bar, shorter
            // without — for as long as the two disagreed. Overlay scrollbars ignore it.
            splitScroller && 'min-h-0 overflow-y-auto [scrollbar-gutter:stable]',
          )}
        >
          {editing && effective.fallback && effective.rows.length > 0 && (
            <p className="col-span-12 text-[11px] text-muted-foreground">
              Showing every target — drop a target below to start building a rig.
            </p>
          )}
          {visibleLines.map((line, lineIndex) =>
            line.map((index) => {
              const row = effective.rows[index]
              return (
                <RigRow
                  key={row.uuid ?? row.localKey ?? `row-${index}`}
                  row={row}
                  index={index}
                  line={lineIndex}
                  span={compact || (stackRows && focus === 'rig' && !editing) ? 12 : rowWidth(row)}
                  inDocument={!effective.fallback}
                  editing={editing}
                  compact={compact}
                  stacked={stackRows && focus === 'rig' && !editing}
                  lookup={lookup}
                  presenceOf={presenceOf}
                  wholeSelected={wholeSelected}
                  selectedCells={selectedCells}
                  onPress={press}
                  onPressCell={onToggle}
                  onPlace={placeHeld}
                />
              )
            }),
          )}
        </div>
      )}

      {editing && (
        <>
          {draggingRow && (
            <RowGap index={effective.fallback ? 0 : effective.rows.length} />
          )}
          <NewRowZone disabled={draggingRow || foreign} />
        </>
      )}

      {/* ── The handle ── */}
      {/* The grip under the rows, on the desk board's Split only: not in edit mode, which forces
          Split for its duration and shows every row unclipped; not on the compact boards, where the
          segmented control is the route; and not in Rig, where the folded page strip's chevron is
          the way back. Mounted **before** the height is measured — its 16px box is part of what the
          page body is measured against, and a ceiling measured without it was 16px too generous on
          every first paint, leaving the page 104 at the bottom of a drag. The floor stands in for
          the value on that one unpainted frame; the layout effect re-renders before paint. */}
      {splitScroller && (
        <RigHandle height={rigHeight ?? RIG_MIN_HEIGHT_PX} max={capacity} rowsRef={rowsRef} onDragging={setDragging} />
      )}

      {/* Confirmed, like the page delete: this takes a whole arrangement away and the rig write
          has no undo. */}
      <AlertDialog open={confirmingReset} onOpenChange={setConfirmingReset}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Show every target?</AlertDialogTitle>
            <AlertDialogDescription>
              The rows you built are removed, and the rig goes back to every group then every
              fixture. The groups and fixtures themselves are not touched.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setConfirmingReset(false)
                commit(() => ({ rows: [] }))
              }}
            >
              Show every target
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

/**
 * What a held record would put on the rig, or null.
 *
 * Null for all three kinds the hand can hold today: a rig row takes a group, a fixture or a cell,
 * and the hand's wire (`hand.pickUp {kind, id}`, `HeldRecord.kind` a `BuskPadKind`) holds only a
 * template, a Look or a cue. The seam is here so that the day the desk can hold a group, this is
 * the one function to teach — the strip, the place and the Undo are already wired above it.
 */
function rigRecordOf(held: HeldRecord): RigPaletteRecord | null {
  void held
  return null
}

/** The selection's attribute mask, drawn only while one is set (D14): absent for a plain selection. */
function FamilyPill({ families }: { families: AttributeFamily[] | null }) {
  if (families == null || families.length === 0) return null
  return (
    <Badge
      variant="outline"
      data-rig-family
      className="shrink-0 whitespace-nowrap border-primary/40 bg-primary/10 px-2 py-0 text-[10px] text-primary"
    >
      {formatFamilyList(families, ' · ')}
    </Badge>
  )
}

// ─── The Cells menu and the steps ────────────────────────────────────────

/**
 * The Cells menu (D12, revised 2026-09-21): *Cells: <last>* opening the seven filters. `last` is
 * the filter pressed here most recently and nothing more — the desk keeps no sub-selection, so
 * there is nothing to read one back from. The two steps are `StepButtons`, beside it.
 */
function CellsMenu({ onSubselect }: { onSubselect: (mode: SubselectMode) => void }) {
  const [last, setLast] = useState<SubselectMode>('ALL')
  const press = (mode: SubselectMode) => {
    setLast(mode)
    onSubselect(mode)
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          data-cells-chip
          aria-label={`Cells: ${SUBSELECT_MODE_LABELS[last]}`}
          title={`Sub-selection over rig order — ${SUBSELECT_FILTER_MODES.map((mode) => SUBSELECT_MODE_LABELS[mode]).join(' · ')}`}
          className={cn(VERB_CLASS, 'gap-1')}
        >
          <Grid2x2 className="size-3.5" />
          {/* Never a bare glyph: the prefix folds, the mode word stays — in its short form once
              the prefix has gone (`1st`, `Masters`), its full form beside it (`1st half`). */}
          <span className={CELLS_PREFIX_CLASS}>Cells: </span>
          <span className={CELLS_FULL_CLASS}>{SUBSELECT_MODE_LABELS[last]}</span>
          <span data-cells-mode className={CELLS_SHORT_CLASS}>{CELLS_SHORT_LABELS[last]}</span>
          <ChevronDown className="size-3 text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">Cells</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={last} onValueChange={(mode) => press(mode as SubselectMode)}>
          {SUBSELECT_FILTER_MODES.map((mode) => (
            <DropdownMenuRadioItem key={mode} value={mode} title={CELLS_MODE_TITLES[mode]}>
              {SUBSELECT_MODE_LABELS[mode]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const STEP_LABELS: Partial<Record<SubselectMode, string>> = {
  PREV: 'Previous along the rig',
  NEXT: 'Next along the rig',
}

/** *Prev* / *Next*: a step along the rig order, which moves the selection rather than filtering it. */
function StepButtons({ onSubselect }: { onSubselect: (mode: SubselectMode) => void }) {
  return (
    <div className="flex shrink-0 items-center gap-0.5" role="group" aria-label="Step the selection along the rig">
      {SUBSELECT_STEP_MODES.map((mode) => (
        <Button
          key={mode}
          variant="outline"
          size="sm"
          data-cells-step={mode}
          aria-label={STEP_LABELS[mode]}
          title={CELLS_MODE_TITLES[mode]}
          className="h-7 w-7 px-0"
          onClick={() => onSubselect(mode)}
        >
          {mode === 'PREV' ? <ChevronLeft className="size-3.5" /> : <ChevronRight className="size-3.5" />}
        </Button>
      ))}
    </div>
  )
}

const CELLS_MODE_TITLES: Partial<Record<SubselectMode, string>> = {
  ALL: 'Every selected cell widened to its whole fixture',
  ODD: 'Every other unit in rig order, from the first — cells where a selected head has them',
  EVEN: 'Every other unit in rig order, from the second',
  FIRST_HALF: 'The first half of the selection in rig order',
  SECOND_HALF: 'The second half of the selection in rig order',
  INVERT: 'Every unit of the selected heads that is not selected',
  MASTERS: 'The masters only: a multi-head fixture’s own channels, no cell',
  NEXT: 'The whole selection one step along rig order; one cell when only cells are selected',
  PREV: 'The whole selection one step back along rig order',
}

/**
 * Below `md` the compact row has no room for a second control: the filters, the steps and the
 * verbs sit in one menu. **Module-level, not a closure inside the band**: a component declared during a
 * render is a new element type per render, so React remounted it — and closed its open menu —
 * on every `selection.state` frame, locate push or rig refetch while the operator had it open.
 */
function CompactVerbs({
  onSubselect,
  onClear,
  canClear,
  verbs,
}: {
  onSubselect: (mode: SubselectMode) => void
  onClear: () => void
  canClear: boolean
  verbs: SelectionVerbs
}) {
  return (
    <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" className="h-7 px-1.5" aria-label="Selection verbs">
                <MoreHorizontal className="size-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {/* Below `md` the menu's filters and the two steps sit here: the compact row has no
                  room for a second control. */}
              <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">Cells</DropdownMenuLabel>
              {SUBSELECT_FILTER_MODES.map((mode) => (
                <DropdownMenuItem key={mode} onSelect={() => onSubselect(mode)}>
                  {SUBSELECT_MODE_LABELS[mode]}
                </DropdownMenuItem>
              ))}
              <DropdownMenuSeparator />
              {SUBSELECT_STEP_MODES.map((mode) => (
                <DropdownMenuItem key={mode} onSelect={() => onSubselect(mode)} title={CELLS_MODE_TITLES[mode]}>
                  {STEP_LABELS[mode] ?? SUBSELECT_MODE_LABELS[mode]}
                </DropdownMenuItem>
              ))}
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={verbs.spread} title="Spread a value across the selection">
                Spread…
              </DropdownMenuItem>
              <DropdownMenuItem disabled={!verbs.locate.enabled} onSelect={verbs.locate.press}>
                {verbs.locate.label}
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem disabled={!canClear} onSelect={onClear}>
                Clear
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
  )
}

function RowChip({
  rows,
  index,
  onSelect,
}: {
  rows: BuskRigRow[]
  index: number
  onSelect: (index: number) => void
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="inline-flex h-6 shrink-0 items-center gap-1 rounded-full border bg-card px-2 text-[11px] font-medium"
          aria-label={`Row: ${rows[index]?.name ?? ''}`}
        >
          <span className="max-w-[9rem] truncate">{rows[index]?.name}</span>
          <ChevronDown className="size-3 text-muted-foreground" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuRadioGroup value={String(index)} onValueChange={(value) => onSelect(Number(value))}>
          {rows.map((row, i) => (
            <DropdownMenuRadioItem key={row.uuid ?? row.localKey ?? i} value={String(i)}>
              {row.name}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// ─── A row ───────────────────────────────────────────────────────────────

/** The row body per flow: the sideways line it always was, a wrapping grid of tiles, or one tile per line. */
function rowBodyClass(flow: BuskFlow, stacked: boolean): string {
  if (stacked) return 'grid grid-cols-2 gap-2 pb-1'
  switch (flow) {
    case 'WRAP':
      return 'flex flex-wrap gap-2 pb-1'
    case 'COLUMN':
      return 'flex flex-col gap-2 pb-1'
    case 'SCROLL':
      return 'flex gap-2 overflow-x-auto pb-1'
  }
}

function RigRow({
  row,
  index,
  line,
  span,
  inDocument,
  editing,
  compact,
  stacked,
  lookup,
  presenceOf,
  wholeSelected,
  selectedCells,
  onPress,
  onPressCell,
  onPlace,
}: {
  row: BuskRigRow
  index: number
  /** Which line of the band's grid this row is on (`rigLines`), for the handle's measurement. */
  line: number
  /** The row's grid span in twelfths — its width, or 12 where rows are one to a line. */
  span: number
  /** False for a show-all fallback row, which nothing can address. */
  inDocument: boolean
  editing: boolean
  compact: boolean
  /** Rig focus below `md`: the tiles in a two-column grid that scrolls with the band, not a sideways row. */
  stacked: boolean
  lookup: TileLookup
  presenceOf: (tile: RenderTile) => EffectPresence
  wholeSelected: (tile: RenderTile) => boolean
  selectedCells: ReadonlySet<string>
  onPress: (tile: RenderTile) => void
  onPressCell: (target: CueTarget) => void
  onPlace: (row: number, rowName: string, held: HeldRecord) => void
}) {
  const { source, target, foreign, commit } = useRigEdit()
  const editable = editing && inDocument
  const draggingRow = source?.type === 'rig-row'
  const tiles = rowTiles(row)
  const flow = rowFlow(row)

  const { attributes, listeners, setNodeRef: setRowRef, isDragging } = useDraggable({
    id: rigRowId(index),
    data: { type: 'rig-row', row: index, name: row.name, tileCount: tiles.length } satisfies RigRowDragData,
    disabled: !editable,
  })
  const { setNodeRef: setBodyRef, isOver } = useDroppable({
    id: rigRowBodyId(index),
    data: {
      type: 'rig-drop',
      target: { kind: 'tile', at: { row: index, tile: tiles.length } },
      depth: RIG_DROP_DEPTH.rowBody,
    } satisfies RigDropData,
    disabled: !editable || draggingRow || foreign,
  })

  const slotIndex =
    editable && !draggingRow && target?.kind === 'tile' && target.at.row === index ? target.at.tile : null

  const cells: React.ReactNode[] = []
  tiles.forEach((stored, tileIndex) => {
    if (slotIndex === tileIndex) cells.push(<RigDropSlot key="drop-slot" compact={compact} />)
    const at: RigTileAddress = { row: index, tile: tileIndex }
    const key = tileKeyOf(stored, at)
    for (const rendered of expandTile(stored, key)) {
      cells.push(
        <RigTile
          key={rendered.key}
          tile={rendered}
          at={inDocument ? at : null}
          stored={inDocument ? stored : null}
          presence={presenceOf(rendered)}
          wholeSelected={wholeSelected(rendered)}
          selectedCells={selectedCells}
          editing={editing}
          compact={compact}
          lookup={lookup}
          onPress={() => onPress(rendered)}
          onPressCell={(element) => onPressCell({ type: 'fixture', key: element.key })}
          onRemove={() => commit((rig) => removeTile(rig, at))}
          onSetMode={(mode: BuskRigCellMode, split?: number) =>
            commit((rig) => setTile(rig, at, { cellMode: mode, cellSplit: split ?? null }))
          }
          onRename={(label) => commit((rig) => relabelTile(rig, at, label))}
        />,
      )
    }
  })
  if (slotIndex === tiles.length) cells.push(<RigDropSlot key="drop-slot" compact={compact} />)

  const style = { gridColumn: `span ${span} / span ${span}` } as CSSProperties

  return (
    <>
      {editing && draggingRow && !isDragging && inDocument && <RowGap index={index} />}
      <div
        ref={setRowRef}
        data-rig-line={line}
        data-rig-row-flow={flow}
        style={style}
        className={cn('flex min-w-0 flex-col gap-1', isDragging && 'opacity-40')}
      >
        <div className="flex min-h-5 items-center gap-2">
          {editable && (
            <button
              type="button"
              {...attributes}
              {...listeners}
              aria-label={`Reorder ${row.name || 'row'}`}
              className="shrink-0 cursor-grab touch-none text-muted-foreground"
            >
              <GripVertical className="size-3.5" />
            </button>
          )}
          {editable ? (
            <NameField
              value={row.name}
              label="Row name"
              placeholder="Row"
              onSave={(name) => commit((rig) => renameRow(rig, index, name))}
              className="min-w-[6rem] max-w-[16rem] flex-1"
            />
          ) : (
            <span className="truncate text-[11px] font-semibold text-muted-foreground">{row.name}</span>
          )}
          {editable && (
            <RowMenu
              row={row}
              onLayout={(patch) => commit((rig) => setRowLayout(rig, index, patch))}
              onRemove={() => commit((rig) => removeRow(rig, index))}
            />
          )}
        </div>
        <div
          ref={setBodyRef}
          data-rig-row-body={stacked ? 'stacked' : flow.toLowerCase()}
          className={cn(
            rowBodyClass(flow, stacked),
            isOver && editable && !draggingRow && !foreign && 'rounded-lg bg-primary/5 ring-1 ring-inset ring-primary/40',
          )}
        >
          {cells}
        </div>
        {editable && (
          <HandPlaceStrip
            target="rig-row"
            where={row.name}
            amongDropTargets
            onPlace={(held) => onPlace(index, row.name, held)}
          />
        )}
      </div>
    </>
  )
}

/**
 * The row's menu while editing — the bank's own, on the row: its **width** share, its **flow**,
 * and *Remove row* last. The width and flow are the row's rather than a column's here, because a
 * rig has no columns: a row *is* the box, and its width says how much of a line it takes.
 */
function RowMenu({
  row,
  onLayout,
  onRemove,
}: {
  row: BuskRigRow
  onLayout: (patch: { flow?: BuskFlow; width?: number }) => void
  onRemove: () => void
}) {
  const width = rowWidth(row)
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label={`Options for row ${row.name || 'row'}`}
          className="ml-auto shrink-0 text-muted-foreground hover:text-foreground"
        >
          <MoreHorizontal className="size-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">Row width</DropdownMenuLabel>
        <div className="flex gap-0.5 px-1 pb-1" role="group" aria-label="Row width">
          {BUSK_WIDTHS.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={option === width}
              onClick={() => onLayout({ width: option })}
              className={cn('rounded px-2 py-1 text-xs hover:bg-accent', option === width && 'bg-muted font-semibold')}
            >
              {BUSK_WIDTH_LABELS[option]}
            </button>
          ))}
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup value={rowFlow(row)} onValueChange={(flow) => onLayout({ flow: flow as BuskFlow })}>
          {BUSK_FLOWS.map((flow) => (
            <DropdownMenuRadioItem key={flow} value={flow}>
              Flow: {BUSK_FLOW_LABELS[flow]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive" onSelect={onRemove}>
          Remove row
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** The strip a lifted row lands on — before row `index`, or after the last for `rows.length`. */
function RowGap({ index }: { index: number }) {
  const { setNodeRef, isOver } = useDroppable({
    id: rigRowGapId(index),
    data: { type: 'rig-drop', target: { kind: 'row-gap', row: index }, depth: RIG_DROP_DEPTH.rowGap } satisfies RigDropData,
  })
  return (
    <div
      ref={setNodeRef}
      className={cn(
        // A whole line of the band's grid, whatever the rows around it span.
        'col-span-12 grid h-[22px] place-items-center rounded-lg border-2 border-dashed text-[10px] font-semibold transition-colors',
        isOver ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground',
      )}
    >
      move row here
    </div>
  )
}

/**
 * Where a tile starts a new row. It **is** `+ Row`: the server refuses an empty row, so a row
 * cannot be minted and then filled — it is minted *by* the first thing dropped into it.
 */
function NewRowZone({ disabled }: { disabled: boolean }) {
  const { setNodeRef, isOver } = useDroppable({
    id: RIG_NEW_ROW_ID,
    data: { type: 'rig-drop', target: { kind: 'new-row' }, depth: RIG_DROP_DEPTH.newRow } satisfies RigDropData,
    disabled,
  })
  return (
    <div
      ref={setNodeRef}
      data-rig-new-row
      className={cn(
        'mt-1.5 grid h-10 place-items-center rounded-lg border-2 border-dashed text-[11px] font-medium transition-colors',
        disabled ? 'border-border/50 text-muted-foreground/50' : isOver ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground',
      )}
    >
      + Row · drop a target here to start a new row
    </div>
  )
}

/**
 * Registered at module scope, so `Layout.tsx`'s one overlay draws a lifted rig tile, row or
 * palette target without the app shell importing the band. Frozen and hookless, by the overlay's
 * rule.
 */
registerDragOverlay((active) => {
  const data = rigDragData(active)
  if (data == null) return null
  if (data.type === 'rig-row') {
    return (
      <div
        className="w-[150px] rounded-lg border border-primary bg-card p-2.5 opacity-90 shadow-lg"
        style={{ transform: 'rotate(-2deg)' }}
      >
        <div className="truncate text-[11px] font-semibold">{data.name || 'Row'}</div>
        <div className="mt-1 text-[10px] text-muted-foreground">
          {data.tileCount} {data.tileCount === 1 ? 'tile' : 'tiles'}
        </div>
      </div>
    )
  }
  return (
    <div
      className="flex h-13 w-[148px] items-center rounded-lg border border-primary bg-card px-3.5 text-sm opacity-90 shadow-lg"
      style={{ transform: 'rotate(-2deg)' }}
    >
      <span className="truncate">{data.name}</span>
    </div>
  )
})
