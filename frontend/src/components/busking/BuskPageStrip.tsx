import { useEffect, useState, type ReactNode } from 'react'
import { useDndMonitor, useDraggable, useDroppable, type DragEndEvent } from '@dnd-kit/core'
import { useSelector } from 'react-redux'
import { ChevronUp, MoreHorizontal, Pencil } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
import { selectSaveStatus } from '@/store/saveStatusSlice'
import type { BuskPage } from '@/api/buskApi'
import { allBanks, buskPageTabId, parseBuskDragId } from '@/lib/buskLayout'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { Badge } from '@/components/ui/badge'
import { DeskChip } from '@/components/desk/DeskChip'
import { EditorLabel } from '../editor/EditorLabel'
import { BuskPageChip } from './BuskPageChip'
import { BlindPill } from './BlindMarks'
import { summariseSelection, type BuskingTarget } from './buskingTypes'
import { SelectionVerbButtons, type SelectionVerbs } from './selectionVerbs'

/**
 * The **pad row**: the page tabs, and — in Pads, on the desk board — everything the body's top row
 * has to carry when the rig row is not drawn (busk-chrome plan D17).
 *
 * **In Split and Rig it is the tabs and the page mark**, under the rig band; in **Pads** it is the
 * body's top row, and the row is: the `PADS` label, the page tabs at the rig row's control size
 * (28px, `text-xs`, the Focus control's own segmented look — they were larger and rounder than
 * everything beside them) with the **page mark** beside them (desk-follow plan D7: the link badge
 * while paged with the desk, naming any other window paged with it; *Page: Own* while not), the
 * three selection verbs — *Spread…* · Locate · Highlight, the band's
 * three with the band's handlers, handed down by `BuskingView` as [pads] — the selection summary
 * in the gap (nothing else says it in Pads: the tiles are off screen), the family pill while a mask
 * is set, the selection's **link badge** (desk-follow plan D8 — Pads always follows the desk
 * selection, D2, so here the desk chip is only ever the badge; the chip's dashed form is kept for
 * the moment between a local window entering Pads and the D3 relink) — then the host's Focus control and *Edit layout* / *Done* right-anchored. **No Cells menu, no steps, no Clear**: those act on a selection
 * made on the tiles, and the use this view is built for is a two-screen desk — the rig on one
 * screen and the pads on the other — so a rig operation is reached on the rig screen. Spread,
 * Locate and Highlight act on the rig from either, and Spread is arguably the pads' own. There is
 * no chevron pill back to Split: the Focus control is on the row.
 *
 * **A project with no pages gets no create button here.** `BuskFirstOpen` owns that moment, and it
 * offers the two starting points the plan settled on; a bare *+ Page* beside it would be a second
 * affordance for the same thing that skipped the starter layout entirely.
 *
 * *Saved* is read off the show-wide save counters rather than any state of its own: a layout write
 * is an ordinary save, so it already reports there (see `NON_SAVE_ENDPOINTS`, which the *press*
 * joins and the layout write deliberately does not).
 *
 * **`controls` is whatever the host wants at the strip's end**, handed in by `BuskingView` rather
 * than mounted here — the *Sheet* button off the desk board, on the short board's merged row the
 * Focus control and the edit toggle, and on the desk board in Pads those same two, which in Split
 * and Rig live on the rig band's one row. [EditLayoutToggle] is exported from here so the button
 * keeps its rules (disabled with no pages; *Done* drawn on every board) wherever it is mounted.
 *
 * **Folded, it is the board's 40px strip and not the tab strip drawn folded** (`Phones.dc.html`
 * note 8, busk-further plan §11): the page's name, its bank count and the controls. Rig focus
 * exists to give the band the height, and the full tab strip wraps on a phone and takes it back. A
 * page is chosen in Split; the fold says which one this window is on, and the band's grip above it
 * is the tap back.
 */

