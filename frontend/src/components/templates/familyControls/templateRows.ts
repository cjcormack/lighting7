import type { AttributeFamily } from '@/lib/attributeFamily'
import {
  parseTemplateIntent,
  serializeTemplateIntent,
  templatePropertiesForFamily,
  type TemplateIntent,
  type WhitePolicy,
} from '@/lib/templateIntent'
import { DEFERRED_TARGET_TYPE, type TemplateRow, type TemplateSummary } from '@/api/templatesApi'

/**
 * A generic value template's draft — one intent per property, keyed by property name.
 *
 * A map rather than one value, because a family can hold more than one property: intensity is a
 * level *and* a strobe, and beam is four continuous roles plus prism. Only the entries present are
 * written, so an untouched property is absent from the template rather than stored at zero.
 */
export type TemplateValues = Record<string, TemplateIntent>

/**
 * **The rows half of the family controls** (library-sheets plan D6, session 3): the rules that turn
 * what `FamilyControls` edits into the rows a template stores, and back. Lifted out of
 * `TemplateEditor` together with the controls, so the editor and the sheet's Value cell draw one
 * control and write one grammar — two hosts, one set of rules. `lib/templateIntent.ts` serialises
 * and parses; nothing here resolves an intent against a head.
 */

/** A generic template's stored rows, parsed back into the draft the controls edit. */
export function seedValues(template: TemplateSummary | null): TemplateValues {
  if (template == null || !template.isGeneric) return {}
  const out: TemplateValues = {}
  for (const row of template.rows ?? []) {
    const intent = parseTemplateIntent(row.value)
    if (intent != null) out[row.propertyName] = intent
  }
  return out
}

/** The draft with one property set, or removed where [intent] is null. Same object when nothing moves. */
export function withIntent(
  values: TemplateValues,
  propertyName: string,
  intent: TemplateIntent | null,
): TemplateValues {
  if (intent == null) {
    if (values[propertyName] == null) return values
    const next = { ...values }
    delete next[propertyName]
    return next
  }
  return { ...values, [propertyName]: intent }
}

/**
 * What an edit changed: every property whose intent differs between [before] and [after], mapped to
 * its new intent, or null where it was removed. Compared through the serialiser, so two spellings of
 * one intent are no change.
 *
 * This is what a **batch** takes rather than the draft itself. A template's rows are replaced whole
 * on the wire, so handing a sibling the origin's draft would delete every property the sibling holds
 * and the origin does not — its strobe, its white, another beam role — on an Enter that changed
 * nothing. A property the operator did not touch is left as each template has it (the colour
 * editor's rule for an emitter it does not hold, CLAUDE.md §The editor kit).
 */
export function valueChanges(before: TemplateValues, after: TemplateValues): Record<string, TemplateIntent | null> {
  const out: Record<string, TemplateIntent | null> = {}
  for (const name of new Set([...Object.keys(before), ...Object.keys(after)])) {
    const a = before[name]
    const b = after[name]
    const same = a == null || b == null ? a == null && b == null : serializeTemplateIntent(a) === serializeTemplateIntent(b)
    if (!same) out[name] = b ?? null
  }
  return out
}

/** [values] with [changes] applied — a set where the change names an intent, a removal where null. */
export function applyValueChanges(
  values: TemplateValues,
  changes: Record<string, TemplateIntent | null>,
): TemplateValues {
  return Object.entries(changes).reduce((acc, [name, intent]) => withIntent(acc, name, intent), values)
}

/** Does this draft hold nothing a template of [family] would store? The builder's emptiness, unbuilt. */
export function templateValuesEmpty(family: AttributeFamily, values: TemplateValues): boolean {
  return templatePropertiesForFamily(family).every((p) => values[p.propertyName] == null)
}

/**
 * Does this draft set an emitter that a white/amber policy would otherwise derive?
 *
 * UV is deliberately not counted: `mixColour` routes the neutral to white or amber and has never
 * touched UV, so a `uv` row conflicts with nothing.
 */
export function colourPolicyLocked(values: TemplateValues): boolean {
  return values.white != null || values.amber != null
}

/**
 * The colour policy this draft actually asserts, given its emitter rows.
 *
 * **Derived at every read rather than written into `values` when an emitter is set.** The draft has
 * two writers — the controls' `onChange` and `seedValues`, which parses stored rows straight in — so
 * a rule enforced in only the first is a rule with a hole: a stored template carrying both an
 * explicit `white` row and `policy=extract` (a hand-edited row, or some future write path) would
 * open showing Extract selected *and* disabled, and save that same refused combination straight
 * back. One reader means the buttons, the saved rows and the resolves-to panel cannot disagree.
 */
export function effectiveColourPolicy(values: TemplateValues): WhitePolicy {
  const colour = values.rgbColour
  // **Two different "no policy", and they take opposite defaults.** A *stored* row with no policy
  // token parses as `rgbonly`, matching every other reader of that string — so it must display as
  // RGB only. A draft with no colour row at all has not been authored yet, and there `extract` is
  // the default a wash wants (the same one `TemplateIntent.Colour`'s Kotlin constructor takes).
  // Collapsing the two made every new colour template default to RGB only.
  const stored: WhitePolicy = colour?.kind === 'colour' ? colour.policy : 'extract'
  return colourPolicyLocked(values) ? 'rgbonly' : stored
}

/**
 * The rows a generic value template of [family] stores for this draft — the one rows builder, read
 * by the editor's save and resolves-to panel and by the sheet's Value cell.
 *
 * **The rgbonly rule is applied here, to the saved rows**: an explicit `white` or `amber` row forces
 * the colour row's policy, through the same derivation `ColourControl` only *reads* for display — see
 * [effectiveColourPolicy]. A host that built rows any other way could send the combination the write
 * boundary refuses by name.
 *
 * Ordered by the family's declared properties, never by the draft's key order, so two hosts building
 * rows from one draft build the same list.
 */
export function templateRowsFromValues(family: AttributeFamily, values: TemplateValues): TemplateRow[] {
  const policy = effectiveColourPolicy(values)
  return templatePropertiesForFamily(family)
    .filter((p) => values[p.propertyName] != null)
    .map((p, index) => {
      const intent = values[p.propertyName]
      return {
        targetType: DEFERRED_TARGET_TYPE,
        targetKey: '',
        propertyName: p.propertyName,
        value: serializeTemplateIntent(intent.kind === 'colour' ? { ...intent, policy } : intent),
        sortOrder: index,
      }
    })
}

/**
 * Two row lists' identity — the fields a save actually writes, in order. The editor's dirty check
 * and the Value cell's no-op test both read it.
 */
export function templateRowsKey(rows: readonly TemplateRow[]): string {
  return JSON.stringify(rows.map((row) => [row.targetType, row.targetKey, row.propertyName, row.value]))
}
