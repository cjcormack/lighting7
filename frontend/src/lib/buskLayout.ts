import type { AttributeFamily } from '@/lib/attributeFamily'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'
import type {
  BuskBank,
  BuskColumn,
  BuskCue,
  BuskLayoutBankInput,
  BuskLayoutPadInput,
  BuskLayoutRequest,
  BuskPad,
  BuskPadKind,
  BuskPage,
} from '@/api/buskApi'

/**
 * The busk page as a document, and every gesture that edits one.
 *
 * The pure half of the busk view's drag: no React, no store, so each arm of the drag
 * behaviour is a plain call a unit test can make. What the view keeps is a hover *target*, and this
 * module turns `(page, source, target)` into the next page exactly once, on drop.
 *
 * Three rules run through all of it:
 *
 * - **Addresses, not ids.** A pad, bank or column created by the *previous* gesture has no id yet —
 *   the layout PUT mints it — so every position here is a tuple of indices. Ids are read in one
 *   place, {@link toLayoutRequest}, at commit.
 * - **Normalise inside every mutator.** The server refuses an empty row or column
 *   (`BUSK_LAYOUT_INVALID`), so pruning is not cosmetic: forget it and the gesture 400s.
 *   {@link normalisePage} is therefore called by each mutator rather than left to the caller.
 * - **Null means nothing would change** — `moveInLayout`'s convention, and what lets the hover
 *   handler skip a state write on a repeat hover.
 */

// ─── Addresses ──────────────────────────────────────────────────────────

export interface BankAddress {
  row: number
  column: number
  bank: number
}

export interface PadAddress extends BankAddress {
  pad: number
}

/** A library record on its way onto a page. Carries the record so the new pad can draw itself. */
export type PaletteRecord =
  | { kind: 'TEMPLATE'; template: TemplateSummary }
  | { kind: 'LOOK'; look: LookSummary }
  | { kind: 'CUE'; cue: BuskCue }

export type DragSource =
  | { kind: 'palette'; record: PaletteRecord }
  | { kind: 'pad'; at: PadAddress }
  | { kind: 'bank'; at: BankAddress }

/**
 * Where a lifted thing would land.
 *
 * `pad` means *insert before this index* — `at.pad === bank.pads.length` is the append case the
 * bank-body droppable produces, which is also the only way into an empty bank. The other three are
 * the bank zones `Layout.dc.html` draws: a strip under a bank, a gutter between two columns, and
 * the page's own footer.
 */
export type DropTarget =
  | { kind: 'pad'; at: PadAddress }
  | { kind: 'bank-under'; at: BankAddress }
  | { kind: 'new-column'; row: number; column: number }
  | { kind: 'new-row' }

// ─── Drag ids ───────────────────────────────────────────────────────────

export const BUSK_NEW_ROW_ID = 'bnewrow'

export function buskPadId(at: PadAddress): string {
  return `bpad:${at.row}.${at.column}.${at.bank}.${at.pad}`
}

export function buskBankBodyId(at: BankAddress): string {
  return `bbody:${at.row}.${at.column}.${at.bank}`
}

export function buskBankId(at: BankAddress): string {
  return `bbank:${at.row}.${at.column}.${at.bank}`
}

export function buskBankUnderId(at: BankAddress): string {
  return `bunder:${at.row}.${at.column}.${at.bank}`
}

/** The gutter **before** column `column` of `row`; `column === columns.length` is the trailing one. */
export function buskGutterId(row: number, column: number): string {
  return `bgut:${row}.${column}`
}

export function buskPaletteId(kind: BuskPadKind, id: number): string {
  return `palette:${kind.toLowerCase()}:${id}`
}

export function buskPageTabId(index: number): string {
  return `bpagetab:${index}`
}

export type ParsedBuskId =
  | { kind: 'pad'; at: PadAddress }
  | { kind: 'bank-body'; at: BankAddress }
  | { kind: 'bank'; at: BankAddress }
  | { kind: 'bank-under'; at: BankAddress }
  | { kind: 'gutter'; row: number; column: number }
  | { kind: 'new-row' }
  | { kind: 'palette'; recordKind: BuskPadKind; id: number }
  | { kind: 'page-tab'; index: number }