/**
 * **The pad row's folds** (D15's shape, D19's re-expansion, D20's label — measured for this row,
 * not the rig row's). The strip is its own `@container`; in Pads the row gives up, in order: the
 * page badge's names and the page chip's *Page:* ([PAD_PAGE_NAMES_CLASS], [PAD_PAGE_SUBJECT_CLASS]
 * — desk-follow plan D7, D9: the names fold before any control moves, and the glyph never does);
 * the verbs' words and *Edit layout*'s ([PAD_VERB_WORD_CLASS], [PAD_EDIT_WORD_CLASS]); the Focus
 * words and the desk chip's *Targets:* ([PAD_FOCUS_WORD_CLASS], [PAD_CHIP_SUBJECT_CLASS]); the
 * `PADS` label ([PAD_LABEL_CLASS]); the summary truncates throughout (`min-w-0`, its whole text on
 * the title); and below the floor the row is **two rows by design** — the label, the tabs, the
 * page mark and the verbs on the first, the summary, the pill, the desk chip and the host's
 * controls on the second — where the words come back while each line holds them and fold again at
 * a second rung, as stacked `@min-[…]:@max-[floor]:` ranges that overlap no rung above. In Split
 * the row wraps as it always did (`flex-wrap`, for edit mode's name field), the label is not
 * drawn, and none of this applies but the page mark's own two rungs ([SPLIT_PAGE_NAMES_CLASS],
 * [SPLIT_PAGE_SUBJECT_CLASS]).
 *
 * **The `@container` is a wrapper, and the row is its child.** A query container is the nearest
 * *ancestor* container and an element is never its own, so a floor class on the element that
 * declares the container has no container to match and never fires — while the classes on its
 * children do. The first cut put both on one element: below 680 the first group took `w-full`
 * inside a row that never wrapped and pushed the Focus control and *Edit layout* out of the row,
 * under the docked sheet, unclickable. The band has the same shape for the same reason
 * (`RigBand.tsx`: `@container` on the band, the floor on the row), and
 * `BuskPageStrip.test.tsx` pins the structure, since jsdom evaluates no container query. The 12px
 * gutter is the wrapper's, not the row's, so the container's content box is the row's and every
 * rung above is a number on that box — as the band's are, whose `px-4` is the band's.
 *
 * **The numbers are the app's, re-measured in the browser on 2026-09-23 for the page mark**
 * (desk-follow plan D7) on the dev rig's two pages (*Page 1* · *S5 Check Page*, 167px of tabs —
 * the tabs have no ceiling, and a longer name moves every rung by its width), a one-family pill
 * (~60), the selection's link badge (21 — Pads always follows the desk selection, D2), and **150px
 * kept for the summary**, which is `min-w-0` and truncates but is what Pads has instead of tiles.
 * The page mark is budgeted at its **wider** resting form, because both are ordinary states: the
 * link badge is 21 as a glyph and up to 136 with a name (the name is capped at 96px, `LinkBadge`),
 * and *Page: Own* is 91 worded and **57** as *Own*. Worded with the glyph-or-*Own* mark, the fixed
 * content is 969 (`PADS` 29, the tabs 167, the mark 57, the verbs 93 · 81 · 94, the pill 60, the
 * badge 21, Focus 183, *Edit layout* 103, ten 8px gaps), 1119 with the summary, so the verbs'
 * words and *Edit layout*'s go at **1120**; with the badge's longest name (136) or the chip's
 * *Page:* (91) it is 1197 and 1153, so those go at **1200**. Iconic (36 each) it is 891, so the
 * Focus words and the desk chip's subject go at **900**; 806 with those gone, so the label (29 +
 * a gap) goes at **810**, and without it 769, so the floor is **770**. Under it the first line —
 * tabs, mark, verbs — is 489 worded following and 525 with *Own*, so the verbs' words return from
 * **530**; 559 with *Page: Own*, so its subject returns from **560**; and 603 with the longest
 * name, so the names return from **610**. The state line is unchanged by the mark: 399 worded
 * beside the summary's 150, so *Edit layout*'s word returns from **560** and the Focus words and
 * the subject, with it iconic, from **490**. Checked in the browser, in both page states, with the
 * strip forced to each rung and one below it, a 60px stand-in for the pill and the badge's name
 * forced to its cap: no overflow, neither line wraps, and the summary keeps its 150 at every one.
 * They were 1070 / 830 / 750 / 710 before the page mark, and 1040 / 800 / 720 / 680 before the
 * selection's badge.
 *
 * **The blind pill is not in those numbers** (`BlindPill`, `BlindMarks.tsx`: 63px worded, 26
 * iconic, drawn only while the programmer is blind, its word on the Focus words' rung here). This
 * row can afford that where the rig row cannot: the summary is `min-w-0 flex-1 truncate` and its
 * 150 is a reservation, not a floor, so the pill comes out of the summary's width — 71 worded
 * between 900 and 1120, 34 iconic below — and never moves a control. The pill is `min-w-0 shrink`
 * besides, the same last resort as on the rig row.
 */
