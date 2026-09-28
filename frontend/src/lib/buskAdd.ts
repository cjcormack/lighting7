import type { BuskPad, BuskPadKind, BuskPage } from '@/api/buskApi'
import { padRecordId } from './buskLayout'
import { createSyncStore } from './syncStore'

/**
 * Placing a record on a busk page from *outside* the busk view.
 *
 * The pure half of *Add to busk page*: what the menu offers, what it remembers, and which target it
 * pre-selects. Everything here is a function of the `buskPages` document, so none of it needs a
 * store, a component or a round trip.
 *
 * The gesture is an **append to a named bank**, never an insert at a position — the operator is
 * looking at a cue or a template, not at a layout, so "somewhere on that page" is the most they can
 * mean. The bank is addressed by **id** for the reason `AddBuskPadRequest` gives: an id survives any
 * reshuffle of the page that keeps the bank alive, where a row/column/bank index does not.
 */

/** What the picker is placing. Just enough to name it in a toast and find it on a page. */
export interface BuskAddRecord {
  kind: BuskPadKind
  id: number
  name: string
}

/** One bank, as a menu item. */
export interface BuskAddBank {
  bankId: number
  /** What the item reads. Never blank and never ambiguous — see [buskAddTargets]. */
  label: string
  padCount: number
  /** Whether this bank already holds a pad for the record being placed. Shown, never enforced. */
  holdsRecord: boolean
}

/** One page, as a menu section or submenu. */
export interface BuskAddPage {
  pageId: number
  pageName: string
  banks: BuskAddBank[]
}

/** A resolved choice: everything a toast and a mutation need. */
export interface BuskAddTarget {
  pageId: number
  pageName: string
  bankId: number
  bankLabel: string
}

function recordKey(kind: BuskPadKind, id: number): string {
  return `${kind.toLowerCase()}:${id}`
}

function padKey(pad: BuskPad): string | null {
  const id = padRecordId(pad)
  return id == null ? null : recordKey(pad.kind, id)
}

/**
 * Every page and bank the record could be appended to.
 *
 * Two rules the menu depends on, both about never showing an item that cannot work or cannot be
 * told apart:
 *
 * - **A bank with no `id` is skipped.** Ids and uuids are optional on the busk document's types
 *   because the same types are the busk view's local draft, so a bank can exist client-side before
 *   the server has minted its id — and a bank with no id cannot be appended to by id.
 * - **Labels are disambiguated.** A bank's identity is its place, not its name (`DaoBuskBanks.name`
 *   "may repeat across banks"), so a page can legally hold two banks called the same thing. A
 *   repeat gets a positional suffix, so no two items on one page ever read alike. The blank-name
 *   arm is defensive rather than routine: every minting path names its bank (`newBank` requires
 *   one, `nextBankName` supplies it) and the server refuses a blank, so only a row no route wrote
 *   — an import, a hand-edited database — reaches it. It becomes its position.
 */
export function buskAddTargets(pages: BuskPage[], record: BuskAddRecord | null): BuskAddPage[] {
  // Null is the *create* case — the record does not exist yet, so nothing can already hold it. The
  // pages and banks are still the answer; only `holdsRecord` has nothing to say.
  const key = record == null ? null : recordKey(record.kind, record.id)
  return pages.map((page) => {
    const banks = page.rows
      .flatMap((row) => row.columns)
      .flatMap((column) => column.banks)
      .map((bank, index) => ({ bank, index }))
      .filter((entry) => entry.bank.id != null)
    const seen = new Map<string, number>()
    return {
      pageId: page.id,
      pageName: page.name,
      banks: banks.map(({ bank, index }) => {
        const base = bank.name.trim() === '' ? `Bank ${index + 1}` : bank.name.trim()
        const repeat = (seen.get(base) ?? 0) + 1
        seen.set(base, repeat)
        return {
          bankId: bank.id!,
          label: repeat === 1 ? base : `${base} · ${repeat}`,
          padCount: bank.pads.length,
          holdsRecord: key != null && bank.pads.some((pad) => padKey(pad) === key),
        }
      }),
    }
  })
}

/** Flatten to the choices themselves, in menu order. */
export function buskAddChoices(pages: BuskAddPage[]): BuskAddTarget[] {
  return pages.flatMap((page) =>
    page.banks.map((bank) => ({
      pageId: page.pageId,
      pageName: page.pageName,
      bankId: bank.bankId,
      bankLabel: bank.label,
    })),
  )
}

/**
 * The bank a placement last landed in, per project.
 *
 * Scoped by project, so switching show falls through to the fallback rather than resolving an id
 * belonging to another rig. The **page** is not stored: a bank id already names exactly one page,
 * and storing both invites them to disagree.
 *
 * A `createSyncStore` rather than raw `localStorage`, so the quota and private-mode guards, the
 * lazy read and the narrowing `parse` are the ones every other remembered preference on the desk
 * uses — a second hand-rolled storage path would miss any later hardening of that one.
 */
export const lastBuskAddTargetStore = createSyncStore<{ projectId: number; bankId: number } | null>({
  key: 'busk.lastAddTarget',
  fallback: null,
  parse: (parsed) => {
    if (parsed == null || typeof parsed !== 'object') return null
    const { projectId, bankId } = parsed as Record<string, unknown>
    if (!Number.isSafeInteger(projectId) || !Number.isSafeInteger(bankId)) return null
    return { projectId: projectId as number, bankId: bankId as number }
  },
})

export function rememberBuskAddTarget(projectId: number, bankId: number): void {
  lastBuskAddTargetStore.set({ projectId, bankId })
}

function readRememberedBankId(projectId: number): number | null {
  const stored = lastBuskAddTargetStore.getSnapshot()
  return stored != null && stored.projectId === projectId ? stored.bankId : null
}

/**
 * Which target a *create* sheet offers before the operator has chosen one.
 *
 * The remembered bank if it is still there, otherwise the first bank of the first page, otherwise
 * nothing — and nothing means the row is not rendered at all rather than rendered saying "—".
 * A remembered bank that has been deleted falls through silently: the operator is being offered a
 * convenience, and an error about it would be louder than the thing itself.
 */
export function defaultBuskAddTarget(projectId: number, pages: BuskAddPage[]): BuskAddTarget | null {
  const choices = buskAddChoices(pages)
  if (choices.length === 0) return null
  const remembered = readRememberedBankId(projectId)
  return choices.find((choice) => choice.bankId === remembered) ?? choices[0]
}

/** The `AddBuskPadRequest` body for a record — exactly one id set. */
export function buskAddBody(record: BuskAddRecord): {
  templateId?: number
  lookId?: number
  cueId?: number
} {
  if (record.kind === 'TEMPLATE') return { templateId: record.id }
  if (record.kind === 'LOOK') return { lookId: record.id }
  return { cueId: record.id }
}