/**
 * Parse a busk drag id, or null for one belonging to something else.
 *
 * Null is how the busk page and the cue-slot grid share **one** `DndContext` without knowing about
 * each other: the busk handler ignores `slot-…`, the slot handler ignores everything here.
 */
export function parseBuskDragId(id: string): ParsedBuskId | null {
  if (id === BUSK_NEW_ROW_ID) return { kind: 'new-row' }

  const pad = /^bpad:(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(id)
  if (pad) {
    return {
      kind: 'pad',
      at: { row: +pad[1], column: +pad[2], bank: +pad[3], pad: +pad[4] },
    }
  }

  const bank = /^(bbody|bbank|bunder):(\d+)\.(\d+)\.(\d+)$/.exec(id)
  if (bank) {
    const at = { row: +bank[2], column: +bank[3], bank: +bank[4] }
    const kind = bank[1] === 'bbody' ? 'bank-body' : bank[1] === 'bbank' ? 'bank' : 'bank-under'
    return { kind, at }
  }

  const gutter = /^bgut:(\d+)\.(\d+)$/.exec(id)
  if (gutter) return { kind: 'gutter', row: +gutter[1], column: +gutter[2] }

  const palette = /^palette:(template|look|cue):(\d+)$/.exec(id)
  if (palette) {
    return {
      kind: 'palette',
      recordKind: palette[1].toUpperCase() as BuskPadKind,
      id: +palette[2],
    }
  }

  const tab = /^bpagetab:(\d+)$/.exec(id)
  if (tab) return { kind: 'page-tab', index: +tab[1] }

  return null
}

/**
 * The drop target a parsed droppable id names, or null when it names no landing place.
 *
 * The bank **body** collapses to a pad target at the end of the bank, which is what makes an empty
 * bank droppable at all — the same trick the retired template-group list used for an empty group.
 */
export function dropTargetFor(parsed: ParsedBuskId, page: BuskPage): DropTarget | null {
  switch (parsed.kind) {
    case 'pad':
      return { kind: 'pad', at: parsed.at }
    case 'bank-body': {
      const bank = bankAt(page, parsed.at)
      if (bank == null) return null
      return { kind: 'pad', at: { ...parsed.at, pad: bank.pads.length } }
    }
    case 'bank-under':
      return { kind: 'bank-under', at: parsed.at }
    case 'gutter':
      return { kind: 'new-column', row: parsed.row, column: parsed.column }
    case 'new-row':
      return { kind: 'new-row' }
    default:
      return null
  }
}

// ─── Reading ────────────────────────────────────────────────────────────

export function bankAt(page: BuskPage, at: BankAddress): BuskBank | null {
  return page.rows[at.row]?.columns[at.column]?.banks[at.bank] ?? null
}

export function padAt(page: BuskPage, at: PadAddress): BuskPad | null {
  return bankAt(page, at)?.pads[at.pad] ?? null
}

/** `template:7` / `look:3` / `cue:12` for every record with a pad here — the palette's *on page*. */
export function recordsOnPage(page: BuskPage): Set<string> {
  const keys = new Set<string>()
  forEachPad(page, (pad) => {
    const id = padRecordId(pad)
    if (id != null) keys.add(`${pad.kind.toLowerCase()}:${id}`)
  })
  return keys
}

export function padRecordId(pad: BuskPad): number | null {
  if (pad.kind === 'TEMPLATE') return pad.template?.id ?? null
  if (pad.kind === 'LOOK') return pad.look?.id ?? null
  return pad.cue?.id ?? null
}

/**
 * Every pad on the page, flat — the one place `rows → columns → banks → pads` is walked, so a
 * change to the shape (or to what counts as a pad) lands here rather than in each of this
 * traversal's several callers. {@link recordsOnPage} is `forEachPad` for the same reason; this is
 * the array-returning form for a caller that wants to `.filter`/`.map` rather than fold, and does
 * not need the bank each pad sits on.
 */
export function allPads(page: BuskPage): BuskPad[] {
  const pads: BuskPad[] = []
  forEachPad(page, (pad) => pads.push(pad))
  return pads
}

/**
 * Every **bank** on the page, flat — [allPads]'s sibling one level up.
 *
 * It is not `allPads` composed with anything, and that is the point: an empty bank has no pad to
 * be found through, and an empty bank is exactly what a *place* target most often is. Two callers,
 * both about the hand's `handPlaceInBank` binding — the surface library's chips and the picker's
 * option list.
 */
export function allBanks(page: BuskPage): BuskBank[] {
  return page.rows.flatMap((row) => row.columns).flatMap((column) => column.banks)
}

/**
 * The traversal itself, exported so a caller that *does* need the bank a pad sits on (a record
 * binding's picker, which shows "`page` · `bank`" as the pad's detail line) is not left re-deriving
 * `rows → columns → banks → pads` a second time by hand.
 */
export function forEachPad(page: BuskPage, fn: (pad: BuskPad, bank: BuskBank) => void): void {
  for (const row of page.rows) {
    for (const column of row.columns) {
      for (const bank of column.banks) {
        for (const pad of bank.pads) fn(pad, bank)
      }
    }
  }
}

// ─── Minting ────────────────────────────────────────────────────────────

let localKeySeq = 0

/** A client-side key for a node the server has not seen. Never sent; see {@link toLayoutRequest}. */
export function mintLocalKey(): string {
  localKeySeq += 1
  return `new-${localKeySeq}`
}

export function newPad(record: PaletteRecord): BuskPad {
  const base = { localKey: mintLocalKey(), kind: record.kind }
  if (record.kind === 'TEMPLATE') return { ...base, template: record.template }
  if (record.kind === 'LOOK') return { ...base, look: record.look }
  return { ...base, cue: record.cue }
}

/**
 * `name` is required rather than defaulted, because the server refuses a blank one
 * (`BUSK_LAYOUT_INVALID`, "A bank at row N, column M has a blank name") and a default of `''` is how
 * `+ Bank` shipped never having succeeded once. Callers with nothing better say {@link nextBankName}.
 */
export function newBank(name: string, pads: BuskPad[] = []): BuskBank {
  return { localKey: mintLocalKey(), name, solo: false, flow: 'WRAP', pads }
}

/**
 * `Bank N`, for the smallest N no bank on the page is already called — the positional vocabulary
 * `buskAdd.ts` uses when it has to *label* a nameless bank, so a bank the operator never renames
 * reads the same in the Add-to-page menu as on the page.
 */
export function nextBankName(page: BuskPage): string {
  const taken = new Set<string>()
  for (const row of page.rows) {
    for (const column of row.columns) {
      for (const bank of column.banks) taken.add(bank.name.trim())
    }
  }
  for (let n = 1; ; n += 1) {
    const candidate = `Bank ${n}`
    if (!taken.has(candidate)) return candidate
  }
}

export function newColumn(width: number, banks: BuskBank[]): BuskColumn {
  return { localKey: mintLocalKey(), width, banks }
}

// ─── Normalising ────────────────────────────────────────────────────────

/**
 * Drop empty columns, and rows left empty with them.
 *
 * **An empty bank is kept**, and that asymmetry is deliberate rather than an oversight: the server
 * accepts one, and pruning it would delete the operator's just-made bank the instant they crossed
 * the last pad off it. A column with no banks and a row with no columns are both refused by the
 * server, so those two must go.
 */
export function normalisePage(page: BuskPage): BuskPage {
  const rows = page.rows
    .map((row) => ({ columns: row.columns.filter((column) => column.banks.length > 0) }))
    .filter((row) => row.columns.length > 0)
  return { ...page, rows }
}

// ─── The wire body ──────────────────────────────────────────────────────

function padInput(pad: BuskPad): BuskLayoutPadInput {
  const input: BuskLayoutPadInput = {}
  if (pad.id != null) input.padId = pad.id
  const recordId = padRecordId(pad)
  if (recordId != null) {
    if (pad.kind === 'TEMPLATE') input.templateId = recordId
    else if (pad.kind === 'LOOK') input.lookId = recordId
    else input.cueId = recordId
  }
  return input
}

function bankInput(bank: BuskBank): BuskLayoutBankInput {
  const input: BuskLayoutBankInput = {
    name: bank.name,
    solo: bank.solo,
    flow: bank.flow,
    pads: bank.pads.map(padInput),
  }
  if (bank.id != null) input.bankId = bank.id
  return input
}

/**
 * The whole page as the layout PUT takes it.
 *
 * Ids where the node has one, **omitted** where it does not (the server creates those), and never a
 * `uuid` — those are sync's — or a `localKey`, which is this client's alone.
 */
export function toLayoutRequest(page: BuskPage): BuskLayoutRequest {
  return {
    rows: page.rows.map((row) => ({
      columns: row.columns.map((column) => {
        const input = { width: column.width, banks: column.banks.map(bankInput) } as {
          columnId?: number
          width: number
          banks: BuskLayoutBankInput[]
        }
        if (column.id != null) input.columnId = column.id
        return input
      }),
    })),
  }
}

// ─── Mutators ───────────────────────────────────────────────────────────

function clone(page: BuskPage): BuskPage {
  return {
    ...page,
    rows: page.rows.map((row) => ({
      columns: row.columns.map((column) => ({
        ...column,
        banks: column.banks.map((bank) => ({ ...bank, pads: [...bank.pads] })),
      })),
    })),
  }
}

/** Did the edit change anything the server would store? The universal no-op guard. */
function changed(before: BuskPage, after: BuskPage): boolean {
  return JSON.stringify(toLayoutRequest(before)) !== JSON.stringify(toLayoutRequest(after))
}

export function removePad(page: BuskPage, at: PadAddress): BuskPage {
  const next = clone(page)
  const bank = bankAt(next, at)
  if (bank == null) return page
  bank.pads.splice(at.pad, 1)
  return normalisePage(next)
}

/**
 * Where the **last** pad of the bank with this server id sits, or null if there is no such bank.
 *
 * The one reader is the hand's Undo for a bank place (`BuskingView`): `POST /busk/banks/{id}/pads`
 * **appends**, and answers the whole page, so the pad to take back off is the last one of that
 * bank in the page the append just returned. There is no remove-pad route — the inverse goes back
 * through the layout PUT like every other edit to a page.
 *
 * By id rather than by address because the caller has a `bankId` and not a `BankAddress`: the
 * append addresses a bank by its server id, and the row/column it sits in is not part of that
 * conversation.
 */
export function lastPadOfBank(page: BuskPage, bankId: number): PadAddress | null {
  for (let row = 0; row < page.rows.length; row++) {
    const columns = page.rows[row]!.columns
    for (let column = 0; column < columns.length; column++) {
      const banks = columns[column]!.banks
      for (let bank = 0; bank < banks.length; bank++) {
        const found = banks[bank]!
        if (found.id !== bankId || found.pads.length === 0) continue
        return { row, column, bank, pad: found.pads.length - 1 }
      }
    }
  }
  return null
}

export function removeBank(page: BuskPage, at: BankAddress): BuskPage {
  const next = clone(page)
  const column = next.rows[at.row]?.columns[at.column]
  if (column == null) return page
  column.banks.splice(at.bank, 1)
  return normalisePage(next)
}

export function setBank(
  page: BuskPage,
  at: BankAddress,
  patch: Partial<Pick<BuskBank, 'name' | 'solo' | 'flow'>>,
): BuskPage {
  const next = clone(page)
  const bank = bankAt(next, at)
  if (bank == null) return page
  Object.assign(bank, patch)
  return normalisePage(next)
}

/** Width belongs to the **column**, which is why the bank's ⋯ menu reaches past the bank to set it. */
export function setColumnWidth(page: BuskPage, row: number, column: number, width: number): BuskPage {
  const next = clone(page)
  const target = next.rows[row]?.columns[column]
  if (target == null) return page
  target.width = width
  return normalisePage(next)
}

/** Copy a bank, pads and all, directly below itself. The copied pads are new pads of the same records. */
export function duplicateBank(page: BuskPage, at: BankAddress): BuskPage {
  const next = clone(page)
  const column = next.rows[at.row]?.columns[at.column]
  const bank = column?.banks[at.bank]
  if (column == null || bank == null) return page
  const copy: BuskBank = {
    localKey: mintLocalKey(),
    name: bank.name,
    solo: bank.solo,
    flow: bank.flow,
    pads: bank.pads.map((pad) => ({ ...pad, id: undefined, uuid: undefined, localKey: mintLocalKey() })),
  }
  column.banks.splice(at.bank + 1, 0, copy)
  return normalisePage(next)
}

/**
 * `+ Bank`: a new column at the end of a row, holding one empty bank.
 *
 * The affordance says *Bank* and its position says *Column* — `Edit.dc.html` puts it in the column
 * slot that ends a row, and stacking a bank inside an existing column is reachable by dragging onto
 * that column's *stack under* strip.
 */
export function addBankColumn(page: BuskPage, row: number): BuskPage {
  const next = clone(page)
  const target = next.rows[row]
  if (target == null) return page
  target.columns.push(newColumn(3, [newBank(nextBankName(next))]))
  return normalisePage(next)
}

/**
 * `+ Row`: a row, a full-width column and an empty bank — **a legal document, not a placeholder**.
 * A bare row would be refused by the layout route on the very next gesture.
 */
export function addRow(page: BuskPage): BuskPage {
  const next = clone(page)
  next.rows.push({ columns: [newColumn(12, [newBank(nextBankName(next))])] })
  return normalisePage(next)
}

// ─── The drop ───────────────────────────────────────────────────────────

/**
 * Apply a drop, or answer null when nothing would change.
 *
 * The order is **lift → insert → prune**, and it is what keeps the index arithmetic to one line.
 * Object identity carries the destination across the lift — the anchor bank or column is located
 * *before* anything moves and found again afterwards — so a bank leaving the column it is being
 * dropped next to cannot silently renumber its own target. Pruning runs last, after both operands
 * are placed, so it can never move a target that has not been used yet.
 *
 * A palette row and a pad land only on a pad target; a bank lands only on the three bank zones.
 * Any other pairing is not a gesture and answers null.
 *
 * A **pad target is an insertion point, not a destination index** — it names the gap the dashed
 * slot is drawn in, counted over the document as it stands with the source still in it. That is the
 * contract `resolveDropTarget` produces and `BuskBank` renders, and the two must agree or a pad
 * lands somewhere other than where the operator was shown it would.
 */
export function applyDrop(page: BuskPage, source: DragSource, target: DropTarget): BuskPage | null {
  const next =
    source.kind === 'bank' ? dropBank(page, source.at, target) : dropPad(page, source, target)
  if (next == null) return null
  const pruned = normalisePage(next)
  return changed(page, pruned) ? pruned : null
}

function dropPad(
  page: BuskPage,
  source: Extract<DragSource, { kind: 'palette' | 'pad' }>,
  target: DropTarget,
): BuskPage | null {
  if (target.kind !== 'pad') return null
  const next = clone(page)
  const destination = bankAt(next, target.at)
  if (destination == null) return null

  if (source.kind === 'palette') {
    destination.pads.splice(clampIndex(target.at.pad, destination.pads.length), 0, newPad(source.record))
    return next
  }

  const origin = bankAt(next, source.at)
  const moving = origin?.pads[source.at.pad]
  if (origin == null || moving == null) return null

  if (origin === destination) {
    if (source.at.pad === target.at.pad) return null
    origin.pads.splice(source.at.pad, 1)
    // `target.at.pad` is an insertion point in the array **before** the lift — literally where the
    // dashed slot was drawn, with the source still in place and ghosted. Removing a source that sat
    // *before* that point shifts it down by one, so the pad must land one earlier than the slot's
    // index or it overshoots by exactly one place. Moving a pad earlier needs no correction, which
    // is why only downward drags were wrong. (The bank path avoids this arithmetic entirely by
    // re-finding its anchor by object identity after the lift; a pad has no anchor object, because
    // the slot can sit past the last pad.)
    const insertAt = source.at.pad < target.at.pad ? target.at.pad - 1 : target.at.pad
    origin.pads.splice(clampIndex(insertAt, origin.pads.length), 0, moving)
    return next
  }

  origin.pads.splice(source.at.pad, 1)
  destination.pads.splice(clampIndex(target.at.pad, destination.pads.length), 0, moving)
  return next
}

function dropBank(page: BuskPage, from: BankAddress, target: DropTarget): BuskPage | null {
  if (target.kind === 'pad') return null
  const next = clone(page)
  const originColumn = next.rows[from.row]?.columns[from.column]
  const moving = originColumn?.banks[from.bank]
  if (originColumn == null || moving == null) return null

  // Located before the lift and found again after it, so an emptied column cannot renumber them.
  const anchorBank = target.kind === 'bank-under' ? bankAt(next, target.at) : null
  const anchorRow = target.kind === 'new-column' ? next.rows[target.row] : null
  if (target.kind === 'bank-under' && anchorBank == null) return null
  if (target.kind === 'new-column' && anchorRow == null) return null

  if (target.kind === 'new-column' && originColumn.banks.length === 1 && from.row === target.row) {
    // The gutter on either side of a bank's own single-bank column puts it back where it started,
    // only in a quarter-width column — a width change the operator did not ask for. Refuse it.
    if (target.column === from.column || target.column === from.column + 1) return null
  }

  originColumn.banks.splice(from.bank, 1)

  if (target.kind === 'bank-under') {
    for (const row of next.rows) {
      for (const column of row.columns) {
        const index = column.banks.indexOf(anchorBank!)
        if (index !== -1) {
          column.banks.splice(index + 1, 0, moving)
          return next
        }
      }
    }
    return null
  }

  if (target.kind === 'new-column') {
    anchorRow!.columns.splice(clampIndex(target.column, anchorRow!.columns.length), 0, newColumn(3, [moving]))
    return next
  }

  next.rows.push({ columns: [newColumn(12, [moving])] })
  return next
}

function clampIndex(index: number, length: number): number {
  return Math.max(0, Math.min(index, length))
}

// ─── The first-open generator ───────────────────────────────────────────

const STARTER_FAMILIES: readonly AttributeFamily[] = ['COLOUR', 'POSITION', 'BEAM', 'INTENSITY']

/**
 * *Start from your library* (D11): a stacking bank per family holding that family's templates, a
 * Looks bank of every Look that can be busked, and an empty Cues bank.
 *
 * **Nothing solo, and no template groups or pinned cues are read.** Both are being deleted in
 * session 3, so a generator that carried them would have to be rewritten immediately; and a page
 * that arrived with exclusivity already set is a page whose first press does something the operator
 * did not ask for. A family with no templates still gets its (legal) empty bank, which is what
 * shows where things go.
 */
export function libraryStarterLayout(
  templates: readonly TemplateSummary[],
  looks: readonly LookSummary[],
): BuskLayoutRequest {
  const familyColumns: BuskLayoutColumn[] = STARTER_FAMILIES.map((family) => ({
    width: 3,
    banks: [
      {
        name: FAMILY_LABELS[family].singular,
        solo: false,
        flow: 'WRAP' as const,
        pads: templates.filter((t) => t.family === family).map((t) => ({ templateId: t.id })),
      },
    ],
  }))

  return {
    rows: [
      { columns: familyColumns },
      {
        columns: [
          {
            width: 8,
            banks: [
              {
                name: 'Looks',
                solo: false,
                flow: 'WRAP',
                pads: looks.filter((l) => l.hasDeferredEffects).map((l) => ({ lookId: l.id })),
              },
            ],
          },
          { width: 4, banks: [{ name: 'Cues', solo: false, flow: 'COLUMN', pads: [] }] },
        ],
      },
    ],
  }
}

type BuskLayoutColumn = BuskLayoutRequest['rows'][number]['columns'][number]