export const PAD_ROW_FLOOR_PX = 770

// Literals, never built from the floor: Tailwind's scanner finds candidates in the source text,
// and a variant assembled at runtime is generated for nothing (`RigBand.tsx`, `RIG_ROW_FLOOR_PX`).
// `BuskPageStrip.test.tsx` pins each literal's floor against the constant.
export const PAD_VERB_WORD_CLASS = 'hidden @[1120px]:inline @min-[530px]:@max-[770px]:inline'
export const PAD_EDIT_WORD_CLASS = 'hidden @[1120px]:inline @min-[560px]:@max-[770px]:inline'
export const PAD_FOCUS_WORD_CLASS = 'hidden @[900px]:inline @min-[490px]:@max-[770px]:inline'
export const PAD_CHIP_SUBJECT_CLASS = 'hidden @[900px]:inline @min-[490px]:@max-[770px]:inline'
/** The `PADS` label: folded to nothing below 810, the rung before the floor (D20). */
export const PAD_LABEL_CLASS = 'hidden @[810px]:block'
export const PAD_TWO_ROWS_CLASS = '@max-[770px]:flex-wrap'
export const PAD_SECOND_ROW_CLASS = '@max-[770px]:basis-full'
export const PAD_FIRST_ROW_CLASS = '@max-[770px]:w-full @max-[770px]:flex-wrap'

/**
 * The page mark's folds (desk-follow plan D7, D9) — the badge's names and the chip's *Page:* — on
 * each row that carries it. On the pad row in Pads they are the ladder's first rung (above). The
 * other three were measured the same way on 2026-09-23, with the badge's name forced to its 96px
 * cap, in both page states:
 *
 * - **Split** has no ladder, but its line holds the tabs, the mark and, off the desk board, the
 *   *Sheet* button (76): 402 with the longest name and 350 with *Page: Own*, so the names show
 *   from **410** and the subject from **360** — a phone's row is 343, and there it reads *Own*.
 * - **The short board's merged row** is 32px and never wraps, with the summary as its one flexible
 *   item and no reservation for it: the names show from **820** and the chip's subject from **760**,
 *   where the summary still keeps ~70–90px; nothing overflows down to the board's 672px floor.
 * - **Rig focus's folded strip** has the page's name as the part that gives (the mark there does
 *   not shrink): the names from **440**, the subject from **400**, and the page's name keeps its
 *   width down to 300.
 */
/** The page badge's names on the pad row in Pads: the first thing to go. */
export const PAD_PAGE_NAMES_CLASS = 'hidden @[1200px]:inline @min-[610px]:@max-[770px]:inline'
/** The page chip's *Page:* on the pad row in Pads, where it sits beside the tabs: with the names above the floor. */
export const PAD_PAGE_SUBJECT_CLASS = 'hidden @[1200px]:inline @min-[560px]:@max-[770px]:inline'
/** In Split the pad row has no ladder, but its line holds the tabs, the mark and the *Sheet* button off the desk board. */
export const SPLIT_PAGE_NAMES_CLASS = 'hidden @[410px]:inline'
export const SPLIT_PAGE_SUBJECT_CLASS = 'hidden @[360px]:inline'
/** The short board's merged row: the names, then the chip's *Page:*, before its Focus words. */
export const MERGED_PAGE_NAMES_CLASS = 'hidden @[820px]:inline'
export const MERGED_PAGE_SUBJECT_CLASS = 'hidden @[760px]:inline'
/** Rig focus's folded page strip: the names first, then the chip's *Page:*. */
export const FOLDED_PAGE_NAMES_CLASS = 'hidden @[440px]:inline'
export const FOLDED_PAGE_SUBJECT_CLASS = 'hidden @[400px]:inline'

