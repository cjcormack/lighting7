import type { AttributeFamily } from '@/lib/attributeFamily'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * The **busk layout**: pages of rows of columns of banks of pads, built by the operator.
 *
 * The busk view used to lay itself out *from the library* — a column per family, a template group
 * drawn as a cluster, a pool of every Look with a deferred effect. `busk-layout-plan.md` moves that
 * decision to the operator (D1–D2): a page is something they build, referencing library records,
 * and the page owns the two facts a template group used to own — where a pad sits, and what else
 * goes off when it is pressed.
 *
 * Backend contract in `lighting7/models/buskLayout.kt` and
 * `lighting7/docs/lighting-composition-model.md` §"The busk layout".
 *
 * **These types are the document the client edits, not only the document the server sends**, which
 * is why `id` and `uuid` are optional on a column, bank and pad. Every node the server answers with
 * has both; a node this client has just minted — the bank you dropped a pad into a second ago — has
 * neither until the layout PUT answers with the ids it created. One type for both states is what
 * lets `lib/buskLayout.ts` be a set of plain functions over the thing on screen; the alternative is
 * a parallel draft tree that has to be zipped back onto the wire tree after every save.
 *
 * A **row has no identity at all** — it is list position, renumbered densely server-side — which is
 * why {@link BuskRow} carries nothing but its columns.
 */

/**
 * How a bank lays its pads out — and, since the rig's rows took the bank's layout, how a rig row
 * lays its tiles out: wrapping to the box's width, one per line, or one line that scrolls
 * sideways. Mirrors `BuskFlow` in `models/buskLayout.kt`; `SCROLL` is what every rig row did
 * before it had a flow, so it is the row's default where a bank's is `WRAP`.
 */
export type BuskFlow = 'WRAP' | 'COLUMN' | 'SCROLL'

export const BUSK_FLOWS: readonly BuskFlow[] = ['WRAP', 'COLUMN', 'SCROLL']

/** The flow menu's words, one rule for banks and rows alike. */
export const BUSK_FLOW_LABELS: Readonly<Record<BuskFlow, string>> = {
  WRAP: 'Wrap',
  COLUMN: 'Column',
  SCROLL: 'Scroll',
}

/** The three things a pad can press. */
export type BuskPadKind = 'TEMPLATE' | 'LOOK' | 'CUE'

/**
 * The width shares a column may take, **in twelfths**: ¼, ⅓, ½, ⅔, ¾ and full.
 *
 * Mirrors `BUSK_WIDTHS` in `models/buskLayout.kt`; the layout route refuses anything else with
 * `BUSK_LAYOUT_INVALID`. Widths in a row need not sum to twelve — the client draws them as `fr`
 * shares, so three quarter-columns simply leave a quarter of the row empty.
 */
export const BUSK_WIDTHS: readonly number[] = [3, 4, 6, 8, 9, 12]

/** Labels for the width menu, in `BUSK_WIDTHS` order. */
export const BUSK_WIDTH_LABELS: Readonly<Record<number, string>> = {
  3: '¼',
  4: '⅓',
  6: '½',
  8: '⅔',
  9: '¾',
  12: 'Full',
}

/**
 * What a cue pad draws: the mono number, the name, the stack it lives in.
 *
 * Deliberately not the full cue document — a cue carries every layer and trigger, and a pad needs
 * three strings. Its **lit** state is not here either: it comes from the cue stack list's
 * `activeCueId` (`useActiveCueIds`), because a cue can be live without being the playhead.
 */
export interface BuskCue {
  id: number
  /** Absent on a cue the palette has just dropped — the server answers with it. See the note above. */
  uuid?: string
  name: string
  cueNumber: string | null
  cueStackId: number
  cueStackName: string
}

/**
 * One pad: an ordered reference to exactly one template, Look or cue (D3).
 *
 * The record's **own summary DTO** is embedded rather than a flattened name / swatch / detail line,
 * so the view needs no second fetch and an effect template's detail line can read a *live*
 * speed-master label that a server string would have frozen.
 *
 * One record may sit on several pads, on several pages; a pad is an **enrichment**, never a guard,
 * so deleting the record deletes its pads.
 */
export interface BuskPad {
  /** Server id. Absent on a pad this client minted and has not yet saved. */
  id?: number
  uuid?: string
  /** Client-minted React/drag key for an unsaved node. Never sent; see `toLayoutRequest`. */
  localKey?: string
  kind: BuskPadKind
  template?: TemplateSummary | null
  look?: LookSummary | null
  cue?: BuskCue | null
}

export interface BuskBank {
  id?: number
  uuid?: string
  localKey?: string
  /** Shown in the bank header. May repeat across banks — a bank's identity is its place. */
  name: string
  /**
   * Whether pressing one pad turns its siblings off (D6). Off means the bank *stacks* — the
   * behaviour every ungrouped pad had before the layout existed, now a choice per bank.
   */
  solo: boolean
  flow: BuskFlow
  pads: BuskPad[]
}

