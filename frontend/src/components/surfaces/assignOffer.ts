import type {
  CatalogueEntry,
  ControlKind,
  SurfaceBinding,
  SurfaceSubject,
} from '@/store/surfaces'
import { doesFor, entriesFor, subjectKindOf } from './BindingForm'

/**
 * What *Assign to surface…* offers (surface-setup plan D12 D, §4): the functions that act on a record
 * — its kind among an entry's subjects, or a parameter naming a record of its kind (a palette's
 * press) — narrowed to that record. Pure, over the desk's catalogue; what an assign would replace is
 * the desk's (`GET …/surface-bindings/slot/preview`).
 */

/** A record, group or fixture a door assigns. */
export type AssignSubject = Extract<SurfaceSubject, { type: 'group' } | { type: 'fixture' } | { type: 'record' }>

/** A catalogue entry narrowed to one subject: it acts on that subject alone, or names it in a parameter. */
export interface FitEntry {
  entry: CatalogueEntry
  subject: AssignSubject
}

/**
 * The entries [input] reaches that fit any of [subjects], each narrowed: an entry acting on the
 * subject's kind takes that kind only; one naming a record of the kind in a parameter has the record
 * as that parameter's default. In catalogue order, but a programmer verb on a record (Record into a
 * stack) after what plays it, so a stack's door starts on GO.
 */
export function fitEntries(catalogue: readonly CatalogueEntry[], subjects: readonly AssignSubject[], input: ControlKind): FitEntry[] {
  const out: FitEntry[] = []
  for (const entry of entriesFor(catalogue, input)) {
    for (const subject of subjects) {
      const kind = subjectKindOf(subject)
      if (entry.subjects.includes(kind)) {
        const filled = fillRecordParams(entry, subjects)
        if (filled != null) out.push({ entry: { ...filled, subjects: [kind] }, subject })
        break
      }
      if (subject.type !== 'record') continue
      const named = entry.params.find((param) => param.kind === 'RECORD' && param.record === subject.kind)
      if (named != null) {
        out.push({
          entry: { ...entry, params: entry.params.map((param) => (param === named ? { ...param, default: subject.uuid } : param)) },
          subject,
        })
        break
      }
    }
  }
  const later = (fit: FitEntry) => (fit.subject.type === 'record' && fit.entry.group === PROGRAMMER_GROUP ? 1 : 0)
  return out.map((fit, index) => ({ fit, index })).sort((a, b) => later(a.fit) - later(b.fit) || a.index - b.index).map(({ fit }) => fit)
}

/**
 * [entry] with each required record parameter defaulted to the target's own record of that kind;
 * null when one names a kind the target is not, which the sheet cannot fill.
 */
function fillRecordParams(entry: CatalogueEntry, subjects: readonly AssignSubject[]): CatalogueEntry | null {
  let params = entry.params
  for (const param of entry.params) {
    if (param.kind !== 'RECORD' || !param.required) continue
    const own = subjects.find((s): s is Extract<AssignSubject, { type: 'record' }> => s.type === 'record' && s.kind === param.record)
    if (own == null) return null
    params = params.map((p) => (p === param ? { ...p, default: own.uuid } : p))
  }
  return params === entry.params ? entry : { ...entry, params }
}

/** The catalogue's heading for the programmer's verbs. */
const PROGRAMMER_GROUP = 'Programmer'

/** A fresh binding of [fit]: its function at its defaults, on the subject where it takes one. */
export function initialBinding(fit: FitEntry): SurfaceBinding {
  const does = doesFor(fit.entry)
  const kinds = fit.entry.subjects
  if (kinds.includes(subjectKindOf(fit.subject))) return { does, on: fit.subject }
  if (kinds.includes('NONE')) return { does }
  if (kinds.includes('SELECTION')) return { does, on: { type: 'selection' } }
  return { does }
}