/** What the pad row carries in Pads on the desk board, beyond the tabs (D17). */
export interface PadRowSelection {
  selectedTargets: Map<string, BuskingTarget>
  families: AttributeFamily[] | null
  verbs: SelectionVerbs
}

function PageTab({
  page,
  index,
  active,
  editing,
  dense,
  onSelect,
}: {
  page: BuskPage
  index: number
  active: boolean
  editing: boolean
  dense: boolean
  onSelect: () => void
}) {
  const id = buskPageTabId(index)
  const { attributes, listeners, setNodeRef: setDragRef, isDragging } = useDraggable({
    id,
    data: { type: 'busk-page-tab', index, pageId: page.id, name: page.name },
    disabled: !editing,
  })
  const { setNodeRef: setDropRef, isOver } = useDroppable({ id, disabled: !editing })

  return (
    <button
      ref={(node) => {
        setDragRef(node)
        setDropRef(node)
      }}
      type="button"
      {...(editing ? attributes : {})}
      {...(editing ? listeners : {})}
      onClick={onSelect}
      aria-current={active ? 'page' : undefined}
      className={cn(
        // The rig row's control size (D17): a 24px item in a 28px group, `text-xs`, the Focus
        // control's segmented look. The merged row keeps its own, smaller, form.
        'font-semibold transition-colors',
        dense ? 'rounded-lg px-2.5 py-0.5 text-xs' : 'h-6 rounded-md px-2 text-xs',
        active ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
        editing && 'cursor-grab touch-none',
        isDragging && 'opacity-40',
        isOver && !isDragging && 'ring-1 ring-primary',
      )}
    >
      {page.name}
    </button>
  )
}

export interface BuskPageStripProps {
  pages: BuskPage[]
  activePageId: number | null
  editing: boolean
  onSelect: (pageId: number) => void
  onCreate: (name: string) => Promise<unknown>
  onRename: (name: string) => Promise<unknown>
  onDelete: () => void
  /** Every page id, in the order wanted — the reorder route takes nothing less. */
  onReorder: (pageIds: number[]) => void
  /**
   * Drawn beside *Edit layout* / *Done*: the sheet button off the desk board, and on the short
   * board's merged row the Focus control — which otherwise lives on the rig's top row, not here.
   */
  controls?: ReactNode
  /** Rig focus: the 40px folded page at the bottom of the body — name, bank count, the chevron back to Split, the controls. */
  folded?: boolean
  /** Folded only: the chevron's press, the way back to Split. */
  onUnfold?: () => void
  /**
   * The short board's merged row (`Phones.dc.html`, landscape): a 32px row with the rig strip's
   * pieces in [leading], the page tabs and the controls after them — one row where the desk board
   * has two, because there are ~350px under the ShowHeader and every row is a row of pads lost.
   */
  dense?: boolean
  /** Drawn before the tabs: the rig strip's pieces on the merged row. */
  leading?: ReactNode
  /**
   * Pads on the desk board: the row is the body's top row and carries the selection — its verbs,
   * its summary, its mask — beside the tabs (D17). Absent in Split and Rig and off the desk board.
   */
  pads?: PadRowSelection
}

/**
 * *Edit layout* / *Done* — the one edit toggle, mounted by the host on the rig band's controls
 * row. The desk board's by decision (`editable`): below `md` the library palette is not shown, and
 * on the short board there is no room for it beside the page — an edit mode with nothing to drag
 * from, or nowhere to drop it, is a trap rather than a feature. `Done` is drawn whatever the board,
 * so a window narrowed mid-edit can still leave the mode.
 */
