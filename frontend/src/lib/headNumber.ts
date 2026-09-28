/**
 * The patch list's **head numbers**: the operator's number for a head — what ChamSys MagicQ calls
 * a head number and other desks a fixture or channel number — carried across when a show is
 * migrated, and unique within a project (lighting7 `docs/fixtures-engineering.md` §"Head numbers").
 *
 * Numbering a selection is one gesture: type the first number and the selection counts up from it
 * in visible-row order, the way a typed address lands consecutively. Unlike the Key column there is
 * no write *order* to plan — the desk's bulk route judges uniqueness against the batch's final
 * state, so a renumber that swaps two heads is one atomic request. What is left to check here is
 * only what the desk would refuse, named before Apply: a number another head outside the batch
 * holds, and a run that would count past the ceiling.
 *
 * Pure, so `headNumber.test.ts` can state it.
 */
import { MAX_HEAD_NUMBER, MIN_HEAD_NUMBER } from '@/api/patchApi'

/** A head as the numbering sees it. */
export interface NumberedHead {
  id: number
  name: string
  headNumber: number | null
}

/**
 * A typed number: null for an empty draft (unnumber), the number when it is a whole number in
 * range, and `'invalid'` otherwise. Digits only — `12.0`, `1e3` and `+4` are not how anyone
 * writes a head number, and `Number()` would take all three.
 */
export function parseHeadNumberDraft(draft: string): number | null | 'invalid' {
  const text = draft.trim()
  if (text === '') return null
  if (!/^\d+$/.test(text)) return 'invalid'
  const n = Number(text)
  return n >= MIN_HEAD_NUMBER && n <= MAX_HEAD_NUMBER ? n : 'invalid'
}

/** The reason a draft is refused, or null — the Head cell's `validate`. */
export function headNumberDraftError(draft: string): string | null {
  return parseHeadNumberDraft(draft) === 'invalid'
    ? `A head number is a whole number, ${MIN_HEAD_NUMBER}–${MAX_HEAD_NUMBER}`
    : null
}

/** The numbers a typed start lands on over `batch`, in visible-row order: `start`, `start + 1`, … */
export function consecutiveHeadNumbers(batch: readonly NumberedHead[], start: number): Map<number, number> {
  return new Map(batch.map((head, i) => [head.id, start + i]))
}

/** The preview of a renumber: one line per head, and the first problem with the run. */
export interface HeadNumberLanding {
  /** `Front PAR → 12`, one per head, drawn one to a line; empty for a single head. */
  lines: string[]
  /** Names the head holding a number, or the ceiling; null when the run lands clear. */
  error: string | null
  /** Every batch head's new number, in batch order — **null exactly when [error] is set**. */
  numbers: Map<number, number> | null
}

/**
 * Check `start` counted over `batch` against every head on the project. A number a **batch member**
 * holds is fine — the batch moves together — so only a head outside it can refuse the run.
 */
export function checkHeadNumberLanding(
  batch: readonly NumberedHead[],
  start: number,
  all: readonly NumberedHead[],
): HeadNumberLanding {
  const numbers = consecutiveHeadNumbers(batch, start)
  const lines = batch.length > 1 ? batch.map((head) => `${head.name} → ${numbers.get(head.id)}`) : []
  const last = start + batch.length - 1
  if (last > MAX_HEAD_NUMBER) {
    return { lines, error: `That run ends at ${last} — the highest head number is ${MAX_HEAD_NUMBER}`, numbers: null }
  }
  const inBatch = new Set(batch.map((head) => head.id))
  const holderOf = new Map<number, NumberedHead>()
  for (const head of all) {
    if (!inBatch.has(head.id) && head.headNumber != null && !holderOf.has(head.headNumber)) {
      holderOf.set(head.headNumber, head)
    }
  }
  for (const head of batch) {
    const n = numbers.get(head.id)!
    const holder = holderOf.get(n)
    if (holder) return { lines, error: `Head ${n} is already ${holder.name}`, numbers: null }
  }
  return { lines, error: null, numbers }
}

/**
 * Heads sharing a number, each mapped to one other head that holds it. The desk refuses a shared
 * number on every write, but a sync merge of two peers' renumbers imports as it stands — so the
 * patch list rings the cell and names the other head rather than trusting the invariant.
 */
export function findHeadNumberClashes(all: readonly NumberedHead[]): Map<number, NumberedHead> {
  const byNumber = new Map<number, NumberedHead[]>()
  for (const head of all) {
    if (head.headNumber == null) continue
    const list = byNumber.get(head.headNumber)
    if (list) list.push(head)
    else byNumber.set(head.headNumber, [head])
  }
  const clashes = new Map<number, NumberedHead>()
  for (const heads of byNumber.values()) {
    if (heads.length < 2) continue
    for (const head of heads) clashes.set(head.id, heads.find((other) => other.id !== head.id)!)
  }
  return clashes
}

/**
 * The single-head form's check — the add and edit sheets: the draft's problem, or a number another
 * head (`exceptId` is the head being edited) already holds, named. Null when it is clear to save.
 */
export function headNumberFieldError(
  draft: string,
  all: readonly { id: number; displayName: string; headNumber?: number | null }[],
  exceptId: number | null,
): string | null {
  const parsed = parseHeadNumberDraft(draft)
  if (parsed === 'invalid') return headNumberDraftError(draft)
  if (parsed === null) return null
  const holder = all.find((patch) => patch.headNumber === parsed && patch.id !== exceptId)
  return holder ? `Head ${parsed} is already ${holder.displayName}` : null
}