export interface BuskColumn {
  id?: number
  uuid?: string
  localKey?: string
  /** A share in twelfths — one of {@link BUSK_WIDTHS}. */
  width: number
  banks: BuskBank[]
}

/** A row is list position and nothing else — it has no id on either side of the wire. */
export interface BuskRow {
  columns: BuskColumn[]
}

export interface BuskPage {
  id: number
  uuid: string
  name: string
  sortOrder: number
  rows: BuskRow[]
}

// ─── Write shapes ───────────────────────────────────────────────────────

export interface CreateBuskPageRequest {
  name: string
}

export interface RenameBuskPageRequest {
  name: string
}

/** Every page of the project, once, in the order wanted. A partial list is a 400. */
export interface ReorderBuskPagesRequest {
  pageIds: number[]
}

/** Exactly one of the three is set. */
export interface BuskLayoutPadInput {
  padId?: number
  templateId?: number
  lookId?: number
  cueId?: number
}

export interface BuskLayoutBankInput {
  bankId?: number
  name: string
  solo: boolean
  flow: BuskFlow
  pads: BuskLayoutPadInput[]
}

export interface BuskLayoutColumnInput {
  columnId?: number
  width: number
  banks: BuskLayoutBankInput[]
}

export interface BuskLayoutRowInput {
  columns: BuskLayoutColumnInput[]
}

/**
 * The **whole** page, in one write (D10).
 *
 * Rows and their order are implied by list position; a column, bank or pad carrying an id is moved
 * and rewritten, one without is created, and one on the page but absent here is deleted. `rows: []`
 * is a legal empty page — but an empty **row** or **column** is refused, which is why
 * `normalisePage` prunes both and is called inside every mutator in `lib/buskLayout.ts`.
 *
 * Whole rather than partial for the reason the retired templates reorder route gave: a partial
 * document cannot say "this column is now empty".
 */
export interface BuskLayoutRequest {
  rows: BuskLayoutRowInput[]
}

/**
 * One pad appended to one bank, addressed by **bank id** rather than by a position in a document.
 *
 * The additive exception to the whole-page write: the surfaces that place a pad from outside the
 * busk view (cue properties, the template editor, the Look sheet, the programmer's create sheets)
 * hold no page document to splice and re-`PUT`, and an id survives any reshuffle of the page that
 * keeps the bank alive. Exactly one of the three is set. The server answers the **whole page**, the
 * same contract `BuskLayoutRequest` has and for the same reason: the ids it minted.
 */
export interface AddBuskPadRequest {
  templateId?: number
  lookId?: number
  cueId?: number
}

/**
 * A press. `targets` is the busk view's selection; it is ignored for a cue pad, and may be empty
 * for a per-fixture template or a Look with no deferred effect — both of which name their own heads.
 *
 * `families` is the selection's attribute mask, riding the press beside the targets it is a mask
 * *of* (multi-screen plan D4): the desk's pair while this tab follows the desk, the tab's own when
 * unlinked. Absent is every attribute. It is tested on the **on** arm only — a press that turns a
 * lit record off answers `removed` under any mask — so a client never pre-refuses a press from
 * the mask it holds: send it, and render the desk's answer. A template outside it is 400
 * `TEMPLATE_OUTSIDE_MASK`, a Look with nothing inside it 400 `LOOK_OUTSIDE_MASK`; a cue ignores it.
 */
export interface BuskPressRequest {
  targets: { type: 'group' | 'fixture'; key: string }[]
  families?: AttributeFamily[]
  beatDivision?: number
}

export interface BuskPressResponse {
  kind: BuskPadKind
  action: 'applied' | 'removed'
  effectCount: number
  /**
   * What an *on* press in a **solo** bank turned off: layer siblings narrowed or dropped, plus cue
   * siblings stopped. Always 0 for an off press and in a stacking bank.
   */
  released: number
  /**
   * A Look's families the mask left out, in declaration order — what the pressing window toasts
   * as `Position rows skipped` (D6). Empty for a template or cue, an unmasked press, and the off
   * arm. *Rows*: the layer's mask filters its rows, not its effects, so a Look's effect in a
   * skipped family still runs while being named here.
   */
  skippedFamilies: string[]
}

// ─── Error codes the UI branches on ─────────────────────────────────────

/** 409: a page of that name already exists in the project. Rendered beside the field. */
export const BUSK_PAGE_NAME_TAKEN = 'BUSK_PAGE_NAME_TAKEN'

/** 400: the document is malformed — a bad width or flow, an empty row or column, a bad pad ref. */
export const BUSK_LAYOUT_INVALID = 'BUSK_LAYOUT_INVALID'

/** 400: the document names a column, bank or pad that is not on this page, or names one twice. */
export const BUSK_LAYOUT_IDENTITY = 'BUSK_LAYOUT_IDENTITY'

/** 400: a pad names a template, Look or cue that is not in this project — see the echo note in `store/busk.ts`. */
export const BUSK_LAYOUT_REF = 'BUSK_LAYOUT_REF'