export function EditLayoutToggle({
  editing,
  editable,
  hasPages,
  onToggle,
  labelClass,
}: {
  editing: boolean
  editable: boolean
  hasPages: boolean
  onToggle: () => void
  /**
   * The word's class — the host's fold. On the rig band's one row it folds with the verbs' words
   * (busk-chrome plan D15, `Band.dc.html`'s 1100 rung draws it as the pencil alone), which is why
   * the button carries its name as `aria-label` and `title` whatever the class hides. *Done* never
   * folds: it is the way out, and short.
   */
  labelClass?: string
}) {
  if (editing) {
    return (
      <Button size="sm" className="h-7 text-xs" onClick={onToggle}>
        Done
      </Button>
    )
  }
  if (!editable) return null
  return (
    <Button
      size="sm"
      variant="outline"
      className="h-7 gap-1.5 px-2 text-xs"
      onClick={onToggle}
      disabled={!hasPages}
      aria-label="Edit layout"
      title={hasPages ? 'Edit layout: arrange the page and the rig' : 'Edit layout — create a page first'}
    >
      <Pencil className="size-3.5" />
      <span className={labelClass}>Edit layout</span>
    </Button>
  )
}

export function BuskPageStrip({
  pages,
  activePageId,
  editing,
  onSelect,
  onCreate,
  onRename,
  onDelete,
  onReorder,
  controls,
  folded = false,
  onUnfold,
  dense = false,
  leading,
  pads,
}: BuskPageStripProps) {
  const { pending, savedTick } = useSelector(selectSaveStatus)
  const [naming, setNaming] = useState<'create' | 'rename' | null>(null)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [justSaved, setJustSaved] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const active = pages.find((page) => page.id === activePageId) ?? null

  useEffect(() => {
    if (savedTick === 0) return
    setJustSaved(true)
    const timer = window.setTimeout(() => setJustSaved(false), 1800)
    return () => window.clearTimeout(timer)
  }, [savedTick])

  useEffect(() => {
    if (!editing) setNaming(null)
  }, [editing])

  // A second monitor on the same shared context. Page order is its own route rather than part of
  // the layout document, so it is handled here rather than in `BuskEditProvider`'s drop reducer.
  useDndMonitor({
    onDragEnd(event: DragEndEvent) {
      const data = event.active.data.current
      if (data?.type !== 'busk-page-tab' || event.over == null) return
      const over = parseBuskDragId(String(event.over.id))
      if (over?.kind !== 'page-tab') return
      const from = data.index as number
      if (from === over.index) return
      const ids = pages.map((page) => page.id)
      const [moved] = ids.splice(from, 1)
      ids.splice(over.index, 0, moved)
      onReorder(ids)
    },
  })

  async function submitName() {
    const name = draft.trim()
    if (name.length === 0) return
    try {
      if (naming === 'create') await onCreate(name)
      else await onRename(name)
      setNaming(null)
      setError(null)
    } catch (err) {
      const code = (err as { data?: { code?: string; error?: string } })?.data
      setError(
        code?.code === 'BUSK_PAGE_NAME_TAKEN'
          ? 'A page of that name already exists'
          : (code?.error ?? 'Could not save that name'),
      )
    }
  }

  if (folded) {
    const bankCount = active == null ? 0 : allBanks(active).length
    return (
      // `@container` on a wrapper for the page mark's fold, the open strip's arrangement.
      <div data-busk-page-strip="folded" className="@container h-10 shrink-0 border-t px-4">
      <div className="flex h-full items-center gap-2.5">
        <EditorLabel className="shrink-0 whitespace-nowrap">Pages</EditorLabel>
        <span className="min-w-0 truncate text-[13px] font-semibold">{active?.name ?? '—'}</span>
        <span className="shrink-0 text-[11px] text-muted-foreground">
          {active == null ? '' : bankCount === 0 ? 'No banks' : `${bankCount} ${bankCount === 1 ? 'bank' : 'banks'}`}
        </span>
        {/* The page mark (desk-follow D7): Rig focus still pages with the group, so the fold says
            whether it does, and who with. It does not shrink — the page's name is what gives here. */}
        {pages.length > 0 && (
          <BuskPageChip
            showingPageId={activePageId}
            subjectClass={FOLDED_PAGE_SUBJECT_CLASS}
            namesClass={FOLDED_PAGE_NAMES_CLASS}
          />
        )}
        <div className="flex-1" />
        {/* The way back to Split, pointing the way the page will come. The rig strip carries no
            twin: there the Focus control beside the summary is the way, and a chevron pushed it
            along on that strip only (`RigStrip`). This strip sits at the bottom with nothing to
            line up against, and a press here is a finger's reach from the pads it brings back. */}
        {onUnfold != null && (
          <button
            type="button"
            onClick={onUnfold}
            aria-label="Show the page again: Split"
            title="Show the page again: Split"
            className="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
          >
            <ChevronUp className="size-4" />
          </button>
        )}
        {controls}
      </div>
      </div>
    )
  }

  const tabs = (
    <div
      data-busk-page-tabs
      className={cn(
        'inline-flex shrink-0 items-center gap-0.5 rounded-lg border bg-card',
        dense ? 'p-px' : 'h-7 p-0.5',
      )}
    >
      {pages.map((page, index) => (
        <PageTab
          key={page.id}
          page={page}
          index={index}
          active={page.id === activePageId}
          editing={editing}
          dense={dense}
          onSelect={() => onSelect(page.id)}
        />
      ))}
      {editing && (
        <button
          type="button"
          onClick={() => {
            setDraft('')
            setError(null)
            setNaming('create')
          }}
          className="h-6 rounded-md border border-dashed px-2 text-xs font-semibold text-muted-foreground hover:text-foreground"
        >
          + Page
        </button>
      )}
    </div>
  )

  const nameField = naming != null && (
    <div className="flex items-center gap-1.5">
      <Input
        autoFocus
        value={draft}
        aria-label={naming === 'create' ? 'New page name' : 'Page name'}
        placeholder={naming === 'create' ? 'Page name' : active?.name}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') void submitName()
          if (e.key === 'Escape') setNaming(null)
        }}
        className="h-7 w-40 text-[13px]"
      />
      <Button size="sm" className="h-7" onClick={() => void submitName()}>
        {naming === 'create' ? 'Add' : 'Rename'}
      </Button>
      {error && <span className="text-[11px] text-destructive">{error}</span>}
    </div>
  )

  /* Withheld until there are pages, like the create button and for a sharper reason: with none, a
     click would unlink this window onto nothing, and — because unlinking *is* a decision — it
     would also spend the one arrival decision `BuskingView` is waiting to make, so a window
     launched at `?page=3` would silently never land on page 3. The link badge while paged with the
     desk, the dashed *Page: Own* while not (desk-follow D7). */
  const pageChip = pages.length > 0 && (
    <BuskPageChip
      showingPageId={activePageId}
      subjectClass={pads != null ? PAD_PAGE_SUBJECT_CLASS : dense ? MERGED_PAGE_SUBJECT_CLASS : SPLIT_PAGE_SUBJECT_CLASS}
      namesClass={pads != null ? PAD_PAGE_NAMES_CLASS : dense ? MERGED_PAGE_NAMES_CLASS : SPLIT_PAGE_NAMES_CLASS}
      className="min-w-0 shrink"
    />
  )

  const editingVerbs = editing ? (
    <>
      <span className="text-[11px] text-muted-foreground">
        {pending > 0 ? 'Saving…' : justSaved ? 'Saved' : ''}
      </span>
      {active && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label={`Options for ${active.name}`}
              className="text-muted-foreground hover:text-foreground"
            >
              <MoreHorizontal className="size-4" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem
              onSelect={() => {
                setDraft(active.name)
                setError(null)
                setNaming('rename')
              }}
            >
              Rename page
            </DropdownMenuItem>
            <DropdownMenuItem
              variant="destructive"
              onSelect={() => setConfirmingDelete(true)}
            >
              Delete page
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </>
  ) : null

  const summary = pads == null ? null : summariseSelection([...pads.selectedTargets.values()])

  return (
    // `@container` on a wrapper and the row inside it — never on the row itself, or its floor
    // class has no container to match (docblock). The gutter is the wrapper's, as the band's is
    // the band's, so the container's content box *is* the row's and the rungs are measured on it.
    <div
      data-busk-page-strip="open"
      data-busk-page-strip-dense={dense ? 'true' : undefined}
      // The merged row's 32px is the wrapper's, border included (`box-sizing: border-box`), and the
      // row fills what is left: an `h-8` row under a bordered wrapper drew the chrome at 33.
      className={cn('@container shrink-0 px-4', dense && 'h-8 border-b')}
    >
    <div
      // `data-pad-row`: the handle a test reaches the row by (D20), since the label folds.
      data-pad-row={dense ? 'merged' : pads != null ? 'pads' : 'split'}
      className={cn(
        'flex items-center',
        // The merged row is exactly 32px and never wraps — a second line would cost the pads the
        // row it was merged to save. In Split the row wraps as it always did, for edit mode's
        // name field; in Pads it is `flex-nowrap` above its floor and two rows by design below.
        dense ? 'h-full gap-2' : 'min-h-7 gap-x-2 gap-y-1 pt-2.5',
        !dense && pads == null && 'flex-wrap',
        !dense && pads != null && PAD_TWO_ROWS_CLASS,
      )}
    >
      {dense ? (
        <>
          {leading}
          {tabs}
          {pageChip}
          {nameField}
          {/* On the merged row the rig's summary is the flexible item and sits in `leading`; a
              dense row with nothing leading it — the short board in Split — still needs one, or
              the controls pack against the tabs. */}
          {leading == null && <div className="flex-1" />}
          {controls}
          {editingVerbs}
        </>
      ) : (
        <>
          {/* Two groups rather than one flat list, so the floor can break the row at exactly one
              place: the label, the tabs and the verbs; then the gap and everything anchored to the
              row's end. */}
          <div
            data-pad-row-tabs
            className={cn(
              'flex shrink-0 items-center gap-2',
              // Pads: its whole line under the floor, wrapping within it as a last resort. Split:
              // capped to the row and wrapping, so edit mode's name field and `+ Page` can break
              // onto a second line beside the palette instead of painting over the page.
              pads != null ? PAD_FIRST_ROW_CLASS : 'max-w-full flex-wrap',
            )}
          >
            {pads != null && <EditorLabel className={PAD_LABEL_CLASS}>Pads</EditorLabel>}
            {tabs}
            {/* The page mark beside the tabs it is about (desk-follow D7, `Pages.dc.html`): the link
                badge while paged with the desk, *Page: Own* while not. */}
            {pageChip}
            {nameField}
            {pads != null && !editing && <SelectionVerbButtons verbs={pads.verbs} wordClass={PAD_VERB_WORD_CLASS} />}
          </div>
          <div
            data-pad-row-state
            className={cn('flex min-w-0 flex-1 items-center gap-2', pads != null && PAD_SECOND_ROW_CLASS)}
          >
            {summary != null ? (
              // Pads: no tiles to say what is selected, so the summary sits in the gap — the first
              // thing to give when the row is short, its whole text on the title.
              <span data-pad-summary className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground" title={summary}>
                {summary}
              </span>
            ) : (
              <span className="flex-1" />
            )}
            {pads != null && pads.families != null && pads.families.length > 0 && (
              <Badge
                variant="outline"
                data-pad-family
                className="shrink-0 whitespace-nowrap border-primary/40 bg-primary/10 px-2 py-0 text-[10px] text-primary"
              >
                {formatFamilyList(pads.families, ' · ')}
              </Badge>
            )}
            {/* Blind beside the mask, in Pads only: in Split and Rig the rig row carries it
                (`BlindMarks.tsx`). */}
            {pads != null && <BlindPill wordClass={PAD_FOCUS_WORD_CLASS} />}
            {/* The desk chip too, in Pads (D17): there is no rig row here to carry it. Pads always
                follows the desk selection (desk-follow D2), so this is the link badge (D8). */}
            {pads != null && !editing && (
              <DeskChip showSubject forcedBy="pads" subjectClass={PAD_CHIP_SUBJECT_CLASS} className="min-w-0 shrink" />
            )}
            {controls}
            {editingVerbs}
          </div>
        </>
      )}
      {/* Confirmed, unlike every other edit-mode gesture: the rest move pads about and are undone by
          moving them back, while this takes a whole arrangement away and the layout write has no
          undo. */}
      <AlertDialog open={confirmingDelete} onOpenChange={setConfirmingDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete “{active?.name}”?</AlertDialogTitle>
            <AlertDialogDescription>
              Its banks and pads go with it. The templates, Looks and cues they pointed at are not
              touched.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setConfirmingDelete(false)
                onDelete()
              }}
            >
              Delete page
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

    </div>
    </div>
  )
}
